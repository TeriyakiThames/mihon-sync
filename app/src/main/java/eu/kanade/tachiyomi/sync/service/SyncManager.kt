package eu.kanade.tachiyomi.sync.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.sync.crypto.CryptoUtil
import eu.kanade.tachiyomi.sync.data.SyncDiffEngine
import eu.kanade.tachiyomi.sync.data.SyncMerger
import eu.kanade.tachiyomi.sync.data.SyncPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat

/**
 * Coordinates end-to-end synchronization workflow:
 * 1. Pull remote updates -> Decrypt with AES-GCM -> Merge into local database
 * 2. Extract local diff -> Encrypt with AES-GCM -> Push to backend server
 *
 * Provides thread-safe execution via Mutex and debounces rapid trigger requests.
 */
@Inject
@SingleIn(AppScope::class)
class SyncManager(
    private val syncPreferences: SyncPreferences,
    private val diffEngine: SyncDiffEngine,
    private val merger: SyncMerger,
    private val apiClient: SyncApiClient,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncMutex = Mutex()
    private var debounceJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * Checks if synchronization is enabled and properly configured.
     */
    fun isSyncEnabled(): Boolean {
        return syncPreferences.isSyncEnabled.get() && syncPreferences.isConfigured()
    }

    /**
     * Schedules a debounced background sync operation (default debounce delay: 1000ms).
     * Ideal for lifecycle events (leaving reader, changing chapters, backgrounding app).
     */
    fun triggerSync(debounceDelayMs: Long = 1000L) {
        if (!isSyncEnabled()) return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceDelayMs)
            syncNow()
        }
    }

    /**
     * Executes a full synchronization cycle:
     * Step 1 (Pull): Pull remote encrypted batches -> Decrypt -> Merge with LWW & forward progress.
     * Step 2 (Push): Extract local diff since last sync -> Encrypt -> Push to server.
     *
     * @param force If true, ignores isSyncEnabled() check (useful for manual "Sync Now" button).
     * @return true if sync cycle completed successfully, false otherwise.
     */
    suspend fun syncNow(force: Boolean = false): Boolean {
        if (!force && !isSyncEnabled()) return false
        if (!syncPreferences.isConfigured()) return false

        return syncMutex.withLock {
            try {
                val encryptionKey = syncPreferences.encryptionKey.get()
                val lastSyncTime = syncPreferences.lastSyncTimestamp.get()
                val currentDevice = syncPreferences.deviceId.get()

                // Phase 1: Pull and apply remote updates
                val pullResponse = apiClient.pullUpdates(sinceTimestamp = lastSyncTime)
                var maxObservedTimestamp = lastSyncTime

                for (update in pullResponse.updates) {
                    if (update.timestamp > maxObservedTimestamp) {
                        maxObservedTimestamp = update.timestamp
                    }

                    // Skip self-authored updates if deviceId is tagged
                    if (!update.deviceId.isNullOrBlank() && update.deviceId == currentDevice) {
                        continue
                    }

                    try {
                        val decryptedJson = CryptoUtil.decryptString(update.payload, encryptionKey)
                        val remotePayload = json.decodeFromString<SyncPayload>(decryptedJson)
                        merger.merge(remotePayload)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to decrypt or merge sync update ${update.id}" }
                    }
                }

                // Phase 2: Diff local changes and push
                val localDiff = diffEngine.extractDiff(sinceTimestampMillis = lastSyncTime)
                val hasChanges = localDiff.chapters.isNotEmpty() ||
                    localDiff.history.isNotEmpty() ||
                    localDiff.mangas.isNotEmpty()

                if (hasChanges) {
                    val localPayloadJson = json.encodeToString(localDiff)
                    val encryptedPayload = CryptoUtil.encryptString(localPayloadJson, encryptionKey)
                    val pushTimestamp = System.currentTimeMillis()

                    val pushResponse = apiClient.pushUpdate(encryptedPayload, pushTimestamp)
                    val serverTimestamp = pushResponse.timestamp ?: pushTimestamp
                    if (serverTimestamp > maxObservedTimestamp) {
                        maxObservedTimestamp = serverTimestamp
                    }
                }

                // Phase 3: Update watermark timestamp
                if (maxObservedTimestamp > lastSyncTime) {
                    syncPreferences.lastSyncTimestamp.set(maxObservedTimestamp)
                }

                logcat(LogPriority.INFO) { "Sync cycle completed successfully. Watermark: $maxObservedTimestamp" }
                true
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync failed with error: ${e.message}" }
                false
            }
        }
    }
}

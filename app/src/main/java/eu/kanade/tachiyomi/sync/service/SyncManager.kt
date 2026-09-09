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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

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
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    private var debounceJob: Job? = null
    var lastSyncCompletedTimestamp: Long = 0L
        internal set

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

    private var hasRunInitialPull: Boolean = false

    /**
     * Resets the initial pull state, allowing triggerInitialPull to execute on next entry.
     */
    fun resetInitialPullState() {
        hasRunInitialPull = false
    }

    /**
     * Schedules a debounced background sync operation.
     * Retained for backward compatibility.
     */
    fun triggerSync(debounceDelayMs: Long = 1000L) {
        triggerPush(debounceDelayMs = debounceDelayMs)
    }

    /**
     * Triggers initial synchronization when the app is first opened in a session:
     * Pulls remote updates and reconciles local state, then pushes any pending offline modifications.
     * Subsequent calls within the same process session are no-ops.
     */
    fun triggerInitialPull() {
        if (!isSyncEnabled()) return
        if (hasRunInitialPull) return
        hasRunInitialPull = true

        scope.launch {
            pushToOrigin()
        }
    }

    /**
     * Triggers a pull from origin on demand.
     * If sync completed within [skipIfRecentMs], skips to avoid redundant network calls.
     */
    fun triggerPull(skipIfRecentMs: Long = 3000L) {
        if (!isSyncEnabled()) return
        val now = System.currentTimeMillis()
        if (now - lastSyncCompletedTimestamp < skipIfRecentMs) return

        scope.launch {
            pullFromOrigin()
        }
    }

    /**
     * Triggers a push to origin on pause or reader exit.
     * If [debounceDelayMs] is 0L, runs immediately and cancels any pending debounce.
     */
    fun triggerPush(debounceDelayMs: Long = 0L) {
        if (!isSyncEnabled()) return

        debounceJob?.cancel()
        if (debounceDelayMs <= 0L) {
            scope.launch {
                pushToOrigin()
            }
        } else {
            debounceJob = scope.launch {
                delay(debounceDelayMs)
                pushToOrigin()
            }
        }
    }

    /**
     * Pulls remote updates from backend origin and merges them into the local database.
     * Advances `lastPullTimestamp` and updates `lastSyncTimestamp`.
     */
    suspend fun pullFromOrigin(force: Boolean = false): Boolean {
        if (!force && !isSyncEnabled()) return false
        if (!syncPreferences.isConfigured()) return false

        return syncMutex.withLock {
            _isSyncing.value = true
            try {
                internalPull()
                lastSyncCompletedTimestamp = System.currentTimeMillis()
                true
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Pull from origin failed: ${e.message}" }
                false
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Rebases with remote origin (pulls first) and then pushes local diffs.
     * Advances `lastPushTimestamp` and updates `lastSyncTimestamp`.
     */
    suspend fun pushToOrigin(force: Boolean = false): Boolean {
        if (!force && !isSyncEnabled()) return false
        if (!syncPreferences.isConfigured()) return false

        return syncMutex.withLock {
            _isSyncing.value = true
            try {
                // Rebase: pull remote updates first to reconcile state
                internalPull()

                val encryptionKey = syncPreferences.encryptionKey.get()
                val currentPushWatermark = getEffectivePushWatermark()
                val localDiff = diffEngine.extractDiff(sinceTimestampMillis = currentPushWatermark)

                val hasChanges = localDiff.chapters.isNotEmpty() ||
                    localDiff.history.isNotEmpty() ||
                    localDiff.mangas.isNotEmpty()

                if (hasChanges) {
                    val localPayloadJson = json.encodeToString(localDiff)
                    val encryptedPayload = CryptoUtil.encryptString(localPayloadJson, encryptionKey)
                    val pushTimestamp = System.currentTimeMillis()

                    val pushResponse = apiClient.pushUpdate(encryptedPayload, pushTimestamp)
                    val serverTimestamp = pushResponse.timestamp ?: pushTimestamp

                    // Clear outbox dirty flags for the successfully pushed changes
                    diffEngine.clearLastExtractedDirty()

                    syncPreferences.lastPushTimestamp.set(serverTimestamp)
                    updateSyncTimestamp(serverTimestamp)
                    logcat(LogPriority.INFO) { "Pushed local modifications successfully. Watermark: $serverTimestamp" }
                }

                lastSyncCompletedTimestamp = System.currentTimeMillis()
                true
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Push to origin failed: ${e.message}" }
                false
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Executes a full synchronization cycle:
     * Pulls remote updates -> Reconciles -> Pushes local diffs.
     *
     * @param force If true, ignores isSyncEnabled() check (useful for manual "Sync Now" button).
     * @return true if sync cycle completed successfully, false otherwise.
     */
    suspend fun syncNow(force: Boolean = false): Boolean {
        return pushToOrigin(force = force)
    }

    /**
     * Core pull logic executed under `syncMutex`.
     */
    private suspend fun internalPull() {
        val encryptionKey = syncPreferences.encryptionKey.get()
        val currentDevice = syncPreferences.deviceId.get()
        val pullWatermark = getEffectivePullWatermark()

        var currentPullSince = pullWatermark
        var hasMoreToPull = true
        var maxObservedTimestamp = pullWatermark
        var serverRequestedSnapshot = false

        while (hasMoreToPull) {
            val pullResponse = apiClient.pullUpdates(sinceTimestamp = currentPullSince)
            if (pullResponse.needsSnapshot) {
                serverRequestedSnapshot = true
            }

            // Merge snapshot first if present
            val snapshot = pullResponse.snapshot
            if (snapshot != null) {
                if (snapshot.timestamp > maxObservedTimestamp) {
                    maxObservedTimestamp = snapshot.timestamp
                }
                try {
                    val decryptedSnapshotJson = CryptoUtil.decryptString(snapshot.payload, encryptionKey)
                    val snapshotPayload = json.decodeFromString<SyncPayload>(decryptedSnapshotJson)
                    merger.merge(snapshotPayload)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to decrypt or merge snapshot ${snapshot.id}" }
                    throw e
                }
            }

            // Merge incremental updates
            for (update in pullResponse.updates) {
                if (update.timestamp > maxObservedTimestamp) {
                    maxObservedTimestamp = update.timestamp
                }

                if (!update.deviceId.isNullOrBlank() && update.deviceId == currentDevice) {
                    continue
                }

                try {
                    val decryptedJson = CryptoUtil.decryptString(update.payload, encryptionKey)
                    val remotePayload = json.decodeFromString<SyncPayload>(decryptedJson)
                    merger.merge(remotePayload)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to decrypt or merge sync update ${update.id}" }
                    throw e
                }
            }

            // Commit pull watermark after successfully merged batch
            if (maxObservedTimestamp > syncPreferences.lastPullTimestamp.get()) {
                syncPreferences.lastPullTimestamp.set(maxObservedTimestamp)
                updateSyncTimestamp(maxObservedTimestamp)
            }

            if (pullResponse.hasMore && pullResponse.nextSince != null && pullResponse.nextSince > currentPullSince) {
                currentPullSince = pullResponse.nextSince
            } else if (pullResponse.hasMore && maxObservedTimestamp > currentPullSince) {
                currentPullSince = maxObservedTimestamp
            } else {
                hasMoreToPull = false
            }
        }

        // Opportunistic snapshot push if requested by server
        if (serverRequestedSnapshot) {
            try {
                val fullSnapshotDiff = diffEngine.extractDiff(sinceTimestampMillis = 0L)
                val fullSnapshotJson = json.encodeToString(fullSnapshotDiff)
                val encryptedSnapshot = CryptoUtil.encryptString(fullSnapshotJson, encryptionKey)
                val snapshotTimestamp = System.currentTimeMillis()
                val snapshotResponse = apiClient.pushSnapshot(encryptedSnapshot, snapshotTimestamp)
                val serverSnapshotTs = snapshotResponse.timestamp ?: snapshotTimestamp
                diffEngine.clearLastExtractedDirty()
                if (serverSnapshotTs > maxObservedTimestamp) {
                    maxObservedTimestamp = serverSnapshotTs
                    syncPreferences.lastPullTimestamp.set(maxObservedTimestamp)
                    syncPreferences.lastPushTimestamp.set(maxObservedTimestamp)
                    updateSyncTimestamp(maxObservedTimestamp)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to push opportunistic snapshot: ${e.message}" }
            }
        }
    }

    private fun getEffectivePullWatermark(): Long {
        val pullTs = syncPreferences.lastPullTimestamp.get()
        return if (pullTs > 0L) pullTs else syncPreferences.lastSyncTimestamp.get()
    }

    private fun getEffectivePushWatermark(): Long {
        val pushTs = syncPreferences.lastPushTimestamp.get()
        return if (pushTs > 0L) pushTs else syncPreferences.lastSyncTimestamp.get()
    }

    private fun updateSyncTimestamp(timestamp: Long) {
        if (timestamp > syncPreferences.lastSyncTimestamp.get()) {
            syncPreferences.lastSyncTimestamp.set(timestamp)
        }
    }
}

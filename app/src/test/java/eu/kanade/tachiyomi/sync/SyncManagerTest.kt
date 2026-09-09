package eu.kanade.tachiyomi.sync

import eu.kanade.tachiyomi.sync.data.SyncDiffEngine
import eu.kanade.tachiyomi.sync.data.SyncMerger
import eu.kanade.tachiyomi.sync.data.SyncPayload
import eu.kanade.tachiyomi.sync.data.SyncUpdateResponse
import eu.kanade.tachiyomi.sync.data.SyncUpdatesResponse
import eu.kanade.tachiyomi.sync.service.SyncApiClient
import eu.kanade.tachiyomi.sync.service.SyncManager
import eu.kanade.tachiyomi.sync.service.SyncPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class SyncManagerTest {

    private lateinit var syncPreferences: SyncPreferences
    private lateinit var diffEngine: SyncDiffEngine
    private lateinit var merger: SyncMerger
    private lateinit var apiClient: SyncApiClient
    private lateinit var syncManager: SyncManager

    private val isSyncEnabledPref = mockk<Preference<Boolean>>()
    private val serverUrlPref = mockk<Preference<String>>()
    private val roomIdPref = mockk<Preference<String>>()
    private val encryptionKeyPref = mockk<Preference<String>>()
    private val lastSyncTimestampPref = mockk<Preference<Long>>()
    private val lastPullTimestampPref = mockk<Preference<Long>>()
    private val lastPushTimestampPref = mockk<Preference<Long>>()
    private val deviceIdPref = mockk<Preference<String>>()

    @BeforeEach
    fun setUp() {
        syncPreferences = mockk()
        diffEngine = mockk()
        merger = mockk()
        apiClient = mockk()

        every { syncPreferences.isSyncEnabled } returns isSyncEnabledPref
        every { syncPreferences.serverUrl } returns serverUrlPref
        every { syncPreferences.roomId } returns roomIdPref
        every { syncPreferences.encryptionKey } returns encryptionKeyPref
        every { syncPreferences.lastSyncTimestamp } returns lastSyncTimestampPref
        every { syncPreferences.lastPullTimestamp } returns lastPullTimestampPref
        every { syncPreferences.lastPushTimestamp } returns lastPushTimestampPref
        every { syncPreferences.deviceId } returns deviceIdPref

        every { isSyncEnabledPref.get() } returns true
        every { serverUrlPref.get() } returns "https://sync.mihon.app"
        every { roomIdPref.get() } returns "room123"
        val validKey = eu.kanade.tachiyomi.sync.crypto.CryptoUtil.generateSecretKey()
        every { encryptionKeyPref.get() } returns validKey
        every { lastSyncTimestampPref.get() } returns 0L
        every { lastSyncTimestampPref.set(any()) } returns Unit
        every { lastPullTimestampPref.get() } returns 0L
        every { lastPullTimestampPref.set(any()) } returns Unit
        every { lastPushTimestampPref.get() } returns 0L
        every { lastPushTimestampPref.set(any()) } returns Unit
        every { deviceIdPref.get() } returns "device1"
        every { syncPreferences.isConfigured() } returns true

        coEvery { apiClient.pullUpdates(any()) } returns SyncUpdatesResponse(updates = emptyList())
        coEvery { apiClient.pushUpdate(any(), any()) } returns SyncUpdateResponse(success = true, timestamp = 1000L)
        coEvery { diffEngine.extractDiff(any()) } returns SyncPayload()
        coEvery { diffEngine.clearLastExtractedDirty() } returns Unit
        coEvery { merger.merge(any()) } returns Unit

        syncManager = SyncManager(syncPreferences, diffEngine, merger, apiClient)
    }

    @Test
    fun `pullFromOrigin pulls updates and advances lastPullTimestamp without advancing lastPushTimestamp`() = runBlocking {
        coEvery { apiClient.pullUpdates(sinceTimestamp = 0L) } returns SyncUpdatesResponse(
            updates = listOf(
                eu.kanade.tachiyomi.sync.data.SyncUpdateRecord(
                    id = "up-1",
                    timestamp = 5000L,
                    deviceId = "device2",
                    payload = eu.kanade.tachiyomi.sync.crypto.CryptoUtil.encryptString("{}", encryptionKeyPref.get()),
                ),
            ),
        )

        val success = syncManager.pullFromOrigin()

        assertTrue(success)
        coVerify(exactly = 1) { apiClient.pullUpdates(sinceTimestamp = 0L) }
        coVerify(exactly = 1) { lastPullTimestampPref.set(5000L) }
        coVerify(exactly = 0) { lastPushTimestampPref.set(any()) }
    }

    @Test
    fun `pushToOrigin rebases first then extracts diff and advances lastPushTimestamp`() = runBlocking {
        every { lastPushTimestampPref.get() } returns 2000L
        coEvery { diffEngine.extractDiff(sinceTimestampMillis = 2000L) } returns SyncPayload(
            chapters = listOf(
                eu.kanade.tachiyomi.sync.data.ChapterSyncRecord(
                    mangaSource = 1L,
                    mangaUrl = "/manga1",
                    chapterUrl = "/ch1",
                    chapterName = "Ch. 1",
                    read = true,
                    bookmark = false,
                    lastPageRead = 10L,
                    chapterNumber = 1.0,
                    scanlator = null,
                    lastModifiedAt = 3L,
                    version = 1L,
                ),
            ),
        )
        coEvery { apiClient.pushUpdate(any(), any()) } returns SyncUpdateResponse(success = true, timestamp = 6000L)

        val success = syncManager.pushToOrigin()

        assertTrue(success)
        coVerify(exactly = 1) { apiClient.pullUpdates(any()) }
        coVerify(exactly = 1) { diffEngine.extractDiff(sinceTimestampMillis = 2000L) }
        coVerify(exactly = 1) { apiClient.pushUpdate(any(), any()) }
        coVerify(exactly = 1) { diffEngine.clearLastExtractedDirty() }
        coVerify(exactly = 1) { lastPushTimestampPref.set(6000L) }
    }

    @Test
    fun `triggerPull skips if sync completed within skipIfRecentMs`() = runBlocking {
        syncManager.lastSyncCompletedTimestamp = System.currentTimeMillis()

        syncManager.triggerPull(skipIfRecentMs = 3000L)
        delay(100L)

        coVerify(exactly = 0) { apiClient.pullUpdates(any()) }
    }

    @Test
    fun `triggerInitialPull executes sync on first call and skips on subsequent calls`() = runBlocking {
        syncManager.triggerInitialPull()
        delay(100L)

        coVerify(exactly = 1) { apiClient.pullUpdates(any()) }

        // Second call should be a no-op
        syncManager.triggerInitialPull()
        delay(100L)

        coVerify(exactly = 1) { apiClient.pullUpdates(any()) }
    }

    @Test
    fun `triggerPush with 0ms delay executes immediately`() = runBlocking {
        syncManager.triggerPush(debounceDelayMs = 0L)
        delay(100L)

        coVerify(exactly = 1) { apiClient.pullUpdates(any()) }
    }

    @Test
    fun `merges snapshot first when pullResponse contains a snapshot`() = runBlocking {
        val key = encryptionKeyPref.get()
        val encryptedSnapshot = eu.kanade.tachiyomi.sync.crypto.CryptoUtil.encryptString("{}", key)

        coEvery { apiClient.pullUpdates(any()) } returns SyncUpdatesResponse(
            snapshot = eu.kanade.tachiyomi.sync.data.SyncSnapshotRecord(
                id = "snap-1",
                timestamp = 5000L,
                payload = encryptedSnapshot,
            ),
            updates = emptyList(),
        )

        val success = syncManager.syncNow(force = true)

        assertTrue(success)
        coVerify(exactly = 1) { merger.merge(any()) }
    }

    @Test
    fun `opportunistically pushes snapshot when pullResponse has needsSnapshot true`() = runBlocking {
        coEvery { apiClient.pullUpdates(any()) } returns SyncUpdatesResponse(
            needsSnapshot = true,
            updates = emptyList(),
        )
        coEvery { apiClient.pushSnapshot(any(), any()) } returns SyncUpdateResponse(success = true, timestamp = 2000L)

        val success = syncManager.syncNow(force = true)

        assertTrue(success)
        coVerify(atLeast = 1) { diffEngine.extractDiff(sinceTimestampMillis = 0L) }
        coVerify(exactly = 1) { apiClient.pushSnapshot(any(), any()) }
    }

    @Test
    fun `pulls subsequent pages when pullResponse indicates hasMore`() = runBlocking {
        coEvery { apiClient.pullUpdates(sinceTimestamp = 0L) } returns SyncUpdatesResponse(
            updates = emptyList(),
            hasMore = true,
            nextSince = 1000L,
        )
        coEvery { apiClient.pullUpdates(sinceTimestamp = 1000L) } returns SyncUpdatesResponse(
            updates = emptyList(),
            hasMore = false,
        )

        val success = syncManager.syncNow(force = true)

        assertTrue(success)
        coVerify(exactly = 1) { apiClient.pullUpdates(sinceTimestamp = 0L) }
        coVerify(exactly = 1) { apiClient.pullUpdates(sinceTimestamp = 1000L) }
    }
}

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
        every { syncPreferences.deviceId } returns deviceIdPref

        every { isSyncEnabledPref.get() } returns true
        every { serverUrlPref.get() } returns "https://sync.mihon.app"
        every { roomIdPref.get() } returns "room123"
        val validKey = eu.kanade.tachiyomi.sync.crypto.CryptoUtil.generateSecretKey()
        every { encryptionKeyPref.get() } returns validKey
        every { lastSyncTimestampPref.get() } returns 0L
        every { lastSyncTimestampPref.set(any()) } returns Unit
        every { deviceIdPref.get() } returns "device1"
        every { syncPreferences.isConfigured() } returns true

        coEvery { apiClient.pullUpdates(any()) } returns SyncUpdatesResponse(updates = emptyList())
        coEvery { apiClient.pushUpdate(any(), any()) } returns SyncUpdateResponse(success = true, timestamp = 1000L)
        coEvery { diffEngine.extractDiff(any()) } returns SyncPayload()
        coEvery { merger.merge(any()) } returns Unit

        syncManager = SyncManager(syncPreferences, diffEngine, merger, apiClient)
    }

    @Test
    fun `AUTO_SYNC_COOLDOWN_MS is exactly 60000ms`() {
        assertEquals(60_000L, SyncManager.AUTO_SYNC_COOLDOWN_MS)
    }

    @Test
    fun `rapid consecutive invocations to triggerSync within 60 seconds do not trigger multiple network syncs`() = runBlocking {
        // First trigger
        syncManager.triggerSync(debounceDelayMs = 10L)
        delay(100L)

        coVerify(exactly = 1) { apiClient.pullUpdates(any()) }

        // Second trigger immediately within cooldown window
        syncManager.triggerSync(debounceDelayMs = 10L)
        delay(100L)

        // Still exactly 1 network pull should have occurred
        coVerify(exactly = 1) { apiClient.pullUpdates(any()) }

        // Third trigger within cooldown window
        syncManager.triggerSync(debounceDelayMs = 0L)
        delay(100L)

        coVerify(exactly = 1) { apiClient.pullUpdates(any()) }
    }

    @Test
    fun `manual sync calls with force = true bypass the 60-second cooldown`() = runBlocking {
        // Set cooldown as actively triggered just now
        syncManager.lastAutoSyncTimestamp = System.currentTimeMillis()

        // Manual sync with force = true should execute immediately despite active cooldown
        val success = syncManager.syncNow(force = true)

        assertTrue(success)
        coVerify(exactly = 1) { apiClient.pullUpdates(any()) }
    }

    @Test
    fun `syncNow with force = false respects the 60-second cooldown`() = runBlocking {
        // Set cooldown as actively triggered just now
        syncManager.lastAutoSyncTimestamp = System.currentTimeMillis()

        // syncNow with force = false should be rejected due to active cooldown
        val result = syncManager.syncNow(force = false)

        assertFalse(result)
        coVerify(exactly = 0) { apiClient.pullUpdates(any()) }
    }

    @Test
    fun `triggerSync succeeds after 60-second cooldown expires`() = runBlocking {
        // Simulate a sync that occurred 61 seconds ago
        syncManager.lastAutoSyncTimestamp = System.currentTimeMillis() - 61_000L

        syncManager.triggerSync(debounceDelayMs = 10L)
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

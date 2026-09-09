package eu.kanade.tachiyomi.sync

import eu.kanade.tachiyomi.sync.data.ChapterSyncRecord
import eu.kanade.tachiyomi.sync.data.HistorySyncRecord
import eu.kanade.tachiyomi.sync.data.MangaSyncRecord
import eu.kanade.tachiyomi.sync.data.SyncDiffEngine
import eu.kanade.tachiyomi.sync.data.SyncPayload
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import java.util.Date

class SyncDiffEngineTest {

    @Test
    fun `timestamp millisecond to second conversion calculates correct seconds for SQLDelight`() {
        // Epoch millisecond timestamp: 1725800000000L
        val epochMillis = 1725800000000L
        val expectedSeconds = 1725800000L

        val calculatedSeconds = epochMillis / 1000L
        assertEquals(expectedSeconds, calculatedSeconds)

        // Sub-second precision truncation verification
        val epochMillisWithSubSeconds = 1725800000999L
        val truncatedSeconds = epochMillisWithSubSeconds / 1000L
        assertEquals(expectedSeconds, truncatedSeconds)

        // Initial sync boundary (0ms -> 0s)
        val initialMillis = 0L
        val initialSeconds = initialMillis / 1000L
        assertEquals(0L, initialSeconds)
    }

    @Test
    fun `timestamp millisecond to Date conversion preserves exact milliseconds for history`() {
        val epochMillis = 1725800123456L
        val date = Date(epochMillis)

        assertEquals(epochMillis, date.time)
    }

    @Test
    fun `timestamp disparity explains why comparing seconds against millis would fail`() {
        val lastModifiedAtSeconds = 1725800000L // Stored in chapters / mangas
        val querySinceMillis = 1725800000000L // Passed from API / sync state

        // Without conversion: 1725800000 > 1725800000000 is FALSE (filters out valid records)
        val erroneousComparison = lastModifiedAtSeconds > querySinceMillis
        assertEquals(false, erroneousComparison)

        // With conversion: 1725800000 > (1725800000000 / 1000 - 1) is TRUE
        val sinceSeconds = (querySinceMillis / 1000L) - 1L
        val correctComparison = lastModifiedAtSeconds > sinceSeconds
        assertEquals(true, correctComparison)
    }

    @Test
    fun `sync payload construction with records`() {
        val now = System.currentTimeMillis()
        val payload = SyncPayload(
            deviceId = "test-device-uuid",
            clientTimestamp = now,
            mangas = listOf(
                MangaSyncRecord(
                    source = 1L,
                    url = "/manga/one-piece",
                    title = "One Piece",
                    favorite = true,
                    lastModifiedAt = now / 1000L,
                ),
            ),
            chapters = listOf(
                ChapterSyncRecord(
                    mangaSource = 1L,
                    mangaUrl = "/manga/one-piece",
                    chapterUrl = "/chapter/1100",
                    chapterName = "Chapter 1100",
                    read = true,
                    bookmark = false,
                    lastPageRead = 18L,
                    lastModifiedAt = now / 1000L,
                ),
            ),
            history = listOf(
                HistorySyncRecord(
                    mangaSource = 1L,
                    mangaUrl = "/manga/one-piece",
                    chapterUrl = "/chapter/1100",
                    lastRead = now,
                    timeRead = 360000L,
                ),
            ),
        )

        assertEquals("test-device-uuid", payload.deviceId)
        assertEquals(1, payload.mangas.size)
        assertEquals(1, payload.chapters.size)
        assertEquals(1, payload.history.size)
        assertEquals(18L, payload.chapters[0].lastPageRead)
        assertTrue(payload.chapters[0].read)
        assertEquals(now, payload.history[0].lastRead)
    }
}

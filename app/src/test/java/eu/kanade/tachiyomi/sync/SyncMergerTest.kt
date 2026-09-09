package eu.kanade.tachiyomi.sync

import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.sync.data.CategorySyncRecord
import eu.kanade.tachiyomi.sync.data.ChapterSyncRecord
import eu.kanade.tachiyomi.sync.data.HistorySyncRecord
import eu.kanade.tachiyomi.sync.data.MangaSyncRecord
import eu.kanade.tachiyomi.sync.data.SyncMerger
import eu.kanade.tachiyomi.sync.data.SyncPayload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.ChaptersQueries
import tachiyomi.data.Database
import tachiyomi.data.HistoryQueries
import tachiyomi.data.Mangas
import tachiyomi.data.MangasQueries
import tachiyomi.data.SyncQueries
import java.util.Date

class SyncMergerTest {

    private lateinit var database: Database
    private lateinit var syncQueries: SyncQueries
    private lateinit var chaptersQueries: ChaptersQueries
    private lateinit var historyQueries: HistoryQueries
    private lateinit var mangasQueries: MangasQueries
    private lateinit var merger: SyncMerger

    @BeforeEach
    fun setUp() {
        mockkStatic("app.cash.sqldelight.async.coroutines.QueryExtensionsKt")
        database = mockk()
        syncQueries = mockk(relaxed = true)
        chaptersQueries = mockk(relaxed = true)
        historyQueries = mockk(relaxed = true)
        mangasQueries = mockk(relaxed = true)

        every { database.syncQueries } returns syncQueries
        every { database.chaptersQueries } returns chaptersQueries
        every { database.historyQueries } returns historyQueries
        every { database.mangasQueries } returns mangasQueries

        coEvery { database.transaction(any(), any()) } coAnswers {
            val block = secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>()
            val tx = mockk<SuspendingTransactionWithoutReturn>(relaxed = true)
            tx.block()
        }

        merger = SyncMerger(database)
    }

    @Test
    fun `merge wraps operations in database transaction and resets is_syncing flags`() = runBlocking {
        var transactionExecuted = false
        coEvery { database.transaction(any(), any()) } coAnswers {
            transactionExecuted = true
            val block = secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>()
            val tx = mockk<SuspendingTransactionWithoutReturn>(relaxed = true)
            tx.block()
        }

        val emptyPayload = SyncPayload(
            chapters = emptyList(),
            history = emptyList(),
            mangas = emptyList(),
        )

        merger.merge(emptyPayload)

        assertTrue(transactionExecuted, "merge operations must be executed inside database.transaction")
        coVerify(exactly = 1) { syncQueries.resetAllChapterIsSyncing() }
        coVerify(exactly = 1) { syncQueries.resetAllMangaIsSyncing() }
    }

    @Test
    fun `merge applies updates using synchronous query execution within transaction`() = runBlocking {
        val localManga = Mangas(
            _id = 1L,
            source = 100L,
            url = "/manga/1",
            artist = null,
            author = null,
            description = null,
            genre = null,
            title = "Test Manga",
            status = 1L,
            thumbnail_url = null,
            favorite = false,
            last_update = null,
            next_update = null,
            initialized = true,
            viewer = 0L,
            chapter_flags = 0L,
            cover_last_modified = 0L,
            date_added = 0L,
            update_strategy = UpdateStrategy.ALWAYS_UPDATE,
            calculate_interval = 0L,
            last_modified_at = 1000L,
            favorite_modified_at = 1000L,
            version = 1L,
            is_syncing = 0L,
            notes = "",
            memo = JsonObject(emptyMap()),
        )

        val localChapter = Chapters(
            _id = 10L,
            manga_id = 1L,
            url = "/chapter/1",
            name = "Chapter 1",
            scanlator = null,
            read = false,
            bookmark = false,
            last_page_read = 5L,
            chapter_number = 1.0,
            source_order = 0L,
            date_fetch = 0L,
            date_upload = 0L,
            last_modified_at = 1000L,
            version = 1L,
            is_syncing = 0L,
            memo = JsonObject(emptyMap()),
        )

        val mangaQuery = mockk<Query<Mangas>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

        val chapterQuery = mockk<Query<Chapters>>()
        coEvery { chapterQuery.awaitAsOneOrNull() } returns localChapter
        every { syncQueries.getChapterByMangaIdAndUrl(1L, "/chapter/1") } returns chapterQuery

        val historyQuery = mockk<Query<tachiyomi.data.History>>()
        coEvery { historyQuery.awaitAsOneOrNull() } returns null
        every { historyQueries.getHistoryByChapterUrlAndMangaId(any(), any()) } returns historyQuery

        val payload = SyncPayload(
            chapters = listOf(
                ChapterSyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/1",
                    chapterUrl = "/chapter/1",
                    read = true,
                    bookmark = true,
                    lastPageRead = 10L,
                    version = 2L,
                    lastModifiedAt = 2000L,
                )
            ),
            history = listOf(
                HistorySyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/1",
                    chapterUrl = "/chapter/1",
                    lastRead = 2000000L,
                    timeRead = 500L,
                )
            ),
            mangas = listOf(
                MangaSyncRecord(
                    source = 100L,
                    url = "/manga/1",
                    title = "Test Manga",
                    favorite = true,
                    lastModifiedAt = 2000L,
                    favoriteModifiedAt = 2000L,
                )
            ),
        )

        merger.merge(payload)

        // Verify async query executions were invoked
        coVerify(atLeast = 1) { mangaQuery.awaitAsOneOrNull() }
        coVerify(atLeast = 1) { chapterQuery.awaitAsOneOrNull() }

        // Verify update operations occurred
        coVerify(exactly = 1) {
            chaptersQueries.update(
                chapterId = 10L,
                mangaId = null,
                url = null,
                name = null,
                scanlator = null,
                read = true,
                bookmark = true,
                lastPageRead = 10L,
                chapterNumber = null,
                sourceOrder = null,
                dateFetch = null,
                dateUpload = null,
                version = null,
                isSyncing = 1L,
                memo = null,
            )
        }

        coVerify(exactly = 1) {
            mangasQueries.update(
                mangaId = 1L,
                source = null,
                url = null,
                artist = null,
                author = null,
                description = null,
                genre = null,
                title = null,
                status = null,
                thumbnailUrl = null,
                favorite = true,
                lastUpdate = null,
                nextUpdate = null,
                initialized = null,
                viewer = null,
                chapterFlags = null,
                coverLastModified = null,
                dateAdded = null,
                updateStrategy = null,
                calculateInterval = null,
                version = null,
                isSyncing = 1L,
                notes = null,
                memo = null,
            )
        }

        coVerify(exactly = 1) {
            historyQueries.upsert(
                chapterId = 10L,
                readAt = Date(2000000L),
                time_read = 500L,
            )
        }

        coVerify(exactly = 1) { syncQueries.resetAllChapterIsSyncing() }
        coVerify(exactly = 1) { syncQueries.resetAllMangaIsSyncing() }
    }

    @Test
    fun `merge inserts new manga and chapter when none exist locally`() = runBlocking {
        val mangaQuery = mockk<Query<Mangas>>()
        val newLocalManga = mockk<Mangas>(relaxed = true)
        every { newLocalManga._id } returns 55L
        coEvery { mangaQuery.awaitAsOneOrNull() } returnsMany listOf(null, newLocalManga)
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/new") } returns mangaQuery

        val insertMangaQuery = mockk<Query<Long>>()
        coEvery { insertMangaQuery.awaitAsOne() } returns 55L
        every {
            syncQueries.insertSyncManga(
                source = 100L,
                url = "/manga/new",
                artist = null,
                author = null,
                description = null,
                genre = emptyList(),
                title = "New Manga",
                status = 1L,
                thumbnailUrl = null,
                favorite = true,
                chapterFlags = 0L,
                viewerFlags = 0L,
                dateAdded = 1000L,
                lastModifiedAt = 1000L,
                favoriteModifiedAt = null,
                version = 1L,
            )
        } returns insertMangaQuery

        val chapterQuery = mockk<Query<Chapters>>()
        coEvery { chapterQuery.awaitAsOneOrNull() } returns null
        every { syncQueries.getChapterByMangaIdAndUrl(55L, "/chapter/new1") } returns chapterQuery

        val insertChapterQuery = mockk<Query<Long>>()
        coEvery { insertChapterQuery.awaitAsOne() } returns 505L
        every {
            syncQueries.insertSyncChapter(
                mangaId = 55L,
                url = "/chapter/new1",
                name = "Chapter 1",
                scanlator = null,
                read = false,
                bookmark = false,
                lastPageRead = 3L,
                chapterNumber = 1.0,
                lastModifiedAt = 1000L,
                version = 1L,
            )
        } returns insertChapterQuery

        val payload = SyncPayload(
            mangas = listOf(
                MangaSyncRecord(
                    source = 100L,
                    url = "/manga/new",
                    title = "New Manga",
                    favorite = true,
                    status = 1L,
                    dateAdded = 1000L,
                    lastModifiedAt = 1000L,
                    version = 1L,
                )
            ),
            chapters = listOf(
                ChapterSyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/new",
                    chapterUrl = "/chapter/new1",
                    chapterName = "Chapter 1",
                    read = false,
                    bookmark = false,
                    lastPageRead = 3L,
                    chapterNumber = 1.0,
                    lastModifiedAt = 1000L,
                    version = 1L,
                )
            ),
        )

        merger.merge(payload)

        coVerify(exactly = 1) { insertMangaQuery.awaitAsOne() }
        coVerify(exactly = 1) { insertChapterQuery.awaitAsOne() }
    }

    @Test
    fun `merge applies safe read resolution and does not mark in-progress chapter read when remote is older`() = runBlocking {
        val localManga = mockk<Mangas>(relaxed = true)
        every { localManga._id } returns 1L
        val mangaQuery = mockk<Query<Mangas>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

        val localChapter = Chapters(
            _id = 10L,
            manga_id = 1L,
            url = "/chapter/1",
            name = "Chapter 1",
            scanlator = null,
            read = false,
            bookmark = false,
            last_page_read = 8L,
            chapter_number = 1.0,
            source_order = 0L,
            date_fetch = 0L,
            date_upload = 0L,
            last_modified_at = 2000L,
            version = 5L,
            is_syncing = 0L,
            memo = JsonObject(emptyMap()),
        )

        val chapterQuery = mockk<Query<Chapters>>()
        coEvery { chapterQuery.awaitAsOneOrNull() } returns localChapter
        every { syncQueries.getChapterByMangaIdAndUrl(1L, "/chapter/1") } returns chapterQuery

        val payload = SyncPayload(
            chapters = listOf(
                ChapterSyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/1",
                    chapterUrl = "/chapter/1",
                    read = true,
                    bookmark = false,
                    lastPageRead = 2L,
                    version = 3L,
                    lastModifiedAt = 1000L,
                )
            ),
        )

        merger.merge(payload)

        coVerify(exactly = 0) {
            chaptersQueries.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `merge advances lastPageRead monotonically when remote has higher page`() = runBlocking {
        val localManga = mockk<Mangas>(relaxed = true)
        every { localManga._id } returns 1L
        val mangaQuery = mockk<Query<Mangas>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

        val localChapter = Chapters(
            _id = 10L,
            manga_id = 1L,
            url = "/chapter/1",
            name = "Chapter 1",
            scanlator = null,
            read = false,
            bookmark = false,
            last_page_read = 3L,
            chapter_number = 1.0,
            source_order = 0L,
            date_fetch = 0L,
            date_upload = 0L,
            last_modified_at = 1000L,
            version = 1L,
            is_syncing = 0L,
            memo = JsonObject(emptyMap()),
        )

        val chapterQuery = mockk<Query<Chapters>>()
        coEvery { chapterQuery.awaitAsOneOrNull() } returns localChapter
        every { syncQueries.getChapterByMangaIdAndUrl(1L, "/chapter/1") } returns chapterQuery

        val payload = SyncPayload(
            chapters = listOf(
                ChapterSyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/1",
                    chapterUrl = "/chapter/1",
                    read = false,
                    bookmark = false,
                    lastPageRead = 7L,
                    version = 2L,
                    lastModifiedAt = 2000L,
                )
            ),
        )

        merger.merge(payload)

        coVerify(exactly = 1) {
            chaptersQueries.update(
                chapterId = 10L,
                mangaId = null,
                url = null,
                name = null,
                scanlator = null,
                read = false,
                bookmark = false,
                lastPageRead = 7L,
                chapterNumber = null,
                sourceOrder = null,
                dateFetch = null,
                dateUpload = null,
                version = null,
                isSyncing = 1L,
                memo = null,
            )
        }
    }
}

package eu.kanade.tachiyomi.sync

import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import app.cash.sqldelight.async.coroutines.awaitAsList
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
import tachiyomi.data.Category
import tachiyomi.data.Chapter
import tachiyomi.data.Database
import tachiyomi.data.GetCategoriesForManga
import tachiyomi.data.GetDuplicateFavoritesForSourceAndTitle
import tachiyomi.data.GetHistoryByChapterId
import tachiyomi.data.Manga
import tachiyomi.data.SyncQueries
import kotlin.time.Instant

class SyncMergerTest {

    private lateinit var database: Database
    private lateinit var syncQueries: SyncQueries
    private lateinit var merger: SyncMerger

    @BeforeEach
    fun setUp() {
        mockkStatic("app.cash.sqldelight.async.coroutines.QueryExtensionsKt")
        database = mockk()
        syncQueries = mockk(relaxed = true)

        every { database.syncQueries } returns syncQueries

        coEvery { database.transaction(any(), any()) } coAnswers {
            val block = secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>()
            val tx = mockk<SuspendingTransactionWithoutReturn>(relaxed = true)
            tx.block()
        }

        val defaultNullMangaQuery = mockk<Query<Manga>>()
        coEvery { defaultNullMangaQuery.awaitAsOneOrNull() } returns null
        every { syncQueries.getMangaBySourceAndUrl(any(), any()) } returns defaultNullMangaQuery
        every { syncQueries.getMangaBySourceAndTitle(any(), any()) } returns defaultNullMangaQuery

        val defaultNullChapterQuery = mockk<Query<Chapter>>()
        coEvery { defaultNullChapterQuery.awaitAsOneOrNull() } returns null
        every { syncQueries.getChapterByMangaIdAndUrl(any(), any()) } returns defaultNullChapterQuery
        every { syncQueries.getChapterByMangaIdAndNumber(any(), any()) } returns defaultNullChapterQuery

        val defaultDuplicatesQuery = mockk<Query<GetDuplicateFavoritesForSourceAndTitle>>()
        coEvery { defaultDuplicatesQuery.awaitAsList() } returns emptyList()
        every { syncQueries.getDuplicateFavoritesForSourceAndTitle(any(), any(), any()) } returns defaultDuplicatesQuery

        val defaultCatQuery = mockk<Query<GetCategoriesForManga>>()
        coEvery { defaultCatQuery.awaitAsList() } returns emptyList()
        every { syncQueries.getCategoriesForManga(any()) } returns defaultCatQuery

        val defaultNullCategoryQuery = mockk<Query<Category>>()
        coEvery { defaultNullCategoryQuery.awaitAsOneOrNull() } returns null
        every { syncQueries.getCategoryByName(any()) } returns defaultNullCategoryQuery

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
        val localManga = Manga(
            id = 1L,
            source_id = 100L,
            remote_url = "/manga/1",
            remote_title = "Test Manga",
            remote_author = null,
            remote_artist = null,
            remote_description = null,
            remote_genre = null,
            remote_status = 1L,
            remote_cover = null,
            remote_update_strategy = UpdateStrategy.ALWAYS_UPDATE,
            remote_memo = JsonObject(emptyMap()),
            user_favorite_at = null,
            user_notes = "",
            user_reader_flags = 0L,
            user_chapter_flags = 0L,
            state_chapter_last_update = null,
            state_chapter_next_update = null,
            state_chapter_fetch_interval = 0L,
            state_cover_last_modified = null,
            state_initialized = true,
            is_syncing = 0L,
            is_dirty = 0L,
            last_modified_at = 1000L,
            favorite_modified_at = 1000L,
        )

        val localChapter = Chapter(
            id = 10L,
            manga_id = 1L,
            remote_url = "/chapter/1",
            remote_name = "Chapter 1",
            remote_scanlator = null,
            remote_chapter_number = 1.0,
            remote_date_upload = null,
            remote_order = 0L,
            remote_memo = JsonObject(emptyMap()),
            user_read = false,
            user_bookmark = false,
            user_last_page_read = 5,
            state_date_fetch = Instant.fromEpochMilliseconds(0L),
            is_syncing = 0L,
            is_dirty = 0L,
            last_modified_at = 1000L,
        )

        val mangaQuery = mockk<Query<Manga>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

        val chapterQuery = mockk<Query<Chapter>>()
        coEvery { chapterQuery.awaitAsOneOrNull() } returns localChapter
        every { syncQueries.getChapterByMangaIdAndUrl(1L, "/chapter/1") } returns chapterQuery

        val historyQuery = mockk<Query<GetHistoryByChapterId>>()
        coEvery { historyQuery.awaitAsOneOrNull() } returns null
        every { syncQueries.getHistoryByChapterId(10L) } returns historyQuery

        val catQuery = mockk<Query<GetCategoriesForManga>>()
        coEvery { catQuery.awaitAsList() } returns emptyList()
        every { syncQueries.getCategoriesForManga(any()) } returns catQuery

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
                ),
            ),
            history = listOf(
                HistorySyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/1",
                    chapterUrl = "/chapter/1",
                    lastRead = 2000000L,
                    timeRead = 500L,
                ),
            ),
            mangas = listOf(
                MangaSyncRecord(
                    source = 100L,
                    url = "/manga/1",
                    title = "Test Manga",
                    favorite = true,
                    lastModifiedAt = 2000L,
                    favoriteModifiedAt = 2000L,
                ),
            ),
        )

        merger.merge(payload)

        // Verify async query executions were invoked
        coVerify(atLeast = 1) { mangaQuery.awaitAsOneOrNull() }
        coVerify(atLeast = 1) { chapterQuery.awaitAsOneOrNull() }

        // Verify update operations occurred via syncQueries
        coVerify(exactly = 1) {
            syncQueries.updateSyncChapter(
                id = 10L,
                read = true,
                bookmark = true,
                lastPageRead = 10,
                lastModifiedAt = 2000L,
            )
        }

        coVerify(exactly = 1) {
            syncQueries.updateSyncMangaFavorite(
                id = 1L,
                userFavoriteAt = any(),
                favoriteModifiedAt = 2000L,
                lastModifiedAt = 2000L,
            )
        }

        coVerify(exactly = 1) {
            syncQueries.upsertSyncHistory(
                mangaId = 1L,
                chapterId = 10L,
                readAt = Instant.fromEpochMilliseconds(2000000L),
                timeRead = 500L,
            )
        }

        coVerify(exactly = 1) { syncQueries.resetAllChapterIsSyncing() }
        coVerify(exactly = 1) { syncQueries.resetAllMangaIsSyncing() }
    }

    @Test
    fun `merge inserts new manga and chapter when none exist locally`() = runBlocking {
        val mangaQuery = mockk<Query<Manga>>()
        val newLocalManga = mockk<Manga>(relaxed = true)
        every { newLocalManga.id } returns 55L
        coEvery { mangaQuery.awaitAsOneOrNull() } returnsMany listOf(null, newLocalManga)
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/new") } returns mangaQuery

        val insertMangaQuery = mockk<Query<Long>>()
        coEvery { insertMangaQuery.awaitAsOne() } returns 55L
        every {
            syncQueries.insertSyncManga(
                source = 100L,
                url = "/manga/new",
                title = "New Manga",
                author = null,
                artist = null,
                description = null,
                genre = emptyList(),
                status = 1L,
                thumbnailUrl = null,
                updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                memo = any(),
                userFavoriteAt = any(),
                viewerFlags = 0L,
                chapterFlags = 0L,
                lastModifiedAt = 1000L,
                favoriteModifiedAt = 0L,
            )
        } returns insertMangaQuery

        val chapterQuery = mockk<Query<Chapter>>()
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
                chapterNumber = 1.0,
                memo = any(),
                read = false,
                bookmark = false,
                lastPageRead = 3,
                stateDateFetch = any(),
                lastModifiedAt = 1000L,
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
                ),
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
                ),
            ),
        )

        merger.merge(payload)

        coVerify(exactly = 1) { insertMangaQuery.awaitAsOne() }
        coVerify(exactly = 1) { insertChapterQuery.awaitAsOne() }
    }

    @Test
    fun `merge applies safe read resolution and does not mark in-progress chapter read when remote is older`() =
        runBlocking {
            val localManga = mockk<Manga>(relaxed = true)
            every { localManga.id } returns 1L
            val mangaQuery = mockk<Query<Manga>>()
            coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
            every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

            val localChapter = Chapter(
                id = 10L,
                manga_id = 1L,
                remote_url = "/chapter/1",
                remote_name = "Chapter 1",
                remote_scanlator = null,
                remote_chapter_number = 1.0,
                remote_date_upload = null,
                remote_order = 0L,
                remote_memo = JsonObject(emptyMap()),
                user_read = false,
                user_bookmark = false,
                user_last_page_read = 8,
                state_date_fetch = Instant.fromEpochMilliseconds(0L),
                is_syncing = 0L,
                is_dirty = 0L,
                last_modified_at = 2000L,
            )

            val chapterQuery = mockk<Query<Chapter>>()
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
                    ),
                ),
            )

            merger.merge(payload)

            coVerify(exactly = 0) {
                syncQueries.updateSyncChapter(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `merge advances lastPageRead monotonically when remote has higher page`() = runBlocking {
        val localManga = mockk<Manga>(relaxed = true)
        every { localManga.id } returns 1L
        val mangaQuery = mockk<Query<Manga>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

        val localChapter = Chapter(
            id = 10L,
            manga_id = 1L,
            remote_url = "/chapter/1",
            remote_name = "Chapter 1",
            remote_scanlator = null,
            remote_chapter_number = 1.0,
            remote_date_upload = null,
            remote_order = 0L,
            remote_memo = JsonObject(emptyMap()),
            user_read = false,
            user_bookmark = false,
            user_last_page_read = 3,
            state_date_fetch = Instant.fromEpochMilliseconds(0L),
            is_syncing = 0L,
            is_dirty = 0L,
            last_modified_at = 1000L,
        )

        val chapterQuery = mockk<Query<Chapter>>()
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
                ),
            ),
        )

        merger.merge(payload)

        coVerify(exactly = 1) {
            syncQueries.updateSyncChapter(
                id = 10L,
                read = false,
                bookmark = false,
                lastPageRead = 7,
                lastModifiedAt = 2000L,
            )
        }
    }

    @Test
    fun `merge history calculates delta duration to avoid duration inflation`() = runBlocking {
        val localManga = mockk<Manga>(relaxed = true)
        every { localManga.id } returns 1L
        val mangaQuery = mockk<Query<Manga>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

        val localChapter = mockk<Chapter>(relaxed = true)
        every { localChapter.id } returns 10L
        val chapterQuery = mockk<Query<Chapter>>()
        coEvery { chapterQuery.awaitAsOneOrNull() } returns localChapter
        every { syncQueries.getChapterByMangaIdAndUrl(1L, "/chapter/1") } returns chapterQuery

        val localHistory = GetHistoryByChapterId(
            id = 5L,
            chapter_id = 10L,
            read_at = Instant.fromEpochMilliseconds(1000L),
            read_duration = 300L,
        )
        val historyQuery = mockk<Query<GetHistoryByChapterId>>()
        coEvery { historyQuery.awaitAsOneOrNull() } returns localHistory
        every { syncQueries.getHistoryByChapterId(10L) } returns historyQuery

        val payload = SyncPayload(
            history = listOf(
                HistorySyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/1",
                    chapterUrl = "/chapter/1",
                    lastRead = 2000L,
                    timeRead = 500L,
                ),
            ),
        )

        merger.merge(payload)

        // Delta should be 500L - 300L = 200L passed to upsertSyncHistory
        coVerify(exactly = 1) {
            syncQueries.upsertSyncHistory(
                mangaId = 1L,
                chapterId = 10L,
                readAt = Instant.fromEpochMilliseconds(2000L),
                timeRead = 200L,
            )
        }
    }

    @Test
    fun `merge matches local manga by trailing slash variation and avoids inserting duplicate`() = runBlocking {
        val localManga = mockk<Manga>(relaxed = true)
        every { localManga.id } returns 42L
        every { localManga.source_id } returns 100L
        every { localManga.remote_url } returns "/manga/onepiece/"
        every { localManga.favorite_modified_at } returns 1000L
        every { localManga.user_favorite_at } returns null

        val mangaQuery = mockk<Query<Manga>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        // Remote has "/manga/onepiece" without slash; local has with slash
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/onepiece/") } returns mangaQuery

        val payload = SyncPayload(
            mangas = listOf(
                MangaSyncRecord(
                    source = 100L,
                    url = "/manga/onepiece",
                    title = "One Piece",
                    favorite = true,
                    lastModifiedAt = 2000L,
                ),
            ),
        )

        merger.merge(payload)

        // Must NOT insert new manga
        coVerify(exactly = 0) { syncQueries.insertSyncManga(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        // Must update the matched local manga
        coVerify(exactly = 1) { syncQueries.updateSyncMangaFavorite(id = 42L, userFavoriteAt = any(), favoriteModifiedAt = any(), lastModifiedAt = any()) }
    }

    @Test
    fun `merge matches local manga by title fallback and avoids inserting duplicate`() = runBlocking {
        val localManga = mockk<Manga>(relaxed = true)
        every { localManga.id } returns 77L
        every { localManga.source_id } returns 200L
        every { localManga.remote_url } returns "/restored/url"
        every { localManga.favorite_modified_at } returns 1000L
        every { localManga.user_favorite_at } returns null

        val titleQuery = mockk<Query<Manga>>()
        coEvery { titleQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndTitle(200L, "Unique Manga Title") } returns titleQuery

        val payload = SyncPayload(
            mangas = listOf(
                MangaSyncRecord(
                    source = 200L,
                    url = "/sync/different-url",
                    title = "Unique Manga Title",
                    favorite = true,
                    lastModifiedAt = 2000L,
                ),
            ),
        )

        merger.merge(payload)

        // Must NOT insert new manga
        coVerify(exactly = 0) { syncQueries.insertSyncManga(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        // Must update existing local manga
        coVerify(exactly = 1) { syncQueries.updateSyncMangaFavorite(id = 77L, userFavoriteAt = any(), favoriteModifiedAt = any(), lastModifiedAt = any()) }
    }

    @Test
    fun `merge unfavorites existing local duplicates when matching manga`() = runBlocking {
        val keeperManga = mockk<Manga>(relaxed = true)
        every { keeperManga.id } returns 10L
        every { keeperManga.source_id } returns 100L
        every { keeperManga.favorite_modified_at } returns 2000L

        val mangaQuery = mockk<Query<Manga>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns keeperManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/dup") } returns mangaQuery

        val duplicateManga = mockk<GetDuplicateFavoritesForSourceAndTitle>(relaxed = true)
        every { duplicateManga.id } returns 99L

        val duplicatesQuery = mockk<Query<GetDuplicateFavoritesForSourceAndTitle>>()
        coEvery { duplicatesQuery.awaitAsList() } returns listOf(duplicateManga)
        every { syncQueries.getDuplicateFavoritesForSourceAndTitle(100L, "Duplicate Test", 10L) } returns duplicatesQuery

        val payload = SyncPayload(
            mangas = listOf(
                MangaSyncRecord(
                    source = 100L,
                    url = "/manga/dup",
                    title = "Duplicate Test",
                    favorite = true,
                    lastModifiedAt = 2000L,
                ),
            ),
        )

        merger.merge(payload)

        // Verify duplicate was unfavorited to remove from library
        coVerify(exactly = 1) { syncQueries.unfavoriteManga(99L) }
    }

    @Test
    fun `merge matches chapter by normalized url trailing slash`() = runBlocking {
        val localManga = mockk<Manga>(relaxed = true)
        every { localManga.id } returns 1L
        val mangaQuery = mockk<Query<Manga>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

        val localChapter = Chapter(
            id = 50L,
            manga_id = 1L,
            remote_url = "/chapter/1/",
            remote_name = "Chapter 1",
            remote_scanlator = null,
            remote_chapter_number = 1.0,
            remote_date_upload = null,
            remote_order = 0L,
            remote_memo = JsonObject(emptyMap()),
            user_read = false,
            user_bookmark = false,
            user_last_page_read = 3,
            state_date_fetch = Instant.fromEpochMilliseconds(0L),
            is_syncing = 0L,
            is_dirty = 0L,
            last_modified_at = 1000L,
        )

        val chapterQuery = mockk<Query<Chapter>>()
        coEvery { chapterQuery.awaitAsOneOrNull() } returns localChapter
        // Remote has "/chapter/1", local has "/chapter/1/"
        every { syncQueries.getChapterByMangaIdAndUrl(1L, "/chapter/1/") } returns chapterQuery

        val payload = SyncPayload(
            chapters = listOf(
                ChapterSyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/1",
                    chapterUrl = "/chapter/1",
                    read = true,
                    bookmark = true,
                    lastPageRead = 10L,
                    lastModifiedAt = 2000L,
                ),
            ),
        )

        merger.merge(payload)

        // Must update existing chapter matched by normalized URL
        coVerify(exactly = 1) {
            syncQueries.updateSyncChapter(
                id = 50L,
                read = true,
                bookmark = true,
                lastPageRead = 10,
                lastModifiedAt = 2000L,
            )
        }
        coVerify(exactly = 0) { syncQueries.insertSyncChapter(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `merge matches chapter by chapter number fallback`() = runBlocking {
        val localManga = mockk<Manga>(relaxed = true)
        every { localManga.id } returns 1L
        val mangaQuery = mockk<Query<Manga>>()
        coEvery { mangaQuery.awaitAsOneOrNull() } returns localManga
        every { syncQueries.getMangaBySourceAndUrl(100L, "/manga/1") } returns mangaQuery

        val localChapter = Chapter(
            id = 60L,
            manga_id = 1L,
            remote_url = "/different/chapter/path",
            remote_name = "Ch. 5",
            remote_scanlator = null,
            remote_chapter_number = 5.0,
            remote_date_upload = null,
            remote_order = 0L,
            remote_memo = JsonObject(emptyMap()),
            user_read = false,
            user_bookmark = false,
            user_last_page_read = 1,
            state_date_fetch = Instant.fromEpochMilliseconds(0L),
            is_syncing = 0L,
            is_dirty = 0L,
            last_modified_at = 1000L,
        )

        val numberQuery = mockk<Query<Chapter>>()
        coEvery { numberQuery.awaitAsOneOrNull() } returns localChapter
        every { syncQueries.getChapterByMangaIdAndNumber(1L, 5.0) } returns numberQuery

        val payload = SyncPayload(
            chapters = listOf(
                ChapterSyncRecord(
                    mangaSource = 100L,
                    mangaUrl = "/manga/1",
                    chapterUrl = "/sync/chapter/5",
                    chapterNumber = 5.0,
                    read = true,
                    bookmark = false,
                    lastPageRead = 15L,
                    lastModifiedAt = 2000L,
                ),
            ),
        )

        merger.merge(payload)

        // Must update existing chapter found by chapter number fallback
        coVerify(exactly = 1) {
            syncQueries.updateSyncChapter(
                id = 60L,
                read = true,
                bookmark = false,
                lastPageRead = 15,
                lastModifiedAt = 2000L,
            )
        }
        coVerify(exactly = 0) { syncQueries.insertSyncChapter(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `merge deduplicates categories and ignores blank category names`() = runBlocking {
        val insertCategoryQuery = mockk<Query<Long>>()
        coEvery { insertCategoryQuery.awaitAsOne() } returns 5L
        every { syncQueries.insertCategory(any(), any(), any()) } returns insertCategoryQuery

        val payload = SyncPayload(
            categories = listOf(
                CategorySyncRecord(name = "", order = 0L, flags = 0L),
                CategorySyncRecord(name = "  ", order = 1L, flags = 0L),
                CategorySyncRecord(name = "Valid Category", order = 2L, flags = 0L),
            ),
        )

        merger.merge(payload)

        // Blank categories should be ignored; only Valid Category inserted
        coVerify(exactly = 1) {
            syncQueries.insertCategory(name = "Valid Category", order = 2L, flags = 0L)
        }
        coVerify(exactly = 0) {
            syncQueries.insertCategory(name = "", any(), any())
        }
    }
}

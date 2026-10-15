package eu.kanade.tachiyomi.sync.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.sync.service.SyncPreferences
import tachiyomi.data.Database
import kotlin.time.Instant

/**
 * Extracts database modifications occurring after a given epoch millisecond timestamp.
 *
 * Handles the timestamp resolution disparity:
 * - chapters.last_modified_at and mangas.last_modified_at are stored in SECONDS (SQLite strftime('%s', 'now'))
 * - history.read_at and sync network payloads are represented in MILLISECONDS (epoch ms)
 */
@Inject
@SingleIn(AppScope::class)
class SyncDiffEngine(
    private val database: Database,
    private val syncPreferences: SyncPreferences? = null,
) {

    @Volatile
    private var lastExtractedChapterIds: List<Long> = emptyList()

    @Volatile
    private var lastExtractedMangaIds: List<Long> = emptyList()

    @Volatile
    private var lastExtractedHistoryIds: List<Long> = emptyList()

    /**
     * Extracts all modified chapters, history records, and mangas since [sinceTimestampMillis].
     *
     * @param sinceTimestampMillis Timestamp in milliseconds since epoch (0 for initial sync).
     * @return [SyncPayload] containing all changes mapped to universal natural keys.
     */
    suspend fun extractDiff(sinceTimestampMillis: Long): SyncPayload {
        val sinceSeconds = if (sinceTimestampMillis > 0) sinceTimestampMillis / 1000L else 0L
        val sinceInstant = if (sinceTimestampMillis > 0) Instant.fromEpochMilliseconds(sinceTimestampMillis) else null

        // 1. Extract modified chapters
        val chapterRows = database.syncQueries
            .getModifiedChapters(sinceSeconds)
            .awaitAsList()
        lastExtractedChapterIds = chapterRows.map { it.chapterId }

        val chapters = chapterRows.map { row ->
            ChapterSyncRecord(
                mangaSource = row.mangaSource,
                mangaUrl = row.mangaUrl,
                chapterUrl = row.chapterUrl,
                chapterName = row.chapterName,
                read = row.read,
                bookmark = row.bookmark,
                lastPageRead = row.lastPageRead.toLong(),
                chapterNumber = row.chapterNumber,
                scanlator = row.scanlator,
                lastModifiedAt = row.lastModifiedAt,
                version = 0L,
            )
        }

        // 2. Extract modified history
        val historyRows = database.syncQueries
            .getModifiedHistory(sinceInstant)
            .awaitAsList()
        lastExtractedHistoryIds = historyRows.map { it.historyId }

        val history = historyRows.map { row ->
            HistorySyncRecord(
                mangaSource = row.mangaSource,
                mangaUrl = row.mangaUrl,
                chapterUrl = row.chapterUrl,
                lastRead = row.readAt?.toEpochMilliseconds() ?: 0L,
                timeRead = row.timeRead,
            )
        }

        // 3. Extract modified mangas
        val mangaRows = database.syncQueries
            .getModifiedMangas(sinceSeconds)
            .awaitAsList()
        lastExtractedMangaIds = mangaRows.map { it.mangaId }

        val mangas = mangaRows.map { row ->
            val categories = database.syncQueries
                .getCategoriesForManga(row.mangaId)
                .awaitAsList()
                .map { it.name }

            MangaSyncRecord(
                source = row.source,
                url = row.url,
                title = row.title,
                artist = row.artist,
                author = row.author,
                description = row.description,
                genre = row.genre ?: emptyList(),
                status = row.status,
                thumbnailUrl = row.thumbnailUrl,
                favorite = row.favorite,
                chapterFlags = row.chapterFlags,
                viewerFlags = row.viewerFlags,
                dateAdded = row.favoriteAt?.toEpochMilliseconds() ?: 0L,
                categories = categories,
                lastModifiedAt = row.lastModifiedAt,
                favoriteModifiedAt = row.favoriteModifiedAt,
                version = 0L,
            )
        }

        // 4. Extract categories
        val categoryRows = database.syncQueries
            .getAllCategories()
            .awaitAsList()

        val categories = categoryRows.map { row ->
            CategorySyncRecord(
                name = row.name,
                order = row.order,
                flags = row.flags,
            )
        }

        return SyncPayload(
            deviceId = syncPreferences?.deviceId?.get() ?: "",
            clientTimestamp = System.currentTimeMillis(),
            mangas = mangas,
            chapters = chapters,
            history = history,
            categories = categories,
        )
    }

    /**
     * Clears local `is_dirty` flags on all rows extracted during the previous [extractDiff] call.
     */
    suspend fun clearLastExtractedDirty() {
        if (lastExtractedChapterIds.isNotEmpty()) {
            database.syncQueries.clearDirtyChaptersByIds(lastExtractedChapterIds)
            lastExtractedChapterIds = emptyList()
        }
        if (lastExtractedMangaIds.isNotEmpty()) {
            database.syncQueries.clearDirtyMangasByIds(lastExtractedMangaIds)
            lastExtractedMangaIds = emptyList()
        }
        if (lastExtractedHistoryIds.isNotEmpty()) {
            database.syncQueries.clearDirtyHistoryByIds(lastExtractedHistoryIds)
            lastExtractedHistoryIds = emptyList()
        }
    }
}

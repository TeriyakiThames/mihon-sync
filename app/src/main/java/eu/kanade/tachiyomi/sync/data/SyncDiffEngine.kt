package eu.kanade.tachiyomi.sync.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.sync.service.SyncPreferences
import tachiyomi.data.Database
import java.util.Date

/**
 * Extracts database modifications occurring after a given epoch millisecond timestamp.
 *
 * Handles the timestamp resolution disparity:
 * - chapters.last_modified_at and mangas.last_modified_at are stored in SECONDS (SQLite strftime('%s', 'now'))
 * - history.last_read and sync network payloads are represented in MILLISECONDS (epoch ms / DateColumnAdapter)
 */
@Inject
@SingleIn(AppScope::class)
class SyncDiffEngine(
    private val database: Database,
    private val syncPreferences: SyncPreferences? = null,
) {

    /**
     * Extracts all modified chapters, history records, and mangas since [sinceTimestampMillis].
     *
     * @param sinceTimestampMillis Timestamp in milliseconds since epoch (0 for initial sync).
     * @return [SyncPayload] containing all changes mapped to universal natural keys.
     */
    suspend fun extractDiff(sinceTimestampMillis: Long): SyncPayload {
        val sinceSeconds = if (sinceTimestampMillis > 0) sinceTimestampMillis / 1000L else 0L
        val sinceDate = Date(sinceTimestampMillis)

        // 1. Extract modified chapters
        val chapterRows = database.syncQueries
            .getModifiedChapters(sinceSeconds)
            .awaitAsList()

        val chapters = chapterRows.map { row ->
            ChapterSyncRecord(
                mangaSource = row.mangaSource,
                mangaUrl = row.mangaUrl,
                chapterUrl = row.chapterUrl,
                chapterName = row.chapterName,
                read = row.read,
                bookmark = row.bookmark,
                lastPageRead = row.lastPageRead,
                chapterNumber = row.chapterNumber,
                scanlator = row.scanlator,
                lastModifiedAt = row.lastModifiedAt,
                version = row.version,
            )
        }

        // 2. Extract modified history
        val historyRows = database.syncQueries
            .getModifiedHistory(sinceDate)
            .awaitAsList()

        val history = historyRows.map { row ->
            HistorySyncRecord(
                mangaSource = row.mangaSource,
                mangaUrl = row.mangaUrl,
                chapterUrl = row.chapterUrl,
                lastRead = row.lastRead?.time ?: 0L,
                timeRead = row.timeRead,
            )
        }

        // 3. Extract modified mangas
        val mangaRows = database.syncQueries
            .getModifiedMangas(sinceSeconds)
            .awaitAsList()

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
                dateAdded = row.dateAdded,
                categories = categories,
                lastModifiedAt = row.lastModifiedAt,
                favoriteModifiedAt = row.favoriteModifiedAt,
                version = row.version,
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

        val deviceId = syncPreferences?.deviceId?.get() ?: ""

        return SyncPayload(
            deviceId = deviceId,
            clientTimestamp = System.currentTimeMillis(),
            mangas = mangas,
            chapters = chapters,
            history = history,
            categories = categories,
            settings = emptyMap(),
        )
    }
}

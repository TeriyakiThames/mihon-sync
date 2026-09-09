package eu.kanade.tachiyomi.sync.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import java.util.Date

/**
 * Merges remote synchronization payloads into the local SQLDelight database.
 *
 * Conflict Resolution:
 * 1. Monotonic Forward Reading Progress: advances chapter read status and page progress forward.
 * 2. Last-Write-Wins (LWW): applies bookmark and favorite updates when remote timestamps/versions are newer.
 * 3. Loop Prevention: uses `is_syncing = 1` during updates to suppress SQLite version increment triggers.
 */
@Inject
@SingleIn(AppScope::class)
class SyncMerger(
    private val database: Database,
) {

    /**
     * Applies remote updates to the local database.
     *
     * @param remotePayload Decrypted sync payload pulled from server.
     */
    suspend fun merge(remotePayload: SyncPayload) {
        // 1. Merge Chapters
        for (remoteChapter in remotePayload.chapters) {
            val localManga = database.syncQueries
                .getMangaBySourceAndUrl(remoteChapter.mangaSource, remoteChapter.mangaUrl)
                .awaitAsOneOrNull() ?: continue

            val localChapter = database.syncQueries
                .getChapterByMangaIdAndUrl(localManga._id, remoteChapter.chapterUrl)
                .awaitAsOneOrNull() ?: continue

            // Forward progress: if either local or remote is read, chapter is marked read
            val newRead = localChapter.read || remoteChapter.read

            // Forward progress: max page read
            val newLastPageRead = maxOf(localChapter.last_page_read, remoteChapter.lastPageRead)

            // LWW for bookmark
            val newBookmark = if (remoteChapter.version > localChapter.version ||
                remoteChapter.lastModifiedAt > localChapter.last_modified_at
            ) {
                remoteChapter.bookmark
            } else {
                localChapter.bookmark
            }

            // Only update if state has changed
            if (newRead != localChapter.read ||
                newLastPageRead != localChapter.last_page_read ||
                newBookmark != localChapter.bookmark
            ) {
                database.syncQueries.updateChapterSyncState(
                    read = newRead,
                    bookmark = newBookmark,
                    lastPageRead = newLastPageRead,
                    chapterId = localChapter._id,
                )
            }
        }

        // 2. Merge Reading History
        for (remoteHistory in remotePayload.history) {
            val localManga = database.syncQueries
                .getMangaBySourceAndUrl(remoteHistory.mangaSource, remoteHistory.mangaUrl)
                .awaitAsOneOrNull() ?: continue

            val localChapter = database.syncQueries
                .getChapterByMangaIdAndUrl(localManga._id, remoteHistory.chapterUrl)
                .awaitAsOneOrNull() ?: continue

            val localHistory = database.historyQueries
                .getHistoryByChapterUrlAndMangaId(remoteHistory.chapterUrl, localManga._id)
                .awaitAsOneOrNull()

            val localLastReadMillis = localHistory?.last_read?.time ?: 0L
            if (remoteHistory.lastRead > localLastReadMillis) {
                database.syncQueries.upsertHistory(
                    chapterId = localChapter._id,
                    lastRead = Date(remoteHistory.lastRead),
                    timeRead = remoteHistory.timeRead,
                )
            }
        }

        // 3. Merge Mangas (Favorites)
        for (remoteManga in remotePayload.mangas) {
            val localManga = database.syncQueries
                .getMangaBySourceAndUrl(remoteManga.source, remoteManga.url)
                .awaitAsOneOrNull() ?: continue

            val remoteFavMod = remoteManga.favoriteModifiedAt ?: remoteManga.lastModifiedAt
            val localFavMod = localManga.favorite_modified_at ?: localManga.last_modified_at

            if (remoteFavMod > localFavMod) {
                if (localManga.favorite != remoteManga.favorite) {
                    database.syncQueries.updateMangaSyncState(
                        favorite = remoteManga.favorite,
                        mangaId = localManga._id,
                    )
                }
            }
        }

        // 4. Reset is_syncing flags to ensure normal operation resumes
        database.syncQueries.resetAllChapterIsSyncing()
        database.syncQueries.resetAllMangaIsSyncing()
    }
}

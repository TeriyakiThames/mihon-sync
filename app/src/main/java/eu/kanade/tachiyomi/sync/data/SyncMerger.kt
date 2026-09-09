package eu.kanade.tachiyomi.sync.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
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
 * 1. Monotonic Forward Reading Progress: advances chapter read status and page progress forward safely.
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
        database.transaction {
            // 1. Merge Categories
            for (remoteCategory in remotePayload.categories) {
                val localCategory = database.syncQueries
                    .getCategoryByName(remoteCategory.name)
                    .awaitAsOneOrNull()

                if (localCategory == null) {
                    database.syncQueries.insertCategory(
                        name = remoteCategory.name,
                        order = remoteCategory.order,
                        flags = remoteCategory.flags,
                    ).awaitAsOne()
                } else if (localCategory.sort != remoteCategory.order || localCategory.flags != remoteCategory.flags) {
                    database.syncQueries.updateCategory(
                        id = localCategory._id,
                        order = remoteCategory.order,
                        flags = remoteCategory.flags,
                    )
                }
            }

            // 2. Merge Mangas
            for (remoteManga in remotePayload.mangas) {
                val localManga = database.syncQueries
                    .getMangaBySourceAndUrl(remoteManga.source, remoteManga.url)
                    .awaitAsOneOrNull()

                if (localManga == null) {
                    val newId = database.syncQueries.insertSyncManga(
                        source = remoteManga.source,
                        url = remoteManga.url,
                        artist = remoteManga.artist,
                        author = remoteManga.author,
                        description = remoteManga.description,
                        genre = remoteManga.genre,
                        title = remoteManga.title,
                        status = remoteManga.status,
                        thumbnailUrl = remoteManga.thumbnailUrl,
                        favorite = remoteManga.favorite,
                        chapterFlags = remoteManga.chapterFlags,
                        viewerFlags = remoteManga.viewerFlags,
                        dateAdded = remoteManga.dateAdded,
                        lastModifiedAt = remoteManga.lastModifiedAt,
                        favoriteModifiedAt = remoteManga.favoriteModifiedAt,
                        version = remoteManga.version,
                    ).awaitAsOne()

                    // Associate categories for the newly inserted manga
                    for (categoryName in remoteManga.categories) {
                        val category = database.syncQueries
                            .getCategoryByName(categoryName)
                            .awaitAsOneOrNull()
                        if (category != null) {
                            database.syncQueries.insertMangaCategory(newId, category._id)
                        }
                    }
                } else {
                    val remoteFavMod = remoteManga.favoriteModifiedAt ?: remoteManga.lastModifiedAt
                    val localFavMod = localManga.favorite_modified_at ?: localManga.last_modified_at

                    if (remoteFavMod > localFavMod) {
                        if (localManga.favorite != remoteManga.favorite) {
                            database.mangasQueries.update(
                                mangaId = localManga._id,
                                source = null,
                                url = null,
                                artist = null,
                                author = null,
                                description = null,
                                genre = null,
                                title = null,
                                status = null,
                                thumbnailUrl = null,
                                favorite = remoteManga.favorite,
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
                    }

                    val currentCategories = database.syncQueries.getCategoriesForManga(localManga._id).awaitAsList()
                    val currentCategoryNames = currentCategories.map { it.name }.toSet()
                    val remoteCategoryNames = remoteManga.categories.toSet()

                    if (currentCategoryNames != remoteCategoryNames) {
                        database.syncQueries.deleteMangaCategoriesForManga(localManga._id)
                        for (categoryName in remoteManga.categories) {
                            val category = database.syncQueries
                                .getCategoryByName(categoryName)
                                .awaitAsOneOrNull()
                            if (category != null) {
                                database.syncQueries.insertMangaCategory(localManga._id, category._id)
                            }
                        }
                    }
                }
            }

            // 3. Merge Chapters
            for (remoteChapter in remotePayload.chapters) {
                val localManga = database.syncQueries
                    .getMangaBySourceAndUrl(remoteChapter.mangaSource, remoteChapter.mangaUrl)
                    .awaitAsOneOrNull() ?: continue

                val localChapter = database.syncQueries
                    .getChapterByMangaIdAndUrl(localManga._id, remoteChapter.chapterUrl)
                    .awaitAsOneOrNull()

                if (localChapter == null) {
                    database.syncQueries.insertSyncChapter(
                        mangaId = localManga._id,
                        url = remoteChapter.chapterUrl,
                        name = remoteChapter.chapterName ?: "",
                        scanlator = remoteChapter.scanlator,
                        read = remoteChapter.read,
                        bookmark = remoteChapter.bookmark,
                        lastPageRead = remoteChapter.lastPageRead,
                        chapterNumber = remoteChapter.chapterNumber,
                        lastModifiedAt = remoteChapter.lastModifiedAt,
                        version = remoteChapter.version,
                    ).awaitAsOne()
                } else {
                    val isRemoteNewer = remoteChapter.version > localChapter.version ||
                        remoteChapter.lastModifiedAt > localChapter.last_modified_at

                    // Last-Write-Wins (LWW) conflict resolution: if remote is strictly newer,
                    // accept its read status, page progress, and bookmark.
                    val newRead = if (isRemoteNewer) remoteChapter.read else localChapter.read
                    val newLastPageRead = if (isRemoteNewer) remoteChapter.lastPageRead else localChapter.last_page_read
                    val newBookmark = if (isRemoteNewer) remoteChapter.bookmark else localChapter.bookmark

                    // Only update if state has changed
                    if (newRead != localChapter.read ||
                        newLastPageRead != localChapter.last_page_read ||
                        newBookmark != localChapter.bookmark
                    ) {
                        database.chaptersQueries.update(
                            chapterId = localChapter._id,
                            mangaId = null,
                            url = null,
                            name = null,
                            scanlator = null,
                            read = newRead,
                            bookmark = newBookmark,
                            lastPageRead = newLastPageRead,
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

            // 4. Merge Reading History
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
                val currentDuration = localHistory?.time_read ?: 0L
                val targetDuration = maxOf(remoteHistory.timeRead, currentDuration)
                val deltaDuration = targetDuration - currentDuration
                if (deltaDuration > 0 || remoteHistory.lastRead > localLastReadMillis) {
                    database.syncQueries.upsertSyncHistory(
                        chapterId = localChapter._id,
                        readAt = Date(maxOf(remoteHistory.lastRead, localLastReadMillis)),
                        time_read = deltaDuration,
                    )
                }
            }

            // 5. Reset is_syncing flags to ensure normal operation resumes
            database.syncQueries.resetAllChapterIsSyncing()
            database.syncQueries.resetAllMangaIsSyncing()
        }
    }
}

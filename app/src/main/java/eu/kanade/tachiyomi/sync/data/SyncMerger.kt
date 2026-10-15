package eu.kanade.tachiyomi.sync.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import tachiyomi.data.Database
import kotlin.time.Instant

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
                } else if (localCategory.order != remoteCategory.order || localCategory.flags != remoteCategory.flags) {
                    database.syncQueries.updateCategory(
                        id = localCategory.id,
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
                    val userFavoriteAt = if (remoteManga.favorite) {
                        if (remoteManga.dateAdded > 0) {
                            Instant.fromEpochMilliseconds(remoteManga.dateAdded)
                        } else {
                            Instant.fromEpochSeconds(remoteManga.lastModifiedAt)
                        }
                    } else {
                        null
                    }

                    val newId = database.syncQueries.insertSyncManga(
                        source = remoteManga.source,
                        url = remoteManga.url,
                        title = remoteManga.title,
                        author = remoteManga.author,
                        artist = remoteManga.artist,
                        description = remoteManga.description,
                        genre = remoteManga.genre,
                        status = remoteManga.status,
                        thumbnailUrl = remoteManga.thumbnailUrl,
                        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                        memo = JsonObject(emptyMap()),
                        userFavoriteAt = userFavoriteAt,
                        viewerFlags = remoteManga.viewerFlags,
                        chapterFlags = remoteManga.chapterFlags,
                        lastModifiedAt = remoteManga.lastModifiedAt,
                        favoriteModifiedAt = remoteManga.favoriteModifiedAt ?: 0L,
                    ).awaitAsOne()

                    // Associate categories for the newly inserted manga
                    for (categoryName in remoteManga.categories) {
                        val category = database.syncQueries
                            .getCategoryByName(categoryName)
                            .awaitAsOneOrNull()
                        if (category != null) {
                            database.syncQueries.insertMangaCategory(newId, category.id)
                        }
                    }
                } else {
                    val remoteFavMod = remoteManga.favoriteModifiedAt ?: remoteManga.lastModifiedAt
                    val localFavMod = localManga.favorite_modified_at ?: localManga.last_modified_at
                    val isLocalFavorite = localManga.user_favorite_at != null

                    if (remoteFavMod > localFavMod && isLocalFavorite != remoteManga.favorite) {
                        val newFavAt = if (remoteManga.favorite) {
                            if (remoteManga.dateAdded > 0) {
                                Instant.fromEpochMilliseconds(remoteManga.dateAdded)
                            } else {
                                Instant.fromEpochSeconds(remoteManga.lastModifiedAt)
                            }
                        } else {
                            null
                        }

                        database.syncQueries.updateSyncMangaFavorite(
                            id = localManga.id,
                            userFavoriteAt = newFavAt,
                            favoriteModifiedAt = remoteFavMod,
                            lastModifiedAt = remoteManga.lastModifiedAt,
                        )
                    }

                    val currentCategories = database.syncQueries.getCategoriesForManga(localManga.id).awaitAsList()
                    val currentCategoryNames = currentCategories.map { it.name }.toSet()
                    val remoteCategoryNames = remoteManga.categories.toSet()

                    if (currentCategoryNames != remoteCategoryNames) {
                        database.syncQueries.deleteMangaCategoriesForManga(localManga.id)
                        for (categoryName in remoteManga.categories) {
                            val category = database.syncQueries
                                .getCategoryByName(categoryName)
                                .awaitAsOneOrNull()
                            if (category != null) {
                                database.syncQueries.insertMangaCategory(localManga.id, category.id)
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
                    .getChapterByMangaIdAndUrl(localManga.id, remoteChapter.chapterUrl)
                    .awaitAsOneOrNull()

                if (localChapter == null) {
                    database.syncQueries.insertSyncChapter(
                        mangaId = localManga.id,
                        url = remoteChapter.chapterUrl,
                        name = remoteChapter.chapterName ?: "",
                        scanlator = remoteChapter.scanlator,
                        chapterNumber = remoteChapter.chapterNumber,
                        memo = JsonObject(emptyMap()),
                        read = remoteChapter.read,
                        bookmark = remoteChapter.bookmark,
                        lastPageRead = remoteChapter.lastPageRead.toInt(),
                        stateDateFetch = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                        lastModifiedAt = remoteChapter.lastModifiedAt,
                    ).awaitAsOne()
                } else {
                    val isRemoteNewer = remoteChapter.lastModifiedAt > localChapter.last_modified_at

                    // Last-Write-Wins (LWW) conflict resolution: if remote is strictly newer,
                    // accept its read status, page progress, and bookmark.
                    val newRead = if (isRemoteNewer) remoteChapter.read else localChapter.user_read
                    val newLastPageRead = if (isRemoteNewer) {
                        remoteChapter.lastPageRead.toInt()
                    } else {
                        localChapter.user_last_page_read
                    }
                    val newBookmark = if (isRemoteNewer) remoteChapter.bookmark else localChapter.user_bookmark

                    // Only update if state has changed
                    if (newRead != localChapter.user_read ||
                        newLastPageRead != localChapter.user_last_page_read ||
                        newBookmark != localChapter.user_bookmark
                    ) {
                        database.syncQueries.updateSyncChapter(
                            id = localChapter.id,
                            read = newRead,
                            bookmark = newBookmark,
                            lastPageRead = newLastPageRead,
                            lastModifiedAt = maxOf(remoteChapter.lastModifiedAt, localChapter.last_modified_at),
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
                    .getChapterByMangaIdAndUrl(localManga.id, remoteHistory.chapterUrl)
                    .awaitAsOneOrNull() ?: continue

                val localHistory = database.syncQueries
                    .getHistoryByChapterId(localChapter.id)
                    .awaitAsOneOrNull()

                val localLastReadMillis = localHistory?.read_at?.toEpochMilliseconds() ?: 0L
                val currentDuration = localHistory?.read_duration ?: 0L
                val targetDuration = maxOf(remoteHistory.timeRead, currentDuration)
                val deltaDuration = targetDuration - currentDuration
                if (deltaDuration > 0 || remoteHistory.lastRead > localLastReadMillis) {
                    database.syncQueries.upsertSyncHistory(
                        mangaId = localManga.id,
                        chapterId = localChapter.id,
                        readAt = Instant.fromEpochMilliseconds(maxOf(remoteHistory.lastRead, localLastReadMillis)),
                        timeRead = deltaDuration,
                    )
                }
            }

            // 5. Reset is_syncing flags to ensure normal operation resumes
            database.syncQueries.resetAllChapterIsSyncing()
            database.syncQueries.resetAllMangaIsSyncing()
        }
    }
}

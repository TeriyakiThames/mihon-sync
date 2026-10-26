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
 * 4. Deduplication: performs normalized URL and natural identity matching to avoid duplicate library records.
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
            for (remoteCategory in remotePayload.categories.filter { it.name.isNotBlank() }) {
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
                val localManga = findLocalManga(remoteManga.source, remoteManga.url, remoteManga.title)

                val targetMangaId: Long
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

                    targetMangaId = database.syncQueries.insertSyncManga(
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
                    for (categoryName in remoteManga.categories.filter { it.isNotBlank() }.distinct()) {
                        val category = database.syncQueries
                            .getCategoryByName(categoryName)
                            .awaitAsOneOrNull()
                        if (category != null && category.id > 0) {
                            database.syncQueries.insertMangaCategory(targetMangaId, category.id)
                        }
                    }
                } else {
                    targetMangaId = localManga.id
                    val remoteFavMod = remoteManga.favoriteModifiedAt ?: remoteManga.lastModifiedAt
                    val localFavMod = localManga.favorite_modified_at
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
                    val currentCategoryNames = currentCategories.map { it.name }.filter { it.isNotBlank() }.toSet()
                    val remoteCategoryNames = remoteManga.categories.filter { it.isNotBlank() }.toSet()

                    if (currentCategoryNames != remoteCategoryNames) {
                        database.syncQueries.deleteMangaCategoriesForManga(localManga.id)
                        for (categoryName in remoteCategoryNames) {
                            val category = database.syncQueries
                                .getCategoryByName(categoryName)
                                .awaitAsOneOrNull()
                            if (category != null && category.id > 0) {
                                database.syncQueries.insertMangaCategory(localManga.id, category.id)
                            }
                        }
                    }
                }

                // Consolidate any duplicate favorite records locally for this source and title
                if (remoteManga.title.isNotBlank()) {
                    val duplicates = database.syncQueries
                        .getDuplicateFavoritesForSourceAndTitle(
                            source = remoteManga.source,
                            title = remoteManga.title,
                            keepId = targetMangaId,
                        )
                        .awaitAsList()
                    for (dup in duplicates) {
                        database.syncQueries.unfavoriteManga(dup.id)
                    }
                }
            }

            // 3. Merge Chapters
            for (remoteChapter in remotePayload.chapters) {
                val localManga = findLocalManga(remoteChapter.mangaSource, remoteChapter.mangaUrl) ?: continue

                val localChapter = findLocalChapter(localManga.id, remoteChapter.chapterUrl, remoteChapter.chapterNumber)

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
                val localManga = findLocalManga(remoteHistory.mangaSource, remoteHistory.mangaUrl) ?: continue

                val localChapter = findLocalChapter(localManga.id, remoteHistory.chapterUrl) ?: continue

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

    private suspend fun findLocalManga(source: Long, url: String, title: String? = null): tachiyomi.data.Manga? {
        val exact = database.syncQueries.getMangaBySourceAndUrl(source, url).awaitAsOneOrNull()
        if (exact != null) return exact

        val trimmed = url.trimEnd('/')
        if (trimmed != url) {
            val byTrimmed = database.syncQueries.getMangaBySourceAndUrl(source, trimmed).awaitAsOneOrNull()
            if (byTrimmed != null) return byTrimmed
        } else {
            val withSlash = "$url/"
            val bySlash = database.syncQueries.getMangaBySourceAndUrl(source, withSlash).awaitAsOneOrNull()
            if (bySlash != null) return bySlash
        }

        if (!title.isNullOrBlank()) {
            val byTitle = database.syncQueries.getMangaBySourceAndTitle(source, title).awaitAsOneOrNull()
            if (byTitle != null) return byTitle
        }

        return null
    }

    private suspend fun findLocalChapter(mangaId: Long, chapterUrl: String, chapterNumber: Double? = null): tachiyomi.data.Chapter? {
        val exact = database.syncQueries.getChapterByMangaIdAndUrl(mangaId, chapterUrl).awaitAsOneOrNull()
        if (exact != null) return exact

        val trimmed = chapterUrl.trimEnd('/')
        if (trimmed != chapterUrl) {
            val byTrimmed = database.syncQueries.getChapterByMangaIdAndUrl(mangaId, trimmed).awaitAsOneOrNull()
            if (byTrimmed != null) return byTrimmed
        } else {
            val withSlash = "$chapterUrl/"
            val bySlash = database.syncQueries.getChapterByMangaIdAndUrl(mangaId, withSlash).awaitAsOneOrNull()
            if (bySlash != null) return bySlash
        }

        if (chapterNumber != null && chapterNumber > 0.0) {
            val byNumber = database.syncQueries.getChapterByMangaIdAndNumber(mangaId, chapterNumber).awaitAsOneOrNull()
            if (byNumber != null) return byNumber
        }

        return null
    }
}

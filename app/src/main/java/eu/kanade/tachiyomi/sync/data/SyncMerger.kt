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
     * Consolidates multiple remote synchronization payloads into a single deduplicated payload.
     * Overlapping manga and chapter updates preserve the latest modification timestamp.
     * Overlapping history records take the maximum read time and latest read timestamp.
     */
    fun consolidate(payloads: List<SyncPayload>): SyncPayload {
        if (payloads.isEmpty()) return SyncPayload()
        if (payloads.size == 1) return payloads.first()

        val categoriesMap = linkedMapOf<String, CategorySyncRecord>()
        val mangasMap = linkedMapOf<Pair<Long, String>, MangaSyncRecord>()
        val chaptersMap = linkedMapOf<Triple<Long, String, String>, ChapterSyncRecord>()
        val historyMap = linkedMapOf<Triple<Long, String, String>, HistorySyncRecord>()
        val settingsMap = mutableMapOf<String, String>()

        for (payload in payloads) {
            for (cat in payload.categories) {
                if (cat.name.isNotBlank()) {
                    categoriesMap[cat.name] = cat
                }
            }

            for (manga in payload.mangas) {
                val key = manga.source to manga.url
                val existing = mangasMap[key]
                if (existing == null) {
                    mangasMap[key] = manga
                } else {
                    val existingFavMod = existing.favoriteModifiedAt ?: existing.lastModifiedAt
                    val newFavMod = manga.favoriteModifiedAt ?: manga.lastModifiedAt
                    val effectiveFavorite = if (newFavMod >= existingFavMod) manga.favorite else existing.favorite
                    val effectiveDateAdded = if (manga.dateAdded > 0) manga.dateAdded else existing.dateAdded
                    val effectiveFavMod = maxOf(existingFavMod, newFavMod)
                    val effectiveLastMod = maxOf(existing.lastModifiedAt, manga.lastModifiedAt)
                    val effectiveCategories = if (manga.categories.isNotEmpty()) {
                        manga.categories
                    } else {
                        existing.categories
                    }

                    mangasMap[key] = manga.copy(
                        favorite = effectiveFavorite,
                        dateAdded = effectiveDateAdded,
                        favoriteModifiedAt = effectiveFavMod,
                        lastModifiedAt = effectiveLastMod,
                        categories = effectiveCategories,
                        title = if (manga.title.isNotBlank()) manga.title else existing.title,
                        author = manga.author ?: existing.author,
                        artist = manga.artist ?: existing.artist,
                        description = manga.description ?: existing.description,
                        genre = if (manga.genre.isNotEmpty()) manga.genre else existing.genre,
                        status = if (manga.status != 0L) manga.status else existing.status,
                        thumbnailUrl = manga.thumbnailUrl ?: existing.thumbnailUrl,
                        viewerFlags = if (manga.viewerFlags != 0L) manga.viewerFlags else existing.viewerFlags,
                        chapterFlags = if (manga.chapterFlags != 0L) manga.chapterFlags else existing.chapterFlags,
                    )
                }
            }

            for (chapter in payload.chapters) {
                val key = Triple(chapter.mangaSource, chapter.mangaUrl, chapter.chapterUrl)
                val existing = chaptersMap[key]
                if (existing == null || chapter.lastModifiedAt >= existing.lastModifiedAt) {
                    chaptersMap[key] = chapter
                }
            }

            for (history in payload.history) {
                val key = Triple(history.mangaSource, history.mangaUrl, history.chapterUrl)
                val existing = historyMap[key]
                if (existing == null) {
                    historyMap[key] = history
                } else {
                    historyMap[key] = history.copy(
                        lastRead = maxOf(existing.lastRead, history.lastRead),
                        timeRead = maxOf(existing.timeRead, history.timeRead),
                    )
                }
            }

            settingsMap.putAll(payload.settings)
        }

        val latestClientTimestamp = payloads.maxOfOrNull { it.clientTimestamp } ?: 0L
        val deviceId = payloads.lastOrNull { it.deviceId.isNotBlank() }?.deviceId ?: ""

        return SyncPayload(
            deviceId = deviceId,
            clientTimestamp = latestClientTimestamp,
            mangas = mangasMap.values.toList(),
            chapters = chaptersMap.values.toList(),
            history = historyMap.values.toList(),
            categories = categoriesMap.values.toList(),
            settings = settingsMap,
        )
    }

    /**
     * Merges a batch of remote synchronization payloads in a single transaction.
     */
    suspend fun merge(remotePayloads: List<SyncPayload>) {
        if (remotePayloads.isEmpty()) return
        val consolidated = if (remotePayloads.size == 1) {
            remotePayloads[0]
        } else {
            consolidate(remotePayloads)
        }
        merge(consolidated)
    }

    /**
     * Applies remote updates to the local database.
     *
     * @param remotePayload Decrypted sync payload pulled from server.
     */
    suspend fun merge(remotePayload: SyncPayload) {
        val mangaCache = mutableMapOf<Pair<Long, String>, tachiyomi.data.Manga?>()
        val chapterCache = mutableMapOf<Pair<Long, String>, tachiyomi.data.Chapter?>()
        val modifiedChapterIds = mutableSetOf<Long>()
        val modifiedMangaIds = mutableSetOf<Long>()

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
                } else if (
                    localCategory.order != remoteCategory.order ||
                    localCategory.flags != remoteCategory.flags
                ) {
                    database.syncQueries.updateCategory(
                        id = localCategory.id,
                        order = remoteCategory.order,
                        flags = remoteCategory.flags,
                    )
                }
            }

            // 2. Merge Mangas
            for (remoteManga in remotePayload.mangas) {
                val localManga = findLocalManga(remoteManga.source, remoteManga.url, remoteManga.title, mangaCache)

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
                    modifiedMangaIds.add(targetMangaId)

                    val insertedManga = database.syncQueries
                        .getMangaBySourceAndUrl(remoteManga.source, remoteManga.url)
                        .awaitAsOneOrNull()
                    mangaCache[remoteManga.source to remoteManga.url] = insertedManga

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
                        modifiedMangaIds.add(localManga.id)
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
                if (remoteManga.favorite && remoteManga.title.isNotBlank()) {
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
                val localManga = findLocalManga(
                    source = remoteChapter.mangaSource,
                    url = remoteChapter.mangaUrl,
                    mangaCache = mangaCache,
                ) ?: continue

                val localChapter = findLocalChapter(
                    mangaId = localManga.id,
                    chapterUrl = remoteChapter.chapterUrl,
                    chapterNumber = remoteChapter.chapterNumber,
                    chapterCache = chapterCache,
                )

                if (localChapter == null) {
                    val newChapterId = database.syncQueries.insertSyncChapter(
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
                    modifiedChapterIds.add(newChapterId)
                    val insertedChapter = database.syncQueries
                        .getChapterByMangaIdAndUrl(localManga.id, remoteChapter.chapterUrl)
                        .awaitAsOneOrNull()
                    chapterCache[localManga.id to remoteChapter.chapterUrl] = insertedChapter
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
                        modifiedChapterIds.add(localChapter.id)
                    }
                }
            }

            // 4. Merge Reading History
            for (remoteHistory in remotePayload.history) {
                val localManga = findLocalManga(
                    source = remoteHistory.mangaSource,
                    url = remoteHistory.mangaUrl,
                    mangaCache = mangaCache,
                ) ?: continue

                val localChapter = findLocalChapter(
                    mangaId = localManga.id,
                    chapterUrl = remoteHistory.chapterUrl,
                    chapterCache = chapterCache,
                ) ?: continue

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
            if (modifiedChapterIds.isNotEmpty()) {
                modifiedChapterIds.chunked(500).forEach { chunk ->
                    database.syncQueries.resetChapterIsSyncingByIds(chunk)
                }
            } else {
                database.syncQueries.resetAllChapterIsSyncing()
            }

            if (modifiedMangaIds.isNotEmpty()) {
                modifiedMangaIds.chunked(500).forEach { chunk ->
                    database.syncQueries.resetMangaIsSyncingByIds(chunk)
                }
            } else {
                database.syncQueries.resetAllMangaIsSyncing()
            }
        }
    }

    private suspend fun findLocalManga(
        source: Long,
        url: String,
        title: String? = null,
        mangaCache: MutableMap<Pair<Long, String>, tachiyomi.data.Manga?>? = null,
    ): tachiyomi.data.Manga? {
        val cacheKey = source to url
        if (mangaCache != null && mangaCache.containsKey(cacheKey)) {
            return mangaCache[cacheKey]
        }

        val exact = database.syncQueries.getMangaBySourceAndUrl(source, url).awaitAsOneOrNull()
        if (exact != null) {
            mangaCache?.put(cacheKey, exact)
            return exact
        }

        val trimmed = url.trimEnd('/')
        if (trimmed != url) {
            val byTrimmed = database.syncQueries.getMangaBySourceAndUrl(source, trimmed).awaitAsOneOrNull()
            if (byTrimmed != null) {
                mangaCache?.put(cacheKey, byTrimmed)
                return byTrimmed
            }
        } else {
            val withSlash = "$url/"
            val bySlash = database.syncQueries.getMangaBySourceAndUrl(source, withSlash).awaitAsOneOrNull()
            if (bySlash != null) {
                mangaCache?.put(cacheKey, bySlash)
                return bySlash
            }
        }

        if (!title.isNullOrBlank()) {
            val byTitle = database.syncQueries.getMangaBySourceAndTitle(source, title).awaitAsOneOrNull()
            if (byTitle != null) {
                mangaCache?.put(cacheKey, byTitle)
                return byTitle
            }
        }

        mangaCache?.put(cacheKey, null)
        return null
    }

    private suspend fun findLocalChapter(
        mangaId: Long,
        chapterUrl: String,
        chapterNumber: Double? = null,
        chapterCache: MutableMap<Pair<Long, String>, tachiyomi.data.Chapter?>? = null,
    ): tachiyomi.data.Chapter? {
        val cacheKey = mangaId to chapterUrl
        if (chapterCache != null && chapterCache.containsKey(cacheKey)) {
            return chapterCache[cacheKey]
        }

        val exact = database.syncQueries.getChapterByMangaIdAndUrl(mangaId, chapterUrl).awaitAsOneOrNull()
        if (exact != null) {
            chapterCache?.put(cacheKey, exact)
            return exact
        }

        val trimmed = chapterUrl.trimEnd('/')
        if (trimmed != chapterUrl) {
            val byTrimmed = database.syncQueries.getChapterByMangaIdAndUrl(mangaId, trimmed).awaitAsOneOrNull()
            if (byTrimmed != null) {
                chapterCache?.put(cacheKey, byTrimmed)
                return byTrimmed
            }
        } else {
            val withSlash = "$chapterUrl/"
            val bySlash = database.syncQueries.getChapterByMangaIdAndUrl(mangaId, withSlash).awaitAsOneOrNull()
            if (bySlash != null) {
                chapterCache?.put(cacheKey, bySlash)
                return bySlash
            }
        }

        if (chapterNumber != null && chapterNumber > 0.0) {
            val byNumber = database.syncQueries
                .getChapterByMangaIdAndNumber(mangaId, chapterNumber)
                .awaitAsOneOrNull()
            if (byNumber != null) {
                chapterCache?.put(cacheKey, byNumber)
                return byNumber
            }
        }

        chapterCache?.put(cacheKey, null)
        return null
    }
}

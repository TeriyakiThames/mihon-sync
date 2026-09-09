package eu.kanade.tachiyomi.sync.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Decrypted synchronization payload transferred between paired devices via encrypted envelope.
 */
@Serializable
data class SyncPayload(
    val deviceId: String = "",
    val clientTimestamp: Long = 0L,
    val mangas: List<MangaSyncRecord> = emptyList(),
    val chapters: List<ChapterSyncRecord> = emptyList(),
    val history: List<HistorySyncRecord> = emptyList(),
    val categories: List<CategorySyncRecord> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
)

/**
 * Synchronized chapter reading progress and state.
 */
@Serializable
data class ChapterSyncRecord(
    val mangaSource: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterName: String? = null,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val chapterNumber: Double = 0.0,
    val scanlator: String? = null,
    val lastModifiedAt: Long = 0L, // In seconds (SQLite strftime('%s', 'now'))
    val version: Long = 0L,
)

/**
 * Synchronized reading history entry.
 */
@Serializable
data class HistorySyncRecord(
    val mangaSource: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val lastRead: Long, // In milliseconds (DateColumnAdapter / epoch ms)
    val timeRead: Long,
)

/**
 * Synchronized manga entity identified by natural key (source, url).
 */
@Serializable
data class MangaSyncRecord(
    val source: Long,
    val url: String,
    val title: String,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String> = emptyList(),
    val status: Long = 0L,
    val thumbnailUrl: String? = null,
    val favorite: Boolean = true,
    val chapterFlags: Long = 0L,
    val viewerFlags: Long = 0L,
    val dateAdded: Long = 0L,
    val categories: List<String> = emptyList(),
    val lastModifiedAt: Long = 0L, // In seconds
    val favoriteModifiedAt: Long? = null,
    val version: Long = 0L,
)

/**
 * Synchronized category definition.
 */
@Serializable
data class CategorySyncRecord(
    val name: String,
    val order: Long = 0L,
    val flags: Long = 0L,
)

/**
 * Request body for pushing encrypted update to POST /api/sync.
 */
@Serializable
data class SyncPushRequest(
    val roomId: String,
    val timestamp: Long,
    val payload: String, // Base64 AES-256-GCM ciphertext
    val deviceId: String? = null,
)

/**
 * Response from POST /api/sync.
 */
@Serializable
data class SyncUpdateResponse(
    val success: Boolean,
    val updateId: String? = null,
    val id: String? = null,
    val timestamp: Long? = null,
    val error: String? = null,
)

/**
 * An individual encrypted update record received from GET /api/sync.
 */
@Serializable
data class SyncUpdateRecord(
    val id: String,
    val roomId: String? = null,
    val timestamp: Long,
    val payload: String, // Base64 AES-256-GCM ciphertext
    val deviceId: String? = null,
)

/**
 * Response from GET /api/sync?roomId=...&since=...
 */
@Serializable
data class SyncUpdatesResponse(
    val success: Boolean = true,
    val roomId: String? = null,
    val since: Long? = null,
    val count: Int = 0,
    val updates: List<SyncUpdateRecord> = emptyList(),
    val error: String? = null,
)

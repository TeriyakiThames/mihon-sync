package eu.kanade.tachiyomi.sync.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.sync.data.SyncPushRequest
import eu.kanade.tachiyomi.sync.data.SyncUpdateResponse
import eu.kanade.tachiyomi.sync.data.SyncUpdatesResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Handles network communication with the Mihon Sync backend API (POST /api/sync and GET /api/sync).
 */
@Inject
@SingleIn(AppScope::class)
class SyncApiClient(
    private val networkHelper: NetworkHelper,
    private val json: Json,
    private val syncPreferences: SyncPreferences,
) {

    private val client: OkHttpClient get() = networkHelper.client

    private val syncJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * Pushes an encrypted sync payload to the backend server via POST /api/sync.
     */
    suspend fun pushUpdate(payloadCiphertext: String, timestamp: Long): SyncUpdateResponse {
        val serverUrl = syncPreferences.serverUrl.get().trim().trimEnd('/')
        val roomId = syncPreferences.roomId.get().trim()
        val deviceId = syncPreferences.deviceId.get()

        require(serverUrl.isNotBlank()) { "Sync server URL is not configured" }
        require(roomId.isNotBlank()) { "Sync room ID is not configured" }

        val requestObj = SyncPushRequest(
            roomId = roomId,
            timestamp = timestamp,
            payload = payloadCiphertext,
            deviceId = deviceId,
        )

        val jsonString = syncJson.encodeToString(requestObj)
        val body = jsonString.toRequestBody(jsonMime)
        val request = POST("$serverUrl/api/sync", body = body, cache = CacheControl.FORCE_NETWORK)

        val response = client.newCall(request).awaitSuccess()
        val responseBody = response.body.string()
        return syncJson.decodeFromString(responseBody)
    }

    /**
     * Pulls encrypted updates recorded after [sinceTimestamp] from GET /api/sync.
     */
    suspend fun pullUpdates(sinceTimestamp: Long): SyncUpdatesResponse {
        val serverUrl = syncPreferences.serverUrl.get().trim().trimEnd('/')
        val roomId = syncPreferences.roomId.get().trim()

        require(serverUrl.isNotBlank()) { "Sync server URL is not configured" }
        require(roomId.isNotBlank()) { "Sync room ID is not configured" }

        val url = "$serverUrl/api/sync".toHttpUrl().newBuilder()
            .addQueryParameter("roomId", roomId)
            .addQueryParameter("since", sinceTimestamp.toString())
            .build()

        val request = GET(url, cache = CacheControl.FORCE_NETWORK)
        val response = client.newCall(request).awaitSuccess()
        val responseBody = response.body.string()
        return syncJson.decodeFromString(responseBody)
    }
}

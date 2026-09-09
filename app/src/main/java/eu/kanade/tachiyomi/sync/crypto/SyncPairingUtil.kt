package eu.kanade.tachiyomi.sync.crypto

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SyncPairingInfo(
    val serverUrl: String,
    val roomId: String,
    val secretKey: String,
)

object SyncPairingUtil {

    const val SCHEME = "mihon-sync"
    const val HOST = "pair"

    /**
     * Creates a pairing URI from connection parameters.
     * Format: mihon-sync://pair?serverUrl=...&roomId=...&key=...
     */
    fun createPairingUri(serverUrl: String, roomId: String, secretKey: String): String {
        val encodedServer = URLEncoder.encode(serverUrl, StandardCharsets.UTF_8.name())
        val encodedRoom = URLEncoder.encode(roomId, StandardCharsets.UTF_8.name())
        val encodedKey = URLEncoder.encode(secretKey, StandardCharsets.UTF_8.name())
        return "$SCHEME://$HOST?serverUrl=$encodedServer&roomId=$encodedRoom&key=$encodedKey"
    }

    /**
     * Parses a pairing URI into [SyncPairingInfo].
     */
    fun parsePairingUri(uriString: String): SyncPairingInfo {
        val uri = URI(uriString)
        require(uri.scheme.equals(SCHEME, ignoreCase = true)) {
            "Invalid scheme '${uri.scheme}'; expected '$SCHEME'"
        }
        require(uri.host.equals(HOST, ignoreCase = true)) {
            "Invalid host '${uri.host}'; expected '$HOST'"
        }

        val query = uri.rawQuery ?: throw IllegalArgumentException("Missing query parameters in pairing URI")
        val paramMap = query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) else ""
            key to value
        }

        val serverUrl = paramMap["serverUrl"] ?: paramMap["server"]
            ?: throw IllegalArgumentException("Missing 'serverUrl' parameter in pairing URI")
        val roomId = paramMap["roomId"] ?: paramMap["room"]
            ?: throw IllegalArgumentException("Missing 'roomId' parameter in pairing URI")
        val secretKey = paramMap["key"] ?: paramMap["secretKey"]
            ?: throw IllegalArgumentException("Missing 'key' parameter in pairing URI")

        return SyncPairingInfo(
            serverUrl = serverUrl,
            roomId = roomId,
            secretKey = secretKey,
        )
    }
}

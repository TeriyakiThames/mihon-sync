package eu.kanade.tachiyomi.sync.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.UUID

@Inject
@SingleIn(AppScope::class)
class SyncPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val serverUrl: Preference<String> = preferenceStore.getString(
        "sync_server_url",
        "https://sync.mihon.app",
    )

    val roomId: Preference<String> = preferenceStore.getString(
        "sync_room_id",
        "",
    )

    val encryptionKey: Preference<String> = preferenceStore.getString(
        "sync_encryption_key",
        "",
    )

    val lastSyncTimestamp: Preference<Long> = preferenceStore.getLong(
        "sync_last_timestamp",
        0L,
    )

    val isSyncEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "sync_enabled",
        false,
    )

    val deviceId: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("sync_device_id"),
        UUID.randomUUID().toString(),
    )

    fun isConfigured(): Boolean {
        return serverUrl.get().isNotBlank() &&
            roomId.get().isNotBlank() &&
            encryptionKey.get().isNotBlank()
    }

    fun clear() {
        serverUrl.delete()
        roomId.delete()
        encryptionKey.delete()
        lastSyncTimestamp.delete()
        isSyncEnabled.delete()
    }
}

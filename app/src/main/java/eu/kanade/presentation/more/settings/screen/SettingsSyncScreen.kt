package eu.kanade.presentation.more.settings.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.sync.crypto.CryptoUtil
import eu.kanade.tachiyomi.sync.crypto.SyncPairingUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsSyncScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = MR.strings.pref_category_sync

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val syncPreferences = remember { context.appGraph.syncPreferences }
        val syncManager = remember { context.appGraph.syncManager }
        val scope = rememberCoroutineScope()

        val isSyncEnabled by syncPreferences.isSyncEnabled.collectAsState()
        val lastSyncTimestamp by syncPreferences.lastSyncTimestamp.collectAsState()
        val isSyncingFromManager by syncManager.isSyncing.collectAsStateWithLifecycle()
        var isManualSyncing by remember { mutableStateOf(false) }
        val isSyncing = isSyncingFromManager || isManualSyncing

        var showImportDialog by remember { mutableStateOf(false) }
        var showClearConfirmDialog by remember { mutableStateOf(false) }

        val lastSyncSubtitle = if (lastSyncTimestamp > 0) {
            relativeTimeSpanString(lastSyncTimestamp)
        } else {
            null
        }

        // Dialog for importing/pasting a pairing URI
        if (showImportDialog) {
            var inputUri by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text(text = stringResource(MR.strings.pref_sync_import_pairing)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.pref_sync_import_pairing_summary),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = inputUri,
                            onValueChange = { inputUri = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(text = "mihon-sync://pair?...") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            try {
                                val pairingInfo = SyncPairingUtil.parsePairingUri(inputUri.trim())
                                syncPreferences.serverUrl.set(pairingInfo.serverUrl)
                                syncPreferences.roomId.set(pairingInfo.roomId)
                                syncPreferences.encryptionKey.set(pairingInfo.secretKey)
                                syncPreferences.isSyncEnabled.set(true)
                                showImportDialog = false
                                context.toast(MR.strings.pref_sync_pairing_success)
                                scope.launch {
                                    syncManager.syncNow(force = true)
                                }
                            } catch (e: Exception) {
                                context.toast("${context.stringResource(MR.strings.pref_sync_pairing_invalid)}: ${e.message}")
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showClearConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showClearConfirmDialog = false },
                title = { Text(text = stringResource(MR.strings.pref_sync_clear)) },
                text = { Text(text = stringResource(MR.strings.pref_sync_clear_summary)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            syncPreferences.clear()
                            showClearConfirmDialog = false
                            context.toast(MR.strings.pref_sync_clear)
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirmDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_sync),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = syncPreferences.isSyncEnabled,
                        title = stringResource(MR.strings.pref_sync_enable),
                        subtitle = stringResource(MR.strings.pref_sync_enable_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_sync_now),
                        subtitle = if (isSyncing) {
                            stringResource(MR.strings.sync_in_progress)
                        } else if (lastSyncSubtitle != null) {
                            stringResource(MR.strings.pref_sync_last_synced, lastSyncSubtitle)
                        } else {
                            stringResource(MR.strings.pref_sync_never)
                        },
                        enabled = isSyncEnabled && syncPreferences.isConfigured() && !isSyncing,
                        onClick = {
                            if (!isSyncing) {
                                isManualSyncing = true
                                scope.launch {
                                    val success = try {
                                        syncManager.syncNow(force = true)
                                    } finally {
                                        isManualSyncing = false
                                    }
                                    if (success) {
                                        context.toast(MR.strings.sync_success)
                                    } else {
                                        context.toast(MR.strings.sync_failed)
                                    }
                                }
                            }
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.sync_pair_device),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_sync_share_pairing),
                        subtitle = stringResource(MR.strings.pref_sync_share_pairing_summary),
                        enabled = syncPreferences.isConfigured(),
                        onClick = {
                            try {
                                val uri = SyncPairingUtil.createPairingUri(
                                    serverUrl = syncPreferences.serverUrl.get(),
                                    roomId = syncPreferences.roomId.get(),
                                    secretKey = syncPreferences.encryptionKey.get(),
                                )
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Mihon Sync Pairing Link", uri)
                                clipboard.setPrimaryClip(clip)
                                context.toast(MR.strings.pref_sync_pairing_link_copied)

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, uri)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, null))
                            } catch (e: Exception) {
                                context.toast("Error generating pairing link: ${e.message}")
                            }
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_sync_import_pairing),
                        subtitle = stringResource(MR.strings.pref_sync_import_pairing_summary),
                        onClick = { showImportDialog = true },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_sync_generate_credentials),
                        subtitle = stringResource(MR.strings.pref_sync_generate_credentials_summary),
                        onClick = {
                            val newRoom = CryptoUtil.generateRoomId()
                            val newKey = CryptoUtil.generateSecretKey()
                            syncPreferences.roomId.set(newRoom)
                            syncPreferences.encryptionKey.set(newKey)
                            syncPreferences.isSyncEnabled.set(true)
                            context.toast("Generated new Room ID and encryption key")
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_network),
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncPreferences.serverUrl,
                        title = stringResource(MR.strings.pref_sync_server_url),
                        subtitle = "%s",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncPreferences.roomId,
                        title = stringResource(MR.strings.pref_sync_room_id),
                        subtitle = "%s",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncPreferences.encryptionKey,
                        title = stringResource(MR.strings.pref_sync_encryption_key),
                        subtitle = "%s",
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_sync_clear),
                        subtitle = stringResource(MR.strings.pref_sync_clear_summary),
                        enabled = syncPreferences.isConfigured(),
                        onClick = { showClearConfirmDialog = true },
                    ),
                ),
            ),
        )
    }
}

package eu.kanade.tachiyomi.sync

import eu.kanade.tachiyomi.sync.service.SyncPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SyncPreferencesTest {

    @Test
    fun `deviceId generates and persists a new UUID when stored value is blank`() {
        val preferenceStore = mockk<PreferenceStore>(relaxed = true)
        val deviceIdPref = mockk<Preference<String>>(relaxed = true)

        var storedValue = ""
        every { deviceIdPref.get() } answers { storedValue }
        every { deviceIdPref.set(any()) } answers {
            storedValue = firstArg()
        }
        every { preferenceStore.getString(Preference.appStateKey("sync_device_id"), "") } returns deviceIdPref

        val syncPreferences = SyncPreferences(preferenceStore)

        val retrievedId = syncPreferences.deviceId.get()

        assertTrue(retrievedId.isNotBlank(), "Generated deviceId should not be blank")
        verify(exactly = 1) { deviceIdPref.set(retrievedId) }
    }

    @Test
    fun `deviceId preserves existing persisted UUID and does not overwrite`() {
        val preferenceStore = mockk<PreferenceStore>(relaxed = true)
        val deviceIdPref = mockk<Preference<String>>(relaxed = true)

        val existingId = "persisted-uuid-12345"
        every { deviceIdPref.get() } returns existingId
        every { preferenceStore.getString(Preference.appStateKey("sync_device_id"), "") } returns deviceIdPref

        val syncPreferences = SyncPreferences(preferenceStore)

        val retrievedId = syncPreferences.deviceId.get()

        assertEquals(existingId, retrievedId)
        verify(exactly = 0) { deviceIdPref.set(any()) }
    }
}

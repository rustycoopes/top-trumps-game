package com.toptrumps.app

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val DISPLAY_NAME_KEY = stringPreferencesKey("display_name")

/**
 * The display name, backed by Preferences DataStore (TDD §11). `null` means "never set" — story
 * 1's first-run screen is the only caller that should treat that as a prompt rather than a
 * loading state; everyone else should wait for the DataStore's first real read, since it is
 * async and there is no sensible synchronous default beyond the device-name prefill.
 */
public class DisplayNamePreferences(private val context: Context) {

    public val displayName: Flow<String?> =
        context.settingsDataStore.data.map { it[DISPLAY_NAME_KEY] }

    public suspend fun setDisplayName(name: String) {
        context.settingsDataStore.edit { it[DISPLAY_NAME_KEY] = name }
    }

    /** `Settings.Global.DEVICE_NAME` with a `Build.MODEL` fallback — not `BluetoothAdapter.getName()`, which needs a runtime permission (TDD §11). */
    public fun deviceNamePrefill(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: android.os.Build.MODEL
            ?: "Player"
}

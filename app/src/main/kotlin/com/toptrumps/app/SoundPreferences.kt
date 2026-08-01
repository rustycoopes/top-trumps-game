package com.toptrumps.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// Reuses [DisplayNamePreferences]'s `settingsDataStore` property rather than declaring a second
// `preferencesDataStore(name = "settings")` delegate — DataStore allows only one open instance per
// on-disk file per process; two independent delegates pointing at the same name throw
// "There are multiple DataStores active for the same file" as soon as both are collected.
private val MUTED_KEY = booleanPreferencesKey("sound_muted")

/**
 * Whether sound effects are muted (WBS slice-7-polish), backed by Preferences DataStore (TDD
 * §11). [muted] is a hot [StateFlow], not the raw DataStore [kotlinx.coroutines.flow.Flow] —
 * [SoundEffects] reads it once per cue on the audio-triggering call site, and a DataStore read per
 * sound (rather than a mirrored `StateFlow`) is exactly what the TDD's design notes warn against.
 */
public class SoundPreferences(private val context: Context, scope: CoroutineScope) {

    public val muted: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[MUTED_KEY] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    public suspend fun setMuted(value: Boolean) {
        context.settingsDataStore.edit { it[MUTED_KEY] = value }
    }
}

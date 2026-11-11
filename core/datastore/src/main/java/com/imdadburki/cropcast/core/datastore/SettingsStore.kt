package com.imdadburki.cropcast.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.imdadburki.cropcast.core.common.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cropcast_prefs")

/**
 * App preferences (spec §12, Settings → Appearance / Storage / Privacy).
 * Recording configuration lives in Room (RecordingSettingsEntity); this store
 * holds only app-level prefs. Onboarding completion is recorded here too.
 */
class SettingsStore(private val context: Context) {

    companion object {
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val HAPTICS = booleanPreferencesKey("haptics")
        private val FLOATING_CONTROLS = booleanPreferencesKey("floating_controls")
        private val SAVE_DESTINATION = stringPreferencesKey("save_destination")
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        when (it[THEME_MODE]) {
            "DARK" -> ThemeMode.DARK
            "LIGHT" -> ThemeMode.LIGHT
            else -> ThemeMode.SYSTEM
        }
    }

    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { it[HAPTICS] ?: true }

    val floatingControlsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[FLOATING_CONTROLS] ?: true }

    /** "movies" = MediaStore Movies (shared); "private" = app-private storage. */
    val saveDestination: Flow<String> =
        context.dataStore.data.map { it[SAVE_DESTINATION] ?: "movies" }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[ONBOARDING_DONE] = done }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setHaptics(enabled: Boolean) {
        context.dataStore.edit { it[HAPTICS] = enabled }
    }

    suspend fun setFloatingControls(enabled: Boolean) {
        context.dataStore.edit { it[FLOATING_CONTROLS] = enabled }
    }

    suspend fun setSaveDestination(destination: String) {
        require(destination == "movies" || destination == "private")
        context.dataStore.edit { it[SAVE_DESTINATION] = destination }
    }
}

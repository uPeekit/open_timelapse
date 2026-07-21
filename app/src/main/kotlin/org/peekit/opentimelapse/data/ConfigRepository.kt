package org.peekit.opentimelapse.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.peekit.opentimelapse.core.model.TimelapseConfig

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("timelapse")

/**
 * Stores the whole config as one JSON blob rather than twenty typed keys.
 *
 * [TimelapseConfig] is already @Serializable with a default for every field, so adding a
 * setting needs no migration: an older stored document simply decodes with the new defaults.
 */
class ConfigRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val config: Flow<TimelapseConfig> = context.dataStore.data.map { preferences ->
        preferences[KEY]?.let { stored ->
            runCatching { json.decodeFromString<TimelapseConfig>(stored) }
                // A corrupt document must not brick the app; defaults are always usable.
                .getOrElse { TimelapseConfig() }
        } ?: TimelapseConfig()
    }

    suspend fun current(): TimelapseConfig = config.first()

    suspend fun update(transform: (TimelapseConfig) -> TimelapseConfig) {
        val updated = transform(current())
        context.dataStore.edit { it[KEY] = json.encodeToString(updated) }
    }

    private companion object {
        val KEY = stringPreferencesKey("config_json")
    }
}

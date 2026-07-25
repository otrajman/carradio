package com.carradio.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "carradio_settings")

/**
 * Feature flags (PROTOCOL §11), current trip persistence (§1: 4 h idle window),
 * and the persistent played-breadcrumb-ids set (§6: survives restart, capped at 2000).
 */
class SettingsStore(private val context: Context) {

    // --- Feature flags -----------------------------------------------------------------

    val wakeWordEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_WAKEWORD] ?: true }

    val syntheticNodesEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SYNTHETIC] ?: true }

    val convoyCode: Flow<String> =
        context.dataStore.data.map { it[KEY_CONVOY_CODE] ?: "" }

    suspend fun setConvoyCode(code: String) {
        context.dataStore.edit { it[KEY_CONVOY_CODE] = code }
    }

    val fakeGpsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_FAKE_GPS] ?: false }

    suspend fun setWakeWordEnabled(value: Boolean) =
        context.dataStore.edit { it[KEY_WAKEWORD] = value }

    suspend fun setSyntheticNodesEnabled(value: Boolean) =
        context.dataStore.edit { it[KEY_SYNTHETIC] = value }

    suspend fun setFakeGpsEnabled(value: Boolean) =
        context.dataStore.edit { it[KEY_FAKE_GPS] = value }

    // --- Trip persistence --------------------------------------------------------------

    data class StoredTrip(val tripId: String, val handle: String, val createdAtMs: Long)

    suspend fun loadTrip(): StoredTrip? {
        val prefs = context.dataStore.data.first()
        val id = prefs[KEY_TRIP_ID] ?: return null
        val handle = prefs[KEY_TRIP_HANDLE] ?: return null
        val at = prefs[KEY_TRIP_CREATED_AT] ?: return null
        return StoredTrip(id, handle, at)
    }

    suspend fun saveTrip(trip: StoredTrip) = context.dataStore.edit {
        it[KEY_TRIP_ID] = trip.tripId
        it[KEY_TRIP_HANDLE] = trip.handle
        it[KEY_TRIP_CREATED_AT] = trip.createdAtMs
    }

    // --- Played breadcrumb ids (persistent dedupe) --------------------------------------

    suspend fun loadPlayedBreadcrumbIds(): Set<String> =
        context.dataStore.data.first()[KEY_PLAYED_IDS] ?: emptySet()

    suspend fun addPlayedBreadcrumbId(id: String) = context.dataStore.edit { prefs ->
        val current = prefs[KEY_PLAYED_IDS] ?: emptySet()
        val updated = (current + id).let { set ->
            // Preferences string-sets are unordered; when over cap, keep an arbitrary
            // subset — old ids expire server-side after 24 h anyway.
            if (set.size > MAX_PLAYED_IDS) set.drop(set.size - MAX_PLAYED_IDS).toSet() else set
        }
        prefs[KEY_PLAYED_IDS] = updated
    }

    companion object {
        private const val MAX_PLAYED_IDS = 2000

        private val KEY_WAKEWORD = booleanPreferencesKey("feature_wakeword")
        private val KEY_SYNTHETIC = booleanPreferencesKey("feature_synthetic_nodes")
        private val KEY_FAKE_GPS = booleanPreferencesKey("feature_fake_gps")
        private val KEY_CONVOY_CODE = stringPreferencesKey("convoy_code")
        private val KEY_TRIP_ID = stringPreferencesKey("trip_id")
        private val KEY_TRIP_HANDLE = stringPreferencesKey("trip_handle")
        private val KEY_TRIP_CREATED_AT = longPreferencesKey("trip_created_at")
        private val KEY_PLAYED_IDS = stringSetPreferencesKey("played_breadcrumb_ids")
    }
}

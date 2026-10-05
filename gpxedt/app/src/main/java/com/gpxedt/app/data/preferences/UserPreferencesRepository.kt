package com.gpxedt.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gpxedt_user_prefs")

/**
 * Repository interface for managing user preferences including trackpoint visibility.
 */
interface UserPreferencesRepository {
    /**
     * Observable flow indicating whether intermediate trackpoint dots along the polyline should be rendered.
     * Default value is true.
     */
    val showTrackpointsFlow: Flow<Boolean>

    /**
     * Persists the user preference for trackpoint visibility via DataStore.
     */
    suspend fun setShowTrackpoints(show: Boolean)

    /**
     * Synchronously returns the current cached/persisted preference for trackpoint visibility.
     */
    fun isShowTrackpoints(): Boolean
}

/**
 * Implementation of [UserPreferencesRepository] backed by Android Jetpack [DataStore] (Preferences)
 * with reactive [StateFlow] observation and asynchronous disk persistence.
 */
class UserPreferencesRepositoryImpl(
    private val context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : UserPreferencesRepository {

    companion object {
        val KEY_SHOW_TRACKPOINTS = booleanPreferencesKey("show_trackpoints")
    }

    private val _state: StateFlow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_SHOW_TRACKPOINTS] ?: true
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    override val showTrackpointsFlow: Flow<Boolean> = _state

    override suspend fun setShowTrackpoints(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SHOW_TRACKPOINTS] = show
        }
    }

    override fun isShowTrackpoints(): Boolean {
        return _state.value
    }
}

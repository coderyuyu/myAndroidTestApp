package com.gpxami.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.gpxami.app.data.model.AdminDivisionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository interface for managing user preferences including administrative division naming format.
 */
interface UserPreferencesRepository {
    val adminDivisionConfig: Flow<AdminDivisionConfig>
    suspend fun setAdminDivisionConfig(config: AdminDivisionConfig)
    fun getAdminDivisionConfig(): AdminDivisionConfig
}

/**
 * Implementation of [UserPreferencesRepository] backed by Android [SharedPreferences]
 * with reactive [StateFlow] observation and thread-safe persistence.
 */
class UserPreferencesRepositoryImpl(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) : UserPreferencesRepository {

    companion object {
        const val PREFS_NAME = "gpxami_user_prefs"
        const val KEY_ADMIN_DIVISION_CONFIG = "admin_division_config"
    }

    private val _adminDivisionConfig = MutableStateFlow(loadInitialConfig())
    override val adminDivisionConfig: Flow<AdminDivisionConfig> = _adminDivisionConfig.asStateFlow()

    private fun loadInitialConfig(): AdminDivisionConfig {
        val stored = prefs.getString(KEY_ADMIN_DIVISION_CONFIG, null)
        return try {
            if (stored != null) AdminDivisionConfig.valueOf(stored) else AdminDivisionConfig.LEVEL_1_ONLY
        } catch (_: Exception) {
            AdminDivisionConfig.LEVEL_1_ONLY
        }
    }

    override suspend fun setAdminDivisionConfig(config: AdminDivisionConfig) {
        prefs.edit().putString(KEY_ADMIN_DIVISION_CONFIG, config.name).apply()
        _adminDivisionConfig.value = config
    }

    override fun getAdminDivisionConfig(): AdminDivisionConfig {
        return _adminDivisionConfig.value
    }
}

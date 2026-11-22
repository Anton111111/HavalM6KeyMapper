package com.anton111111.haval.m6.keymapper.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "key_mapper_settings")

class SettingsStore(private val context: Context) {
    val settings: Flow<MapperSettings> = context.settingsDataStore.data.map { preferences ->
        MapperSettings(
            serviceEnabled = preferences[ENABLED_KEY] ?: false,
            rules = decodeRules(preferences[RULES_KEY]),
        )
    }

    suspend fun resetRule(value: Int) {
        context.settingsDataStore.edit { preferences ->
            val rules = decodeRules(preferences[RULES_KEY]).toMutableMap()
            rules.remove(value)
            preferences[RULES_KEY] = encodeRules(rules)
        }
    }

    suspend fun updateAction(value: Int, longPress: Boolean, action: KeyAction) {
        context.settingsDataStore.edit { preferences ->
            val rules = decodeRules(preferences[RULES_KEY]).toMutableMap()
            val current = rules[value] ?: ButtonRule(value)
            val updated = current.withAction(longPress, action)
            if (updated.overridesStock()) rules[value] = updated
            else rules.remove(value)
            preferences[RULES_KEY] = encodeRules(rules)
        }
    }

    suspend fun updateActionTargetApp(value: Int, longPress: Boolean, packageName: String?) {
        context.settingsDataStore.edit { preferences ->
            val rules = decodeRules(preferences[RULES_KEY]).toMutableMap()
            val current = rules[value] ?: ButtonRule(value)
            val updated = current.withTargetApp(longPress, packageName)
            if (updated.overridesStock()) rules[value] = updated
            else rules.remove(value)
            preferences[RULES_KEY] = encodeRules(rules)
        }
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("service_enabled")
        val RULES_KEY = stringPreferencesKey("button_rules")
    }
}

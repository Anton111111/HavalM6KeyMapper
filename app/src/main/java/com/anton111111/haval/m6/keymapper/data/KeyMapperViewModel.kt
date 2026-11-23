package com.anton111111.haval.m6.keymapper.data

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class KeyMapperViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val mutableState = MutableStateFlow(MapperUiState())
    val state: StateFlow<MapperUiState> = mutableState.asStateFlow()
    private var catalog: OemKeyCatalog? = null

    init {
        val lastCrash = getApplication<Application>()
            .getSharedPreferences("runtime", 0)
            .getString("last_java_crash", null)
        if (!lastCrash.isNullOrBlank()) {
            mutableState.value = mutableState.value.copy(lastCrash = lastCrash)
        }
        viewModelScope.launch {
            try {
                catalog = withContext(Dispatchers.IO) {
                    OemKeyCatalogLoader.load(getApplication())
                }
                val apps = withContext(Dispatchers.IO) {
                    loadLaunchableApps()
                }
                store.settings.collect { settings ->
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        definitions = visibleKeys(catalog!!.buttons, settings),
                        settings = settings,
                        launchableApps = apps,
                        status = serviceStatus(),
                        error = current.error,
                        loading = false,
                    )
                }
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(
                    error = "Не удалось прочитать OEM-настройки кнопок: ${exception.message}",
                    loading = false,
                )
            }
        }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000.milliseconds)
                val current = mutableState.value
                if (!current.loading) mutableState.value = current.copy(status = serviceStatus())
            }
        }
    }

    fun setAction(value: Int, longPress: Boolean, action: KeyAction) {
        viewModelScope.launch { store.updateAction(value, longPress, action) }
    }

    fun setActionTargetApp(value: Int, longPress: Boolean, packageName: String?) {
        viewModelScope.launch { store.updateActionTargetApp(value, longPress, packageName) }
    }

    fun resetRule(value: Int) {
        viewModelScope.launch { store.resetRule(value) }
    }

    fun clearLastCrash() {
        getApplication<Application>().getSharedPreferences("runtime", 0).edit {
            remove("last_java_crash")
        }
        mutableState.value = mutableState.value.copy(lastCrash = null)
    }

    fun setServiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                store.setServiceEnabled(enabled)
                val context = getApplication<Application>()
                val intent = Intent(
                    context,
                    com.anton111111.haval.m6.keymapper.service.KeyMapperService::class.java
                )
                if (enabled) context.startForegroundService(intent)
                else {
                    intent.action =
                        com.anton111111.haval.m6.keymapper.service.KeyMapperService.ACTION_STOP_AND_RESTORE
                    context.startService(intent)
                }
            } catch (exception: Exception) {
                setError("Не удалось изменить состояние службы: ${exception.message}")
            }
        }
    }

    fun restoreStockKeys() {
        setServiceEnabled(false)
    }

    private fun setError(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }

    private fun serviceStatus(): String =
        getApplication<Application>().getSharedPreferences("runtime", 0)
            .getString("status", "Служба ещё не запускалась") ?: "Нет статуса"

    private fun visibleKeys(
        buttons: List<ButtonDefinition>,
        settings: MapperSettings = mutableState.value.settings,
    ): List<ButtonDefinition> {
        val currentRules = settings.rules.keys
        return buttons.filter {
            it.value !in HIDDEN_BUTTON_KEYS &&
                    (it.value in currentRules || it.value in DEFAULT_WHEEL_KEYS)
        }.sortedBy { it.value }
    }

    private fun loadLaunchableApps(): List<LaunchableApp> {
        val context = getApplication<Application>()
        val packageManager = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                if (packageName.isBlank() || label.isBlank()) return@mapNotNull null
                LaunchableApp(packageName = packageName, label = label)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    private companion object {
        val DEFAULT_WHEEL_KEYS = setOf(
            0x05, 0x06, 0x21,
            0x60, 0x61, 0x62, 0x63, 0x78, 0x79,
        ) + SINGLE_EVENT_BUTTON_KEYS

        val HIDDEN_BUTTON_KEYS = setOf(
            0x09, 0x0A,
            0x51, 0x52, 0x53, 0x54, 0x60, 0x63
        )
    }
}

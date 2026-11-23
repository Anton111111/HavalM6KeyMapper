package com.anton111111.haval.m6.keymapper.data

import org.json.JSONArray
import org.json.JSONObject

enum class KeyAction(val title: String, val climate: Boolean = false) {
    DEFAULT("По умолчанию"),
    DISABLE("Отключить"),
    CLIMATE_TEMP_UP("Климат: температура +", true),
    CLIMATE_TEMP_DOWN("Климат: температура −", true),
    CLIMATE_FAN_UP("Климат: вентилятор +", true),
    CLIMATE_FAN_DOWN("Климат: вентилятор −", true),
    CLIMATE_AC_TOGGLE("Климат: AC вкл/выкл", true),
    CLIMATE_RECIRC_TOGGLE("Климат: рециркуляция", true),
    MEDIA_NEXT("Медиа: следующий трек"),
    MEDIA_PREVIOUS("Медиа: предыдущий трек"),
    MEDIA_PLAY_PAUSE("Медиа: плей/пауза"),
    APP_LAUNCH("Приложение: запустить");

    companion object {
        fun fromStored(value: String?): KeyAction =
            when (value) {
                "APP_TOGGLE" -> APP_LAUNCH
                else -> entries.firstOrNull { it.name == value } ?: DEFAULT
            }
    }
}

data class LaunchableApp(
    val packageName: String,
    val label: String,
)

data class ButtonDefinition(
    val value: Int,
    val oemName: String,
    val title: String,
)

data class ButtonRule(
    val value: Int,
    val shortAction: KeyAction = KeyAction.DEFAULT,
    val longAction: KeyAction = KeyAction.DEFAULT,
    val shortAppPackage: String? = null,
    val longAppPackage: String? = null,
) {
    fun withAction(longPress: Boolean, action: KeyAction): ButtonRule {
        val updated = if (longPress) {
            copy(
                longAction = action,
                longAppPackage = if (action.requiresAppSelection()) longAppPackage else null,
            )
        } else {
            copy(
                shortAction = action,
                shortAppPackage = if (action.requiresAppSelection()) shortAppPackage else null,
            )
        }
        if (!updated.overridesStock()) return updated
        return updated.copy(
            shortAction = if (updated.shortAction == KeyAction.DEFAULT) KeyAction.DISABLE
            else updated.shortAction,
            longAction = if (updated.longAction == KeyAction.DEFAULT) KeyAction.DISABLE
            else updated.longAction,
        )
    }

    fun withTargetApp(longPress: Boolean, packageName: String?): ButtonRule =
        if (longPress) copy(longAppPackage = packageName) else copy(shortAppPackage = packageName)

    fun overridesStock(): Boolean =
        shortAction != KeyAction.DEFAULT || longAction != KeyAction.DEFAULT

    fun action(longPress: Boolean): KeyAction =
        if (longPress) longAction else shortAction

    fun appPackage(longPress: Boolean): String? =
        if (longPress) longAppPackage else shortAppPackage
}

data class MapperSettings(
    val serviceEnabled: Boolean = false,
    val rules: Map<Int, ButtonRule> = emptyMap(),
)

data class OemKeyCatalog(
    val buttons: List<ButtonDefinition>,
    val allButtonNames: Set<String>,
    val callingLevel: Int,
    val cameraLevel: Int,
    val powerLevel: Int,
) {
    fun whitelist(settings: MapperSettings): Array<String> {
        val reserved = setOf(callingLevel, cameraLevel, powerLevel)
        require(OVERRIDE_LEVEL !in reserved) {
            "OEM call/camera/power handling already uses key filter level $OVERRIDE_LEVEL"
        }
        val blocked = settings.rules.values
            .filter(ButtonRule::overridesStock)
            .mapNotNull { rule -> buttons.firstOrNull { it.value == rule.value }?.oemName }
            .toSet()
        val allowed = allButtonNames - blocked
        require(allowed.isNotEmpty()) { "OEM whitelist would be empty" }
        return allowed.toTypedArray()
    }

    companion object {
        const val OVERRIDE_LEVEL = 10
    }
}

data class MapperUiState(
    val definitions: List<ButtonDefinition> = emptyList(),
    val launchableApps: List<LaunchableApp> = emptyList(),
    val settings: MapperSettings = MapperSettings(),
    val status: String = "Загрузка конфигурации ГУ…",
    val error: String? = null,
    val lastCrash: String? = null,
    val loading: Boolean = true,
)

val SINGLE_EVENT_BUTTON_KEYS = setOf(0x12, 0x13)

internal fun encodeRules(rules: Map<Int, ButtonRule>): String {
    val array = JSONArray()
    rules.toSortedMap().forEach { (value, rule) ->
        array.put(JSONObject()
            .put("value", value)
            .put("short", rule.shortAction.name)
            .put("long", rule.longAction.name)
            .put("shortPackage", rule.shortAppPackage)
            .put("longPackage", rule.longAppPackage))
    }
    return array.toString()
}

internal fun decodeRules(json: String?): Map<Int, ButtonRule> {
    if (json.isNullOrBlank()) return emptyMap()
    val result = linkedMapOf<Int, ButtonRule>()
    val array = JSONArray(json)
    for (index in 0 until array.length()) {
        val item = array.getJSONObject(index)
        val value = item.getInt("value")
        require(value in 0..255) { "Invalid saved button value: $value" }
        val rule = ButtonRule(
            value = value,
            shortAction = KeyAction.fromStored(item.optString("short")),
            longAction = KeyAction.fromStored(item.optString("long")),
            shortAppPackage = item.optString("shortPackage").takeIf { it.isNotBlank() },
            longAppPackage = item.optString("longPackage").takeIf { it.isNotBlank() },
        )
        result[value] = if (rule.overridesStock()) {
            val normalized = rule.copy(
                shortAction = if (rule.shortAction == KeyAction.DEFAULT) KeyAction.DISABLE
                else rule.shortAction,
                longAction = if (rule.longAction == KeyAction.DEFAULT) KeyAction.DISABLE
                else rule.longAction,
            )
            normalized.copy(
                shortAppPackage = if (normalized.shortAction.requiresAppSelection()) normalized.shortAppPackage else null,
                longAppPackage = if (normalized.longAction.requiresAppSelection()) normalized.longAppPackage else null,
            )
        } else {
            rule.copy(shortAppPackage = null, longAppPackage = null)
        }
    }
    return result
}

fun KeyAction.requiresAppSelection(): Boolean =
    this == KeyAction.APP_LAUNCH

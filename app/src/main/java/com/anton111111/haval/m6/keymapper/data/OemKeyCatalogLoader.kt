package com.anton111111.haval.m6.keymapper.data

import android.content.Context
import org.json.JSONObject
import java.io.File

object OemKeyCatalogLoader {
    private const val KEY_SERVICE_PACKAGE = "com.adayo.service.keyevent"
    private const val CONFIG_DIR = "/system/etc/adayo/keyService/"

    private val preferredSteeringKeys = mapOf(
        0x05 to "Громкость +",
        0x06 to "Громкость −",
        0x0C to "Кнопка беззвучного режима / регулятор громкости",
        0x09 to "Голосовой помощник",
        0x0A to "Siri",
        0x12 to "Регулятор громкости: поворот −",
        0x13 to "Регулятор громкости: поворот +",
        0x21 to "MODE / следующий источник",
        0x51 to "Радио: поиск +",
        0x52 to "Радио: поиск −",
        0x53 to "Радио: станция +",
        0x54 to "Радио: станция −",
        0x60 to "Телефон",
        0x61 to "Вызов",
        0x62 to "Завершить вызов",
        0x63 to "Повторный вызов",
        0x78 to "Следующий трек",
        0x79 to "Предыдущий трек",
    )

    fun load(context: Context): OemKeyCatalog {
        val vendorContext = context.createPackageContext(KEY_SERVICE_PACKAGE, 0)
        val buttons = linkedMapOf<Int, ButtonDefinition>()
        val allNames = linkedSetOf<String>()
        readKeyList(vendorContext.assets.open("hardkey.json").bufferedReader().use { it.readText() },
            buttons, allNames, includeButtons = true)
        readKeyList(vendorContext.assets.open("hardkey_touchscreen.json").bufferedReader()
            .use { it.readText() }, buttons, allNames, includeButtons = false)
        readOverride(File(CONFIG_DIR + "hardkey.json"), buttons, allNames, includeButtons = true)
        readOverride(File(CONFIG_DIR + "touchscreenkey.json"), buttons, allNames, includeButtons = false)
        require(buttons[0x21]?.oemName == "K_SOURCE") {
            "Installed OEM map does not define 0x21 as K_SOURCE; key remapping is disabled"
        }
        require(allNames.isNotEmpty()) { "OEM key whitelist is empty" }
        val configFile = File(CONFIG_DIR + "keyServiceConfig.json")
        var calling = 2
        var camera = 5
        var power = 8
        if (configFile.exists()) {
            val config = JSONObject(configFile.readText())
            calling = config.optString("callinglevel", "2").toInt()
            camera = config.optString("cameralevel", "5").toInt()
            power = config.optString("powerlevel", "8").toInt()
        }
        return OemKeyCatalog(buttons.values.toList(), allNames, calling, camera, power)
    }

    private fun readOverride(
        file: File,
        buttons: MutableMap<Int, ButtonDefinition>,
        names: MutableSet<String>,
        includeButtons: Boolean,
    ) {
        if (!file.exists()) return
        readKeyList(file.readText(), buttons, names, includeButtons)
    }

    private fun readKeyList(
        json: String,
        buttons: MutableMap<Int, ButtonDefinition>,
        names: MutableSet<String>,
        includeButtons: Boolean,
    ) {
        val entries = JSONObject(json).getJSONArray("hardkey")
        require(entries.length() > 0) { "OEM hardkey list is empty" }
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val value = item.getString("KeyValue").removePrefix("0x").toInt(16)
            val name = item.getString("KeyName")
            require(value in 0..255 && name.isNotBlank()) { "Invalid OEM key entry $index" }
            val title = preferredSteeringKeys[value] ?: name
            if (includeButtons) buttons[value] = ButtonDefinition(value, name, title)
            names += name
        }
    }
}

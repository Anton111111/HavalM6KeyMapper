package com.anton111111.haval.m6.keymapper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anton111111.haval.m6.keymapper.data.ButtonDefinition
import com.anton111111.haval.m6.keymapper.data.ButtonRule
import com.anton111111.haval.m6.keymapper.data.KeyAction
import com.anton111111.haval.m6.keymapper.data.KeyMapperViewModel
import com.anton111111.haval.m6.keymapper.data.LaunchableApp
import com.anton111111.haval.m6.keymapper.data.MapperUiState
import com.anton111111.haval.m6.keymapper.data.SINGLE_EVENT_BUTTON_KEYS
import com.anton111111.haval.m6.keymapper.data.requiresAppSelection
import com.anton111111.haval.m6.keymapper.service.ServiceEventLogStore
import com.anton111111.haval.m6.keymapper.service.ServiceLogEntry

class MainActivity : ComponentActivity() {
    private var crashForSave: String? = null
    private val saveCrashLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val crash = crashForSave
        if (uri != null && crash != null) {
            runCatching {
                contentResolver.openOutputStream(uri)
                    ?.use { it.write(crash.toByteArray(Charsets.UTF_8)) }
                    ?: error("Не удалось открыть выбранный файл")
            }.onFailure {
                android.widget.Toast.makeText(
                    this,
                    "Не удалось сохранить: ${it.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }.onSuccess {
                android.widget.Toast.makeText(
                    this,
                    "Сбой сохранён",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalM6Theme {
                KeyMapperScreen(viewModel())
            }
        }
    }

    fun shareCrash(crash: String) {
        val file = java.io.File(cacheDir, "haval-key-mapper-crash.txt")
        file.writeText(crash, Charsets.UTF_8)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, crash)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Отправить отчёт о сбое"))
    }

    fun saveCrash(crash: String) {
        crashForSave = crash
        saveCrashLauncher.launch("haval-key-mapper-crash.txt")
    }

    fun shareServiceLog(logText: String) {
        val file = java.io.File(cacheDir, "haval-key-mapper-service-log.txt")
        file.writeText(logText, Charsets.UTF_8)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Поделиться логом сервиса"))
    }
}

@Composable
private fun KeyMapperScreen(viewModel: KeyMapperViewModel) {
    val state by viewModel.state.collectAsState()
    val serviceLogs by remember { ServiceEventLogStore.logs }.collectAsState()
    val activity = LocalActivity.current as? MainActivity
    KeyMapperContent(
        state = state,
        serviceLogs = serviceLogs,
        onSetServiceEnabled = viewModel::setServiceEnabled,
        onRestoreStockKeys = viewModel::restoreStockKeys,
        onSetAction = { value, long, action -> viewModel.setAction(value, long, action) },
        onSetActionTargetApp = { value, long, packageName ->
            viewModel.setActionTargetApp(
                value,
                long,
                packageName
            )
        },
        onResetRule = viewModel::resetRule,
        onClearLastCrash = viewModel::clearLastCrash,
        onShareCrash = { crash -> activity?.shareCrash(crash) },
        onSaveCrash = { crash -> activity?.saveCrash(crash) },
        onSetServiceLogCaptureEnabled = ServiceEventLogStore::setCaptureEnabled,
        onClearServiceLog = ServiceEventLogStore::clear,
        onShareServiceLog = { activity?.shareServiceLog(ServiceEventLogStore.exportText()) },
    )
}

@Composable
private fun KeyMapperContent(
    state: MapperUiState,
    serviceLogs: List<ServiceLogEntry>,
    onSetServiceEnabled: (Boolean) -> Unit,
    onRestoreStockKeys: () -> Unit,
    onSetAction: (Int, Boolean, KeyAction) -> Unit,
    onSetActionTargetApp: (Int, Boolean, String?) -> Unit,
    onResetRule: (Int) -> Unit,
    onClearLastCrash: () -> Unit,
    onShareCrash: (String) -> Unit,
    onSaveCrash: (String) -> Unit,
    onSetServiceLogCaptureEnabled: (Boolean) -> Unit,
    onClearServiceLog: () -> Unit,
    onShareServiceLog: () -> Unit,
) {
    var showCrashDialog by remember { mutableStateOf(false) }
    var showServiceLogDialog by remember { mutableStateOf(false) }
    DisposableEffect(showServiceLogDialog) {
        onSetServiceLogCaptureEnabled(showServiceLogDialog)
        onDispose { onSetServiceLogCaptureEnabled(false) }
    }
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Haval M6 Key Mapper", style = MaterialTheme.typography.headlineSmall)
            }
            item {
                AuthorInfoBlock()
            }
            item {
                Row {
                    Text(
                        modifier = Modifier.weight(1f),
                        color = HavalColorSuccess,
                        text = state.status,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    IconButton(
                        onClick = { showServiceLogDialog = true },
                    ) {
                        Icon(
                            imageVector = Icons.Default.ViewTimeline,
                            contentDescription = null
                        )
                    }
                }
            }
            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            state.lastCrash?.let {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Обнаружен сбой предыдущего запуска",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { showCrashDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.onError,
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Text("Открыть отчёт")
                                }
                                OutlinedButton(
                                    onClick = onClearLastCrash,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                ) {
                                    Text("Очистить")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Кратко: включите «Фоновое переназначение», затем выберите действия для 1-го " +
                            "и 2-го нажатия в нужной кнопке. Если задано хотя бы одно действие, " +
                            "штатная обработка этой кнопки блокируется. Чтобы вернуть поведение " +
                            "с завода, сбросьте правило кнопки или нажмите «Восстановить штатные кнопки и выключить».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Фоновое переназначение", Modifier.weight(1f))
                    Switch(
                        checked = state.settings.serviceEnabled,
                        onCheckedChange = onSetServiceEnabled,
                        enabled = !state.loading,
                    )
                }
            }
            item {
                Button(
                    onClick = onRestoreStockKeys,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    )
                ) {
                    Text("Восстановить штатные кнопки и выключить")
                }
            }
            items(state.definitions, key = ButtonDefinition::value) { button ->
                ButtonCard(
                    button = button,
                    rule = state.settings.rules[button.value] ?: ButtonRule(button.value),
                    launchableApps = state.launchableApps,
                    onAction = { long, action -> onSetAction(button.value, long, action) },
                    onActionTargetApp = { long, packageName ->
                        onSetActionTargetApp(button.value, long, packageName)
                    },
                    onReset = { onResetRule(button.value) },
                )
            }
        }
    }

    if (showCrashDialog) {
        val crash = state.lastCrash
        if (crash != null) {
            AlertDialog(
                onDismissRequest = { showCrashDialog = false },
                title = { Text("Отчёт о сбое") },
                text = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(crash)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onShareCrash(crash) }) { Text("Поделиться") }
                },
                dismissButton = {
                    TextButton(onClick = { onSaveCrash(crash) }) { Text("Сохранить в Downloads") }
                },
            )
        }
    }

    if (showServiceLogDialog) {
        ServiceLogDialog(
            logs = serviceLogs,
            onClear = onClearServiceLog,
            onShare = onShareServiceLog,
            onClose = { showServiceLogDialog = false },
        )
    }
}

@Composable
private fun ServiceLogDialog(
    logs: List<ServiceLogEntry>,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onClear) { Text("Очистить") }
                    Button(onClick = onShare) { Text("Поделиться") }
                    OutlinedButton(onClick = onClose) { Text("Закрыть") }
                }
                if (logs.isEmpty()) {
                    Text("Лог пока пуст")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(logs) { entry ->
                            Text("[${entry.timestamp}] ${entry.event}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonCard(
    button: ButtonDefinition,
    rule: ButtonRule,
    launchableApps: List<LaunchableApp>,
    onAction: (Boolean, KeyAction) -> Unit,
    onActionTargetApp: (Boolean, String?) -> Unit,
    onReset: () -> Unit,
) {
    val hasOverride = rule.overridesStock()
    val supportsLongPress = button.value !in SINGLE_EVENT_BUTTON_KEYS
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasOverride) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (hasOverride) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(button.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "OEM: ${button.oemName} · value 0x${button.value.toString(16)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (hasOverride) {
                    Button(
                        onClick = onReset,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) { Text("Сбросить настройки этой кнопки") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionDropdown("Короткое:", rule.shortAction) { onAction(false, it) }
                if (supportsLongPress) {
                    ActionDropdown("Длинное:", rule.longAction) { onAction(true, it) }
                }
            }
            if (!supportsLongPress) {
                Text("Для этой кнопки доступно только SINGLE-событие")
            }
            if (rule.shortAction.requiresAppSelection()) {
                AppDropdown(
                    label = "Приложение для короткого",
                    selectedPackage = rule.shortAppPackage,
                    apps = launchableApps,
                    onSelected = { onActionTargetApp(false, it) },
                )
            }
            if (supportsLongPress && rule.longAction.requiresAppSelection()) {
                AppDropdown(
                    label = "Приложение для длинного",
                    selectedPackage = rule.longAppPackage,
                    apps = launchableApps,
                    onSelected = { onActionTargetApp(true, it) },
                )
            }
        }
    }
}


@Composable
private fun AuthorInfoBlock() {
    val text = buildAnnotatedString {
        append("Автор: Anton111111 (")
        withLink(LinkAnnotation.Url("https://t.me/TheManFromSaturn")) {
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.tertiary,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append("@TheManFromSaturn")
            }
        }
        append(") ")
        withLink(LinkAnnotation.Url("https://github.com/Anton111111")) {
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.tertiary,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append("https://github.com/Anton111111")
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            text = "Приложение разработано для бесплатного распростарнения.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Коммерческое использование и платная установка запрещена!",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppDropdown(
    label: String,
    selectedPackage: String?,
    apps: List<LaunchableApp>,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = apps.firstOrNull { it.packageName == selectedPackage }?.label
        ?: selectedPackage
        ?: "Не выбрано"
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = HavalColorAccentCyan)
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedTitle)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Не выбрано") },
                onClick = {
                    expanded = false
                    onSelected(null)
                },
            )
            apps.forEach { app ->
                DropdownMenuItem(
                    text = { Text("${app.label} (${app.packageName})") },
                    onClick = {
                        expanded = false
                        onSelected(app.packageName)
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.ActionDropdown(
    label: String,
    selected: KeyAction,
    onSelected: (KeyAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, color = HavalColorAccentCyan)
            TextButton(
                onClick = { expanded = true },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = HavalColorAccentSoft
                )
            ) {
                Text(selected.title)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            KeyAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.title) },
                    onClick = {
                        expanded = false
                        onSelected(action)
                    },
                )
            }
        }
    }
}


@Preview(name = "KeyMapper landscape", showBackground = true, widthDp = 960, heightDp = 960)
@Composable
private fun KeyMapperScreenPreview() {
    val previewButtons = listOf(
        ButtonDefinition(0x51, "KEY_VOL_UP", "Громкость +"),
        ButtonDefinition(0x52, "KEY_VOL_DOWN", "Громкость -"),
        ButtonDefinition(0x21, "KEY_MODE", "MODE"),
        ButtonDefinition(0x13, "K_KNOB_UP", "Крутилка +"),
    )
    val previewRules = mapOf(
        0x51 to ButtonRule(
            value = 0x51,
            shortAction = KeyAction.APP_LAUNCH,
            longAction = KeyAction.APP_LAUNCH,
            shortAppPackage = "ru.yandex.yandexmaps",
            longAppPackage = "ru.yandex.yandexnavi",
        ),
        0x52 to ButtonRule(
            value = 0x52,
            shortAction = KeyAction.MEDIA_PREVIOUS,
            longAction = KeyAction.DISABLE
        ),
        0x13 to ButtonRule(
            value = 0x13,
            shortAction = KeyAction.MEDIA_NEXT,
            longAction = KeyAction.DEFAULT,
        ),
    )
    val previewApps = listOf(
        LaunchableApp("ru.yandex.yandexmaps", "Яндекс Карты"),
        LaunchableApp("ru.yandex.yandexnavi", "Яндекс Навигатор"),
        LaunchableApp("com.google.android.apps.maps", "Google Maps"),
    )
    val previewState = MapperUiState(
        definitions = previewButtons,
        launchableApps = previewApps,
        settings = com.anton111111.haval.m6.keymapper.data.MapperSettings(
            serviceEnabled = true,
            rules = previewRules,
        ),
        status = "Переназначение активно; фильтруется кнопок: 2",
        lastCrash = "Поток: main\njava.lang.IllegalStateException: Preview sample",
        loading = false,
    )

    HavalM6Theme {
        KeyMapperContent(
            state = previewState,
            serviceLogs = listOf(
                ServiceLogEntry("12:00:00.100", "Кнопка MODE нажата: события нет"),
                ServiceLogEntry("12:00:01.540", "Кнопка Громкость + (короткое): выполнено Медиа: следующий трек"),
            ),
            onSetServiceEnabled = {},
            onRestoreStockKeys = {},
            onSetAction = { _, _, _ -> },
            onSetActionTargetApp = { _, _, _ -> },
            onResetRule = {},
            onClearLastCrash = {},
            onShareCrash = {},
            onSaveCrash = {},
            onSetServiceLogCaptureEnabled = {},
            onClearServiceLog = {},
            onShareServiceLog = {},
        )
    }

}

@Preview(name = "Service log dialog", showBackground = true, widthDp = 960, heightDp = 540)
@Composable
private fun ServiceLogDialogPreview() {
    HavalM6Theme {
        ServiceLogDialog(
            logs = listOf(
                ServiceLogEntry("12:00:00.100", "Service status updated"),
                ServiceLogEntry(
                    "12:00:01.540",
                    "Button MODE pressed; foreground app: ru.yandex.yandexmaps; no event (no remap)"
                ),
                ServiceLogEntry("12:00:03.220", "Button Volume+ (short press): executed MEDIA_NEXT"),
            ),
            onClear = {},
            onShare = {},
            onClose = {},
        )
    }
}

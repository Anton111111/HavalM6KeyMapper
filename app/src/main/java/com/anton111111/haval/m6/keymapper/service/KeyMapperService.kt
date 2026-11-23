package com.anton111111.haval.m6.keymapper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.adayo.mcucommproxy.IMcuCommCallback
import com.adayo.mcucommproxy.McuCommManager
import com.anton111111.haval.m6.keymapper.MainActivity
import com.anton111111.haval.m6.keymapper.data.ButtonRule
import com.anton111111.haval.m6.keymapper.data.KeyAction
import com.anton111111.haval.m6.keymapper.data.MapperSettings
import com.anton111111.haval.m6.keymapper.data.OemKeyCatalog
import com.anton111111.haval.m6.keymapper.data.OemKeyCatalogLoader
import com.anton111111.haval.m6.keymapper.data.SettingsStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class KeyMapperService : Service() {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { SettingsStore(applicationContext) }
    @Volatile private var settings = MapperSettings()
    @Volatile private var catalog: OemKeyCatalog? = null
    @Volatile private var keyServiceBinder: IBinder? = null
    @Volatile private var mcuReady = false
    private var bound = false
    @Volatile private var filterApplied = false
    private var restorePending = false
    private var stopAfterRestore = false
    private val registeredGroups = mutableSetOf<Byte>()
    private val presses = mutableMapOf<Int, PressState>()
    private val pulses = mutableMapOf<String, ScheduledFuture<*>>()
    private val climateClient = HvacClient()

    private val mcuCallback = object : IMcuCommCallback {
        override fun mcuCommCallback(data: ByteArray?, length: Char) {
            if (data == null || length.code < 5 || length.code > data.size) return
            if ((data[0].toInt() and 0xff) != 0x81 || data[1].toInt() != 0) return
            val status = data[2].toInt() and 0xff
            val value = data[3].toInt() and 0xff
            val source = data[4].toInt() and 0xff
            handler.post { onButtonPacket(status, value, source) }
        }
    }

    private val keyConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            keyServiceBinder = service
            setStatus("OEM-служба кнопок подключена; ожидание MCU и настроек")
            applyCurrentConfiguration()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            keyServiceBinder = null
            filterApplied = false
            setStatus("Соединение с OEM-службой кнопок потеряно")
        }

        override fun onBindingDied(name: ComponentName?) {
            keyServiceBinder = null
            filterApplied = false
            setStatus("OEM-служба кнопок завершилась; выполняется повторное подключение")
            if (bound) runCatching { unbindService(this) }
            bound = false
            handler.postDelayed(::rebindKeyService, 1_000)
        }

        override fun onNullBinding(name: ComponentName?) {
            if (bound) runCatching { unbindService(this) }
            bound = false
            setStatus("OEM-служба кнопок вернула пустое соединение")
            handler.postDelayed(::rebindKeyService, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Запуск переназначения"))
        setStatus("Запуск переназначения кнопок")
        scope.launch {
            store.settings.collect { newSettings ->
                settings = newSettings
                applyCurrentConfiguration()
            }
        }
        worker.execute {
            try {
                catalog = OemKeyCatalogLoader.load(this)
            } catch (exception: Throwable) {
                Log.e(TAG, "Failed loading OEM key catalog", exception)
                rememberFailure("Не удалось прочитать конфигурацию OEM-кнопок", exception)
                setStatus("Не удалось прочитать конфигурацию OEM-кнопок: ${exception.message}")
            }
            try {
                connectMcu()
            } catch (exception: Throwable) {
                Log.e(TAG, "Failed connecting to MCU", exception)
                rememberFailure("MCU недоступен", exception)
                setStatus("MCU недоступен: ${exception.message}")
            }
            handler.post { rebindKeyService() }
            applyCurrentConfiguration()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTORE, ACTION_STOP_AND_RESTORE -> {
                stopAfterRestore = true
                scope.launch { store.setServiceEnabled(false) }
                restorePending = true
                rebindKeyService()
                applyCurrentConfiguration()
            }
            else -> rebindKeyService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        worker.execute {
            if (filterApplied) {
                runCatching { keyServiceBinder?.let(KeyServiceClient::restore) }
                    .onFailure { Log.e(TAG, "Failed restoring OEM keys on service destruction", it) }
            }
            registeredGroups.toList().forEach { group ->
                runCatching { McuCommManager.unregistMcuCommCallback(mcuCallback, group) }
                    .onFailure { Log.e(TAG, "Failed unregistering MCU callback for $group", it) }
            }
            pulses.keys.toList().forEach { key ->
                runCatching { climateClient.setBoolean(key, false) }
                    .onFailure { Log.e(TAG, "Failed clearing HVAC pulse $key", it) }
            }
            worker.shutdown()
        }
        if (bound) {
            runCatching { unbindService(keyConnection) }
                .onFailure { Log.e(TAG, "Failed unbinding OEM key service", it) }
        }
        super.onDestroy()
    }

    private fun connectMcu() {
        System.loadLibrary("mcucommproxy_jni")
        check(McuCommManager.connectMcuCommServer()) { "MCU server connection failed" }
        for (group in 0..13) {
            val id = group.toByte()
            if (McuCommManager.registMcuCommCallback(mcuCallback, id)) {
                registeredGroups += id
            }
        }
        check(registeredGroups.isNotEmpty()) { "No MCU callback groups registered" }
        mcuReady = true
        setStatus("MCU подключён; групп подписки: ${registeredGroups.size}")
    }

    private fun rebindKeyService() {
        if (bound) return
        val intent = Intent().setComponent(ComponentName(KeyServiceClient.PACKAGE, KeyServiceClient.SERVICE))
        try {
            bound = bindService(intent, keyConnection, Context.BIND_AUTO_CREATE)
            if (!bound) setStatus("Не удалось подключиться к OEM-службе кнопок")
        } catch (exception: SecurityException) {
            setStatus("Нет доступа к OEM-службе кнопок: ${exception.message}")
        }
    }

    private fun applyCurrentConfiguration() {
        val currentCatalog = catalog
        val binder = keyServiceBinder
        if (binder == null) return
        worker.execute {
            try {
                val mustRestore = restorePending || !settings.serviceEnabled ||
                    (recoveryPending() && (!mcuReady || currentCatalog == null))
                if (mustRestore) {
                    if (filterApplied || recoveryPending()) {
                        KeyServiceClient.restore(binder)
                        filterApplied = false
                        clearRecoveryPending()
                        setStatus("Штатная обработка OEM-кнопок восстановлена")
                    } else {
                        setStatus(if (settings.serviceEnabled) "Ожидание MCU" else "Переназначение выключено")
                    }
                    restorePending = false
                    if (stopAfterRestore) {
                        stopAfterRestore = false
                        stopSelf()
                    }
                    return@execute
                }
                if (currentCatalog == null) {
                    setStatus("OEM-конфигурация кнопок недоступна")
                    return@execute
                }
                if (!mcuReady) {
                    setStatus("Переназначение не включено: поток MCU недоступен; OEM-кнопки восстановлены")
                    return@execute
                }
                val rules = settings.rules.values.filter(ButtonRule::overridesStock)
                if (rules.isEmpty()) {
                    if (filterApplied || recoveryPending()) {
                        KeyServiceClient.restore(binder)
                        filterApplied = false
                        clearRecoveryPending()
                    }
                    setStatus("Служба активна; переопределённых кнопок пока нет")
                    return@execute
                }
                val whitelist = KeyServiceClient.whitelist(currentCatalog, settings)
                persistRecoveryPending()
                KeyServiceClient.disable(binder, whitelist)
                filterApplied = true
                setStatus("Переназначение активно; фильтруется кнопок: ${rules.size}")
            } catch (exception: Throwable) {
                Log.e(TAG, "Failed applying OEM key filter", exception)
                rememberFailure("Ошибка применения фильтра OEM-кнопок", exception)
                setStatus("Ошибка применения фильтра; восстановление требуется: ${exception.message}")
            }
        }
    }

    private fun onButtonPacket(status: Int, value: Int, source: Int) {
        val buttonTitle = catalog?.buttons?.firstOrNull { it.value == value }?.title
            ?: "0x${value.toString(16).uppercase()}"
        val statusName = packetStatusName(status)
        val foregroundApp = detectForegroundAppPackage()
        ServiceEventLogStore.add(
            "MCU packet 0x81/0x00: status=$statusName($status), button=$buttonTitle (0x${value.toString(16).uppercase()}), source=0x${source.toString(16).uppercase()}, foreground app: $foregroundApp"
        )
        val rule = settings.rules[value]
        if (status == 1 || status == 0) {
            when {
                rule == null || !rule.overridesStock() ->
                    ServiceEventLogStore.add(
                        "Button $buttonTitle $statusName; foreground app: $foregroundApp; no event (no remap)"
                    )
                !filterApplied ->
                    ServiceEventLogStore.add(
                        "Button $buttonTitle $statusName; foreground app: $foregroundApp; no event (remap inactive)"
                    )
                else ->
                    ServiceEventLogStore.add(
                        "Button $buttonTitle $statusName; foreground app: $foregroundApp; waiting for gesture resolution"
                    )
            }
        }
        if (!filterApplied || rule == null || !rule.overridesStock()) return
        val id = source * 256 + value
        val now = android.os.SystemClock.elapsedRealtime()
        when (status) {
            0 -> {
                presses.remove(id)?.timeout?.let(handler::removeCallbacks)
                executeGesture(rule, false, buttonTitle)
            }
            1 -> {
                if (presses[id] == null) {
                    val press = PressState()
                    press.timeout = Runnable {
                        val current = presses[id]
                        if (current === press && !current.longSent) {
                            current.longSent = true
                            executeGesture(rule, true, buttonTitle)
                        }
                    }
                    presses[id] = press
                    handler.postDelayed(press.timeout!!, LONG_PRESS_MS)
                }
            }
            2 -> {
                val press = presses.remove(id) ?: return
                press.timeout?.let(handler::removeCallbacks)
                if (!press.longSent) executeGesture(rule, now - press.startedAt >= LONG_PRESS_MS, buttonTitle)
            }
            3 -> {
                val press = presses.getOrPut(id) { PressState() }
                press.timeout?.let(handler::removeCallbacks)
                if (!press.longSent) {
                    press.longSent = true
                    executeGesture(rule, true, buttonTitle)
                }
            }
            else -> {
                ServiceEventLogStore.add("Button $buttonTitle: unsupported packet status $status")
            }
        }
    }

    private fun executeGesture(rule: ButtonRule, longPress: Boolean, buttonTitle: String) {
        val action = rule.action(longPress)
        val pressType = if (longPress) "long press" else "short press"
        if (action == KeyAction.DEFAULT || action == KeyAction.DISABLE) {
            ServiceEventLogStore.add("Button $buttonTitle ($pressType): no event")
            return
        }
        worker.execute {
            try {
                when (action) {
                    KeyAction.CLIMATE_TEMP_UP -> pulseClimate("ac_temp_inc_front_b")
                    KeyAction.CLIMATE_TEMP_DOWN -> pulseClimate("ac_temp_dec_front_b")
                    KeyAction.CLIMATE_FAN_UP -> pulseClimate("ac_blower_speed_inc_front_b")
                    KeyAction.CLIMATE_FAN_DOWN -> pulseClimate("ac_blower_speed_dec_front_b")
                    KeyAction.CLIMATE_AC_TOGGLE -> {
                        val enabled = climateClient.getBoolean("ac_cold_status_b")
                        check(climateClient.setBoolean("ac_cold_status_b", !enabled)) {
                            "HVAC rejected AC toggle"
                        }
                    }
                    KeyAction.CLIMATE_RECIRC_TOGGLE -> {
                        val mode = climateClient.getInt("ac_loop_mode3_i")
                        check(climateClient.setInt("ac_loop_mode3_i", if (mode == 1) 0 else 1)) {
                            "HVAC rejected recirculation toggle"
                        }
                    }
                    KeyAction.MEDIA_NEXT -> dispatchMediaAction(MediaAction.NEXT)
                    KeyAction.MEDIA_PREVIOUS -> dispatchMediaAction(MediaAction.PREVIOUS)
                    KeyAction.MEDIA_PLAY_PAUSE -> dispatchMediaAction(MediaAction.PLAY_PAUSE)
                    KeyAction.APP_LAUNCH -> launchApp(requireSelectedApp(rule, longPress, action))
                    KeyAction.DEFAULT, KeyAction.DISABLE -> Unit
                }
                ServiceEventLogStore.add("Button $buttonTitle ($pressType): executed ${action.name}")
                setStatus("Выполнено: ${action.title}")
            } catch (exception: Throwable) {
                Log.e(TAG, "Failed executing ${action.name}", exception)
                rememberFailure("Ошибка действия «${action.title}»", exception)
                ServiceEventLogStore.add(
                    "Button $buttonTitle ($pressType): action ${action.name} failed: ${exception.message}"
                )
                setStatus("Ошибка действия «${action.title}»: ${exception.message}")
            }
        }
    }

    private fun pulseClimate(key: String) {
        check(climateClient.setBoolean(key, true)) { "HVAC rejected pulse $key" }
        pulses.remove(key)?.cancel(false)
        pulses[key] = worker.schedule({
            try {
                check(climateClient.setBoolean(key, false)) { "HVAC rejected pulse release $key" }
                pulses.remove(key)
            } catch (exception: Throwable) {
                Log.e(TAG, "Failed releasing HVAC pulse $key", exception)
                rememberFailure("Не удалось завершить климатическую команду $key", exception)
                setStatus("Не удалось завершить климатическую команду $key: ${exception.message}")
            }
        }, HVAC_PULSE_MS, TimeUnit.MILLISECONDS)
    }

    private fun dispatchMediaAction(action: MediaAction) {
        val keyCode = action.keyCode
        val keyName = KeyEvent.keyCodeToString(keyCode)
        val receivers = packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0)
        val receiverPackages = receivers
            .mapNotNull { it.activityInfo?.packageName ?: it.resolvePackageName }
            .distinct()
        val receiverPreview = receiverPackages.take(4).joinToString()
        ServiceEventLogStore.add(
            "Media action ${action.name}: key=$keyName($keyCode), receivers=${receiverPackages.size}" +
                if (receiverPreview.isBlank()) "" else ", top=$receiverPreview"
        )
        if (receiverPackages.isEmpty()) {
            ServiceEventLogStore.add("Media action ${action.name}: no ACTION_MEDIA_BUTTON receivers")
        }
        dispatchMediaButton(keyCode, KeyEvent.ACTION_DOWN)
        dispatchMediaButton(keyCode, KeyEvent.ACTION_UP)
    }

    private fun dispatchMediaButton(keyCode: Int, eventAction: Int) {
        val phase = if (eventAction == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        val mediaIntent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
            Intent.EXTRA_KEY_EVENT,
            KeyEvent(eventAction, keyCode)
        )
        sendOrderedBroadcast(
            mediaIntent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val data = resultData ?: "null"
                    val extras = getResultExtras(false)
                    val extraKeys = extras?.keySet()?.take(4)?.joinToString() ?: "none"
                    ServiceEventLogStore.add(
                        "Media key $phase finished: resultCode=$resultCode, resultData=$data, extras=$extraKeys"
                    )
                }
            },
            handler,
            0,
            null,
            null,
        )
    }

    private fun requireSelectedApp(rule: ButtonRule, longPress: Boolean, action: KeyAction): String {
        val appPackage = rule.appPackage(longPress)
        check(!appPackage.isNullOrBlank()) { "Для действия «${action.title}» не выбрано приложение" }
        return appPackage
    }

    private fun launchApp(appPackage: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(appPackage)
        checkNotNull(launchIntent) { "Приложение $appPackage не найдено или не поддерживает запуск" }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launchIntent)
    }

    private fun recoveryPending(): Boolean =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(RECOVERY_PENDING, false)

    private fun persistRecoveryPending() {
        check(getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(RECOVERY_PENDING, true).commit()) {
            "Could not persist OEM filter recovery marker"
        }
    }

    private fun clearRecoveryPending() {
        check(getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .remove(RECOVERY_PENDING).commit()) {
            "OEM filter restored but recovery marker could not be cleared"
        }
    }

    private fun setStatus(message: String) {
        ServiceEventLogStore.add("Service status updated")
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(STATUS, message).apply()
        handler.post { notificationManager()?.notify(NOTIFICATION_ID, buildNotification(message)) }
    }

    private fun detectForegroundAppPackage(): String {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val process = activityManager.runningAppProcesses
                ?.filter {
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                        it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                }
                ?.minByOrNull { it.importance }
            if (process != null) {
                val packageName = process.pkgList.firstOrNull { it.isNotBlank() } ?: process.processName
                if (!packageName.isNullOrBlank()) return packageName
            }
        }
        val usageManager = getSystemService(UsageStatsManager::class.java)
            ?: return "unknown(no foreground process)"
        val end = System.currentTimeMillis()
        val begin = end - 15_000L
        val packageName = usageManager
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
        return packageName?.takeIf { it.isNotBlank() } ?: "unknown(no foreground process)"
    }

    private fun packetStatusName(status: Int): String = when (status) {
        0 -> "SINGLE"
        1 -> "DOWN"
        2 -> "UP"
        3 -> "LONG_PRESS"
        else -> "UNKNOWN"
    }

    private fun rememberFailure(contextMessage: String, throwable: Throwable) {
        val details = buildString {
            append("Источник: KeyMapperService\n")
            append("Контекст: ").append(contextMessage).append('\n')
            append("Поток: ").append(Thread.currentThread().name).append('\n')
            append(throwable.stackTraceToString())
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(LAST_JAVA_CRASH, details).apply()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Haval key mapper",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Haval M6 Key Mapper")
            .setContentText(message.take(120))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private class PressState(
        val startedAt: Long = android.os.SystemClock.elapsedRealtime(),
        var longSent: Boolean = false,
        var timeout: Runnable? = null,
    )

    private enum class MediaAction(val keyCode: Int) {
        NEXT(KeyEvent.KEYCODE_MEDIA_NEXT),
        PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    }

    companion object {
        const val ACTION_RESTORE = "com.anton111111.haval.m6.keymapper.RESTORE"
        const val ACTION_STOP_AND_RESTORE = "com.anton111111.haval.m6.keymapper.STOP_AND_RESTORE"
        private const val TAG = "HavalKeyMapper"
        private const val CHANNEL_ID = "key_mapper"
        private const val NOTIFICATION_ID = 101
        private const val PREFERENCES = "runtime"
        private const val STATUS = "status"
        private const val LAST_JAVA_CRASH = "last_java_crash"
        private const val RECOVERY_PENDING = "oem_filter_recovery_pending"
        private const val LONG_PRESS_MS = 800L
        private const val HVAC_PULSE_MS = 300L
    }
}

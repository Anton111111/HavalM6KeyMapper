package com.anton111111.haval.m6.keymapper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                if (com.anton111111.haval.m6.keymapper.data.SettingsStore(appContext)
                        .settings.first().serviceEnabled
                ) {
                    appContext.startForegroundService(
                        Intent(appContext, KeyMapperService::class.java),
                    )
                }
            } catch (exception: Exception) {
                Log.e("HavalKeyMapper", "Failed restoring key mapper after boot", exception)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }
}

package com.anton111111.haval.m6.keymapper

import android.app.Application
import android.content.Context
import androidx.core.content.edit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashCapture(applicationContext)
    }

    private fun installCrashCapture(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit(commit = true) {
                    putString(
                        LAST_JAVA_CRASH,
                        "Поток: ${thread.name}\n${throwable.stackTraceToString()}",
                    )
                }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val PREFERENCES = "runtime"
        const val LAST_JAVA_CRASH = "last_java_crash"
    }
}

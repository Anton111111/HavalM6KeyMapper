package com.anton111111.haval.m6.keymapper.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServiceLogEntry(
    val timestamp: String,
    val event: String,
)

object ServiceEventLogStore {
    private val mutableLogs = MutableStateFlow<List<ServiceLogEntry>>(emptyList())
    val logs: StateFlow<List<ServiceLogEntry>> = mutableLogs.asStateFlow()

    @Volatile private var captureEnabled = false

    fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled = enabled
    }

    fun clear() {
        mutableLogs.value = emptyList()
    }

    fun add(event: String) {
        if (!captureEnabled) return
        val entry = ServiceLogEntry(
            timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date()),
            event = event,
        )
        mutableLogs.value = (mutableLogs.value + entry).takeLast(MAX_LOG_MESSAGES)
    }

    fun exportText(): String {
        val snapshot = mutableLogs.value
        if (snapshot.isEmpty()) return "Service log is empty."
        return snapshot.joinToString(separator = "\n") { "[${it.timestamp}] ${it.event}" }
    }

    private const val MAX_LOG_MESSAGES = 20
}

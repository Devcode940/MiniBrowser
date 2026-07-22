package com.minibrowser.telemetry

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object TelemetryManager {
    private const val TAG = "TelemetryManager"
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    @Volatile
    private var initialized = false

    @JvmStatic
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        Log.i(TAG, "OpenTelemetry-lite Engine initialized for MiniBrowser (Kotlin)")
    }

    @JvmStatic
    fun recordMetric(metricName: String, value: Long) {
        counters.putIfAbsent(metricName, AtomicLong(0))
        counters[metricName]?.addAndGet(value)
        Log.d(TAG, "Metric [$metricName] updated: ${counters[metricName]?.get()}")
    }

    @JvmStatic
    fun getMetricValue(metricName: String): Long {
        return counters[metricName]?.get() ?: 0L
    }

    @JvmStatic
    fun recordDownload() {
        recordMetric("downloads.completed", 1)
    }

    @JvmStatic
    fun recordAiQuery() {
        recordMetric("ai.queries.total", 1)
    }

    @JvmStatic
    fun recordBlockedDomain() {
        recordMetric("adblock.domains.blocked", 1)
    }

    @JvmStatic
    fun shutdown() {
        Log.i(TAG, "OpenTelemetry Engine shutdown completed")
        counters.clear()
        initialized = false
    }
}

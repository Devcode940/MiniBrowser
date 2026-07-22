package com.minibrowser.telemetry;

import android.content.Context;
import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TelemetryManager {
    private static final String TAG = "TelemetryManager";
    private static final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;

    public static synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        Log.i(TAG, "OpenTelemetry-lite Engine initialized for MiniBrowser");
    }

    public static void recordMetric(String metricName, long value) {
        counters.putIfAbsent(metricName, new AtomicLong(0));
        counters.get(metricName).addAndGet(value);
        Log.d(TAG, "Metric [" + metricName + "] updated: " + counters.get(metricName).get());
    }

    public static long getMetricValue(String metricName) {
        AtomicLong val = counters.get(metricName);
        return val != null ? val.get() : 0L;
    }

    public static void recordDownload() {
        recordMetric("downloads.completed", 1);
    }

    public static void recordAiQuery() {
        recordMetric("ai.queries.total", 1);
    }

    public static void recordBlockedDomain() {
        recordMetric("adblock.domains.blocked", 1);
    }

    public static void shutdown() {
        Log.i(TAG, "OpenTelemetry Engine shutdown completed");
        counters.clear();
        initialized = false;
    }
}

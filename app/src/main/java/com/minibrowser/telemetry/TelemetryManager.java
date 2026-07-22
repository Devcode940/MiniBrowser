package com.minibrowser.telemetry;

import android.util.Log;

public class TelemetryManager {
    private static final String TAG = "TelemetryManager";
    private static int downloadCount = 0;
    private static int aiQueryCount = 0;
    private static int blockCount = 0;

    public static synchronized void recordDownload() {
        downloadCount++;
        Log.i(TAG, "Telemetry [Downloads]: " + downloadCount);
    }

    public static synchronized void recordAiQuery() {
        aiQueryCount++;
        Log.i(TAG, "Telemetry [AI Queries]: " + aiQueryCount);
    }

    public static synchronized void recordBlockedDomain() {
        blockCount++;
        Log.i(TAG, "Telemetry [Blocked Ads]: " + blockCount);
    }
}

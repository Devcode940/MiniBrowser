package com.minibrowser;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import com.minibrowser.download.DownloadManager;

/**
 * Application entry point. Bootstraps the (singleton) download manager and its
 * notification channel. Keeping this here means the download queue is alive for
 * the whole process lifetime, independent of any single Activity.
 */
public class MiniApp extends Application {

    public static final String CHANNEL_DOWNLOAD = "minibrowser.downloads";

    @Override
    public void onCreate() {
        super.onCreate();
        createDownloadChannel();
        // Initialise the singleton with the app context so it outlives Activities.
        DownloadManager.init(this);
    }

    private void createDownloadChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_DOWNLOAD,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Video/audio download progress");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }
}

package com.minibrowser.database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import com.minibrowser.database.daos.BookmarkDao;
import com.minibrowser.database.daos.DownloadDao;
import com.minibrowser.database.daos.BlocklistDao;
import com.minibrowser.database.daos.CredentialDao;
import com.minibrowser.database.entities.BookmarkEntity;
import com.minibrowser.database.entities.DownloadEntity;
import com.minibrowser.database.entities.BlocklistEntity;
import com.minibrowser.database.entities.CredentialEntity;

@Database(entities = {BookmarkEntity.class, DownloadEntity.class, BlocklistEntity.class, CredentialEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract BookmarkDao bookmarkDao();
    public abstract DownloadDao downloadDao();
    public abstract BlocklistDao blocklistDao();
    public abstract CredentialDao credentialDao();

    private static volatile AppDatabase instance;

    public static AppDatabase getDatabase(final Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "minibrowser_db")
                            .allowMainThreadQueries()
                            .build();
                }
            }
        }
        return instance;
    }
}

package com.minibrowser.database.repositories;

import android.content.Context;
import com.minibrowser.database.AppDatabase;
import com.minibrowser.database.daos.DownloadDao;
import com.minibrowser.database.entities.DownloadEntity;
import java.util.List;

public class DownloadRepository {
    private final DownloadDao dao;

    public DownloadRepository(Context ctx) {
        dao = AppDatabase.getDatabase(ctx).downloadDao();
    }

    public List<DownloadEntity> getAllDownloads() {
        return dao.getAllDownloads();
    }

    public void saveOrUpdate(DownloadEntity download) {
        dao.insertOrUpdate(download);
    }

    public void delete(String id) {
        dao.delete(id);
    }
}

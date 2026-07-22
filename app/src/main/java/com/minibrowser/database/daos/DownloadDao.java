package com.minibrowser.database.daos;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.minibrowser.database.entities.DownloadEntity;
import java.util.List;

@Dao
public interface DownloadDao {
    @Query("SELECT * FROM downloads")
    List<DownloadEntity> getAllDownloads();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(DownloadEntity download);

    @Query("DELETE FROM downloads WHERE id = :id")
    void delete(String id);
}

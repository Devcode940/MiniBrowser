package com.minibrowser.database.daos;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.minibrowser.database.entities.BlocklistEntity;
import java.util.List;

@Dao
public interface BlocklistDao {
    @Query("SELECT * FROM blocklist")
    List<BlocklistEntity> getAllDomains();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<BlocklistEntity> domains);

    @Query("DELETE FROM blocklist")
    void clearAll();
}

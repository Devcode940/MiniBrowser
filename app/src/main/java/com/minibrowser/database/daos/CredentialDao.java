package com.minibrowser.database.daos;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.minibrowser.database.entities.CredentialEntity;
import java.util.List;

@Dao
public interface CredentialDao {
    @Query("SELECT * FROM credentials WHERE domain = :domain AND userId = :userId")
    List<CredentialEntity> getCredentialsForDomain(String domain, String userId);

    @Query("SELECT * FROM credentials WHERE userId = :userId")
    List<CredentialEntity> getAllCredentials(String userId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(CredentialEntity credential);

    @Query("DELETE FROM credentials WHERE id = :id")
    void deleteById(int id);
}

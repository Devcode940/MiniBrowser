package com.minibrowser.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "credentials")
public class CredentialEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String domain;
    public String username;
    public String encryptedPassword;
    public String userId;
}

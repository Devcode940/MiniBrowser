package com.minibrowser.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "blocklist")
public class BlocklistEntity {
    @PrimaryKey
    @androidx.annotation.NonNull
    public String domain;
}

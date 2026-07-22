package com.minibrowser.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "bookmarks")
public class BookmarkEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String title;
    public String url;
    public String userId;
}

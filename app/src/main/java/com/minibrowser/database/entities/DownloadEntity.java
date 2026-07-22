package com.minibrowser.database.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "downloads")
public class DownloadEntity {
    @PrimaryKey
    @androidx.annotation.NonNull
    public String id;
    public String url;
    public String filename;
    public String status;
    public int progress;
    public long downloadedBytes;
    public long totalBytes;
    public String outputPath;
}

package com.minibrowser.database.daos;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.minibrowser.database.entities.BookmarkEntity;
import java.util.List;

@Dao
public interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE userId = :userId")
    List<BookmarkEntity> getBookmarks(String userId);

    @Insert
    void insert(BookmarkEntity bookmark);

    @Query("DELETE FROM bookmarks WHERE url = :url AND userId = :userId")
    void delete(String url, String userId);
}

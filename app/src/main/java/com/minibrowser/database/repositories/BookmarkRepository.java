package com.minibrowser.database.repositories;

import android.content.Context;
import com.minibrowser.database.AppDatabase;
import com.minibrowser.database.daos.BookmarkDao;
import com.minibrowser.database.entities.BookmarkEntity;
import java.util.List;

public class BookmarkRepository {
    private final BookmarkDao dao;

    public BookmarkRepository(Context ctx) {
        dao = AppDatabase.getDatabase(ctx).bookmarkDao();
    }

    public List<BookmarkEntity> getBookmarks(String userId) {
        return dao.getBookmarks(userId);
    }

    public void addBookmark(String title, String url, String userId) {
        BookmarkEntity entity = new BookmarkEntity();
        entity.title = title;
        entity.url = url;
        entity.userId = userId;
        dao.insert(entity);
    }

    public void removeBookmark(String url, String userId) {
        dao.delete(url, userId);
    }
}

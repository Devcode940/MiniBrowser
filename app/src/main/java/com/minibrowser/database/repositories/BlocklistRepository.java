package com.minibrowser.database.repositories;

import android.content.Context;
import com.minibrowser.database.AppDatabase;
import com.minibrowser.database.daos.BlocklistDao;
import com.minibrowser.database.entities.BlocklistEntity;
import java.util.ArrayList;
import java.util.List;

public class BlocklistRepository {
    private final BlocklistDao dao;

    public BlocklistRepository(Context ctx) {
        dao = AppDatabase.getDatabase(ctx).blocklistDao();
    }

    public List<String> getAllDomains() {
        List<BlocklistEntity> list = dao.getAllDomains();
        List<String> domains = new ArrayList<>();
        for (BlocklistEntity e : list) {
            domains.add(e.domain);
        }
        return domains;
    }

    public void insertAll(List<String> domains) {
        List<BlocklistEntity> list = new ArrayList<>();
        for (String d : domains) {
            BlocklistEntity e = new BlocklistEntity();
            e.domain = d;
            list.add(e);
        }
        dao.insertAll(list);
    }

    public void clearAll() {
        dao.clearAll();
    }
}

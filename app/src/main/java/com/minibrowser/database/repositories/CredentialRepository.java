package com.minibrowser.database.repositories;

import android.content.Context;
import com.minibrowser.database.AppDatabase;
import com.minibrowser.database.daos.CredentialDao;
import com.minibrowser.database.entities.CredentialEntity;
import java.util.List;

public class CredentialRepository {
    private final CredentialDao dao;

    public CredentialRepository(Context ctx) {
        dao = AppDatabase.getDatabase(ctx).credentialDao();
    }

    public List<CredentialEntity> getCredentialsForDomain(String domain, String userId) {
        return dao.getCredentialsForDomain(domain, userId);
    }

    public List<CredentialEntity> getAllCredentials(String userId) {
        return dao.getAllCredentials(userId);
    }

    public void saveCredential(String domain, String username, String encryptedPassword, String userId) {
        CredentialEntity entity = new CredentialEntity();
        entity.domain = domain;
        entity.username = username;
        entity.encryptedPassword = encryptedPassword;
        entity.userId = userId;
        dao.insert(entity);
    }

    public void deleteCredential(int id) {
        dao.deleteById(id);
    }
}

package com.minibrowser.security;

public class AuthorizationUtil {
    public static boolean checkAccess(String currentUserId, String resourceOwnerId) {
        if (currentUserId == null || resourceOwnerId == null) return false;
        return currentUserId.equals(resourceOwnerId);
    }
}

package com.minibrowser.security

object AuthorizationUtil {
    private var currentUserId: String? = "default_user"

    @JvmStatic
    fun getCurrentUserId(): String? = currentUserId

    @JvmStatic
    fun setCurrentUserId(userId: String?) {
        currentUserId = userId ?: "default_user"
    }

    @JvmStatic
    fun checkAccess(currentUserId: String?, resourceOwnerId: String?): Boolean {
        if (currentUserId == null || resourceOwnerId == null) return false
        return currentUserId == resourceOwnerId
    }
}

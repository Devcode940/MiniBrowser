package com.minibrowser.tab

import java.util.ArrayList
import java.util.Collections

class TabManager private constructor() {
    private val tabs = Collections.synchronizedList(ArrayList<Tab>())
    private var currentUserId = "default_user"

    companion object {
        @JvmStatic
        val instance: TabManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            TabManager()
        }

        @JvmStatic
        fun get(): TabManager = instance
    }

    fun setUserId(userId: String?) {
        this.currentUserId = userId ?: "default_user"
    }

    fun addTab(tab: Tab?) {
        if (tab != null && currentUserId == tab.userId) {
            tabs.add(tab)
        }
    }

    fun getUserTabs(): List<Tab> {
        val userTabs = ArrayList<Tab>()
        synchronized(tabs) {
            for (t in tabs) {
                if (currentUserId == t.userId) {
                    userTabs.add(t)
                }
            }
        }
        return userTabs
    }

    fun closeTab(id: String) {
        var toRemove: Tab? = null
        synchronized(tabs) {
            for (t in tabs) {
                if (t.id == id && currentUserId == t.userId) {
                    toRemove = t
                    break
                }
            }
            if (toRemove != null) {
                tabs.remove(toRemove)
                toRemove?.core?.destroy()
            }
        }
    }
}

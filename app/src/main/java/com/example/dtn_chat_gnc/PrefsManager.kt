package com.example.dtn_chat_gnc

import android.content.Context
import java.util.UUID

object PrefsManager {
    private const val PREFS_NAME = "MeshPrefs"
    private const val KEY_NODE_ID = "node_unique_id"
    private const val KEY_DISPLAY_NAME = "node_display_name"

    fun getMyNodeId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_NODE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString(KEY_NODE_ID, id).apply()
        }
        return id!!
    }

    // --- NUOVE FUNZIONI PER IL NOME ---
    fun getDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Default: "User_ABCD"
        return prefs.getString(KEY_DISPLAY_NAME, null)
            ?: "User_${getMyNodeId(context).take(4)}"
    }

    fun saveDisplayName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
    }
}
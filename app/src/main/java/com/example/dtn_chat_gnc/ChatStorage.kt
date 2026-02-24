package com.example.dtn_chat_gnc

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChatStorage {
    private const val FILE_NAME = "chat_history.json"
    private val gson = Gson()

    /**
     * Salva la mappa dei messaggi su file JSON.
     * Eseguiamo su Dispatchers.IO per non bloccare la UI.
     */
    suspend fun saveChats(context: Context, history: Map<String, List<ChatMessage>>) {
        withContext(Dispatchers.IO) {
            try {
                // Convertiamo la mappa in JSON
                val json = gson.toJson(history)
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(json)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Carica la mappa dei messaggi dal file JSON.
     */
    suspend fun loadChats(context: Context): Map<String, List<ChatMessage>> {
        return withContext(Dispatchers.IO) {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return@withContext emptyMap()

            try {
                val json = file.readText()
                val type = object : TypeToken<Map<String, List<ChatMessage>>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyMap()
            }
        }
    }
}
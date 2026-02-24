package com.example.dtn_chat_gnc

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChatStorage {
    private const val FILE_NAME = "chat_history.json"
    private const val DTN_FILE_NAME = "dtn_queue.json" // NUOVO FILE
    private val gson = Gson()

    suspend fun saveChats(context: Context, history: Map<String, List<ChatMessage>>) {
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(history)
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(json)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

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

    // --- NUOVE FUNZIONI PER SALVATAGGIO DTN ---

    suspend fun saveDtnQueue(context: Context, queue: List<MessageData>) {
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(queue)
                val file = File(context.filesDir, DTN_FILE_NAME)
                file.writeText(json)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun loadDtnQueue(context: Context): List<MessageData> {
        return withContext(Dispatchers.IO) {
            val file = File(context.filesDir, DTN_FILE_NAME)
            if (!file.exists()) return@withContext emptyList()

            try {
                val json = file.readText()
                val type = object : TypeToken<List<MessageData>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
}
package com.example.dtn_chat_gnc

import com.google.gson.Gson
import java.util.UUID

/**
 * Tipi di pacchetti scambiati nel protocollo Mesh.
 */
enum class PacketType {
    HELLO,
    DFS_TOKEN,
    CPL_TOKEN,
    ALARM,
    MESSAGE // Tipo per la chat
}

/**
 * Payload del messaggio di chat.
 */
data class MessageData(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val destinationId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isDtn: Boolean = false
)

/**
 * Modello del messaggio per la UI e per il salvataggio locale.
 */
data class ChatMessage(
    val senderId: String,
    val text: String,
    val isFromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class SafetyLevel { SAFE, WARNING, DANGER }

/**
 * Nodo dell'albero topologico.
 */
data class TopologyNode(
    val id: String,
    var parentId: String? = null,
    val children: MutableList<TopologyNode> = mutableListOf(),
    var isReady: Boolean = false
) {
    fun findNode(targetId: String): TopologyNode? {
        if (this.id == targetId) return this
        for (child in children) {
            val found = child.findNode(targetId)
            if (found != null) return found
        }
        return null
    }

    fun getAllIds(): Set<String> {
        val ids = mutableSetOf(id)
        children.forEach { ids.addAll(it.getAllIds()) }
        return ids
    }
}

/**
 * Pacchetto principale scambiato via Nearby.
 */
data class MeshPacket(
    val type: PacketType,
    val sourceId: String,
    val senderId: String,
    val senderName: String? = null, // <--- QUESTA RIGA È FONDAMENTALE PER RISOLVERE L'ERRORE
    val timestamp: Long,
    val safetyLevel: SafetyLevel = SafetyLevel.SAFE,
    val rootTopology: TopologyNode? = null,
    val content: String? = null,
    val messageData: MessageData? = null
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): MeshPacket = Gson().fromJson(json, MeshPacket::class.java)
    }
}
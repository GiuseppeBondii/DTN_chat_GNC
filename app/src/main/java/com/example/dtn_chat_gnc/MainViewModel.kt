package com.example.dtn_chat_gnc

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val client = DtnMeshClient(application.applicationContext, viewModelScope)

    var currentChatPartnerId = mutableStateOf<String?>(null)
    var currentChatPartnerName = mutableStateOf<String>("")

    // La UI osserva questa mappa
    val chatHistory = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()

    private val _notificationEvent = MutableSharedFlow<MessageData>()
    val notificationEvent: SharedFlow<MessageData> = _notificationEvent

    init {
        // 1. CARICAMENTO STORICO ALL'AVVIO
        viewModelScope.launch {
            val savedChats = ChatStorage.loadChats(application.applicationContext)

            // Convertiamo la Map<String, List> (statica) in Map<String, SnapshotStateList> (reattiva)
            savedChats.forEach { (peerId, messages) ->
                val stateList = mutableStateListOf<ChatMessage>()
                stateList.addAll(messages)
                chatHistory[peerId] = stateList
            }
        }

        // 2. Ascolto messaggi dalla rete
        viewModelScope.launch {
            client.receivedMessages.collect { msg ->
                addToHistory(msg.senderId, msg.content, isFromMe = false)

                if (currentChatPartnerId.value != msg.senderId) {
                    _notificationEvent.emit(msg)
                }
            }
        }
    }

    fun sendMessage(destId: String, text: String) {
        client.sendMessage(destId, text)
        addToHistory(destId, text, isFromMe = true)
    }

    fun saveNewName(name: String) {
        client.updateName(name)
    }

    private fun addToHistory(peerId: String, text: String, isFromMe: Boolean) {
        // Aggiorna UI
        if (!chatHistory.containsKey(peerId)) {
            chatHistory[peerId] = mutableStateListOf()
        }
        chatHistory[peerId]?.add(ChatMessage(peerId, text, isFromMe))

        // 3. SALVATAGGIO SU FILE (Persistenza)
        // Creiamo una copia "pulita" della mappa da salvare
        val mapToSave = chatHistory.toMap() // Converte in mappa standard

        viewModelScope.launch {
            ChatStorage.saveChats(getApplication(), mapToSave)
        }
    }

    fun startMesh() {
        client.start()
    }

    override fun onCleared() {
        super.onCleared()
        client.stop()
    }
}
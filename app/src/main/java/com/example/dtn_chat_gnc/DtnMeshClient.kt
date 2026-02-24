package com.example.dtn_chat_gnc

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Semplice data class per la UI
data class Peer(val id: String, val name: String)

class DtnMeshClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _receivedMessages = MutableSharedFlow<MessageData>()
    val receivedMessages: SharedFlow<MessageData> = _receivedMessages

    private val _logs = MutableSharedFlow<String>()
    val logs: SharedFlow<String> = _logs

    // NUOVO: Lista di oggetti Peer (ID + Nome)
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private var meshManager: MeshManager? = null

    val myNodeId: String
        get() = PrefsManager.getMyNodeId(context)

    // NUOVO: Getter per il nome corrente
    val myDisplayName: String
        get() = PrefsManager.getDisplayName(context)

    fun start() {
        if (meshManager != null) return

        meshManager = MeshManager(
            context,
            scope,
            onLog = { logMsg -> scope.launch { _logs.emit(logMsg) } },
            onPeersUpdated = { peerMap ->
                scope.launch {
                    // Convertiamo la mappa in lista e rimuoviamo noi stessi
                    val list = peerMap.map { Peer(it.key, it.value) }
                        .filter { it.id != myNodeId }
                        .sortedBy { it.name }
                    _peers.emit(list)
                }
            }
        )

        meshManager?.setMessageListener { msg ->
            scope.launch { _receivedMessages.emit(msg) }
        }

        meshManager?.startMesh()
    }

    fun stop() {
        meshManager?.stop()
        meshManager = null
    }

    // NUOVO: Funzione per cambiare nome e riavviare la rete
    fun updateName(newName: String) {
        PrefsManager.saveDisplayName(context, newName)
        // Dobbiamo riavviare per inviare il nuovo nome agli altri (via HELLO)
        stop()
        start()
    }

    fun sendMessage(destId: String, content: String) {
        meshManager?.sendMessage(destId, content)
    }
}
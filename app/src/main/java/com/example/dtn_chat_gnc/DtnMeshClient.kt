package com.example.dtn_chat_gnc

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Peer(val id: String, val name: String)

class DtnMeshClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _receivedMessages = MutableSharedFlow<MessageData>()
    val receivedMessages: SharedFlow<MessageData> = _receivedMessages

    private val _logs = MutableSharedFlow<String>()
    val logs: SharedFlow<String> = _logs

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private val _topology = MutableStateFlow<String>("Nessuna rete")
    val topology: StateFlow<String> = _topology

    // --- ECCO LA VARIABILE CHE MANCAVA ---
    private val _dtnQueueSize = MutableStateFlow<Int>(0)
    val dtnQueueSize: StateFlow<Int> = _dtnQueueSize

    private var meshManager: MeshManager? = null

    val myNodeId: String get() = PrefsManager.getMyNodeId(context)
    val myDisplayName: String get() = PrefsManager.getDisplayName(context)

    fun start() {
        if (meshManager != null) return

        meshManager = MeshManager(
            context,
            scope,
            onLog = { logMsg -> scope.launch { _logs.emit(logMsg) } },
            onPeersUpdated = { peerMap ->
                scope.launch {
                    val list = peerMap.map { Peer(it.key, it.value) }.filter { it.id != myNodeId }.sortedBy { it.name }
                    _peers.emit(list)
                }
            },
            onTopologyUpdated = { treeString -> scope.launch { _topology.emit(treeString) } },
            // Passiamo il valore della coda alla variabile _dtnQueueSize
            onDtnQueueUpdated = { size -> scope.launch { _dtnQueueSize.emit(size) } }
        )

        meshManager?.setMessageListener { msg -> scope.launch { _receivedMessages.emit(msg) } }
        meshManager?.startMesh()
    }

    fun stop() {
        meshManager?.stop()
        meshManager = null
    }

    fun updateName(newName: String) {
        PrefsManager.saveDisplayName(context, newName)
        stop()
        start()
    }

    fun sendMessage(destId: String, content: String) {
        meshManager?.sendMessage(destId, content)
    }
}
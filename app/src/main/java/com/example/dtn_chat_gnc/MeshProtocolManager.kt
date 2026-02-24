package com.example.dtn_chat_gnc

import kotlinx.coroutines.*

class MeshProtocolManager(
    val myId: String,
    val myName: String,
    private val scope: CoroutineScope,
    private val sendFunc: (String, String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onBossElected: (Boolean) -> Unit,
    private val onAlarm: (String) -> Unit,
    private val onMessageReceived: (MessageData) -> Unit,
    // Callback aggiornata: restituisce una Mappa [ID -> Nome] invece di un Set
    private val onPeersUpdated: (Map<String, String>) -> Unit
) {
    private val physicalNeighbors = mutableSetOf<String>()
    private val endpointMap = mutableMapOf<String, String>()
    private val reverseEndpointMap = mutableMapOf<String, String>()

    // NUOVO: Mappa per ricordare i nomi associati agli ID (es. "A1B2" -> "Mario")
    private val peerNames = mutableMapOf<String, String>()

    private var activeBundleTimestamp: Long = 0
    private var activeBundleSourceId: String = ""
    private var currentSafetyLevel = SafetyLevel.SAFE
    private var currentDfsParentNodeId: String? = null
    private val downstreamRoutingTable = mutableMapOf<String, String>()
    private var electionJob: Job? = null

    // --- FASE 1: HANDSHAKE ---
    fun handleHandshake(endpointId: String, packet: MeshPacket) {
        val neighborNodeId = packet.senderId
        // NUOVO: Salviamo il nome del vicino se presente
        val neighborName = packet.senderName ?: "Unknown"
        peerNames[neighborNodeId] = neighborName

        endpointMap[neighborNodeId] = endpointId
        reverseEndpointMap[endpointId] = neighborNodeId

        if (!physicalNeighbors.contains(neighborNodeId)) {
            physicalNeighbors.add(neighborNodeId)
            onLog("[NET] Connesso: $neighborName ($neighborNodeId)")
            restartElectionTimer()
        }
    }

    fun onPeerDisconnected(endpointId: String) {
        val nodeId = reverseEndpointMap[endpointId] ?: return
        physicalNeighbors.remove(nodeId)
        endpointMap.remove(nodeId)
        reverseEndpointMap.remove(endpointId)
        // peerNames.remove(nodeId) // Opzionale: possiamo tenerlo in cache o rimuoverlo
        onLog("[NET] Disconnesso: $nodeId")
        if (nodeId == currentDfsParentNodeId) handleOrphanScenario()

        // Notifica UI con la lista aggiornata
        updateUiPeers(lastKnownTreeIds)
    }

    // Variabile di appoggio per ricordarci l'ultimo stato dell'albero
    private var lastKnownTreeIds: Set<String> = emptySet()

    // --- HELPER PER UI ---
    private fun updateUiPeers(allIdsInTree: Set<String>) {
        lastKnownTreeIds = allIdsInTree
        // Costruiamo la mappa da inviare alla UI
        val displayMap = mutableMapOf<String, String>()
        allIdsInTree.forEach { id ->
            // Se conosciamo il nome bene, altrimenti usiamo l'ID o "Unknown"
            displayMap[id] = peerNames[id] ?: "Nodo $id"
        }
        onPeersUpdated(displayMap)
    }

    // --- FASE 2, 3, 4 (DFS & Routing) ---
    // (Il codice DFS è quasi identico, cambia solo la chiamata a updateUiPeers)

    private fun restartElectionTimer() {
        electionJob?.cancel()
        electionJob = scope.launch {
            delay(5000)
            if (System.currentTimeMillis() - activeBundleTimestamp > 8000) {
                startNewDfsRound(isMaintenance = false)
            }
        }
    }

    fun startNewDfsRound(isMaintenance: Boolean) {
        if (!isMaintenance) downstreamRoutingTable.clear()
        val timestamp = System.currentTimeMillis()
        activeBundleTimestamp = timestamp
        activeBundleSourceId = myId
        currentDfsParentNodeId = null

        val root = TopologyNode(id = myId)
        val packet = MeshPacket(
            type = if (isMaintenance) PacketType.CPL_TOKEN else PacketType.DFS_TOKEN,
            sourceId = myId,
            senderId = myId,
            timestamp = timestamp,
            safetyLevel = currentSafetyLevel,
            rootTopology = root
        )
        if (!isMaintenance) onLog("\n[DFS] CANDIDATURA LEADER")
        processDfsToken(packet)
    }

    fun handleIncomingPacket(endpointId: String, json: String) {
        val packet = try { MeshPacket.fromJson(json) } catch (e: Exception) { return }
        val senderNodeId = packet.senderId

        // Se riceviamo un pacchetto da qualcuno che non abbiamo mappato (es. messaggi relay)
        // salviamo il suo nome se presente nel pacchetto (utile per chat diretta)
        if (packet.senderName != null) {
            peerNames[senderNodeId] = packet.senderName
        }

        if (!endpointMap.containsKey(senderNodeId)) {
            endpointMap[senderNodeId] = endpointId
            reverseEndpointMap[endpointId] = senderNodeId
        }

        when (packet.type) {
            PacketType.HELLO -> handleHandshake(endpointId, packet)

            PacketType.DFS_TOKEN, PacketType.CPL_TOKEN -> {
                val isNewer = packet.timestamp > activeBundleTimestamp
                val isSameRound = (packet.timestamp == activeBundleTimestamp && packet.sourceId == activeBundleSourceId)

                if (isNewer || (packet.timestamp == activeBundleTimestamp && packet.sourceId > activeBundleSourceId)) {
                    onLog("\n[DFS] Nuovo Leader: ${packet.sourceId}")
                    activeBundleTimestamp = packet.timestamp
                    activeBundleSourceId = packet.sourceId
                    currentDfsParentNodeId = null
                    processDfsToken(packet)
                } else if (isSameRound) {
                    processDfsToken(packet)
                }
            }
            PacketType.ALARM -> {
                if (packet.safetyLevel == SafetyLevel.DANGER) {
                    onAlarm(packet.content ?: "DANGER")
                    physicalNeighbors.forEach { if (it != senderNodeId) sendToNode(it, packet) }
                }
            }
            PacketType.MESSAGE -> {
                packet.messageData?.let { msg ->
                    if (msg.destinationId == myId) {
                        onMessageReceived(msg)
                    } else {
                        routeMessage(msg)
                    }
                }
            }
        }
    }

    private fun processDfsToken(packet: MeshPacket) {
        val tree = packet.rootTopology ?: return
        val sender = packet.senderId

        // 1. AGGIORNAMENTO UI: Passiamo per la funzione helper che associa i nomi
        updateUiPeers(tree.getAllIds())

        val myNodeInTree = tree.findNode(myId) ?: return
        if (myNodeInTree.isReady) {
            if (packet.sourceId == myId) { /* Boss loop complete */ } else return
        }

        val isSenderMyChild = myNodeInTree.children.any { it.id == sender }
        if (!isSenderMyChild && sender != myId) {
            currentDfsParentNodeId = sender
        }

        if (isSenderMyChild) {
            val childBranch = tree.findNode(sender)
            childBranch?.getAllIds()?.forEach { descendantId ->
                downstreamRoutingTable[descendantId] = sender
            }
        }

        val visitedIds = tree.getAllIds()
        val unvisitedNeighbors = physicalNeighbors.filter { !visitedIds.contains(it) }

        if (unvisitedNeighbors.isNotEmpty()) {
            val nextChildId = unvisitedNeighbors[0]
            myNodeInTree.children.add(TopologyNode(id = nextChildId, parentId = myId))
            val newPacket = packet.copy(senderId = myId, rootTopology = tree)
            sendToNode(nextChildId, newPacket)
        } else {
            myNodeInTree.isReady = true
            if (currentDfsParentNodeId != null) {
                val newPacket = packet.copy(senderId = myId, rootTopology = tree)
                sendToNode(currentDfsParentNodeId!!, newPacket)
            } else if (packet.sourceId == myId) {
                onBossElected(true)
                onLog("[DFS] RE DELLA RETE")
                scope.launch { delay(15000); startNewDfsRound(true) }
            } else {
                handleOrphanScenario()
            }
        }
    }

    // --- FASE 5: MESSAGGISTICA ---
    fun sendMessage(destId: String, content: String) {
        val msg = MessageData(senderId = myId, destinationId = destId, content = content)
        routeMessage(msg)
    }

    private fun routeMessage(msg: MessageData) {
        if (msg.destinationId == myId) {
            onMessageReceived(msg)
            return
        }
        val nextHop = if (downstreamRoutingTable.containsKey(msg.destinationId)) {
            downstreamRoutingTable[msg.destinationId]
        } else {
            currentDfsParentNodeId
        }

        if (nextHop != null && endpointMap.containsKey(nextHop)) {
            val packet = MeshPacket(
                type = PacketType.MESSAGE,
                sourceId = myId,
                senderId = myId,
                senderName = myName, // Inseriamo il nome anche nei messaggi relay per sicurezza
                timestamp = System.currentTimeMillis(),
                messageData = msg
            )
            sendToNode(nextHop, packet)
        } else {
            onLog("[ERR] Destinazione ${msg.destinationId} irraggiungibile.")
        }
    }

    // --- UTILITIES ---
    fun sendHelloTo(endpointId: String) {
        // NUOVO: Inseriamo myName nel pacchetto HELLO
        val packet = MeshPacket(
            type = PacketType.HELLO,
            sourceId = myId,
            senderId = myId,
            senderName = myName,
            timestamp = System.currentTimeMillis()
        )
        sendFunc(endpointId, packet.toJson())
    }

    private fun sendToNode(nodeId: String, packet: MeshPacket) {
        endpointMap[nodeId]?.let { sendFunc(it, packet.toJson()) }
    }

    private fun handleOrphanScenario() { startNewDfsRound(true) }
}
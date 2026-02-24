package com.example.dtn_chat_gnc

import kotlinx.coroutines.*
import kotlin.math.sqrt

class MeshProtocolManager(
    val myId: String,
    val myName: String,
    private val scope: CoroutineScope,
    private val sendFunc: (String, String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onBossElected: (Boolean) -> Unit,
    private val onAlarm: (String) -> Unit,
    private val onMessageReceived: (MessageData) -> Unit,
    private val onPeersUpdated: (Map<String, String>) -> Unit,
    private val onTopologyUpdated: (String) -> Unit,
    private val onDtnQueueUpdated: (Int) -> Unit
) {
    private val physicalNeighbors = mutableSetOf<String>()
    private val endpointMap = mutableMapOf<String, String>()
    private val reverseEndpointMap = mutableMapOf<String, String>()
    private val peerNames = mutableMapOf<String, String>()

    private var activeBundleTimestamp: Long = 0
    private var activeBundleSourceId: String = ""
    private var currentSafetyLevel = SafetyLevel.SAFE
    private var currentDfsParentNodeId: String? = null
    private val downstreamRoutingTable = mutableMapOf<String, String>()
    private var electionJob: Job? = null
    private var lastKnownTree: TopologyNode? = null

    // --- VARIABILI DTN ---
    private val dtnQueue = mutableListOf<MessageData>()
    private val MAX_DTN_QUEUE_SIZE = 5

    fun handleHandshake(endpointId: String, packet: MeshPacket) {
        val neighborNodeId = packet.senderId
        val neighborName = packet.senderName ?: "Unknown"
        peerNames[neighborNodeId] = neighborName
        endpointMap[neighborNodeId] = endpointId
        reverseEndpointMap[endpointId] = neighborNodeId

        if (!physicalNeighbors.contains(neighborNodeId)) {
            physicalNeighbors.add(neighborNodeId)
            onLog("[NET] Connesso: $neighborName ($neighborNodeId)")

            startNewDfsRound(true)
            restartElectionTimer()
        }
    }

    fun onPeerDisconnected(endpointId: String) {
        val nodeId = reverseEndpointMap[endpointId] ?: return
        physicalNeighbors.remove(nodeId)
        endpointMap.remove(nodeId)
        reverseEndpointMap.remove(endpointId)
        onLog("[NET] Disconnesso: $nodeId")

        startNewDfsRound(true)
        updateUiPeers(lastKnownTree)
    }

    private fun updateUiPeers(tree: TopologyNode?) {
        if (tree == null) return
        lastKnownTree = tree
        val allIds = tree.getAllIds()
        val displayMap = mutableMapOf<String, String>()
        allIds.forEach { id -> displayMap[id] = peerNames[id] ?: "Nodo $id" }
        onPeersUpdated(displayMap)
        onTopologyUpdated(buildTreeString(tree))
    }

    private fun buildTreeString(node: TopologyNode, depth: Int = 0): String {
        val indent = "  ".repeat(depth)
        val name = peerNames[node.id] ?: node.id.take(4)
        val meMarker = if (node.id == myId) " (Tu)" else ""
        val sb = StringBuilder("$indent- $name$meMarker\n")
        node.children.forEach { sb.append(buildTreeString(it, depth + 1)) }
        return sb.toString()
    }

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
            sourceId = myId, senderId = myId, timestamp = timestamp,
            safetyLevel = currentSafetyLevel, rootTopology = root
        )
        if (!isMaintenance) onLog("\n[DFS] CANDIDATURA LEADER")
        processDfsToken(packet)
    }

    fun handleIncomingPacket(endpointId: String, json: String) {
        val packet = try { MeshPacket.fromJson(json) } catch (e: Exception) { return }
        val senderNodeId = packet.senderId

        if (packet.senderName != null) peerNames[senderNodeId] = packet.senderName
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

                    // --- MODIFICA UI: AVVISO CAMBIO LEADER ---
                    if (activeBundleSourceId == myId && packet.sourceId != myId) {
                        onLog("[DFS] Persa leadership. Nuovo Leader: ${packet.sourceId.take(4)}")
                    }

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
                    if (msg.destinationId == myId) onMessageReceived(msg) else routeMessage(msg)
                }
            }
        }
    }

    private fun processDfsToken(packet: MeshPacket) {
        val tree = packet.rootTopology ?: return
        val sender = packet.senderId

        updateUiPeers(tree)

        val myNodeInTree = tree.findNode(myId) ?: return
        if (myNodeInTree.isReady) { if (packet.sourceId == myId) { } else return }

        val isSenderMyChild = myNodeInTree.children.any { it.id == sender }
        if (!isSenderMyChild && sender != myId) currentDfsParentNodeId = sender

        if (isSenderMyChild) {
            tree.findNode(sender)?.getAllIds()?.forEach { downstreamRoutingTable[it] = sender }
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
                // --- MODIFICA UI: LOG PULITO SENZA ALBERO ---
                onLog("[DFS] Elezione completata. Sono il LEADER.")
                scope.launch { delay(15000); startNewDfsRound(true) }
            }
        }

        checkDtnDelivery()
    }

    fun sendMessage(destId: String, content: String) {
        val msg = MessageData(senderId = myId, destinationId = destId, content = content)
        routeMessage(msg)
    }

    private fun routeMessage(msg: MessageData) {
        if (msg.destinationId == myId) {
            onMessageReceived(msg)
            return
        }

        val removed = dtnQueue.removeAll { it.id == msg.id }
        if (removed) onDtnQueueUpdated(dtnQueue.size)

        val destInTree = lastKnownTree?.findNode(msg.destinationId) != null
        var routedSuccessfully = false

        if (destInTree) {
            val nextHop = if (endpointMap.containsKey(msg.destinationId)) {
                msg.destinationId
            } else if (downstreamRoutingTable.containsKey(msg.destinationId)) {
                downstreamRoutingTable[msg.destinationId]
            } else {
                currentDfsParentNodeId
            }

            if (nextHop != null && endpointMap.containsKey(nextHop)) {
                val packet = MeshPacket(
                    type = PacketType.MESSAGE, sourceId = myId, senderId = myId, senderName = myName,
                    timestamp = System.currentTimeMillis(), messageData = msg.copy(isDtn = false)
                )
                sendToNode(nextHop, packet)
                onLog("[NET] Inviato MSG a ${msg.destinationId.take(4)} via ${nextHop.take(4)}")
                routedSuccessfully = true
            }
        }

        if (!routedSuccessfully) {
            onLog("[DTN] Rotta non disponibile per ${msg.destinationId.take(4)}. Passo a DTN.")
            if (msg.senderId == myId && !msg.isDtn) {
                initiateSprayAndWait(msg)
            } else {
                enqueueDtnMessage(msg)
            }
        }
    }

    private fun initiateSprayAndWait(msg: MessageData) {
        val n = lastKnownTree?.getAllIds()?.size ?: 1
        var L = sqrt(n.toDouble()).toInt()
        if (L < 1) L = 1

        onLog("[DTN] Dest. Offline. Rete N=$n, genero L=$L copie.")

        enqueueDtnMessage(msg)

        val sprayCount = L - 1
        if (sprayCount > 0) {
            val neighborsToSpray = physicalNeighbors.shuffled().take(sprayCount)

            neighborsToSpray.forEach { neighborId ->
                val packet = MeshPacket(
                    type = PacketType.MESSAGE,
                    sourceId = myId, senderId = myId, senderName = myName,
                    timestamp = System.currentTimeMillis(),
                    messageData = msg.copy(isDtn = true)
                )
                sendToNode(neighborId, packet)
                onLog("[DTN] Copia spruzzata a ${neighborId.take(4)}")
            }
        }
    }

    private fun enqueueDtnMessage(msg: MessageData) {
        if (dtnQueue.any { it.id == msg.id }) return

        if (dtnQueue.size >= MAX_DTN_QUEUE_SIZE) {
            val oldest = dtnQueue.removeAt(0)
            delegateDtnBundle(oldest)
        }

        dtnQueue.add(msg.copy(isDtn = true))
        onDtnQueueUpdated(dtnQueue.size)
    }

    private fun delegateDtnBundle(msg: MessageData) {
        val neighbors = physicalNeighbors.toList()
        if (neighbors.isNotEmpty()) {
            val chosenPeer = neighbors.random()
            onLog("[DTN] Buffer Pieno! Delego bundle a ${chosenPeer.take(4)}")
            val packet = MeshPacket(
                type = PacketType.MESSAGE, sourceId = myId, senderId = myId, senderName = myName,
                timestamp = System.currentTimeMillis(), messageData = msg.copy(isDtn = true)
            )
            sendToNode(chosenPeer, packet)
        }
    }

    private fun checkDtnDelivery() {
        val tree = lastKnownTree ?: return
        val toDeliver = dtnQueue.filter { tree.findNode(it.destinationId) != null }.toList()

        toDeliver.forEach { msg ->
            onLog("[DTN] Target ${msg.destinationId.take(4)} online! Consegna in corso...")
            routeMessage(msg)
        }
    }

    fun sendHelloTo(endpointId: String) {
        val packet = MeshPacket(
            type = PacketType.HELLO, sourceId = myId, senderId = myId, senderName = myName,
            timestamp = System.currentTimeMillis()
        )
        sendFunc(endpointId, packet.toJson())
    }

    private fun sendToNode(nodeId: String, packet: MeshPacket) {
        endpointMap[nodeId]?.let { sendFunc(it, packet.toJson()) }
    }
}
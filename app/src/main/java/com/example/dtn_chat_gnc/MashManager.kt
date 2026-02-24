package com.example.dtn_chat_gnc

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import java.nio.charset.StandardCharsets

class MeshManager(
    context: Context,
    scope: CoroutineScope,
    private val onLog: (String) -> Unit,
    private val onPeersUpdated: (Map<String, String>) -> Unit,
    private val onTopologyUpdated: (String) -> Unit,
    // NUOVO: Aggiungiamo la callback della coda DTN
    private val onDtnQueueUpdated: (Int) -> Unit
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "com.mesh.protocol.CHAT_V1"
    private val strategy = Strategy.P2P_CLUSTER
    val myId = PrefsManager.getMyNodeId(context)
    val myName = PrefsManager.getDisplayName(context)

    private var messageListener: ((MessageData) -> Unit)? = null

    fun setMessageListener(listener: (MessageData) -> Unit) {
        this.messageListener = listener
    }

    private val protocol = MeshProtocolManager(
        myId = myId,
        myName = myName,
        scope = scope,
        sendFunc = { endpointId, json -> sendPayload(endpointId, json) },
        onLog = onLog,
        onBossElected = { },
        onAlarm = { msg -> onLog("[ALARM] $msg") },
        onMessageReceived = { msg -> messageListener?.invoke(msg) },
        onPeersUpdated = onPeersUpdated,
        onTopologyUpdated = onTopologyUpdated,
        onDtnQueueUpdated = onDtnQueueUpdated // <--- NUOVO
    )

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) protocol.sendHelloTo(endpointId)
        }
        override fun onDisconnected(endpointId: String) {
            protocol.onPeerDisconnected(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    val json = String(bytes, StandardCharsets.UTF_8)
                    protocol.handleIncomingPacket(endpointId, json)
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun startMesh() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising("NODE_$myId", serviceId, connectionLifecycleCallback, options)

        val discOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                connectionsClient.requestConnection(myId, endpointId, connectionLifecycleCallback)
            }
            override fun onEndpointLost(endpointId: String) {}
        }, discOptions)
    }

    private fun sendPayload(endpointId: String, json: String) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8)))
    }

    fun stop() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    fun sendMessage(destId: String, content: String) {
        protocol.sendMessage(destId, content)
    }
}
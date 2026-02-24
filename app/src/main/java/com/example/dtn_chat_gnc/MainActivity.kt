package com.example.dtn_chat_gnc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        checkPermissions()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val viewModel = viewModel<MainViewModel>()
                LaunchedEffect(Unit) {
                    viewModel.notificationEvent.collect { msg -> showNotification(msg.senderId, msg.content) }
                }
                ChatApp(viewModel)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("MESH_CHANNEL_ID", "Mesh Chat", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showNotification(senderId: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "MESH_CHANNEL_ID")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Da: $senderId")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        NotificationManagerCompat.from(this).notify(senderId.hashCode(), builder.build())
    }

    private fun checkPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) p.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        if (Build.VERSION.SDK_INT >= 33) { p.add(Manifest.permission.NEARBY_WIFI_DEVICES); p.add(Manifest.permission.POST_NOTIFICATIONS) }
        requestPermissionLauncher.launch(p.toTypedArray())
    }
}

@Composable
fun ChatApp(viewModel: MainViewModel) {
    val myName = viewModel.client.myDisplayName
    val peers by viewModel.client.peers.collectAsState(initial = emptyList())
    val logs by viewModel.client.logs.collectAsState(initial = "")
    val topology by viewModel.client.topology.collectAsState(initial = "Nessuna rete")
    // NUOVO: Ascoltiamo la coda DTN
    val dtnQueueSize by viewModel.client.dtnQueueSize.collectAsState(initial = 0)
    val currentPartnerId = viewModel.currentChatPartnerId.value

    var showNameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.startMesh() }

    if (showNameDialog) {
        NameEditDialog(
            currentName = myName,
            onDismiss = { showNameDialog = false },
            onSave = { newName -> viewModel.saveNewName(newName); showNameDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (currentPartnerId == null) {
                val onlineIds = peers.map { it.id }.toSet()
                val onlineChatted = peers.filter { viewModel.chatHistory.containsKey(it.id) }.sortedBy { it.name }
                val onlineNew = peers.filter { !viewModel.chatHistory.containsKey(it.id) }.sortedBy { it.name }
                val offlineChattedIds = viewModel.chatHistory.keys.filter { it !in onlineIds }
                val offlineChatted = offlineChattedIds.map { id ->
                    Peer(id, viewModel.knownPeers[id] ?: "Utente Sconosciuto")
                }.sortedBy { it.name }

                PeerListScreen(
                    myName = myName,
                    onlineChatted = onlineChatted,
                    onlineNew = onlineNew,
                    offlineChatted = offlineChatted,
                    onPeerClick = { peer ->
                        viewModel.currentChatPartnerId.value = peer.id
                        viewModel.currentChatPartnerName.value = peer.name
                    },
                    onStart = { viewModel.startMesh() },
                    onEditName = { showNameDialog = true }
                )
            } else {
                val messages = viewModel.chatHistory[currentPartnerId] ?: emptyList()
                ChatScreen(
                    partnerName = viewModel.currentChatPartnerName.value,
                    messages = messages,
                    onSendMessage = { text -> viewModel.sendMessage(currentPartnerId, text) },
                    onBack = { viewModel.currentChatPartnerId.value = null }
                )
            }
        }

        HorizontalDivider(color = Color.Green, thickness = 1.dp)
        // Passiamo la variabile DTN al pannello Debug
        DebugPanel(logs = logs, topology = topology, dtnQueueSize = dtnQueueSize)
    }
}

@Composable
fun NameEditDialog(currentName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica Nome") },
        text = { TextField(value = text, onValueChange = { text = it }) },
        confirmButton = { Button(onClick = { if(text.isNotBlank()) onSave(text) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(myName: String, onlineChatted: List<Peer>, onlineNew: List<Peer>, offlineChatted: List<Peer>, onPeerClick: (Peer) -> Unit, onStart: () -> Unit, onEditName: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("D2D Chat"); Text(myName, fontSize = 12.sp, color = Color.Green) }
                        IconButton(onClick = onEditName) { Icon(Icons.Default.Edit, "Edit Name", modifier = Modifier.size(16.dp)) }
                    }
                },
                actions = { IconButton(onClick = onStart) { Icon(Icons.Default.Refresh, "Start", tint = Color.Green) } }
            )
        }
    ) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f)) {
                if (onlineChatted.isNotEmpty()) {
                    item { Text("Online (Chat Recenti)", Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp), fontWeight = FontWeight.Bold, color = Color.Green) }
                    items(onlineChatted) { peer -> PeerItem(peer, onPeerClick, isOffline = false) }
                }
                if (onlineNew.isNotEmpty()) {
                    item { Text("Online (Nuovi)", Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp), fontWeight = FontWeight.Bold) }
                    items(onlineNew) { peer -> PeerItem(peer, onPeerClick, isOffline = false) }
                }
                if (offlineChatted.isNotEmpty()) {
                    item { Text("Offline (Storico)", Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp), fontWeight = FontWeight.Bold, color = Color.Gray) }
                    items(offlineChatted) { peer -> PeerItem(peer, onPeerClick, isOffline = true) }
                }
                if (onlineChatted.isEmpty() && onlineNew.isEmpty() && offlineChatted.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { Text("Nessuna chat e nessuno connesso.", color = Color.Gray) } }
                }
            }
        }
    }
}

@Composable
fun PeerItem(peer: Peer, onClick: (Peer) -> Unit, isOffline: Boolean = false) {
    val alpha = if (isOffline) 0.5f else 1f
    Card(Modifier.fillMaxWidth().padding(8.dp).clickable { onClick(peer) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D).copy(alpha = alpha))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, null, tint = Color.White.copy(alpha = alpha))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(peer.name, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = alpha))
                Text("ID: ${peer.id.take(4)}...", fontSize = 10.sp, color = Color.Gray.copy(alpha = alpha))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(partnerName: String, messages: List<ChatMessage>, onSendMessage: (String) -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    BackHandler { onBack() }
    Scaffold(
        topBar = { TopAppBar(title = { Text(partnerName) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }
    ) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).padding(8.dp), reverseLayout = true) {
                items(messages.reversed()) { msg -> MessageBubble(msg) }
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), placeholder = { Text("Scrivi...") }, shape = RoundedCornerShape(24.dp), colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(onClick = { if (text.isNotBlank()) { onSendMessage(text); text = "" } }, containerColor = Color(0xFF4CAF50)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val align = if (msg.isFromMe) Alignment.End else Alignment.Start
    val color = if (msg.isFromMe) Color(0xFF4CAF50) else Color(0xFF424242)
    val shape = if (msg.isFromMe) RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(Modifier.padding(4.dp).background(color, shape).padding(12.dp)) { Text(msg.text, color = Color.White) }
    }
}

@Composable
fun DebugPanel(logs: String, topology: String, dtnQueueSize: Int) {
    Row(modifier = Modifier.fillMaxWidth().height(200.dp).background(Color(0xFF121212))) {
        Column(modifier = Modifier.weight(1.5f).padding(8.dp)) {
            Text("LOG DI SISTEMA", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = logs.takeLast(800), color = Color.Green, fontSize = 9.sp, lineHeight = 12.sp, modifier = Modifier.fillMaxSize())
        }
        VerticalDivider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = Color.DarkGray)
        Column(modifier = Modifier.weight(1f).padding(8.dp)) {
            // NUOVO: Riga con il Titolo e il contatore DTN giallo/grigio
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RETE ATTUALE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                val dtnColor = if (dtnQueueSize > 0) Color.Yellow else Color.Gray
                Text("DTN: $dtnQueueSize/5", color = dtnColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = topology, color = Color(0xFF03A9F4), fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxSize())
        }
    }
}
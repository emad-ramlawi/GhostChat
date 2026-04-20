package com.ghostchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ghostchat.app.ui.ChatScreen
import com.ghostchat.app.ui.HomeScreen
import com.ghostchat.app.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkSchemeFallback()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    val vm: ChatViewModel = viewModel()
    val connState by vm.connState.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()
    val errorMessage by vm.errors.collectAsState()

    var openedPeer by remember { mutableStateOf<String?>(null) }
    val peer = openedPeer

    if (peer == null) {
        HomeScreen(
            myId = vm.myId,
            serverUrl = serverUrl,
            connState = connState,
            errorMessage = errorMessage,
            conversations = conversations,
            onUpdateServer = vm::updateServerUrl,
            onTrustNewIdentity = vm::trustNewServerIdentity,
            onOpenChat = { openedPeer = it }
        )
    } else {
        ChatScreen(
            myId = vm.myId,
            peerId = peer,
            messages = conversations[peer].orEmpty(),
            onBack = { openedPeer = null },
            onSend = { body -> vm.sendMessage(peer, body) }
        )
    }
}

@Composable
private fun darkSchemeFallback() = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFFBBBBFF),
    onPrimary = Color(0xFF1B1B33),
    primaryContainer = Color(0xFF33335A),
    onPrimaryContainer = Color(0xFFE0E0FF),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A)
)

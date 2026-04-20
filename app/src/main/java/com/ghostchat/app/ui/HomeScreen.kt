package com.ghostchat.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghostchat.app.data.ConnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    myId: String,
    serverUrl: String,
    connState: ConnState,
    conversations: Map<String, List<com.ghostchat.app.model.Message>>,
    onUpdateServer: (String) -> Unit,
    onOpenChat: (String) -> Unit
) {
    val clipboard: ClipboardManager = LocalClipboardManager.current
    var recipient by remember { mutableStateOf("") }
    var urlField by remember(serverUrl) { mutableStateOf(serverUrl) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("GhostChat") })
        }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Your ID", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = myId,
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(myId))
                        }) { Text("Copy") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Status: ${connState.name}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = urlField,
                onValueChange = { urlField = it },
                label = { Text("Server URL (wss://host/ws)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onUpdateServer(urlField.trim()) }) { Text("Reconnect") }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = recipient,
                onValueChange = { recipient = it },
                label = { Text("Recipient ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val r = recipient.trim()
                    if (r.isNotEmpty()) onOpenChat(r)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Chat") }

            Spacer(Modifier.height(24.dp))
            Text("Recent conversations", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(conversations.entries.toList()) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                entry.key,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                entry.value.lastOrNull()?.body ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { onOpenChat(entry.key) }) { Text("Open") }
                        }
                    }
                }
            }
        }
    }
}

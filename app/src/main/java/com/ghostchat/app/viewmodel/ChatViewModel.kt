package com.ghostchat.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostchat.app.data.ConnState
import com.ghostchat.app.data.Crypto
import com.ghostchat.app.data.PinStore
import com.ghostchat.app.data.UserPrefs
import com.ghostchat.app.data.WebSocketClient
import com.ghostchat.app.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = UserPrefs(app)
    private val pinStore = PinStore(app)
    private val crypto = Crypto(app)
    val myId: String = crypto.userId

    private val _serverUrl = MutableStateFlow(prefs.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private var client: WebSocketClient = newClient(_serverUrl.value)

    val connState: StateFlow<ConnState> get() = client.state
    val errors: StateFlow<String?> get() = client.errors

    private val _conversations = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<Message>>> = _conversations.asStateFlow()

    init {
        client.start()
        observeIncoming()
    }

    private fun newClient(url: String) = WebSocketClient(viewModelScope, myId, url, pinStore)

    private fun observeIncoming() {
        viewModelScope.launch {
            client.messages.collect { wire ->
                if (wire.type != "msg") return@collect
                val peer = if (wire.from == myId) wire.to else wire.from
                val plain = crypto.decryptFrom(wire.from, wire.body) ?: "[undecryptable]"
                appendLocally(peer, wire.copy(body = plain))
            }
        }
    }

    fun updateServerUrl(url: String) {
        if (url == _serverUrl.value) return
        prefs.serverUrl = url
        _serverUrl.value = url
        restartClient()
    }

    fun trustNewServerIdentity() {
        val host = WebSocketClient.hostOf(_serverUrl.value)
        if (host.isBlank()) return
        pinStore.remove(host)
        restartClient()
    }

    private fun restartClient() {
        client.stop()
        client = newClient(_serverUrl.value)
        client.start()
        observeIncoming()
    }

    fun sendMessage(to: String, body: String) {
        if (to.isBlank() || body.isBlank()) return
        val cipher = crypto.encryptTo(to, body) ?: return
        val wire = Message(type = "msg", from = myId, to = to, body = cipher)
        client.enqueue(wire)
        appendLocally(to, wire.copy(body = body))
    }

    private fun appendLocally(peer: String, msg: Message) {
        _conversations.update { current ->
            val list = current[peer].orEmpty() + msg
            current + (peer to list)
        }
    }

    override fun onCleared() {
        super.onCleared()
        client.stop()
    }
}

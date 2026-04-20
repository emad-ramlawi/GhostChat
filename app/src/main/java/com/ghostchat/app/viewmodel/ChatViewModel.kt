package com.ghostchat.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostchat.app.data.ConnState
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
    val myId: String = prefs.userId

    private val _serverUrl = MutableStateFlow(prefs.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private var client: WebSocketClient = newClient(_serverUrl.value)

    val connState: StateFlow<ConnState> get() = client.state

    private val _conversations = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<Message>>> = _conversations.asStateFlow()

    init {
        client.start()
        observeIncoming()
    }

    private fun newClient(url: String) = WebSocketClient(viewModelScope, myId, url)

    private fun observeIncoming() {
        viewModelScope.launch {
            client.messages.collect { msg ->
                if (msg.type != "msg") return@collect
                val peer = if (msg.from == myId) msg.to else msg.from
                appendLocally(peer, msg)
            }
        }
    }

    fun updateServerUrl(url: String) {
        if (url == _serverUrl.value) return
        prefs.serverUrl = url
        _serverUrl.value = url
        client.stop()
        client = newClient(url)
        client.start()
        observeIncoming()
    }

    fun sendMessage(to: String, body: String) {
        if (to.isBlank() || body.isBlank()) return
        val msg = Message(type = "msg", from = myId, to = to, body = body)
        client.enqueue(msg)
        appendLocally(to, msg)
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

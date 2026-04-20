package com.ghostchat.app.data

import com.ghostchat.app.model.HistoryEnvelope
import com.ghostchat.app.model.Message
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.URLBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.min

enum class ConnState { Disconnected, Connecting, Connected }

class WebSocketClient(
    private val scope: CoroutineScope,
    private val userId: String,
    private val serverUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val outbox = Channel<Message>(Channel.BUFFERED)

    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    private var loopJob: Job? = null

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) { runReconnectLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.value = ConnState.Disconnected
    }

    fun enqueue(message: Message) {
        outbox.trySend(message)
    }

    private suspend fun runReconnectLoop() {
        var attempt = 0
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            while (scope.isActive) {
                _state.value = ConnState.Connecting
                try {
                    val url = URLBuilder(serverUrl).buildString()
                    client.webSocket(urlString = url) {
                        _state.value = ConnState.Connected
                        attempt = 0
                        val registerJson = json.encodeToString(
                            Message.serializer(),
                            Message(type = "register", from = userId)
                        )
                        send(Frame.Text(registerJson))
                        val writer = launch {
                            for (msg in outbox) {
                                val text = json.encodeToString(Message.serializer(), msg)
                                send(Frame.Text(text))
                            }
                        }
                        try {
                            for (frame in this.incoming) {
                                if (frame is Frame.Text) handleText(frame.readText())
                            }
                        } finally {
                            writer.cancel()
                        }
                    }
                } catch (_: Throwable) {
                    // fall through to reconnect
                }
                _state.value = ConnState.Disconnected
                if (!scope.isActive) break
                val backoff = min(30_000L, 1000L * (1L shl min(attempt, 5)))
                attempt++
                delay(backoff)
            }
        } finally {
            client.close()
        }
    }

    private suspend fun handleText(text: String) {
        if (text.contains("\"history\"")) {
            runCatching { json.decodeFromString(HistoryEnvelope.serializer(), text) }
                .getOrNull()
                ?.messages
                ?.forEach { _messages.emit(it) }
            return
        }
        runCatching { json.decodeFromString(Message.serializer(), text) }
            .getOrNull()
            ?.let { _messages.emit(it) }
    }
}

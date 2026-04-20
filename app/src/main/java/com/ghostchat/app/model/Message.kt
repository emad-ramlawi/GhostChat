package com.ghostchat.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val type: String = "msg",
    val from: String = "",
    val to: String = "",
    val body: String = "",
    val ts: Long = System.currentTimeMillis() / 1000
)

@Serializable
data class HistoryEnvelope(
    val type: String = "history",
    val messages: List<Message> = emptyList()
)

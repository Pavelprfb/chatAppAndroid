package com.chatapp.data

import com.chatapp.api.ChatApi
import com.chatapp.api.ChatConversation
import com.chatapp.api.ChatMessage
import com.chatapp.api.ChatUser
import com.chatapp.api.CreateConversationRequest
import com.chatapp.api.SendMessageRequest
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.CancellationException

class ChatRepository(
    private val api: ChatApi,
    private val authRepository: AuthRepository,
    private val baseUrl: String
) {
    private var socket: Socket? = null

    suspend fun searchUsers(query: String): List<ChatUser> =
        withAuthorization { api.searchUsers(it, query) }

    suspend fun conversations(): List<ChatConversation> =
        withAuthorization(api::conversations)

    suspend fun createConversation(participantId: String): ChatConversation =
        withAuthorization { api.createConversation(it, CreateConversationRequest(participantId)) }

    suspend fun messages(conversationId: String): List<ChatMessage> =
        withAuthorization { api.messages(it, conversationId) }

    suspend fun sendMessage(conversationId: String, content: String): ChatMessage =
        withAuthorization { api.sendMessage(it, conversationId, SendMessageRequest(content)) }

    suspend fun connect(onMessage: (ChatMessage) -> Unit, onError: (String) -> Unit) {
        disconnect()
        val accessToken = authRepository.refreshAccessToken()
        val activeSocket = ChatApi.connectSocket(baseUrl, accessToken)
        socket = activeSocket
        activeSocket.on("message:new", Emitter.Listener { arguments ->
            arguments.firstOrNull()?.let { payload ->
                runCatching { ChatApi.decodeMessage(payload) }
                    .onSuccess(onMessage)
                    .onFailure { onError("A live message could not be read") }
            }
        })
        activeSocket.on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener {
            onError("Live connection unavailable; messages can still be refreshed")
        })
        activeSocket.connect()
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.close()
        socket = null
    }

    private suspend fun <T> withAuthorization(request: suspend (String) -> T): T {
        val firstToken = authRepository.accessToken()
        return try {
            request("Bearer $firstToken")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!ChatApi.isUnauthorized(error)) {
                throw error
            }
            request("Bearer ${authRepository.refreshAccessToken()}")
        }
    }
}

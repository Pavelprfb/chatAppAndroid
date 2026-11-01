package com.chatapp.data

import com.chatapp.api.ChatApi
import com.chatapp.api.ChatConversation
import com.chatapp.api.ChatMessage
import com.chatapp.api.ChatUser
import com.chatapp.api.CreateConversationRequest
import com.chatapp.api.SendMessageRequest
import io.socket.client.Socket
import io.socket.client.Ack
import io.socket.emitter.Emitter
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class ChatRepository(
    private val api: ChatApi,
    private val authRepository: AuthRepository,
    private val baseUrl: String,
    private val cache: ChatCache
) {
    private var socket: Socket? = null
    @Volatile
    private var ownerId: String? = null

    fun cachedSearchUsers(query: String): List<ChatUser>? {
        val owner = ownerId ?: return null
        return cache.search(owner, query)
    }

    suspend fun searchUsers(query: String): List<ChatUser> =
        withAuthorization { token ->
            api.searchUsers(token, query).also { users ->
                ownerId?.let { cache.saveSearch(it, query, users) }
            }
        }

    fun cachedConversations(): List<ChatConversation> {
        val owner = ownerId ?: return emptyList()
        return cache.conversations(owner)
    }

    fun useOwner(userId: String) {
        ownerId = userId
    }

    fun cacheConversations(conversations: List<ChatConversation>) {
        ownerId?.let { cache.saveConversations(it, conversations) }
    }

    suspend fun conversations(): List<ChatConversation> =
        withAuthorization(api::conversations).also { conversations ->
            ownerId?.let { cache.saveConversations(it, conversations) }
        }

    suspend fun createConversation(participantId: String): ChatConversation =
        withAuthorization { api.createConversation(it, CreateConversationRequest(participantId)) }

    fun cachedMessages(conversationId: String): List<ChatMessage> {
        val owner = ownerId ?: return emptyList()
        return cache.messages(owner, conversationId)
    }

    suspend fun messages(conversationId: String): List<ChatMessage> =
        withAuthorization { api.messages(it, conversationId) }.also { messages ->
            ownerId?.let { cache.saveMessages(it, conversationId, messages) }
        }

    suspend fun sendMessage(conversationId: String, content: String): ChatMessage =
        withAuthorization { api.sendMessage(it, conversationId, SendMessageRequest(content)) }
            .also { message ->
                ownerId?.let { cache.saveMessages(it, conversationId, listOf(message)) }
            }

    suspend fun connect(
        userId: String,
        onMessage: (ChatMessage) -> Unit,
        onCallSignal: (String, JSONObject) -> Unit,
        onError: (String) -> Unit,
        onConnectionChanged: (Boolean) -> Unit
    ) {
        disconnect()
        ownerId = userId
        val accessToken = authRepository.refreshAccessToken()
        val activeSocket = ChatApi.connectSocket(baseUrl, accessToken)
        socket = activeSocket
        activeSocket.on(Socket.EVENT_CONNECT, Emitter.Listener {
            onConnectionChanged(true)
        })
        activeSocket.on(Socket.EVENT_DISCONNECT, Emitter.Listener {
            onConnectionChanged(false)
        })
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
        listOf("call:incoming", "call:accepted", "call:ended", "call:ice").forEach { event ->
            activeSocket.on(event, Emitter.Listener { arguments ->
                val payload = arguments.firstOrNull() as? JSONObject
                if (payload == null) {
                    onError("A call signal could not be read")
                } else {
                    onCallSignal(event, payload)
                }
            })
        }
        activeSocket.connect()
    }

    fun sendCallSignal(
        event: String,
        payload: JSONObject,
        callback: (JSONObject) -> Unit = {}
    ) {
        val activeSocket = socket
        if (activeSocket == null || !activeSocket.connected()) {
            callback(JSONObject().put("ok", false).put("error", "Live connection is unavailable"))
            return
        }
        activeSocket.emit(event, payload, Ack { arguments ->
            callback(arguments.firstOrNull() as? JSONObject
                ?: JSONObject().put("ok", false).put("error", "Invalid call response"))
        })
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.close()
        socket = null
    }

    fun clearOwnerCache() {
        ownerId?.let(cache::clearOwner)
        ownerId = null
        disconnect()
    }

    fun cacheMessage(message: ChatMessage) {
        ownerId?.let { cache.saveMessages(it, message.conversationId, listOf(message)) }
    }

    private suspend fun <T> withAuthorization(request: suspend (String) -> T): T {
        val firstToken = authRepository.accessToken()
        return try {
            request(firstToken)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!ChatApi.isUnauthorized(error)) {
                throw error
            }
            request(authRepository.refreshAccessToken())
        }
    }
}

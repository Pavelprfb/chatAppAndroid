package com.chatapp.api

import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class ChatUser(
    val id: String = "",
    val username: String = "",
    val displayName: String = ""
)

data class ChatMessage(
    val id: String = "",
    val conversationId: String = "",
    val content: String = "",
    val sender: ChatUser = ChatUser(),
    val createdAt: String = ""
)

data class ChatConversation(
    val id: String = "",
    val participants: List<ChatUser> = emptyList(),
    val lastMessage: ChatMessage? = null,
    val updatedAt: String = ""
) {
    fun otherParticipant(userId: String): ChatUser? = participants.firstOrNull { it.id != userId }
}

data class CreateConversationRequest(val participantId: String)
data class SendMessageRequest(val content: String)

interface ChatApi {
    @GET("api/users/search")
    suspend fun searchUsers(
        @Header("Authorization") authorization: String,
        @Query("q") query: String
    ): List<ChatUser>

    @GET("api/conversations")
    suspend fun conversations(
        @Header("Authorization") authorization: String
    ): List<ChatConversation>

    @POST("api/conversations")
    suspend fun createConversation(
        @Header("Authorization") authorization: String,
        @Body request: CreateConversationRequest
    ): ChatConversation

    @GET("api/conversations/{conversationId}/messages")
    suspend fun messages(
        @Header("Authorization") authorization: String,
        @Path("conversationId") conversationId: String,
        @Query("limit") limit: Int = 50
    ): List<ChatMessage>

    @POST("api/conversations/{conversationId}/messages")
    suspend fun sendMessage(
        @Header("Authorization") authorization: String,
        @Path("conversationId") conversationId: String,
        @Body request: SendMessageRequest
    ): ChatMessage

    companion object {
        fun create(baseUrl: String): ChatApi = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChatApi::class.java)

        fun connectSocket(baseUrl: String, accessToken: String): Socket {
            val options = IO.Options().apply {
                auth = mapOf("token" to accessToken)
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 10_000
            }
            return IO.socket(baseUrl, options)
        }

        fun decodeMessage(payload: Any): ChatMessage =
            Gson().fromJson(payload.toString(), ChatMessage::class.java)

        fun isUnauthorized(error: Throwable): Boolean =
            error is HttpException && error.code() == 401
    }
}

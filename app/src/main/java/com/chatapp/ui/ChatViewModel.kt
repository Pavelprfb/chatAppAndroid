package com.chatapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatapp.api.ChatConversation
import com.chatapp.api.ChatMessage
import com.chatapp.api.ChatUser
import com.chatapp.api.toUserMessage
import com.chatapp.data.ChatRepository
import com.chatapp.data.WebRtcCallManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val userId: String = "",
    val conversations: List<ChatConversation> = emptyList(),
    val selectedConversation: ChatConversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<ChatUser> = emptyList(),
    val busy: Boolean = false,
    val sending: Boolean = false,
    val liveConnected: Boolean = false,
    val error: String? = null
)

class ChatViewModel(
    private val repository: ChatRepository,
    val callManager: WebRtcCallManager
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null

    init {
        callManager.setSignalSender(repository::sendCallSignal)
    }

    fun startSession(userId: String) {
        if (mutableState.value.userId == userId) return
        repository.disconnect()
        repository.useOwner(userId)
        mutableState.value = ChatUiState(userId = userId, busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val cached = repository.cachedConversations()
            mutableState.value = mutableState.value.copy(
                conversations = cached,
                busy = cached.isEmpty()
            )
            runCatching {
                repository.connect(
                    userId = userId,
                    onMessage = ::receiveMessage,
                    onCallSignal = callManager::onSignal,
                    onError = { message ->
                        mutableState.value = mutableState.value.copy(error = message)
                    },
                    onConnectionChanged = { connected ->
                        mutableState.value = mutableState.value.copy(liveConnected = connected)
                    }
                )
                repository.conversations()
            }
                .onSuccess { conversations ->
                    mutableState.value = mutableState.value.copy(
                        conversations = conversations,
                        busy = false
                    )
                }
                .onFailure { error ->
                    error.rethrowCancellation()
                    mutableState.value = mutableState.value.copy(
                        busy = false,
                        error = error.toUserMessage()
                    )
                }
        }
    }

    fun clearSession() {
        searchJob?.cancel()
        callManager.endCall()
        callManager.dismissCall()
        repository.clearOwnerCache()
        mutableState.value = ChatUiState()
    }

    fun startCall(mediaType: String) {
        val conversation = mutableState.value.selectedConversation ?: return
        val participant = conversation.otherParticipant(mutableState.value.userId)
        callManager.startCall(
            conversationId = conversation.id,
            participantName = participant?.displayName ?: "ChatApp user",
            mediaType = mediaType
        )
    }

    fun acceptCall() = callManager.acceptCall()

    fun declineCall() = callManager.declineCall()

    fun endCall() = callManager.endCall()

    fun toggleMicrophone() = callManager.toggleMicrophone()

    fun toggleCamera() = callManager.toggleCamera()

    fun switchCamera() = callManager.switchCamera()

    fun toggleSpeaker() = callManager.toggleSpeaker()

    fun dismissCall() = callManager.dismissCall()

    fun search(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query, error = null)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            mutableState.value = mutableState.value.copy(searchResults = emptyList())
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val normalizedQuery = query.trim()
            val cached = repository.cachedSearchUsers(normalizedQuery)
            if (cached != null && mutableState.value.searchQuery.trim() == normalizedQuery) {
                mutableState.value = mutableState.value.copy(searchResults = cached)
            }
            delay(300)
            if (mutableState.value.searchQuery.trim() != normalizedQuery) return@launch
            runCatching { repository.searchUsers(normalizedQuery) }
                .onSuccess { users ->
                    if (mutableState.value.searchQuery.trim() == normalizedQuery) {
                        mutableState.value = mutableState.value.copy(searchResults = users)
                    }
                }
                .onFailure { error ->
                    error.rethrowCancellation()
                    mutableState.value = mutableState.value.copy(error = error.toUserMessage())
                }
        }
    }

    fun startConversation(user: ChatUser) {
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createConversation(user.id) }
                .onSuccess { conversation ->
                    mutableState.value = mutableState.value.copy(
                        searchQuery = "",
                        searchResults = emptyList()
                    )
                    openConversation(conversation)
                    refreshConversations()
                }
                .onFailure { error ->
                    error.rethrowCancellation()
                    mutableState.value = mutableState.value.copy(
                        busy = false,
                        error = error.toUserMessage()
                    )
                }
        }
    }

    fun openConversation(conversation: ChatConversation) {
        val cached = repository.cachedMessages(conversation.id)
        mutableState.value = mutableState.value.copy(
            selectedConversation = conversation,
            messages = cached,
            busy = cached.isEmpty(),
            error = null
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.messages(conversation.id)
            }.onSuccess { messages ->
                if (mutableState.value.selectedConversation?.id == conversation.id) {
                    mutableState.value = mutableState.value.copy(
                        messages = mergeMessages(mutableState.value.messages, messages),
                        busy = false
                    )
                }
            }.onFailure { error ->
                error.rethrowCancellation()
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    error = error.toUserMessage()
                )
            }
        }
    }

    fun closeConversation() {
        mutableState.value = mutableState.value.copy(
            selectedConversation = null,
            messages = emptyList(),
            error = null
        )
    }

    fun sendMessage(content: String) {
        val conversation = mutableState.value.selectedConversation ?: return
        if (content.isBlank() || mutableState.value.sending) return
        mutableState.value = mutableState.value.copy(sending = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.sendMessage(conversation.id, content) }
                .onSuccess { message ->
                    receiveMessage(message)
                    mutableState.value = mutableState.value.copy(sending = false)
                    refreshConversations()
                }
                .onFailure { error ->
                    error.rethrowCancellation()
                    mutableState.value = mutableState.value.copy(
                        sending = false,
                        error = error.toUserMessage()
                    )
                }
        }
    }

    private fun receiveMessage(message: ChatMessage) {
        repository.cacheMessage(message)
        val current = mutableState.value
        val conversationExists = current.conversations.any { it.id == message.conversationId }
        val conversations = current.conversations.map { conversation ->
            if (conversation.id == message.conversationId) {
                conversation.copy(lastMessage = message, updatedAt = message.createdAt)
            } else {
                conversation
            }
        }.sortedByDescending { it.updatedAt }

        val messages = if (current.selectedConversation?.id == message.conversationId) {
            mergeMessages(current.messages, listOf(message))
        } else {
            current.messages
        }
        repository.cacheConversations(conversations)
        mutableState.value = current.copy(conversations = conversations, messages = messages)
        if (!conversationExists) {
            viewModelScope.launch(Dispatchers.IO) { refreshConversations() }
        }
    }

    private suspend fun refreshConversations() {
        runCatching { repository.conversations() }
            .onSuccess { conversations ->
                mutableState.value = mutableState.value.copy(conversations = conversations)
            }
            .onFailure { error ->
                error.rethrowCancellation()
                mutableState.value = mutableState.value.copy(error = error.toUserMessage())
            }
    }

    private fun mergeMessages(
        existing: List<ChatMessage>,
        incoming: List<ChatMessage>
    ): List<ChatMessage> = (existing + incoming)
        .distinctBy { it.id }
        .sortedBy { it.createdAt }

    private fun Throwable.rethrowCancellation() {
        if (this is CancellationException) throw this
    }

    override fun onCleared() {
        callManager.dispose()
        repository.disconnect()
        super.onCleared()
    }
}

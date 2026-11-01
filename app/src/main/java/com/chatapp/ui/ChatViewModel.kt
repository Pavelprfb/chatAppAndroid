package com.chatapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatapp.api.ChatConversation
import com.chatapp.api.ChatMessage
import com.chatapp.api.ChatUser
import com.chatapp.api.toUserMessage
import com.chatapp.data.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val error: String? = null
)

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state = mutableState.asStateFlow()

    fun startSession(userId: String) {
        if (mutableState.value.userId == userId) return
        repository.disconnect()
        mutableState.value = ChatUiState(userId = userId, busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.conversations() }
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
        repository.disconnect()
        mutableState.value = ChatUiState()
    }

    fun search(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query, error = null)
        if (query.trim().length < 2) {
            mutableState.value = mutableState.value.copy(searchResults = emptyList())
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.searchUsers(query.trim()) }
                .onSuccess { users ->
                    mutableState.value = mutableState.value.copy(searchResults = users)
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
        mutableState.value = mutableState.value.copy(
            selectedConversation = conversation,
            messages = emptyList(),
            busy = true,
            error = null
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.connect(
                    conversation.id,
                    onMessage = ::receiveMessage,
                    onError = { message ->
                        mutableState.value = mutableState.value.copy(error = message)
                    }
                )
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
        repository.disconnect()
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
        val current = mutableState.value
        val conversations = current.conversations.map { conversation ->
            if (conversation.id == message.conversationId) {
                conversation.copy(lastMessage = message)
            } else {
                conversation
            }
        }.sortedByDescending { it.id == message.conversationId }

        val messages = if (current.selectedConversation?.id == message.conversationId) {
            mergeMessages(current.messages, listOf(message))
        } else {
            current.messages
        }
        mutableState.value = current.copy(conversations = conversations, messages = messages)
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
}

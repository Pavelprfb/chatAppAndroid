package com.chatapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatapp.api.UserProfile
import com.chatapp.data.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val user: UserProfile? = null,
    val busy: Boolean = true,
    val message: String? = null
)

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.restoreSession() }
                .onSuccess { user -> mutableState.value = AuthUiState(user = user, busy = false) }
                .onFailure { error ->
                    error.rethrowCancellation()
                    mutableState.value = AuthUiState(
                        busy = false,
                        message = error.message ?: "Could not restore the saved session"
                    )
                }
        }
    }

    fun authenticate(email: String, username: String, displayName: String, password: String, register: Boolean) {
        if (email.isBlank() || password.isBlank() ||
            (register && (username.isBlank() || displayName.isBlank()))
        ) {
            mutableState.value = mutableState.value.copy(message = "Complete all required fields")
            return
        }
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (register) repository.register(email, username, displayName, password)
                else repository.login(email, password)
            }.onSuccess { result ->
                mutableState.value = AuthUiState(user = result.user, busy = false)
            }.onFailure { error ->
                error.rethrowCancellation()
                mutableState.value = AuthUiState(
                    busy = false,
                    message = error.message ?: "Authentication failed"
                )
            }
        }
    }

    fun logout() {
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.logout() }
                .onSuccess { mutableState.value = AuthUiState(busy = false) }
                .onFailure { error ->
                    error.rethrowCancellation()
                    mutableState.value = AuthUiState(
                        busy = false,
                        message = "Local session cleared; server logout failed: ${error.message ?: "unknown error"}"
                    )
                }
        }
    }
}

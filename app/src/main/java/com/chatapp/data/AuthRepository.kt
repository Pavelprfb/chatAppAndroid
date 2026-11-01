package com.chatapp.data

import com.chatapp.api.AuthApi
import com.chatapp.api.AuthResponse
import com.chatapp.api.LoginRequest
import com.chatapp.api.LogoutRequest
import com.chatapp.api.RefreshRequest
import com.chatapp.api.RegisterRequest
import com.chatapp.api.UserProfile
import com.chatapp.security.RefreshTokenStore

class AuthRepository(
    private val api: AuthApi,
    private val tokenStore: RefreshTokenStore
) {
    @Volatile
    private var currentAccessToken: String? = null

    suspend fun login(email: String, password: String) =
        accept(api.login(LoginRequest(email.trim(), password)))

    suspend fun register(email: String, username: String, displayName: String, password: String) =
        accept(api.register(RegisterRequest(email.trim(), username.trim(), displayName.trim(), password)))

    suspend fun restoreSession(): UserProfile? {
        val savedToken = tokenStore.read() ?: return null
        return try {
            accept(api.refresh(RefreshRequest(savedToken))).user
        } catch (error: retrofit2.HttpException) {
            if (error.code() == 401) {
                tokenStore.clear()
                null
            } else {
                throw error
            }
        }
    }

    suspend fun accessToken(): String {
        currentAccessToken?.let { return it }
        val savedToken = tokenStore.read()
            ?: throw IllegalStateException("Sign in to continue")
        return accept(api.refresh(RefreshRequest(savedToken))).accessToken
    }

    suspend fun refreshAccessToken(): String {
        val savedToken = tokenStore.read()
            ?: throw IllegalStateException("Sign in to continue")
        return accept(api.refresh(RefreshRequest(savedToken))).accessToken
    }

    suspend fun logout() {
        val token = tokenStore.read() ?: return
        try {
            val authResponse = api.refresh(RefreshRequest(token))
            tokenStore.write(authResponse.refreshToken)
            api.logout(authResponse.accessToken, LogoutRequest(authResponse.refreshToken))
                .also { response ->
                    if (!response.isSuccessful) {
                        throw retrofit2.HttpException(response)
                    }
                }
        } finally {
            tokenStore.clear()
            currentAccessToken = null
        }
    }

    private fun accept(response: AuthResponse): AuthResponse {
        tokenStore.write(response.refreshToken)
        currentAccessToken = response.accessToken
        return response
    }
}

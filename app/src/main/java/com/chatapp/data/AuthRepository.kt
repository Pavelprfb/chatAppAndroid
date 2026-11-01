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

    suspend fun logout() {
        val token = tokenStore.read() ?: return
        try {
            val accessToken = api.refresh(RefreshRequest(token))
            tokenStore.write(accessToken.refreshToken)
            api.logout("Bearer ${accessToken.accessToken}", LogoutRequest(accessToken.refreshToken))
                .also { response ->
                    if (!response.isSuccessful) {
                        throw retrofit2.HttpException(response)
                    }
                }
        } finally {
            tokenStore.clear()
        }
    }

    private fun accept(response: AuthResponse): AuthResponse {
        tokenStore.write(response.refreshToken)
        return response
    }
}

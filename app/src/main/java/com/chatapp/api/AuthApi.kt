package com.chatapp.api

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

data class LoginRequest(val email: String, val password: String)
data class RegisterRequest(
    val email: String,
    val username: String,
    val displayName: String,
    val password: String
)
data class RefreshRequest(val refreshToken: String)
data class LogoutRequest(val refreshToken: String)
data class AuthResponse(val user: UserProfile, val accessToken: String, val refreshToken: String)
data class UserProfile(val id: String, val email: String, val username: String, val displayName: String)

interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("api/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): AuthResponse

    @POST("api/auth/logout")
    suspend fun logout(
        @Header("Authorization") authorization: String,
        @Body request: LogoutRequest
    ): Response<Unit>

    @GET("api/users/me")
    suspend fun me(@Header("Authorization") authorization: String): UserProfile

    companion object {
        fun create(baseUrl: String): AuthApi = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }
}

package com.chatapp.api

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.HttpException

fun Throwable.toUserMessage(): String {
    if (this is HttpException) {
        val responseMessage = try {
            response()?.errorBody()?.string()?.let { body ->
                val json = JsonParser.parseString(body)
                if (!json.isJsonObject) return@let null
                val error = json.asJsonObject.get("error")
                if (error?.isJsonPrimitive == true && error.asJsonPrimitive.isString) {
                    error.asString
                } else {
                    null
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: JsonParseException) {
            null
        }

        if (!responseMessage.isNullOrBlank()) {
            return when (code()) {
                400 -> "Check your details: $responseMessage"
                409 -> "Account conflict: $responseMessage"
                else -> responseMessage
            }
        }
        return "Request failed (HTTP ${code()}). Please try again."
    }

    return when (this) {
        is UnknownHostException -> "Cannot reach the server. Check your internet connection."
        is SocketTimeoutException -> "The server took too long to respond. Please try again."
        else -> message ?: "Request failed. Please try again."
    }
}

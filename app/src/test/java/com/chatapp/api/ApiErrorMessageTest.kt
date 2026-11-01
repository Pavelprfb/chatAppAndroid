package com.chatapp.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorMessageTest {
    @Test
    fun `shows backend validation detail for bad registration input`() {
        val error = httpError(
            400,
            """{"error":"Password must be between 12 and 128 characters"}"""
        )

        assertEquals(
            "Check your details: Password must be between 12 and 128 characters",
            error.toUserMessage()
        )
    }

    @Test
    fun `shows a useful fallback for an unreadable error response`() {
        assertEquals(
            "Request failed (HTTP 400). Please try again.",
            httpError(400, "not json").toUserMessage()
        )
    }

    private fun httpError(status: Int, body: String): HttpException {
        val request = Request.Builder().url("https://example.invalid").build()
        val rawResponse = okhttp3.Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Error")
            .build()
        val errorBody = body.toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(errorBody, rawResponse))
    }
}

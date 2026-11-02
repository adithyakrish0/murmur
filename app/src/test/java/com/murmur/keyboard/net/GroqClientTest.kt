package com.murmur.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqClientTest {

    @Test
    fun testErrorMapping_401() {
        val code = 401
        val errorMsg = mapError(code, null)
        assertEquals("API key invalid, check settings.", errorMsg)
    }

    @Test
    fun testErrorMapping_413() {
        val code = 413
        val errorMsg = mapError(code, null)
        assertEquals("Recording too large.", errorMsg)
    }

    @Test
    fun testErrorMapping_429_WithRetry() {
        val code = 429
        val retryAfter = "30"
        val errorMsg = mapError(code, retryAfter)
        assertEquals("Rate limit hit, try again in 30s.", errorMsg)
    }

    @Test
    fun testErrorMapping_429_WithoutRetry() {
        val code = 429
        val errorMsg = mapError(code, null)
        assertEquals("Rate limit hit, please wait.", errorMsg)
    }

    @Test
    fun testErrorMapping_5xx() {
        val code = 503
        val errorMsg = mapError(code, null)
        assertEquals("Groq server error (503). Try again.", errorMsg)
    }

    private fun mapError(code: Int, retryAfter: String?): String {
        return when (code) {
            401 -> "API key invalid, check settings."
            413 -> "Recording too large."
            429 -> {
                if (retryAfter != null) {
                    "Rate limit hit, try again in " + retryAfter + "s."
                } else {
                    "Rate limit hit, please wait."
                }
            }
            in 500..599 -> "Groq server error (" + code + "). Try again."
            else -> "Error: " + code
        }
    }
}

package com.murmur.keyboard.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult()
    data class Error(val message: String) : TranscriptionResult()
}

class GroqClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun validateApiKey(apiKey: String): Pair<Boolean, String> = suspendCancellableCoroutine { continuation ->
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            continuation.resume(Pair(false, "API key cannot be empty"))
            return@suspendCancellableCoroutine
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .addHeader("Authorization", "Bearer $trimmedKey")
            .get()
            .build()

        val call = client.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (!call.isCanceled()) {
                    continuation.resume(Pair(false, "Connection error: ${e.localizedMessage ?: "Failed to reach Groq"}"))
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { res ->
                    if (res.isSuccessful) {
                        continuation.resume(Pair(true, "API key valid! Connection successful."))
                    } else if (res.code == 401) {
                        continuation.resume(Pair(false, "Invalid API key (401 Unauthorized)"))
                    } else {
                        continuation.resume(Pair(false, "Groq error (${res.code})"))
                    }
                }
            }
        })
    }

    suspend fun transcribeAudio(
        apiKey: String,
        audioFile: File,
        model: String
    ): TranscriptionResult = suspendCancellableCoroutine { continuation ->
        if (apiKey.isBlank()) {
            continuation.resume(TranscriptionResult.Error("No API key set. Tap to open settings."))
            return@suspendCancellableCoroutine
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("language", "en")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (call.isCanceled()) {
                    // Do nothing, coroutine cancelled
                } else {
                    continuation.resume(TranscriptionResult.Error("Couldn't reach Groq. Check your connection."))
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        val errorMsg = when (res.code) {
                            401 -> "API key invalid, check settings."
                            413 -> "Recording too large."
                            429 -> {
                                val retryAfter = res.header("Retry-After")
                                if (retryAfter != null) {
                                    "Rate limit hit, try again in ${retryAfter}s."
                                } else {
                                    "Rate limit hit, please wait."
                                }
                            }
                            in 500..599 -> "Groq server error (${res.code}). Try again."
                            else -> "Error: ${res.code}"
                        }
                        continuation.resume(TranscriptionResult.Error(errorMsg))
                        return
                    }

                    val bodyString = res.body?.string()
                    if (bodyString == null) {
                        continuation.resume(TranscriptionResult.Error("Empty response from server."))
                        return
                    }

                    try {
                        val json = JSONObject(bodyString)
                        val text = json.optString("text", "")
                        if (text.isBlank()) {
                             continuation.resume(TranscriptionResult.Error("Didn't catch anything, try again."))
                        } else {
                             continuation.resume(TranscriptionResult.Success(text))
                        }
                    } catch (e: Exception) {
                        continuation.resume(TranscriptionResult.Error("Malformed JSON response."))
                    }
                }
            }
        })
    }
}

package com.papercut.app.core.data

import android.graphics.Bitmap
import com.papercut.app.core.data.model.AiProvider
import com.papercut.app.core.data.model.KeyEntry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Domain errors the queue can act on (rotate key / cooldown / give up). */
sealed class AiException(message: String) : Exception(message) {
    class NoProviderConfigured : AiException("No AI provider configured")
    class NoUsableKeys : AiException("Add an API key for this provider in Settings")
    class AllKeysCooling(val retryInSecs: Long) : AiException("Every key is cooling down — retry in ${retryInSecs}s")
    class Auth : AiException("Provider rejected the API key (401/403)")
    class RateLimited : AiException("Rate limited by provider (429)")
    class Server(val status: Int) : AiException("Provider error (HTTP $status)")
    class Network : AiException("Network unreachable — check the provider URL")
    class EmptyResult : AiException("Model returned no content")
    class BadConfig : AiException("Provider has no model selected — pick one in Settings")
}

/**
 * Talks to any OpenAI-compatible chat-completions endpoint with vision support.
 * One dialect covers OpenAI, OpenRouter, Google AI Studio (/v1beta/openai
 * compat route), Groq, and LAN servers like Ollama/LM Studio.
 *
 * Key rotation policy is NOT here — the caller (ProcessingQueue) selects the key
 * and reports outcomes, so this class stays a pure transport.
 */
class AiClient(private val secrets: SecretStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000 // long generations, but bounded
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 180_000
        }
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Msg>,
        val temperature: Double = 0.15,
    )

    @Serializable
    private data class Msg(val role: String, val content: List<Part>)

    @Serializable
    private data class Part(
        val type: String,
        val text: String? = null,
        @SerialName("image_url") val imageUrl: ImageUrl? = null,
    )

    @Serializable
    private data class ImageUrl(val url: String)

    // Responses are decoded as JsonObject (not typed) because providers return
    // message.content either as a plain string or as an array of parts.

    /**
     * Send one vision request. [key] is pre-selected by the queue; its secret is
     * read from encrypted storage right here, used once, never cached.
     * Throws [AiException] subclasses for every failure mode.
     */
    suspend fun digitize(
        provider: AiProvider,
        model: String,
        key: KeyEntry,
        bitmap: Bitmap,
        prompt: String,
        maxSidePx: Int = 1568, // vision models cap useful detail around this
    ): String {
        val secret = secrets.readKey(key.id) ?: throw AiException.Auth()

        val dataUrl = "data:image/jpeg;base64," + encodeJpeg(bitmap, maxSidePx)
        val bodyString = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = listOf(
                    Msg(
                        role = "user",
                        content = listOf(
                            Part(type = "text", text = prompt),
                            Part(type = "image_url", imageUrl = ImageUrl(url = dataUrl)),
                        ),
                    ),
                ),
            ),
        )

        val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val responseJson: JsonElement = try {
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(bodyString)
                headers.append("Authorization", "Bearer $secret")
            }.body<JsonElement>()
        } catch (e: io.ktor.client.statement.HttpResponseException) {
            throw when (e.status.value) {
                401, 403 -> AiException.Auth()
                429 -> AiException.RateLimited()
                else -> AiException.Server(e.status.value)
            }
        } catch (e: Exception) {
            throw AiException.Network()
        }

        val text = extractContent(responseJson)
        if (text.isNullOrBlank()) throw AiException.EmptyResult()
        return text
    }

    /** message.content is either a string or an array of {type,text} parts — read both. */
    private fun extractContent(root: JsonElement): String? {
        val choice = root as? JsonObject
            ?.get("choices")?.jsonArrayOrNull()
            ?.firstOrNull() as? JsonObject
            ?: return null
        val content = choice["message"]?.jsonObjectOrNull()?.get("content") ?: return null
        return when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.joinToString("\n") { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
            }
            else -> null
        }
    }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    /** Downscale so the longest side is <= maxSidePx, then JPEG-85 + Base64. */
    private fun encodeJpeg(bitmap: Bitmap, maxSidePx: Int): String {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest <= maxSidePx) bitmap else {
            val ratio = maxSidePx.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        val out = ByteArrayOutputStream(256 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val bytes = out.toByteArray()
        if (scaled !== bitmap) scaled.recycle()
        return Base64.getEncoder().encodeToString(bytes) // minSdk 26 => java.util.Base64 is available
    }
}

package com.papercut.app.core.data

import android.graphics.Bitmap
import com.papercut.app.core.data.model.AiProvider
import com.papercut.app.core.data.model.KeyEntry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import javax.net.ssl.SSLException

/** Domain errors the queue can act on (rotate key / back off / give up). */
sealed class AiException(message: String) : Exception(message) {
    companion object {
        const val TAG_AUTH = "auth"
    }

    /**
     * Stable, obfuscation-proof identifier persisted into KeyEntry.lastFailure.
     * Never use Class.simpleName here: R8 renames it in release builds.
     */
    open val tag: String get() = "other"

    class NoProviderConfigured : AiException("No AI provider configured — open Settings")
    class NoUsableKeys : AiException("Add an API key for this provider in Settings")
    /** retryInSecs > 0: cooling down. -1: every key was REJECTED — replace them.
     *  [cause] is the failure that benched the last key — surfaced so a wrong
     *  URL/model (which also returns 401/403 on some gateways) isn't misread
     *  as "your key is bad". */
    class AllKeysCooling(val retryInSecs: Long, cause: AiException? = null) :
        AiException(
            when {
                retryInSecs > 0 -> "Every key is cooling down — retry in ${retryInSecs}s"
                cause != null -> "Key was set aside after: ${cause.message}"
                else -> "All keys were rejected — check them in Settings"
            },
        )
    class Auth : AiException("Provider rejected the API key (401) — add a valid key") {
        override val tag: String get() = TAG_AUTH
    }
    class RateLimited : AiException("Rate limited by provider (429) — will retry") {
        override val tag: String get() = "rate-limited"
    }
    /** 404: wrong base URL or model name — NOT a key problem, never bench a key. */
    class NotFound(val status: Int, detail: String? = null) :
        AiException(
            if (detail != null) "Not found: $detail — check the Base URL and model name in Settings"
            else "Not found (HTTP $status) — check the Base URL and model name in Settings",
        )
    class Server(val status: Int, detail: String? = null) :
        AiException(
            when {
                detail != null -> "Provider: $detail"
                status == 0 -> "Provider returned an error — check its status/dashboard"
                else -> "Provider error (HTTP $status)"
            },
        )
    class Timeout(val host: String) : AiException("Timed out talking to $host — try again or raise the timeout")
    class Network(val host: String) : AiException("Can't reach $host — check the provider URL")
    class EmptyResult : AiException("Model returned no content")
    /** Response looked truncated (finish_reason = length) or wasn't valid HTML. */
    class Truncated : AiException("The page is too complex — the model's answer got cut off. Try a Notes page or a crop.")
    class StorageFailed : AiException("Couldn't write the result to your folder — check storage access")
    class BadConfig : AiException("Provider has no model selected — pick one in Settings")
}

/**
 * Talks to any OpenAI-compatible chat-completions endpoint with vision support.
 * One dialect covers OpenAI, OpenRouter, Google AI Studio's /openai compat route,
 * Groq, and LAN servers (Ollama / LM Studio — keyless via requiresKey=false).
 *
 * Key selection and retry policy live in the queue; this class is transport only.
 */
class AiClient(private val secrets: SecretStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 240_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 240_000
        }
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Msg>,
        /** null = omit entirely (o-series/gpt-5 reject an explicit temperature). */
        val temperature: Float? = null,
        val max_tokens: Int = 16384,
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

    /**
     * Send one vision request. [key] may be null for keyless local servers.
     * Throws [AiException] for every failure mode; never swallows cancellation.
     */
    suspend fun digitize(
        provider: AiProvider,
        model: String,
        key: KeyEntry?,
        bitmap: Bitmap,
        prompt: String,
        maxSidePx: Int = 1568, // vision models cap useful detail around here
    ): String {
        val host = provider.baseUrl.substringAfter("://").substringBefore('/')

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
        val secret = key?.let { secrets.readKey(it.id) }
        if (provider.requiresKey && secret == null && key != null) throw AiException.Auth()

        val response: HttpResponse = try {
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(bodyString)
                if (secret != null) headers.append("Authorization", "Bearer $secret")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw AiException.Timeout(host)
        } catch (e: SocketTimeoutException) {
            throw AiException.Timeout(host)
        } catch (e: UnknownHostException) {
            throw AiException.Network(host)
        } catch (e: ConnectException) {
            throw AiException.Network(host)
        } catch (e: NoRouteToHostException) {
            throw AiException.Network(host)
        } catch (e: SSLException) {
            throw AiException.Network(host)
        } catch (e: Exception) {
            throw AiException.Network(host)
        }

        val status = response.status.value
        if (status !in 200..299) {
            throw when (status) {
                401, 403 -> AiException.Auth()
                429 -> AiException.RateLimited()
                404 -> AiException.NotFound(status)
                else -> AiException.Server(status)
            }
        }

        val root: JsonElement = try {
            json.parseToJsonElement(response.body<String>())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AiException.EmptyResult()
        }

        // Some providers (OpenRouter) return HTTP 200 with an error body.
        (root as? JsonObject)?.get("error")?.let { err ->
            val eo = err as? JsonObject
            val code = (eo?.get("code") as? JsonPrimitive)?.intOrNull ?: 0
            val msg = (eo?.get("message") as? JsonPrimitive)?.contentOrNull
            throw when {
                code == 401 || code == 403 -> AiException.Auth()
                code == 404 -> AiException.NotFound(code, msg)
                code != 0 -> AiException.Server(code, msg)
                // no numeric code — surface the provider's own words
                msg != null -> AiException.Server(0, msg)
                else -> AiException.Server(0)
            }
        }

        val choices = ((root as? JsonObject)?.get("choices") as? JsonArray)
            ?: throw AiException.EmptyResult()
        val choice = choices.firstOrNull() as? JsonObject ?: throw AiException.EmptyResult()
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (finish == "length") throw AiException.Truncated()

        val text = extractContent(choice) ?: throw AiException.EmptyResult()
        val cleaned = sanitizeHtml(text)
        if (!cleaned.contains("</html>", ignoreCase = true)) throw AiException.Truncated()
        return cleaned
    }

    /** message.content is a string OR an array of {type,text} parts — read both. */
    private fun extractContent(choice: JsonObject): String? {
        val message = choice["message"] as? JsonObject ?: return null
        val content = message["content"] ?: return null
        return when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.joinToString("\n") { part ->
                ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull ?: ""
            }.ifBlank { null }
            else -> null
        }
    }

    /** Strip ```html fences and leading prose the model may wrap around the doc. */
    private fun sanitizeHtml(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', "")
            val end = s.lastIndexOf("```")
            if (end > 0) s = s.substring(0, end)
        }
        val lower = s.lowercase()
        val start = lower.indexOf("<!doctype")
        if (start < 0) {
            val h = lower.indexOf("<html")
            if (h >= 0) return s.substring(h)
        } else if (start > 0) {
            return s.substring(start)
        }
        return s
    }

    /** Downscale so the longest side is <= maxSidePx, then JPEG-85 + Base64. */
    private fun encodeJpeg(bitmap: Bitmap, maxSidePx: Int): String {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest <= maxSidePx) bitmap
        else Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * maxSidePx / longest).coerceAtLeast(1),
            (bitmap.height * maxSidePx / longest).coerceAtLeast(1),
            true,
        )
        val out = ByteArrayOutputStream(512 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.getEncoder().encodeToString(out.toByteArray()) // minSdk 26
    }
}

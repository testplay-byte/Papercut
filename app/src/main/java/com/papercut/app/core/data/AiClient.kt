package com.papercut.app.core.data

import android.graphics.Bitmap
import com.papercut.app.core.data.model.AiProvider
import com.papercut.app.core.data.model.KeyEntry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
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
    class StorageFailed : AiException("Couldn't save the digital twin to your folder — try Re-run; if it repeats, re-pick the storage folder in Settings")
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
        /** true = SSE stream; lets the UI show the twin WHILE it generates. */
        val stream: Boolean? = null,
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
     * Streams via SSE and reports progress through [onPartial] so the UI can
     * show the twin WHILE the model writes it. Throws [AiException] for every
     * failure mode; never swallows cancellation.
     * Returns [Salvaged] — a finish_reason=length answer is auto-closed, not
     * discarded (see [closeOpenHtml]).
     */
    suspend fun digitize(
        provider: AiProvider,
        model: String,
        key: KeyEntry?,
        bitmap: Bitmap,
        prompt: String,
        onPartial: ((String) -> Unit)? = null,
        maxSidePx: Int = 1568, // vision models cap useful detail around here
    ): Salvaged {
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
                stream = onPartial != null,
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

        if (onPartial != null) {
            // SSE stream: "data: {chunk}\n\n" lines until "data: [DONE]".
            val sb = StringBuilder()
            try {
                val channel: io.ktor.utils.io.ByteReadChannel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val chunk: JsonElement = try {
                        json.parseToJsonElement(payload)
                    } catch (_: Exception) {
                        continue // keepalive comments, partial lines, etc.
                    }
                    val delta = ((((chunk as? JsonObject)?.get("choices") as? JsonArray)
                        ?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject)
                    val piece = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
                    if (piece != null) {
                        sb.append(piece)
                        onPartial(sb.toString())
                    }
                    // some gateways carry the error in-stream with no content
                    (chunk as? JsonObject)?.get("error")?.let { err ->
                        val eo = err as? JsonObject
                        val code = (eo?.get("code") as? JsonPrimitive)?.intOrNull ?: 0
                        val msg = (eo?.get("message") as? JsonPrimitive)?.contentOrNull
                        throw when {
                            code == 401 || code == 403 -> AiException.Auth()
                            code == 404 -> AiException.NotFound(code, msg)
                            code != 0 -> AiException.Server(code, msg)
                            msg != null -> AiException.Server(0, msg)
                            else -> AiException.Server(0)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                throw e
            } catch (_: Exception) {
                // mid-stream transport failure: salvage what streamed in
                if (sb.isEmpty()) throw AiException.Network(host)
            }
            if (sb.isEmpty()) {
                // provider accepted the request but streamed nothing usable —
                // some gateways answer streaming with a plain JSON body
                val plain = try { response.bodyAsText() } catch (_: Exception) { null }
                val nonStream = plain?.let { parseChatResponse(it) }
                if (nonStream != null) return nonStream
                throw AiException.EmptyResult()
            }
            val cleaned = sanitizeHtml(sb.toString())
            if (!cleaned.contains("</html>", ignoreCase = true)) {
                val salvaged = closeOpenHtml(cleaned) ?: throw AiException.Truncated()
                return Salvaged(salvaged, wasTruncated = true)
            }
            return Salvaged(cleaned, wasTruncated = false)
        }

        val plain = response.bodyAsText()
        parseChatResponse(plain)?.let { return it }
        throw AiException.EmptyResult()
    }

    /** Shared parser for a non-streaming chat-completions body. */
    private fun parseChatResponse(body: String): Salvaged? {
        val root: JsonElement = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            return null
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
            ?: return null
        val choice = choices.firstOrNull() as? JsonObject ?: return null
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        val text = extractContent(choice) ?: return null
        val cleaned = sanitizeHtml(text)
        // the model ran out of tokens mid-document — that's a PAID result,
        // salvage what arrived: close open tags and ship it. finish_reason
        // = "length" flags the twin as partial rather than discarding it.
        if (!cleaned.contains("</html>", ignoreCase = true)) {
            val salvaged = closeOpenHtml(cleaned) ?: throw AiException.Truncated()
            return Salvaged(salvaged, wasTruncated = true)
        }
        return Salvaged(cleaned, wasTruncated = finish == "length")
    }

    data class Salvaged(val html: String, val wasTruncated: Boolean)

    /**
     * Close an HTML fragment that was cut mid-tag: drop a trailing partial tag,
     * then close open elements in reverse order up to (and including) <html>.
     * Returns null if there is no usable content at all.
     */
    private fun closeOpenHtml(fragment: String): String? {
        var s = fragment.trim()
        if (s.isEmpty()) return null
        // a tag sliced in half ("...<td class=fo") is markup poison — drop it
        val lastOpen = s.lastIndexOf('<')
        if (lastOpen > s.lastIndexOf('>')) s = s.substring(0, lastOpen)
        if (s.length < 30) return null // nothing usable arrived
        // walk tags, track unclosed ones (void elements never need closing)
        val void = setOf("br", "hr", "img", "meta", "link", "input", "area", "col", "source", "wbr")
        val open = ArrayDeque<String>()
        Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*?)(/?)>").findAll(s).forEach { m ->
            val name = m.groupValues[2].lowercase()
            if (m.groupValues[1] == "/") {
                // a closing tag pops back to its opener (mismatched tags happen)
                val idx = open.indexOfLast { it == name }
                if (idx >= 0) while (open.size > idx) open.removeLastOrNull()
            } else if (m.groupValues[4] == "/" || name in void) {
                // self-closing or void — nothing to track
            } else open.addLast(name)
        }
        val sb = StringBuilder(s)
        // close document-level containers even if the model never opened them
        // (browsers recover; the WebView renders fine)
        for (tag in open.asReversed()) sb.append("</").append(tag).append('>')
        if (!s.lowercase().contains("</body>")) sb.append("</body>")
        if (!s.lowercase().contains("</html>")) sb.append("</html>")
        return sb.toString()
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

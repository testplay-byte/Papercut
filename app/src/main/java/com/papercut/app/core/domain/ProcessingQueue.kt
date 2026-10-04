package com.papercut.app.core.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.papercut.app.core.data.AiClient
import com.papercut.app.core.data.AiException
import com.papercut.app.core.data.DocumentRepository
import com.papercut.app.core.data.PagePipeline
import com.papercut.app.core.data.SettingsRepository
import com.papercut.app.core.data.model.KeyEntry
import com.papercut.app.core.data.model.PageView
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.ScanStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serial AI digitization queue — Papercut's domain heart.
 *
 *  - one status map drives every badge/overlay (key "folder/doc#page")
 *  - key rotation: LRU among healthy keys; 429 rests 60s, 401/403 10min;
 *    transient provider errors do NOT punish the key, just back off
 *  - the input image is the page AFTER crop/rotate/filter (clean input)
 *  - real child jobs: cancel() actually interrupts the in-flight request
 *  - every status write goes through [MutableStateFlow.update] (CAS) — the map
 *    is written from both the UI thread and Dispatchers.Default
 */
class ProcessingQueue(
    private val context: Context,
    private val settings: SettingsRepository,
    private val docs: DocumentRepository,
    private val ai: AiClient,
) {
    companion object {
        private const val COOLDOWN_RATE_LIMIT_MS = 60_000L
        private const val COOLDOWN_AUTH_MS = 600_000L
        private const val MAX_ATTEMPTS = 3
        private const val MAX_QUEUED = 128

        fun statusKey(folder: String, doc: String, page: Int) = "$folder/$doc#$page"
    }

    data class TaskState(
        val status: ScanStatus,
        val message: String? = null,
        /** Live partial HTML while status is Processing — drives the streaming preview. */
        val preview: String? = null,
    )

    private val _statuses = MutableStateFlow<Map<String, TaskState>>(emptyMap())
    val statuses: StateFlow<Map<String, TaskState>> = _statuses.asStateFlow()

    private data class Task(val page: PageView, val mode: ScanMode, val feedback: String?)

    private val queue = Channel<Task>(MAX_QUEUED)
    private val runningJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val cancelledKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            for (task in queue) {
                val key = statusKey(task.page.folder, task.page.docName, task.page.spec.index)
                if (cancelledKeys.remove(key)) continue // cancelled while queued
                if (key in cancelledKeys) continue
                val job = scope.launch { process(task) }
                runningJobs[key] = job
                job.join()
                runningJobs.remove(key)
            }
        }
    }

    fun submit(page: PageView, mode: ScanMode, feedback: String? = null) {
        val key = statusKey(page.folder, page.docName, page.spec.index)
        cancelledKeys.remove(key) // never let a stale tombstone swallow this
        val cur = _statuses.value[key]?.status
        if (cur == ScanStatus.Processing || cur == ScanStatus.Queued) return
        setStatus(key, ScanStatus.Queued)
        scope.launch {
            if (queue.trySend(Task(page, mode, feedback)).isFailure) {
                setStatus(key, ScanStatus.Error, "Queue is full — try again later")
            }
        }
    }

    fun submitAll(pages: List<PageView>, mode: ScanMode) =
        pages.filter { it.htmlUri == null }.forEach { submit(it, mode) }

    fun cancel(folder: String, doc: String, page: Int) {
        val key = statusKey(folder, doc, page)
        // tombstone FIRST: a task still sitting in the Channel has no Job to
        // cancel, and would otherwise run, bill, and write to the document
        cancelledKeys.add(key)
        runningJobs.remove(key)?.cancel()
        _statuses.update { it - key }
    }

    /** Page indexes shift on delete/reorder — drop this document's badges wholesale. */
    fun invalidateDocument(folder: String, doc: String) {
        val prefix = "$folder/$doc#"
        _statuses.update { m -> m.filterKeys { !it.startsWith(prefix) } }
    }

    fun clearError(folder: String, doc: String, page: Int) {
        val key = statusKey(folder, doc, page)
        if (_statuses.value[key]?.status == ScanStatus.Error)
            _statuses.update { it - key }
    }

    fun activeCount(): Int = _statuses.value.values.count {
        it.status == ScanStatus.Queued || it.status == ScanStatus.Processing
    }

    private fun setStatus(key: String, status: ScanStatus, message: String? = null) {
        _statuses.update { it + (key to TaskState(status, message)) }
    }

    /** Last preview push per key — streaming delivers tokens far faster than
     *  a WebView can sensibly reload; ~1 update/sec keeps it live but calm. */
    private val lastPreviewAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun setLivePreview(key: String, partial: String) {
        if (key in cancelledKeys) return
        val now = System.currentTimeMillis()
        val last = lastPreviewAt[key] ?: 0L
        if (now - last < 900L) return
        lastPreviewAt[key] = now
        _statuses.update { m ->
            val cur = m[key] ?: return@update m
            m + (key to cur.copy(preview = partial))
        }
    }

    private suspend fun process(task: Task) {
        val key = statusKey(task.page.folder, task.page.docName, task.page.spec.index)
        try {
            if (key in cancelledKeys) return
            val s = settings.settings.value
            val provider = s.providers.find { it.id == s.activeProviderId && it.enabled }
                ?: s.providers.firstOrNull { it.enabled && it.models.isNotEmpty() }
                ?: run {
                    setStatus(key, ScanStatus.Error, "No AI provider configured — open Settings")
                    return
                }
            val model = s.activeModel?.takeIf { it in provider.models }
                ?: provider.models.firstOrNull()
                ?: run {
                    setStatus(key, ScanStatus.Error, "No model selected — open Settings")
                    return
                }

            // Render the edited page (rotate→warp→filter). render() may return the
            // SAME instance as `raw` (ORIGINAL + no edits) — hence the identity check.
            val raw = decodeSampled(task.page.imageUri)
                ?: run {
                    setStatus(key, ScanStatus.Error, "Could not read the page image")
                    return
                }
            // ONE finally-owned bitmap: the old code recycled inline, which
            // leaked ~10 MB on every cancellation that landed in the back-off
            val rendered = try {
                val out = PagePipeline.render(raw, task.page.spec)
                if (out !== raw) raw.recycle()
                out
            } catch (e: OutOfMemoryError) {
                raw.recycle()
                setStatus(key, ScanStatus.Error, "Page too large to process on this device")
                return
            }
            try {

            val prompt = buildPrompt(task.mode, task.feedback)
            setStatus(key, ScanStatus.Processing)
            var lastError: AiException? = null

            for (attempt in 1..MAX_ATTEMPTS) {
                // keyless local servers (Ollama/LM Studio) run without a key
                val keyEntry = if (provider.requiresKey) pickKey(provider.id) else null
                if (provider.requiresKey && keyEntry == null) {
                    val mine = settings.settings.value.keys.filter { it.providerId == provider.id }
                    lastError = if (mine.isEmpty()) {
                        AiException.NoUsableKeys()
                    } else if (mine.all { it.lastFailure == AiException.TAG_AUTH }) {
                        // say WHY the last attempt failed, not a blanket "rejected":
                        // a 401 from a wrong URL or missing model reads like a key
                        // problem when it isn't — the real error is below it
                        AiException.AllKeysCooling(-1, cause = lastError)
                    } else {
                        val waitSecs = ((mine.maxOf { it.cooldownUntil } - System.currentTimeMillis()) / 1000L)
                            .coerceAtLeast(1L)
                        AiException.AllKeysCooling(waitSecs)
                    }
                    break
                }
                if (attempt > 1) delay(attempt * 1_500L) // back off before a paid retry

                // null = success; an AiException = this attempt's failure
                val outcome: AiException? = try {
                    // stream: the twin appears live in the document screen as
                    // the model writes it
                    val result = ai.digitize(
                        provider, model, keyEntry, rendered, prompt,
                        onPartial = { partial -> setLivePreview(key, partial) },
                    )
                    if (key in cancelledKeys) throw CancellationException("cancelled")
                    if (docs.savePageHtml(task.page, result.html)) {
                        if (keyEntry != null) markKeySuccess(keyEntry.id)
                        if (result.wasTruncated) {
                            // partial twin still saved — tell the user what it is
                            setStatus(key, ScanStatus.Done,
                                "Partial — the model hit its length limit; Re-run for the rest")
                            null
                        } else null
                    } else {
                        AiException.StorageFailed() // not retryable — stop burning keys
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    if (keyEntry != null) handleKeyFailure(keyEntry.id, e)
                    e
                } catch (e: Exception) {
                    AiException.Network(e.message ?: "")
                }
                if (outcome == null) {
                    // Done already carries its message on the truncated path —
                    // don't overwrite it with a bare Done
                    if (_statuses.value[key]?.status != ScanStatus.Done)
                        setStatus(key, ScanStatus.Done)
                    return
                }
                lastError = outcome
                // terminal for this task — retrying cannot fix these.
                // Server: only 4xx is terminal (bad request shape); 5xx and the
                // code-unknown error-body case are transient and retry.
                // Auth is NOT terminal: rotate to the next key and keep trying —
                // only when NO usable key remains does the AllKeysCooling branch
                // end the task with the full diagnosis.
                if (outcome is AiException.EmptyResult || outcome is AiException.BadConfig ||
                    outcome is AiException.StorageFailed || outcome is AiException.NotFound ||
                    (outcome is AiException.Server && outcome.status in 400..499)
                ) break
            }

                setStatus(key, ScanStatus.Error, lastError?.message ?: "Digitization failed")
            } finally {
                rendered.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            setStatus(key, ScanStatus.Error, "Out of memory — try a smaller page")
        } catch (e: Exception) {
            setStatus(key, ScanStatus.Error, e.message ?: "Digitization failed")
        } finally {
            _statuses.update { it } // no-op: ensure a write barrier before the worker re-reads
        }
    }

    private fun pickKey(providerId: String): KeyEntry? {
        val now = System.currentTimeMillis()
        return settings.settings.value.keys
            .filter { it.providerId == providerId && it.cooldownUntil <= now }
            .minWithOrNull(compareBy({ it.failures }, { it.totalUses }))
    }

    private fun markKeySuccess(keyId: String) = settings.update { s ->
        s.copy(keys = s.keys.map {
            if (it.id == keyId) it.copy(
                totalUses = it.totalUses + 1,
                cooldownUntil = 0L,
                failures = (it.failures - 1).coerceAtLeast(0),
                lastFailure = null,
            ) else it
        })
    }

    private fun handleKeyFailure(keyId: String, e: AiException) {
        val bench = when (e) {
            is AiException.RateLimited -> COOLDOWN_RATE_LIMIT_MS
            is AiException.Auth -> COOLDOWN_AUTH_MS
            else -> 0L // provider/network errors are not the key's fault
        }
        if (bench == 0L) return
        settings.update { s ->
            s.copy(keys = s.keys.map {
                if (it.id == keyId) it.copy(
                    cooldownUntil = System.currentTimeMillis() + bench,
                    failures = it.failures + 1,
                    lastFailure = e.tag,
                ) else it
            })
        }
    }

    private fun buildPrompt(mode: ScanMode, feedback: String?): String {
        val base = settings.promptFor(mode)
        return if (feedback.isNullOrBlank()) base
        else "$base\n\n## Correction request from the user (the previous result had these problems):\n$feedback\nFix exactly these while keeping every other rule."
    }

    private suspend fun decodeSampled(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@withContext null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 1600)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }
}

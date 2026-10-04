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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serial AI digitization queue — Papercut's domain heart.
 *
 *  - one status map drives every badge/overlay (key "folder/doc#page")
 *  - key rotation: LRU among non-cooling keys; 429 benches 60s, 401/403 10min;
 *    up to MAX_ATTEMPTS across different keys before the task fails
 *  - input image = the page AFTER crop/rotation/filter (clean input -> clean twin)
 *  - real child jobs: cancel() actually interrupts running work
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

    data class TaskState(val status: ScanStatus, val message: String? = null)

    private val _statuses = MutableStateFlow<Map<String, TaskState>>(emptyMap())
    val statuses: StateFlow<Map<String, TaskState>> = _statuses.asStateFlow()

    private data class Task(val page: PageView, val mode: ScanMode, val feedback: String?)

    private val queue = Channel<Task>(MAX_QUEUED)
    private val runningJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val cancelledKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            for (task in queue) {
                val key = statusKey(task.page.folder, task.page.docName, task.page.spec.index)
                if (key in cancelledKeys) { cancelledKeys.remove(key); continue }
                val job = scope.launch { process(task) }
                runningJobs[key] = job
                job.join()
                runningJobs.remove(key)
            }
        }
    }

    fun submit(page: PageView, mode: ScanMode, feedback: String? = null) {
        val key = statusKey(page.folder, page.docName, page.spec.index)
        val cur = _statuses.value[key]?.status
        if (cur == ScanStatus.Processing || cur == ScanStatus.Queued) return
        setStatus(key, ScanStatus.Queued)
        scope.launch {
            if (queue.trySend(Task(page, mode, feedback)).isFailure) {
                // channel full/closed — never leave a phantom Queued badge
                setStatus(key, ScanStatus.Error, "Queue is full — try later")
            }
        }
    }

    fun submitAll(pages: List<PageView>, mode: ScanMode) =
        pages.filter { it.htmlUri == null }.forEach { submit(it, mode) }

    fun cancel(folder: String, doc: String, page: Int) {
        val key = statusKey(folder, doc, page)
        val active = runningJobs.remove(key)
        if (active != null) { active.cancel(); cancelledKeys.remove(key) }
        else cancelledKeys.add(key)
        _statuses.value = _statuses.value - key
    }

    fun cancelDocument(folder: String, doc: String) {
        _statuses.value.keys.filter { it.startsWith("$folder/$doc#") }.forEach {
            val page = it.substringAfterLast('#').toIntOrNull() ?: return@forEach
            cancel(folder, doc, page)
        }
    }

    fun clearError(folder: String, doc: String, page: Int) {
        val key = statusKey(folder, doc, page)
        if (_statuses.value[key]?.status == ScanStatus.Error)
            _statuses.value = _statuses.value - key
    }

    fun activeCount(): Int = _statuses.value.values.count {
        it.status == ScanStatus.Queued || it.status == ScanStatus.Processing
    }

    private fun setStatus(key: String, status: ScanStatus, message: String? = null) {
        _statuses.value = _statuses.value + (key to TaskState(status, message))
    }

    private suspend fun process(task: Task) {
        val key = statusKey(task.page.folder, task.page.docName, task.page.spec.index)
        try {
            // cancel can land after the drain check but before we start
            if (key in cancelledKeys) return
            val s = settings.settings.value
            val provider = s.providers.find { it.id == s.activeProviderId && it.enabled }
                ?: s.providers.firstOrNull { it.enabled && it.models.isNotEmpty() }
                ?: run {
                    setStatus(key, ScanStatus.Error, "No AI provider configured")
                    return
                }
            val model = s.activeModel?.takeIf { it in provider.models }
                ?: provider.models.firstOrNull()
                ?: run {
                    setStatus(key, ScanStatus.Error, "No model selected")
                    return
                }

            // Render the edited page (crop+rotate+filter), then downsample for transport.
            val raw = decodeDownsampled(task.page.imageUri)
                ?: run {
                    setStatus(key, ScanStatus.Error, "Could not read the page image")
                    return
                }
            val rendered = PagePipeline.render(raw, task.page.spec)
            raw.recycle()
            val prompt = buildPrompt(task.mode, task.feedback)

            setStatus(key, ScanStatus.Processing)
            var lastError: AiException? = null

            for (attempt in 1..MAX_ATTEMPTS) {
                val keyEntry = pickKey(provider.id)
                if (keyEntry == null) {
                    val mine = settings.settings.value.keys.filter { it.providerId == provider.id }
                    lastError = if (mine.isNotEmpty()) {
                        val waitSecs = ((mine.maxOf { it.cooldownUntil } - System.currentTimeMillis()) / 1000L)
                            .coerceAtLeast(1L)
                        AiException.AllKeysCooling(waitSecs)
                    } else AiException.NoUsableKeys()
                    break
                }
                val outcome = try {
                    val html = ai.digitize(provider, model, keyEntry, rendered, prompt)
                    // final cancel gate: don't write results for a cancelled task
                    if (key in cancelledKeys) throw kotlinx.coroutines.CancellationException("cancelled")
                    if (docs.savePageHtml(task.page, html)) {
                        markKeySuccess(keyEntry.id)
                        null
                    } else AiException.Server(0)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: AiException) {
                    handleKeyFailure(keyEntry.id, e)
                    e
                } catch (e: Exception) {
                    AiException.Network()
                }
                if (outcome == null) {
                    setStatus(key, ScanStatus.Done)
                    return
                }
                lastError = outcome
                if (outcome is AiException.EmptyResult || outcome is AiException.BadConfig) break
            }

            setStatus(key, ScanStatus.Error, lastError?.message ?: "Digitization failed")
        } finally {
            cancelledKeys.remove(key)
        }
    }

    private fun pickKey(providerId: String): KeyEntry? {
        val now = System.currentTimeMillis()
        return settings.settings.value.keys
            .filter { it.providerId == providerId && it.cooldownUntil <= now }
            .minByOrNull { it.totalUses }
    }

    private fun markKeySuccess(keyId: String) = settings.update { s ->
        s.copy(keys = s.keys.map {
            if (it.id == keyId) it.copy(totalUses = it.totalUses + 1, cooldownUntil = 0L) else it
        })
    }

    private fun handleKeyFailure(keyId: String, e: AiException) = settings.update { s ->
        val bench = when (e) {
            is AiException.RateLimited -> COOLDOWN_RATE_LIMIT_MS
            is AiException.Auth -> COOLDOWN_AUTH_MS
            else -> 0L
        }
        s.copy(keys = s.keys.map {
            if (it.id == keyId) it.copy(
                cooldownUntil = if (bench > 0) System.currentTimeMillis() + bench else it.cooldownUntil,
                totalUses = it.totalUses + 1,
            ) else it
        })
    }

    private fun buildPrompt(mode: ScanMode, feedback: String?): String {
        val base = settings.promptFor(mode)
        return if (feedback.isNullOrBlank()) base
        else "$base\n\n## Correction request from the user (the previous result had these problems):\n$feedback\nFix exactly these while keeping every other rule."
    }

    private suspend fun decodeDownsampled(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@withContext null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2000)
            }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
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

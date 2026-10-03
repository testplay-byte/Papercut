package com.papercut.app.core.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.papercut.app.core.data.AiClient
import com.papercut.app.core.data.AiException
import com.papercut.app.core.data.ScanRepository
import com.papercut.app.core.data.SettingsRepository
import com.papercut.app.core.data.model.KeyEntry
import com.papercut.app.core.data.model.ScanItem
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.ScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serial AI processing queue — the domain heart of Papercut.
 *
 * Design vs the old singleton:
 *  - owned by AppContainer (app scope), not an object; fully replaceable in tests
 *  - per-item status map drives ALL badges/overlays (single source, no stacked overlays)
 *  - key rotation with cooldowns: a 429 benches that key for 60s and moves to the
 *    next; a 401 benches it for 10min; retry advances to another key instead of
 *    failing the task on the first hiccup
 *  - cancellation is cooperative per task (job map), unlike the old flag-set hack
 */
class ProcessingQueue(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scans: ScanRepository,
    private val ai: AiClient,
) {
    companion object {
        private const val COOLDOWN_RATE_LIMIT_MS = 60_000L
        private const val COOLDOWN_AUTH_MS = 600_000L
        private const val MAX_ATTEMPTS_PER_TASK = 3 // different keys count as different attempts
        private const val MAX_QUEUED = 64
    }

    data class Task(
        val item: ScanItem,
        val mode: ScanMode,
        val feedback: String? = null,
    )

    private val _statuses = MutableStateFlow<Map<String, TaskState>>(emptyMap())
    /** key = "${folder}/${scanName}" */
    val statuses: StateFlow<Map<String, TaskState>> = _statuses.asStateFlow()

    data class TaskState(val status: ScanStatus, val message: String? = null)

    private val queue = Channel<Task>(MAX_QUEUED)
    private val runningJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val cancelledKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    init {
        scope.launch {
            for (task in queue) {
                val key = statusKey(task)
                if (key in cancelledKeys) { // cancelled while still queued
                    cancelledKeys.remove(key)
                    continue
                }
                // real child Job: cancel() can stop the work mid-flight
                val job = scope.launch { process(task) }
                runningJobs[key] = job
                job.join()
                runningJobs.remove(key)
            }
        }
    }

    fun statusKey(task: Task) = "${task.item.folder}/${task.item.name}"

    /** Queue a scan for digitizing. Ignored if that item is already queued/working. */
    fun submit(item: ScanItem, mode: ScanMode, feedback: String? = null) {
        val task = Task(item, mode, feedback)
        val key = statusKey(task)
        if (_statuses.value[key]?.status == ScanStatus.Processing ||
            _statuses.value[key]?.status == ScanStatus.Queued
        ) return
        setStatus(key, ScanStatus.Queued)
        scope.launch { queue.trySend(task) }
    }

    /** Cancel a queued or running task by scan id. */
    fun cancel(item: ScanItem) {
        val key = "${item.folder}/${item.name}"
        val active = runningJobs.remove(key)
        if (active != null) {
            active.cancel()          // interrupts the running task
            cancelledKeys.remove(key) // it will not be drained as "queued-cancelled"
        } else {
            cancelledKeys.add(key)   // worker skips it when the queue drains
        }
        _statuses.value = _statuses.value - key
    }

    fun clearError(item: ScanItem) {
        val key = "${item.folder}/${item.name}"
        if (_statuses.value[key]?.status == ScanStatus.Error) {
            _statuses.value = _statuses.value - key
        }
    }

    private fun setStatus(key: String, status: ScanStatus, message: String? = null) {
        _statuses.value = _statuses.value + (key to TaskState(status, message))
    }

    private suspend fun process(task: Task) {
        val key = statusKey(task)
        try {
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
                    setStatus(key, ScanStatus.Error, "No model selected for ${provider.name}")
                    return
                }

            val bitmap = decodeDownsampled(task.item.imageUri)
                ?: run {
                    setStatus(key, ScanStatus.Error, "Could not read the captured image")
                    return
                }

            val prompt = buildPrompt(task.mode, task.feedback)

            setStatus(key, ScanStatus.Processing)
            var lastError: AiException? = null

            for (attempt in 1..MAX_ATTEMPTS_PER_TASK) {
                val keyEntry = pickKey(provider.id)
                    ?: run {
                        lastError = if (settings.settings.value.keys.any { it.providerId == provider.id })
                            AiException.AllKeysCooling(COOLDOWN_RATE_LIMIT_MS / 1000)
                        else AiException.NoUsableKeys()
                        break
                    }
                val outcome = try {
                    val html = ai.digitize(provider, model, keyEntry, bitmap, prompt)
                    if (scans.saveHtml(task.item, html)) {
                        markKeySuccess(keyEntry.id)
                        null // success
                    } else AiException.Server(0)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // cancelled via cancel() — don't mark error
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
                // terminal for this task if it's an empty/bad-config result
                if (outcome is AiException.EmptyResult || outcome is AiException.BadConfig) break
            }

            setStatus(key, ScanStatus.Error, lastError?.message ?: "Processing failed")
        } finally {
            // a cancel() that raced task completion must not leave a stale flag
            cancelledKeys.remove(key)
        }
    }

    /** Least-recently-used, skipping cooling-down keys. */
    private fun pickKey(providerId: String): KeyEntry? {
        val now = System.currentTimeMillis()
        val candidates = settings.settings.value.keys
            .filter { it.providerId == providerId && it.cooldownUntil <= now }
            .minByOrNull { it.totalUses }
        return candidates
    }

    private fun markKeySuccess(keyId: String) = settings.update { s ->
        s.copy(keys = s.keys.map { if (it.id == keyId) it.copy(totalUses = it.totalUses + 1, cooldownUntil = 0L) else it })
    }

    private fun handleKeyFailure(keyId: String, e: AiException) = settings.update { s ->
        val benchMs = when (e) {
            is AiException.RateLimited -> COOLDOWN_RATE_LIMIT_MS
            is AiException.Auth -> COOLDOWN_AUTH_MS
            else -> 0L // network/server issues are the provider's, not the key's
        }
        s.copy(keys = s.keys.map {
            if (it.id == keyId) it.copy(
                cooldownUntil = if (benchMs > 0) System.currentTimeMillis() + benchMs else it.cooldownUntil,
                totalUses = it.totalUses + 1,
            ) else it
        })
    }

    private fun buildPrompt(mode: ScanMode, feedback: String?): String {
        val base = settings.promptFor(mode)
        return if (feedback.isNullOrBlank()) base
        else base + "\n\n## Correction request from the user (previous attempt had these problems):\n" + feedback +
            "\nFix exactly these issues while keeping every other rule above."
    }

    /** Decode the saved JPEG already downscaled to ~maxSide — no full-res bitmaps in memory. */
    private suspend fun decodeDownsampled(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0) return@withContext null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, 1568)
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeSampleSize(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}

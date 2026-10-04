package com.papercut.app.core.data

import android.content.Context
import androidx.core.content.edit
import com.papercut.app.core.data.model.AiProvider
import com.papercut.app.core.data.model.AppSettings
import com.papercut.app.core.data.model.KeyEntry
import com.papercut.app.core.data.model.ProviderFormat
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.domain.PromptTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Single source of truth for app settings.
 *
 * Persists as JSON in app-private SharedPreferences (never in the user's shared
 * folder — the old app leaked settings.json + plaintext keys there). Secrets are
 * handled exclusively by [SecretStore]; this file only holds key metadata.
 */
class SettingsRepository(
    private val context: Context,
    val secrets: SecretStore,
) {

    companion object {
        private const val PREFS = "papercut_settings"
        private const val KEY_DOC = "settings_doc"
        const val DEFAULT_FOLDER = "Default"

        /** Ready-to-use provider presets; the user can add custom ones in Settings. */
        fun builtInProviders(): List<AiProvider> = listOf(
            AiProvider(
                id = "google-ai",
                name = "Google AI Studio",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                format = ProviderFormat.OPENAI_CHAT,
                models = listOf("gemini-2.5-flash", "gemini-2.5-pro"),
                isBuiltIn = true,
            ),
            AiProvider(
                id = "openai",
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                format = ProviderFormat.OPENAI_CHAT,
                models = listOf("gpt-4o", "gpt-4o-mini"),
                isBuiltIn = true,
            ),
            AiProvider(
                id = "openrouter",
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                format = ProviderFormat.OPENAI_CHAT,
                models = listOf(
                    "google/gemini-2.5-flash",
                    "anthropic/claude-3.5-sonnet",
                    "openai/gpt-4o-mini",
                ),
                isBuiltIn = true,
            ),
            AiProvider(
                id = "groq",
                name = "Groq",
                baseUrl = "https://api.groq.com/openai/v1",
                format = ProviderFormat.OPENAI_CHAT,
                models = listOf("meta-llama/llama-4-scout-17b-16e-instruct"),
                isBuiltIn = true,
            ),
            // Local servers need a LAN IP (127.0.0.1 is the phone itself) and
            // ignore the Authorization header — edit the URL to your PC's IP.
            AiProvider(
                id = "ollama",
                name = "Ollama (local)",
                baseUrl = "http://192.168.1.2:11434/v1",
                format = ProviderFormat.OPENAI_CHAT,
                models = listOf("llama3.2-vision:11b"),
                isBuiltIn = true,
                requiresKey = false,
            ),
            AiProvider(
                id = "lmstudio",
                name = "LM Studio (local)",
                baseUrl = "http://192.168.1.2:1234/v1",
                format = ProviderFormat.OPENAI_CHAT,
                models = emptyList(),
                isBuiltIn = true,
                requiresKey = false,
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * One writer, ordered: a single-threaded dispatcher means the LAST launched
     * write is the LAST applied. A shared IO pool could persist an older
     * snapshot after a newer one (resurrecting a removed key, losing cooldowns).
     */
    private val persistDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val persistScope = CoroutineScope(SupervisorJob() + persistDispatcher)

    private val updateLock = Any()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Load once at app start (called from PapercutApp). */
    fun load() {
        val raw = prefs.getString(KEY_DOC, null)
        val loaded = raw?.let {
            try {
                json.decodeFromString<AppSettings>(it)
            } catch (_: Exception) {
                null // corrupt file -> start clean rather than crash
            }
        }
        _settings.value = loaded?.withMigrations() ?: freshDefaults()
        update { it } // persists the migrated/defaulted doc in order
    }

    private fun freshDefaults(): AppSettings = AppSettings(
        providers = builtInProviders(),
        prompts = ScanMode.entries.associate { it.name to PromptTemplates.defaultFor(it) },
        activeProviderId = "google-ai",
        activeModel = "gemini-2.5-flash",
    )

    /** Forward-compatible fixes for docs written by older versions. */
    private fun AppSettings.withMigrations(): AppSettings {
        val builtIns = builtInProviders()
        // keep built-ins up to date (model lists drift), preserve user custom providers
        // refresh built-ins from code (model lists drift), but keep the user's
        // enable state and any custom providers they added
        // preserve EVERY user edit to a built-in (the Ollama/LAN base URL, custom
        // models, keyless flag) — code presets only fill in untouched fields
        val merged = builtIns.map { bi ->
            providers.find { it.id == bi.id }?.let { old ->
                bi.copy(
                    enabled = old.enabled,
                    baseUrl = old.baseUrl,
                    models = if (old.models.isNotEmpty()) old.models else bi.models,
                    requiresKey = old.requiresKey,
                )
            } ?: bi
        } + providers.filter { p -> !p.isBuiltIn }
        val defaultsMissing = ScanMode.entries.any { prompts[it.name].isNullOrBlank() }
        return copy(
            providers = merged,
            // an active provider that no longer exists would digitize nothing
            activeProviderId = if (merged.any { it.id == activeProviderId }) activeProviderId else "google-ai",
            activeModel = if (merged.find { it.id == activeProviderId }?.models?.contains(activeModel) == true)
                activeModel else merged.firstOrNull { it.models.isNotEmpty() }?.models?.firstOrNull(),
            prompts = if (defaultsMissing) prompts + ScanMode.entries
                .filter { prompts[it.name].isNullOrBlank() }
                .associate { it.name to PromptTemplates.defaultFor(it) }
            else prompts,
        )
    }

    /**
     * Atomic read-modify-write of the in-memory settings, then an ORDERED
     * background persist. Synchronized because callers span Main and Default
     * (a lost write loses a key cooldown -> a benched key gets hammered again).
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = synchronized(updateLock) {
            transform(_settings.value).also { _settings.value = it }
        }
        persistScope.launch {
            prefs.edit { putString(KEY_DOC, json.encodeToString(AppSettings.serializer(), next)) }
        }
    }

    // ---- convenience mutators used by ViewModels ----

    fun promptFor(mode: ScanMode): String =
        _settings.value.prompts[mode.name] ?: PromptTemplates.defaultFor(mode)

    fun setPrompt(mode: ScanMode, text: String) =
        update { it.copy(prompts = it.prompts + (mode.name to text)) }

    fun resetPrompt(mode: ScanMode) =
        setPrompt(mode, PromptTemplates.defaultFor(mode))

    fun upsertProvider(provider: AiProvider) = update { current ->
        val exists = current.providers.any { it.id == provider.id }
        current.copy(
            providers = if (exists) current.providers.map { if (it.id == provider.id) provider else it }
            else current.providers + provider,
        )
    }

    fun removeProvider(id: String) {
        // Keystore I/O is blocking binder work: keep it OUT of updateLock
        val removedKeys = _settings.value.keys.filter { it.providerId == id }.map { it.id }
        if (removedKeys.isNotEmpty()) secrets.deleteKeys(removedKeys)
        update { current -> current.copy(
            providers = current.providers.filterNot { it.id == id },
            keys = current.keys.filterNot { it.providerId == id },
            activeProviderId = if (current.activeProviderId == id) null else current.activeProviderId,
            activeModel = if (current.activeProviderId == id) null else current.activeModel,
        ) }
    }

    fun addKey(providerId: String, label: String, secret: String): KeyEntry {
        val entry = KeyEntry(
            id = UUID.randomUUID().toString(),
            providerId = providerId,
            label = label.ifBlank { "Key" },
            addedAt = System.currentTimeMillis(),
        )
        secrets.putKey(entry.id, secret)
        update { it.copy(keys = it.keys + entry) }
        return entry
    }

    fun removeKey(keyId: String) {
        secrets.deleteKey(keyId)
        update { it.copy(keys = it.keys.filterNot { k -> k.id == keyId }) }
    }

    /** Metadata-only view of a secret (first 4 + last 4 chars). */
    fun maskedSecret(keyId: String): String {
        val secret = secrets.readKey(keyId) ?: return "••••"
        return if (secret.length <= 8) "••••"
        else secret.take(4) + "•".repeat(10) + secret.takeLast(4)
    }

    /**
     * Store the SAF tree uri AND take the persistable permission, so access
     * survives reboots (the old app granted non-persistable flags once).
     */
    fun persistRootFolder(uriString: String) {
        val uri = android.net.Uri.parse(uriString)
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // launcher normally grants persistable on OpenDocumentTree; if not,
            // access still works for this session and we surface failures per-op
        }
        update { it.copy(rootFolderUri = uriString) }
    }

    /** Re-check onboarding validity at start (permission can be revoked in system UI). */
    fun hasRootAccess(): Boolean {
        val uriString = _settings.value.rootFolderUri ?: return false
        val uri = android.net.Uri.parse(uriString)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }
}

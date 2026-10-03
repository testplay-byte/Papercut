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
                    "google/gemini-2.0-flash-001",
                    "anthropic/claude-3.5-sonnet",
                    "openai/gpt-4o-mini",
                ),
                isBuiltIn = true,
            ),
            AiProvider(
                id = "google-ai",
                name = "Google AI Studio",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                format = ProviderFormat.OPENAI_CHAT,
                models = listOf("gemini-2.0-flash", "gemini-2.5-flash"),
                isBuiltIn = true,
            ),
            AiProvider(
                id = "groq",
                name = "Groq",
                baseUrl = "https://api.groq.com/openai/v1",
                format = ProviderFormat.OPENAI_CHAT,
                models = listOf("llama-3.2-90b-vision-preview"),
                isBuiltIn = true,
            ),
            AiProvider(
                id = "local",
                name = "Local (Ollama / LM Studio)",
                baseUrl = "http://127.0.0.1:1234/v1",
                format = ProviderFormat.OPENAI_CHAT,
                models = emptyList(),
                isBuiltIn = true,
                enabled = false,
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One long-lived scope for persistence writes (no fire-and-forget scopes). */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        persist(_settings.value)
    }

    private fun freshDefaults(): AppSettings = AppSettings(
        providers = builtInProviders(),
        prompts = ScanMode.entries.associate { it.name to PromptTemplates.defaultFor(it) },
        activeProviderId = "google-ai",
        activeModel = "gemini-2.0-flash",
    )

    /** Forward-compatible fixes for docs written by older versions. */
    private fun AppSettings.withMigrations(): AppSettings {
        val builtIns = builtInProviders()
        // keep built-ins up to date (model lists drift), preserve user custom providers
        val merged = builtIns.map { bi -> providers.find { it.id == bi.id }?.let { bi.copy(enabled = it.enabled) } ?: bi } +
            providers.filter { p -> !p.isBuiltIn }
        val defaultsMissing = ScanMode.entries.any { prompts[it.name].isNullOrBlank() }
        return copy(
            providers = merged,
            prompts = if (defaultsMissing) prompts + ScanMode.entries
                .filter { prompts[it.name].isNullOrBlank() }
                .associate { it.name to PromptTemplates.defaultFor(it) }
            else prompts,
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        persist(next)
    }

    private fun persist(doc: AppSettings) {
        // writes are tiny and infrequent; commit off the main thread
        ioScope.launch {
            prefs.edit { putString(KEY_DOC, json.encodeToString(AppSettings.serializer(), doc)) }
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

    fun removeProvider(id: String) = update { current ->
        val removedKeys = current.keys.filter { it.providerId == id }.map { it.id }
        if (removedKeys.isNotEmpty()) secrets.deleteKeys(removedKeys)
        current.copy(
            providers = current.providers.filterNot { it.id == id },
            keys = current.keys.filterNot { it.providerId == id },
            activeProviderId = if (current.activeProviderId == id) null else current.activeProviderId,
            activeModel = if (current.activeProviderId == id) null else current.activeModel,
        )
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

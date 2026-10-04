package com.papercut.app.core.data.model

import kotlinx.serialization.Serializable

/** What kind of digital output the AI should produce for a scan. */
@Serializable
enum class ScanMode { TEXT, NOTES }

/**
 * API dialect a provider speaks. One implementation now (OpenAI chat completions,
 * which the overwhelming majority of hosted + local servers support); the enum
 * keeps the door open for Anthropic-style formats later without churn.
 */
@Serializable
enum class ProviderFormat { OPENAI_CHAT }

/** A configured AI provider: where to call it, which models it offers. */
@Serializable
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,               // e.g. https://api.openai.com/v1
    val format: ProviderFormat = ProviderFormat.OPENAI_CHAT,
    val models: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,    // built-ins can be disabled but not deleted-by-name
    val enabled: Boolean = true,
    /** false = local servers (Ollama/LM Studio) that ignore the Authorization header. */
    val requiresKey: Boolean = true,
)

/**
 * Metadata for one stored API key. The secret itself NEVER lives here —
 * it is in [com.papercut.app.core.data.SecretStore] (keystore-backed).
 */
@Serializable
data class KeyEntry(
    val id: String,
    val providerId: String,
    val label: String,
    val addedAt: Long,
    /** Epoch millis until which this key is cooling down (after 429/401 errors). */
    val cooldownUntil: Long = 0L,
    val totalUses: Int = 0,
    /** Rejected/limited count — a key that only ever failed sorts last. */
    val failures: Int = 0,
    /** Simple class name of the last AiException, for "keys rejected" messaging. */
    val lastFailure: String? = null,
)

/** The full persisted settings document (app-private storage, no secrets inside). */
@Serializable
data class AppSettings(
    val providers: List<AiProvider> = emptyList(),
    val keys: List<KeyEntry> = emptyList(),
    val activeProviderId: String? = null,
    val activeModel: String? = null,
    val autoEnhance: Boolean = true,
    val defaultScanMode: ScanMode = ScanMode.TEXT,
    val prompts: Map<String, String> = emptyMap(), // ScanMode.name -> prompt text
    val rootFolderUri: String? = null,             // SAF tree uri (not a secret)
)

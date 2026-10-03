package com.papercut.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papercut.app.AppContainer
import com.papercut.app.core.data.model.AiProvider
import com.papercut.app.core.data.model.AppSettings
import com.papercut.app.core.data.model.KeyEntry
import com.papercut.app.core.data.model.ScanMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings hub. The whole settings experience is rebuilt around three tiles
 * (AI providers / Prompts / Storage) instead of the old app's long undifferentiated
 * list with a fake footer ("SwiftScan v4.2.0 Build #8292023" — an old-app
 * screenshot artifact — replaced with the real version).
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings.settings

    /** provider summaries for the bento grid: model + key count + masked sample */
    data class ProviderRow(
        val provider: AiProvider,
        val keyCount: Int,
        val isActive: Boolean,
        val maskedSample: String?,
    )

    val providerRows: StateFlow<List<ProviderRow>> =
        settings.map { s ->
            s.providers.filter { it.enabled }.map { p ->
                val keys = s.keys.filter { it.providerId == p.id }
                ProviderRow(
                    provider = p,
                    keyCount = keys.size,
                    isActive = p.id == s.activeProviderId,
                    maskedSample = keys.firstOrNull()?.let { container.settings.maskedSecret(it.id) },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setActive(providerId: String, model: String) = container.settings.update {
        it.copy(activeProviderId = providerId, activeModel = model)
    }

    fun setAutoEnhance(on: Boolean) = container.settings.update { it.copy(autoEnhance = on) }

    fun setDefaultScanMode(mode: ScanMode) = container.settings.update { it.copy(defaultScanMode = mode) }

    fun savePrompt(mode: ScanMode, text: String) = container.settings.setPrompt(mode, text)
    fun resetPrompt(mode: ScanMode) = container.settings.resetPrompt(mode)

    fun toggleProvider(id: String) = container.settings.update { s ->
        s.copy(providers = s.providers.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    /** Re-point storage at another folder (permission taken in the repository). */
    fun moveRootFolder(uriString: String) {
        container.settings.persistRootFolder(uriString)
        viewModelScope.launch { container.scans.ensureRoot() }
    }

    fun addCustomProvider(name: String, baseUrl: String, models: List<String>) {
        val id = "custom-" + java.util.UUID.randomUUID().toString().take(8)
        container.settings.upsertProvider(
            AiProvider(id = id, name = name, baseUrl = baseUrl, models = models, isBuiltIn = false),
        )
    }
}

/** Provider detail screen state. */
class ProviderViewModel(
    private val container: AppContainer,
    val providerId: String,
) : ViewModel() {

    // Seed with the CURRENT value: a fresh StateFlow starts null/empty for one
    // frame, and the screen's "provider == null -> close" check bounced the user
    // straight back out — model/key selection never worked (field report 2026-10-04).
    val provider: StateFlow<AiProvider?> = container.settings.settings
        .map { s -> s.providers.find { p -> p.id == providerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            container.settings.settings.value.providers.find { it.id == providerId })

    val keys: StateFlow<List<KeyEntry>> = container.settings.settings
        .map { s -> s.keys.filter { k -> k.providerId == providerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            container.settings.settings.value.keys.filter { it.providerId == providerId })

    fun masked(keyId: String) = container.settings.maskedSecret(keyId)

    fun cooldownLeft(entry: KeyEntry): Long =
        (entry.cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0)

    fun addKey(label: String, secret: String) {
        container.settings.addKey(providerId, label, secret)
    }

    fun removeKey(keyId: String) = container.settings.removeKey(keyId)

    fun saveProvider(name: String, baseUrl: String, models: List<String>) {
        val current = provider.value ?: return
        container.settings.upsertProvider(
            current.copy(name = name, baseUrl = baseUrl, models = models),
        )
    }

    fun selectModel(model: String) = container.settings.update {
        it.copy(activeProviderId = providerId, activeModel = model)
    }

    fun deleteProvider() = container.settings.removeProvider(providerId)
}

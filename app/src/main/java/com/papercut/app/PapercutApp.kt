package com.papercut.app

import android.app.Application
import com.papercut.app.core.data.AiClient
import com.papercut.app.core.data.HtmlRenderer
import com.papercut.app.core.data.ScanRepository
import com.papercut.app.core.data.SecretStore
import com.papercut.app.core.data.SettingsRepository
import com.papercut.app.core.domain.ProcessingQueue

/**
 * Manual DI: one container built at process start, shared by every ViewModel.
 * Chosen over Hilt deliberately — fewer moving parts, trivially readable, and
 * docs/ARCHITECTURE.md maps it 1:1 (any agent can trace the whole graph here).
 */
class AppContainer(app: Application) {
    val secrets = SecretStore(app)
    val settings = SettingsRepository(app, secrets)
    val scans = ScanRepository(app, settings)
    val ai = AiClient(secrets)
    val renderer = HtmlRenderer(app)
    val queue = ProcessingQueue(app, settings, scans, ai)
}

class PapercutApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.settings.load()
    }
}

/** ViewModel factory access: `val vm: XViewModel by viewModel(factory = …)` */
fun Application.container(): AppContainer = (this as PapercutApp).container

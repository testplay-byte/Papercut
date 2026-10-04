package com.papercut.app

import android.app.Application
import com.papercut.app.core.data.AiClient
import com.papercut.app.core.data.DocumentRepository
import com.papercut.app.core.data.HtmlRenderer
import com.papercut.app.core.data.SecretStore
import com.papercut.app.core.data.SettingsRepository
import com.papercut.app.core.design.MessageBus
import com.papercut.app.core.domain.DraftStore
import com.papercut.app.core.domain.ProcessingQueue

/**
 * Manual DI: one container built at process start, shared by every ViewModel.
 * No DI framework — the whole object graph is readable in 10 lines here.
 */
class AppContainer(app: Application) {
    val secrets = SecretStore(app)
    val settings = SettingsRepository(app, secrets)
    val docs = DocumentRepository(app, settings)
    val ai = AiClient(secrets)
    val renderer = HtmlRenderer(app)
    val drafts = DraftStore()
    val queue = ProcessingQueue(app, settings, docs, ai)
    val messages = MessageBus()
    val appContext = app
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

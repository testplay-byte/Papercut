package com.papercut.app.core.design

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * App-wide transient messages (snackbars). Screens post here instead of
 * Toasts; the root Scaffold observes and displays. Actions can attach a
 * single [actionLabel] callback via [postWithAction].
 */
class MessageBus {
    sealed interface Msg {
        data class Plain(val text: String) : Msg
        data class WithAction(val text: String, val action: String, val id: Int) : Msg
    }

    private val _flow = MutableSharedFlow<Msg>(extraBufferCapacity = 4)
    val flow: SharedFlow<Msg> = _flow.asSharedFlow()

    private val pendingActions = HashMap<Int, () -> Unit>()
    private var actionSeq = 0

    fun post(text: String) { _flow.tryEmit(Msg.Plain(text)) }

    /** Returns the action id so a consumer can map it back to its callback. */
    fun postWithAction(text: String, action: String, callback: () -> Unit): Int {
        val id = ++actionSeq
        pendingActions[id] = callback
        _flow.tryEmit(Msg.WithAction(text, action, id))
        return id
    }

    fun consumeAction(id: Int): (() -> Unit)? = pendingActions.remove(id)
}

/** Wire MessageBus -> SnackbarHostState. Call once inside the root Scaffold. */
@Composable
fun rememberMessageCollector(bus: MessageBus, host: SnackbarHostState, scope: CoroutineScope) {
    LaunchedEffect(bus, host) {
        bus.flow.collect { msg ->
            when (msg) {
                is MessageBus.Msg.Plain ->
                    host.showSnackbar(msg.text, duration = SnackbarDuration.Short)
                is MessageBus.Msg.WithAction -> {
                    val result = host.showSnackbar(
                        msg.text, msg.action, duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        bus.consumeAction(msg.id)?.invoke()
                    }
                }
            }
        }
    }
}

/** Convenience: fire-and-forget post from view models. */
fun MessageBus.postSafe(text: String) {
    post(text)
}

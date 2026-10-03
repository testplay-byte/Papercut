package com.papercut.app.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papercut.app.AppContainer
import com.papercut.app.PapercutApp

/**
 * Every ViewModel in Papercut is built the same way: it receives the one
 * [AppContainer]. No DI framework, no annotations — grep for appViewModel
 * and the whole object graph is visible in PapercutApp.kt.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    noinline create: (AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as PapercutApp
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                create(app.container) as T
        },
    )
}

package com.papercut.app.navigation

import android.net.Uri

/**
 * Type-safe routes. Screens never build paths by string concatenation; they
 * construct a Route and the NavHost turns it into a navigation call.
 */
sealed class Route(val path: String) {
    data object Library : Route("library")
    data class Folder(val name: String) : Route("folder/${Uri.encode(name)}")
    data class Scanner(val folder: String) : Route("scanner/${Uri.encode(folder)}")
    data class Viewer(val folder: String, val scan: String) :
        Route("viewer/${Uri.encode(folder)}/${Uri.encode(scan)}")
    data object Settings : Route("settings")
    data class ProviderSettings(val providerId: String) : Route("provider/${Uri.encode(providerId)}")
    data object Prompts : Route("prompts")

    companion object {
        const val ARG_FOLDER = "folderName"
        const val ARG_SCAN = "scanName"
        const val ARG_PROVIDER = "providerId"

        // patterns registered on the NavHost (args decoded from here)
        const val PATTERN_FOLDER = "folder/{$ARG_FOLDER}"
        const val PATTERN_SCANNER = "scanner/{$ARG_FOLDER}"
        const val PATTERN_VIEWER = "viewer/{$ARG_FOLDER}/{$ARG_SCAN}"
        const val PATTERN_PROVIDER = "provider/{$ARG_PROVIDER}"
    }
}

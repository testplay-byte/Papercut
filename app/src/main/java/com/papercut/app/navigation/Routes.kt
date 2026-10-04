package com.papercut.app.navigation

import android.net.Uri

/**
 * Type-safe routes for the v2 document flow. The scanner carries the target
 * folder and optionally an existing document to append pages to ("-" = new doc).
 */
sealed class Route(val path: String) {
    data object Library : Route("library")
    data class Folder(val name: String) : Route("folder/${Uri.encode(name)}")
    data class Scanner(val folder: String, val docName: String? = null) :
        Route("scanner/${Uri.encode(folder)}/${Uri.encode(docName ?: "-")}")
    data class Editor(val draftIndex: Int) : Route("editor/$draftIndex")
    data class Document(val folder: String, val docName: String) :
        Route("document/${Uri.encode(folder)}/${Uri.encode(docName)}")
    data object Settings : Route("settings")
    data class ProviderSettings(val providerId: String) : Route("provider/${Uri.encode(providerId)}")
    data object Prompts : Route("prompts")

    companion object {
        const val ARG_FOLDER = "folderName"
        const val ARG_DOC = "docName"
        const val ARG_INDEX = "draftIndex"
        const val ARG_PROVIDER = "providerId"

        const val PATTERN_FOLDER = "folder/{$ARG_FOLDER}"
        const val PATTERN_SCANNER = "scanner/{$ARG_FOLDER}/{$ARG_DOC}"
        const val PATTERN_EDITOR = "editor/{$ARG_INDEX}"
        const val PATTERN_DOCUMENT = "document/{$ARG_FOLDER}/{$ARG_DOC}"
        const val PATTERN_PROVIDER = "provider/{$ARG_PROVIDER}"
    }
}

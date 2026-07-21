package com.holeintimes.vbrowser.ui.navigation

sealed class AppDestination(val route: String) {
    data object Home : AppDestination("home")
    data object Downloads : AppDestination("downloads")
    data object Files : AppDestination("files")
    data object Settings : AppDestination("settings")
    data object PrivacySettings : AppDestination("privacy-settings")
    data object About : AppDestination("about")
    data object Player : AppDestination("player/{path}") {
        fun create(path: String): String {
            val encoded = android.util.Base64.encodeToString(
                path.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "player/$encoded"
        }

        fun decode(encoded: String): String {
            val bytes = android.util.Base64.decode(
                encoded,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return String(bytes, Charsets.UTF_8)
        }
    }
}

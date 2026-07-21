package com.holeintimes.vbrowser

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.holeintimes.vbrowser.data.media.MediaLibrary
import com.holeintimes.vbrowser.domain.AppLanguage
import com.holeintimes.vbrowser.ui.about.AboutScreen
import com.holeintimes.vbrowser.ui.browser.BrowserScreen
import com.holeintimes.vbrowser.ui.browser.BrowserViewModel
import com.holeintimes.vbrowser.ui.downloads.DownloadsScreen
import com.holeintimes.vbrowser.ui.files.FilesScreen
import com.holeintimes.vbrowser.ui.navigation.AppDestination
import com.holeintimes.vbrowser.ui.player.PlayerScreen
import com.holeintimes.vbrowser.ui.settings.PrivacySettingsScreen
import com.holeintimes.vbrowser.ui.settings.SettingsScreen
import com.holeintimes.vbrowser.ui.theme.VBrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

@Composable
private fun OpaqueOverlay(content: @Composable () -> Unit) {
    // background alone is not a hit target — consume pointer events so the
    // always-mounted WebView underneath cannot receive touches.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        content()
    }
}

class MainActivity : ComponentActivity() {
    private var pendingBrowserUrl by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        val lang = PrefsLanguageHelper.readLanguageSync(newBase)
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleIncomingIntent(intent)

        val app = application as VBrowserApp
        setContent {
            VBrowserTheme {
                VBrowserAppScaffold(
                    container = app.container,
                    pendingBrowserUrl = pendingBrowserUrl,
                    onPendingConsumed = { pendingBrowserUrl = null },
                    onLanguageChanged = { recreate() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            1001
        )
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString?.let { pendingBrowserUrl = it }
            }
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type.startsWith("text/")) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                        val url = Regex("https?://\\S+").find(text)?.value ?: text.trim()
                        if (url.startsWith("http")) pendingBrowserUrl = url
                    }
                } else if (type.contains("zip") || type == "application/zip") {
                    val uri = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    uri?.let { importZip(it) }
                }
            }
        }
    }

    private fun importZip(uri: Uri) {
        try {
            val root = MediaLibrary.createRoot(this)
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val out = File(root, entry.name)
                        if (!out.canonicalPath.startsWith(root.canonicalPath)) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            Toast.makeText(this, R.string.nav_files, Toast.LENGTH_SHORT).show()
            val app = application as VBrowserApp
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                app.container.mediaLibrary.refresh(
                    app.container.privacySession.isUnlocked.value
                )
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Zip import failed", Toast.LENGTH_LONG).show()
        }
    }
}

object LocaleHelper {
    fun wrap(context: Context, language: AppLanguage): Context {
        val locale = when (language) {
            AppLanguage.English -> Locale.ENGLISH
            AppLanguage.Chinese -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.System -> return context
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}

object PrefsLanguageHelper {
    fun readLanguageSync(context: Context): AppLanguage {
        return when (
            context.getSharedPreferences("vbrowser_prefs_meta", Context.MODE_PRIVATE)
                .getString("language", "system")
        ) {
            "en" -> AppLanguage.English
            "zh" -> AppLanguage.Chinese
            else -> AppLanguage.System
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VBrowserAppScaffold(
    container: AppContainer,
    pendingBrowserUrl: String?,
    onPendingConsumed: () -> Unit,
    onLanguageChanged: () -> Unit
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: AppDestination.Home.route
    var privacySettingsAuthorized by remember { mutableStateOf(false) }

    val browserVm: BrowserViewModel = viewModel(factory = BrowserViewModel.Factory(container))

    LaunchedEffect(pendingBrowserUrl) {
        val url = pendingBrowserUrl ?: return@LaunchedEffect
        browserVm.openExternalUrl(url)
        navController.navigate(AppDestination.Home.route) {
            launchSingleTop = true
        }
        onPendingConsumed()
    }

    LaunchedEffect(route) {
        if (route != AppDestination.PrivacySettings.route) {
            privacySettingsAuthorized = false
        }
    }

    data class Dest(val dest: AppDestination, val titleRes: Int, val icon: ImageVector)

    val destinations = listOf(
        Dest(AppDestination.Home, R.string.nav_home, Icons.Default.Language),
        Dest(AppDestination.Downloads, R.string.nav_downloads, Icons.Default.Download),
        Dest(AppDestination.Files, R.string.nav_files, Icons.Default.Folder),
        Dest(AppDestination.Settings, R.string.nav_settings, Icons.Default.Settings),
        Dest(AppDestination.About, R.string.nav_about, Icons.Default.Info)
    )

    val hideChrome = route?.startsWith("player") == true
    val isBrowserHome = route == AppDestination.Home.route
    val showGlobalTopBar = !hideChrome && !isBrowserHome
    val isDrawerOpen = drawerState.isOpen

    // Prefer closing the drawer over page-level BackHandlers (Browser gates itself via isDrawerOpen).
    BackHandler(enabled = isDrawerOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Edge swipe conflicts with WebView horizontal scroll when closed on browser;
        // keep gestures on while open so the scrim dismisses on outside tap.
        gesturesEnabled = !hideChrome && (!isBrowserHome || isDrawerOpen),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(272.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                destinations.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(item.titleRes)) },
                        selected = route == item.dest.route,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(item.dest.route) {
                                popUpTo(AppDestination.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (showGlobalTopBar) {
                    TopAppBar(
                        title = {
                            val title = when (route) {
                                AppDestination.PrivacySettings.route ->
                                    R.string.privacy_settings_title
                                else -> destinations.firstOrNull {
                                    it.dest.route == route
                                }?.titleRes ?: R.string.app_name
                            }
                            Text(stringResource(title))
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = stringResource(R.string.nav_menu)
                                )
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isBrowserHome) Modifier else Modifier.padding(padding)
                    )
            ) {
                // Keep WebView mounted even when user switches tabs, so in-page scripts
                // and timers continue running in the background.
                BrowserScreen(
                    viewModel = browserVm,
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    isHomeVisible = isBrowserHome,
                    isDrawerOpen = isDrawerOpen,
                    modifier = Modifier.fillMaxSize()
                )

                NavHost(
                    navController = navController,
                    startDestination = AppDestination.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(AppDestination.Home.route) {
                        // Transparent: BrowserScreen underneath stays visible and alive.
                    }
                    composable(AppDestination.Downloads.route) {
                        OpaqueOverlay {
                            DownloadsScreen(container = container)
                        }
                    }
                    composable(AppDestination.Files.route) {
                        OpaqueOverlay {
                            FilesScreen(
                                container = container,
                                onPlay = { item ->
                                    navController.navigate(AppDestination.Player.create(item.path))
                                }
                            )
                        }
                    }
                    composable(AppDestination.Settings.route) {
                        OpaqueOverlay {
                            SettingsScreen(
                                container = container,
                                onOpenAbout = { navController.navigate(AppDestination.About.route) },
                                onOpenPrivacy = {
                                    privacySettingsAuthorized = true
                                    navController.navigate(AppDestination.PrivacySettings.route)
                                },
                                onLanguageChanged = onLanguageChanged
                            )
                        }
                    }
                    composable(AppDestination.PrivacySettings.route) {
                        OpaqueOverlay {
                            if (privacySettingsAuthorized) {
                                PrivacySettingsScreen(container = container)
                            } else {
                                LaunchedEffect(Unit) {
                                    if (!navController.popBackStack()) {
                                        navController.navigate(AppDestination.Settings.route) {
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    composable(AppDestination.About.route) {
                        OpaqueOverlay {
                            AboutScreen()
                        }
                    }
                    composable(
                        route = "player/{path}",
                        arguments = listOf(navArgument("path") { type = NavType.StringType })
                    ) { entry ->
                        OpaqueOverlay {
                            val encoded = entry.arguments?.getString("path").orEmpty()
                            val path = AppDestination.Player.decode(encoded)
                            PlayerScreen(
                                container = container,
                                mediaPath = path,
                                onBack = { navController.popBackStack() },
                                onPlayNext = { nextPath ->
                                    navController.navigate(AppDestination.Player.create(nextPath)) {
                                        popUpTo(AppDestination.Files.route)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

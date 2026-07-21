package com.holeintimes.vbrowser

import android.app.Application
import android.content.Context
import com.holeintimes.vbrowser.data.download.DownloadManager
import com.holeintimes.vbrowser.data.hls.LocalHlsServer
import com.holeintimes.vbrowser.data.media.MediaLibrary
import com.holeintimes.vbrowser.data.prefs.PrefsRepository
import com.holeintimes.vbrowser.data.privacy.PrivacySession
import com.holeintimes.vbrowser.data.sniff.VideoSniffer
import com.holeintimes.vbrowser.domain.AppConfig
import java.io.File

class VBrowserApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        container.start()
    }

    companion object {
        lateinit var instance: VBrowserApp
            private set
    }
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val rootDir: File = MediaLibrary.createRoot(appContext)
    val config: AppConfig = AppConfig(rootDataPath = rootDir.absolutePath)
    val prefs = PrefsRepository(appContext)
    val privacySession = PrivacySession()
    val hlsServer = LocalHlsServer(config.webServerPort)
    val mediaLibrary = MediaLibrary(appContext, rootDir)
    val sniffer = VideoSniffer(config.videoSnifferThreadNum, config.videoSnifferRetryCountOnFail)
    val downloadManager = DownloadManager(
        context = appContext,
        config = config,
        prefs = prefs,
        rootDir = rootDir,
        onTaskFinished = { mediaLibrary.refresh(privacySession.isUnlocked.value) }
    )
    val networkMonitor = com.holeintimes.vbrowser.data.network.NetworkMonitor(
        appContext,
        downloadManager
    )

    fun start() {
        hlsServer.start(rootDir)
        sniffer.start()
        downloadManager.start()
        networkMonitor.start()
    }
}

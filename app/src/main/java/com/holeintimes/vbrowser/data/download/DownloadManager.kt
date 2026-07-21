package com.holeintimes.vbrowser.data.download

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import com.holeintimes.vbrowser.data.http.HttpClient
import com.holeintimes.vbrowser.data.prefs.PrefsRepository
import com.holeintimes.vbrowser.domain.AppConfig
import com.holeintimes.vbrowser.domain.DownloadStatus
import com.holeintimes.vbrowser.domain.DownloadTask
import com.holeintimes.vbrowser.domain.VideoInfo
import com.holeintimes.vbrowser.service.DownloadForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Concurrent download orchestrator.
 *
 * Lifecycle: Ready → Loading → Running → Saving → (removed on Completed)
 *            any active state → Paused / Error; resume → Ready → pump again.
 *
 * Pause/cancel always cancels the task Job; work runs inside [coroutineScope]
 * so segment/part workers are cancelled with the parent (no orphan downloads).
 */
class DownloadManager(
    private val context: Context,
    private val config: AppConfig,
    private val prefs: PrefsRepository,
    private val rootDir: File,
    private val onTaskFinished: suspend (DownloadTask) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val concurrency = Semaphore(config.maxConcurrentTask)

    private val tasks = ConcurrentHashMap<String, MutableTask>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val networkBlocked = AtomicBoolean(false)

    private val _tasksFlow = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasksFlow: StateFlow<List<DownloadTask>> = _tasksFlow.asStateFlow()

    private val _finished = MutableSharedFlow<DownloadTask>(extraBufferCapacity = 16)
    val finished: SharedFlow<DownloadTask> = _finished.asSharedFlow()

    fun start() {
        scope.launch {
            restorePending()
            pump()
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                publish()
            }
        }
    }

    fun addFromVideo(info: VideoInfo) {
        scope.launch {
            mutex.withLock {
                val existing = tasks.values.firstOrNull {
                    it.url == info.url &&
                        it.status != DownloadStatus.Error
                }
                if (existing != null) {
                    if (existing.status == DownloadStatus.Paused) {
                        existing.status = DownloadStatus.Ready
                        existing.failedReason = ""
                        existing.cancelReason = CancelReason.None
                        persist(existing)
                        publish()
                    }
                    return@withLock
                }
                val taskId = UUID.randomUUID().toString().replace("-", "")
                val isM3u8 = info.videoFormat.name.equals("m3u8", true)
                val fileName = info.fileName.ifBlank { taskId }
                val mt = MutableTask(
                    DownloadTask(
                        taskId = taskId,
                        fileName = fileName,
                        videoType = if (isM3u8) "m3u8" else "normal",
                        fileExtension = info.videoFormat.name,
                        url = info.url,
                        sourcePageUrl = info.sourcePageUrl,
                        sourcePageTitle = info.sourcePageTitle,
                        size = info.size,
                        status = DownloadStatus.Ready.name.lowercase()
                    )
                )
                tasks[taskId] = mt
                ensureTempDir(mt)
                persist(mt)
                publish()
            }
            startForegroundIfNeeded()
            pump()
        }
    }

    fun pause(taskId: String) {
        scope.launch {
            var job: Job? = null
            mutex.withLock {
                val t = tasks[taskId] ?: return@withLock
                if (t.status == DownloadStatus.Paused || t.status == DownloadStatus.Completed) {
                    return@withLock
                }
                t.cancelReason = CancelReason.UserPause
                t.status = DownloadStatus.Paused
                t.currentSpeed = 0
                persist(t)
                publish()
                job = runningJobs.remove(taskId)
            }
            job?.cancel(CancellationException("user_pause"))
            pump()
        }
    }

    fun resume(taskId: String) {
        scope.launch {
            if (networkBlocked.get()) return@launch
            mutex.withLock {
                val t = tasks[taskId] ?: return@withLock
                if (t.status != DownloadStatus.Paused && t.status != DownloadStatus.Error) return@withLock
                t.cancelReason = CancelReason.None
                t.pausedByNetwork = false
                t.status = DownloadStatus.Ready
                t.failedReason = ""
                t.currentSpeed = 0
                persist(t)
                publish()
            }
            startForegroundIfNeeded()
            pump()
        }
    }

    fun retry(taskId: String) = resume(taskId)

    fun stop(taskId: String) {
        scope.launch {
            var job: Job? = null
            var tempDir: File? = null
            mutex.withLock {
                val t = tasks[taskId] ?: return@withLock
                t.cancelReason = CancelReason.UserStop
                job = runningJobs.remove(taskId)
                tasks.remove(taskId)
                tempDir = t.tempDir
                publish()
            }
            job?.cancel(CancellationException("user_stop"))
            tempDir?.let { runCatching { it.deleteRecursively() } }
            stopForegroundIfIdle()
            pump()
        }
    }

    fun onNetworkAvailable(available: Boolean) {
        if (available) {
            networkBlocked.set(false)
            scope.launch {
                mutex.withLock {
                    tasks.values.filter { it.status == DownloadStatus.Paused && it.pausedByNetwork }.forEach {
                        it.pausedByNetwork = false
                        it.cancelReason = CancelReason.None
                        it.status = DownloadStatus.Ready
                        it.failedReason = ""
                        persist(it)
                    }
                    publish()
                }
                pump()
            }
        } else {
            networkBlocked.set(true)
            scope.launch {
                val toCancel = mutableListOf<Job>()
                mutex.withLock {
                    tasks.values.filter {
                        it.status == DownloadStatus.Ready ||
                            it.status == DownloadStatus.Loading ||
                            it.status == DownloadStatus.Running ||
                            it.status == DownloadStatus.Saving
                    }.forEach { t ->
                        t.cancelReason = CancelReason.Network
                        t.pausedByNetwork = true
                        t.status = DownloadStatus.Paused
                        t.currentSpeed = 0
                        persist(t)
                        runningJobs.remove(t.taskId)?.let { toCancel += it }
                    }
                    publish()
                }
                toCancel.forEach { it.cancel(CancellationException("network")) }
            }
        }
    }

    private suspend fun pump() {
        if (networkBlocked.get()) return
        val starters = mutableListOf<Pair<MutableTask, Job>>()
        mutex.withLock {
            val slots = config.maxConcurrentTask - runningJobs.size
            if (slots <= 0) return@withLock
            val ready = tasks.values
                .filter { it.status == DownloadStatus.Ready && !runningJobs.containsKey(it.taskId) }
                .sortedBy { it.createdAt }
                .take(slots)
            for (t in ready) {
                t.status = DownloadStatus.Loading
                val job = scope.launch {
                    concurrency.withPermit {
                        runTask(t)
                    }
                }
                runningJobs[t.taskId] = job
                starters += t to job
            }
            if (ready.isNotEmpty()) publish()
        }
        starters.forEach { (t, job) ->
            job.invokeOnCompletion {
                runningJobs.remove(t.taskId, job)
                scope.launch {
                    stopForegroundIfIdle()
                    pump()
                }
            }
        }
        startForegroundIfNeeded()
    }

    private suspend fun runTask(t: MutableTask) {
        try {
            checkNotStopped(t)
            // Child work must be tied to this coroutine so pause/cancel stops workers.
            coroutineScope {
                if (t.isM3u8) downloadM3u8(t) else downloadNormal(t)
            }
            checkNotStopped(t)
            t.status = DownloadStatus.Saving
            t.currentSpeed = 0
            publish()
            persist(t)
            finalizePackage(t)
            t.status = DownloadStatus.Completed
            publish()
            prefs.markUrlDownloaded(t.url)
            val snapshot = t.snapshot()
            _finished.emit(snapshot)
            onTaskFinished(snapshot)
            mutex.withLock {
                tasks.remove(t.taskId)
                publish()
            }
            stopForegroundIfIdle()
        } catch (e: CancellationException) {
            handleCancellation(t)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Task failed ${t.taskId}", e)
            if (t.cancelReason == CancelReason.UserStop) return
            t.status = DownloadStatus.Error
            t.failedReason = e.message ?: "Unknown error"
            t.currentSpeed = 0
            persist(t)
            publish()
        }
    }

    private fun handleCancellation(t: MutableTask) {
        when (t.cancelReason) {
            CancelReason.UserStop -> Unit // already removed
            CancelReason.UserPause, CancelReason.Network -> {
                if (tasks.containsKey(t.taskId)) {
                    t.status = DownloadStatus.Paused
                    t.currentSpeed = 0
                    persist(t)
                    publish()
                }
            }
            CancelReason.None -> {
                // Unexpected cancel — keep resumable.
                if (tasks.containsKey(t.taskId)) {
                    t.status = DownloadStatus.Paused
                    t.currentSpeed = 0
                    persist(t)
                    publish()
                }
            }
        }
    }

    private suspend fun checkNotStopped(t: MutableTask) {
        coroutineContext.ensureActive()
        when (t.cancelReason) {
            CancelReason.UserStop -> throw CancellationException("user_stop")
            CancelReason.UserPause -> throw CancellationException("user_pause")
            CancelReason.Network -> throw CancellationException("network")
            CancelReason.None -> Unit
        }
    }

    private suspend fun downloadM3u8(t: MutableTask) = coroutineScope {
        ensureActive()
        t.status = DownloadStatus.Running
        publish()
        ensureTempDir(t)
        File(t.tempDir, "videoTitle").writeText(t.sourcePageTitle.ifBlank { t.fileName })
        File(t.tempDir, "urls").writeText("${t.url}\n${t.sourcePageUrl}")

        val sizeQueue = ArrayDeque<SegmentJob>()
        val downloadQueue = ArrayDeque<SegmentJob>()
        val extra = probeHeaders(t)
        parseM3u8(t.url, "index.m3u8", t.fileName, t.tempDir, sizeQueue, downloadQueue, extra)
        ensureActive()

        val sizeAcc = AtomicLong(0)
        val downloadedBytes = AtomicLong(countExistingDownloadedBytes(t.tempDir))
        t.totalDownloaded = downloadedBytes.get()
        val speedBytes = AtomicLong(0)

        val detectCount = min(config.m3U8DownloadThreadNum, maxOf(1, sizeQueue.size))
        val detectJobs = List(detectCount) {
            async {
                while (isActive) {
                    val job = synchronized(sizeQueue) {
                        if (sizeQueue.isEmpty()) null else sizeQueue.removeFirst()
                    } ?: break
                    retryIO(config.m3U8DownloadSizeDetectRetryCountOnFail) {
                        ensureActive()
                        val head = HttpClient.probe(job.url, extra)
                        sizeAcc.addAndGet(head.contentLength ?: 0L)
                        t.size = sizeAcc.get() + downloadedBytes.get()
                    }
                }
            }
        }
        detectJobs.awaitAll()
        ensureActive()
        t.size = maxOf(t.size, sizeAcc.get() + downloadedBytes.get())

        val speedJob = launch {
            while (isActive) {
                delay(1000)
                t.currentSpeed = speedBytes.getAndSet(0)
            }
        }

        val recordFile = File(t.tempDir, ".downloaded_segments")
        val dlCount = min(config.m3U8DownloadThreadNum, maxOf(1, downloadQueue.size))
        val dlJobs = List(dlCount) {
            async {
                while (isActive) {
                    val job = synchronized(downloadQueue) {
                        if (downloadQueue.isEmpty()) null else downloadQueue.removeFirst()
                    } ?: break
                    val dest = File(job.downloadPath)
                    if (dest.exists() && dest.length() > 0L && isRecorded(recordFile, job.url, job.segmentFileName)) {
                        continue
                    }
                    retryIO(config.downloadSubFileRetryCountOnFail) {
                        ensureActive()
                        if (dest.exists()) dest.delete()
                        HttpClient.downloadToFile(job.url, dest, extra = extra) { n ->
                            downloadedBytes.addAndGet(n)
                            speedBytes.addAndGet(n)
                            t.totalDownloaded = downloadedBytes.get()
                        }
                        require(dest.exists() && dest.length() > 0L) { "empty segment ${job.segmentFileName}" }
                        synchronized(recordFile) {
                            recordFile.appendText("${job.url}\n${job.segmentFileName}\n")
                        }
                    }
                }
            }
        }
        try {
            dlJobs.awaitAll()
        } finally {
            speedJob.cancel()
        }
        ensureActive()
        t.totalDownloaded = downloadedBytes.get()
        if (t.size <= 0) t.size = t.totalDownloaded
        persist(t)
    }

    private fun parseM3u8(
        m3u8Url: String,
        newFileName: String,
        relativePath: String,
        outputPath: File,
        sizeQueue: ArrayDeque<SegmentJob>,
        downloadQueue: ArrayDeque<SegmentJob>,
        extra: HttpClient.ProbeHeaders
    ) {
        val content = HttpClient.getString(m3u8Url, extra)
        val recordFile = File(outputPath, ".downloaded_segments")
        val downloadedPairs = loadRecordPairs(recordFile)
        val sb = StringBuilder()
        var subFile = false

        for (raw in content.split("\n")) {
            val lineStr = raw
            if (lineStr.startsWith("#")) {
                var line = lineStr
                if (line.startsWith("#EXT-X-KEY:")) {
                    val keyUri = Regex("URI=\"(.*?)\"").find(line)?.groupValues?.get(1)
                        ?: throw IllegalStateException("EXT-X-KEY parse failed")
                    val keyUrl = resolveUrl(m3u8Url, keyUri)
                    val segmentName = stableName(keyUrl, "key")
                    val keyPath = File(outputPath, segmentName).absolutePath
                    if (!downloadedPairs.containsKey(keyUrl)) {
                        downloadQueue.add(SegmentJob(keyUrl, keyPath, segmentName))
                    }
                    line = Regex("URI=\"(.*?)\"").replace(line, "URI=\"/$relativePath/$segmentName\"")
                }
                if (line.startsWith("#EXT-X-STREAM-INF")) subFile = true
                sb.append(line).append('\n')
            } else {
                val videoUri = lineStr.trim()
                if (videoUri.isEmpty()) continue
                val fileUrl = resolveUrl(m3u8Url, videoUri)

                if (subFile) {
                    subFile = false
                    val nestedName = stableName(fileUrl, "m3u8")
                    parseM3u8(fileUrl, nestedName, relativePath, outputPath, sizeQueue, downloadQueue, extra)
                    sb.append('/').append(relativePath).append('/').append(nestedName).append('\n')
                } else {
                    val existing = downloadedPairs[fileUrl]
                    val segmentName = existing ?: stableName(fileUrl, "ts")
                    val segmentPath = File(outputPath, segmentName).absolutePath
                    sb.append('/').append(relativePath).append('/').append(segmentName).append('\n')
                    if (existing == null || !File(segmentPath).exists()) {
                        val job = SegmentJob(fileUrl, segmentPath, segmentName)
                        sizeQueue.add(job)
                        downloadQueue.add(job)
                    }
                }
            }
        }
        File(outputPath, newFileName).writeText(sb.toString())
    }

    private suspend fun downloadNormal(t: MutableTask) = coroutineScope {
        ensureActive()
        t.status = DownloadStatus.Running
        publish()
        ensureTempDir(t)
        File(t.tempDir, "videoTitle").writeText(t.sourcePageTitle.ifBlank { t.fileName })
        File(t.tempDir, "urls").writeText("${t.url}\n${t.sourcePageUrl}")
        File(t.tempDir, "normalVideoType").writeText(t.fileExtension)

        val extra = probeHeaders(t)
        val head = retryIO(config.normalFileHeaderCheckRetryCountOnFail) {
            ensureActive()
            HttpClient.probe(t.url, extra)
        }
        val size = head.contentLength ?: 0L
        t.size = size
        val outFile = File(t.tempDir, "video.${t.fileExtension}")
        val speedBytes = AtomicLong(0)
        val downloaded = AtomicLong(0)
        val speedJob = launch {
            while (isActive) {
                delay(1000)
                t.currentSpeed = speedBytes.getAndSet(0)
            }
        }

        try {
            if (!head.acceptRanges || size <= 0L) {
                if (outFile.exists()) outFile.delete()
                HttpClient.downloadToFile(t.url, outFile, extra = extra) { n ->
                    downloaded.addAndGet(n)
                    speedBytes.addAndGet(n)
                    t.totalDownloaded = downloaded.get()
                }
            } else {
                val split = config.normalFileSplitSize
                val parts = mutableListOf<Pair<Long, Long>>()
                var start = 0L
                while (start < size) {
                    val end = min(start + split - 1, size - 1)
                    parts += start to end
                    start = end + 1
                }
                val partFiles = parts.mapIndexed { i, range ->
                    val f = File(t.tempDir, "part_$i")
                    val expected = range.second - range.first + 1
                    Triple(i, f, expected)
                }
                // Resume: keep complete parts, re-download incomplete.
                var already = 0L
                partFiles.forEach { (_, f, expected) ->
                    if (f.exists() && f.length() == expected) already += expected
                }
                downloaded.set(already)
                t.totalDownloaded = already

                val pending = partFiles.mapIndexedNotNull { idx, triple ->
                    val (i, f, expected) = triple
                    if (f.exists() && f.length() == expected) null else idx to parts[i]
                }
                val workers = min(config.normalFileDownloadThreadNum, maxOf(1, pending.size))
                val queue = ArrayDeque(pending)
                val jobs = List(workers) {
                    async {
                        while (isActive) {
                            val item = synchronized(queue) {
                                if (queue.isEmpty()) null else queue.removeFirst()
                            } ?: break
                            val (idx, range) = item
                            val (s, e) = range
                            val expected = e - s + 1
                            val part = partFiles[idx].second
                            retryIO(config.downloadSubFileRetryCountOnFail) {
                                ensureActive()
                                if (part.exists()) part.delete()
                                HttpClient.downloadToFile(t.url, part, s, e, extra = extra) { n ->
                                    downloaded.addAndGet(n)
                                    speedBytes.addAndGet(n)
                                    t.totalDownloaded = downloaded.get()
                                }
                                require(part.exists() && part.length() == expected) {
                                    "part $idx size mismatch ${part.length()} != $expected"
                                }
                            }
                        }
                    }
                }
                jobs.awaitAll()
                ensureActive()

                FileChannel.open(
                    outFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { out ->
                    partFiles.forEach { (_, part, expected) ->
                        require(part.exists() && part.length() == expected) {
                            "missing part ${part.name}"
                        }
                        FileChannel.open(part.toPath(), StandardOpenOption.READ).use { input ->
                            var pos = 0L
                            val len = input.size()
                            while (pos < len) {
                                pos += input.transferTo(pos, len - pos, out)
                            }
                        }
                    }
                }
                partFiles.forEach { (_, part, _) -> part.delete() }
            }
        } finally {
            speedJob.cancel()
        }
        ensureActive()
        t.totalDownloaded = downloaded.get().let { if (it > 0) it else outFile.length() }
        if (t.size <= 0) t.size = t.totalDownloaded
        persist(t)
    }

    private fun finalizePackage(t: MutableTask) {
        val finalDir = File(rootDir, t.fileName)
        if (finalDir.exists()) finalDir.deleteRecursively()
        if (!t.tempDir.renameTo(finalDir)) {
            t.tempDir.copyRecursively(finalDir, overwrite = true)
            t.tempDir.deleteRecursively()
        }
        File(finalDir, "download_task_info.json").delete()
        File(finalDir, ".downloaded_segments").delete()
    }

    private fun ensureTempDir(t: MutableTask) {
        t.tempDir.mkdirs()
    }

    private fun persist(t: MutableTask) {
        ensureTempDir(t)
        File(t.tempDir, "download_task_info.json").writeText(json.encodeToString(t.snapshot()))
    }

    private fun restorePending() {
        rootDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || !dir.name.endsWith(".temp")) return@forEach
            val info = File(dir, "download_task_info.json")
            if (!info.exists()) return@forEach
            runCatching {
                val task = json.decodeFromString<DownloadTask>(info.readText())
                val mt = MutableTask(task)
                // Interrupted mid-flight → Ready so pump resumes; explicit paused stays paused.
                mt.status = when {
                    task.status.equals("paused", true) -> DownloadStatus.Paused
                    task.status.equals("error", true) -> DownloadStatus.Error
                    else -> DownloadStatus.Ready
                }
                mt.cancelReason = CancelReason.None
                tasks[mt.taskId] = mt
            }.onFailure {
                Log.w(TAG, "skip corrupt task dir ${dir.name}", it)
            }
        }
        publish()
    }

    private fun publish() {
        _tasksFlow.value = tasks.values
            .sortedByDescending { it.createdAt }
            .map { it.snapshot() }
    }

    private fun startForegroundIfNeeded() {
        val hasWork = tasks.values.any {
            it.status == DownloadStatus.Ready || it.status == DownloadStatus.Running ||
                it.status == DownloadStatus.Loading || it.status == DownloadStatus.Saving
        }
        if (!hasWork) return
        val intent = Intent(context, DownloadForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopForegroundIfIdle() {
        val hasWork = tasks.values.any {
            it.status == DownloadStatus.Ready || it.status == DownloadStatus.Running ||
                it.status == DownloadStatus.Loading || it.status == DownloadStatus.Saving
        }
        if (!hasWork) {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        return if (relative.startsWith("http://") || relative.startsWith("https://")) {
            relative
        } else {
            URL(URL(base), relative.trim()).toString()
        }
    }

    private fun probeHeaders(t: MutableTask): HttpClient.ProbeHeaders {
        val cookie = runCatching { CookieManager.getInstance().getCookie(t.url) }.getOrNull()
        return HttpClient.ProbeHeaders(
            cookie = cookie,
            referer = t.sourcePageUrl.ifBlank { null }
        )
    }

    private suspend fun <T> retryIO(maxTries: Int, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(maxOf(1, maxTries)) { attempt ->
            coroutineContext.ensureActive()
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                Log.d(TAG, "retry ${attempt + 1}/$maxTries: ${e.message}")
                delay(1500L + attempt * 500L)
            }
        }
        throw last ?: IllegalStateException("retry failed")
    }

    private fun stableName(url: String, ext: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val dig = md.digest(url.toByteArray(Charsets.UTF_8))
        val hex = dig.joinToString("") { b -> "%02x".format(b) }.take(24)
        return "$hex.$ext"
    }

    private fun loadRecordPairs(recordFile: File): Map<String, String> {
        if (!recordFile.exists()) return emptyMap()
        val lines = recordFile.readLines()
        val map = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < lines.size) {
            map[lines[i]] = lines[i + 1]
            i += 2
        }
        return map
    }

    private fun isRecorded(recordFile: File, url: String, name: String): Boolean {
        val pairs = loadRecordPairs(recordFile)
        return pairs[url] == name
    }

    private fun countExistingDownloadedBytes(dir: File): Long {
        var total = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && (f.extension == "ts" || f.extension == "key")) {
                total += f.length()
            }
        }
        return total
    }

    private enum class CancelReason { None, UserPause, UserStop, Network }

    private inner class MutableTask(initial: DownloadTask) {
        val taskId = initial.taskId
        val fileName = initial.fileName
        val videoType = initial.videoType
        val fileExtension = initial.fileExtension
        val url = initial.url
        val sourcePageUrl = initial.sourcePageUrl
        val sourcePageTitle = initial.sourcePageTitle
        @Volatile var size = initial.size
        @Volatile var status = DownloadStatus.entries.find {
            it.name.equals(initial.status, true)
        } ?: DownloadStatus.Ready
        @Volatile var failedReason = initial.failedReason
        @Volatile var totalDownloaded = initial.totalDownloaded
        @Volatile var currentSpeed = initial.currentSpeed
        @Volatile var pausedByNetwork = false
        @Volatile var cancelReason = CancelReason.None
        val createdAt = System.currentTimeMillis()
        val isM3u8 = videoType.equals("m3u8", true)
        val tempDir: File get() = File(rootDir, "$fileName.temp")

        fun snapshot() = DownloadTask(
            taskId = taskId,
            fileName = fileName,
            videoType = videoType,
            fileExtension = fileExtension,
            url = url,
            sourcePageUrl = sourcePageUrl,
            sourcePageTitle = sourcePageTitle,
            size = size,
            status = status.name.lowercase(),
            failedReason = failedReason,
            totalDownloaded = totalDownloaded,
            currentSpeed = currentSpeed
        )
    }

    private data class SegmentJob(
        val url: String,
        val downloadPath: String,
        val segmentFileName: String
    )

    companion object {
        private const val TAG = "DownloadManager"
    }
}

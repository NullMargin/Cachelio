package com.holeintimes.vbrowser.data.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClient {
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    val client: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    data class HeadResult(
        val realUrl: String,
        val contentType: String?,
        val contentLength: Long?,
        val acceptRanges: Boolean,
        val headers: Map<String, List<String>>,
        val code: Int = 0
    )

    data class ProbeHeaders(
        val cookie: String? = null,
        val referer: String? = null
    )

    /**
     * Probe media URL metadata. Many CDNs reject HEAD; fall back to Range GET.
     */
    fun probe(url: String, extra: ProbeHeaders = ProbeHeaders()): HeadResult {
        try {
            val head = executeProbe(url, methodHead = true, extra = extra)
            if (head.code in 200..399 && !head.contentType.isNullOrBlank()) {
                return head
            }
        } catch (_: Exception) {
            // fall through to GET
        }
        return executeProbe(url, methodHead = false, extra = extra)
    }

    fun head(url: String): HeadResult = probe(url)

    fun getString(url: String, extra: ProbeHeaders = ProbeHeaders()): String {
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", USER_AGENT)
        applyExtra(builder, extra)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: ""
        }
    }

    fun downloadToFile(
        url: String,
        dest: File,
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
        extra: ProbeHeaders = ProbeHeaders(),
        onBytes: ((Long) -> Unit)? = null
    ) {
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", USER_AGENT)
        applyExtra(builder, extra)
        if (rangeStart != null && rangeEnd != null) {
            builder.header("Range", "bytes=$rangeStart-$rangeEnd")
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty body")
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        onBytes?.invoke(read.toLong())
                    }
                }
            }
        }
    }

    private fun executeProbe(url: String, methodHead: Boolean, extra: ProbeHeaders): HeadResult {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
        applyExtra(builder, extra)
        if (methodHead) {
            builder.head()
        } else {
            // Tiny range probe — enough for Content-Type without downloading the file.
            builder.get().header("Range", "bytes=0-1")
        }
        client.newCall(builder.build()).execute().use { response ->
            return parseHead(response)
        }
    }

    private fun applyExtra(builder: Request.Builder, extra: ProbeHeaders) {
        extra.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        extra.referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
    }

    private fun parseHead(response: Response): HeadResult {
        val headers = mutableMapOf<String, List<String>>()
        response.headers.names().forEach { name ->
            headers[name] = response.headers.values(name)
        }
        val contentType = response.header("Content-Type")
            ?: headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
        val length = response.header("Content-Length")?.toLongOrNull()
            ?: response.header("Content-Range")
                ?.substringAfter('/')
                ?.toLongOrNull()
        val acceptRanges = response.header("Accept-Ranges")?.contains("bytes", true) == true ||
            response.code == 206
        return HeadResult(
            realUrl = response.request.url.toString(),
            contentType = contentType,
            contentLength = length,
            acceptRanges = acceptRanges,
            headers = headers,
            code = response.code
        )
    }

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}

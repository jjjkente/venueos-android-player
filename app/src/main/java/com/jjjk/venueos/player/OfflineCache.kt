package com.jjjk.venueos.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Real on-device offline storage for the signage display. Two halves:
//
// 1. Media (/uploads/*) is downloaded to app storage ahead of time by
//    syncMedia() (driven from AgentService) and served straight from disk by
//    intercept() from then on - online or not, since upload filenames are
//    timestamped and never change content. Before this the display relied
//    on the WebView's own HTTP cache, which works on the Pi's Chromium but is
//    only a few tens of MB inside Android WebView, so any real-sized video
//    got evicted and errored out the moment the venue lost internet.
//
// 2. The display page itself, its fonts/icons and the now-playing playlist
//    JSON are proxied through intercept(): every good response is saved, and
//    when the network fails the last saved copy is served instead. That's
//    what lets a panel that powers up with no internet still boot straight
//    into its last playlist rather than Android's "Webpage not available".
object OfflineCache {
    private const val TAG = "VenueOSCache"

    // Leave this much free on the device no matter what - a panel that
    // fills its own storage can stop booting properly.
    private const val MIN_FREE_BYTES = 500L * 1024 * 1024

    // Media not referenced by any playlist sync for this long gets pruned.
    private const val PRUNE_AFTER_MS = 14L * 24 * 60 * 60 * 1000

    // After a failed network attempt, serve cached pages immediately for a
    // while instead of blocking every request on another connect timeout -
    // the display polls now-playing every 2s and WebView's request threads
    // are a small shared pool.
    private const val OFFLINE_BACKOFF_MS = 15_000L

    @Volatile private var lastNetworkFailureAt = 0L
    // ConcurrentHashMap.newKeySet() is API 24+, this app's minSdk is 21
    private val downloading: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    private fun mediaDir(ctx: Context) = File(ctx.filesDir, "offline/media").apply { mkdirs() }
    private fun pageDir(ctx: Context) = File(ctx.filesDir, "offline/pages").apply { mkdirs() }

    // /signage/uploads/foo/bar.mp4 -> foo_bar.mp4 (upload names are already
    // unique timestamps, so the path alone is a safe key)
    private fun mediaFile(ctx: Context, uploadPath: String) =
        File(mediaDir(ctx), uploadPath.substringAfter("/uploads/").replace('/', '_'))

    private fun pageKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isOfflineRecently() = System.currentTimeMillis() - lastNetworkFailureAt < OFFLINE_BACKOFF_MS

    // ---- WebView interception ----

    fun intercept(ctx: Context, request: WebResourceRequest, venueHost: String?): WebResourceResponse? {
        if (request.method != "GET") return null
        val uri = request.url
        if (venueHost == null || uri.host != venueHost) return null
        val path = uri.path ?: return null

        if (path.contains("/uploads/") && !path.contains("/uploads/screenshots/")) {
            val file = mediaFile(ctx, path)
            if (file.exists()) return serveFile(file, request.requestHeaders)
            // Not on disk yet - let the WebView fetch it normally this time,
            // and pull our own copy down in the background for next time.
            queueDownload(ctx, uri.toString(), file)
            return null
        }

        val cacheable = path.endsWith("/signage/display") ||
            path.contains("/signage/fonts/") ||
            path.contains("/signage/icons/") ||
            (path.contains("/signage/api/screens/") && path.endsWith("/now-playing"))
        if (!cacheable) return null
        return proxyWithFallback(ctx, uri)
    }

    private fun proxyWithFallback(ctx: Context, uri: Uri): WebResourceResponse? {
        val url = uri.toString()
        val body = File(pageDir(ctx), pageKey(url))
        val meta = File(pageDir(ctx), pageKey(url) + ".type")

        if (!isOfflineRecently()) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 6_000
                conn.readTimeout = 10_000
                conn.useCaches = false
                try {
                    val code = conn.responseCode
                    val contentType = conn.contentType ?: "application/octet-stream"
                    if (code in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        // now-playing is polled every 2s - only touch flash
                        // storage when the content actually changed.
                        if (!body.exists() || !body.readBytes().contentEquals(bytes)) {
                            writeAtomically(body, bytes)
                            meta.writeText(contentType)
                        }
                        return response(contentType, code, bytes.inputStream())
                    }
                    // A 5xx is the server/proxy being down - treat it the same
                    // as no network. Anything else (404 = screen removed etc)
                    // is a real answer the display page needs to see.
                    if (code < 500) {
                        val bytes = conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                        return response(contentType, code, bytes.inputStream())
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network failed for ${uri.path}: ${e.message}")
            }
            lastNetworkFailureAt = System.currentTimeMillis()
        }

        if (body.exists()) {
            val type = if (meta.exists()) meta.readText() else "text/html"
            return response(type, 200, FileInputStream(body))
        }
        // Nothing saved - let the WebView fail normally so MainActivity's
        // error handler can put up the offline screen.
        return null
    }

    private fun response(contentType: String, code: Int, stream: InputStream): WebResourceResponse {
        val mime = contentType.substringBefore(';').trim()
        val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)?.trim() ?: "utf-8"
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Status $code" }
        return WebResourceResponse(mime, charset, code, reason,
            mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"), stream)
    }

    // Video needs byte-range support - the WebView's media player seeks and
    // loops via Range requests and won't play a stream it can't range into.
    private fun serveFile(file: File, headers: Map<String, String>): WebResourceResponse {
        val mime = when (file.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        val total = file.length()
        val range = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
        val match = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (match != null && total > 0) {
            val (s, e) = match.destructured
            val start = if (s.isEmpty()) maxOf(0, total - (e.toLongOrNull() ?: 0)) else s.toLong()
            val end = if (s.isEmpty() || e.isEmpty()) total - 1 else minOf(e.toLong(), total - 1)
            if (start in 0..end) {
                val raf = RandomAccessFile(file, "r").apply { seek(start) }
                val len = end - start + 1
                val stream = object : FilterInputStream(FileInputStream(raf.fd)) {
                    var remaining = len
                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        return super.read().also { if (it >= 0) remaining-- }
                    }
                    override fun read(b: ByteArray, off: Int, n: Int): Int {
                        if (remaining <= 0) return -1
                        val r = super.read(b, off, minOf(n.toLong(), remaining).toInt())
                        if (r > 0) remaining -= r
                        return r
                    }
                    override fun close() { raf.close() }
                }
                return WebResourceResponse(mime, null, 206, "Partial Content", mapOf(
                    "Content-Range" to "bytes $start-$end/$total",
                    "Content-Length" to len.toString(),
                    "Accept-Ranges" to "bytes",
                    "Access-Control-Allow-Origin" to "*"
                ), stream)
            }
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf(
            "Content-Length" to total.toString(),
            "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*"
        ), FileInputStream(file))
    }

    // ---- Downloading ----

    private fun queueDownload(ctx: Context, url: String, dest: File) {
        if (!downloading.add(dest.name)) return
        downloadExecutor.execute {
            try { download(ctx, url, dest) } finally { downloading.remove(dest.name) }
        }
    }

    // Returns true if the file is on disk afterwards, false on a 404 (so the
    // caller can try the next candidate), throws on network trouble.
    private fun download(ctx: Context, url: String, dest: File): Boolean {
        if (dest.exists()) return true
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode == 404) return false
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val size = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L // contentLengthLong is API 24+
            if (size > 0 && dest.parentFile!!.usableSpace - size < MIN_FREE_BYTES) {
                Log.w(TAG, "Skipping ${dest.name} (${size / 1_048_576}MB) - not enough free space")
                return false
            }
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (size > 0 && tmp.length() != size) {
                tmp.delete()
                throw java.io.IOException("Incomplete download of ${dest.name}")
            }
            tmp.renameTo(dest)
            Log.i(TAG, "Cached ${dest.name} (${dest.length() / 1024}KB)")
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun writeAtomically(dest: File, bytes: ByteArray) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(dest)
    }

    // Pulls this screen's current playlist and downloads every upload it
    // references that isn't already on disk. Called periodically from
    // AgentService's own thread (blocking is fine there). Videos are stored
    // as their .mp4 sibling - what display.html's first <source> asks for and
    // what Android decodes in hardware - falling back to the .webm original
    // for old uploads with no mp4.
    fun syncMedia(ctx: Context, venueUrl: String, screenId: String) {
        val json = try {
            val conn = URL("$venueUrl/signage/api/screens/$screenId/now-playing").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
        } catch (e: Exception) {
            Log.w(TAG, "Media sync skipped (offline?): ${e.message}")
            return
        }

        val paths = Regex("\"/uploads/([^\"\\\\]+)\"").findAll(json)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("screenshots/") }
            .toSet()
        val now = System.currentTimeMillis()
        for (p in paths) {
            val candidates = if (p.endsWith(".webm", true)) listOf(p.dropLast(5) + ".mp4", p) else listOf(p)
            for (c in candidates) {
                val dest = mediaFile(ctx, "/uploads/$c")
                if (dest.exists()) { dest.setLastModified(now); break }
                if (!downloading.add(dest.name)) break
                val ok = try {
                    download(ctx, "$venueUrl/signage/uploads/$c", dest)
                } catch (e: Exception) {
                    Log.w(TAG, "Download failed for $c: ${e.message}")
                    true // network trouble, not a 404 - don't fall through to the webm
                } finally {
                    downloading.remove(dest.name)
                }
                if (ok) break
            }
        }
        prune(ctx)
    }

    private fun prune(ctx: Context) {
        val cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS
        mediaDir(ctx).listFiles()?.forEach { f ->
            if (f.name.endsWith(".part") || f.lastModified() < cutoff) {
                if (!downloading.contains(f.name.removeSuffix(".part"))) f.delete()
            }
        }
    }
}

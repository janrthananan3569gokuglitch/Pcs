package com.example.media

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Intercepts `https://appassets.androidplatform.net/` requests for:
 * 1. `/assets/...` -> Bundled HTML/CSS/JS via [WebViewAssetLoader.AssetsPathHandler]
 * 2. `/local-audio/...` -> Local SD card audio files with HTTP 206 Partial Content Range support
 *    and CORS headers so HTML5 `<audio>` seeking and Web Audio API (8D Orbit, Auto-EQ, Visualizer)
 *    run 100% smoothly without CORS errors.
 * 3. `/local-covers/...` -> Cached album art JPEGs with same-origin headers so cover color extraction works.
 */
class LocalMediaWebResponseHandler(context: Context) {

    private val coverCacheDir = File(context.cacheDir, "pcs_covers")

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(LocalAudioScanner.ASSET_HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (!LocalAudioScanner.ASSET_HOST.equals(uri.host, ignoreCase = true)) {
            return null
        }

        val path = uri.path ?: return null
        return when {
            path.startsWith("/local-audio/") -> serveLocalAudio(path.removePrefix("/local-audio/"), request)
            path.startsWith("/local-covers/") -> serveLocalCover(path.removePrefix("/local-covers/"))
            else -> assetLoader.shouldInterceptRequest(uri)
        }
    }

    private fun serveLocalAudio(encodedSegment: String, request: WebResourceRequest): WebResourceResponse {
        val file = decodeAudioPath(encodedSegment)
            ?: return errorResponse(404, "Not Found")
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return errorResponse(404, "Not Found")
        }

        val totalLength = file.length()
        val mimeType = audioMimeType(file.extension)
        val rangeHeader = request.requestHeaders["Range"] ?: request.requestHeaders["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").substringBefore(",").trim()
            val parts = rangeSpec.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val requestedEnd = parts.getOrNull(1)?.toLongOrNull()
            val end = when {
                requestedEnd != null -> minOf(requestedEnd, totalLength - 1L)
                else -> totalLength - 1L
            }

            if (start < 0L || start >= totalLength || end < start) {
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    416,
                    "Range Not Satisfiable",
                    mapOf(
                        "Content-Range" to "bytes */$totalLength",
                        "Access-Control-Allow-Origin" to "*"
                    ),
                    null
                )
            }

            val contentLength = end - start + 1L
            val boundedStream = RangeFileInputStream(file, start, contentLength)
            val headers = mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Range" to "bytes $start-$end/$totalLength",
                "Content-Length" to contentLength.toString(),
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
            return WebResourceResponse(
                mimeType,
                null,
                206,
                "Partial Content",
                headers,
                boundedStream
            )
        }

        val headers = mapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to totalLength.toString(),
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache"
        )
        return WebResourceResponse(
            mimeType,
            null,
            200,
            "OK",
            headers,
            FileInputStream(file)
        )
    }

    private fun serveLocalCover(fileName: String): WebResourceResponse {
        val safeName = File(Uri.decode(fileName)).name
        val coverFile = File(coverCacheDir, safeName)
        if (!coverFile.exists() || !coverFile.isFile || !coverFile.canRead()) {
            return errorResponse(404, "Not Found")
        }
        val headers = mapOf(
            "Content-Length" to coverFile.length().toString(),
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "public, max-age=31536000"
        )
        return WebResourceResponse(
            "image/jpeg",
            null,
            200,
            "OK",
            headers,
            FileInputStream(coverFile)
        )
    }

    private fun decodeAudioPath(segment: String): File? {
        return try {
            val base64Part = segment.substringBeforeLast('.')
            val decodedBytes = Base64.decode(
                base64Part,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val absolutePath = String(decodedBytes, Charsets.UTF_8)
            File(absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun audioMimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        else -> "application/octet-stream"
    }

    private fun errorResponse(statusCode: Int, reason: String): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            statusCode,
            reason,
            mapOf("Access-Control-Allow-Origin" to "*"),
            null
        )
    }

    private class RangeFileInputStream(
        file: File,
        startOffset: Long,
        private var remaining: Long
    ) : InputStream() {
        private val raf = RandomAccessFile(file, "r").apply {
            seek(startOffset)
        }

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val byteRead = raf.read()
            if (byteRead != -1) {
                remaining--
            }
            return byteRead
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val count = raf.read(b, off, toRead)
            if (count > 0) {
                remaining -= count
            }
            return count
        }

        override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

        override fun close() {
            try {
                raf.close()
            } catch (_: Exception) {
            }
        }
    }
}

package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

data class ScannedFolderResult(
    val jsonArrayString: String,
    val trackCount: Int,
    val folderExists: Boolean,
    val folderReadable: Boolean
)

/**
 * High-performance coroutine audio scanner for `/storage/3263-3638/phonks` (or any custom path).
 * - Filters strictly to `.mp3`, `.flac`, `.wav`, `.m4a`, `.ogg`, `.opus`.
 * - Sorts strictly by `lastModified` descending (newest first, oldest last).
 * - Extracts Title, Artist, Album, Duration, and Embedded Album Art via [MediaMetadataRetriever].
 * - Caches downscaled cover art in `cacheDir/pcs_covers` and serves via same-origin WebViewAssetLoader
 *   URLs (`https://appassets.androidplatform.net/local-covers/...`) for zero-lag scrolling and CORS-safe canvas color extraction.
 */
class LocalAudioScanner(private val context: Context) {

    companion object {
        const val DEFAULT_PHONKS_PATH = "/storage/3263-3638/phonks"
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val LOCAL_AUDIO_PREFIX = "https://$ASSET_HOST/local-audio/"
        const val LOCAL_COVERS_PREFIX = "https://$ASSET_HOST/local-covers/"

        private val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "ogg", "opus")
        private const val COVER_CACHE_DIR = "pcs_covers"
        private const val MAX_COVER_DIM_PX = 512
    }

    fun getCoverCacheDir(): File {
        val dir = File(context.cacheDir, COVER_CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun scanFolder(folderPath: String): ScannedFolderResult = withContext(Dispatchers.IO) {
        val targetPath = folderPath.ifBlank { DEFAULT_PHONKS_PATH }
        val dir = File(targetPath)
        if (!dir.exists() || !dir.isDirectory) {
            return@withContext ScannedFolderResult(
                jsonArrayString = "[]",
                trackCount = 0,
                folderExists = false,
                folderReadable = false
            )
        }

        val rawFiles = dir.listFiles()
        if (rawFiles == null) {
            return@withContext ScannedFolderResult(
                jsonArrayString = "[]",
                trackCount = 0,
                folderExists = true,
                folderReadable = false
            )
        }

        // Filter ONLY supported audio extensions and sort strictly by lastModified DESC (newest top)
        val audioFiles = rawFiles
            .filter { file ->
                file.isFile &&
                    file.canRead() &&
                    file.length() > 0L &&
                    file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
            }
            .sortedByDescending { it.lastModified() }

        if (audioFiles.isEmpty()) {
            return@withContext ScannedFolderResult(
                jsonArrayString = "[]",
                trackCount = 0,
                folderExists = true,
                folderReadable = true
            )
        }

        val coverCacheDir = getCoverCacheDir()
        val jsonArray = JSONArray()

        for (file in audioFiles) {
            val trackJson = extractTrackJson(file, coverCacheDir)
            jsonArray.put(trackJson)
        }

        ScannedFolderResult(
            jsonArrayString = jsonArray.toString(),
            trackCount = audioFiles.size,
            folderExists = true,
            folderReadable = true
        )
    }

    private fun extractTrackJson(file: File, coverCacheDir: File): JSONObject {
        val fileHash = sha256Short("${file.absolutePath}|${file.length()}|${file.lastModified()}")
        val cleanBaseName = file.nameWithoutExtension
            .replace('_', ' ')
            .trim()
        val splitParts = cleanBaseName.split(" - ")
        val fallbackArtist = if (splitParts.size > 1) splitParts.first().trim() else "Offline Phonks"
        val fallbackTitle = if (splitParts.size > 1) {
            splitParts.drop(1).joinToString(" - ").trim()
        } else {
            cleanBaseName.ifBlank { file.name }
        }

        var title = fallbackTitle
        var artist = fallbackArtist
        var album = "phonks (Offline)"
        var durationSec = 0.0
        var coverUrl = ""

        val cachedCoverFile = File(coverCacheDir, "$fileHash.jpg")
        val hasCachedCover = cachedCoverFile.exists() && cachedCoverFile.length() > 0L

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { title = it }

            val extractedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            if (extractedArtist != null) {
                artist = extractedArtist
            }

            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { album = it }

            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { durationMs -> durationSec = durationMs / 1000.0 }

            if (hasCachedCover) {
                coverUrl = "$LOCAL_COVERS_PREFIX${cachedCoverFile.name}"
            } else {
                val embeddedArt = retriever.embeddedPicture
                if (embeddedArt != null && embeddedArt.isNotEmpty()) {
                    if (writeOptimizedCoverArt(embeddedArt, cachedCoverFile)) {
                        coverUrl = "$LOCAL_COVERS_PREFIX${cachedCoverFile.name}"
                    }
                }
            }
        } catch (_: Exception) {
            // Gracefully fall back to filename metadata if a file has malformed tags
            if (hasCachedCover) {
                coverUrl = "$LOCAL_COVERS_PREFIX${cachedCoverFile.name}"
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val encodedPath = Base64.encodeToString(
            file.absolutePath.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val ext = file.extension.lowercase(Locale.ROOT)
        val audioUrl = "$LOCAL_AUDIO_PREFIX$encodedPath.$ext"

        return JSONObject().apply {
            put("id", "native-$fileHash")
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("duration", durationSec)
            put("audio_url", audioUrl)
            put("cover_url", coverUrl)
            put("native_path", file.absolutePath)
            put("added_at", file.lastModified() / 1000L)
        }
    }

    private fun writeOptimizedCoverArt(rawBytes: ByteArray, destFile: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > MAX_COVER_DIM_PX ||
                bounds.outHeight / sampleSize > MAX_COVER_DIM_PX
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return false

            try {
                FileOutputStream(destFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                true
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }
}

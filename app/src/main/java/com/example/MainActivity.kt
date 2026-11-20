package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.media.LocalAudioScanner
import com.example.media.LocalMediaWebResponseHandler
import com.example.service.MusicForegroundService
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val BUNDLED_INDEX_URL =
            "https://${LocalAudioScanner.ASSET_HOST}/assets/index.html"

        // Official Poweramp API constants
        private const val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"
        private const val POWERAMP_SERVICE_CLASS = "com.maxmpz.audioplayer.player.PlayerService"
        private const val POWERAMP_API_COMMAND = "com.maxmpz.audioplayer.API_COMMAND"
        private const val POWERAMP_EXTRA_CMD = "cmd"
        private const val POWERAMP_CMD_TOGGLE_PLAY_PAUSE = 1
        private const val POWERAMP_CMD_PAUSE = 2
        private const val POWERAMP_CMD_RESUME = 3
        private const val POWERAMP_CMD_NEXT = 4
        private const val POWERAMP_CMD_PREVIOUS = 5
        private const val POWERAMP_CMD_STOP = 6
        private const val POWERAMP_CMD_OPEN_TO_PLAY = 20
    }

    private lateinit var audioScanner: LocalAudioScanner
    private lateinit var mediaResponseHandler: LocalMediaWebResponseHandler
    private var webView: WebView? = null
    private var scanJob: Job? = null
    private var pendingScanPath: String = LocalAudioScanner.DEFAULT_PHONKS_PATH

    private var showStorageBanner by mutableStateOf(false)
    private var needsAllFilesAccess by mutableStateOf(false)

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        evaluateStoragePermissionsAndScan(pendingScanPath, triggerPromptIfMissing = false)
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        evaluateStoragePermissionsAndScan(pendingScanPath, triggerPromptIfMissing = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure hardware volume buttons always adjust media volume (STREAM_MUSIC)
        volumeControlStream = AudioManager.STREAM_MUSIC

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        audioScanner = LocalAudioScanner(applicationContext)
        mediaResponseHandler = LocalMediaWebResponseHandler(applicationContext)

        MusicForegroundService.mediaActionListener = { action ->
            runOnUiThread {
                webView?.evaluateJavascript(
                    "window.PCS_NATIVE_MEDIA_ACTION && window.PCS_NATIVE_MEDIA_ACTION(${JSONObject.quote(action)});",
                    null
                )
            }
        }

        requestNotificationPermissionIfNeeded()

        setContent {
            MyApplicationTheme {
                BackHandler(enabled = true) {
                    handleSystemBack()
                }
                PcsHybridPlayerHost(
                    showStorageBanner = showStorageBanner,
                    needsAllFilesAccess = needsAllFilesAccess,
                    onGrantMediaPermission = { requestAudioStoragePermissions() },
                    onGrantAllFilesAccess = { launchAllFilesAccessSettings() },
                    onDismissBanner = { showStorageBanner = false },
                    onWebViewCreated = { createdWebView ->
                        webView = createdWebView
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep timers active when returning from background
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        // CRITICAL: Do NOT pause WebView or JS timers while music is playing so 8D Orbit,
        // Auto-EQ, and background playback continue seamlessly when screen is locked or minimized.
        if (!MusicForegroundService.isCurrentlyPlaying) {
            webView?.onPause()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            MusicForegroundService.mediaActionListener = null
            stopService(Intent(this, MusicForegroundService::class.java))
            webView?.destroy()
            webView = null
        }
        super.onDestroy()
    }

    private fun handleSystemBack() {
        val currentWebView = webView
        if (currentWebView == null) {
            finish()
            return
        }
        currentWebView.evaluateJavascript(
            "(window.PCS_HANDLE_BACK && window.PCS_HANDLE_BACK()) ? 'handled' : 'unhandled'"
        ) { result ->
            if (result?.contains("handled") == true) {
                return@evaluateJavascript
            }
            if (currentWebView.canGoBack()) {
                currentWebView.goBack()
            } else if (MusicForegroundService.isCurrentlyPlaying) {
                // Keep audio playing in background when user presses Back on root screen
                moveTaskToBack(true)
            } else {
                finish()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runtimePermissionLauncher.launch(buildRequiredRuntimePermissions())
            }
        }
    }

    private fun buildRequiredRuntimePermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.toTypedArray()
    }

    private fun hasAudioStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAudioStoragePermissions() {
        runtimePermissionLauncher.launch(buildRequiredRuntimePermissions())
    }

    private fun launchAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                allFilesAccessLauncher.launch(intent)
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                allFilesAccessLauncher.launch(fallback)
            }
        }
    }

    private fun evaluateStoragePermissionsAndScan(path: String, triggerPromptIfMissing: Boolean) {
        pendingScanPath = path.ifBlank { LocalAudioScanner.DEFAULT_PHONKS_PATH }
        val hasPerm = hasAudioStoragePermission()

        if (!hasPerm) {
            showStorageBanner = true
            needsAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            if (triggerPromptIfMissing) {
                requestAudioStoragePermissions()
            }
            // While waiting for storage permission, fall back cleanly to online cloud if available
            notifyScanStatus("GRANT STORAGE", fallbackOnline = isNetworkAvailable())
            return
        }

        runNativeFolderScan(pendingScanPath)
    }

    private fun runNativeFolderScan(folderPath: String) {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            val result = audioScanner.scanFolder(folderPath)
            if (result.trackCount > 0) {
                showStorageBanner = false
                val quotedJson = JSONObject.quote(result.jsonArrayString)
                webView?.evaluateJavascript(
                    "window.PCS_LOAD_NATIVE_OFFLINE_TRACKS && window.PCS_LOAD_NATIVE_OFFLINE_TRACKS($quotedJson);",
                    null
                )
            } else {
                // On Android 11+, direct access to removable SD card (/storage/3263-3638/phonks)
                // may require All Files Access (MANAGE_EXTERNAL_STORAGE) if folder exists but isn't readable.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !Environment.isExternalStorageManager()
                ) {
                    needsAllFilesAccess = true
                    showStorageBanner = true
                }
                val statusLabel = when {
                    !result.folderExists -> "SD NOT MOUNTED"
                    !result.folderReadable -> "SD ACCESS NEEDED"
                    else -> "NO AUDIO FILES"
                }
                notifyScanStatus(statusLabel, fallbackOnline = isNetworkAvailable())
            }
        }
    }

    private fun notifyScanStatus(status: String, fallbackOnline: Boolean) {
        runOnUiThread {
            webView?.evaluateJavascript(
                "window.PCS_ON_NATIVE_SCAN_STATUS && window.PCS_ON_NATIVE_SCAN_STATUS(${JSONObject.quote(status)}, $fallbackOnline);",
                null
            )
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(context: Context): WebView {
        return WebView(context).apply {
            setBackgroundColor(AndroidColor.parseColor("#05070C"))
            overScrollMode = WebView.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }

            addJavascriptInterface(PCSNativeBridge(), "PCSNativeBridge")

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request != null) {
                        val intercepted = mediaResponseHandler.shouldInterceptRequest(request)
                        if (intercepted != null) return intercepted
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    // Keep bundled app assets and PCS.XO.JE inside WebView
                    if (uri.host == LocalAudioScanner.ASSET_HOST || uri.host == "pcs.xo.je") {
                        return false
                    }
                    return false
                }
            }

            loadUrl(BUNDLED_INDEX_URL)
        }
    }

    /**
     * Native Kotlin <-> WebView JavaScript Bridge (`window.PCSNativeBridge`).
     */
    inner class PCSNativeBridge {

        @JavascriptInterface
        fun scanFolder(path: String) {
            runOnUiThread {
                evaluateStoragePermissionsAndScan(
                    path = path.ifBlank { LocalAudioScanner.DEFAULT_PHONKS_PATH },
                    triggerPromptIfMissing = true
                )
            }
        }

        @JavascriptInterface
        fun controlPoweramp(cmd: String, filePath: String) {
            runOnUiThread {
                dispatchPowerampCommand(cmd, filePath)
            }
        }

        @JavascriptInterface
        fun syncPlaybackState(
            title: String,
            artist: String,
            album: String,
            coverUrl: String,
            isPlaying: Boolean,
            durationMs: Long,
            positionMs: Long
        ) {
            MusicForegroundService.syncPlaybackState(
                context = applicationContext,
                title = title,
                artist = artist,
                album = album,
                coverUrl = coverUrl,
                isPlaying = isPlaying,
                durationMs = durationMs,
                positionMs = positionMs
            )
        }

        @JavascriptInterface
        fun isOnline(): Boolean = isNetworkAvailable()
    }

    private fun dispatchPowerampCommand(cmd: String, filePath: String) {
        if (!isPackageInstalled(POWERAMP_PACKAGE)) {
            Toast.makeText(
                this,
                getString(R.string.toast_poweramp_missing),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val apiCmdCode = when (cmd.lowercase()) {
            "play_file" -> POWERAMP_CMD_OPEN_TO_PLAY
            "toggle_play_pause" -> POWERAMP_CMD_TOGGLE_PLAY_PAUSE
            "pause" -> POWERAMP_CMD_PAUSE
            "resume" -> POWERAMP_CMD_RESUME
            "next" -> POWERAMP_CMD_NEXT
            "prev" -> POWERAMP_CMD_PREVIOUS
            "stop" -> POWERAMP_CMD_STOP
            else -> return
        }

        val intent = Intent(POWERAMP_API_COMMAND).apply {
            component = ComponentName(POWERAMP_PACKAGE, POWERAMP_SERVICE_CLASS)
            putExtra(POWERAMP_EXTRA_CMD, apiCmdCode)
            if (apiCmdCode == POWERAMP_CMD_OPEN_TO_PLAY && filePath.isNotBlank()) {
                data = Uri.fromFile(File(filePath))
            }
        }

        val oldPolicy = StrictMode.getVmPolicy()
        try {
            // Allow file:// URI required by Poweramp's official OPEN_TO_PLAY API_COMMAND
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
                } catch (_: Exception) {
                    startService(intent)
                }
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            // Broadcast fallback if Poweramp's service cannot be started directly in background
            val broadcastIntent = Intent(POWERAMP_API_COMMAND).apply {
                setPackage(POWERAMP_PACKAGE)
                putExtra(POWERAMP_EXTRA_CMD, apiCmdCode)
                if (apiCmdCode == POWERAMP_CMD_OPEN_TO_PLAY && filePath.isNotBlank()) {
                    data = Uri.fromFile(File(filePath))
                }
            }
            sendBroadcast(broadcastIntent)
        } finally {
            StrictMode.setVmPolicy(oldPolicy)
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    @Composable
    private fun PcsHybridPlayerHost(
        showStorageBanner: Boolean,
        needsAllFilesAccess: Boolean,
        onGrantMediaPermission: () -> Unit,
        onGrantAllFilesAccess: () -> Unit,
        onDismissBanner: () -> Unit,
        onWebViewCreated: (WebView) -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pcs_webview"),
                factory = { ctx ->
                    createConfiguredWebView(ctx).also(onWebViewCreated)
                }
            )

            AnimatedVisibility(
                visible = showStorageBanner,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(max = 560.dp)
            ) {
                StoragePermissionCard(
                    needsAllFilesAccess = needsAllFilesAccess,
                    onGrantMediaPermission = onGrantMediaPermission,
                    onGrantAllFilesAccess = onGrantAllFilesAccess,
                    onDismiss = onDismissBanner
                )
            }
        }
    }
}

@Composable
private fun StoragePermissionCard(
    needsAllFilesAccess: Boolean,
    onGrantMediaPermission: () -> Unit,
    onGrantAllFilesAccess: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("storage_permission_banner")
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.SdStorage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.permission_banner_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.permission_banner_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dismiss_permission_banner")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.permission_dismiss_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onGrantMediaPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("grant_media_permission_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.permission_grant_button),
                        fontWeight = FontWeight.Bold
                    )
                }

                if (needsAllFilesAccess) {
                    OutlinedButton(
                        onClick = onGrantAllFilesAccess,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("grant_sd_card_access_button")
                    ) {
                        Text(
                            text = stringResource(R.string.permission_all_files_button),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

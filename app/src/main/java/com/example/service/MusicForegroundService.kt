package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.example.MainActivity
import com.example.R
import com.example.media.LocalAudioScanner
import java.io.File

/**
 * Persistent Foreground Media Service for PCS.xo.je:
 * - Maintains active [MediaSessionCompat] with lock-screen & notification transport controls.
 * - Requests [AudioManager.STREAM_MUSIC] Audio Focus so playback pauses cleanly on phone calls
 *   and ducks/resumes appropriately.
 * - Broadcasts [AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION] for Poweramp Equalizer compatibility.
 * - Holds a partial [PowerManager.WakeLock] while playing so Web Audio DSP (8D Orbit, Auto-EQ)
 *   runs smoothly even when the screen is locked or minimized.
 */
class MusicForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pcs_audio_playback_channel"
        const val NOTIFICATION_ID = 6501

        const val ACTION_SYNC_STATE = "com.example.action.SYNC_STATE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_PREV = "com.example.action.PREV"
        const val ACTION_NEXT = "com.example.action.NEXT"
        const val ACTION_STOP = "com.example.action.STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ALBUM = "extra_album"
        const val EXTRA_COVER_URL = "extra_cover_url"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_POSITION_MS = "extra_position_ms"

        @Volatile
        var mediaActionListener: ((String) -> Unit)? = null

        @Volatile
        var isCurrentlyPlaying: Boolean = false
            private set

        fun syncPlaybackState(
            context: Context,
            title: String,
            artist: String,
            album: String,
            coverUrl: String,
            isPlaying: Boolean,
            durationMs: Long,
            positionMs: Long
        ) {
            val intent = Intent(context, MusicForegroundService::class.java).apply {
                action = ACTION_SYNC_STATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ALBUM, album)
                putExtra(EXTRA_COVER_URL, coverUrl)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_POSITION_MS, positionMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPlaying) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Ignore background start restriction if app is already closing
            }
        }
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var effectSessionOpened = false
    private var isForegroundStarted = false

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentCoverUrl: String = ""
    private var currentCoverBitmap: Bitmap? = null
    private var currentDurationMs: Long = 0L
    private var currentPositionMs: Long = 0L

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isCurrentlyPlaying) {
                    mediaActionListener?.invoke("pause")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // User or system regained focus; keep AudioFocus ready
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        currentTitle = getString(R.string.notification_default_title)
        currentArtist = getString(R.string.notification_default_artist)

        createNotificationChannel()
        initMediaSession()
        initWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
        }

        when (intent?.action) {
            ACTION_SYNC_STATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                    .ifBlank { getString(R.string.notification_default_title) }
                val artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                    .ifBlank { getString(R.string.notification_default_artist) }
                val album = intent.getStringExtra(EXTRA_ALBUM).orEmpty()
                val coverUrl = intent.getStringExtra(EXTRA_COVER_URL).orEmpty()
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)

                if (coverUrl != currentCoverUrl) {
                    currentCoverUrl = coverUrl
                    currentCoverBitmap = loadLocalCoverBitmap(coverUrl)
                }

                currentTitle = title
                currentArtist = artist
                currentAlbum = album
                currentDurationMs = durationMs
                currentPositionMs = positionMs
                isCurrentlyPlaying = playing

                if (playing) {
                    requestMusicAudioFocus()
                    openAudioEffectSession()
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }

                updateMediaSessionState()
                val notification = buildMediaNotification()
                if (playing || !isForegroundStarted) {
                    startForegroundCompat(notification)
                } else {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
            ACTION_TOGGLE_PLAY_PAUSE -> mediaActionListener?.invoke("toggle")
            ACTION_PREV -> mediaActionListener?.invoke("prev")
            ACTION_NEXT -> mediaActionListener?.invoke("next")
            ACTION_STOP -> {
                isCurrentlyPlaying = false
                mediaActionListener?.invoke("stop")
                releaseWakeLock()
                abandonMusicAudioFocus()
                closeAudioEffectSession()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                isForegroundStarted = false
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isCurrentlyPlaying = false
        releaseWakeLock()
        abandonMusicAudioFocus()
        closeAudioEffectSession()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "PCSAudioSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    mediaActionListener?.invoke("play")
                }

                override fun onPause() {
                    mediaActionListener?.invoke("pause")
                }

                override fun onSkipToNext() {
                    mediaActionListener?.invoke("next")
                }

                override fun onSkipToPrevious() {
                    mediaActionListener?.invoke("prev")
                }

                override fun onStop() {
                    mediaActionListener?.invoke("stop")
                }
            })
            isActive = true
        }
    }

    private fun initWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PCSAudio::BackgroundDspWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4h max safety timeout
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun requestMusicAudioFocus() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun abandonMusicAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        hasAudioFocus = false
    }

    /**
     * Broadcasts standard Android AudioEffect control session intent so Poweramp Equalizer
     * can attach to PCS.xo.je's audio stream automatically.
     */
    private fun openAudioEffectSession() {
        if (effectSessionOpened) return
        effectSessionOpened = true
        val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        sendBroadcast(intent)
    }

    private fun closeAudioEffectSession() {
        if (!effectSessionOpened) return
        effectSessionOpened = false
        val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateMediaSessionState() {
        val stateCode = if (isCurrentlyPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(stateCode, currentPositionMs, if (isCurrentlyPlaying) 1.0f else 0.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)

        currentCoverBitmap?.let { bmp ->
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
        }
        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun buildMediaNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPending = serviceActionPendingIntent(ACTION_PREV, 1)
        val togglePending = serviceActionPendingIntent(ACTION_TOGGLE_PLAY_PAUSE, 2)
        val nextPending = serviceActionPendingIntent(ACTION_NEXT, 3)
        val stopPending = serviceActionPendingIntent(ACTION_STOP, 4)

        val playPauseIcon = if (isCurrentlyPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        val playPauseLabel = if (isCurrentlyPlaying) {
            getString(R.string.action_pause)
        } else {
            getString(R.string.action_play)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_music)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText(currentAlbum.ifBlank { null })
            .setLargeIcon(currentCoverBitmap)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(stopPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isCurrentlyPlaying)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_notif_prev, getString(R.string.action_previous), prevPending)
            .addAction(playPauseIcon, playPauseLabel, togglePending)
            .addAction(R.drawable.ic_notif_next, getString(R.string.action_next), nextPending)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundStarted = true
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun loadLocalCoverBitmap(coverUrl: String): Bitmap? {
        if (!coverUrl.startsWith(LocalAudioScanner.LOCAL_COVERS_PREFIX)) return null
        val fileName = coverUrl.removePrefix(LocalAudioScanner.LOCAL_COVERS_PREFIX)
        val file = File(File(cacheDir, "pcs_covers"), File(fileName).name)
        if (!file.exists() || !file.canRead()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }
}

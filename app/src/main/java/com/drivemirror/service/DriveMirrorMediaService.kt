package com.drivemirror.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.drivemirror.ui.MainActivity

/**
 * MediaBrowserService that makes the app visible in Android Auto's media section.
 * When the user taps play, it launches MainActivity to start the mirroring pipeline.
 */
class DriveMirrorMediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "DriveMirrorMediaService"
        private const val ROOT_ID = "root"
        private const val MEDIA_ID_START = "start_drivemirror"
        private const val CHANNEL_ID = "drivemirror_auto_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private lateinit var mediaSession: MediaSessionCompat

    private val stateBuilder = PlaybackStateCompat.Builder()
        .setActions(
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
        )

    private val nowPlayingMetadata = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MEDIA_ID_START)
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "DriveMirror")
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Mirror attivo")
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Screen Mirror")
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
        .build()

    private val sessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() {
            Log.i(TAG, "onPlay")
            activateSession()
            launchMainActivity()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.i(TAG, "onPlayFromMediaId: $mediaId")
            if (mediaId == MEDIA_ID_START) {
                activateSession()
                launchMainActivity()
            }
        }

        override fun onPause() {
            mediaSession.setPlaybackState(
                stateBuilder.setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f).build()
            )
        }

        override fun onStop() {
            mediaSession.isActive = false
            mediaSession.setPlaybackState(
                stateBuilder.setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f).build()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "DriveMirrorSession")

        @Suppress("DEPRECATION")
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        mediaSession.setCallback(sessionCallback)

        mediaSession.setPlaybackState(
            stateBuilder.setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f).build()
        )

        mediaSession.isActive = true
        sessionToken = mediaSession.sessionToken

        Log.i(TAG, "Servizio avviato")
    }

    private fun activateSession() {
        mediaSession.setMetadata(nowPlayingMetadata)
        mediaSession.setPlaybackState(
            stateBuilder.setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1f).build()
        )
        Handler(Looper.getMainLooper()).postDelayed({
            mediaSession.setPlaybackState(
                stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f).build()
            )
            mediaSession.isActive = true
        }, 300)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        if (parentId == ROOT_ID) {
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_START)
                .setTitle("Avvia DriveMirror")
                .setSubtitle("Tocca per avviare il mirror")
                .setIconUri(
                    android.net.Uri.parse(
                        "android.resource://$packageName/${android.R.drawable.ic_media_play}"
                    )
                )
                .build()

            items.add(
                MediaBrowserCompat.MediaItem(
                    description,
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        }

        result.sendResult(items)
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("from_android_auto", true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            showStartNotification(intent)
            Log.i(TAG, "Android 14+: notifica mostrata (BAL bloccato)")
        } else {
            try {
                startActivity(intent)
                Log.i(TAG, "MainActivity avviata direttamente")
            } catch (e: Exception) {
                Log.w(TAG, "startActivity fallito, uso notifica", e)
                showStartNotification(intent)
            }
        }
    }

    private fun showStartNotification(intent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DriveMirror")
            .setContentText("Tocca per avviare il mirroring")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "DriveMirror Auto",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifiche per Android Auto"
            }.also {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(it)
            }
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}

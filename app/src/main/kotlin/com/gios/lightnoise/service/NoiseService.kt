package com.gios.lightnoise.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.gios.lightnoise.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the render thread alive with the screen off and puts a stop control in the
 * shade. Playback itself lives in [NoiseController]; this class only holds the process
 * up, owns the notification, and gives the audio focus back when it goes away.
 */
class NoiseService : Service() {

    companion object {
        const val ACTION_STOP = "com.gios.lightnoise.STOP"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var refreshJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NoiseController.attach(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NoiseController.stop()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        requestFocus()
        acquireWakeLock()

        // The countdown is the only part of this notification that moves, and it only moves
        // when a timer is set. The old version rewrote the notification every 20 s regardless,
        // so a plain overnight session — no timer, subtext pinned to the fixed string
        // "Playing" — posted the same notification about 1,400 times before morning, waking
        // the process out of Doze each time while the playback wakelock was held.
        //
        // NoiseController already ticks `remainingSeconds` once a second, but only while a
        // timer job exists, so watching the state it publishes is enough: with no timer this
        // collector simply never emits a second time and the loop costs nothing.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            NoiseController.state
                .map { it.playing to notificationSubtext() }
                .distinctUntilChanged()
                .collect { (playing, _) ->
                    if (playing) notificationManager().notify(NOTIFICATION_ID, buildNotification())
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        abandonFocus()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away should not kill a sleep session that is still running.
        super.onTaskRemoved(rootIntent)
    }

    // ---------------------------------------------------------------- notification

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager().createNotificationChannel(channel)
    }

    /**
     * The one line on the notification that can change while playing.
     *
     * Pulled out of [buildNotification] so the refresh collector can compare *this* rather
     * than re-posting whenever any unrelated part of the state changes — volume, mix, the
     * selected sound. Those are all invisible from the shade.
     */
    private fun notificationSubtext(): String {
        val s = NoiseController.state.value
        if (s.timerEndsAt <= 0L) return "Playing"
        val m = s.remainingSeconds / 60
        val sec = s.remainingSeconds % 60
        return if (m > 0) "Stops in ${m}m" else "Stops in ${sec}s"
    }

    private fun buildNotification(): Notification {
        val s = NoiseController.state.value
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NoiseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val sub = notificationSubtext()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(NoiseController.nowPlayingLabel())
            .setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stop,
                ).build(),
            )
            .build()
    }

    // ------------------------------------------------------------------ audio focus

    private fun requestFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            // A phone call or a notification should not end the session outright.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> NoiseController.stop()
                    else -> Unit
                }
            }
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightNoise:playback").apply {
            setReferenceCounted(false)
            // Longest supported session plus slack, so a stuck lock cannot drain the phone.
            acquire(13 * 60 * 60 * 1000L)
        }
    }
}

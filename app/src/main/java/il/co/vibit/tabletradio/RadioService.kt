package il.co.vibit.tabletradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** מצב נגינה משותף ל-UI. */
object RadioState {
    enum class Status { IDLE, CONNECTING, PLAYING, RETRYING, PAUSED_TV }

    @Volatile var station: Station? = null
    @Volatile var status: Status = Status.IDLE

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { main.post { listeners.toList().forEach { it() } } }
}

object Prefs {
    private const val FILE = "radio"
    fun lastStation(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("last", null)
    fun wantPlaying(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("want", false)
    fun save(ctx: Context, id: String?, want: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("last", id).putBoolean("want", want).apply()
    }
}

class RadioService : Service() {

    companion object {
        const val ACTION_PLAY = "il.co.vibit.tabletradio.PLAY"
        const val ACTION_STOP = "il.co.vibit.tabletradio.STOP"
        const val ACTION_RESUME = "il.co.vibit.tabletradio.RESUME"
        const val ACTION_PAUSE_FOR_TV = "il.co.vibit.tabletradio.PAUSE_TV"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "radio"
        private const val NOTIF_ID = 99
        private const val UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        fun send(ctx: Context, action: String, id: String? = null) {
            val i = Intent(ctx, RadioService::class.java).setAction(action)
            if (id != null) i.putExtra(EXTRA_ID, id)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Android 12+ may refuse from background; activity will retry on resume
            }
        }
    }

    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var station: Station? = null
    private var urlIndex = 0
    private var wantPlaying = false
    private var pausedForTv = false
    private var retryCount = 0
    private var bufferingSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(false)
            .build()
        // ללא ניהול audio focus — אף אפליקציה אחרת לא תעצור את הרדיו
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false,
        )
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        retryCount = 0
                        bufferingSince = 0
                        if (wantPlaying && !pausedForTv) setStatus(RadioState.Status.PLAYING)
                    }
                    Player.STATE_BUFFERING -> {
                        if (bufferingSince == 0L) bufferingSince = SystemClock.elapsedRealtime()
                        if (RadioState.status != RadioState.Status.RETRYING) setStatus(RadioState.Status.CONNECTING)
                    }
                    Player.STATE_ENDED -> if (wantPlaying && !pausedForTv) scheduleRetry(false)
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (wantPlaying && !pausedForTv) scheduleRetry(true)
            }
        })
        handler.postDelayed(watchdog, 10_000)
    }

    /** בודק כל 10 שניות שהרדיו באמת מנגן, ואם לא — מחבר מחדש. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (wantPlaying && !pausedForTv && station != null) {
                val st = player.playbackState
                val stuck = st == Player.STATE_BUFFERING && bufferingSince != 0L &&
                    SystemClock.elapsedRealtime() - bufferingSince > 25_000
                if (st == Player.STATE_IDLE || st == Player.STATE_ENDED || stuck) {
                    bufferingSince = 0
                    startStream(next = stuck)
                } else if (!player.playWhenReady) {
                    player.playWhenReady = true
                }
            }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_PLAY -> {
                val s = Stations.byId(intent.getStringExtra(EXTRA_ID))
                if (s != null) play(s)
            }
            ACTION_STOP -> stopRadio()
            ACTION_PAUSE_FOR_TV -> {
                if (!wantPlaying) { quietStop(); return START_NOT_STICKY }
                pausedForTv = true
                handler.removeCallbacks(retryRunnable)
                player.pause()
                setStatus(RadioState.Status.PAUSED_TV)
            }
            ACTION_RESUME, null -> {
                pausedForTv = false
                val s = station ?: Stations.byId(Prefs.lastStation(this))
                if (s != null && (wantPlaying || Prefs.wantPlaying(this))) play(s)
                else { quietStop(); return START_NOT_STICKY }
            }
        }
        return START_STICKY
    }

    private fun play(s: Station) {
        val same = station?.id == s.id
        station = s
        wantPlaying = true
        pausedForTv = false
        Prefs.save(this, s.id, true)
        RadioState.station = s
        if (same && player.playbackState == Player.STATE_READY) {
            player.playWhenReady = true
            setStatus(RadioState.Status.PLAYING)
            return
        }
        urlIndex = 0
        retryCount = 0
        startStream(next = false)
    }

    private fun startStream(next: Boolean) {
        val s = station ?: return
        if (next) urlIndex = (urlIndex + 1) % s.urls.size
        val url = s.urls[urlIndex]
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        setStatus(if (retryCount > 0) RadioState.Status.RETRYING else RadioState.Status.CONNECTING)
        bufferingSince = SystemClock.elapsedRealtime()
        player.stop()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        updateNotification()
    }

    private val retryRunnable = Runnable { if (wantPlaying && !pausedForTv) startStream(next = true) }

    private fun scheduleRetry(error: Boolean) {
        retryCount++
        setStatus(RadioState.Status.RETRYING)
        handler.removeCallbacks(retryRunnable)
        val delay = (2_000L * retryCount).coerceAtMost(15_000L)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun stopRadio() {
        wantPlaying = false
        pausedForTv = false
        handler.removeCallbacks(retryRunnable)
        player.stop()
        Prefs.save(this, station?.id, false)
        setStatus(RadioState.Status.IDLE)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun quietStop() {
        setStatus(RadioState.Status.IDLE)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setStatus(s: RadioState.Status) {
        RadioState.status = s
        RadioState.notifyChanged()
    }

    // ---- notification ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "רדיו", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(station?.name ?: getString(R.string.app_name))
            .setContentText(station?.subtitle ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // ממשיך לנגן גם אם האפליקציה נסגרה מהמשימות האחרונות
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}

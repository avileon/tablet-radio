package il.co.vibit.tabletradio

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class TvActivity : Activity() {

    companion object {
        const val EXTRA_ID = "tv_id"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var channel: TvChannel? = null
    private var urlIndex = 0
    private var retries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        setContentView(R.layout.activity_tv)
        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.tvStatus)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        channel = Stations.tvById(intent.getStringExtra(EXTRA_ID))
        if (channel == null) finish()
    }

    override fun onStart() {
        super.onStart()
        hideSystemUi()
        RadioService.send(this, RadioService.ACTION_PAUSE_FOR_TV)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { retries = 0; status.text = "" }
            }
            override fun onPlayerError(error: PlaybackException) {
                retries++
                status.text = "אין שידור — מנסה שוב…"
                handler.postDelayed({ load(next = true) }, (2000L * retries).coerceAtMost(10_000L))
            }
        })
        player = p
        playerView.player = p
        load(next = false)
    }

    private fun load(next: Boolean) {
        val ch = channel ?: return
        val p = player ?: return
        if (next) urlIndex = (urlIndex + 1) % ch.urls.size
        status.text = "טוען ${ch.name}…"
        p.setMediaItem(
            MediaItem.Builder().setUri(ch.urls[urlIndex]).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
        )
        p.prepare()
        p.playWhenReady = true
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        RadioService.send(this, RadioService.ACTION_RESUME)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }
}

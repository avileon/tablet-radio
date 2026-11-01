package il.co.vibit.tabletradio

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** שומר מסך: מצגת תמונות מהאלבום + שעה ותאריך. נגיעה = חזרה לרדיו (הרדיו ממשיך לנגן). */
class ScreensaverActivity : Activity() {

    companion object {
        const val SLIDE_MS = 12_000L
        private const val FADE_MS = 1_500L
    }

    private lateinit var imgA: ImageView
    private lateinit var imgB: ImageView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var now: TextView
    private var front = true
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var urls: List<String> = emptyList()
    private var files: List<File> = emptyList()
    private var order: MutableList<Int> = mutableListOf()
    private var running = false

    private val timeFmt = SimpleDateFormat("HH:mm", Locale("he", "IL"))
    private val dateFmt = SimpleDateFormat("EEEE, d בMMMM yyyy", Locale("he", "IL"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        setContentView(R.layout.activity_screensaver)
        imgA = findViewById(R.id.imgA)
        imgB = findViewById(R.id.imgB)
        clock = findViewById(R.id.ssClock)
        date = findViewById(R.id.ssDate)
        now = findViewById(R.id.ssNow)
        imgB.alpha = 0f
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        running = true
        handler.post(tick)
        io.execute {
            urls = PhotoRepo.urls(this)
            files = PhotoRepo.cachedFiles(this)
            handler.post { if (running) nextSlide() }
        }
    }

    override fun onPause() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        finish() // שומר המסך לא נשאר ברקע
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            val d = Date()
            clock.text = timeFmt.format(d)
            date.text = dateFmt.format(d)
            val s = RadioState.station
            now.text = if (s != null && RadioState.status == RadioState.Status.PLAYING) "♪  ${s.name}" else ""
            handler.postDelayed(this, 10_000)
        }
    }

    private fun nextIndex(size: Int): Int {
        if (order.isEmpty() || order.size != size) order = (0 until size).shuffled().toMutableList()
        val i = order.removeAt(0)
        if (order.isEmpty()) order = (0 until size).shuffled().toMutableList()
        return i
    }

    private fun nextSlide() {
        if (!running) return
        val w = resources.displayMetrics.widthPixels.coerceAtMost(1920)
        val h = resources.displayMetrics.heightPixels.coerceAtMost(1200)
        io.execute {
            var bmp: Bitmap? = null
            if (urls.isNotEmpty()) {
                for (attempt in 0 until 3) {
                    bmp = PhotoRepo.load(this, urls[nextIndex(urls.size)], w, h)
                    if (bmp != null) break
                }
            }
            if (bmp == null && files.isNotEmpty()) {
                bmp = PhotoRepo.decode(files[nextIndex(files.size)], w, h)
            }
            handler.post {
                if (!running) return@post
                if (bmp != null) show(bmp, w, h)
                handler.postDelayed({ nextSlide() }, if (bmp != null) SLIDE_MS else 5_000L)
            }
        }
    }

    private fun show(bmp: Bitmap, w: Int, h: Int) {
        val incoming = if (front) imgB else imgA
        val outgoing = if (front) imgA else imgB
        val photoLandscape = bmp.width >= bmp.height
        val screenLandscape = w >= h
        incoming.scaleType =
            if (photoLandscape == screenLandscape) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        incoming.setImageBitmap(bmp)
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.animate().alpha(1f).setDuration(FADE_MS).start()
        // זום עדין לאורך זמן התצוגה
        incoming.animate().scaleX(1.06f).scaleY(1.06f).setDuration(SLIDE_MS + FADE_MS).start()
        outgoing.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            outgoing.setImageDrawable(null)
        }.start()
        front = !front
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
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

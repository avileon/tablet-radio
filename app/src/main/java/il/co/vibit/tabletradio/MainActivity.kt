package il.co.vibit.tabletradio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        /** קוד יציאה ממצב קיוסק (לחיצה ארוכה על השעון). */
        const val ADMIN_PIN = "1234"
        /** זמן ללא נגיעה עד הפעלת שומר המסך (תמונות + שעה). */
        const val IDLE_MS = 60_000L
        private const val REQ_PHOTOS = 7
        private var pinningAsked = false
    }

    private lateinit var nowTitle: TextView
    private lateinit var nowStatus: TextView
    private lateinit var btnPlay: TextView
    private lateinit var clock: TextView
    private lateinit var volume: SeekBar
    private lateinit var audio: AudioManager
    private val tiles = mutableMapOf<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private val stateListener: () -> Unit = { render() }
    private var lastTouch = 0L
    private var resumed = false

    private val idleCheck = object : Runnable {
        override fun run() {
            if (resumed && hasWindowFocus() &&
                android.os.SystemClock.elapsedRealtime() - lastTouch >= IDLE_MS
            ) {
                lastTouch = android.os.SystemClock.elapsedRealtime()
                startActivity(Intent(this@MainActivity, ScreensaverActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            handler.postDelayed(this, 5_000)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastTouch = android.os.SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        setContentView(R.layout.activity_main)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        nowTitle = findViewById(R.id.nowTitle)
        nowStatus = findViewById(R.id.nowStatus)
        btnPlay = findViewById(R.id.btnPlay)
        clock = findViewById(R.id.clock)
        volume = findViewById(R.id.volume)

        btnPlay.background = rounded(Color.parseColor("#FFD400"), 36f)
        btnPlay.setOnClickListener {
            val playing = RadioState.status != RadioState.Status.IDLE
            if (playing) RadioService.send(this, RadioService.ACTION_STOP)
            else {
                val id = RadioState.station?.id ?: Prefs.lastStation(this) ?: Stations.main[0].id
                RadioService.send(this, RadioService.ACTION_PLAY, id)
            }
        }

        clock.setOnLongClickListener { askPin(); true }

        setupVolume()
        buildContent()
        askPermissionsOnce()
        // הפעלה ראשונה אחרי התקנה — בקשה לחבר אלבום תמונות לשומר המסך
        if (!PhotoRepo.isConfigured(this)) {
            handler.postDelayed({ if (!isFinishing) photoSetup(firstRun = true) }, 1500)
        } else {
            // טעינה מוקדמת של רשימת התמונות
            Thread { try { PhotoRepo.urls(applicationContext) } catch (_: Exception) {} }.start()
        }
    }

    // ---------- UI ----------

    private fun buildContent() {
        val content = findViewById<LinearLayout>(R.id.content)
        content.removeAllViews()
        val wide = resources.configuration.screenWidthDp >= 900

        content.addView(header("תחנות ראשיות"))
        content.addView(grid(3, Stations.main.map { s ->
            tile(s.id, s.name, s.subtitle, s.color, 130) {
                RadioService.send(this, RadioService.ACTION_PLAY, s.id)
            }
        }))

        content.addView(header("ערוצי מוזיקה לפי סגנון"))
        content.addView(grid(if (wide) 5 else 4, Stations.genres.map { s ->
            tile(s.id, s.name, s.subtitle, s.color, 100) {
                RadioService.send(this, RadioService.ACTION_PLAY, s.id)
            }
        }))

        content.addView(header("טלוויזיה"))
        content.addView(grid(2, Stations.tv.map { ch ->
            tile("tv_" + ch.id, "📺  " + ch.name, "שידור חי", ch.color, 100) {
                startActivity(Intent(this, TvActivity::class.java).putExtra(TvActivity.EXTRA_ID, ch.id))
            }
        }))

        content.addView(header("צפייה לפי בחירה (VOD) ו-YouTube"))
        content.addView(grid(if (wide) 4 else 2, Stations.vod.map { v ->
            tile("vod_" + v.id, "▶  " + v.name, v.subtitle, v.color, 100) {
                startActivity(Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_ID, v.id))
            }
        }))
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#9CA3AF"))
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(14), dp(8), dp(6))
    }

    private fun grid(cols: Int, views: List<View>) = GridLayout(this).apply {
        columnCount = cols
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        views.forEach { addView(it) }
    }

    private fun tile(id: String, title: String, sub: String, color: Int, heightDp: Int, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = "$title\n$sub"
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = if (heightDp >= 130) 30f else 22f
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setLineSpacing(0f, 1.1f)
        tv.background = rounded(color, 22f)
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
        )
        lp.width = 0
        lp.height = dp(heightDp)
        lp.setMargins(dp(7), dp(7), dp(7), dp(7))
        tv.layoutParams = lp
        tiles[id] = tv
        return tv
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
        if (stroke) setStroke(dp(5), Color.WHITE)
    }

    private fun render() {
        val s = RadioState.station
        val st = RadioState.status
        nowTitle.text = s?.name ?: "בחר תחנה"
        nowStatus.text = when (st) {
            RadioState.Status.IDLE -> if (s == null) "לחץ על תחנה כדי להתחיל" else "עצור"
            RadioState.Status.CONNECTING -> "מתחבר…"
            RadioState.Status.PLAYING -> "● משדר עכשיו · ${s?.subtitle ?: ""}"
            RadioState.Status.RETRYING -> "אין חיבור — מנסה שוב…"
            RadioState.Status.PAUSED_TV -> "מושהה (טלוויזיה)"
        }
        btnPlay.text = if (st == RadioState.Status.IDLE) "▶" else "■"
        for (station in Stations.all) {
            val t = tiles[station.id] ?: continue
            val active = station.id == s?.id && st != RadioState.Status.IDLE
            t.background = rounded(station.color, 22f, active)
            t.alpha = if (s == null || active || st == RadioState.Status.IDLE) 1f else 0.75f
        }
        volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun setupVolume() {
        volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale("he", "IL")).format(Date())
            handler.postDelayed(this, 15_000)
        }
    }

    // ---------- lifecycle ----------

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        RadioState.addListener(stateListener)
        render()
        handler.post(clockTick)
        resumed = true
        lastTouch = android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 5_000)
        startKiosk()
        // אם הרדיו אמור לנגן — לוודא שהשירות פועל
        if (Prefs.wantPlaying(this) && RadioState.status == RadioState.Status.IDLE) {
            RadioService.send(this, RadioService.ACTION_RESUME)
        }
    }

    override fun onPause() {
        RadioState.removeListener(stateListener)
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(idleCheck)
        resumed = false
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // סיבוב מסך (הטאבלט נדלק לאורך ומסתובב לרוחב) — לבנות מחדש את הרשת לפי הרוחב החדש
        tiles.clear()
        buildContent()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // חסום — מצב קיוסק
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            handler.postDelayed({ volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC) }, 150)
        }
        return super.dispatchKeyEvent(event)
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

    // ---------- kiosk ----------

    private fun isLocked(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= 23) return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        @Suppress("DEPRECATION")
        return am.isInLockTaskMode
    }

    private fun startKiosk() {
        if (isLocked()) return
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        try {
            if (dpm.isDeviceOwnerApp(packageName)) {
                // נעילה מלאה (אחרי הגדרת Device Owner ב-ADB)
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
                startLockTask()
            } else if (!pinningAsked) {
                // הצמדת מסך (Screen pinning) — מבקש אישור פעם אחת בכל הפעלה
                pinningAsked = true
                startLockTask()
            }
        } catch (_: Exception) {}
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "קוד מנהל"
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this)
            .setTitle("תפריט מנהל")
            .setView(input)
            .setPositiveButton("כניסה") { _, _ ->
                if (input.text.toString() == ADMIN_PIN) adminMenu()
                else android.widget.Toast.makeText(this, "קוד שגוי", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun adminMenu() {
        val items = arrayOf("אלבום תמונות לשומר המסך", "הצג שומר מסך עכשיו", "הגדרות המכשיר", "החלפת מסך בית")
        AlertDialog.Builder(this)
            .setTitle("תפריט מנהל")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> photoSetup(firstRun = false)
                    1 -> startActivity(Intent(this, ScreensaverActivity::class.java))
                    2 -> exitKiosk(Settings.ACTION_SETTINGS)
                    3 -> exitKiosk(Settings.ACTION_HOME_SETTINGS)
                }
            }
            .setNegativeButton("סגור", null)
            .show()
    }

    // ---------- photo album setup ----------

    /** בחירת מקור תמונות לשומר המסך — מוצג אוטומטית בהפעלה הראשונה אחרי התקנה. */
    private fun photoSetup(firstRun: Boolean) {
        val items = arrayOf(
            "אלבום Google Photos (קישור שיתוף)",
            "תמונות מהגלריה של המכשיר",
            "בלי תמונות — שעון בלבד",
        )
        AlertDialog.Builder(this)
            .setTitle(if (firstRun) "חיבור אלבום תמונות לשומר המסך" else "אלבום תמונות")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askAlbumLink()
                    1 -> useDevicePhotos()
                    2 -> { PhotoRepo.setSource(this, PhotoRepo.Source.NONE); toast("שומר המסך יציג שעון בלבד") }
                }
            }
            .setCancelable(!firstRun)
            .setNegativeButton(if (firstRun) "אחר כך" else "ביטול") { _, _ ->
                if (firstRun) PhotoRepo.setSource(this, PhotoRepo.Source.NONE)
            }
            .show()
    }

    private fun askAlbumLink() {
        val input = EditText(this).apply {
            hint = "https://photos.app.goo.gl/…"
            setText(PhotoRepo.albumUrl(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        // הדבקה מהלוח אם יש שם קישור
        try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cb.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clip != null && input.text.isEmpty() && (clip.contains("photos.app.goo.gl") || clip.contains("photos.google.com"))) {
                input.setText(clip)
            }
        } catch (_: Exception) {}
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "ב-Google Photos: אלבום ← שיתוף ← יצירת קישור, ולהדביק כאן."
                textSize = 15f
                setPadding(0, 0, 0, dp(8))
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("קישור לאלבום Google Photos")
            .setView(box)
            .setPositiveButton("שמור") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http")) { toast("קישור לא תקין"); askAlbumLink(); return@setPositiveButton }
                toast("בודק את האלבום…")
                Thread {
                    val n = PhotoRepo.probeAlbum(url)
                    runOnUiThread {
                        if (n > 0) {
                            PhotoRepo.setSource(this, PhotoRepo.Source.GOOGLE, url)
                            toast("✓ חובר — נמצאו $n תמונות")
                            Thread { PhotoRepo.urls(applicationContext) }.start()
                        } else {
                            toast("לא נמצאו תמונות. ודא שהאלבום משותף בקישור")
                            askAlbumLink()
                        }
                    }
                }.start()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun useDevicePhotos() {
        val perm = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES"
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_PHOTOS)
        } else {
            PhotoRepo.setSource(this, PhotoRepo.Source.DEVICE)
            toast("שומר המסך יציג את תמונות הגלריה")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHOTOS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                PhotoRepo.setSource(this, PhotoRepo.Source.DEVICE)
                toast("שומר המסך יציג את תמונות הגלריה")
            } else {
                toast("אין הרשאה לתמונות")
                photoSetup(firstRun = !PhotoRepo.isConfigured(this))
            }
        }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    private fun exitKiosk(action: String) {
        try { stopLockTask() } catch (_: Exception) {}
        pinningAsked = true
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------- permissions ----------

    @SuppressLint("BatteryLife")
    private fun askPermissionsOnce() {
        val prefs = getSharedPreferences("setup", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT >= 23 && !prefs.getBoolean("battery_asked", false)) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName")),
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

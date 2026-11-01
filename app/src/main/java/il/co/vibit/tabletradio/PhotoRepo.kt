package il.co.vibit.tabletradio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * תמונות לשומר המסך מאלבום Google Photos משותף (קישור ציבורי).
 * מושך את דף האלבום, מחלץ כתובות lh3.googleusercontent.com, ושומר מטמון בדיסק לעבודה גם בלי רשת.
 */
object PhotoRepo {
    /** אלבום שומר המסך. להחלפה — לשנות כאן ולדחוף. */
    const val ALBUM_URL =
        "https://photos.google.com/share/AF1QipM9Y3LEFxCQskSSST_owNWiy9eX_hSG_FOpY-cZxrTRWU9gnjqrRmR1bm0ka_cwfQ?key=NzVpTWpJOTR6TFUwbEhaLW5JODJ1SE9KTVpIbkxB"

    private const val PREFS = "photos"
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private const val MAX_CACHE_FILES = 150
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private val PHOTO_RE = Regex("""\["(https://lh3\.googleusercontent\.com/pw/[^"]+)",(\d+),(\d+)""")

    /** רשימת כתובות (מהמטמון, ומרענן מהרשת אם עבר זמן). קריאה מ-thread רקע בלבד. */
    fun urls(ctx: Context): List<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = p.getString("urls", "")!!.split('\n').filter { it.isNotBlank() }
        val age = System.currentTimeMillis() - p.getLong("ts", 0)
        if (cached.isNotEmpty() && age < REFRESH_MS) return cached
        return try {
            val html = get(ALBUM_URL).toString(Charsets.UTF_8)
            val list = PHOTO_RE.findAll(html).map { it.groupValues[1] }.distinct().toList()
            if (list.isNotEmpty()) {
                p.edit().putString("urls", list.joinToString("\n")).putLong("ts", System.currentTimeMillis()).apply()
                list
            } else cached
        } catch (_: Exception) {
            cached
        }
    }

    /** תמונות שכבר שמורות בדיסק (גיבוי כשאין רשת). */
    fun cachedFiles(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.length() > 0 }?.toList() ?: emptyList()

    /** טוען תמונה בגודל מסך — מהמטמון או מהרשת. קריאה מ-thread רקע בלבד. */
    fun load(ctx: Context, url: String, w: Int, h: Int): Bitmap? {
        val f = File(dir(ctx), md5(url) + ".jpg")
        if (!f.exists() || f.length() == 0L) {
            try {
                val bytes = get("$url=w$w-h$h")
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
                trim(ctx)
            } catch (_: Exception) {
                return null
            }
        }
        return decode(f, w, h)
    }

    fun decode(f: File, w: Int, h: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "slides").apply { mkdirs() }

    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_CACHE_FILES) files.take(files.size - MAX_CACHE_FILES).forEach { it.delete() }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

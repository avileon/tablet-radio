package il.co.vibit.tabletradio

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * מקור התמונות לשומר המסך — נבחר בהתקנה (ואפשר לשנות מתפריט המנהל):
 *  - GOOGLE: אלבום Google Photos משותף (קישור ציבורי, כולל קישור מקוצר photos.app.goo.gl)
 *  - DEVICE: תמונות מגלריית המכשיר
 *  - NONE: שעון בלבד
 */
object PhotoRepo {
    enum class Source { NONE, GOOGLE, DEVICE }

    private const val PREFS = "photos"
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private const val MAX_CACHE_FILES = 150
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private val PHOTO_RE = Regex("""\["(https://lh3\.googleusercontent\.com/pw/[^"]+)",(\d+),(\d+)""")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context) = prefs(ctx).getBoolean("configured", false)

    fun source(ctx: Context): Source =
        runCatching { Source.valueOf(prefs(ctx).getString("source", "NONE")!!) }.getOrDefault(Source.NONE)

    fun albumUrl(ctx: Context): String = prefs(ctx).getString("album", "") ?: ""

    fun setSource(ctx: Context, source: Source, album: String = "") {
        prefs(ctx).edit()
            .putBoolean("configured", true)
            .putString("source", source.name)
            .putString("album", album)
            .remove("urls").remove("ts")
            .apply()
        File(ctx.cacheDir, "slides").listFiles()?.forEach { it.delete() }
    }

    /** בדיקת קישור אלבום — מחזיר את מספר התמונות שנמצאו (0 = לא תקין). קריאה מ-thread רקע. */
    fun probeAlbum(url: String): Int = try {
        fetchAlbum(url).size
    } catch (_: Exception) {
        0
    }

    private fun fetchAlbum(url: String): List<String> {
        val html = get(url.trim()).toString(Charsets.UTF_8)
        return PHOTO_RE.findAll(html).map { it.groupValues[1] }.distinct().toList()
    }

    /** רשימת התמונות (כתובות רשת או content://). קריאה מ-thread רקע בלבד. */
    fun urls(ctx: Context): List<String> = when (source(ctx)) {
        Source.NONE -> emptyList()
        Source.DEVICE -> deviceImages(ctx)
        Source.GOOGLE -> googleImages(ctx)
    }

    private fun googleImages(ctx: Context): List<String> {
        val p = prefs(ctx)
        val cached = p.getString("urls", "")!!.split('\n').filter { it.isNotBlank() }
        val age = System.currentTimeMillis() - p.getLong("ts", 0)
        val album = albumUrl(ctx)
        if (album.isBlank()) return emptyList()
        if (cached.isNotEmpty() && age < REFRESH_MS) return cached
        return try {
            val list = fetchAlbum(album)
            if (list.isNotEmpty()) {
                p.edit().putString("urls", list.joinToString("\n")).putLong("ts", System.currentTimeMillis()).apply()
                list
            } else cached
        } catch (_: Exception) {
            cached
        }
    }

    private fun deviceImages(ctx: Context): List<String> = try {
        val out = ArrayList<String>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media._ID), null, null,
            MediaStore.Images.Media.DATE_ADDED + " DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && out.size < 1000) {
                out.add(ContentUris.withAppendedId(uri, c.getLong(idCol)).toString())
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }

    /** תמונות שכבר שמורות בדיסק (גיבוי כשאין רשת). */
    fun cachedFiles(ctx: Context): List<File> =
        if (source(ctx) == Source.GOOGLE) dir(ctx).listFiles()?.filter { it.length() > 0 }?.toList() ?: emptyList()
        else emptyList()

    /** טוען תמונה בגודל מסך. קריאה מ-thread רקע בלבד. */
    fun load(ctx: Context, url: String, w: Int, h: Int): Bitmap? {
        if (url.startsWith("content://")) return decodeUri(ctx, Uri.parse(url), w, h)
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

    private fun sampleFor(ow: Int, oh: Int, w: Int, h: Int): Int {
        var sample = 1
        while (ow / (sample * 2) >= w && oh / (sample * 2) >= h) sample *= 2
        return sample
    }

    private fun decodeUri(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(o.outWidth, o.outHeight, w, h) }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Throwable) {
        null
    }

    fun decode(f: File, w: Int, h: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sampleFor(o.outWidth, o.outHeight, w, h) })
    } catch (_: Throwable) {
        null
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "slides").apply { mkdirs() }

    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_CACHE_FILES) files.take(files.size - MAX_CACHE_FILES).forEach { it.delete() }
    }

    private fun get(url: String): ByteArray {
        var target = url
        for (i in 0 until 6) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", UA)
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    target = URL(URL(target), c.getHeaderField("Location")).toString()
                    continue
                }
                if (code !in 200..299) throw RuntimeException("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
        throw RuntimeException("Too many redirects")
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

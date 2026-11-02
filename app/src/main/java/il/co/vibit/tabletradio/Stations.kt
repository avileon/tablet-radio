package il.co.vibit.tabletradio

/**
 * כל התחנות במקום אחד. לעדכון כתובת — לשנות כאן ולדחוף לגיטהאב (ה-APK נבנה אוטומטית).
 * urls: הראשונה ראשית, השאר גיבוי (המעבר אוטומטי בשגיאה).
 * מקור: digital.100fm.co.il (נבדק 01/11/2026).
 */
data class Station(
    val id: String,
    val name: String,
    val subtitle: String,
    val color: Int,
    val urls: List<String>,
)

data class TvChannel(
    val id: String,
    val name: String,
    val color: Int,
    val urls: List<String>,
)

data class VodSite(
    val id: String,
    val name: String,
    val subtitle: String,
    val color: Int,
    val url: String,
)

object Stations {
    private const val SG = "https://gb25.streamgates.net/radios-audio"

    private fun digital(id: String, name: String, sub: String, color: Long, path: String) =
        Station(
            id, name, sub, color.toInt(),
            listOf("$SG/$path/playlist.m3u8", "$SG/$path/icecast.audio"),
        )

    val main = listOf(
        Station(
            "glglz", "גלגלצ", "91.8FM", 0xFF0EA5E9.toInt(),
            listOf(
                "https://cdna.streamgates.net/Radios_Radio_App/Glglz/playlist.m3u8",
                "https://glzwizzlv.bynetcdn.com/glglz_mp3",
                "http://glzwizzlv.bynetcdn.com/glglz_mp3",
            ),
        ),
        Station(
            "eco99", "אקו 99FM", "99FM", 0xFF16A34A.toInt(),
            listOf(
                "https://cdna.streamgates.net/Radios_Radio_App/Eco99FM/playlist.m3u8",
            ),
        ),
        Station(
            "100fm", "רדיוס 100FM", "100FM", 0xFFEAB308.toInt(),
            listOf(
                "https://cdn.cybercdn.live/Radios_100FM/Audio/playlist.m3u8",
                "https://cdn.cybercdn.live/Radios_100FM/Audio/icecast.audio",
                "https://cdna.streamgates.net/Radios_Radio_App/Radios100FM/playlist.m3u8",
            ),
        ),
    )

    val genres = listOf(
        digital("dance", "דאנס", "100% Dance", 0xFFDB2777, "100Dance"),
        digital("top40", "טופ 40", "100% Top40", 0xFFDC2626, "100Top40"),
        digital("hits", "להיטים", "100% Hits", 0xFFEA580C, "100Hits"),
        digital("club", "קלאב", "100% Club", 0xFF7C3AED, "100Club"),
        digital("trance", "טראנס", "100% Trance", 0xFF4F46E5, "100Trance"),
        digital("deep", "דיפ / טכנו", "100% Deep", 0xFF0F766E, "100Deep"),
        digital("djset", "סטים של DJ", "100% DJSet", 0xFF9333EA, "100Djset"),
        digital("mizrachit", "ים תיכוני", "100% Mizrachit", 0xFFB45309, "100Mizrachit"),
        digital("hot", "ים תיכוני לוהט", "91FM Hot", 0xFFC2410C, "91Hot"),
        digital("israeli", "ישראלי", "100% Israeli", 0xFF2563EB, "100Israeli"),
    )

    val tv = listOf(
        TvChannel(
            "i24", "i24NEWS", 0xFF1D4ED8.toInt(),
            listOf("https://i24newshebrew-cdn.encoders.immergo.tv/master.m3u8"),
        ),
        TvChannel(
            "ch14", "ערוץ 14", 0xFFB91C1C.toInt(),
            listOf("https://r.il.cdn-redge.media/livehls/oil/ch14/live/ch14/live.livx/playlist.m3u8"),
        ),
    )

    /** VOD חוקי וחינמי + YouTube — נפתחים בדפדפן פנימי (נשאר במצב קיוסק). */
    val vod = listOf(
        VodSite("youtube", "YouTube", "סרטונים, קליפים, טריילרים", 0xFFDC2626.toInt(), "https://m.youtube.com/"),
        VodSite("kanbox", "כאן BOX", "סדרות ותוכניות של כאן 11", 0xFF0EA5E9.toInt(), "https://www.kan.org.il/lobby/kan-box/"),
        VodSite("mako", "mako VOD", "תוכניות קשת 12", 0xFF7C3AED.toInt(), "https://www.mako.co.il/mako-vod-index"),
        VodSite("reshet13", "13 VOD", "תוכניות רשת 13", 0xFFEA580C.toInt(), "https://13tv.co.il/allshows/"),
    )

    val all: List<Station> get() = main + genres

    fun vodById(id: String?): VodSite? = vod.firstOrNull { it.id == id }

    fun byId(id: String?): Station? = all.firstOrNull { it.id == id }
    fun tvById(id: String?): TvChannel? = tv.firstOrNull { it.id == id }
}

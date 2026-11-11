package com.ghar.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free internet radio on the TV, from Radio Browser (radio-browser.info), a free community
 * directory of public radio streams — no key, no account. Same lists as Bcash Player.
 */
object TvRadio {
    data class Station(
        val uuid: String, val name: String, val url: String,
        val country: String, val state: String, val tags: String,
        val codec: String, val bitrate: Int, val votes: Int,
    ) {
        val place get() = listOf(state, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
        val quality get() = listOf(codec.uppercase(), if (bitrate > 0) "$bitrate kbps" else "").filter { it.isNotBlank() }.joinToString(" · ")

        fun toJson(): JSONObject = JSONObject()
            .put("stationuuid", uuid).put("name", name).put("url_resolved", url).put("country", country)
            .put("state", state).put("tags", tags).put("codec", codec).put("bitrate", bitrate).put("votes", votes)

        companion object {
            fun fromJson(o: JSONObject): Station? {
                val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
                val name = o.optString("name").trim()
                if (name.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) return null
                return Station(
                    o.optString("stationuuid"), name, url, o.optString("country"), o.optString("state").trim(),
                    o.optString("tags"), o.optString("codec"), o.optInt("bitrate"), o.optInt("votes"),
                )
            }
        }
    }

    enum class Category {
        FOR_YOU, SAVED, NEPAL, HINDI, DEVOTIONAL, WORLD;

        val label: String get() = when (this) {
            FOR_YOU -> t("तपाईंका लागि", "For you")
            SAVED -> t("♥ मनपर्ने", "♥ My favourites")
            NEPAL -> t("नेपाल", "Nepal")
            HINDI -> t("हिन्दी", "Hindi")
            DEVOTIONAL -> t("भजन", "Bhajan")
            WORLD -> t("विश्व", "World")
        }
    }

    // ---------------- language (Nepali by default) ----------------

    @Volatile var nepali = true
        private set

    fun loadLang(c: Context) { nepali = prefs(c).getBoolean("nepali", true) }
    fun setNepali(c: Context, v: Boolean) { nepali = v; prefs(c).edit().putBoolean("nepali", v).apply() }

    /** Nepali or English text, by the chosen language. */
    fun t(ne: String, en: String) = if (nepali) ne else en

    /** Numbers in Devanagari digits when Nepali is chosen. */
    fun num(n: Int): String = if (!nepali) n.toString() else n.toString().map { if (it in '0'..'9') '०' + (it - '0') else it }.joinToString("")

    // ---------------- last played station (played again when the TV app starts) ----------------

    fun saveLast(c: Context, list: List<Station>, index: Int, from: String) {
        val o = JSONObject().put("from", from).put("index", index)
            .put("stations", JSONArray().apply { list.take(300).forEach { put(it.toJson()) } })
        prefs(c).edit().putString("last", o.toString()).apply()
    }

    fun saveLastIndex(c: Context, index: Int) {
        val o = runCatching { JSONObject(prefs(c).getString("last", "") ?: "") }.getOrNull() ?: return
        prefs(c).edit().putString("last", o.put("index", index).toString()).apply()
    }

    data class Last(val stations: List<Station>, val index: Int, val from: String)

    fun last(c: Context): Last? = runCatching {
        val o = JSONObject(prefs(c).getString("last", "") ?: "")
        val arr = o.getJSONArray("stations")
        val list = (0 until arr.length()).mapNotNull { Station.fromJson(arr.getJSONObject(it)) }
        if (list.isEmpty()) null else Last(list, o.optInt("index", 0).coerceIn(0, list.size - 1), o.optString("from"))
    }.getOrNull()

    /** A station rebuilt from what the TV is playing (for adding it to favourites). */
    fun stationOf(item: Item) = Station(
        uuid = "", name = item.title, url = item.stream,
        country = item.artist.substringAfterLast(", ").trim(),
        state = if (item.artist.contains(", ")) item.artist.substringBeforeLast(", ") else "",
        tags = "", codec = item.album.substringBefore(" · ").takeIf { !it.contains("kbps") } ?: "",
        bitrate = Regex("(\\d+) kbps").find(item.album)?.groupValues?.get(1)?.toIntOrNull() ?: 0, votes = 0,
    )

    private val servers = listOf(
        "https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info",
        "https://fi1.api.radio-browser.info", "https://nl1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )
    @Volatile private var good = 0

    private fun get(path: String): String {
        var last: Exception? = null
        for (k in servers.indices) {
            val i = (good + k) % servers.size
            try {
                val c = URL(servers[i] + path).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 7000; c.readTimeout = 12000
                    c.setRequestProperty("User-Agent", "GharPlayer/1.0")
                    if (c.responseCode !in 200..299) throw IllegalStateException("answered ${c.responseCode}")
                    val t = c.inputStream.bufferedReader().use { it.readText() }
                    good = i
                    return t
                } finally { c.disconnect() }
            } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("Couldn't reach the radio directory. Check the TV's internet.", last)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun search(q: String, limit: Int): List<Station> {
        val arr = JSONArray(get("/json/stations/search?hidebroken=true&order=votes&reverse=true&limit=$limit&$q"))
        return (0 until arr.length()).mapNotNull { Station.fromJson(arr.getJSONObject(it)) }
    }

    private val cache = HashMap<Category, List<Station>>()

    /** Call off the main thread. */
    fun load(c: Context, cat: Category): List<Station> {
        if (cat == Category.SAVED) return saved(c)
        synchronized(cache) { cache[cat] }?.let { return it }
        val list = when (cat) {
            Category.FOR_YOU -> forYou()
            Category.NEPAL -> search("countrycode=NP", 300)
            Category.HINDI -> search("language=hindi", 200)
            Category.WORLD -> search("", 200)
            Category.DEVOTIONAL -> listOf("bhajan", "devotional", "spiritual", "mantra", "kirtan", "hindu", "buddhist", "meditation")
                .flatMap { runCatching { search("tag=${enc(it)}", 60) }.getOrDefault(emptyList()) }
                .distinctBy { it.uuid }.sortedByDescending { it.votes }
            Category.SAVED -> emptyList()
        }.distinctBy { it.url }
        synchronized(cache) { cache[cat] = list }
        return list
    }

    /**
     * Picked for a listener in Janakpur who prefers Maithili, Nepali and Hindi: local Mithila /
     * Madhesh stations first, then well-known Nepali national FM, then popular Hindi stations.
     * Only stations that the directory reports as working are included.
     */
    private fun forYou(): List<Station> {
        fun byName(n: String, lim: Int = 10) = runCatching { search("name=${enc(n)}", lim) }.getOrDefault(emptyList())
        fun byLang(l: String, lim: Int) = runCatching { search("language=${enc(l)}", lim) }.getOrDefault(emptyList())
        val local = listOf("janakpur", "mithila", "maithili", "madhesh", "dhanusha").flatMap { byName(it) } + byLang("maithili", 20)
        val nepali = listOf("radio nepal", "kantipur", "ujyaalo", "image fm", "hits fm", "radio sagarmatha", "nepali").flatMap { byName(it, 6) }
            .filter { it.country.contains("Nepal", true) } + byLang("nepali", 25)
        val hindi = listOf("vividh bharati", "air fm gold", "mirchi", "radio city", "red fm", "bhakti").flatMap { byName(it, 5) } + byLang("hindi", 25)
        return (local + nepali + hindi).distinctBy { it.url }.take(120)
    }

    fun countClick(uuid: String) { if (uuid.isNotEmpty()) runCatching { get("/json/url/${enc(uuid)}") } }

    private fun prefs(c: Context) = c.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun saved(c: Context): List<Station> = runCatching {
        val arr = JSONArray(prefs(c).getString("saved", "[]"))
        (0 until arr.length()).mapNotNull { Station.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun isSaved(c: Context, url: String) = saved(c).any { it.url == url }

    fun toggleSaved(c: Context, s: Station): Boolean {
        val list = saved(c).toMutableList()
        val had = list.removeAll { it.url == s.url }
        if (!had) list.add(0, s)
        prefs(c).edit().putString("saved", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
        return !had
    }

    /** FM frequency written in a station name ("Kantipur FM 96.1"), if any. */
    fun fmOf(name: String): Float? =
        Regex("(\\d{2,3}(?:\\.\\d)?)").findAll(name).mapNotNull { it.value.toFloatOrNull() }.firstOrNull { it in 87.5f..108f }
}

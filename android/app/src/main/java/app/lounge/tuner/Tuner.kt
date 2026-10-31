package app.lounge.tuner

import android.content.Context
import android.media.AudioManager
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Log
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.withLock

const val TAG = "Tuner"

// Saved data is shown at once and refreshed in the background once it is older than these (seconds)
private const val LIBRARY_TTL = 6 * 3600.0
private const val EPG_TTL = 6 * 3600.0
private const val ART_TTL = 30 * 86400.0
private const val MISS_TTL = 86400.0
private const val SERIES_TTL = 86400.0
private const val WALL_TTL = 86400.0

private val HEADERS = mapOf(
    "Content-Security-Policy" to "default-src 'self'; img-src * data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY",
    "Referrer-Policy" to "no-referrer",
)

fun now() = System.currentTimeMillis() / 1000.0

private val BACKGROUND = Executors.newCachedThreadPool { r -> Thread(r, "tuner-background").apply { isDaemon = true } }
private val SCHEDULER = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tuner-timer").apply { isDaemon = true } }

fun writeText(file: File, text: String) {
    val tmp = File("${file.path}.${Thread.currentThread().id}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) throw IOException("could not save ${file.name}")
}

fun hex(bytes: Int) = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

class Source(val id: String, val name: String, val type: String, val url: String, val user: String, val pass: String) {
    val base get() = url.trimEnd('/')

    companion object {
        fun of(o: JSONObject) = Source(o.s("id"), o.s("name"), o.s("type"), o.s("url"), o.s("user"), o.s("pass"))
    }
}

/** Settings, sources, favourites and resume points, saved to state.json in the app's private storage. */
object State {
    private lateinit var file: File
    private lateinit var data: JSONObject

    fun load(dir: File) {
        file = File(dir, "state.json")
        data = try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            JSONObject()
        }
        if (data.optJSONArray("sources") == null) data.put("sources", JSONArray())
        for (k in listOf("fav", "progress")) if (data.optJSONObject(k) == null) data.put(k, JSONObject())
        if (!data.has("tmdb")) data.put("tmdb", "")
    }

    fun <T> read(block: (JSONObject) -> T): T = synchronized(this) { block(data) }

    fun edit(block: (JSONObject) -> Unit) = synchronized(this) {
        block(data)
        writeText(file, data.toString())
    }
}

/** Small lookups saved to one JSON file. Writes are batched so a burst of lookups saves once. */
class Store(private val file: File) {
    private var data = try {
        JSONObject(file.readText())
    } catch (_: Exception) {
        JSONObject()
    }
    private var timer = false

    @Synchronized
    fun get(key: String, ttl: Double): JSONObject? {
        val hit = data.optJSONObject(key) ?: return null
        return if (now() - hit.optDouble("t") < ttl) hit else null
    }

    @Synchronized
    fun put(key: String, value: Any) {
        data.put(key, JSONObject().put("t", now()).put("v", value))
        if (!timer) {
            timer = true
            SCHEDULER.schedule(::flush, 15, TimeUnit.SECONDS)
        }
    }

    fun clear() {
        synchronized(this) { data = JSONObject() }
        flush()
    }

    private fun flush() {
        val text = synchronized(this) {
            timer = false
            // Old entries are dropped so the file does not grow forever
            val now = now()
            for (k in data.keys().asSequence().toList()) {
                if (now - (data.optJSONObject(k)?.optDouble("t") ?: 0.0) >= 2 * ART_TTL) data.remove(k)
            }
            data.toString()
        }
        try {
            writeText(file, text)
        } catch (e: IOException) {
            Log.w(TAG, "could not save ${file.name}", e)
        }
    }
}

object Net {
    /** Opens an address and follows redirects by hand, since HttpURLConnection will not go from http to https. */
    fun open(url: String, timeoutSeconds: Int): HttpURLConnection {
        var next = URL(url)
        repeat(6) {
            val c = next.openConnection() as HttpURLConnection
            c.connectTimeout = timeoutSeconds * 1000
            c.readTimeout = timeoutSeconds * 1000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Lounge/0.1")
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val location = c.getHeaderField("Location") ?: throw IOException("redirect without an address")
                c.disconnect()
                next = URL(next, location)
            } else {
                if (code >= 400) {
                    c.disconnect()
                    throw IOException("HTTP $code")
                }
                return c
            }
        }
        throw IOException("too many redirects")
    }

    fun fetch(url: String, timeoutSeconds: Int = 40) = open(url, timeoutSeconds).inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
}

/** The event stream the TV interface listens on, for remote presses and "the guide is ready" notices. */
object Events {
    private val subs = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()

    fun broadcast(cmd: String) = subs.forEach { it.put(cmd) }

    fun stream(out: OutputStream) {
        val q = LinkedBlockingQueue<String>()
        subs.add(q)
        try {
            while (true) {
                val cmd = q.poll(20, TimeUnit.SECONDS)
                out.write((if (cmd != null) "data: $cmd\n\n" else ": ping\n\n").toByteArray())
                out.flush()
            }
        } catch (_: IOException) {
        } finally {
            subs.remove(q)
        }
    }
}

/** How far each source's library is, for Settings and the loading screens: a big provider takes minutes. */
object Progress {
    private val status = ConcurrentHashMap<String, JSONObject>()
    private var last = 0L

    fun step(sid: String, step: String, n: Int = 0) {
        // Counting goes up thousands of times a second; the page only needs a fresh number now and then
        if (n > 0 && System.currentTimeMillis() - last < 250) return
        last = System.currentTimeMillis()
        status[sid] = JSONObject().put("state", "loading").put("step", step).put("n", n)
    }

    fun ready(sid: String, counts: JSONObject) {
        status[sid] = JSONObject().put("state", "ready").put("counts", counts)
    }

    fun failed(sid: String, e: Exception) {
        Log.w(TAG, "library failed: ${e.javaClass.simpleName}")
        // A reason the viewer can act on. Exception messages are not shown, since they can hold the login.
        val error = when {
            e is java.net.UnknownHostException -> "notfound"
            e is java.net.SocketTimeoutException -> "timeout"
            e is java.net.ConnectException -> "refused"
            e is IOException && e.message.orEmpty().startsWith("HTTP ") -> "http:" + e.message!!.substring(5)
            e is org.json.JSONException || e is IllegalStateException || e is java.io.EOFException -> "badreply"
            else -> "other"
        }
        status[sid] = JSONObject().put("state", "failed").put("error", error)
    }

    fun forget(sid: String) = status.remove(sid)

    fun all() = JSONObject(status.toMap())
}

/** What server.py does for Tuner, served to the WebView on 127.0.0.1. */
object Tuner {
    lateinit var app: Context
    private lateinit var cacheDir: File
    private lateinit var artStore: Store
    private lateinit var seriesStore: Store
    // A secret cookie, so other apps on the device cannot use the local server
    private var token = ""
    var remote = ""
        private set
    var port = 0
        private set

    @Synchronized
    fun start(context: Context): Pair<Int, String> {
        if (port != 0) return port to token
        app = context.applicationContext
        State.load(app.filesDir)
        cacheDir = File(app.filesDir, "cache").apply { mkdirs() }
        artStore = Store(File(cacheDir, "art.json"))
        seriesStore = Store(File(cacheDir, "series.json"))
        token = hex(16)
        port = HttpServer(InetAddress.getByName("127.0.0.1"), 0, 1 shl 20, ::handle).start().port
        remote = Remote.start()
        return port to token
    }

    private class Hit(val t: Double, val v: Any)

    /** A kind of data that is downloaded per source and saved to disk: the library and the guide. save writes it out
     *  and returns what is kept in memory; open reads that back from disk. */
    private abstract class Kind(val name: String, val ttl: Double) {
        abstract fun load(sid: String): Any
        abstract fun save(sid: String, t: Double, value: Any): Any
        abstract fun open(sid: String): Hit?
    }

    // A big provider has well over 100,000 titles, more than a TV box can parse in reasonable time or hold in memory
    // next to the WebView. So the library is never kept in memory: each list is written out once, already in the
    // compressed form the TV interface downloads, with the stream addresses in a separate file looked up on Play.
    private val LIBRARY = object : Kind("library", LIBRARY_TTL) {
        override fun load(sid: String) = loadLibrary(sid)
        override fun save(sid: String, t: Double, value: Any) = saveShelf(sid, t, value as Library)
        override fun open(sid: String): Hit? {
            val meta = shelfFile(sid, "meta")
            if (!meta.exists()) {
                // The library cache of earlier versions is converted once, so it does not have to be downloaded again
                val legacy = File(cacheDir, "library-$sid.json")
                if (!legacy.exists()) return null
                try {
                    legacy.bufferedReader().use { Hit(it.readLine().toDouble(), Library.read(JsonReader(it))) }
                        .let { saveShelf(sid, it.t, it.v as Library) }
                } finally {
                    legacy.delete()
                }
            }
            val lines = meta.readLines()
            lines.getOrNull(2)?.let { Progress.ready(sid, JSONObject(it)) }
            return Hit(lines[0].toDouble(), Shelf(sid, lines.getOrElse(1) { "" }))
        }
    }

    private val EPG = object : Kind("epg", EPG_TTL) {
        override fun load(sid: String) = loadEpg(sid)
        // The first line holds when it was downloaded, the rest is the guide itself
        override fun save(sid: String, t: Double, value: Any) = value.also { writeText(File(cacheDir, "epg-$sid.json"), "$t\n$value") }
        override fun open(sid: String) = File(cacheDir, "epg-$sid.json").bufferedReader().use {
            Hit(it.readLine().toDouble(), JSONObject(it.readText()))
        }
    }

    private val DATA = ConcurrentHashMap<String, Hit>()
    private val LOCKS = ConcurrentHashMap<String, ReentrantLock>()

    private fun keep(kind: Kind, sid: String, value: Any): Hit {
        val t = now()
        return Hit(t, kind.save(sid, t, value)).also { DATA["${kind.name}|$sid"] = it }
    }

    private fun readDisk(kind: Kind, sid: String): Hit? = try {
        kind.open(sid)?.also { DATA["${kind.name}|$sid"] = it }
    } catch (e: Exception) {
        if (e !is java.io.FileNotFoundException) Log.w(TAG, "${kind.name} cache unreadable: ${e.javaClass.simpleName}")
        null
    }

    /** Where a source's library is on disk; only the guide address is kept in memory. */
    private class Shelf(val sid: String, val epgUrl: String)

    private fun shelfFile(sid: String, part: String) = File(cacheDir, "lib-$sid.$part")

    private fun saveShelf(sid: String, t: Double, library: Library): Shelf {
        Progress.step(sid, "saving")
        for (kind in listOf("live", "movies", "series")) {
            val items = library.kind(kind)
            // Fastest compression: the TV's CPU is the bottleneck, not the few extra megabytes sent to the WebView
            val gz = File(shelfFile(sid, "$kind.gz").path + ".tmp")
            object : GZIPOutputStream(gz.outputStream().buffered(1 shl 16), 1 shl 16) {
                init { def.setLevel(Deflater.BEST_SPEED) }
            }.writer(Charsets.UTF_8).buffered(1 shl 16).use { w ->
                w.write("[")
                val sb = StringBuilder(512)
                items.forEachIndexed { n, i ->
                    sb.setLength(0)
                    if (n > 0) sb.append(',')
                    i.json(sb, LEAVE_OUT.getValue(kind))
                    w.append(sb)
                }
                w.write("]")
            }
            gz.renameTo(shelfFile(sid, "$kind.gz"))
            if (kind != "series") {
                val urls = File(shelfFile(sid, "$kind.urls").path + ".tmp")
                urls.bufferedWriter().use { w -> items.forEach { w.append(it.id).append('\t').append(it.url.orEmpty()).append('\n') } }
                urls.renameTo(shelfFile(sid, "$kind.urls"))
            }
        }
        // Written last, so a library that was cut short while saving is downloaded again
        val counts = JSONObject().put("live", library.live.size).put("movies", library.movies.size).put("series", library.series.size)
        writeText(shelfFile(sid, "meta"), "$t\n${library.epgUrl}\n$counts")
        Progress.ready(sid, counts)
        return Shelf(sid, library.epgUrl)
    }

    private fun refresh(kind: Kind, sid: String) {
        val lock = LOCKS.getOrPut("${kind.name}|$sid") { ReentrantLock() }
        if (!lock.tryLock()) return
        try {
            keep(kind, sid, kind.load(sid))
            Events.broadcast(kind.name)
        } catch (e: Exception) {
            Log.w(TAG, "${kind.name} refresh failed: ${e.javaClass.simpleName}")
        } finally {
            lock.unlock()
        }
    }

    /** Returns the saved copy at once and refreshes it in the background once it is older than its ttl. With nothing
     *  saved it downloads and waits, or with wait=false starts the download and returns null. */
    private fun cached(kind: Kind, sid: String, force: Boolean = false, wait: Boolean = true): Any? {
        val key = "${kind.name}|$sid"
        val hit = if (force) null else DATA[key] ?: readDisk(kind, sid)
        if (hit == null) {
            if (!wait) {
                BACKGROUND.execute { refresh(kind, sid) }
                return null
            }
            return LOCKS.getOrPut(key) { ReentrantLock() }.withLock {
                (if (force) null else DATA[key]) ?: keep(kind, sid, kind.load(sid))
            }.v
        }
        if (now() - hit.t > kind.ttl) BACKGROUND.execute { refresh(kind, sid) }
        return hit.v
    }

    private fun forget(sid: String) {
        for (kind in listOf(LIBRARY, EPG)) DATA.remove("${kind.name}|$sid")
        Progress.forget(sid)
        cacheDir.listFiles()?.filter { f -> listOf("lib-$sid.", "library-$sid.", "epg-$sid.").any { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    private fun source(sid: String) = State.read { state ->
        val sources = state.getJSONArray("sources")
        Source.of((0 until sources.length()).map { sources.getJSONObject(it) }.first { it.s("id") == sid })
    }

    private val M3U_ATTRS = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun buildM3u(lines: Sequence<String>, sid: String = ""): Library {
        val live = ArrayList<Title>()
        val movies = ArrayList<Title>()
        val series = LinkedHashMap<String, Title>()
        var meta: Pair<String, Map<String, String>>? = null
        var guide = ""
        lines.forEachIndexed { n, raw ->
            val line = raw.trim()
            if (line.startsWith("#EXTM3U")) {
                val a = attrs(line)
                guide = (a["url-tvg"].orEmpty().ifEmpty { a["x-tvg-url"].orEmpty() }).split(',')[0].trim()
            } else if (line.startsWith("#EXTINF")) {
                meta = line.substringAfterLast(',') to attrs(line)
            } else if (line.isNotEmpty() && !line.startsWith("#") && meta != null) {
                val (name, a) = meta!!
                val entry = item(n.toString(), name, a["tvg-logo"].orEmpty(), a["group-title"].orEmpty(), line)
                val found = EPISODE.find(entry.name)
                val show = found?.let { clean(it.groupValues[1]) }.orEmpty()
                if (found != null && show.isNotEmpty()) {
                    val s = series.getOrPut(show) {
                        Title("m3u:$show", show, entry.lang, entry.logo, entry.group, seasons = LinkedHashMap<String, MutableList<Title>>())
                    }
                    @Suppress("UNCHECKED_CAST")
                    val seasons = s.seasons as MutableMap<String, MutableList<Title>>
                    seasons.getOrPut(found.groupValues[2].toInt().toString()) { ArrayList() }.add(entry.copy(ep = found.groupValues[3].toInt()))
                } else if ("/movie/" in line) {
                    movies.add(entry)
                    Progress.step(sid, "playlist", live.size + movies.size)
                } else {
                    val chno = a["tvg-chno"].orEmpty()
                    val num = if (chno.isNotEmpty() && chno.all { it.isDigit() }) chno.toIntOrNull() else null
                    live.add(entry.copy(num = num ?: (live.size + 1), epg = a["tvg-id"].orEmpty()))
                    Progress.step(sid, "playlist", live.size + movies.size)
                }
                meta = null
            }
        }
        return Library(
            bareLangs(live), years(bareLangs(movies)), years(bareLangs(ArrayList(series.values))),
            if (guide.startsWith("http://") || guide.startsWith("https://")) guide else "",
        )
    }

    private fun attrs(line: String) = M3U_ATTRS.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

    private fun xtreamUrl(src: Source, action: String, extra: Map<String, String> = emptyMap()) =
        "${src.base}/player_api.php?username=${quote(src.user)}&password=${quote(src.pass)}&action=$action" +
            extra.entries.joinToString("") { "&${it.key}=${quote(it.value)}" }

    // Xtream lists can be tens of megabytes, so they are read one object at a time
    private fun xtreamEach(src: Source, action: String, each: (Map<String, Any?>) -> Unit) =
        Net.open(xtreamUrl(src, action), 120).inputStream.use { Json.eachObject(it, each) }

    private fun buildXtream(src: Source): Library {
        val sid = src.id
        val base = src.base
        val u = quote(src.user)
        val p = quote(src.pass)

        fun categories(action: String) = HashMap<String, String>().also { m ->
            xtreamEach(src, action) { c -> m[str(c["category_id"])] = str(c["category_name"]) }
        }

        Progress.step(sid, "categories")
        val liveCats = categories("get_live_categories")
        val vodCats = categories("get_vod_categories")
        val seriesCats = categories("get_series_categories")
        val live = ArrayList<Title>()
        xtreamEach(src, "get_live_streams") { c ->
            val id = str(c["stream_id"])
            val num = str(c["num"]).toIntOrNull()?.takeIf { it != 0 } ?: (live.size + 1)
            live.add(item(id, str(c["name"]), str(c["stream_icon"]), liveCats[str(c["category_id"])].orEmpty(),
                "$base/live/$u/$p/$id.ts", num = num, epg = str(c["epg_channel_id"])))
            Progress.step(sid, "live", live.size)
        }
        // The TMDB id, when the server has one, finds the exact film for posters and translated plots
        val movies = ArrayList<Title>()
        xtreamEach(src, "get_vod_streams") { c ->
            val id = str(c["stream_id"])
            val tmdb = str(c["tmdb"]).takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
            val ext = str(c["container_extension"]).ifEmpty { "mp4" }
            movies.add(item(id, str(c["name"]), str(c["stream_icon"]), vodCats[str(c["category_id"])].orEmpty(),
                "$base/movie/$u/$p/$id.$ext", tmdb = tmdb))
            Progress.step(sid, "movies", movies.size)
        }
        val series = ArrayList<Title>()
        xtreamEach(src, "get_series") { c ->
            series.add(item(str(c["series_id"]), str(c["name"]), str(c["cover"]), seriesCats[str(c["category_id"])].orEmpty(),
                null, plot = str(c["plot"])))
            Progress.step(sid, "series", series.size)
        }
        return Library(bareLangs(live), years(bareLangs(movies)), years(bareLangs(series)))
    }

    private fun seriesInfo(sid: String, seriesId: String): JSONObject {
        val key = "$sid|$seriesId"
        seriesStore.get(key, SERIES_TTL)?.let { return it.getJSONObject("v") }
        return loadSeries(sid, seriesId).also { seriesStore.put(key, it) }
    }

    private fun loadSeries(sid: String, seriesId: String): JSONObject {
        val src = source(sid)
        val base = src.base
        val u = quote(src.user)
        val p = quote(src.pass)
        val data = JSONObject(Net.fetch(xtreamUrl(src, "get_series_info", mapOf("series_id" to seriesId)), 120))
        // Most servers send the seasons as an object, some as a list
        val episodes = when (val raw = data.opt("episodes")) {
            is JSONObject -> raw
            is JSONArray -> JSONObject().also { e -> for (i in 0 until raw.length()) e.put((i + 1).toString(), raw.get(i)) }
            else -> JSONObject()
        }
        val seasons = JSONObject()
        for (number in episodes.keys()) {
            val eps = episodes.optJSONArray(number) ?: continue
            val out = JSONArray()
            for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                out.put(JSONObject()
                    .put("id", e.s("id"))
                    .put("ep", e.s("episode_num").toIntOrNull() ?: 0)
                    .put("name", clean(e.s("title")).ifEmpty { "Episode " + e.s("episode_num").ifEmpty { "None" } })
                    .put("logo", e.optJSONObject("info")?.s("movie_image").orEmpty())
                    .put("url", "$base/series/$u/$p/${e.s("id")}.${e.s("container_extension").ifEmpty { "mkv" }}"))
            }
            seasons.put(number, out)
        }
        return JSONObject().put("seasons", seasons).put("plot", data.optJSONObject("info")?.s("plot").orEmpty())
    }

    private fun loadLibrary(sid: String): Library = try {
        val src = source(sid)
        Progress.step(sid, "connecting")
        if (src.type == "xtream") buildXtream(src)
        else Net.open(src.url, 180).inputStream.bufferedReader().useLines { buildM3u(it, sid) }
    } catch (e: Exception) {
        Progress.failed(sid, e)
        throw e
    }

    private fun library(sid: String, force: Boolean = false) = cached(LIBRARY, sid, force) as Shelf

    // What Tuner receives per list: no stream addresses, which hold the login and are looked up on Play, and no series
    // plots, which the series page loads itself.
    private val LEAVE_OUT = mapOf("live" to setOf("url"), "movies" to setOf("url"), "series" to setOf("plot"))

    private fun libraryList(sid: String, kind: String, force: Boolean): File {
        require(kind in LEAVE_OUT) { "unknown kind" }
        return shelfFile(library(sid, force).sid, "$kind.gz")
    }

    /** Episodes send their address; movies and channels are looked up by id in the library's address list. */
    private fun streamUrl(body: JSONObject): String {
        if (body.s("url").isNotEmpty()) return body.s("url")
        val kind = body.s("kind")
        require(kind == "live" || kind == "movies") { "unknown kind" }
        val prefix = body.s("id") + "\t"
        return shelfFile(library(body.s("src")).sid, "$kind.urls").useLines { lines ->
            lines.first { it.startsWith(prefix) }.substring(prefix.length)
        }
    }

    /** The guide ids of the live channels, read from the list the TV interface gets. */
    private fun epgIds(sid: String): Set<String> {
        val out = HashSet<String>()
        GZIPInputStream(libraryList(sid, "live", false).inputStream().buffered(1 shl 16)).reader().use { input ->
            val r = JsonReader(input)
            r.beginArray()
            while (r.hasNext()) {
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "epg" && r.peek() == android.util.JsonToken.STRING) r.nextString().takeIf { it.isNotEmpty() }?.let(out::add)
                    else r.skipValue()
                }
                r.endObject()
            }
        }
        return out
    }

    private val BACKDROP = Regex("https?://[^\\s'\"\\],]+")
    private val DURATION = Regex("^(\\d+):(\\d+)")

    /** What a movie is about, for its page before it plays. Only Xtream servers have this; M3U playlists do not. */
    private fun movieInfo(sid: String, movieId: String): JSONObject {
        val src = source(sid)
        if (src.type != "xtream") return JSONObject()
        // Some servers now and then answer with empty details, so they are asked a second time
        var info = JSONObject()
        for (attempt in 0 until 2) {
            info = JSONObject(Net.fetch(xtreamUrl(src, "get_vod_info", mapOf("vod_id" to movieId)), 120)).optJSONObject("info") ?: JSONObject()
            if (info.length() > 0) break
        }
        // Some servers send the backdrop as a list, or as a list written out as text
        val b = info.opt("backdrop_path")
        val backdrop = when (b) {
            is JSONArray -> (0 until b.length()).joinToString(" ") { b.opt(it).toString() }
            is String -> b
            else -> ""
        }
        val date = info.s("releasedate")
        // "01:50:00" reads as 1 h 50 min; servers that do not know the length send zeros
        val found = DURATION.find(info.s("duration"))
        val h = found?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = found?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val length = listOfNotNull(if (h != 0) "$h h" else null, if (m != 0) "$m min" else null).joinToString(" ")
        return JSONObject()
            .put("plot", info.s("plot").ifEmpty { info.s("description") })
            .put("year", if (date.length >= 4 && date.take(4).all { it.isDigit() }) date.take(4).toInt() else 0)
            .put("genre", info.s("genre"))
            .put("duration", length)
            .put("cast", info.s("cast").ifEmpty { info.s("actors") })
            .put("backdrop", BACKDROP.find(backdrop)?.value.orEmpty())
            .put("tmdb", info.s("tmdb_id"))
    }

    private val ZONE = Regex("([+-])(\\d\\d)(\\d\\d)\\s*$")

    /** XMLTV times look like 20261020203000 +0200. */
    fun xmltvTime(text: String): Long {
        val d = text.take(14)
        require(d.length == 14 && d.all { it.isDigit() }) { "not a time" }
        val c = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        c.isLenient = false
        c.clear()
        c.set(d.substring(0, 4).toInt(), d.substring(4, 6).toInt() - 1, d.substring(6, 8).toInt(),
            d.substring(8, 10).toInt(), d.substring(10, 12).toInt(), d.substring(12, 14).toInt())
        val stamp = c.timeInMillis / 1000
        val zone = ZONE.find(text) ?: return stamp
        val offset = zone.groupValues[2].toInt() * 3600 + zone.groupValues[3].toInt() * 60
        return stamp - if (zone.groupValues[1] == "+") offset else -offset
    }

    private fun loadEpg(sid: String): JSONObject {
        val src = source(sid)
        val url = if (src.type == "xtream") "${src.base}/xmltv.php?username=${quote(src.user)}&password=${quote(src.pass)}"
        else library(sid).epgUrl
        if (url.isEmpty()) return JSONObject()
        val wanted = epgIds(sid)
        val now = now()
        val guide = HashMap<String, MutableList<Triple<Long, Long, String>>>()
        // Guides can be hundreds of megabytes, so they are read as a stream and only the next 12 hours are kept
        Net.open(url, 300).inputStream.use { raw ->
            var stream: java.io.InputStream = BufferedInputStream(raw, 1 shl 16)
            stream.mark(2)
            val magic = stream.read()
            stream.read()
            stream.reset()
            if (magic == 0x1f) stream = GZIPInputStream(stream, 1 shl 16)
            val p = Xml.newPullParser()
            p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
            p.setInput(stream, null)
            var channel: String? = null
            var start = 0L
            var stop = 0L
            var title: String? = null
            var inTitle = false
            while (true) {
                when (p.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> when (p.name) {
                        "programme" -> {
                            channel = p.getAttributeValue(null, "channel")?.takeIf { it in wanted }
                            if (channel != null) {
                                try {
                                    start = xmltvTime(p.getAttributeValue(null, "start").orEmpty())
                                    stop = xmltvTime(p.getAttributeValue(null, "stop").orEmpty())
                                } catch (_: IllegalArgumentException) {
                                    start = 0
                                    stop = 0
                                }
                            }
                            title = null
                        }
                        "title" -> inTitle = channel != null && title == null
                    }
                    XmlPullParser.TEXT -> if (inTitle) title = (title ?: "") + p.text
                    XmlPullParser.END_TAG -> when (p.name) {
                        "title" -> inTitle = false
                        "programme" -> {
                            val ch = channel
                            if (ch != null && stop > now && start < now + 12 * 3600) {
                                guide.getOrPut(ch) { ArrayList() }.add(Triple(start, stop, clean(title)))
                            }
                            channel = null
                        }
                    }
                }
            }
        }
        val out = JSONObject()
        for ((ch, shows) in guide) {
            out.put(ch, JSONArray(shows.sortedWith(compareBy({ it.first }, { it.second }, { it.third })).take(6)
                .map { JSONArray().put(it.first).put(it.second).put(it.third) }))
        }
        return out
    }

    private fun epg(sid: String) = cached(EPG, sid, wait = false) as JSONObject?

    private const val TMDB = "https://api.themoviedb.org/3"

    /** Poster, backdrop and plot. lang asks TMDB for the plot in that language; tmdbId, which Xtream servers give for
     *  their movies, finds the exact film instead of searching by name. */
    private fun art(title: String, kind: String, year: String, lang: String, tmdbId: String): JSONObject {
        val key = "$kind|$title|$year|$lang|$tmdbId"
        artStore.get(key, ART_TTL)?.let { hit ->
            val v = hit.getJSONObject("v")
            if (v.length() > 0 || now() - hit.optDouble("t") < MISS_TTL) return v
        }
        val apiKey = State.read { it.s("tmdb") }
        val result = JSONObject()
        try {
            if (apiKey.isNotEmpty()) {
                val path = if (kind == "tv") "tv" else "movie"
                val auth = "api_key=${quote(apiKey)}&language=${quote(lang.ifEmpty { "en" })}"
                val hit = if (tmdbId.isNotEmpty() && tmdbId.all { it.isDigit() }) {
                    JSONObject(Net.fetch("$TMDB/$path/$tmdbId?$auth"))
                } else {
                    val hits = JSONObject(Net.fetch("$TMDB/search/$path?$auth&query=${quote(title)}")).getJSONArray("results")
                    val date = if (kind == "tv") "first_air_date" else "release_date"
                    val all = (0 until hits.length()).map { hits.getJSONObject(it) }
                    val best = all.firstOrNull { year.isNotEmpty() && it.s(date).startsWith(year) } ?: all.first()
                    // Search results have no genre names, so the match is looked up once more in full
                    JSONObject(Net.fetch("$TMDB/$path/${best.s("id")}?$auth"))
                }
                if (hit.s("overview").isEmpty() && lang !in setOf("", "en")) {
                    // No translation of the plot: the English one is better than none
                    val english = JSONObject(Net.fetch("$TMDB/$path/${hit.s("id")}?api_key=${quote(apiKey)}&language=en"))
                    hit.put("overview", english.s("overview"))
                }
                val genres = hit.optJSONArray("genres") ?: JSONArray()
                result.put("poster", hit.s("poster_path").let { if (it.isEmpty()) "" else "https://image.tmdb.org/t/p/w500$it" })
                    .put("backdrop", hit.s("backdrop_path").let { if (it.isEmpty()) "" else "https://image.tmdb.org/t/p/w1280$it" })
                    // Empty when TMDB has no translation in that language, so the page falls back to the provider's plot
                    .put("plot", hit.s("overview"))
                    .put("genre", (0 until genres.length()).joinToString(", ") { genres.getJSONObject(it).s("name") })
            } else if (kind == "tv") {
                val hit = JSONObject(Net.fetch("https://api.tvmaze.com/singlesearch/shows?q=" + quote(title)))
                result.put("poster", hit.optJSONObject("image")?.s("original").orEmpty()).put("backdrop", "")
                    .put("plot", hit.s("summary").replace(Regex("<[^>]+>"), ""))
            }
        } catch (e: Exception) {
            // Network trouble is not saved, so the next request tries again
            Log.w(TAG, "artwork lookup failed: ${e.javaClass.simpleName}")
            return result
        }
        artStore.put(key, result)
        return result
    }

    private fun wallpapers(): JSONArray {
        val apiKey = State.read { it.s("tmdb") }
        if (apiKey.isEmpty()) return JSONArray()
        artStore.get("wall", WALL_TTL)?.let { return it.getJSONArray("v") }
        val results = JSONObject(Net.fetch("$TMDB/trending/all/week?api_key=${quote(apiKey)}")).getJSONArray("results")
        val wall = JSONArray((0 until results.length()).map { results.getJSONObject(it).s("backdrop_path") }
            .filter { it.isNotEmpty() }.take(12).map { "https://image.tmdb.org/t/p/w1280$it" })
        artStore.put("wall", wall)
        return wall
    }

    private fun publicState() = State.read { state ->
        val all = state.getJSONArray("sources")
        val sources = JSONArray()
        for (i in 0 until all.length()) {
            val s = all.getJSONObject(i)
            sources.put(JSONObject().put("id", s.s("id")).put("name", s.s("name")).put("type", s.s("type")))
        }
        JSONObject().put("sources", sources).put("fav", state.getJSONObject("fav")).put("progress", state.getJSONObject("progress"))
            .put("tmdb", state.s("tmdb").isNotEmpty()).put("remote", remote).put("playing", Playback.playing())
            .put("recent", state.optJSONArray("recent") ?: JSONArray()).put("lang", state.s("lang")).toString()
    }

    private fun addSource(body: JSONObject): JSONObject {
        val type = body.s("type")
        val url = body.s("url")
        require(type in setOf("m3u", "xtream") && (url.startsWith("http://") || url.startsWith("https://"))) { "invalid source" }
        require(type != "xtream" || (body.s("user").isNotEmpty() && body.s("pass").isNotEmpty())) { "login required" }
        val sid = System.currentTimeMillis().toString()
        State.edit {
            it.getJSONArray("sources").put(JSONObject().put("id", sid).put("name", body.s("name").take(60)).put("type", type)
                .put("url", url.trim()).put("user", body.s("user")).put("pass", body.s("pass")))
        }
        // Starts downloading at once, so Settings can show how far it is; Progress keeps any error for it
        Progress.step(sid, "connecting")
        BACKGROUND.execute { runCatching { library(sid) } }
        return JSONObject().put("id", sid)
    }

    private fun rememberChannel(entry: JSONObject) {
        val kept = JSONObject()
        for (k in listOf("key", "id", "src", "name", "logo", "url", "group", "num", "epg")) if (entry.has(k)) kept.put(k, entry.get(k))
        State.edit { state ->
            val old = state.optJSONArray("recent") ?: JSONArray()
            val recent = JSONArray().put(kept)
            (0 until old.length()).map { old.getJSONObject(it) }.filter { it.s("key") != kept.s("key") }.take(19).forEach { recent.put(it) }
            state.put("recent", recent)
        }
    }

    private val DIGITS = (0..9).map { it.toString() }.toSet()
    val UI_KEYS = setOf("up", "down", "left", "right", "ok", "back", "home") + DIGITS
    private val PLAYER_KEYS = setOf("ok", "playpause", "left", "seek_back", "right", "seek_fwd", "up", "volup", "down", "voldown", "mute")
    private val VOLUME = mapOf("volup" to AudioManager.ADJUST_RAISE, "voldown" to AudioManager.ADJUST_LOWER, "mute" to AudioManager.ADJUST_TOGGLE_MUTE)

    fun systemVolume(cmd: String) {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, VOLUME.getValue(cmd), AudioManager.FLAG_SHOW_UI)
    }

    /** A button on the phone remote, or a key the TV interface passes on. */
    fun runCommand(cmd: String) {
        if (Playback.playing()) {
            if (cmd in setOf("back", "home", "stop")) {
                Playback.stop()
                Events.broadcast("stopped")
                if (cmd == "home") Events.broadcast("home")
            } else if (cmd in DIGITS) {
                // The TV interface collects channel numbers and switches the channel
                Events.broadcast(cmd)
            } else if (cmd in PLAYER_KEYS && Playback.started()) {
                Playback.command(cmd)
            }
        } else if (cmd in VOLUME) {
            systemVolume(cmd)
        } else if (cmd in UI_KEYS) {
            Events.broadcast(cmd)
        }
    }

    fun sendText(text: String) {
        // Line breaks would split the event stream message, so control characters become spaces
        val clean = Regex("[\\x00-\\x1f\\x7f]").replace(text, " ").take(100)
        if (!Playback.playing()) Events.broadcast("text:$clean")
    }

    fun saveProgress(key: String, meta: JSONObject, pos: Double, dur: Double) {
        if (key.isEmpty() || pos <= 0 || dur <= 300) return
        val entry = JSONObject(meta.toString()).put("pos", pos).put("dur", dur).put("ts", now())
        State.edit { it.getJSONObject("progress").put(key, entry) }
    }

    private fun json(body: Any, code: Int = 200, gzipped: Boolean = false): Response {
        val bytes = if (body is ByteArray) body else body.toString().toByteArray()
        return Response(code, "application/json", bytes, HEADERS + if (gzipped) mapOf("Content-Encoding" to "gzip") else emptyMap())
    }

    private fun error(code: Int, text: String) = json(JSONObject().put("error", text), code)

    private fun asset(name: String, type: String) = Response(200, type, app.assets.open(name).use { it.readBytes() }, HEADERS)

    private fun handle(r: Request): Response {
        // Only the WebView, which holds the cookie, gets in
        val local = r.headers["host"].orEmpty().substringBefore(':') in setOf("127.0.0.1", "localhost")
        val cookie = r.headers["cookie"].orEmpty().split(';').map { it.trim() }.any { it == "tuner=$token" }
        if (!local || !cookie) return error(403, "Forbidden")
        return try {
            if (r.method == "POST") post(r) else get(r)
        } catch (e: Exception) {
            Log.w(TAG, "request failed ${r.path}: ${e.javaClass.simpleName}")
            error(500, "Request failed")
        }
    }

    private fun get(r: Request): Response {
        val q = r.query
        return when (r.path) {
            // /tvhub is the old address of Tuner, kept so bookmarks still work
            "/tuner", "/tvhub" -> asset("index.html", "text/html; charset=utf-8")
            "/i18n.js" -> asset("i18n.js", "text/javascript; charset=utf-8")
            "/api/events" -> Response(200, "text/event-stream", headers = HEADERS + mapOf("Cache-Control" to "no-cache"), stream = Events::stream)
            "/api/state" -> json(publicState())
            "/api/status" -> json(Progress.all())
            "/api/library" -> {
                // Sent straight from disk, so a list of 100,000 titles never has to be read into memory
                val file = libraryList(q.getValue("src"), q.getValue("kind"), q["refresh"] == "1")
                val gzip = "gzip" in r.headers["accept-encoding"].orEmpty()
                Response(200, "application/json", headers = HEADERS + if (gzip) mapOf("Content-Encoding" to "gzip") else emptyMap(), stream = { out ->
                    file.inputStream().use { if (gzip) it.copyTo(out, 1 shl 16) else GZIPInputStream(it).copyTo(out, 1 shl 16) }
                })
            }
            "/api/series" -> json(seriesInfo(q.getValue("src"), q.getValue("id")))
            "/api/poster" -> {
                // Cards point their picture here when the provider has none; it sends the WebView on to the TMDB poster
                val poster = art(q.getValue("q"), q["kind"] ?: "movie", q["year"].orEmpty(), q["lang"].orEmpty(), q["tmdb"].orEmpty()).s("poster")
                if (poster.isEmpty()) error(404, "Not found")
                else Response(302, "text/plain", headers = mapOf("Location" to poster, "Cache-Control" to "max-age=86400"))
            }
            "/api/movie" -> json(movieInfo(q.getValue("src"), q.getValue("id")))
            "/api/art" -> json(art(q.getValue("q"), q["kind"] ?: "movie", q["year"].orEmpty(), q["lang"].orEmpty(), q["tmdb"].orEmpty()))
            "/api/wallpapers" -> json(wallpapers())
            "/api/epg" -> {
                val guide = epg(q.getValue("src"))
                json(JSONObject().put("ready", guide != null).put("guide", guide ?: JSONObject()))
            }
            else -> error(404, "Not found")
        }
    }

    private fun post(r: Request): Response {
        if (r.headers["x-tvhub"] != "1") return error(403, "Forbidden")
        val body = JSONObject(String(r.body, Charsets.UTF_8).ifEmpty { "{}" })
        var result = JSONObject().put("ok", true)
        when (r.path) {
            "/api/play" -> {
                val pos = body.optDouble("pos", 0.0).takeUnless { it.isNaN() } ?: 0.0
                result.put("started", Playback.play(streamUrl(body), body.s("key"), pos, body.optJSONObject("meta") ?: JSONObject()))
                body.optJSONObject("recent")?.let(::rememberChannel)
            }
            "/api/key" -> if (body.s("cmd") in UI_KEYS) runCommand(body.s("cmd"))
            "/api/stop" -> {
                Playback.stop()
                Events.broadcast("stopped")
            }
            "/api/osd" -> if (Playback.playing() && Playback.started()) Playback.osd(body.s("text").take(60))
            "/api/fav" -> State.edit { state ->
                val fav = state.getJSONObject("fav")
                val item = body.optJSONObject("item")
                if (item != null) fav.put(body.s("key"), item) else fav.remove(body.s("key"))
            }
            "/api/source" -> result = addSource(body)
            "/api/source/delete" -> {
                State.edit { state ->
                    val old = state.getJSONArray("sources")
                    state.put("sources", JSONArray((0 until old.length()).map { old.getJSONObject(it) }.filter { it.s("id") != body.s("id") }))
                }
                forget(body.s("id"))
            }
            "/api/lang" -> {
                require(Regex("[a-z]{2}").matches(body.s("lang"))) { "unknown language" }
                State.edit { it.put("lang", body.s("lang")) }
            }
            "/api/settings" -> {
                State.edit { it.put("tmdb", body.s("tmdb").trim()) }
                artStore.clear()
            }
            else -> return error(404, "Not found")
        }
        return json(result)
    }
}

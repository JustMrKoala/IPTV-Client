package app.lounge.tuner

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader

/** One channel, movie, series or episode. Kept as plain fields since a big provider has tens of thousands. */
data class Title(
    val id: String,
    var name: String,
    var lang: String,
    val logo: String,
    val group: String,
    val url: String? = null,
    val num: Int? = null,
    val epg: String? = null,
    val tmdb: String? = null,
    var year: Int? = null,
    val plot: String? = null,
    val ep: Int? = null,
    val seasons: Map<String, List<Title>>? = null,
) {
    fun write(w: JsonWriter, leaveOut: Set<String> = emptySet()) {
        w.beginObject()
        w.name("id").value(id)
        w.name("name").value(name)
        w.name("lang").value(lang)
        w.name("logo").value(logo)
        w.name("group").value(group)
        if (url != null && "url" !in leaveOut) w.name("url").value(url)
        num?.let { w.name("num").value(it.toLong()) }
        epg?.let { w.name("epg").value(it) }
        tmdb?.let { w.name("tmdb").value(it) }
        year?.let { w.name("year").value(it.toLong()) }
        if (plot != null && "plot" !in leaveOut) w.name("plot").value(plot)
        ep?.let { w.name("ep").value(it.toLong()) }
        seasons?.let { map ->
            w.name("seasons").beginObject()
            for ((n, eps) in map) {
                w.name(n).beginArray()
                eps.forEach { it.write(w) }
                w.endArray()
            }
            w.endObject()
        }
        w.endObject()
    }

    /** The same JSON as write, built by hand: JsonWriter is several times slower, which shows on a TV's CPU. */
    fun json(sb: StringBuilder, leaveOut: Set<String> = emptySet()) {
        sb.append("{\"id\":").str(id).append(",\"name\":").str(name).append(",\"lang\":").str(lang)
            .append(",\"logo\":").str(logo).append(",\"group\":").str(group)
        if (url != null && "url" !in leaveOut) sb.append(",\"url\":").str(url)
        num?.let { sb.append(",\"num\":").append(it) }
        epg?.let { sb.append(",\"epg\":").str(it) }
        tmdb?.let { sb.append(",\"tmdb\":").str(it) }
        year?.let { sb.append(",\"year\":").append(it) }
        if (plot != null && "plot" !in leaveOut) sb.append(",\"plot\":").str(plot)
        ep?.let { sb.append(",\"ep\":").append(it) }
        seasons?.let { map ->
            sb.append(",\"seasons\":{")
            var first = true
            for ((n, eps) in map) {
                if (!first) sb.append(',')
                first = false
                sb.str(n).append(":[")
                eps.forEachIndexed { k, e ->
                    if (k > 0) sb.append(',')
                    e.json(sb)
                }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append('}')
    }

    companion object {
        fun read(r: JsonReader): Title {
            val f = HashMap<String, Any?>()
            var seasons: MutableMap<String, List<Title>>? = null
            r.beginObject()
            while (r.hasNext()) {
                when (val name = r.nextName()) {
                    "num", "year", "ep" -> f[name] = r.nextInt()
                    "seasons" -> {
                        seasons = LinkedHashMap()
                        r.beginObject()
                        while (r.hasNext()) {
                            val n = r.nextName()
                            val eps = ArrayList<Title>()
                            r.beginArray()
                            while (r.hasNext()) eps.add(read(r))
                            r.endArray()
                            seasons[n] = eps
                        }
                        r.endObject()
                    }
                    else -> f[name] = r.nextString()
                }
            }
            r.endObject()
            return Title(
                f["id"] as String, f["name"] as String, (f["lang"] as String).intern(), f["logo"] as String,
                (f["group"] as String).intern(), f["url"] as String?, f["num"] as Int?, f["epg"] as String?,
                f["tmdb"] as String?, f["year"] as Int?, f["plot"] as String?, f["ep"] as Int?, seasons,
            )
        }
    }
}

class Library(val live: List<Title>, val movies: List<Title>, val series: List<Title>, val epgUrl: String = "") {
    fun kind(kind: String) = when (kind) {
        "live" -> live
        "movies" -> movies
        "series" -> series
        else -> throw IllegalArgumentException("unknown kind")
    }

    fun write(w: JsonWriter) {
        w.beginObject()
        for (kind in listOf("live", "movies", "series")) {
            w.name(kind).beginArray()
            kind(kind).forEach { it.write(w) }
            w.endArray()
        }
        w.name("epg_url").value(epgUrl)
        w.endObject()
    }

    companion object {
        fun read(r: JsonReader): Library {
            val lists = HashMap<String, List<Title>>()
            var epgUrl = ""
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                if (name == "epg_url") {
                    epgUrl = r.nextString()
                } else {
                    val items = ArrayList<Title>()
                    r.beginArray()
                    while (r.hasNext()) items.add(Title.read(r))
                    r.endArray()
                    lists[name] = items
                }
            }
            r.endObject()
            return Library(lists["live"] ?: emptyList(), lists["movies"] ?: emptyList(), lists["series"] ?: emptyList(), epgUrl)
        }
    }
}

/** A cleaned-up title: the language code and junk taken off the name, and the category tidied the same way. */
fun item(
    ident: String, name: String, logo: String, group: String, url: String?, num: Int? = null, epg: String? = null,
    tmdb: String? = null, plot: String? = null,
): Title {
    val (lang, title) = splitLang(name)
    val (groupLang, groupName) = splitLang(group)
    return Title(
        ident, clean(title), lang.ifEmpty { groupLang }.intern(), logo, clean(groupName).ifEmpty { "Other" }.intern(),
        url, num, epg, tmdb, plot = plot,
    )
}

/** Appends text as a JSON string. */
fun StringBuilder.str(text: String): StringBuilder {
    append('"')
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            // Line and paragraph separators are valid JSON but not valid JavaScript in older engines
            c < ' ' || c == ' ' || c == ' ' -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
    }
    return append('"')
}

/** A JSON value as Python's str() would print it, since Xtream servers mix numbers and strings freely. */
fun str(v: Any?): String = when (v) {
    null -> ""
    is Boolean -> if (v) "True" else "False"
    else -> v.toString()
}

fun JSONObject.s(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

/** Streaming JSON helpers, so a provider's list of 50,000 movies never sits in memory as one JSON tree. */
object Json {
    fun read(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_ARRAY -> ArrayList<Any?>().also { list ->
            r.beginArray()
            while (r.hasNext()) list.add(read(r))
            r.endArray()
        }
        JsonToken.BEGIN_OBJECT -> LinkedHashMap<String, Any?>().also { map ->
            r.beginObject()
            while (r.hasNext()) map[r.nextName()] = read(r)
            r.endObject()
        }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean()
        else -> {
            r.skipValue()
            null
        }
    }

    /** Calls each for every object in a top level array. Anything else, such as an error object, gives nothing. */
    fun eachObject(stream: InputStream, each: (Map<String, Any?>) -> Unit) {
        val r = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
        r.isLenient = true
        if (r.peek() != JsonToken.BEGIN_ARRAY) return
        r.beginArray()
        while (r.hasNext()) {
            @Suppress("UNCHECKED_CAST")
            (read(r) as? Map<String, Any?>)?.let(each)
        }
    }

    fun array(items: Iterable<Any?>) = JSONArray().also { a -> items.forEach { a.put(it) } }
}

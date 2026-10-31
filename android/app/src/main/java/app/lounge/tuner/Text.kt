package app.lounge.tuner

import java.text.Normalizer

// Title cleanup, the same rules as server.py. Python's \w also matches letters outside ASCII, so W stands in for it.
private const val W = "\\p{L}\\p{N}_"
private val PREFIX = Regex("^\\s*(?:[\\[(|]+\\s*[A-Z]{2,4}\\s*[|\\])]+|[A-Z]{2,4}\\s*\\|)\\s*")
private val TAGS = Regex("[\\[({][^\\])}]*[\\])}]")
private val JUNK = Regex("(?<![$W])(4K|UHD|FHD|HD|SD|HEVC|H\\.?26[45]|x26[45]|\\d{3,4}p|MULTI|RAW|VOSTFR)(?![$W])", RegexOption.IGNORE_CASE)
private val SYMBOLS = Regex("[^$W\\s&'.:,!?+-]")
private val DOTS = Regex("(?<=[$W])\\.(?=[$W]{2})")
private val SPACES = Regex("\\s+")
val EPISODE = Regex("^(.*?)[\\s._-]*S(\\d{1,2})\\s*E(\\d{1,3})", RegexOption.IGNORE_CASE)

// Language and country codes that providers put in front of titles, such as "NL | ", "[NL]", "NL - " or "NL: "
val LANGS = setOf(
    "NL", "BE", "VL", "EN", "UK", "GB", "US", "USA", "CA", "AU", "IE", "DE", "AT", "CH", "FR", "ES", "LAT", "IT", "PT", "BR",
    "PL", "TR", "AR", "ARA", "RU", "UA", "SE", "SW", "NO", "DK", "FI", "IS", "GR", "RO", "HU", "CZ", "SK", "HR", "RS", "SI", "BA",
    "MK", "BG", "AL", "EXYU", "IN", "PK", "IR", "KU", "KR", "JP", "CN", "PH", "TH", "VN", "ID", "IL", "MA", "EG", "ZA", "MX", "AF",
)
private val LANG = Regex("^\\s*(?:[\\[(|]+\\s*([A-Z]{2,4})\\s*[|\\])]+|([A-Z]{2,4})\\s*(?:[|:]|[-–](?=\\s)))\\s*")
private val BARE_LANG = Regex("^([A-Z]{2,4}) (?=\\S)")
// A year after the title: "Title - 2009", "Title (2009)" or "Title [2009]", but not "Blade Runner 2049" or "1881 - 1919"
private val TITLE_YEAR = Regex("^(.*?\\S)(?<!\\d{4})\\s*(?:-\\s*|[(\\[])((?:19|20)\\d\\d)[)\\]]?\\s*$")

private fun nfkc(text: String?) = Normalizer.normalize(text ?: "", Normalizer.Form.NFKC)

fun clean(text: String?): String {
    var s = nfkc(text)
    s = PREFIX.replace(s, "")
    s = TAGS.replace(s, " ")
    s = JUNK.replace(s, " ")
    s = SYMBOLS.replace(s, " ").replace('_', ' ')
    s = DOTS.replace(s, " ")
    return SPACES.replace(s, " ").trim(' ', '-', '.', ':')
}

/** Takes the language code off the front of a title and returns it separately, so it can still be filtered on. */
fun splitLang(text: String?): Pair<String, String> {
    val s = nfkc(text)
    val found = LANG.find(s) ?: return "" to s
    val code = found.groupValues[1].ifEmpty { found.groupValues[2] }
    if (code !in LANGS) return "" to s
    return code to s.substring(found.range.last + 1)
}

/** Some providers write "NL Title" with only a space. That is only treated as a language code when a good share
 *  of the list does it, so a film like "IT Chapter Two" keeps its name in a list that does not. */
fun <T : List<Title>> bareLangs(items: T): T {
    val hits = items.mapNotNull { i ->
        if (i.lang.isNotEmpty()) null
        else BARE_LANG.find(i.name)?.groupValues?.get(1)?.takeIf { it in LANGS }?.let { i to it }
    }
    if (hits.size >= maxOf(3, items.size / 5)) {
        for ((i, code) in hits) {
            i.lang = code
            i.name = i.name.substring(code.length).trim()
        }
    }
    return items
}

/** Moves a year after the title into its own field. */
fun <T : List<Title>> years(items: T): T {
    for (i in items) {
        val found = TITLE_YEAR.find(i.name) ?: continue
        i.name = found.groupValues[1]
        i.year = found.groupValues[2].toInt()
    }
    return items
}

/** Percent-encodes like Python's urllib.parse.quote, which leaves / alone. */
fun quote(text: String): String {
    val out = StringBuilder()
    for (b in text.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        if (c < 128 && (Character.isLetterOrDigit(c.toChar()) || c.toChar() in "_.-~/")) out.append(c.toChar())
        else out.append('%').append(String.format("%02X", c))
    }
    return out.toString()
}

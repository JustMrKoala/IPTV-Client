package app.lounge.tuner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest

/** The phone remote: remote.html on the local network, behind a secret path that works like a password. */
object Remote {
    private const val PORT = 8776
    private var token = ""
    private val ICON = Regex("icon-(\\d{2,3})\\.png")

    /** Starts the remote and returns its address, or "" when the port cannot be opened. */
    fun start(): String {
        token = State.read { it.s("remote_token") }
        if (token.isEmpty()) {
            token = hex(4)
            State.edit { it.put("remote_token", token) }
        }
        return try {
            HttpServer(InetAddress.getByName("0.0.0.0"), PORT, 1024, ::handle).start()
            "http://${lanIp()}:$PORT/$token/"
        } catch (e: IOException) {
            Log.w(TAG, "phone remote could not start: ${e.javaClass.simpleName}")
            ""
        }
    }

    // Read from the network interfaces rather than by connecting a socket, which Android refuses on the main thread
    private fun lanIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            // Wired and Wi-Fi first, so a VPN or mobile data address is only used when there is nothing else
            .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }

    private fun send(data: ByteArray, type: String, code: Int = 200, extra: Map<String, String> = emptyMap()) = Response(
        code, type, data,
        mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:",
        ) + extra,
    )

    private fun notFound() = send("Not found".toByteArray(), "text/plain", 404)

    /** The part of the path after the secret, or null when the secret is wrong. */
    private fun target(path: String): String? {
        val trimmed = path.trimStart('/')
        val given = trimmed.substringBefore('/')
        if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return null
        return if ('/' in trimmed) trimmed.substringAfter('/') else ""
    }

    private fun handle(r: Request): Response {
        val sub = target(r.path) ?: return notFound()
        if (r.method == "POST") {
            if (sub != "cmd") return notFound()
            return try {
                val body = JSONObject(String(r.body, Charsets.UTF_8).ifEmpty { "{}" })
                if (body.getString("cmd") == "text") Tuner.sendText(body.s("text")) else Tuner.runCommand(body.s("cmd"))
                send("{\"ok\":true}".toByteArray(), "application/json")
            } catch (e: Exception) {
                Log.w(TAG, "remote command failed: ${e.javaClass.simpleName}")
                send("{\"error\":\"Request failed\"}".toByteArray(), "application/json", 500)
            }
        }
        if (sub == "" && !r.path.endsWith("/")) return send(ByteArray(0), "text/plain", 302, mapOf("Location" to r.path + "/"))
        if (sub == "") return send(Tuner.app.assets.open("remote.html").use { it.readBytes() }, "text/html; charset=utf-8")
        if (sub == "manifest.json") {
            val icons = JSONArray(listOf(192, 512).map {
                JSONObject().put("src", "icon-$it.png").put("sizes", "${it}x$it").put("type", "image/png")
            })
            val manifest = JSONObject().put("name", "Lounge Remote").put("short_name", "Remote").put("start_url", "./")
                .put("scope", "./").put("display", "standalone").put("background_color", "#141414")
                .put("theme_color", "#141414").put("icons", icons)
            return send(manifest.toString().toByteArray(), "application/manifest+json")
        }
        ICON.matchEntire(sub)?.let { return send(icon(minOf(it.groupValues[1].toInt(), 512)), "image/png") }
        return notFound()
    }

    // The same red dot on black as the Linux version's icon
    private fun icon(size: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(0xff141414.toInt())
            drawCircle(size / 2f, size / 2f, size * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffe50914.toInt() })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

package app.lounge.tuner

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

/** A response is either a body, or a stream that writes until the client goes away (the event stream). */
class Response(
    val code: Int,
    val type: String,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val stream: ((OutputStream) -> Unit)? = null,
)

/** A small HTTP/1.1 server, one request per connection, which is all the TV interface and the phone remote need. */
class HttpServer(address: InetAddress, port: Int, private val maxBody: Int, private val handle: (Request) -> Response) {
    private val socket = ServerSocket(port, 50, address)
    val port: Int get() = socket.localPort

    private class TooLarge : Exception()

    fun start() = apply {
        thread(isDaemon = true, name = "http-$port") {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    WORKERS.execute { serve(client) }
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                it.soTimeout = 15000
                val out = BufferedOutputStream(it.getOutputStream())
                try {
                    val request = read(BufferedInputStream(it.getInputStream())) ?: return
                    write(out, handle(request))
                } catch (_: TooLarge) {
                    write(out, Response(413, "text/plain", "Too large".toByteArray()))
                } catch (_: IllegalArgumentException) {
                    write(out, Response(400, "text/plain", "Bad request".toByteArray()))
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun read(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        require(parts.size >= 3) { "bad request line" }
        val headers = HashMap<String, String>()
        while (true) {
            val header = readLine(input)
            if (header.isNullOrEmpty()) break
            require(headers.size <= 100) { "too many headers" }
            val colon = header.indexOf(':')
            if (colon > 0) headers[header.substring(0, colon).trim().lowercase(Locale.ROOT)] = header.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > maxBody) throw TooLarge()
        val body = readBody(input, length)
        val target = parts[1]
        return Request(parts[0], target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), headers, body)
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(maxOf(length, 0))
        var got = 0
        while (got < body.size) {
            val n = input.read(body, got, body.size - got)
            if (n < 0) throw IOException("body cut short")
            got += n
        }
        return body
    }

    private fun write(out: OutputStream, r: Response) {
        val head = StringBuilder("HTTP/1.1 ${r.code} ${REASONS[r.code] ?: "OK"}\r\n")
        head.append("Content-Type: ").append(r.type).append("\r\n")
        if (r.stream == null) head.append("Content-Length: ").append(r.body.size).append("\r\n")
        for ((k, v) in r.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        val stream = r.stream
        if (stream != null) {
            out.flush()
            stream(out)
        } else {
            out.write(r.body)
        }
        out.flush()
    }

    companion object {
        private val WORKERS = Executors.newCachedThreadPool { r -> Thread(r, "http-worker").apply { isDaemon = true } }
        private val REASONS = mapOf(
            200 to "OK", 302 to "Found", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found",
            413 to "Payload Too Large", 500 to "Internal Server Error",
        )

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (buf.size() <= 8192) {
                when (val b = input.read()) {
                    -1 -> return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                    '\n'.code -> return buf.toString("ISO-8859-1")
                    '\r'.code -> {}
                    else -> buf.write(b)
                }
            }
            throw IllegalArgumentException("line too long")
        }

        // Like Python's parse_qs: the first value wins and empty values are left out
        fun parseQuery(query: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (pair in query.split('&')) {
                val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                if (v.isNotEmpty() && k !in out) out[k] = v
            }
            return out
        }
    }
}

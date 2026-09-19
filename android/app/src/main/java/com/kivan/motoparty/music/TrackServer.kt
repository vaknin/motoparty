package com.kivan.motoparty.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Minimal HTTP/1.1 server for PROTOCOL.md "Tracks": only `GET|HEAD /track/<id>.m4a`, with single
 * `Range` support. Hand-rolled (≈150 lines) instead of NanoHTTPD. Pure JVM.
 */
class TrackServer(
    private val scope: CoroutineScope,
    private val lookup: (id: String) -> File?,
    private val port: Int = PORT,
) {
    private var server: ServerSocket? = null
    private var job: Job? = null

    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = ss
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                launch(Dispatchers.IO) { s.use { runCatching { serve(it) } } }
            }
        }
    }

    fun stop() {
        job?.cancel()
        runCatching { server?.close() }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        // Keep-alive: serve requests until the client closes or goes idle.
        while (true) {
            val request = try { readRequest(input) } catch (_: SocketTimeoutException) { return } ?: return
            val keepAlive = request.headers["connection"]?.equals("close", ignoreCase = true) != true
            respond(request, out)
            out.flush()
            if (!keepAlive) return
        }
    }

    internal class HttpRequest(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): HttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            if (headers.size > 100) return null
        }
        return HttpRequest(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) throw IOException("header line too long")
        }
    }

    private fun respond(req: HttpRequest, out: OutputStream) {
        if (req.method != "GET" && req.method != "HEAD") return status(out, 405, "Method Not Allowed")
        val id = PATH.matchEntire(req.path.substringBefore('?'))?.groupValues?.get(1)
        val file = id?.takeIf(::isValidTrackId)?.let(lookup)
        if (file == null || !file.isFile) return status(out, 404, "Not Found")
        val size = file.length()
        val range = req.headers["range"]?.let { parseRange(it, size) }
        if (range == INVALID) {
            return head(out, 416, "Range Not Satisfiable", 0, listOf("Content-Range: bytes */$size"))
        }
        val (from, to) = range ?: (0L to size - 1)
        val length = to - from + 1
        val extra = if (range != null) listOf("Content-Range: bytes $from-$to/$size") else emptyList()
        if (range != null) head(out, 206, "Partial Content", length, extra) else head(out, 200, "OK", length, extra)
        if (req.method == "HEAD") return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun head(out: OutputStream, code: Int, reason: String, length: Long, extra: List<String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: audio/mp4\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $length\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun status(out: OutputStream, code: Int, reason: String) {
        val body = "$code $reason\n".toByteArray()
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
        out.write(body)
    }

    companion object {
        const val PORT = 47802
        private val PATH = Regex("/track/([^/]+)\\.m4a")
        internal val INVALID = -1L to -1L

        /** Single-range `bytes=a-b`, `bytes=a-`, `bytes=-n`. Null = ignore header (serve 200). */
        internal fun parseRange(header: String, size: Long): Pair<Long, Long>? {
            val spec = header.trim().removePrefix("bytes=").takeIf { header.trim().startsWith("bytes=") } ?: return null
            if (',' in spec) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return when {
                a.isEmpty() -> {
                    val n = b.toLongOrNull() ?: return null
                    if (n <= 0) INVALID else maxOf(0, size - n) to size - 1
                }
                else -> {
                    val from = a.toLongOrNull() ?: return null
                    val to = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(size - 1)
                    if (from >= size || to < from) INVALID else from to to
                }
            }
        }
    }
}

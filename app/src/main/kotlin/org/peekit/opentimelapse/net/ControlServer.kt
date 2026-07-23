package org.peekit.opentimelapse.net

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import org.peekit.opentimelapse.core.net.Access
import org.peekit.opentimelapse.core.net.ControlAuth
import org.peekit.opentimelapse.core.net.Route
import org.peekit.opentimelapse.core.net.StatusSnapshot
import org.peekit.opentimelapse.data.LogRepository

/**
 * What the server needs from the rest of the app. Kept as an interface so the socket code
 * below has no idea what a session is - it moves bytes and checks tokens, nothing more.
 */
interface ControlBackend {
    fun status(): StatusSnapshot

    /** Last confirmed frame, downscaled to a JPEG for the browser. Null if there is none yet. */
    fun previewJpeg(): ByteArray?

    fun requestStart()

    fun requestStop()
}

/**
 * A hand-rolled HTTP/1.1 server for the four control endpoints, run inside the foreground
 * service so it lives exactly as long as a session and never has to be started from the
 * background.
 *
 * Hand-rolled rather than NanoHTTPD: four endpoints is ~a screen of parsing, and the app's
 * dependency list is short enough to be worth keeping that way. The parsing is deliberately
 * minimal - request line, headers, optional body - because the surface is a personal LAN
 * tool, not the open internet, and every request is gated by [ControlAuth] before any work.
 *
 * Threading: one accept loop, a small pool for connections. SSE ties up a thread per client,
 * so connections are capped.
 */
class ControlServer(
    private val backend: ControlBackend,
    private val log: LogRepository,
) {

    private val json = Json { encodeDefaults = true }
    private val running = AtomicBoolean(false)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var token: String = ""

    private val pool = Executors.newFixedThreadPool(MAX_CONNECTIONS)
    private var acceptThread: Thread? = null

    /** The port actually bound, so mDNS and the UI advertise the truth. -1 until started. */
    @Volatile var boundPort: Int = -1
        private set

    fun start(token: String, port: Int) {
        if (running.getAndSet(true)) return
        this.token = token

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            running.set(false)
            log.message("Control server could not bind port $port: ${e.message}")
            return
        }

        serverSocket = socket
        boundPort = socket.localPort

        acceptThread = Thread({ acceptLoop(socket) }, "control-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = -1
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val connection = try {
                socket.accept()
            } catch (e: Exception) {
                // A closed socket during stop() lands here; that is the normal exit.
                if (running.get()) log.message("Control server accept failed: ${e.message}")
                break
            }
            pool.execute { handle(connection) }
        }
    }

    private fun handle(connection: Socket) {
        connection.use { socket ->
            runCatching {
                socket.soTimeout = READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val request = parseRequest(reader) ?: return@runCatching
                route(request, socket.getOutputStream())
            }
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val authorization: String?,
    )

    private fun parseRequest(reader: BufferedReader): Request? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        val path = parts[1]

        var authorization: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Authorization:", ignoreCase = true)) {
                authorization = line.substringAfter(':').trim()
            }
        }
        return Request(method, path, authorization)
    }

    private fun route(request: Request, out: OutputStream) {
        val presented = ControlAuth.presentedToken(request.authorization, request.path)
        when (ControlAuth.evaluate(token, request.method, request.path, presented)) {
            Access.NOT_FOUND -> respond(out, 404, "text/plain", "Not found".toByteArray())
            Access.BAD_METHOD -> respond(out, 405, "text/plain", "Method not allowed".toByteArray())
            Access.UNAUTHORIZED ->
                respond(out, 401, "text/plain", "Unauthorized - token required".toByteArray())

            Access.ALLOW_PUBLIC -> respond(out, 200, "text/html; charset=utf-8", ControlPage.HTML.toByteArray())

            Access.ALLOW -> serve(Route.match(request.method, request.path), request, out)
        }
    }

    private fun serve(route: Route?, request: Request, out: OutputStream) {
        when (route) {
            Route.STATUS ->
                respond(out, 200, "application/json", json.encodeToString(backend.status()).toByteArray())

            Route.PREVIEW -> {
                val jpeg = backend.previewJpeg()
                if (jpeg == null) {
                    respond(out, 204, "text/plain", ByteArray(0))
                } else {
                    respond(out, 200, "image/jpeg", jpeg)
                }
            }

            Route.START -> {
                backend.requestStart()
                respond(out, 200, "application/json", """{"ok":true}""".toByteArray())
            }

            Route.STOP -> {
                backend.requestStop()
                respond(out, 200, "application/json", """{"ok":true}""".toByteArray())
            }

            Route.EVENTS -> streamEvents(out)

            // PAGE is handled as ALLOW_PUBLIC; reaching here means an unmapped allowed route.
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    /**
     * Server-sent events: push a status line about twice a second until the client hangs up.
     * A dropped client surfaces as a write failure, which ends the loop and frees the thread.
     */
    private fun streamEvents(out: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray())
        out.flush()

        while (running.get()) {
            val payload = json.encodeToString(backend.status())
            try {
                out.write("data: $payload\n\n".toByteArray())
                out.flush()
            } catch (e: Exception) {
                break
            }
            try {
                Thread.sleep(EVENT_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, contentType: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            // A personal tool served over http to a browser page on a laptop; let it fetch.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val buffer = ByteArrayOutputStream()
        buffer.write(header.toByteArray())
        buffer.write(body)
        out.write(buffer.toByteArray())
        out.flush()
    }

    private companion object {
        const val MAX_CONNECTIONS = 4
        const val READ_TIMEOUT_MS = 15_000
        const val EVENT_INTERVAL_MS = 500L
    }
}

package com.kivan.motoparty.link

import android.util.Log
import com.kivan.motoparty.core.Bye
import com.kivan.motoparty.core.Codec
import com.kivan.motoparty.core.FrameReader
import com.kivan.motoparty.core.Hello
import com.kivan.motoparty.core.MalformedMessageException
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.Ping
import com.kivan.motoparty.core.Pong
import com.kivan.motoparty.core.ProtocolException
import com.kivan.motoparty.core.Role
import com.kivan.motoparty.core.UnknownMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP control channel (PROTOCOL.md "Control channel"). Every connection gets `hello` + `state`
 * at once; the connection that answers with a client `hello` becomes *the* client, replacing
 * any previous one. Pings are answered on the reader thread for accurate t1/t2; everything
 * else is handed to the host through [events] in arrival order.
 */
class ControlServer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val hostHello: () -> Hello,
    private val stateNow: () -> Message,
    private val port: Int = PORT,
) {
    sealed interface Event {
        data class ClientConnected(val name: String, val address: InetAddress) : Event
        data class Received(val message: Message, val atMs: Long) : Event
        data class ClientGone(val reason: String) : Event
    }

    val events = Channel<Event>(Channel.UNLIMITED)

    @Volatile
    private var active: Connection? = null
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val ids = AtomicInteger()

    val clientAddress: InetAddress? get() = active?.socket?.inetAddress

    /** Client clock minus host clock, taken from each ping's t1 - t0 (includes one-way delay). */
    @Volatile
    var lastPingSkewMs: Long? = null
        private set

    @Volatile
    var lastRxAtMs: Long = 0
        private set

    fun start() {
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = ss
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (isActive) Log.w(TAG, "accept failed", e)
                    break
                }
                Connection(socket, ids.incrementAndGet()).start()
            }
        }
    }

    fun stop() {
        active?.close("host stopping", sendBye = true)
        acceptJob?.cancel()
        runCatching { server?.close() }
    }

    fun hasClient(): Boolean = active != null

    /** Sends to the current client, if any. Safe from any thread. */
    fun send(message: Message) {
        active?.send(message)
    }

    private inner class Connection(val socket: Socket, val id: Int) {
        private val out = BufferedOutputStream(socket.getOutputStream())
        // All writes go through one writer coroutine: callers may be on the main thread, where
        // Android forbids socket I/O, and frames must never interleave.
        private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile private var lastRx = clock()
        @Volatile private var closed = false
        private val jobs = mutableListOf<Job>()
        private lateinit var writer: Job

        fun start() {
            socket.tcpNoDelay = true
            Log.i(TAG, "#$id connected from ${socket.remoteSocketAddress}")
            writer = scope.launch(Dispatchers.IO) { writeLoop() }
            jobs += scope.launch(Dispatchers.IO) { readLoop() }
            jobs += scope.launch(Dispatchers.IO) { watchdog() }
            send(hostHello())
            send(stateNow())
        }

        fun send(message: Message) {
            if (closed) return
            outbox.trySend(Codec.frame(message))
        }

        private suspend fun writeLoop() {
            try {
                for (frame in outbox) {
                    out.write(frame)
                    out.flush()
                }
            } catch (e: IOException) {
                close("write failed: ${e.message}", sendBye = false)
            }
        }

        private fun readLoop() {
            val reader = FrameReader(socket.getInputStream())
            try {
                while (!closed) {
                    val text = reader.readText() ?: break
                    val now = clock()
                    lastRx = now
                    val message = try {
                        Codec.decode(text)
                    } catch (e: MalformedMessageException) {
                        // PROTOCOL.md: dropped and logged, the connection stays up.
                        Log.w(TAG, "#$id dropping malformed message: ${e.message}")
                        continue
                    }
                    if (this === active) lastRxAtMs = now
                    handle(message, now)
                    if (message is Bye) break
                }
                close("closed by peer", sendBye = false)
            } catch (e: ProtocolException) {
                close("protocol error: ${e.message}", sendBye = false)
            } catch (e: IOException) {
                close("read failed: ${e.message}", sendBye = false)
            }
        }

        private fun handle(message: Message, now: Long) {
            when (message) {
                is Ping -> {
                    if (this === active) lastPingSkewMs = message.t0 - now
                    send(Pong(message.id, message.t0, now, clock()))
                }
                is Hello -> {
                    if (message.role != Role.CLIENT) return
                    val previous = active
                    active = this
                    lastRxAtMs = now
                    if (previous != null && previous !== this) {
                        previous.close("replaced by #$id", sendBye = true)
                    }
                    Log.i(TAG, "#$id is the client: ${message.name}")
                    events.trySend(Event.ClientConnected(message.name, socket.inetAddress))
                }
                is UnknownMessage -> Unit // PROTOCOL.md: unknown types are ignored
                else -> if (this === active) events.trySend(Event.Received(message, now))
            }
        }

        private suspend fun watchdog() {
            while (!closed) {
                delay(1000)
                if (clock() - lastRx > LIVENESS_MS) {
                    close("no frame for ${LIVENESS_MS / 1000} s", sendBye = false)
                }
            }
        }

        fun close(reason: String, sendBye: Boolean) {
            if (closed) return
            closed = true
            if (sendBye) outbox.trySend(Codec.frame(Bye(reason.take(60))))
            outbox.close()
            jobs.forEach { it.cancel() }
            // Let the writer drain (the bye) before the socket goes away.
            scope.launch(Dispatchers.IO) {
                withTimeoutOrNull(1000) { writer.join() }
                runCatching { socket.close() }
            }
            Log.i(TAG, "#$id closed: $reason")
            if (active === this) {
                active = null
                lastPingSkewMs = null
                events.trySend(Event.ClientGone(reason))
            }
        }
    }

    companion object {
        const val PORT = 47800
        const val LIVENESS_MS = 6_000L
        private const val TAG = "ControlServer"
    }
}

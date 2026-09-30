package com.kivan.motoparty.link

import android.util.Log
import com.kivan.motoparty.core.Bye
import com.kivan.motoparty.core.Codec
import com.kivan.motoparty.core.FrameReader
import com.kivan.motoparty.core.Hello
import com.kivan.motoparty.core.MalformedMessageException
import com.kivan.motoparty.core.PROTO_VERSION
import com.kivan.motoparty.core.Message
import com.kivan.motoparty.core.Ping
import com.kivan.motoparty.core.Pong
import com.kivan.motoparty.core.ProtocolException
import com.kivan.motoparty.core.Role
import com.kivan.motoparty.core.UnknownMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP control channel (PROTOCOL.md "Control channel"). Every connection gets `hello` + `state`
 * at once; the connection that answers with a client `hello` becomes *the* client, replacing
 * any previous one. Pings are answered on the reader thread for accurate t1/t2; everything
 * else is handed to the host through [events] in arrival order.
 *
 * A connection that never sends a client `hello` is a discovery probe (PROTOCOL.md "Discovery"
 * 2-4: it reads our `hello` and closes). It raises no event and touches no client state; it is
 * closed after [PRE_HELLO_MS] if it lingers, and costs exactly one [log] line when it goes.
 * [log] also carries the few lines that matter to the rider's log (the accept loop dying).
 */
class ControlServer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val hostHello: () -> Hello,
    private val stateNow: () -> Message,
    private val port: Int = PORT,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    sealed interface Event {
        data class ClientConnected(val name: String, val address: InetAddress) : Event
        data class Received(val message: Message, val atMs: Long) : Event
        data class ClientGone(val reason: String) : Event
    }

    val events = Channel<Event>(Channel.UNLIMITED)

    /** The client. Written only under [swap]; read from any thread. */
    @Volatile
    private var active: Connection? = null
    /**
     * Guards every change of [active] together with the event that reports it, so a closing
     * client and the hello that replaces it cannot interleave: the host sees `ClientGone` then
     * `ClientConnected`, or the replacement alone, never a connected client it then forgets.
     */
    private val swap = Any()
    /**
     * Writers and the socket close that waits for them run here, not in [scope]: the host
     * cancels its scope right after [stop], and the `bye` must still go out. Nothing here
     * outlives a connection by more than [DRAIN_MS].
     */
    private val drain = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val ids = AtomicInteger()
    /** Every open connection, the client and probes alike, so [stop] can close them all. */
    private val conns = ConcurrentHashMap.newKeySet<Connection>()

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
                    // After this nothing answers on the port until the host restarts: say so.
                    if (isActive) log("control: accept failed, port ${port} closed: ${e.message}")
                    break
                }
                // A probe may already have reset the socket: one bad connection must never end
                // the accept loop.
                try {
                    Connection(socket, ids.incrementAndGet()).start()
                } catch (e: Exception) {
                    Log.w(TAG, "connection from ${socket.inetAddress?.hostAddress} failed to start: $e")
                    runCatching { socket.close() }
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { server?.close() }
        for (c in conns.toList()) c.close("host stopping", sendBye = c === active)
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
        private val openedAt = clock()
        @Volatile private var lastRx = openedAt
        @Volatile private var closed = false
        /** Set on its first client `hello`; until then this is a probe. */
        @Volatile private var hello = false
        private val jobs = mutableListOf<Job>()
        private lateinit var writer: Job

        fun start() {
            conns += this
            socket.tcpNoDelay = true
            // CS5 (voice is EF): control and its clock pings go ahead of best-effort traffic on
            // the hotspot's WMM queues. A hint only; a stack that refuses it changes nothing.
            runCatching { socket.trafficClass = TRAFFIC_CLASS }
            writer = drain.launch { writeLoop() }
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
                close(PEER_CLOSED, sendBye = false)
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
                    // PROTOCOL.md "Control channel": another protocol version gets
                    // `bye{reason:"proto"}` and no session; it never becomes the client.
                    if (message.proto != PROTO_VERSION) {
                        log("control: \"${message.name}\" speaks proto ${message.proto}, not $PROTO_VERSION: refused")
                        close(BYE_PROTO, sendBye = true)
                        return
                    }
                    hello = true
                    val previous = synchronized(swap) {
                        if (closed) return // closed meanwhile (host stopping): never the client
                        val was = active
                        active = this
                        lastRxAtMs = now
                        events.trySend(Event.ClientConnected(message.name, socket.inetAddress))
                        was
                    }
                    Log.i(TAG, "#$id is the client: ${message.name} (${socket.remoteSocketAddress})")
                    // Outside the lock ([close] takes the connection's monitor, then [swap]).
                    // It is no longer the client, so its close raises no ClientGone.
                    if (previous != null && previous !== this) {
                        previous.close("replaced by #$id", sendBye = true)
                    }
                }
                is UnknownMessage -> Unit // PROTOCOL.md: unknown types are ignored
                else -> if (this === active) events.trySend(Event.Received(message, now))
            }
        }

        private suspend fun watchdog() {
            while (!closed) {
                delay(500)
                if (!hello && clock() - openedAt > PRE_HELLO_MS) {
                    close("no hello in ${PRE_HELLO_MS / 1000} s", sendBye = false)
                } else if (clock() - lastRx > LIVENESS_MS) {
                    close("no frame for ${LIVENESS_MS / 1000} s", sendBye = false)
                }
            }
        }

        @Synchronized
        fun close(reason: String, sendBye: Boolean) {
            if (closed) return
            closed = true
            conns -= this
            if (sendBye) outbox.trySend(Codec.frame(Bye(reason.take(60))))
            outbox.close()
            jobs.forEach { it.cancel() }
            // Let the writer drain (the bye) before the socket goes away; closing the socket
            // also unblocks the reader, and a writer stuck on a dead peer.
            drain.launch {
                withTimeoutOrNull(DRAIN_MS) { writer.join() }
            }.invokeOnCompletion { runCatching { socket.close() } }
            if (hello || reason == BYE_PROTO) {
                Log.i(TAG, "#$id closed: $reason")
            } else {
                val from = socket.inetAddress?.hostAddress
                log(if (reason == PEER_CLOSED) "probe from $from closed" else "probe from $from closed: $reason")
            }
            synchronized(swap) {
                if (active === this) {
                    active = null
                    lastPingSkewMs = null
                    events.trySend(Event.ClientGone(reason))
                }
            }
        }
    }

    companion object {
        const val PORT = 47800
        const val LIVENESS_MS = 6_000L
        /** A connection with no client `hello` by then is a probe that lingered (PROTOCOL.md "Discovery" 4). */
        const val PRE_HELLO_MS = 3_000L
        /** How long a closing connection's writer gets to send what is queued (the `bye`). */
        private const val DRAIN_MS = 1_000L
        private const val PEER_CLOSED = "closed by peer"
        /** `bye.reason` for a client `hello` with another `proto`. */
        const val BYE_PROTO = "proto"
        /** DSCP CS5 in the TOS byte. */
        const val TRAFFIC_CLASS = 0xA0
        private const val TAG = "ControlServer"
    }
}

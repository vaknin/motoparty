package com.kivan.motoparty.core

import com.kivan.motoparty.link.ControlServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/** ControlServer over real loopback sockets (android.util.Log is stubbed in unit tests). */
class ControlServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    @Volatile private var now = 1_000L
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val server = ControlServer(
        scope, { now },
        hostHello = { Hello(proto = 1, role = Role.HOST, name = "test", voicePort = 47801, httpPort = 47802) },
        stateNow = { State(talk = false, queue = emptyList()) },
        port = port,
        log = { logs += it },
    ).also { it.start() }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private class Client(port: Int) {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        private val reader = FrameReader(socket.getInputStream())
        private val out = DataOutputStream(socket.getOutputStream())
        fun send(m: Message) = out.write(Codec.frame(m)).also { out.flush() }
        fun sendRaw(bytes: ByteArray) = out.write(bytes).also { out.flush() }
        fun read(): Message? = reader.read()
    }

    private fun nextEvent(): ControlServer.Event = runBlocking { withTimeout(3000) { server.events.receive() } }

    @Test
    fun helloStatePingPong() {
        val c = Client(port)
        assertEquals(Hello(proto = 1, role = "host", name = "test", voicePort = 47801, httpPort = 47802), c.read())
        assertEquals(State(talk = false, queue = emptyList()), c.read())
        c.send(Hello(proto = 1, role = "client", name = "iPhone"))
        val e = nextEvent() as ControlServer.Event.ClientConnected
        assertEquals("iPhone", e.name)
        c.send(Ping(7, 123))
        val pong = c.read() as Pong
        assertEquals(7L, pong.id)
        assertEquals(123L, pong.t0)
        assertEquals(1000L, pong.t1)
        assertEquals(1000L, pong.t2)
        c.send(TalkOpen("client"))
        assertEquals(TalkOpen("client"), (nextEvent() as ControlServer.Event.Received).message)
        server.send(Announce("hi"))
        assertEquals(Announce("hi"), c.read())
    }

    @Test
    fun messagesBeforeHelloAreNotDispatched() {
        val c = Client(port)
        c.read(); c.read()
        c.send(TalkOpen("client"))
        c.send(Ping(1, 5))
        assertTrue(c.read() is Pong) // pings are answered anyway
        assertNull(runBlocking { kotlinx.coroutines.withTimeoutOrNull(300) { server.events.receive() } })
    }

    @Test
    fun secondHelloReplacesFirstClient() {
        val a = Client(port)
        a.read(); a.read()
        a.send(Hello(proto = 1, role = "client", name = "A"))
        nextEvent()
        val b = Client(port)
        b.read(); b.read()
        b.send(Hello(proto = 1, role = "client", name = "B"))
        // A is told and closed; the host then reports B. ClientGone is not raised for A,
        // because A stopped being the client before it was closed.
        assertTrue(a.read() is Bye)
        assertEquals(null, runCatching { a.read() }.getOrNull())
        assertEquals("B", (nextEvent() as ControlServer.Event.ClientConnected).name)
    }

    @Test
    fun oversizeFrameClosesConnection() {
        val c = Client(port)
        c.read(); c.read()
        c.send(Hello(proto = 1, role = "client", name = "A"))
        nextEvent()
        c.sendRaw(byteArrayOf(0, 1, 0, 1))
        assertTrue(nextEvent() is ControlServer.Event.ClientGone)
    }

    @Test
    fun sixSecondsOfSilenceIsLinkLoss() {
        val c = Client(port)
        c.read(); c.read()
        c.send(Hello(proto = 1, role = "client", name = "A"))
        nextEvent()
        now += 6_001
        val gone = runBlocking { withTimeout(3000) { server.events.receive() } }
        assertTrue(gone is ControlServer.Event.ClientGone)
    }

    @Test
    fun malformedMessageIsDroppedButInvalidJsonCloses() {
        val c = Client(port)
        c.read(); c.read()
        c.send(Hello(proto = 1, role = "client", name = "A"))
        nextEvent()
        val framing = Fixtures.load("control/framing.json").jsonObject
        for (v in framing["malformed"]!!.jsonArray) c.sendRaw(Codec.frameText(v.jsonObject["json"]!!.jsonPrimitive.content))
        c.send(MusicReady("ok"))
        assertEquals(MusicReady("ok"), (nextEvent() as ControlServer.Event.Received).message)
        c.sendRaw(Fixtures.hex(framing["fatal"]!!.jsonArray[1].jsonObject["hex"]!!.jsonPrimitive.content))
        assertTrue(nextEvent() is ControlServer.Event.ClientGone)
    }

    @Test
    fun unknownTypesAreIgnored() {
        val c = Client(port)
        c.read(); c.read()
        c.send(Hello(proto = 1, role = "client", name = "A"))
        nextEvent()
        c.sendRaw(Codec.frameText("""{"t":"future.thing","x":1}"""))
        c.send(MusicReady("x"))
        assertEquals(MusicReady("x"), (nextEvent() as ControlServer.Event.Received).message)
    }

    private fun noEvent(ms: Long = 300) =
        assertNull(runBlocking { kotlinx.coroutines.withTimeoutOrNull(ms) { server.events.receive() } })

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 3000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; log: $logs")
            Thread.sleep(10)
        }
    }

    /** PROTOCOL.md "Discovery" 4: a probe reads hello (+ state) and closes; the client is untouched. */
    @Test
    fun probesDoNotDisturbTheClient() {
        val a = Client(port)
        a.read(); a.read()
        a.send(Hello(proto = 1, role = "client", name = "A"))
        nextEvent()
        // Several probes at once, as a client's parallel candidates and /24 sweep produce.
        val probes = List(16) { Client(port) }
        for (p in probes) {
            assertTrue(p.read() is Hello)
            assertTrue(p.read() is State)
            p.socket.close()
        }
        waitFor("16 probe lines") { logs.count { it.startsWith("probe from 127.0.0.1 closed") } == 16 }
        noEvent()
        assertTrue(server.hasClient())
        server.send(Announce("still here"))
        assertEquals(Announce("still here"), a.read())
        a.send(MusicReady("x"))
        assertEquals(MusicReady("x"), (nextEvent() as ControlServer.Event.Received).message)
    }

    @Test
    fun probeClosingWithoutReadingIsHarmless() {
        // Closing with unread data makes the kernel send a reset instead of a FIN.
        repeat(8) { Socket("127.0.0.1", port).close() }
        waitFor("8 probe lines") { logs.count { it.startsWith("probe from") } == 8 }
        noEvent()
        // The accept loop survived.
        val c = Client(port)
        assertTrue(c.read() is Hello)
    }

    @Test
    fun silentConnectionIsClosedBeforeHello() {
        val c = Client(port)
        c.read(); c.read()
        now += ControlServer.PRE_HELLO_MS + 1
        assertNull(c.read()) // EOF: the host closed it
        waitFor("probe line") { logs.any { it.startsWith("probe from 127.0.0.1 closed: no hello") } }
        noEvent()
    }

    @Test
    fun stopClosesProbesToo() {
        val c = Client(port)
        c.read(); c.read()
        server.stop()
        assertNull(runCatching { c.read() }.getOrNull())
    }
}

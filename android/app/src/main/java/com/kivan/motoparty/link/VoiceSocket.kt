package com.kivan.motoparty.link

import android.util.Log
import com.kivan.motoparty.core.VoicePacket
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * UDP voice endpoint (PROTOCOL.md "Voice"). Replies go to the source address of the most recent
 * valid packet from the control client's IP. Sends a keepalive once per second whenever no
 * audio went out, like the client does, so neither radio idles.
 */
class VoiceSocket(
    private val clientIp: () -> InetAddress?,
    /** Audio and keepalive packets from the client (keepalives matter to the jitter buffer). */
    private val onPacket: (VoicePacket) -> Unit,
    private val port: Int = PORT,
) {
    private var socket: DatagramSocket? = null
    @Volatile private var peer: SocketAddress? = null
    @Volatile private var running = false
    @Volatile private var lastSentMs = 0L
    private val seq = AtomicInteger()

    val packetsIn = AtomicLong()
    val packetsOut = AtomicLong()
    val peerAddress: SocketAddress? get() = peer

    fun start() {
        val s = DatagramSocket(port).apply {
            runCatching { trafficClass = 0xB8 } // DSCP EF: voice.
        }
        socket = s
        running = true
        thread(name = "voice-rx", isDaemon = true, priority = Thread.MAX_PRIORITY) { receiveLoop(s) }
        thread(name = "voice-keepalive", isDaemon = true) { keepaliveLoop() }
    }

    fun stop() {
        running = false
        socket?.close()
        peer = null
    }

    /** Forget the peer when the control client goes away. */
    fun forgetPeer() {
        peer = null
    }

    // PROTOCOL.md: `ts` is a running 16 kHz clock from a random start that keeps running while
    // talk is closed. Audio frames advance it sample-exactly; [currentTs] extrapolates in between.
    private val tsBase = (Math.random() * 0xffffffffL).toLong()
    private val tsStartNanos = System.nanoTime()
    @Volatile private var lastAudioTs = -1L

    /** Current value of the running clock, never behind the last audio frame sent. */
    fun currentTs(): Long {
        val clock = tsBase + (System.nanoTime() - tsStartNanos) * VoicePacket.SAMPLE_RATE / 1_000_000_000L
        val afterLast = if (lastAudioTs < 0) clock else lastAudioTs + VoicePacket.FRAME_SAMPLES
        return maxOf(clock, afterLast)
    }

    /** Sends the first [length] bytes of [payload]; it is copied, the caller may reuse it. */
    fun sendAudio(ts: Long, payload: ByteArray, length: Int) {
        lastAudioTs = ts
        send(VoicePacket.KIND_AUDIO, ts, payload, length)
    }

    // One buffer and one packet for every send (L8): the capture thread sends 50 a second. The
    // lock is only ever contended by the keepalive thread, which does not send while audio flows.
    private val sendBuf = ByteArray(VoicePacket.HEADER + MAX_PAYLOAD)
    private val sendPacket = DatagramPacket(sendBuf, 0)

    private fun send(kind: Int, ts: Long, payload: ByteArray, length: Int) {
        val to = peer ?: return
        val s = socket ?: return
        if (length > MAX_PAYLOAD) return
        try {
            synchronized(sendBuf) {
                VoicePacket.writeHeader(sendBuf, kind, seq.getAndIncrement() and 0xffff, ts and 0xffffffffL)
                System.arraycopy(payload, 0, sendBuf, VoicePacket.HEADER, length)
                sendPacket.setData(sendBuf, 0, VoicePacket.HEADER + length)
                sendPacket.socketAddress = to
                s.send(sendPacket)
            }
            lastSentMs = System.currentTimeMillis()
            packetsOut.incrementAndGet()
        } catch (e: IOException) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(1500)
        val dp = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                dp.length = buf.size
                s.receive(dp)
            } catch (e: IOException) {
                if (running) Log.w(TAG, "receive failed: ${e.message}")
                continue
            }
            val expected = clientIp() ?: continue
            if (dp.address != expected) continue
            val packet = VoicePacket.decode(buf, dp.length) ?: continue
            packetsIn.incrementAndGet()
            peer = dp.socketAddress
            onPacket(packet)
        }
    }

    private fun keepaliveLoop() {
        while (running) {
            Thread.sleep(250)
            if (System.currentTimeMillis() - lastSentMs >= 1000) send(VoicePacket.KIND_KEEPALIVE, currentTs(), NO_PAYLOAD, 0)
        }
    }

    companion object {
        const val PORT = 47801
        /** The largest Opus packet. */
        private const val MAX_PAYLOAD = 1275
        private val NO_PAYLOAD = ByteArray(0)
        private const val TAG = "VoiceSocket"
    }
}

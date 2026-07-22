package sh.haven.mosh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.mosh.crypto.MoshCrypto
import sh.haven.mosh.network.MoshConnection
import sh.haven.mosh.network.UdpReceivedPacket
import sh.haven.mosh.network.UdpSocketAdapter
import sh.haven.mosh.network.UdpSocketProvider
import java.util.Base64

/**
 * Regression tests for the socket rebind used to recover a stalled Mosh
 * session after IP roaming (#421). The bug: the old rebind closed the current
 * socket *before* calling the provider's `create()`; when `create()` threw
 * (tunnel flapped, or the interface change wasn't complete mid-roam), the
 * connection was left with a closed socket installed and the exception unwound
 * into the send loop — stranding the session one-way-dead with no reconnect.
 */
class RebindSocketTest {

    private fun crypto() = MoshCrypto(Base64.getEncoder().encodeToString(ByteArray(16)))

    private class RecordingAdapter : UdpSocketAdapter {
        var sends = 0
        var closed = false
        override fun send(data: ByteArray, host: String, port: Int) { sends++ }
        override fun receive(buf: ByteArray, timeoutMs: Int): UdpReceivedPacket? = null
        override fun close() { closed = true }
    }

    /** The #421 bug: a failing `create()` must not throw or close the old socket. */
    @Test
    fun `failed rebind keeps the old socket and does not throw`() {
        val a = RecordingAdapter()
        val b = RecordingAdapter()
        var call = 0
        val provider = UdpSocketProvider {
            when (call++) {
                0 -> a // installed at construction
                1 -> throw java.io.IOException("tunnel still down mid-roam")
                else -> b
            }
        }
        val conn = MoshConnection("127.0.0.1", 60001, crypto(), provider)

        // Must not throw (the old code unwound into the send loop) and must not
        // close the still-usable old socket.
        conn.rebindSocket()
        assertFalse("old socket must be retained, not closed, on a failed rebind", a.closed)

        // Once connectivity returns, the next rebind swaps cleanly.
        conn.rebindSocket()
        assertTrue("old socket is closed after a successful swap", a.closed)
        conn.close()
        assertTrue("the rebound socket is the one now installed", b.closed)
    }

    /** Happy path: a successful rebind swaps to the new socket and closes the old. */
    @Test
    fun `successful rebind swaps to the new socket and closes the old`() {
        val a = RecordingAdapter()
        val b = RecordingAdapter()
        var call = 0
        val provider = UdpSocketProvider { if (call++ == 0) a else b }
        val conn = MoshConnection("127.0.0.1", 60001, crypto(), provider)

        conn.rebindSocket()
        assertTrue("old socket closed after swap", a.closed)
        assertFalse("new socket left open", b.closed)
        conn.close()
        assertTrue("close() closes the current (rebound) socket", b.closed)
    }
}

package sh.haven.mosh.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Default [UdpSocketAdapter] — wraps a plain
 * [java.net.DatagramSocket] bound to the OS-assigned ephemeral port.
 * Matches Mosh's pre-#164 behaviour exactly: unconnected socket (avoids
 * ICMP-port-unreachable propagation as exceptions on Android),
 * per-receive [DatagramSocket.setSoTimeout].
 */
class AndroidUdpAdapter : UdpSocketAdapter {

    private val socket = DatagramSocket()
    private val sendPacket = DatagramPacket(ByteArray(0), 0)
    private val recvPacket = DatagramPacket(ByteArray(0), 0)

    override fun send(data: ByteArray, host: String, port: Int) {
        sendPacket.address = InetAddress.getByName(host)
        sendPacket.port = port
        sendPacket.setData(data, 0, data.size)
        socket.send(sendPacket)
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): UdpReceivedPacket? {
        socket.soTimeout = timeoutMs
        recvPacket.setData(buf, 0, buf.size)
        try {
            socket.receive(recvPacket)
        } catch (_: SocketTimeoutException) {
            return null
        }
        val addr = recvPacket.address
        return UdpReceivedPacket(
            length = recvPacket.length,
            srcHost = addr?.hostAddress ?: "",
            srcPort = recvPacket.port,
        )
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Throwable) { /* idempotent */ }
    }
}

package sh.haven.mosh.network

/**
 * Pluggable UDP transport for [MoshConnection]. The default
 * [AndroidUdpAdapter] wraps [java.net.DatagramSocket] for the normal
 * direct-Mosh case; alternative adapters route packets through a tunnel
 * (e.g. WireGuard via Haven's TunnelResolver — see #164).
 *
 * Not thread-safe by contract. [MoshConnection] serialises sends and
 * receives via its own `socketLock` and the receive coroutine, so
 * adapters can assume single-threaded access except during a rebind.
 */
interface UdpSocketAdapter {
    /**
     * Send [data] to `host:port`. host is a literal IP — Mosh resolved
     * the server IP earlier during the SSH bootstrap, so the data path
     * never needs DNS.
     */
    fun send(data: ByteArray, host: String, port: Int)

    /**
     * Block until a datagram arrives or [timeoutMs] elapses. Returns
     * `null` on timeout. The datagram is copied into [buf] starting at
     * index 0; up to `buf.size` bytes are filled. Larger datagrams are
     * truncated, mirroring [java.net.DatagramSocket].
     */
    fun receive(buf: ByteArray, timeoutMs: Int): UdpReceivedPacket?

    /** Close the socket. Idempotent. */
    fun close()
}

/**
 * Result of a successful [UdpSocketAdapter.receive]. Carries the byte
 * count and the source address — Mosh today only reads [length] (the
 * SSP nonce already authenticates the source), but tunnel adapters and
 * future roaming-on-source-change logic can use the address fields.
 */
data class UdpReceivedPacket(
    val length: Int,
    val srcHost: String,
    val srcPort: Int,
)

/**
 * Factory for [UdpSocketAdapter] instances. Used by [MoshConnection]'s
 * rebind path so the new socket inherits the same transport (tunnel vs.
 * direct) as the original. `fun interface` so callers can pass a lambda.
 */
fun interface UdpSocketProvider {
    fun create(): UdpSocketAdapter
}

package sh.haven.mosh.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.google.protobuf.ExtensionRegistryLite
import sh.haven.mosh.MoshLogger
import sh.haven.mosh.NoOpLogger
import sh.haven.mosh.crypto.MoshCrypto
import sh.haven.mosh.network.MoshConnection
import sh.haven.mosh.proto.Hostinput
import sh.haven.mosh.proto.Transportinstruction.Instruction as TransportInstruction
import java.io.Closeable

private const val TAG = "MoshTransport"

/**
 * Pure Kotlin mosh transport implementing the State Synchronization Protocol.
 *
 * Replaces the native mosh-client binary. Handles UDP communication,
 * AES-128-OCB encryption, SSP state tracking, and protobuf framing.
 *
 * Terminal output (VT100 sequences from the server) is delivered via
 * [onOutput] and fed directly to connectbot's termlib emulator.
 */
class MoshTransport(
    private val serverIp: String,
    private val port: Int,
    key: String,
    private val onOutput: (ByteArray, Int, Int) -> Unit,
    private val onDisconnect: ((cleanExit: Boolean) -> Unit)? = null,
    private val logger: MoshLogger = NoOpLogger,
    private val initialCols: Int = 80,
    private val initialRows: Int = 24,
    /**
     * Factory for the underlying UDP socket. Defaults to a plain
     * [java.net.DatagramSocket] via [sh.haven.mosh.network.AndroidUdpAdapter],
     * matching pre-#164 behaviour. Tunneled callers (Mosh-over-WireGuard
     * or Mosh-over-Tailscale per #164) pass a provider that yields a
     * tunneled adapter so packets traverse the tunnel rather than the
     * device's default route.
     */
    private val socketProvider: sh.haven.mosh.network.UdpSocketProvider =
        sh.haven.mosh.network.UdpSocketProvider { sh.haven.mosh.network.AndroidUdpAdapter() },
) : Closeable {

    private val crypto = MoshCrypto(key)
    private val userStream = UserStream()
    private val extensionRegistry = ExtensionRegistryLite.newInstance().also {
        Hostinput.registerAllExtensions(it)
    }

    // Connection created on IO thread in start() to avoid main-thread network StrictMode
    @Volatile private var connection: MoshConnection? = null

    // SSP state tracking
    private val receiveState = ReceiveState()         // guards client-side state advancement (#73)
    @Volatile private var serverAckedOurNum: Long = 0 // server's ack of our state
    @Volatile private var lastAckSent: Long = 0       // last ack we sent to server
    @Volatile private var lastSendTimeMs: Long = 0

    /** Current state number from the server; updated only when a matching-base diff is applied. */
    private val remoteStateNum: Long
        get() = receiveState.num

    // Track whether we have genuinely new data vs just retransmitting
    @Volatile private var lastSentNewNum: Long = 0
    @Volatile private var retransmitCount: Int = 0

    // Diagnostic counters
    @Volatile private var packetsSent: Long = 0
    @Volatile private var packetsReceived: Long = 0
    @Volatile private var firstOutputReceived: Boolean = false

    // Network roaming / session-dead detection
    @Volatile private var lastReceiveTimeMs: Long = 0
    @Volatile private var stallRebound = false

    // Countdown surfaced to the UI when the server has gone silent.
    // null while the connection is healthy or before the first packet arrives;
    // otherwise the seconds remaining before [SESSION_DEAD_MS] fires the close.
    private val _secondsUntilDisconnect = MutableStateFlow<Int?>(null)
    val secondsUntilDisconnect: StateFlow<Int?> = _secondsUntilDisconnect.asStateFlow()

    // Conflated channel: wakes the send loop immediately when input arrives
    private val inputNotify = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var closed = false
    private var receiveJob: Job? = null
    private var sendJob: Job? = null

    /**
     * Start the transport: opens UDP socket on IO thread, begins receive and send loops.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            if (closed) return@launch
            try {
                connection = MoshConnection(serverIp, port, crypto, socketProvider)
                logger.d(TAG, "UDP socket connected to $serverIp:$port")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to create UDP connection", e)
                onDisconnect?.invoke(false)
                return@launch
            }
            // Send initial resize — mosh-server waits for a client state change
            // (newNum > 0) before releasing the child shell process
            userStream.pushResize(initialCols, initialRows)
            logger.d(TAG, "Queued initial resize ${initialCols}x${initialRows}")

            // Only one coroutine sends — no race on nonce counter
            receiveJob = launch { receiveLoop() }
            sendJob = launch { sendLoop() }
        }
    }

    /** Enqueue user keystrokes for delivery to the server. */
    fun sendInput(data: ByteArray) {
        if (closed) return
        val prevSize = userStream.size
        userStream.pushKeystroke(data)
        logger.d(TAG, "sendInput: ${data.size} bytes, userStream ${prevSize}→${userStream.size}")
        inputNotify.trySend(Unit)
    }

    /** Enqueue a terminal resize event. */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        userStream.pushResize(cols, rows)
        inputNotify.trySend(Unit)
    }

    override fun close() {
        if (closed) return
        closed = true
        receiveJob?.cancel()
        sendJob?.cancel()
        try { connection?.close() } catch (_: Exception) {}
    }

    private suspend fun receiveLoop() {
        try {
            while (!closed) {
                val conn = connection ?: break
                val instruction = try {
                    conn.receiveInstruction(RECV_TIMEOUT_MS)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (e: Exception) {
                    if (!closed) logger.e(TAG, "Receive error: ${e.message}")
                    continue
                }

                if (instruction == null) continue // timeout
                lastReceiveTimeMs = System.currentTimeMillis()
                stallRebound = false
                _secondsUntilDisconnect.value = null
                processInstruction(instruction)
            }
        } catch (_: CancellationException) {
            // normal shutdown
        } catch (e: Exception) {
            if (!closed) {
                logger.e(TAG, "Receive loop failed", e)
                onDisconnect?.invoke(false)
            }
        }
    }

    private fun processInstruction(inst: TransportInstruction) {
        packetsReceived++

        // Update server's acknowledgement of our state
        if (inst.ackNum > serverAckedOurNum) {
            val oldAck = serverAckedOurNum
            serverAckedOurNum = inst.ackNum
            retransmitCount = 0 // server got our data, reset backoff
            logger.d(TAG, "Server acked our state: $oldAck → ${inst.ackNum}")
        }

        // Only apply diffs whose base matches our current state. The server's
        // diff is a VT100 rendering sequence computed from oldNum → newNum on
        // the *server's* framebuffer. Feeding those bytes to termlib when it
        // isn't at oldNum produces cumulative display corruption (the exact
        // "cursor below the prompt / freezes after a few Returns" symptom
        // reported on #73).
        //
        // receiveState.tryAdvance mirrors upstream mosh's Transport::recv()
        // semantics: on base mismatch it returns false and leaves the state
        // number alone. We must NOT lie about our state in subsequent acks
        // (the prior implementation advanced the number anyway, which made
        // the server throw away the exact states the client needed).
        if (!receiveState.tryAdvance(inst.oldNum, inst.newNum)) {
            if (inst.newNum > remoteStateNum) {
                logger.d(
                    TAG,
                    "Skipping diff: oldNum=${inst.oldNum} ≠ remoteStateNum=$remoteStateNum " +
                        "(newNum=${inst.newNum}) — waiting for retransmit with matching base",
                )
            }
            return
        }

        // Notify the send loop so it can ack the new state promptly.
        // Without this, the send loop sleeps until its next keepalive
        // (up to 3s), during which the server keeps computing diffs from
        // the old acked state — all of which get skipped because the
        // client's base has moved on. The cascade of skips causes
        // terminal state divergence (e.g. DECCKM mode lost → arrow keys
        // fail in Mutt). See #73.
        inputNotify.trySend(Unit)

        // tryAdvance already committed the state number. Render the diff.
        if (inst.hasDiff() && !inst.diff.isEmpty) {
            if (!firstOutputReceived) {
                firstOutputReceived = true
                logger.d(TAG, "First terminal output received (newNum=${inst.newNum}, diffSize=${inst.diff.size()}, packets sent=$packetsSent received=$packetsReceived)")
            }
            try {
                val hostMsg = Hostinput.HostMessage.parseFrom(inst.diff, extensionRegistry)
                for (hi in hostMsg.instructionList) {
                    if (hi.hasExtension(Hostinput.hostbytes)) {
                        val hb = hi.getExtension(Hostinput.hostbytes)
                        val bytes = hb.hoststring.toByteArray()
                        onOutput(bytes, 0, bytes.size)
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "Failed to decode HostMessage", e)
            }
        }
    }

    private suspend fun sendLoop() {
        try {
            // Send initial keepalive immediately
            sendState()

            while (!closed) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastSendTimeMs
                val currentNum = userStream.size
                val hasNewInput = currentNum != lastSentNewNum
                val hasNewAck = remoteStateNum > lastAckSent
                val needsRetransmit = currentNum > serverAckedOurNum

                val recvAge = now - lastReceiveTimeMs
                if (lastReceiveTimeMs > 0) {
                    // Surface a countdown once we cross the stall threshold so
                    // the UI can replace its silent unresponsive window with a
                    // visible "closing in N s" indicator.
                    _secondsUntilDisconnect.value = if (recvAge > STALL_DISPLAY_MS) {
                        ((SESSION_DEAD_MS - recvAge + 999) / 1000).coerceAtLeast(0L).toInt()
                    } else {
                        null
                    }
                    // Session-dead: mosh-server shuts down ~4s after shell exit
                    // and stops sending packets. Detect this and tear down
                    // instead of retransmitting into the void forever (#92).
                    if (recvAge > SESSION_DEAD_MS) {
                        logger.d(TAG, "Server unresponsive for ${recvAge}ms, disconnecting")
                        _secondsUntilDisconnect.value = 0
                        close()
                        onDisconnect?.invoke(true)
                        return
                    }
                    // Network stall: rebind socket once for IP roaming recovery
                    // before giving up at SESSION_DEAD_MS.
                    if (recvAge > NETWORK_STALL_MS && !stallRebound) {
                        logger.d(TAG, "No packets for ${recvAge}ms, rebinding socket")
                        connection?.rebindSocket()
                        stallRebound = true
                    }
                }

                when {
                    // New keystrokes: send promptly
                    hasNewInput && elapsed >= SEND_MIN_INTERVAL_MS -> sendState()
                    // New ack to send: send soon
                    hasNewAck && elapsed >= ACK_DELAY_MS -> sendState()
                    // Retransmit unacked data: back off exponentially
                    needsRetransmit && elapsed >= retransmitInterval() -> sendState()
                    // Keepalive
                    elapsed >= KEEPALIVE_INTERVAL_MS -> sendState()
                    else -> {
                        val wait = when {
                            hasNewInput -> SEND_MIN_INTERVAL_MS - elapsed
                            hasNewAck -> ACK_DELAY_MS - elapsed
                            needsRetransmit -> retransmitInterval() - elapsed
                            // Idle: sleep until next keepalive, woken early by inputNotify
                            else -> KEEPALIVE_INTERVAL_MS - elapsed
                        }
                        withTimeoutOrNull(maxOf(5L, wait)) { inputNotify.receive() }
                    }
                }
            }
        } catch (_: CancellationException) {
            // normal shutdown
        }
    }

    /** Exponential backoff for retransmissions: 100ms, 200ms, 400ms, capped at 1000ms. */
    private fun retransmitInterval(): Long {
        val base = 100L
        val interval = base shl minOf(retransmitCount, 3) // 100, 200, 400, 800
        return minOf(interval, 1000L)
    }

    private fun sendState() {
        if (closed) return
        try {
            val currentNum = userStream.size
            val diff = userStream.diffFrom(serverAckedOurNum)
            val instruction = TransportInstruction.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION)
                .setOldNum(serverAckedOurNum)
                .setNewNum(currentNum)
                .setAckNum(remoteStateNum)
                .setThrowawayNum(serverAckedOurNum)
                .setDiff(com.google.protobuf.ByteString.copyFrom(diff))
                .build()
            connection?.sendInstruction(instruction) ?: return
            packetsSent++
            val isRetransmit = currentNum == lastSentNewNum && currentNum > serverAckedOurNum
            if (packetsSent <= 3L || diff.isNotEmpty()) {
                logger.d(TAG, "sendState #$packetsSent: oldNum=$serverAckedOurNum newNum=$currentNum ackNum=$remoteStateNum diffSize=${diff.size}${if (isRetransmit) " RETRANSMIT" else ""}")
            }
            lastSendTimeMs = System.currentTimeMillis()
            lastAckSent = remoteStateNum

            if (currentNum == lastSentNewNum && currentNum > serverAckedOurNum) {
                retransmitCount++ // same data resent
            } else {
                retransmitCount = 0
            }
            lastSentNewNum = currentNum
        } catch (e: Exception) {
            if (!closed) logger.e(TAG, "Send error", e)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 2
        const val SEND_MIN_INTERVAL_MS = 20L
        const val ACK_DELAY_MS = 20L
        /** Threshold after which the UI sees a non-null countdown — set above
         *  [KEEPALIVE_INTERVAL_MS] (3s) so a single missed keepalive doesn't
         *  flicker the indicator on a healthy connection. */
        const val STALL_DISPLAY_MS = 4_000L
        const val NETWORK_STALL_MS = 6_000L
        const val SESSION_DEAD_MS = 8_000L
        const val KEEPALIVE_INTERVAL_MS = 3000L
        const val RECV_TIMEOUT_MS = 250
    }
}

package sh.haven.mosh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.mosh.transport.MoshTransport

/**
 * Policy tests for the #421 dead-session escalation.
 *
 * Reported symptom: a mosh session stalls (often while scrolling), the "no
 * server contact — retrying" banner appears and never resolves, and only a
 * manual close-and-reconnect — which starts a fresh mosh-server — recovers.
 * The transport deliberately never gave up, on the assumption that silence
 * only ever means the network is away; when the server side is gone instead,
 * that assumption strands the session forever.
 *
 * The escalation must NOT reintroduce #92, where a silence-only death
 * heuristic killed live sessions after 8s offline — hence the connectivity
 * gate, which these tests pin down.
 */
class DeadSessionEscalationTest {

    private val online = true
    private val offline = false

    /** The reported case: online, long silence, repeated rebinds unanswered. */
    @Test
    fun `declares dead when online and silent well past the threshold`() {
        assertTrue(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS + 1,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD,
                networkAvailable = online,
            ),
        )
    }

    /**
     * The #92 regression guard: a phone in a pocket, in a tunnel, or in doze is
     * offline, and mosh's whole value is that such a session resumes. However
     * long the silence, an offline device must never declare the session dead.
     */
    @Test
    fun `never declares dead while offline, however long the silence`() {
        assertFalse(
            "an offline device must keep waiting — this is the #92 regression",
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS * 100,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD * 10,
                networkAvailable = offline,
            ),
        )
    }

    /** A brief stall (a lost packet, a roam in progress) must not kill the session. */
    @Test
    fun `does not declare dead before the silence threshold`() {
        assertFalse(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS - 1,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD * 5,
                networkAvailable = online,
            ),
        )
    }

    /**
     * Recovery must have been attempted and ignored first: one rebind landing
     * badly (e.g. mid-interface-change) is not evidence the session is gone.
     */
    @Test
    fun `does not declare dead until enough rebinds have gone unanswered`() {
        assertFalse(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS * 10,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD - 1,
                networkAvailable = online,
            ),
        )
    }

    /**
     * The threshold is deliberately far above the rebind cadence, so a real
     * roam gets several recovery attempts before we ever consider giving up.
     */
    @Test
    fun `threshold leaves room for several rebinds before giving up`() {
        assertTrue(
            "DEAD_SESSION_MS must span at least MIN_REBINDS_BEFORE_DEAD rebind cycles",
            MoshTransport.DEAD_SESSION_MS >=
                MoshTransport.NETWORK_STALL_MS * MoshTransport.MIN_REBINDS_BEFORE_DEAD,
        )
    }
}

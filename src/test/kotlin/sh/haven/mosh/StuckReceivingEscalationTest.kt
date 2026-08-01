package sh.haven.mosh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.mosh.transport.MoshTransport

/**
 * #421, second failure mode: the session keeps RECEIVING and still goes
 * nowhere.
 *
 * From a reporter's log (v5.86.14). The first stall in that capture was the
 * silence case the existing escalation already handles — rebinds, then
 * "declaring the session dead", then an automatic reconnect. It worked. The
 * replacement session then wedged differently:
 *
 *     09:32:36  Skipping diff: oldNum=52 ≠ remoteStateNum=53 — waiting for retransmit
 *     09:32:51  Skipping diff: oldNum=57 ≠ remoteStateNum=58 — waiting for retransmit
 *     ...
 *     09:34:00  Skipping diff: oldNum=80 ≠ remoteStateNum=81 — waiting for retransmit
 *
 * Ninety seconds of the server talking and the client understanding none of
 * it: every diff is based on a state we do not have, so every one is dropped.
 * The silence-based check cannot see this, because packets are arriving the
 * whole time — `recvAge` never grows. The session is dead in every way the
 * user cares about and reads as perfectly healthy, which is exactly the "no
 * automatic reconnect" in the issue title.
 *
 * The escalation these pin must not resurrect #92 (a heuristic that killed
 * live sessions whenever the phone went briefly offline), and must not kill
 * an idle session — which is why it is gated on skips rather than on elapsed
 * time alone.
 */
class StuckReceivingEscalationTest {

    private val online = true
    private val offline = false

    /** The reported case: receiving throughout, rejecting everything. */
    @Test
    fun `declares dead when diffs keep arriving but none can be applied`() {
        assertTrue(
            "a session rejecting every diff for longer than the threshold is stuck, " +
                "however healthy its receive stream looks",
            MoshTransport.shouldDeclareDead(
                recvAgeMs = 0, // packets arriving constantly — silence check blind
                rebindsSinceReceive = 0,
                networkAvailable = online,
                noProgressMs = MoshTransport.NO_PROGRESS_DEAD_MS + 1,
                skipsSinceProgress = MoshTransport.MIN_SKIPS_BEFORE_DEAD,
            ),
        )
    }

    /**
     * The regression this could easily introduce: a session nobody is using
     * also makes no progress. It must survive, or every mosh session left open
     * overnight dies — which is the point of mosh.
     */
    @Test
    fun `an idle session is never declared dead however long it sits`() {
        assertFalse(
            "an idle session records no skips; only a peer actively sending " +
                "unusable diffs should escalate",
            MoshTransport.shouldDeclareDead(
                recvAgeMs = 0,
                rebindsSinceReceive = 0,
                networkAvailable = online,
                noProgressMs = MoshTransport.NO_PROGRESS_DEAD_MS * 100,
                skipsSinceProgress = 0,
            ),
        )
    }

    /** A diff crossing with our ack resolves itself; don't escalate on a blip. */
    @Test
    fun `a couple of skips is not enough`() {
        assertFalse(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = 0,
                rebindsSinceReceive = 0,
                networkAvailable = online,
                noProgressMs = MoshTransport.NO_PROGRESS_DEAD_MS + 1,
                skipsSinceProgress = MoshTransport.MIN_SKIPS_BEFORE_DEAD - 1,
            ),
        )
    }

    /** Nor is a sustained run that has not yet lasted long enough. */
    @Test
    fun `skips that have not persisted long enough do not escalate`() {
        assertFalse(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = 0,
                rebindsSinceReceive = 0,
                networkAvailable = online,
                noProgressMs = MoshTransport.NO_PROGRESS_DEAD_MS - 1,
                skipsSinceProgress = MoshTransport.MIN_SKIPS_BEFORE_DEAD * 10,
            ),
        )
    }

    /**
     * #92 guard, restated for this path: offline is offline. A phone in a
     * tunnel must resume, not be torn down, no matter what the counters say.
     */
    @Test
    fun `offline never escalates even when stuck`() {
        assertFalse(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = 0,
                rebindsSinceReceive = 0,
                networkAvailable = offline,
                noProgressMs = MoshTransport.NO_PROGRESS_DEAD_MS * 10,
                skipsSinceProgress = MoshTransport.MIN_SKIPS_BEFORE_DEAD * 10,
            ),
        )
    }

    /** The original silence path must still work exactly as before. */
    @Test
    fun `the silence path is unaffected by the new arguments`() {
        assertTrue(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS + 1,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD,
                networkAvailable = online,
            ),
        )
    }
}

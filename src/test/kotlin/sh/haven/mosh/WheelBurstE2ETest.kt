package sh.haven.mosh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.haven.mosh.transport.MoshTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Haven#421: scrolling tmux history over mosh kills the session — the reporter
 * confirmed mosh-server is gone afterwards — while the identical scroll over
 * SSH does not. Same terminal, same tmux, same bytes, so the difference is the
 * mosh transport rather than what the terminal emits.
 *
 * A swipe does not send one wheel event; Haven emits one per ~9dp of travel, so
 * a 2cm swipe on a 3x screen is roughly fourteen SGR wheel sequences delivered
 * back-to-back. This drives exactly that burst at a real mosh-server and asks
 * whether the session survives it.
 *
 * Skipped unless mosh-server is on PATH and MOSH_SERVER_E2E=1 is set.
 */
class WheelBurstE2ETest {

    private fun sgrWheelUp(col: Int, row: Int) = "[<64;$col;${row}M".toByteArray()

    @Test
    fun `session survives a burst of wheel events`() {
        assumeTrue(System.getenv("MOSH_SERVER_E2E") == "1")

        val proc = ProcessBuilder("mosh-server", "new", "-c", "256", "-l", "LANG=C.UTF-8")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(10, TimeUnit.SECONDS)
        val connect = Regex("MOSH CONNECT (\\d+) (\\S+)").find(out)
            ?: error("no MOSH CONNECT in mosh-server output: $out")
        val (port, key) = connect.destructured

        val output = StringBuilder()
        val disconnected = CountDownLatch(1)
        var cleanExit: Boolean? = null

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val t = MoshTransport(
            serverIp = "127.0.0.1",
            port = port.toInt(),
            key = key,
            onOutput = { data, off, len -> synchronized(output) { output.append(String(data, off, len)) } },
            onDisconnect = { clean ->
                cleanExit = clean
                disconnected.countDown()
            },
        )
        try {
            t.start(scope)
            Thread.sleep(1500)

            // Prove the session is live before stressing it, so a failure below
            // can't be "it was never up".
            t.sendInput("echo burst-ready\n".toByteArray())
            val readyDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < readyDeadline &&
                !synchronized(output) { output.contains("burst-ready") }
            ) {
                Thread.sleep(100)
            }
            assertTrue(
                "session never came up; output so far: $output",
                synchronized(output) { output.contains("burst-ready") },
            )

            // Ten swipes' worth of wheel events, delivered as fast as the
            // transport accepts them — the shape of a user flicking through
            // scrollback rather than a single tidy scroll.
            repeat(10) { swipe ->
                repeat(15) { i ->
                    t.sendInput(sgrWheelUp(col = 20 + (i % 3), row = 10 + (swipe % 5)))
                }
                Thread.sleep(30)
            }

            // The session must still be there afterwards, and must still round-trip.
            assertFalse(
                "transport reported a disconnect during the wheel burst (clean=$cleanExit)",
                disconnected.await(3, TimeUnit.SECONDS),
            )

            t.sendInput("echo still-alive\n".toByteArray())
            val aliveDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < aliveDeadline &&
                !synchronized(output) { output.contains("still-alive") }
            ) {
                Thread.sleep(100)
            }
            assertTrue(
                "no echo after the wheel burst — session is wedged or gone",
                synchronized(output) { output.contains("still-alive") },
            )
        } finally {
            t.close()
            scope.cancel()
        }
    }

    @Test
    fun `session survives a wheel burst while tmux redraws scrollback`() {
        assumeTrue(System.getenv("MOSH_SERVER_E2E") == "1")
        assumeTrue(ProcessBuilder("which", "tmux").start().waitFor() == 0)

        // The reporter's setup: mosh runs tmux, and scrolling is done in tmux's
        // copy-mode. Each wheel event there repaints the whole window, so the
        // burst stresses the SERVER->CLIENT direction (large framebuffer diffs)
        // rather than the handful of input bytes the previous test sends.
        val session = "haven421-" + System.nanoTime()
        val proc = ProcessBuilder(
            "mosh-server", "new", "-c", "256", "-l", "LANG=C.UTF-8",
            "--", "tmux", "new-session", "-A", "-s", session,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(10, TimeUnit.SECONDS)
        val connect = Regex("MOSH CONNECT (\\d+) (\\S+)").find(out)
            ?: error("no MOSH CONNECT in mosh-server output: $out")
        val (port, key) = connect.destructured

        val output = StringBuilder()
        val disconnected = CountDownLatch(1)
        var cleanExit: Boolean? = null

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val t = MoshTransport(
            serverIp = "127.0.0.1",
            port = port.toInt(),
            key = key,
            onOutput = { data, off, len -> synchronized(output) { output.append(String(data, off, len)) } },
            onDisconnect = { clean ->
                cleanExit = clean
                disconnected.countDown()
            },
        )
        try {
            t.start(scope)
            Thread.sleep(2500)

            // Fill the scrollback so there is something to page through.
            t.sendInput("seq 1 2000\n".toByteArray())
            Thread.sleep(3000)

            t.sendInput("echo tmux-ready\n".toByteArray())
            val readyDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < readyDeadline &&
                !synchronized(output) { output.contains("tmux-ready") }
            ) {
                Thread.sleep(100)
            }
            assertTrue(
                "tmux session never came up; output so far: ${output.takeLast(400)}",
                synchronized(output) { output.contains("tmux-ready") },
            )

            // Enter copy-mode, then flick: each wheel event repaints the window.
            t.sendInput(byteArrayOf(0x02)) // C-b
            t.sendInput("[".toByteArray())
            Thread.sleep(300)
            repeat(12) { swipe ->
                repeat(15) { i -> t.sendInput(sgrWheelUp(col = 20 + (i % 3), row = 10 + (swipe % 5))) }
                Thread.sleep(25)
            }

            assertFalse(
                "transport disconnected during the tmux scroll burst (clean=$cleanExit)",
                disconnected.await(5, TimeUnit.SECONDS),
            )

            // Leave copy-mode and prove the session still round-trips.
            t.sendInput("q".toByteArray())
            Thread.sleep(300)
            t.sendInput("echo survived-scroll\n".toByteArray())
            val aliveDeadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < aliveDeadline &&
                !synchronized(output) { output.contains("survived-scroll") }
            ) {
                Thread.sleep(100)
            }
            assertTrue(
                "no echo after the tmux scroll burst — session wedged or gone",
                synchronized(output) { output.contains("survived-scroll") },
            )
        } finally {
            t.close()
            scope.cancel()
            ProcessBuilder("tmux", "kill-session", "-t", session).start().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

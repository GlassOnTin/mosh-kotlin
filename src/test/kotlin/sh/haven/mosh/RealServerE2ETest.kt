package sh.haven.mosh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.haven.mosh.transport.MoshTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end test against a REAL mosh-server binary. Verifies empirically
 * that the server announces shutdown (state -1) when the shell exits —
 * the assumption the no-client-side-timeout design rests on
 * (GlassHaven/Haven#365) — and that basic SSP echo works.
 *
 * Skipped unless mosh-server is on PATH and MOSH_SERVER_E2E=1 is set:
 * CI runners don't have mosh-server installed by default.
 */
class RealServerE2ETest {

    @Test
    fun `real mosh-server announces shutdown on shell exit`() {
        assumeTrue(System.getenv("MOSH_SERVER_E2E") == "1")

        // mosh-server prints "MOSH CONNECT <port> <key>" then daemonizes.
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

            // Prove SSP round-trips before testing shutdown.
            Thread.sleep(1500)
            t.sendInput("echo m0sh-e2e-marker\n".toByteArray())
            val echoDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < echoDeadline &&
                !synchronized(output) { output.contains("m0sh-e2e-marker") }
            ) {
                Thread.sleep(100)
            }
            assertTrue(
                "no echo from real server; output so far: $output",
                synchronized(output) { output.contains("m0sh-e2e-marker") },
            )

            // Shell exit → server must ANNOUNCE shutdown (state -1) and the
            // client must report a clean exit. If the server merely went
            // silent this would hang and fail the await below.
            t.sendInput("exit\n".toByteArray())
            assertTrue(
                "no shutdown announcement within 15s of shell exit",
                disconnected.await(15, TimeUnit.SECONDS),
            )
            assertEquals(true, cleanExit)
        } finally {
            t.close()
            scope.cancel()
        }
    }
}

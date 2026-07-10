package sh.haven.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.mosh.proto.Transportinstruction.Instruction as TransportInstruction
import sh.haven.mosh.transport.MoshTransport
import java.util.Base64

/**
 * The server announces shutdown by sending a state numbered -1 (uint64
 * max) once the shell exits — upstream mosh Transport::start_shutdown().
 * The client must close and report a CLEAN exit on that instruction, and
 * must NOT treat ordinary instructions as shutdown. This is what lets the
 * transport survive network silence indefinitely (GlassHaven/Haven#365):
 * silence is never a death signal, only the announced shutdown is.
 */
class ShutdownDetectionTest {

    private fun randomKey(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun instruction(oldNum: Long, newNum: Long): TransportInstruction =
        TransportInstruction.newBuilder()
            .setProtocolVersion(MoshTransport.PROTOCOL_VERSION)
            .setOldNum(oldNum)
            .setNewNum(newNum)
            .setAckNum(0)
            .setThrowawayNum(0)
            .build()

    private class DisconnectRecorder {
        val calls = mutableListOf<Boolean>()
        fun callback(): (Boolean) -> Unit = { calls.add(it) }
    }

    private fun transport(recorder: DisconnectRecorder) = MoshTransport(
        serverIp = "127.0.0.1",
        port = 60001,
        key = randomKey(),
        onOutput = { _, _, _ -> },
        onDisconnect = recorder.callback(),
    )

    @Test
    fun `shutdown instruction closes with cleanExit true`() {
        val recorder = DisconnectRecorder()
        val t = transport(recorder)

        t.processInstruction(instruction(oldNum = 0, newNum = MoshTransport.SHUTDOWN_STATE_NUM))

        assertEquals(listOf(true), recorder.calls)
    }

    @Test
    fun `shutdown instruction fires disconnect only once`() {
        val recorder = DisconnectRecorder()
        val t = transport(recorder)

        t.processInstruction(instruction(oldNum = 0, newNum = MoshTransport.SHUTDOWN_STATE_NUM))
        t.processInstruction(instruction(oldNum = 0, newNum = MoshTransport.SHUTDOWN_STATE_NUM))

        assertEquals(listOf(true), recorder.calls)
    }

    @Test
    fun `ordinary instruction does not disconnect`() {
        val recorder = DisconnectRecorder()
        val t = transport(recorder)

        t.processInstruction(instruction(oldNum = 0, newNum = 1))

        assertEquals(emptyList<Boolean>(), recorder.calls)
        t.close()
    }

    @Test
    fun `stall flow starts null`() {
        val recorder = DisconnectRecorder()
        val t = transport(recorder)

        assertNull(t.stallSeconds.value)
        t.close()
    }
}

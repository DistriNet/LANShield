package tech.httptoolkit.android.vpn

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.httptoolkit.android.vpn.socket.ICloseSession
import tech.httptoolkit.android.vpn.transport.ip.IPAddress
import java.nio.ByteBuffer

/** Pure-JVM tests for [Session]'s send/receive buffers and sequence bookkeeping. */
class SessionStateMachineTest {

    private fun newSession(): Session = Session(
        SessionProtocol.TCP,
        IPAddress(byteArrayOf(10, 0, 0, 2)), 50000,
        IPAddress(byteArrayOf(8, 8, 8, 8)), 443,
        ICloseSession { /* no-op */ },
    )

    @Test
    fun `sending buffer accumulates and drains`() {
        val session = newSession()
        assertThat(session.hasDataToSend()).isFalse()

        val added = session.setSendingData(ByteBuffer.wrap("hello".toByteArray()))
        assertThat(added).isEqualTo(5)
        assertThat(session.hasDataToSend()).isTrue()

        assertThat(String(session.getSendingData())).isEqualTo("hello")
        assertThat(session.hasDataToSend()).isFalse()
    }

    @Test
    fun `received buffer returns up to maxSize and keeps the remainder`() {
        val session = newSession()
        session.addReceivedData("abcdef".toByteArray())
        assertThat(session.hasReceivedData()).isTrue()

        assertThat(String(session.getReceivedData(4))).isEqualTo("abcd")
        // remainder is preserved for the next drain
        assertThat(String(session.getReceivedData(10))).isEqualTo("ef")
        assertThat(session.hasReceivedData()).isFalse()
    }

    @Test
    fun `sequence numbers round-trip`() {
        val session = newSession()
        session.recSequence = 1000L
        session.setSendNext(2000L)
        session.sendUnack = 1500L

        assertThat(session.recSequence).isEqualTo(1000L)
        assertThat(session.sendNext).isEqualTo(2000L)
        assertThat(session.sendUnack).isEqualTo(1500L)
    }
}

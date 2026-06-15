package org.distrinet.lanshield.vpnservice

import com.google.common.truth.Truth.assertThat
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.Policy.ALLOW
import org.distrinet.lanshield.Policy.BLOCK
import org.distrinet.lanshield.Policy.DEFAULT
import org.junit.Test
import java.net.InetAddress

/**
 * Pure-JVM tests for the packet forwarding decision engine. This is a
 * behaviour-preserving extraction of the logic previously inline in
 * [VPNRunnable.shouldForwardPacket]; the cases below double as the executable
 * specification for that decision matrix.
 */
class PolicyEngineTest {

    private val unicast: InetAddress = InetAddress.getByName("8.8.8.8")
    private val multicast: InetAddress = InetAddress.getByName("239.1.1.1")
    private val broadcast: InetAddress = InetAddress.getByName("255.255.255.255")
    private val ipv6Multicast: InetAddress = InetAddress.getByName("ff02::1")

    /** Builds an input with sensible "ordinary unicast, default ALLOW" defaults. */
    private fun input(
        isTcpOrUdp: Boolean = true,
        hasValidUid: Boolean = true,
        destAddress: InetAddress = unicast,
        destPort: Int = 443,
        perAppPolicy: Policy = DEFAULT,
        isSystemApp: Boolean = false,
        defaultForwardPolicy: Policy = ALLOW,
        systemAppsForwardPolicy: Policy = ALLOW,
        allowMulticast: Boolean = false,
        allowDns: Boolean = false,
        hideDnsNotifications: Boolean = false,
        hideMulticastNotifications: Boolean = false,
    ) = PacketDecisionInput(
        isTcpOrUdp = isTcpOrUdp,
        hasValidUid = hasValidUid,
        destAddress = destAddress,
        destPort = destPort,
        perAppPolicy = perAppPolicy,
        isSystemApp = isSystemApp,
        defaultForwardPolicy = defaultForwardPolicy,
        systemAppsForwardPolicy = systemAppsForwardPolicy,
        allowMulticast = allowMulticast,
        allowDns = allowDns,
        hideDnsNotifications = hideDnsNotifications,
        hideMulticastNotifications = hideMulticastNotifications,
    )

    @Test
    fun `non TCP or UDP yields DEFAULT and no notification`() {
        val decision = PolicyEngine.decide(input(isTcpOrUdp = false))
        assertThat(decision.appliedPolicy).isEqualTo(DEFAULT)
        assertThat(decision.shouldNotify).isFalse()
    }

    @Test
    fun `invalid uid multicast with default ALLOW is allowed`() {
        val decision = PolicyEngine.decide(
            input(hasValidUid = false, destAddress = multicast, defaultForwardPolicy = ALLOW)
        )
        assertThat(decision.appliedPolicy).isEqualTo(ALLOW)
        assertThat(decision.shouldNotify).isFalse()
    }

    @Test
    fun `invalid uid multicast with default BLOCK and multicast disallowed is blocked`() {
        val decision = PolicyEngine.decide(
            input(
                hasValidUid = false,
                destAddress = multicast,
                defaultForwardPolicy = BLOCK,
                allowMulticast = false,
            )
        )
        assertThat(decision.appliedPolicy).isEqualTo(BLOCK)
    }

    @Test
    fun `invalid uid multicast with default BLOCK but multicast allowed is allowed`() {
        val decision = PolicyEngine.decide(
            input(
                hasValidUid = false,
                destAddress = multicast,
                defaultForwardPolicy = BLOCK,
                allowMulticast = true,
            )
        )
        assertThat(decision.appliedPolicy).isEqualTo(ALLOW)
    }

    @Test
    fun `invalid uid broadcast is treated as multicast`() {
        val decision = PolicyEngine.decide(
            input(
                hasValidUid = false,
                destAddress = broadcast,
                defaultForwardPolicy = BLOCK,
                allowMulticast = false,
            )
        )
        assertThat(decision.appliedPolicy).isEqualTo(BLOCK)
    }

    @Test
    fun `ipv6 multicast is recognised`() {
        assertThat(PolicyEngine.isMulticastDest(ipv6Multicast)).isTrue()
    }

    @Test
    fun `invalid uid DNS honours the allowDns and default settings`() {
        assertThat(
            PolicyEngine.decide(
                input(hasValidUid = false, destPort = 53, defaultForwardPolicy = BLOCK, allowDns = false)
            ).appliedPolicy
        ).isEqualTo(BLOCK)

        assertThat(
            PolicyEngine.decide(
                input(hasValidUid = false, destPort = 53, defaultForwardPolicy = BLOCK, allowDns = true)
            ).appliedPolicy
        ).isEqualTo(ALLOW)

        assertThat(
            PolicyEngine.decide(
                input(hasValidUid = false, destPort = 53, defaultForwardPolicy = ALLOW)
            ).appliedPolicy
        ).isEqualTo(ALLOW)
    }

    @Test
    fun `invalid uid ordinary unicast falls through to DEFAULT`() {
        val decision = PolicyEngine.decide(input(hasValidUid = false, destPort = 443))
        assertThat(decision.appliedPolicy).isEqualTo(DEFAULT)
        assertThat(decision.shouldNotify).isFalse()
    }

    @Test
    fun `explicit per-app ALLOW wins and never notifies`() {
        val decision = PolicyEngine.decide(input(perAppPolicy = ALLOW))
        assertThat(decision.appliedPolicy).isEqualTo(ALLOW)
        assertThat(decision.shouldNotify).isFalse()
    }

    @Test
    fun `explicit per-app BLOCK wins and never notifies`() {
        val decision = PolicyEngine.decide(input(perAppPolicy = BLOCK))
        assertThat(decision.appliedPolicy).isEqualTo(BLOCK)
        assertThat(decision.shouldNotify).isFalse()
    }

    @Test
    fun `default per-app non-system unicast with default ALLOW notifies`() {
        val decision = PolicyEngine.decide(
            input(perAppPolicy = DEFAULT, isSystemApp = false, defaultForwardPolicy = ALLOW)
        )
        assertThat(decision.appliedPolicy).isEqualTo(DEFAULT)
        assertThat(decision.shouldNotify).isTrue()
    }

    @Test
    fun `system app uses the system apps policy when default is not ALLOW`() {
        val decision = PolicyEngine.decide(
            input(
                perAppPolicy = DEFAULT,
                isSystemApp = true,
                defaultForwardPolicy = BLOCK,
                systemAppsForwardPolicy = BLOCK,
            )
        )
        assertThat(decision.appliedPolicy).isEqualTo(BLOCK)
        assertThat(decision.shouldNotify).isTrue()
    }

    @Test
    fun `multicast can override a system-app BLOCK to ALLOW preserving precedence`() {
        // System app -> BLOCK, then the multicast branch resolves to ALLOW and,
        // because the running policy is not already ALLOW, overrides it to ALLOW.
        val decision = PolicyEngine.decide(
            input(
                perAppPolicy = DEFAULT,
                isSystemApp = true,
                destAddress = multicast,
                defaultForwardPolicy = BLOCK,
                systemAppsForwardPolicy = BLOCK,
                allowMulticast = true,
            )
        )
        assertThat(decision.appliedPolicy).isEqualTo(ALLOW)
    }

    @Test
    fun `an already ALLOW policy is not downgraded by the multicast branch`() {
        val decision = PolicyEngine.decide(
            input(
                perAppPolicy = DEFAULT,
                isSystemApp = true,
                destAddress = multicast,
                defaultForwardPolicy = BLOCK,
                systemAppsForwardPolicy = ALLOW,
                allowMulticast = false,
            )
        )
        assertThat(decision.appliedPolicy).isEqualTo(ALLOW)
    }

    @Test
    fun `DNS notification is hidden only when default is ALLOW and hideDnsNotifications is set`() {
        val hidden = PolicyEngine.decide(
            input(destPort = 53, defaultForwardPolicy = ALLOW, hideDnsNotifications = true)
        )
        assertThat(hidden.appliedPolicy).isEqualTo(ALLOW)
        assertThat(hidden.shouldNotify).isFalse()

        // hiding does not apply when the default policy is BLOCK
        val notHidden = PolicyEngine.decide(
            input(destPort = 53, defaultForwardPolicy = BLOCK, allowDns = false, hideDnsNotifications = true)
        )
        assertThat(notHidden.appliedPolicy).isEqualTo(BLOCK)
        assertThat(notHidden.shouldNotify).isTrue()
    }

    @Test
    fun `multicast notification is hidden only when default is ALLOW and hideMulticastNotifications is set`() {
        val hidden = PolicyEngine.decide(
            input(destAddress = multicast, defaultForwardPolicy = ALLOW, hideMulticastNotifications = true)
        )
        assertThat(hidden.shouldNotify).isFalse()
    }

    @Test
    fun `either hidden flag suppresses notification for DNS over a multicast address`() {
        val decision = PolicyEngine.decide(
            input(
                destAddress = multicast,
                destPort = 53,
                defaultForwardPolicy = ALLOW,
                hideDnsNotifications = true,
                hideMulticastNotifications = false,
            )
        )
        assertThat(decision.appliedPolicy).isEqualTo(ALLOW)
        assertThat(decision.shouldNotify).isFalse()
    }

    @Test
    fun `shouldForward folds the applied policy and the default policy`() {
        // ALLOW always forwards, regardless of the default policy.
        assertThat(PolicyEngine.decide(input(perAppPolicy = ALLOW, defaultForwardPolicy = BLOCK)).shouldForward)
            .isTrue()
        // BLOCK never forwards.
        assertThat(PolicyEngine.decide(input(perAppPolicy = BLOCK, defaultForwardPolicy = ALLOW)).shouldForward)
            .isFalse()
        // DEFAULT defers to the default policy.
        assertThat(PolicyEngine.decide(input(perAppPolicy = DEFAULT, defaultForwardPolicy = ALLOW)).shouldForward)
            .isTrue()
        assertThat(PolicyEngine.decide(input(perAppPolicy = DEFAULT, defaultForwardPolicy = BLOCK)).shouldForward)
            .isFalse()
    }

    @Test
    fun `non TCP or UDP forwards iff the default policy is ALLOW`() {
        assertThat(PolicyEngine.decide(input(isTcpOrUdp = false, defaultForwardPolicy = ALLOW)).shouldForward)
            .isTrue()
        assertThat(PolicyEngine.decide(input(isTcpOrUdp = false, defaultForwardPolicy = BLOCK)).shouldForward)
            .isFalse()
    }
}

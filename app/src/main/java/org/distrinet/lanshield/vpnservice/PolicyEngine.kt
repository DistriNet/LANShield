package org.distrinet.lanshield.vpnservice

import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.Policy.ALLOW
import org.distrinet.lanshield.Policy.BLOCK
import org.distrinet.lanshield.Policy.DEFAULT
import java.net.InetAddress


data class PacketDecisionInput(
    /** True for TCP/UDP, i.e. protocols whose owning app uid can be looked up. */
    val isTcpOrUdp: Boolean,
    /** True when a usable app uid was found (uid != -1, != 1000, != 0). */
    val hasValidUid: Boolean,
    val destAddress: InetAddress,
    val destPort: Int,
    /** The per-app policy; only meaningful when [hasValidUid] is true. */
    val perAppPolicy: Policy,
    /** Whether the owning app is a system app; only meaningful when [hasValidUid]. */
    val isSystemApp: Boolean,
    val defaultForwardPolicy: Policy,
    val systemAppsForwardPolicy: Policy,
    val allowMulticast: Boolean,
    val allowDns: Boolean,
    val hideDnsNotifications: Boolean,
    val hideMulticastNotifications: Boolean,
)

/**
 * The outcome of a policy decision.
 *
 * @param appliedPolicy the policy resolved for the packet.
 * @param shouldForward whether the packet should actually be forwarded
 * @param shouldNotify whether we should show a push notification for this packet.
 */
data class PacketDecision(
    val appliedPolicy: Policy,
    val shouldForward: Boolean,
    val shouldNotify: Boolean,
)

object PolicyEngine {

    fun isMulticastDest(addr: InetAddress): Boolean =
        addr.isMulticastAddress || addr.hostAddress == "255.255.255.255"

    fun decide(input: PacketDecisionInput): PacketDecision {
        if (!input.isTcpOrUdp) {
            // We can only look up the app's uid for TCP and UDP packets.
            // For other protocols we use the default policy.
            return resolveDefaultPolicy(DEFAULT, input.defaultForwardPolicy, shouldNotify = false)
        }

        val isMulticast = isMulticastDest(input.destAddress)
        val isDns = input.destPort == 53

        if (!input.hasValidUid) {
            val policy = when {
                isMulticast ->
                    if (input.defaultForwardPolicy == BLOCK && !input.allowMulticast) BLOCK else ALLOW
                isDns ->
                    if (input.defaultForwardPolicy == BLOCK && !input.allowDns) BLOCK else ALLOW
                else -> DEFAULT
            }
            return resolveDefaultPolicy(policy, input.defaultForwardPolicy, shouldNotify = false)
        }

        // hasValidUid == true
        var appliedPolicy = input.perAppPolicy
        var shouldNotify = false

        if (input.perAppPolicy == DEFAULT) {
            if (input.defaultForwardPolicy != ALLOW && input.isSystemApp) { // System app
                appliedPolicy = input.systemAppsForwardPolicy
            }
            if (isMulticast) { // Multicast
                val multicastPolicy =
                    if (input.defaultForwardPolicy == BLOCK && !input.allowMulticast) BLOCK else ALLOW
                appliedPolicy = if (appliedPolicy == ALLOW) ALLOW else multicastPolicy
            }
            if (isDns) { // DNS
                val dnsPolicy =
                    if (input.defaultForwardPolicy == BLOCK && !input.allowDns) BLOCK else ALLOW
                appliedPolicy = if (appliedPolicy == ALLOW) ALLOW else dnsPolicy
            }

            val isDnsNotificationHidden =
                input.defaultForwardPolicy == ALLOW && isDns && input.hideDnsNotifications
            val isMulticastNotificationHidden =
                input.defaultForwardPolicy == ALLOW && isMulticast && input.hideMulticastNotifications

            shouldNotify = !isDnsNotificationHidden && !isMulticastNotificationHidden
        }

        return resolveDefaultPolicy(appliedPolicy, input.defaultForwardPolicy, shouldNotify)
    }

    private fun resolveDefaultPolicy(
        appliedPolicy: Policy,
        defaultForwardPolicy: Policy,
        shouldNotify: Boolean,
    ): PacketDecision {
        val shouldForward = appliedPolicy == ALLOW ||
            (appliedPolicy == DEFAULT && defaultForwardPolicy == ALLOW)
        return PacketDecision(appliedPolicy, shouldForward, shouldNotify)
    }
}

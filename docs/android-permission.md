# <div align="center">Android Local Network Permission</div>

<a id="toc"></a>
## 1. Table of Contents

1. [Table of Contents](#toc)
2. [Android's New Opt-In Permission](#permission)
3. [Local Network Definition](#definition)
4. [Security Impact of Local Network Definition](#impact)
5. [Android's Implementation Using eBPF](#implementation)


<a id="permission"></a>
## 2. Android's New Opt-In Permission

This document is an overview of how Android's new opt-in Local Network Permission (LPN) has been implemented and what its properties are.
This is done as part of our [LANShield project](https://lanshield.eu/).

The official Android documentation can be found on their page [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission).
Summarized, Android 16 now provides an opt-in permission to control access to the local network. This opt-in permission can be enabled
for a specific app using the following command:

	adb shell am compat enable RESTRICT_LOCAL_NETWORK <package_name>

Note that this `am compat` override is blocked on production ("user") builds unless the target app is set to be debuggable.
This means that this opt-in is only intended for developers and is not meant for ordinary users to already increase their protection.

Once the above feature is enabled, **and after restarting your devices**, local network access will be blocked unless the
`NEARBY_WIFI_DEVICES` permission is also granted in the app's manifest. Examples of errors you can get when local network
access is blocked are the following:

	sendto failed: EPERM (Operation not permitted)
	sendto failed: ECONNABORTED (Operation not permitted)

Google also provides an explicit definition of what is considered to be local network access in their
[Local Network Definition](https://developer.android.com/privacy-and-security/local-network-definition) page.
Notably, local network access excludes cellular (WWAN) and VPN connections, and instead covers broadcast-capable
network interfaces such as Wi-Fi or Ethernet and their associated subnets.


<a id="definition"></a>
## 3. Local Network Definition

When inspecting Android's implementation, the exact definition of what is considered local network access is more
subtle than simply blocking or allowing a pre-defined range of IP addresses. More precisely, the function
[`updateLocalNetworkAddresses`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/service/src/com/android/server/ConnectivityService.java#9899)
configures what precisely is considered local network access and hence which traffic requires the appropriate permissions.
Summarized, this function configures the following:

- The subnets defined by the global constant [`MULTICAST_AND_BROADCAST_PREFIXES`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/staticlibs/framework/com/android/net/module/util/NetworkStackConstants.java#356)
  are considered local network access (see below).

- Traffic to the local DNS server(s) on ports UDP/53, TCP/53, and TCP/853 is always allowed.

- For each IPv6 address assigned to an interface, the associated IPv6 subnet is considered local network access.

- For each IPv4 address assigned to an interface, if this address is within one of the hardcoded subnets defined by the global constant
  [`IPV4_LOCAL_PREFIXES`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/staticlibs/framework/com/android/net/module/util/NetworkStackConstants.java#345),
  then all traffic towards that hardcoded subnet is considered local network access.

The above means that for IPv4, only the following subnets are ever considered local network access:

    /**
     * List of IpPrefix that are local network prefixes.
	   */
    public static final List<IpPrefix> IPV4_LOCAL_PREFIXES = List.of(
            new IpPrefix("169.254.0.0/16"), // Link Local
            new IpPrefix("100.64.0.0/10"),  // CGNAT
            new IpPrefix("10.0.0.0/8"),     // RFC1918
            new IpPrefix("172.16.0.0/12"),  // RFC1918
            new IpPrefix("192.168.0.0/16")  // RFC1918
    );
    
    /**
     * List of IpPrefix that are multicast and broadcast prefixes.
     */
    public static final List<IpPrefix> MULTICAST_AND_BROADCAST_PREFIXES = List.of(
              new IpPrefix("224.0.0.0/4"),               // Multicast
              new IpPrefix("ff00::/8"),                  // Multicast
              new IpPrefix("255.255.255.255/32")         // Broadcast
    );

However, each of these `IPV4_LOCAL_PREFIXES` subnets is only added if an interface's IP address is part of that subnet.
For instance, if the current local network uses the range 172.16.0.0/12, then communication to 192.168.0.0/16 is _not_
considered local network access. This is due to the following code:

	/**
	 * Filters IpPrefix that are local prefixes and LinkAddress is part of them.
	 * @param linkAddress link address used for filtering
	 * @return list of IpPrefix that are local addresses.
	 */
	private List<IpPrefix> getLocalNetworkPrefixesForAddress(LinkAddress linkAddress) {
		List<IpPrefix> localPrefixes = new ArrayList<>();
		if (linkAddress.isIpv6()) {
		    // For IPv6, if the prefix length is greater than zero then they are part of local
		    // network
		    if (linkAddress.getPrefixLength() != 0) {
		        localPrefixes.add(
		                new IpPrefix(linkAddress.getAddress(), linkAddress.getPrefixLength()));
		    }
		} else {
		    // For IPv4, if the linkAddress is part of IpPrefix adding prefix to result.
		    for (IpPrefix ipv4LocalPrefix : IPV4_LOCAL_PREFIXES) {
		        if (ipv4LocalPrefix.containsPrefix(
		                new IpPrefix(linkAddress.getAddress(), linkAddress.getPrefixLength()))) {
		            localPrefixes.add(ipv4LocalPrefix);
		        }
		    }
		}
		return localPrefixes;
	}

The code that adds an exception for traffic to the local DNS server is the following:

	/**
	 * Adds list of prefixes(addresses) to local network access map.
	 * @param iface interface name
	 * @param prefixes list of prefixes/addresses
	 * @param lp LinkProperties
	 */
	private void addLocalAddressesToBpfMap(final String iface, final List<IpPrefix> prefixes,
	                                @Nullable final LinkProperties lp) {
		if (!BpfNetMaps.isAtLeast25Q2()) return;

		for (IpPrefix prefix : prefixes) {
		    // Add local dnses allow rule To BpfMap before adding the block rule for prefix
		    addLocalDnsesToBpfMap(iface, prefix, lp);
		    /*
		    Prefix length is used by LPM trie map(local_net_access_map) for performing longest
		    prefix matching, this length represents the maximum number of bits used for matching.
		    The interface index should always be matched which is 32-bit integer. For IPv6, prefix
		    length is calculated by adding the ip address prefix length along with interface index
		    making it (32 + length). IPv4 addresses are stored as ipv4-mapped-ipv6 which implies
		    first 96 bits are common for all ipv4 addresses. Hence, prefix length is calculated as
		    32(interface index) + 96 (common for ipv4-mapped-ipv6) + length.
		     */
		    final int prefixLengthConstant = (prefix.isIPv4() ? (32 + 96) : 32);
		    mBpfNetMaps.addLocalNetAccess(prefixLengthConstant + prefix.getPrefixLength(),
		            iface, prefix.getAddress(), 0, 0, false);

		}
	}


<a id="impact"></a>
## 4. Security Impact of Local Network Definition

Android's definition of local network access has two possible security impacts:

- If the local network is using a non-[RFC1918](https://datatracker.ietf.org/doc/html/rfc1918#section-3) IPv4 subnet,
  then any connections to this local network are _not_ considered local network access by Android. This is because for
  IPv4, only subnets in `IPV4_LOCAL_PREFIXES` are ever considered local network access.

- If a user is, for instance, using their ISP's router with a subnet in 192.168.0.0/16, and a second custom router with
  a subnet in 172.16.0.0/12, then communication between these two local networks is _not_ considered local network access.
  This can be considered a less severe case of the same flaw in how iOS defines the local network, see Section 6.2.5 in
  our paper ["LANShield: Analysing and Protecting Local Network Access on Mobile Devices"](https://papers.mathyvanhoef.com/pets2025.pdf).

We confirmed this behavior on a Samsung S24 using a custom app that transmitted a UDP packet to the above two subnet ranges
and using tcpdump on the router to monitor the traffic sent by the phone.


<a id="implementation"></a>
## 5. Android's Implementation Using eBPF

The enforcement of local network access restrictions happens using eBPF modules. For a general introduction to Android's usage of eBPF,
see the presentation [eBPF use in Android Networking](https://lpc.events/event/4/contributions/411/attachments/354/585/2019_LPC_Lisbon__eBPF_use_in_Android_Networking.pdf)
and [Meizu's eBPF blogpost](https://kernel.meizu.com/2023/11/06/Optimization-practice-and-exploration-of-Android-devices-based-on-eBPF/).
Summarized, eBPF modules are a restricted type of code that can hook kernel functions and safely execute code in the kernel.
Communication with user-space programs is performed using _maps_, where both the eBPF module and the user-space program can add, lookup,
and delete entries in a map. Various types of maps can be used.

Enforcement of local network access happens in `netd.c`, which defines eBPF programs to filter traffic and, in particular, in the
[`should_block_local_network_packets`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/bpf/progs/netd.c#259)
function. This function first uses the `local_net_blocked_uid_map` map to determine whether local network access must be blocked for
the UID associated with the packet being handled:

	DEFINE_BPF_MAP_EXT(local_net_blocked_uid_map, HASH, uint32_t, bool, -1000,
		           AID_ROOT, AID_NET_BW_ACCT, 0060, "fs_bpf_net_shared", "", PRIVATE,
		           BPFLOADER_MAINLINE_25Q2_VERSION, BPFLOADER_MAX_VER, LOAD_ON_ENG, LOAD_ON_USER,
		           LOAD_ON_USERDEBUG, 0)

	// ...

	static __always_inline inline bool should_block_local_network_packets(struct __sk_buff *skb,
		                           const uint32_t uid, const struct egress_bool egress,
		                           const struct kver_uint kver) {
	    if (is_system_uid(uid)) return false;

	    bool* block_local_net = bpf_local_net_blocked_uid_map_lookup_elem(&uid);
	    if (!block_local_net) return false; // uid not found in map
	    if (!*block_local_net) return false; // lookup returned 'bool false'

Put differently, if this map returns true, then local network traffic must be blocked. This map is managed from user-space in the file
[`BpfNetMaps.java`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/service/src/com/android/server/BpfNetMaps.java#1014).
For instance, there are functions to remove and add entries from this map:

	/**
	 * Add uid to local_net_blocked_uid map.
	 * @param uid application uid that needs to block local network calls.
	 */
	@RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
	public void addUidToLocalNetBlockMap(final int uid) {
	    throwIfPre25Q2("addUidToLocalNetBlockMap is not available on pre-B devices");
	    try {
		sLocalNetBlockedUidMap.updateEntry(new U32(uid), new Bool(true));
	    } catch (ErrnoException e) {
		Log.e(TAG, "Failed to add local network blocked for uid : " + uid);
	    }
	}

Whether an app is added or not to this map is determined based on its permissions, see
[PermissionMonitor.java](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/service/src/com/android/server/connectivity/PermissionMonitor.java#307):

	void setLocalNetworkPermissions(final int uid, @Nullable final String packageName) {
	    // ...
	    if (permissionState == PermissionManager.PERMISSION_GRANTED) {
		mBpfNetMaps.removeUidFromLocalNetBlockMap(attributionSource.getUid());
	    } else {
		mBpfNetMaps.addUidToLocalNetBlockMap(attributionSource.getUid());
	    }

If the uid was added to this map, meaning its local network access should be blocked, this function will
subsequently inspect the traffic, where it performs all filtering based on IPv6 addresses:
any IPv4 addresses are first converted into an IPv4-mapped IPv6 address of the form `::FFFF:IPv4_ADDRESS`.
Filtering is then done based on the destination IPv6 address, the protocol (TCP, UDP, etc), and the remote port, if any.
This filtering is done by looking up this information in an
[`LPM_TRIE` map](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/bpf/progs/netd.c#102).
This map is declared using helper macros from
[`bpf_helpers.h`](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/bpf/headers/include/bpf_helpers.h#349)
and essentially provides a longest prefix match algorithm that can be used to match IP addresses to a stored set of prefixes:

	// False iff arguments are found with longest prefix match lookup and disallowed.
	static inline __always_inline bool is_local_net_access_allowed(const uint32_t if_index,
		const struct in6_addr* remote_ip6, const uint16_t protocol, const __be16 remote_port) {
	    LocalNetAccessKey query_key = {
		.lpm_bitlen = 8 * (sizeof(if_index) + sizeof(*remote_ip6) + sizeof(protocol)
		    + sizeof(remote_port)),
		.if_index = if_index,
		.remote_ip6 = *remote_ip6,
		.protocol = protocol,
		.remote_port = remote_port
	    };
	    bool* v = bpf_local_net_access_map_lookup_elem(&query_key);
	    return v ? *v : true;
	}

**Summary**: The eBPF programs running in the kernel block local network access in two steps.
First, a map is used to determine whether local network traffic should be blocked for the app.
If so, a second map is used to look up whether the destination, protocol, and port should be
considered local network access, and if so, the packet gets dropped.

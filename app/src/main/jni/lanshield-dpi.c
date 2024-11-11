#include <ndpi_api.h>
#include <jni.h>

struct ndpi_detection_module_struct *ndpi_module = NULL;
NDPI_PROTOCOL_BITMASK ndpi_protocols_bitmask;

struct ndpi_flow_struct ndpi_flow;
static ndpi_serializer json_serializer = {};

static char protocol_name[64];

// protocols which are not application protocols
static void init_ndpi_protocols_bitmask(ndpi_protocol_bitmask_struct_t *b) {
    NDPI_ZERO(b);

    // https://github.com/ntop/nDPI/blob/dev/src/include/ndpi_protocol_ids.h
    NDPI_SET(b, NDPI_PROTOCOL_FTP_CONTROL);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_POP);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_SMTP);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_IMAP);
    NDPI_SET(b, NDPI_PROTOCOL_DNS);
    NDPI_SET(b, NDPI_PROTOCOL_IPP);
    NDPI_SET(b, NDPI_PROTOCOL_HTTP);
    NDPI_SET(b, NDPI_PROTOCOL_MDNS);
    NDPI_SET(b, NDPI_PROTOCOL_NTP);
    NDPI_SET(b, NDPI_PROTOCOL_NETBIOS);
    NDPI_SET(b, NDPI_PROTOCOL_NFS);
    NDPI_SET(b, NDPI_PROTOCOL_SSDP);
    NDPI_SET(b, NDPI_PROTOCOL_SNMP);
    NDPI_SET(b, NDPI_PROTOCOL_SMBV1);
    NDPI_SET(b, NDPI_PROTOCOL_SYSLOG);
    NDPI_SET(b, NDPI_PROTOCOL_DHCP);
    NDPI_SET(b, NDPI_PROTOCOL_POSTGRES);
    NDPI_SET(b, NDPI_PROTOCOL_MYSQL);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_POPS);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_SMTPS);
    NDPI_SET(b, NDPI_PROTOCOL_DTLS);
    NDPI_SET(b, NDPI_PROTOCOL_BITTORRENT);
    NDPI_SET(b, NDPI_PROTOCOL_SMBV23);
    NDPI_SET(b, NDPI_PROTOCOL_RTSP);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_IMAPS);
    NDPI_SET(b, NDPI_PROTOCOL_IRC);
    NDPI_SET(b, NDPI_PROTOCOL_NATS);
    NDPI_SET(b, NDPI_PROTOCOL_TELNET);
    NDPI_SET(b, NDPI_PROTOCOL_STUN);
    NDPI_SET(b, NDPI_PROTOCOL_IP_IPSEC);
    NDPI_SET(b, NDPI_PROTOCOL_IP_GRE);
    NDPI_SET(b, NDPI_PROTOCOL_RTP);
    NDPI_SET(b, NDPI_PROTOCOL_RDP);
    NDPI_SET(b, NDPI_PROTOCOL_VNC);
    NDPI_SET(b, NDPI_PROTOCOL_TLS);
    NDPI_SET(b, NDPI_PROTOCOL_SSH);
    NDPI_SET(b, NDPI_PROTOCOL_TFTP);
    NDPI_SET(b, NDPI_PROTOCOL_STEALTHNET);
    NDPI_SET(b, NDPI_PROTOCOL_SIP);
    NDPI_SET(b, NDPI_PROTOCOL_DHCPV6);
    NDPI_SET(b, NDPI_PROTOCOL_KERBEROS);
    NDPI_SET(b, NDPI_PROTOCOL_PPTP);
    NDPI_SET(b, NDPI_PROTOCOL_NETFLOW);
    NDPI_SET(b, NDPI_PROTOCOL_SFLOW);
    NDPI_SET(b, NDPI_PROTOCOL_HTTP_CONNECT);
    NDPI_SET(b, NDPI_PROTOCOL_HTTP_PROXY);
    NDPI_SET(b, NDPI_PROTOCOL_RADIUS);
    NDPI_SET(b, NDPI_PROTOCOL_TEAMVIEWER);
    NDPI_SET(b, NDPI_PROTOCOL_OPENVPN);
    NDPI_SET(b, NDPI_PROTOCOL_CISCOVPN);
    NDPI_SET(b, NDPI_PROTOCOL_TOR);
    NDPI_SET(b, NDPI_PROTOCOL_RTCP);
    NDPI_SET(b, NDPI_PROTOCOL_SOCKS);
    NDPI_SET(b, NDPI_PROTOCOL_RTMP);
    NDPI_SET(b, NDPI_PROTOCOL_FTP_DATA);
    NDPI_SET(b, NDPI_PROTOCOL_ZMQ);
    NDPI_SET(b, NDPI_PROTOCOL_REDIS);
    NDPI_SET(b, NDPI_PROTOCOL_QUIC);
    NDPI_SET(b, NDPI_PROTOCOL_WIREGUARD);
    NDPI_SET(b, NDPI_PROTOCOL_DNSCRYPT);
    NDPI_SET(b, NDPI_PROTOCOL_TINC);
    NDPI_SET(b, NDPI_PROTOCOL_DNSCRYPT);
    NDPI_SET(b, NDPI_PROTOCOL_MQTT);
    NDPI_SET(b, NDPI_PROTOCOL_RX);
    NDPI_SET(b, NDPI_PROTOCOL_GIT);
    NDPI_SET(b, NDPI_PROTOCOL_WEBSOCKET);
    NDPI_SET(b, NDPI_PROTOCOL_Z3950);
    NDPI_SET(b, NDPI_PROTOCOL_IP_IPSEC);
    NDPI_SET(b, NDPI_PROTOCOL_IP_GRE);
    NDPI_SET(b, NDPI_PROTOCOL_IP_ICMP);
    NDPI_SET(b, NDPI_PROTOCOL_IP_IGMP);
    NDPI_SET(b, NDPI_PROTOCOL_IP_EGP);
    NDPI_SET(b, NDPI_PROTOCOL_IP_SCTP);
    NDPI_SET(b, NDPI_PROTOCOL_IP_OSPF);
    NDPI_SET(b, NDPI_PROTOCOL_IP_IP_IN_IP);
}


static int init_dpi(){
    if (ndpi_module != NULL) {
        return -1;
    }

    if(ndpi_init_serializer(&json_serializer, ndpi_serialization_format_json) != 0) {
        return -1;
    }

    ndpi_module = ndpi_init_detection_module(ndpi_no_prefs);
    if (ndpi_module == NULL) {
        return -1;
    }
    init_ndpi_protocols_bitmask(&ndpi_protocols_bitmask);
    ndpi_set_protocol_detection_bitmask2(ndpi_module, &ndpi_protocols_bitmask);
    ndpi_finalize_initialization(ndpi_module);

    return 0;
}

static void free_dpi() {
    if (ndpi_module != NULL) {
        ndpi_exit_detection_module(ndpi_module);
        ndpi_module = NULL;
    }
}

JNIEXPORT void JNICALL
Java_org_distrinet_lanshield_vpnservice_dpi_00024Companion_terminateNDPI(JNIEnv *env,
                                                                         jobject thiz) {
    free_dpi();
}


JNIEXPORT jint JNICALL
Java_org_distrinet_lanshield_vpnservice_dpi_00024Companion_doDPI(JNIEnv *env, jobject thiz,
                                                                 jbyteArray packet,
                                                                 jint packet_size,
                                                                 jint packet_offset,
                                                                 jobject dpi_result) {
    uint8_t protocol_was_guessed;

    if (ndpi_module == NULL) {
        int err = init_dpi();
        if(err) return err;
    }

    jbyte *pkt_data_no_offset = (*env)->GetByteArrayElements(env, packet, NULL);
    jbyte *pkt_data = pkt_data_no_offset + packet_offset;

    memset(&ndpi_flow, 0, SIZEOF_FLOW_STRUCT);
    memset(&protocol_name, 0, sizeof(protocol_name));
    ndpi_reset_serializer(&json_serializer);

    ndpi_protocol detected_protocol = ndpi_detection_process_packet(ndpi_module, &ndpi_flow, (unsigned char*) pkt_data, packet_size, 0);
    ndpi_protocol guessed_protocol = ndpi_detection_giveup(ndpi_module, &ndpi_flow, 1,  &protocol_was_guessed);

    ndpi_protocol selected_protocol = protocol_was_guessed ? guessed_protocol : detected_protocol;
    ndpi_dpi2json(ndpi_module, &ndpi_flow, selected_protocol, &json_serializer);
    ndpi_protocol2name(ndpi_module, selected_protocol, (char *) &protocol_name, sizeof(protocol_name));

    protocol_name[sizeof(protocol_name) - 1] = '\0';

    u_int32_t json_buffer_len = 0;
    char *json_buffer = ndpi_serializer_get_buffer(&json_serializer, &json_buffer_len);

    ndpi_free_flow_data(&ndpi_flow);

    (*env)->ReleaseByteArrayElements(env, packet, pkt_data_no_offset, 0);

    // Find and set the `jsonBuffer` field
    jclass dpiResultClass = (*env)->GetObjectClass(env, dpi_result);
    jfieldID jsonBufferField = (*env)->GetFieldID(env, dpiResultClass, "jsonBuffer", "Ljava/lang/String;");
    jstring jsonBufferJava = (*env)->NewStringUTF(env, json_buffer);
    (*env)->SetObjectField(env, dpi_result, jsonBufferField, jsonBufferJava);
    (*env)->DeleteLocalRef(env, jsonBufferJava);

    // Find and set the `protocolName` field
    jfieldID protocolNameField = (*env)->GetFieldID(env, dpiResultClass, "protocolName", "Ljava/lang/String;");
    jstring protocolNameJava = (*env)->NewStringUTF(env, protocol_name);
    (*env)->SetObjectField(env, dpi_result, protocolNameField, protocolNameJava);
    (*env)->DeleteLocalRef(env, protocolNameJava);

    return 0;
    }

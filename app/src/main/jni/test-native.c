#include <jni.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/endian.h>
#include <linux/in.h>
#include <stdio.h>
#include <arpa/inet.h>
#include <string.h>
#include <linux/inet_diag.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>

void test_netlink_get_sockets() {
    int sock_fd = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_SOCK_DIAG);
    if (sock_fd < 0) {
        return;  // Error creating socket
    }

    struct inet_diag_req_v2 request;
    memset(&request, 0, sizeof(request));
    request.sdiag_family = AF_INET;
    request.sdiag_protocol = IPPROTO_TCP;
    request.idiag_states = -1;

    struct sockaddr_nl sa;
    memset(&sa, 0, sizeof(sa));
    sa.nl_family = AF_NETLINK;

    struct msghdr msg;
    struct iovec iov;

    iov.iov_base = &request;
    iov.iov_len = sizeof(request);
    msg.msg_name = &sa;
    msg.msg_namelen = sizeof(sa);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    if (sendmsg(sock_fd, &msg, 0) < 0) {
        close(sock_fd);
        return;
    }

    char buffer[8192];
    ssize_t len = recv(sock_fd, buffer, sizeof(buffer), 0);
    if (len < 0) {
        close(sock_fd);
        return ;
    }

    struct nlmsghdr *nlh = (struct nlmsghdr *)buffer;
    while (NLMSG_OK(nlh, len)) {
        struct inet_diag_msg *diag_msg = (struct inet_diag_msg *)NLMSG_DATA(nlh);


        nlh = NLMSG_NEXT(nlh, len);
    }

    close(sock_fd);
    return;
}


void test_fwmark() {
    int fd;
    struct sockaddr_in address;
    const char *message = "Hello, World!";

    fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (fd < 0) {
        return;
    }

    int option_value = 0x16;
    if (setsockopt(fd, SOL_SOCKET, 36, &option_value, sizeof(option_value)) < 0) {
        close(fd);
        return;
    }

    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons(8000);
    if (inet_pton(AF_INET, "192.168.0.1", &address.sin_addr) <= 0) {
        close(fd);
        return;
    }

    if (sendto(fd, message, strlen(message), 0, (struct sockaddr*)&address, sizeof(address)) < 0) {
        close(fd);
        return;
    }


    close(fd);
    return;
}

#define PROC_NET_PATH "/proc/net/"
#define FILE_COUNT 4

// List of common files to check in /proc/net
const char *files_to_check[FILE_COUNT] = {"tcp", "udp", "tcp6", "udp6"};

// Function to check if files in /proc/net can be read
void check_proc_net_files() {
    char file_path[256];

    for (int i = 0; i < FILE_COUNT; i++) {
        // Construct the full path of the file
        snprintf(file_path, sizeof(file_path), "%s%s", PROC_NET_PATH, files_to_check[i]);

        // Try to open the file in read mode
        FILE *file = fopen(file_path, "r");

        if (file) {
            printf("Readable: %s\n", file_path);
            fclose(file);  // Close the file after checking
        } else {
            printf("Not readable: %s\n", file_path);
        }
    }
}

// Function to list all files in /proc/net and check if they can be read
void list_and_check_proc_net_files() {
    DIR *dir = opendir(PROC_NET_PATH);

    if (dir == NULL) {
        perror("Could not open /proc/net");
        return;
    }

    struct dirent *entry;
    char file_path[256];

    printf("Files in /proc/net and their read status:\n");

    while ((entry = readdir(dir)) != NULL) {
        // Skip "." and ".." entries
        if (entry->d_name[0] == '.' && (entry->d_name[1] == '\0' || (entry->d_name[1] == '.' && entry->d_name[2] == '\0')))
            continue;

        // Construct the full file path
        snprintf(file_path, sizeof(file_path), "%s%s", PROC_NET_PATH, entry->d_name);

        // Try to open the file in read mode
        FILE *file = fopen(file_path, "r");
        if (file) {
            printf("Readable: %s\n", file_path);
            fclose(file);  // Close the file after checking
        } else {
            printf("Not readable: %s\n", file_path);
        }
    }

    closedir(dir);
}



JNIEXPORT void JNICALL
Java_org_distrinet_lanshield_vpnservice_VPNRunnable_test_1native(JNIEnv *env, jobject thiz) {
    test_netlink_get_sockets();
    test_fwmark();
    check_proc_net_files();
    list_and_check_proc_net_files();
}
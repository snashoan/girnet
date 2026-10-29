/*
 * girchat / shnet - Dual-stack IPv6 peer mesh communicator & node runtime (Native C Edition)
 * Zero external dependencies. C99 / POSIX compliant.
 */

#define _GNU_SOURCE
#if defined(__APPLE__)
#define _DARWIN_C_SOURCE
#include <mach-o/dyld.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <ifaddrs.h>
#include <dirent.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <limits.h>
#include <signal.h>
#include <poll.h>
#include <fcntl.h>
#include <ctype.h>

#include "qrcodegen.h"

#define GIRCHAT_VERSION "1.1.0"
#define DEFAULT_PORT 8723

// ANSI 256-color definitions
#define ANSI_RESET "\033[0m"
#define ANSI_BOLD "\033[1m"
#define ANSI_DIM "\033[2m"
#define ANSI_ITALIC "\033[3m"

#define CLR_CYAN "\033[38;5;45m"
#define CLR_BLUE "\033[38;5;75m"
#define CLR_GREEN "\033[38;5;48m"
#define CLR_GOLD "\033[38;5;220m"
#define CLR_RED "\033[38;5;203m"
#define CLR_PURPLE "\033[38;5;141m"
#define CLR_ORANGE "\033[38;5;208m"
#define CLR_GRAY "\033[38;5;244m"
#define ANSI_WHITE_BG_BLACK_FG "\033[47;30m"

static char g_local_name[64] = "rei";
static char g_active_peer[256] = "";
static char g_peer_name[128] = "";
static bool g_is_host = true;
static char g_static_dir[2048] = "";
static int g_local_port = DEFAULT_PORT;
static volatile bool g_running = true;
#define MAX_PEERS 64

typedef struct {
    char url[256];
    char name[128];
    char version[32];
    time_t last_seen;
    bool online;
} PeerNode;

static PeerNode g_peers[MAX_PEERS];
static int g_peer_count = 0;
static int g_active_peer_idx = -1;

static pthread_mutex_t g_storage_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_peer_mutex = PTHREAD_MUTEX_INITIALIZER;

static inline void safe_write(int fd, const void *buf, size_t count) {
    if (write(fd, buf, count) < 0) { /* ignored */ }
}

static inline void safe_strncpy(char *dest, const char *src, size_t size) {
    if (!dest || size == 0) return;
    if (!src) {
        dest[0] = '\0';
        return;
    }
    size_t len = strlen(src);
    if (len >= size) len = size - 1;
    memcpy(dest, src, len);
    dest[len] = '\0';
}

/* ------------------------------------------------------------------------- */
/* Identity & Storage Helpers                                                */
/* ------------------------------------------------------------------------- */

static void get_identity_path(char *dest, size_t max_len) {
    const char *home = getenv("HOME");
    if (!home) home = ".";
    snprintf(dest, max_len, "%s/.shnet", home);
    mkdir(dest, 0755);
    snprintf(dest, max_len, "%s/.shnet/identity", home);
}

static void save_nickname(const char *nick) {
    if (!nick || !nick[0]) return;
    char path[512];
    get_identity_path(path, sizeof(path));
    FILE *f = fopen(path, "w");
    if (f) {
        fprintf(f, "%s\n", nick);
        fclose(f);
    }
}

static void resolve_nickname(const char *cli_name, char *out_name, size_t max_len) {
    if (cli_name && cli_name[0]) {
        strncpy(out_name, cli_name, max_len - 1);
        out_name[max_len - 1] = '\0';
        save_nickname(out_name);
        return;
    }

    char path[512];
    get_identity_path(path, sizeof(path));
    FILE *f = fopen(path, "r");
    if (f) {
        char buf[128];
        if (fgets(buf, sizeof(buf), f)) {
            char *nl = strchr(buf, '\n');
            if (nl) *nl = '\0';
            char *cr = strchr(buf, '\r');
            if (cr) *cr = '\0';
            if (buf[0]) {
                fclose(f);
                snprintf(out_name, max_len, "%.*s", (int)max_len - 1, buf);
                return;
            }
        }
        fclose(f);
    }

    if (isatty(STDIN_FILENO)) {
        printf("nickname [rei]: ");
        fflush(stdout);
        char buf[128];
        if (fgets(buf, sizeof(buf), stdin)) {
            char *nl = strchr(buf, '\n');
            if (nl) *nl = '\0';
            char *cr = strchr(buf, '\r');
            if (cr) *cr = '\0';
            char *p = buf;
            while (*p == ' ' || *p == '\t') p++;
            if (*p) {
                snprintf(out_name, max_len, "%.*s", (int)max_len - 1, p);
                save_nickname(out_name);
                return;
            }
        }
    }

    snprintf(out_name, max_len, "rei");
    save_nickname(out_name);
}

static void get_history_path(char *dest, size_t max_len) {
    const char *home = getenv("HOME");
    if (!home) home = ".";
    snprintf(dest, max_len, "%s/.shnet/chats", home);
    mkdir(dest, 0755);
    snprintf(dest, max_len, "%s/.shnet/chats/history.jsonl", home);
}

static void escape_json(const char *src, char *dest, size_t max_len) {
    size_t d = 0;
    for (size_t s = 0; src[s] && d + 4 < max_len; s++) {
        char c = src[s];
        if (c == '"') { dest[d++] = '\\'; dest[d++] = '"'; }
        else if (c == '\\') { dest[d++] = '\\'; dest[d++] = '\\'; }
        else if (c == '\n') { dest[d++] = '\\'; dest[d++] = 'n'; }
        else if (c == '\r') { dest[d++] = '\\'; dest[d++] = 'r'; }
        else if (c == '\t') { dest[d++] = '\\'; dest[d++] = 't'; }
        else { dest[d++] = c; }
    }
    dest[d] = '\0';
}

static bool extract_json_str(const char *json, const char *key, char *out, size_t max_len) {
    if (!json || !key || !out || max_len == 0) return false;
    out[0] = '\0';
    char search[128];
    snprintf(search, sizeof(search), "\"%s\"", key);
    const char *pos = strstr(json, search);
    if (!pos) return false;
    pos = strchr(pos + strlen(search), ':');
    if (!pos) return false;
    pos++;
    while (*pos == ' ' || *pos == '\t' || *pos == '\r' || *pos == '\n') pos++;
    if (*pos != '"') return false;
    pos++;
    size_t idx = 0;
    while (*pos && *pos != '"' && idx + 1 < max_len) {
        if (*pos == '\\') {
            pos++;
            if (!*pos) break;
        }
        out[idx++] = *pos++;
    }
    out[idx] = '\0';
    return true;
}

static void save_message(const char *id, const char *sender, const char *peer, const char *text, const char *dir) {
    char path[512];
    get_history_path(path, sizeof(path));
    char esc_sender[128], esc_peer[256], esc_text[4096];
    escape_json(sender, esc_sender, sizeof(esc_sender));
    escape_json(peer, esc_peer, sizeof(esc_peer));
    escape_json(text, esc_text, sizeof(esc_text));

    long long ts = (long long)time(NULL) * 1000;
    pthread_mutex_lock(&g_storage_mutex);
    FILE *f = fopen(path, "a");
    if (f) {
        fprintf(f, "{\"id\":\"%s\",\"sender\":\"%s\",\"recipient\":\"%s\",\"peer\":\"%s\",\"text\":\"%s\",\"timestamp\":%lld,\"direction\":\"%s\"}\n",
                id, esc_sender, g_local_name, esc_peer, esc_text, ts, dir);
        fclose(f);
    }
    pthread_mutex_unlock(&g_storage_mutex);
}

static void format_peer_display_name(const char *sender, char *out, size_t max_len) {
    if (!sender || !sender[0]) {
        strncpy(out, "peer", max_len - 1);
        out[max_len - 1] = '\0';
        return;
    }
    if (strcasecmp(sender, g_local_name) == 0) {
        snprintf(out, max_len, "%s#peer", sender);
    } else {
        strncpy(out, sender, max_len - 1);
        out[max_len - 1] = '\0';
    }
}

static void print_history(const char *filter_peer, int limit) {
    char path[512];
    get_history_path(path, sizeof(path));
    FILE *f = fopen(path, "r");
    if (!f) {
        printf("No chat messages recorded.\n");
        return;
    }

    char line[8192];
    int count = 0;
    if (filter_peer && filter_peer[0]) {
        printf("[HISTORY] %s\n", filter_peer);
    } else {
        printf("[HISTORY] all\n");
    }
    while (fgets(line, sizeof(line), f)) {
        char sender[128] = "", peer[256] = "", text[4096] = "", dir[16] = "";
        extract_json_str(line, "sender", sender, sizeof(sender));
        extract_json_str(line, "peer", peer, sizeof(peer));
        extract_json_str(line, "text", text, sizeof(text));
        extract_json_str(line, "direction", dir, sizeof(dir));

        if (filter_peer && filter_peer[0] && !strstr(peer, filter_peer) && !strstr(filter_peer, peer)) {
            continue;
        }

        if (strcmp(dir, "OUT") == 0) {
            if (strncmp(text, "/me ", 4) == 0) {
                printf("[ACTION] * %s %s\n", g_local_name, text + 4);
            } else {
                printf("[SEND] <%s> %s\n", g_local_name, text);
            }
        } else {
            char dname[128];
            format_peer_display_name(sender, dname, sizeof(dname));
            if (strncmp(text, "/me ", 4) == 0) {
                printf("[ACTION] * %s %s\n", dname, text + 4);
            } else {
                printf("[RECV] <%s> %s\n", dname, text);
            }
        }
        count++;
        if (limit > 0 && count >= limit) break;
    }
    fclose(f);
    if (count == 0) printf("  (none)\n");
}

/* ------------------------------------------------------------------------- */
/* Network & Dual-Stack Helpers                                              */
/* ------------------------------------------------------------------------- */

static bool is_internet_ipv6(const struct in6_addr *addr) {
    if (IN6_IS_ADDR_LOOPBACK(addr) || IN6_IS_ADDR_LINKLOCAL(addr) ||
        IN6_IS_ADDR_SITELOCAL(addr) || IN6_IS_ADDR_MULTICAST(addr) ||
        IN6_IS_ADDR_V4MAPPED(addr)) {
        return false;
    }
    // Exclude Unique Local Addresses (ULA fc00::/7)
    if ((addr->s6_addr[0] & 0xfe) == 0xfc) {
        return false;
    }
    // Global Unicast (2000::/3 has top 3 bits 001)
    return (addr->s6_addr[0] & 0xe0) == 0x20;
}

static void get_primary_url(int port, char *out_url, size_t max_len) {
    struct ifaddrs *ifaddr, *ifa;
    out_url[0] = '\0';
    if (getifaddrs(&ifaddr) != -1) {
        // Pass 1: Global internet IPv6 (outside network address)
        for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
            if (!ifa->ifa_addr) continue;
            if (ifa->ifa_addr->sa_family == AF_INET6) {
                struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)ifa->ifa_addr;
                if (is_internet_ipv6(&sin6->sin6_addr)) {
                    char host[INET6_ADDRSTRLEN];
                    inet_ntop(AF_INET6, &sin6->sin6_addr, host, sizeof(host));
                    snprintf(out_url, max_len, "http://[%s]:%d/", host, port);
                    freeifaddrs(ifaddr);
                    return;
                }
            }
        }
        // Pass 2: Link-local IPv6
        for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
            if (!ifa->ifa_addr) continue;
            if (ifa->ifa_addr->sa_family == AF_INET6) {
                struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)ifa->ifa_addr;
                if (IN6_IS_ADDR_LINKLOCAL(&sin6->sin6_addr)) {
                    char host[INET6_ADDRSTRLEN];
                    inet_ntop(AF_INET6, &sin6->sin6_addr, host, sizeof(host));
                    snprintf(out_url, max_len, "http://[%s]:%d/", host, port);
                    freeifaddrs(ifaddr);
                    return;
                }
            }
        }
        // Pass 3: IPv4
        for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
            if (!ifa->ifa_addr) continue;
            if (ifa->ifa_addr->sa_family == AF_INET) {
                struct sockaddr_in *sin = (struct sockaddr_in *)ifa->ifa_addr;
                if (sin->sin_addr.s_addr != htonl(INADDR_LOOPBACK)) {
                    char host[INET_ADDRSTRLEN];
                    inet_ntop(AF_INET, &sin->sin_addr, host, sizeof(host));
                    snprintf(out_url, max_len, "http://%s:%d/", host, port);
                    freeifaddrs(ifaddr);
                    return;
                }
            }
        }
        freeifaddrs(ifaddr);
    }
    snprintf(out_url, max_len, "http://[::1]:%d/", port);
}

static void print_links(int port) {
    struct ifaddrs *ifaddr, *ifa;
    if (getifaddrs(&ifaddr) == -1) {
        printf("    [internet-v6] http://[::1]:%d/\n", port);
        printf("    [local-v4]    http://127.0.0.1:%d/\n", port);
        return;
    }

    int found = 0;
    // Pass 1: Global internet routable IPv6 first (outside network)
    for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr) continue;
        if (ifa->ifa_addr->sa_family == AF_INET6) {
            struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)ifa->ifa_addr;
            if (is_internet_ipv6(&sin6->sin6_addr)) {
                char host[INET6_ADDRSTRLEN];
                inet_ntop(AF_INET6, &sin6->sin6_addr, host, sizeof(host));
                printf("    [internet-v6] http://[%s]:%d/\n", host, port);
                found++;
            }
        }
    }

    // Pass 2: Local link IPv6
    for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr) continue;
        if (ifa->ifa_addr->sa_family == AF_INET6) {
            struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)ifa->ifa_addr;
            if (IN6_IS_ADDR_LINKLOCAL(&sin6->sin6_addr)) {
                char host[INET6_ADDRSTRLEN];
                inet_ntop(AF_INET6, &sin6->sin6_addr, host, sizeof(host));
                printf("    [local-v6]    http://[%s]:%d/\n", host, port);
                found++;
            }
        }
    }

    // Pass 3: Local LAN IPv4
    for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr) continue;
        if (ifa->ifa_addr->sa_family == AF_INET) {
            struct sockaddr_in *sin = (struct sockaddr_in *)ifa->ifa_addr;
            if (sin->sin_addr.s_addr != htonl(INADDR_LOOPBACK)) {
                char host[INET_ADDRSTRLEN];
                inet_ntop(AF_INET, &sin->sin_addr, host, sizeof(host));
                printf("    [local-v4]    http://%s:%d/\n", host, port);
                found++;
            }
        }
    }

    freeifaddrs(ifaddr);
    if (!found) {
        printf("    [internet-v6] http://[::1]:%d/\n", port);
        printf("    [local-v4]    http://127.0.0.1:%d/\n", port);
    }
}

static void print_qr_ansi(const char *text) {
    uint8_t qrcode[qrcodegen_BUFFER_LEN_MAX];
    uint8_t tempBuffer[qrcodegen_BUFFER_LEN_MAX];
    bool ok = qrcodegen_encodeText(text, tempBuffer, qrcode, qrcodegen_Ecc_MEDIUM,
                                   qrcodegen_VERSION_MIN, qrcodegen_VERSION_MAX,
                                   qrcodegen_Mask_AUTO, true);
    if (!ok) {
        printf("[QR Generation failed for: %s]\n", text);
        return;
    }

    int size = qrcodegen_getSize(qrcode);
    int border = 2;
    int total = size + border * 2;

    printf("%s", ANSI_WHITE_BG_BLACK_FG);
    for (int i = 0; i < total; i++) putchar(' ');
    printf("%s\n", ANSI_RESET);

    for (int y = 0; y < size; y += 2) {
        printf("%s", ANSI_WHITE_BG_BLACK_FG);
        for (int b = 0; b < border; b++) putchar(' ');
        for (int x = 0; x < size; x++) {
            bool top = qrcodegen_getModule(qrcode, x, y);
            bool bot = (y + 1 < size) ? qrcodegen_getModule(qrcode, x, y + 1) : false;
            if (top && bot) printf("█");
            else if (!top && !bot) printf(" ");
            else if (top) printf("▀");
            else printf("▄");
        }
        for (int b = 0; b < border; b++) putchar(' ');
        printf("%s\n", ANSI_RESET);
    }

    printf("%s", ANSI_WHITE_BG_BLACK_FG);
    for (int i = 0; i < total; i++) putchar(' ');
    printf("%s\n", ANSI_RESET);
}

static void format_http_host(const char *host, int port, char *dest, size_t max_len) {
    if (strchr(host, ':') && host[0] != '[') {
        snprintf(dest, max_len, "[%s]:%d", host, port);
    } else {
        snprintf(dest, max_len, "%s:%d", host, port);
    }
}

static void parse_host_port(const char *peer, char *host, int *port) {
    const char *p = peer;
    if (strncmp(p, "http://", 7) == 0) p += 7;
    else if (strncmp(p, "https://", 8) == 0) p += 8;

    char temp[256];
    strncpy(temp, p, sizeof(temp) - 1);
    temp[sizeof(temp) - 1] = '\0';
    char *slash = strchr(temp, '/');
    if (slash) *slash = '\0';

    if (temp[0] == '[') {
        char *close_b = strchr(temp, ']');
        if (close_b) {
            *close_b = '\0';
            strncpy(host, temp + 1, 255);
            host[255] = '\0';
            if (*(close_b + 1) == ':') {
                *port = atoi(close_b + 2);
            } else {
                *port = DEFAULT_PORT;
            }
            return;
        }
    }

    // Check if the entire temp string is a valid IPv6 address without brackets
    struct in6_addr sa6;
    if (inet_pton(AF_INET6, temp, &sa6) == 1) {
        strncpy(host, temp, 255);
        host[255] = '\0';
        *port = DEFAULT_PORT;
        return;
    }

    // Check if it has multiple colons (IPv6 with trailing port)
    char *last_c = strrchr(temp, ':');
    char *first_c = strchr(temp, ':');
    if (last_c && first_c && last_c != first_c) {
        *last_c = '\0';
        if (inet_pton(AF_INET6, temp, &sa6) == 1) {
            int p_val = atoi(last_c + 1);
            if (p_val > 0 && p_val <= 65535) {
                strncpy(host, temp, 255);
                host[255] = '\0';
                *port = p_val;
                return;
            }
        }
        *last_c = ':'; // restore
    }

    if (last_c) {
        *last_c = '\0';
        *port = atoi(last_c + 1);
        strncpy(host, temp, 255);
        host[255] = '\0';
    } else {
        strncpy(host, temp, 255);
        host[255] = '\0';
        *port = DEFAULT_PORT;
    }
}

static int connect_to_host(const char *host, int port, int timeout_sec) {
    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    struct addrinfo hints, *res, *rp;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC; // Dual-stack IPv6 & IPv4!
    hints.ai_socktype = SOCK_STREAM;

    if (getaddrinfo(host, port_str, &hints, &res) != 0) {
        return -1;
    }

    int sock = -1;
    for (rp = res; rp != NULL; rp = rp->ai_next) {
        sock = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (sock == -1) continue;

        struct timeval tv = { .tv_sec = timeout_sec, .tv_usec = 0 };
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        if (connect(sock, rp->ai_addr, rp->ai_addrlen) != -1) {
            break; // Success!
        }
        close(sock);
        sock = -1;
    }
    freeaddrinfo(res);
    return sock;
}

static ssize_t read_http_response(int sock, char *buf, size_t max_len) {
    size_t total = 0;
    char *hdr_end = NULL;
    while (total < max_len - 1) {
        ssize_t n = read(sock, buf + total, max_len - 1 - total);
        if (n <= 0) break;
        total += n;
        buf[total] = '\0';
        hdr_end = strstr(buf, "\r\n\r\n");
        if (hdr_end) break;
    }
    if (!hdr_end) return total;

    int clen = 0;
    char *cl = strcasestr(buf, "Content-Length:");
    if (cl && cl < hdr_end) {
        clen = atoi(cl + 15);
    }

    size_t body_have = total - ((hdr_end + 4) - buf);
    while (clen > 0 && body_have < (size_t)clen && total < max_len - 1) {
        size_t to_read = (size_t)clen - body_have;
        if (to_read > max_len - 1 - total) to_read = max_len - 1 - total;
        ssize_t n = read(sock, buf + total, to_read);
        if (n <= 0) break;
        total += n;
        buf[total] = '\0';
        body_have = total - ((hdr_end + 4) - buf);
    }
    return total;
}

static bool ping_peer(const char *peer, char *out_name, size_t out_len, char *out_ver, size_t out_ver_len) {
    char host[256] = "";
    int port = DEFAULT_PORT;
    parse_host_port(peer, host, &port);

    int sock = connect_to_host(host, port, 2);
    if (sock < 0) return false;

    char host_hdr[300];
    format_http_host(host, port, host_hdr, sizeof(host_hdr));

    char request[512];
    snprintf(request, sizeof(request),
             "GET /api/chat/info HTTP/1.1\r\nHost: %s\r\nUser-Agent: girchat-c/%s\r\nConnection: close\r\n\r\n",
             host_hdr, GIRCHAT_VERSION);
    safe_write(sock, request, strlen(request));

    char response[2048];
    ssize_t n = read_http_response(sock, response, sizeof(response));
    close(sock);

    if (n > 0) {
        response[n] = '\0';
        if (strstr(response, "200 OK")) {
            char *body = strstr(response, "\r\n\r\n");
            if (body) {
                body += 4;
                if (out_name && out_len > 0) {
                    extract_json_str(body, "name", out_name, out_len);
                }
                if (out_ver && out_ver_len > 0) {
                    extract_json_str(body, "version", out_ver, out_ver_len);
                }
            }
            return true;
        }
    }
    return false;
}

static bool send_connect(const char *peer, const char *local_name, int local_port,
                        char *out_peer_name, size_t out_name_len,
                        char *out_ver, size_t out_ver_len) {
    char host[256] = "";
    int port = DEFAULT_PORT;
    parse_host_port(peer, host, &port);

    int sock = connect_to_host(host, port, 3);
    if (sock < 0) return false;

    char host_hdr[300];
    format_http_host(host, port, host_hdr, sizeof(host_hdr));

    char primary_url[128];
    get_primary_url(local_port, primary_url, sizeof(primary_url));

    char json_body[512];
    snprintf(json_body, sizeof(json_body),
             "{\"name\":\"%s\",\"port\":%d,\"internet_url\":\"%s\",\"version\":\"%s\"}",
             local_name, local_port, primary_url, GIRCHAT_VERSION);

    char request[1024];
    snprintf(request, sizeof(request),
             "POST /api/chat/connect HTTP/1.1\r\n"
             "Host: %s\r\n"
             "Content-Type: application/json\r\n"
             "Content-Length: %zu\r\n"
             "Connection: close\r\n\r\n%s",
             host_hdr, strlen(json_body), json_body);

    safe_write(sock, request, strlen(request));

    char response[2048];
    ssize_t n = read_http_response(sock, response, sizeof(response));
    close(sock);

    if (n > 0) {
        response[n] = '\0';
        if (strstr(response, "200 OK")) {
            char *body = strstr(response, "\r\n\r\n");
            if (body) {
                body += 4;
                if (out_peer_name && out_name_len > 0) {
                    extract_json_str(body, "name", out_peer_name, out_name_len);
                }
                if (out_ver && out_ver_len > 0) {
                    extract_json_str(body, "version", out_ver, out_ver_len);
                }
            }
            return true;
        }
    }
    return false;
}

static void send_disconnect(const char *peer, const char *local_name) {
    if (!peer || !peer[0]) return;
    char host[256] = "";
    int port = DEFAULT_PORT;
    parse_host_port(peer, host, &port);

    int sock = connect_to_host(host, port, 1);
    if (sock < 0) return;

    char host_hdr[300];
    format_http_host(host, port, host_hdr, sizeof(host_hdr));

    char json_body[256];
    snprintf(json_body, sizeof(json_body), "{\"name\":\"%s\"}", local_name);

    char request[1024];
    snprintf(request, sizeof(request),
             "POST /api/chat/disconnect HTTP/1.1\r\n"
             "Host: %s\r\n"
             "Content-Type: application/json\r\n"
             "Content-Length: %zu\r\n"
             "Connection: close\r\n\r\n%s",
             host_hdr, strlen(json_body), json_body);

    safe_write(sock, request, strlen(request));
    char buf[256];
    ssize_t r = read(sock, buf, sizeof(buf));
    (void)r;
    close(sock);
}

static bool send_http_message(const char *peer, const char *sender, const char *text, int reply_port) {
    char host[256] = "";
    int port = DEFAULT_PORT;
    parse_host_port(peer, host, &port);

    int sock = connect_to_host(host, port, 5);
    if (sock < 0) return false;

    char host_hdr[300];
    format_http_host(host, port, host_hdr, sizeof(host_hdr));

    char esc_text[4096];
    escape_json(text, esc_text, sizeof(esc_text));
    char json_body[5000];
    char id[64];
    snprintf(id, sizeof(id), "%lld-c%04x", (long long)time(NULL) * 1000, rand() & 0xFFFF);
    snprintf(json_body, sizeof(json_body),
             "{\"id\":\"%s\",\"sender\":\"%s\",\"peer\":\"%s\",\"text\":\"%s\",\"reply_port\":%d,\"version\":\"%s\"}",
             id, sender, peer, esc_text, reply_port, GIRCHAT_VERSION);

    char request[6000];
    snprintf(request, sizeof(request),
             "POST /api/chat/message HTTP/1.1\r\n"
             "Host: %s\r\n"
             "Content-Type: application/json\r\n"
             "Content-Length: %zu\r\n"
             "Connection: close\r\n\r\n%s",
             host_hdr, strlen(json_body), json_body);

    ssize_t w = write(sock, request, strlen(request));
    (void)w;

    char response[2048];
    ssize_t n = read_http_response(sock, response, sizeof(response));
    close(sock);

    if (n > 0) {
        response[n] = '\0';
        if (strstr(response, "200 OK")) {
            save_message(id, sender, peer, text, "OUT");
            return true;
        }
    }
    return false;
}

static void normalize_peer_url(const char *in_url, char *out_url, size_t max_len) {
    if (!in_url || !out_url || max_len == 0) return;
    const char *p = in_url;
    while (*p == ' ' || *p == '\t') p++;

    char temp[256];
    if (strncmp(p, "http://", 7) != 0 && strncmp(p, "https://", 8) != 0) {
        snprintf(temp, sizeof(temp), "http://%s", p);
    } else {
        strncpy(temp, p, sizeof(temp) - 1);
        temp[sizeof(temp) - 1] = '\0';
    }
    size_t len = strlen(temp);
    while (len > 0 && temp[len - 1] == '/') {
        temp[--len] = '\0';
    }
    strncpy(out_url, temp, max_len - 1);
    out_url[max_len - 1] = '\0';
}

static int parse_peer_index(const char *str) {
    if (!str) return -1;
    while (*str == ' ') str++;
    if (*str == '#' || *str == '[') str++;
    if (!isdigit((unsigned char)*str)) return -1;
    char *end = NULL;
    long val = strtol(str, &end, 10);
    while (*end == ' ' || *end == ']') end++;
    if (*end != '\0') return -1;
    return (int)(val - 1);
}

static int peer_find_locked(const char *target) {
    if (!target || !target[0]) return -1;

    int num_idx = parse_peer_index(target);
    if (num_idx >= 0) {
        if (num_idx < g_peer_count) return num_idx;
        return -2; // index out of range
    }

    char norm[256];
    normalize_peer_url(target, norm, sizeof(norm));

    for (int i = 0; i < g_peer_count; i++) {
        if (strcasecmp(g_peers[i].url, norm) == 0) return i;
    }
    for (int i = 0; i < g_peer_count; i++) {
        if (strcasecmp(g_peers[i].name, target) == 0) return i;
    }
    return -1;
}

static int peer_upsert_locked(const char *url, const char *name, const char *ver, bool set_active_if_none) {
    if (!url || !url[0]) return -1;
    char norm[256];
    normalize_peer_url(url, norm, sizeof(norm));

    int idx = -1;
    for (int i = 0; i < g_peer_count; i++) {
        if (strcasecmp(g_peers[i].url, norm) == 0) {
            idx = i;
            break;
        }
    }

    if (idx < 0) {
        if (g_peer_count < MAX_PEERS) {
            idx = g_peer_count++;
        } else {
            idx = 0;
            time_t oldest = g_peers[0].last_seen;
            for (int i = 1; i < g_peer_count; i++) {
                if (!g_peers[i].online && g_peers[i].last_seen < oldest) {
                    oldest = g_peers[i].last_seen;
                    idx = i;
                }
            }
        }
        safe_strncpy(g_peers[idx].url, norm, sizeof(g_peers[idx].url));
        if (name && name[0] && strcmp(name, "peer") != 0 && strcmp(name, "anonymous") != 0) {
            safe_strncpy(g_peers[idx].name, name, sizeof(g_peers[idx].name));
        } else {
            safe_strncpy(g_peers[idx].name, "peer", sizeof(g_peers[idx].name));
        }
        if (ver && ver[0]) {
            safe_strncpy(g_peers[idx].version, ver, sizeof(g_peers[idx].version));
        } else {
            g_peers[idx].version[0] = '\0';
        }
    } else {
        if (name && name[0] && strcmp(name, "peer") != 0 && strcmp(name, "anonymous") != 0) {
            safe_strncpy(g_peers[idx].name, name, sizeof(g_peers[idx].name));
        }
        if (ver && ver[0]) {
            safe_strncpy(g_peers[idx].version, ver, sizeof(g_peers[idx].version));
        }
    }

    g_peers[idx].last_seen = time(NULL);
    g_peers[idx].online = true;

    // Never clobber an already active conversation! Only set active if no active peer exists.
    if ((g_active_peer_idx < 0 || !g_active_peer[0]) && set_active_if_none) {
        g_active_peer_idx = idx;
        safe_strncpy(g_active_peer, g_peers[idx].url, sizeof(g_active_peer));
        safe_strncpy(g_peer_name, g_peers[idx].name, sizeof(g_peer_name));
    }

    return idx;
}

static bool peer_switch_locked(int idx) {
    if (idx < 0 || idx >= g_peer_count) return false;
    g_active_peer_idx = idx;
    safe_strncpy(g_active_peer, g_peers[idx].url, sizeof(g_active_peer));
    safe_strncpy(g_peer_name, g_peers[idx].name, sizeof(g_peer_name));
    return true;
}

static bool peer_replace_locked(int old_idx, const char *new_url, const char *new_name, const char *new_ver) {
    if (old_idx < 0 || old_idx >= g_peer_count) return false;
    char norm[256];
    normalize_peer_url(new_url, norm, sizeof(norm));
    safe_strncpy(g_peers[old_idx].url, norm, sizeof(g_peers[old_idx].url));
    if (new_name && new_name[0]) {
        safe_strncpy(g_peers[old_idx].name, new_name, sizeof(g_peers[old_idx].name));
    } else {
        safe_strncpy(g_peers[old_idx].name, "peer", sizeof(g_peers[old_idx].name));
    }
    if (new_ver && new_ver[0]) {
        safe_strncpy(g_peers[old_idx].version, new_ver, sizeof(g_peers[old_idx].version));
    } else {
        g_peers[old_idx].version[0] = '\0';
    }
    g_peers[old_idx].last_seen = time(NULL);
    g_peers[old_idx].online = true;

    if (g_active_peer_idx == old_idx) {
        safe_strncpy(g_active_peer, g_peers[old_idx].url, sizeof(g_active_peer));
        safe_strncpy(g_peer_name, g_peers[old_idx].name, sizeof(g_peer_name));
    }
    return true;
}

static void print_peers_table(void) {
    pthread_mutex_lock(&g_peer_mutex);
    printf("[PEERS] (%d registered)\n", g_peer_count);
    if (g_peer_count == 0) {
        printf("  No peers connected yet. Use /scan to discover or /peer <url> to connect.\n");
        pthread_mutex_unlock(&g_peer_mutex);
        return;
    }

    printf("  #   STATUS    VER       NAME             ENDPOINT\n");
    for (int i = 0; i < g_peer_count; i++) {
        char marker = (i == g_active_peer_idx) ? '*' : ' ';
        const char *active_tag = (i == g_active_peer_idx) ? " (ACTIVE)" : "";
        const char *status_str = g_peers[i].online ? "online" : "offline";
        const char *ver_str = g_peers[i].version[0] ? g_peers[i].version : "-";
        char dname[128];
        format_peer_display_name(g_peers[i].name, dname, sizeof(dname));

        printf("%c [%d] [%-7s] %-9s %-16s %s%s\n",
               marker, i + 1, status_str, ver_str, dname, g_peers[i].url, active_tag);
    }
    pthread_mutex_unlock(&g_peer_mutex);
    printf("\nCommands: /peer <#> (switch), /scan (discover), /replace <#> <url>, /all <msg>, /version\n");
}

static void load_history_peers(void) {
    char path[512];
    get_history_path(path, sizeof(path));
    FILE *f = fopen(path, "r");
    if (!f) return;

    char line[8192];
    pthread_mutex_lock(&g_peer_mutex);
    while (fgets(line, sizeof(line), f)) {
        char sender[128] = "", peer[256] = "", dir[16] = "";
        extract_json_str(line, "sender", sender, sizeof(sender));
        extract_json_str(line, "peer", peer, sizeof(peer));
        extract_json_str(line, "direction", dir, sizeof(dir));

        if (peer[0] && strcmp(peer, "peer") != 0 && (strncmp(peer, "http://", 7) == 0 || strncmp(peer, "https://", 8) == 0 || strchr(peer, ':'))) {
            const char *name = (strcmp(dir, "IN") == 0 && sender[0]) ? sender : "peer";
            int idx = peer_upsert_locked(peer, name, NULL, false);
            g_peers[idx].online = false;
        }
    }
    pthread_mutex_unlock(&g_peer_mutex);
    fclose(f);
}

static int scan_network(int target_port) {
    if (target_port <= 0) target_port = DEFAULT_PORT;
    printf("[SCAN] Probing localhost and local network on port %d...\n", target_port);

    int found = 0;

    // 1. Scan localhost ports 8720 to 8730
    for (int p = 8720; p <= 8730; p++) {
        if (p == g_local_port) continue;
        char test_url[64];
        snprintf(test_url, sizeof(test_url), "http://127.0.0.1:%d", p);
        char node_name[128] = "";
        char node_ver[64] = "";
        if (ping_peer(test_url, node_name, sizeof(node_name), node_ver, sizeof(node_ver))) {
            pthread_mutex_lock(&g_peer_mutex);
            int idx = peer_upsert_locked(test_url, node_name, node_ver, false);
            pthread_mutex_unlock(&g_peer_mutex);
            const char *vdisplay = node_ver[0] ? node_ver : "-";
            printf("    [FOUND] [%d] %-14s (v%-5s) %s\n", idx + 1, node_name[0] ? node_name : "node", vdisplay, test_url);
            found++;
        }
    }

    // 2. Scan local IPv4 subnets
    struct ifaddrs *ifaddr, *ifa;
    if (getifaddrs(&ifaddr) == 0) {
        for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
            if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
            struct sockaddr_in *sin = (struct sockaddr_in *)ifa->ifa_addr;
            if (sin->sin_addr.s_addr == htonl(INADDR_LOOPBACK)) continue;

            struct sockaddr_in *sin_mask = (struct sockaddr_in *)ifa->ifa_netmask;
            uint32_t mask = sin_mask ? sin_mask->sin_addr.s_addr : htonl(0xFFFFFF00);
            uint32_t my_ip = sin->sin_addr.s_addr;
            uint32_t net = my_ip & mask;

            struct pollfd fds[256];
            int sock_map[256];
            char ip_map[256][INET_ADDRSTRLEN];
            int n_targets = 0;

            for (int host = 1; host <= 254; host++) {
                uint32_t target_ip = (net & htonl(0xFFFFFF00)) | htonl(host);
                if (target_ip == my_ip) continue;

                int s = socket(AF_INET, SOCK_STREAM, 0);
                if (s < 0) continue;

                int flags = fcntl(s, F_GETFL, 0);
                fcntl(s, F_SETFL, flags | O_NONBLOCK);

                struct sockaddr_in saddr;
                memset(&saddr, 0, sizeof(saddr));
                saddr.sin_family = AF_INET;
                saddr.sin_addr.s_addr = target_ip;
                saddr.sin_port = htons(target_port);

                connect(s, (struct sockaddr *)&saddr, sizeof(saddr));

                fds[n_targets].fd = s;
                fds[n_targets].events = POLLOUT;
                fds[n_targets].revents = 0;
                sock_map[n_targets] = s;
                inet_ntop(AF_INET, &saddr.sin_addr, ip_map[n_targets], INET_ADDRSTRLEN);
                n_targets++;
            }

            int ready = poll(fds, n_targets, 1000);
            if (ready > 0) {
                for (int i = 0; i < n_targets; i++) {
                    if (fds[i].revents & POLLOUT) {
                        int err = 0;
                        socklen_t elen = sizeof(err);
                        if (getsockopt(fds[i].fd, SOL_SOCKET, SO_ERROR, &err, &elen) == 0 && err == 0) {
                            char test_url[256];
                            snprintf(test_url, sizeof(test_url), "http://%.64s:%d", ip_map[i], target_port);
                            char node_name[128] = "";
                            char node_ver[64] = "";
                            ping_peer(test_url, node_name, sizeof(node_name), node_ver, sizeof(node_ver));
                            pthread_mutex_lock(&g_peer_mutex);
                            int idx = peer_upsert_locked(test_url, node_name, node_ver, false);
                            pthread_mutex_unlock(&g_peer_mutex);
                            const char *vdisplay = node_ver[0] ? node_ver : "-";
                            printf("    [FOUND] [%d] %-14s (v%-5s) %s\n", idx + 1, node_name[0] ? node_name : "node", vdisplay, test_url);
                            found++;
                        }
                    }
                }
            }
            for (int i = 0; i < n_targets; i++) {
                close(sock_map[i]);
            }
        }
        freeifaddrs(ifaddr);
    }

    printf("[SCAN] Finished. %d node(s) discovered.\n", found);
    return found;
}

static void send_broadcast(const char *message) {
    if (!message || !message[0]) return;
    int total = 0;
    char urls[MAX_PEERS][256];
    char names[MAX_PEERS][128];
    pthread_mutex_lock(&g_peer_mutex);
    for (int i = 0; i < g_peer_count; i++) {
        if (g_peers[i].online) {
            safe_strncpy(urls[total], g_peers[i].url, sizeof(urls[total]));
            safe_strncpy(names[total], g_peers[i].name, sizeof(names[total]));
            total++;
        }
    }
    pthread_mutex_unlock(&g_peer_mutex);

    if (total == 0) {
        printf("[BROADCAST] No online peers found. Use /scan or /peer <url> to connect.\n");
        return;
    }

    int sent = 0;
    for (int i = 0; i < total; i++) {
        if (send_http_message(urls[i], g_local_name, message, g_local_port)) {
            sent++;
        }
    }
    time_t now = time(NULL);
    struct tm tm_buf;
    struct tm *tm = localtime_r(&now, &tm_buf);
    printf("[BROADCAST] %02d:%02d:%02d <%s> %s (delivered to %d/%d peers)\n",
           tm ? tm->tm_hour : 0, tm ? tm->tm_min : 0, tm ? tm->tm_sec : 0,
           g_local_name, message, sent, total);
}

static const char *get_mime_type(const char *path) {
    const char *dot = strrchr(path, '.');
    if (!dot) return "application/octet-stream";
    if (strcasecmp(dot, ".html") == 0 || strcasecmp(dot, ".htm") == 0) return "text/html; charset=utf-8";
    if (strcasecmp(dot, ".css") == 0) return "text/css; charset=utf-8";
    if (strcasecmp(dot, ".js") == 0 || strcasecmp(dot, ".mjs") == 0) return "application/javascript; charset=utf-8";
    if (strcasecmp(dot, ".json") == 0) return "application/json; charset=utf-8";
    if (strcasecmp(dot, ".txt") == 0 || strcasecmp(dot, ".md") == 0) return "text/plain; charset=utf-8";
    if (strcasecmp(dot, ".png") == 0) return "image/png";
    if (strcasecmp(dot, ".jpg") == 0 || strcasecmp(dot, ".jpeg") == 0) return "image/jpeg";
    if (strcasecmp(dot, ".svg") == 0) return "image/svg+xml";
    if (strcasecmp(dot, ".sh") == 0) return "application/x-sh";
    return "application/octet-stream";
}

static void send_404(int sock) {
    const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: 13\r\nConnection: close\r\n\r\n404 Not Found";
    ssize_t w = write(sock, resp, strlen(resp));
    (void)w;
}

static void send_403(int sock) {
    const char *resp = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: 13\r\nConnection: close\r\n\r\n403 Forbidden";
    safe_write(sock, resp, strlen(resp));
}

static void handle_static_file(int client_sock, const char *req_path, bool is_head) {
    char clean_path[1024];
    strncpy(clean_path, req_path, sizeof(clean_path) - 1);
    clean_path[sizeof(clean_path) - 1] = '\0';
    char *q = strchr(clean_path, '?');
    if (q) *q = '\0';

    char full_path[4096];
    if (clean_path[0] == '/') {
        snprintf(full_path, sizeof(full_path), "%s%s", g_static_dir, clean_path);
    } else {
        snprintf(full_path, sizeof(full_path), "%s/%s", g_static_dir, clean_path);
    }

    char resolved[4096];
    if (!realpath(full_path, resolved)) {
        send_404(client_sock);
        return;
    }
    size_t sdir_len = strlen(g_static_dir);
    if (strncmp(resolved, g_static_dir, sdir_len) != 0 ||
        (resolved[sdir_len] != '\0' && resolved[sdir_len] != '/')) {
        send_403(client_sock);
        return;
    }

    struct stat st;
    if (stat(resolved, &st) != 0) {
        send_404(client_sock);
        return;
    }

    if (S_ISDIR(st.st_mode)) {
        char index_path[4200];
        snprintf(index_path, sizeof(index_path), "%s/index.html", resolved);
        if (stat(index_path, &st) == 0 && S_ISREG(st.st_mode)) {
            strncpy(resolved, index_path, sizeof(resolved) - 1);
            resolved[sizeof(resolved) - 1] = '\0';
        } else {
            DIR *d = opendir(resolved);
            if (!d) { send_404(client_sock); return; }
            char html[8192];
            int pos = snprintf(html, sizeof(html),
                "<!DOCTYPE html><html><head><title>Index of %s</title></head><body>"
                "<h2>Index of %s</h2><ul>", clean_path, clean_path);
            if (pos < 0 || pos >= (int)sizeof(html)) pos = sizeof(html) - 1;
            if (strcmp(clean_path, "/") != 0) {
                int n = snprintf(html + pos, sizeof(html) - pos, "<li><a href='../'>..</a></li>");
                if (n > 0) pos += (pos + n < (int)sizeof(html)) ? n : ((int)sizeof(html) - 1 - pos);
            }
            struct dirent *ent;
            while ((ent = readdir(d)) != NULL) {
                if (ent->d_name[0] == '.') continue;
                char slash[2] = "";
                if (ent->d_type == DT_DIR) slash[0] = '/';
                if ((int)sizeof(html) - pos > 200) {
                    int n = snprintf(html + pos, sizeof(html) - pos,
                        "<li><a href='%s%s'>%s%s</a></li>", ent->d_name, slash, ent->d_name, slash);
                    if (n > 0) pos += (pos + n < (int)sizeof(html)) ? n : ((int)sizeof(html) - 1 - pos);
                } else {
                    break;
                }
            }
            closedir(d);
            if ((int)sizeof(html) - pos > 25) {
                snprintf(html + pos, sizeof(html) - pos, "</ul></body></html>");
            } else {
                html[sizeof(html) - 1] = '\0';
            }

            char header[256];
            snprintf(header, sizeof(header),
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n", strlen(html));
            safe_write(client_sock, header, strlen(header));
            if (!is_head) {
                safe_write(client_sock, html, strlen(html));
            }
            return;
        }
    }

    FILE *f = fopen(resolved, "rb");
    if (!f) {
        send_404(client_sock);
        return;
    }

    const char *mime = get_mime_type(resolved);
    char header[512];
    snprintf(header, sizeof(header),
        "HTTP/1.1 200 OK\r\nContent-Type: %s\r\nContent-Length: %ld\r\nConnection: close\r\n\r\n",
        mime, (long)st.st_size);
    ssize_t w = write(client_sock, header, strlen(header));
    if (w < 0 || is_head) {
        fclose(f);
        return;
    }

    char chunk[8192];
    size_t bytes;
    while ((bytes = fread(chunk, 1, sizeof(chunk), f)) > 0) {
        w = write(client_sock, chunk, bytes);
        if (w < 0) break;
    }
    fclose(f);
}

static void extract_peer_ip(int sock, char *out_ip, size_t max_len) {
    if (!out_ip || max_len == 0) return;
    out_ip[0] = '\0';
    struct sockaddr_storage peer_addr;
    memset(&peer_addr, 0, sizeof(peer_addr));
    socklen_t peer_len = sizeof(peer_addr);
    if (getpeername(sock, (struct sockaddr *)&peer_addr, &peer_len) != 0) {
        strncpy(out_ip, "unknown", max_len - 1);
        out_ip[max_len - 1] = '\0';
        return;
    }

    if (peer_addr.ss_family == AF_INET6) {
        struct sockaddr_in6 *s6 = (struct sockaddr_in6 *)&peer_addr;
        if (IN6_IS_ADDR_V4MAPPED(&s6->sin6_addr)) {
            inet_ntop(AF_INET, &s6->sin6_addr.s6_addr[12], out_ip, max_len);
        } else {
            char temp_ip[INET6_ADDRSTRLEN];
            if (inet_ntop(AF_INET6, &s6->sin6_addr, temp_ip, sizeof(temp_ip))) {
                if (strcmp(temp_ip, "::1") == 0) {
                    strncpy(out_ip, "127.0.0.1", max_len - 1);
                } else {
                    snprintf(out_ip, max_len, "[%s]", temp_ip);
                }
            } else {
                strncpy(out_ip, "unknown", max_len - 1);
            }
        }
    } else if (peer_addr.ss_family == AF_INET) {
        struct sockaddr_in *s4 = (struct sockaddr_in *)&peer_addr;
        if (!inet_ntop(AF_INET, &s4->sin_addr, out_ip, max_len)) {
            strncpy(out_ip, "unknown", max_len - 1);
        }
    } else {
        strncpy(out_ip, "unknown", max_len - 1);
    }
    out_ip[max_len - 1] = '\0';
}

static void print_prompt(void);

static void *handle_client(void *arg) {
    int client_sock = (int)(intptr_t)arg;
    struct timeval tv = { .tv_sec = 10, .tv_usec = 0 };
    setsockopt(client_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(client_sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    char buffer[16384];
    size_t total_read = 0;
    char *header_end = NULL;

    while (total_read < sizeof(buffer) - 1) {
        ssize_t n = read(client_sock, buffer + total_read, sizeof(buffer) - 1 - total_read);
        if (n <= 0) break;
        total_read += n;
        buffer[total_read] = '\0';
        header_end = strstr(buffer, "\r\n\r\n");
        if (header_end) break;
    }

    if (!header_end) {
        close(client_sock);
        return NULL;
    }

    int content_length = 0;
    char *cl = strcasestr(buffer, "Content-Length:");
    if (cl && cl < header_end) {
        content_length = atoi(cl + 15);
    }

    size_t body_offset = (header_end + 4) - buffer;
    size_t body_have = total_read - body_offset;
    while (content_length > 0 && body_have < (size_t)content_length && total_read < sizeof(buffer) - 1) {
        size_t to_read = (size_t)content_length - body_have;
        if (to_read > sizeof(buffer) - 1 - total_read) {
            to_read = sizeof(buffer) - 1 - total_read;
        }
        ssize_t n = read(client_sock, buffer + total_read, to_read);
        if (n <= 0) break;
        total_read += n;
        buffer[total_read] = '\0';
        body_have = total_read - body_offset;
    }

    char *body = header_end + 4;

    if (strncmp(buffer, "POST /api/chat/connect", 22) == 0) {
        char name[128] = "anonymous";
        char port_str[32] = "";
        char internet_url[256] = "";
        char peer_ver[32] = "";
        extract_json_str(body, "name", name, sizeof(name));
        extract_json_str(body, "port", port_str, sizeof(port_str));
        extract_json_str(body, "internet_url", internet_url, sizeof(internet_url));
        extract_json_str(body, "version", peer_ver, sizeof(peer_ver));
        int c_port = atoi(port_str);
        if (c_port <= 0) c_port = DEFAULT_PORT;

        char client_ip[128] = "";
        extract_peer_ip(client_sock, client_ip, sizeof(client_ip));

        char peer_url[256];
        if (internet_url[0] && strcmp(client_ip, "127.0.0.1") != 0 && strcmp(client_ip, "::1") != 0) {
            strncpy(peer_url, internet_url, sizeof(peer_url) - 1);
            peer_url[sizeof(peer_url) - 1] = '\0';
        } else {
            if (client_ip[0] == '[') {
                snprintf(peer_url, sizeof(peer_url), "http://%s:%d", client_ip, c_port);
            } else if (strchr(client_ip, ':')) {
                snprintf(peer_url, sizeof(peer_url), "http://[%s]:%d", client_ip, c_port);
            } else {
                snprintf(peer_url, sizeof(peer_url), "http://%s:%d", client_ip, c_port);
            }
        }

        pthread_mutex_lock(&g_peer_mutex);
        int p_idx = peer_upsert_locked(peer_url, name, peer_ver, true);
        pthread_mutex_unlock(&g_peer_mutex);

        char dname[128];
        format_peer_display_name(name, dname, sizeof(dname));
        printf("\r\033[2K[CONNECT] [%d] %s <%s>\n", p_idx + 1, peer_url, dname);
        print_prompt();

        char primary_url[128];
        get_primary_url(g_local_port, primary_url, sizeof(primary_url));

        char resp_body[512];
        snprintf(resp_body, sizeof(resp_body),
                 "{\"status\":\"connected\",\"name\":\"%s\",\"port\":%d,\"internet_url\":\"%s\",\"version\":\"%s\"}",
                 g_local_name, g_local_port, primary_url, GIRCHAT_VERSION);
        char resp[768];
        snprintf(resp, sizeof(resp),
                 "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n%s",
                 strlen(resp_body), resp_body);
        safe_write(client_sock, resp, strlen(resp));
    } else if (strncmp(buffer, "POST /api/chat/disconnect", 25) == 0) {
        char name[128] = "peer";
        extract_json_str(body, "name", name, sizeof(name));
        char dname[128];
        format_peer_display_name(name, dname, sizeof(dname));

        pthread_mutex_lock(&g_peer_mutex);
        int d_idx = peer_find_locked(name);
        if (d_idx >= 0) {
            g_peers[d_idx].online = false;
        }
        pthread_mutex_unlock(&g_peer_mutex);

        printf("\r\033[2K[DISCONNECT] <%s>\n", dname);
        print_prompt();
        const char *resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 26\r\nConnection: close\r\n\r\n{\"status\":\"disconnected\"}";
        safe_write(client_sock, resp, strlen(resp));
    } else if (strncmp(buffer, "POST /api/chat/message", 22) == 0) {
        char sender[128] = "anonymous", text[4096] = "", id[64] = "", r_port[32] = "", sender_ver[32] = "";
        extract_json_str(body, "sender", sender, sizeof(sender));
        extract_json_str(body, "text", text, sizeof(text));
        extract_json_str(body, "id", id, sizeof(id));
        extract_json_str(body, "reply_port", r_port, sizeof(r_port));
        extract_json_str(body, "version", sender_ver, sizeof(sender_ver));
        if (!id[0]) snprintf(id, sizeof(id), "%lld-in", (long long)time(NULL)*1000);

        int rp = atoi(r_port);
        if (rp <= 0) rp = DEFAULT_PORT;
        char client_ip[128] = "";
        extract_peer_ip(client_sock, client_ip, sizeof(client_ip));

        char sender_url[256];
        if (client_ip[0] == '[') {
            snprintf(sender_url, sizeof(sender_url), "http://%s:%d", client_ip, rp);
        } else if (strchr(client_ip, ':')) {
            snprintf(sender_url, sizeof(sender_url), "http://[%s]:%d", client_ip, rp);
        } else {
            snprintf(sender_url, sizeof(sender_url), "http://%s:%d", client_ip, rp);
        }

        pthread_mutex_lock(&g_peer_mutex);
        peer_upsert_locked(sender_url, sender, sender_ver, false);
        char current_active[256];
        strncpy(current_active, g_active_peer[0] ? g_active_peer : sender_url, sizeof(current_active) - 1);
        current_active[sizeof(current_active) - 1] = '\0';
        pthread_mutex_unlock(&g_peer_mutex);

        save_message(id, sender, sender_url, text, "IN");

        time_t now = time(NULL);
        struct tm tm_buf;
        struct tm *tm = localtime_r(&now, &tm_buf);
        char dname[128];
        format_peer_display_name(sender, dname, sizeof(dname));

        printf("\r\033[2K");
        if (strncmp(text, "/me ", 4) == 0) {
            printf("[ACTION] %02d:%02d:%02d * %s %s\n",
                   tm ? tm->tm_hour : 0, tm ? tm->tm_min : 0, tm ? tm->tm_sec : 0,
                   dname, text + 4);
        } else {
            printf("[RECV] %02d:%02d:%02d <%s> %s\n",
                   tm ? tm->tm_hour : 0, tm ? tm->tm_min : 0, tm ? tm->tm_sec : 0,
                   dname, text);
        }
        print_prompt();

        const char *resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"status\":\"ok\"}";
        safe_write(client_sock, resp, strlen(resp));
    } else if (strncmp(buffer, "GET /api/chat/info", 18) == 0) {
        char resp_body[256];
        snprintf(resp_body, sizeof(resp_body),
                 "{\"name\":\"%s\",\"version\":\"%s\",\"service\":\"girchat-c\",\"port\":%d}",
                 g_local_name, GIRCHAT_VERSION, g_local_port);
        char resp[512];
        snprintf(resp, sizeof(resp),
                 "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n%s",
                 strlen(resp_body), resp_body);
        safe_write(client_sock, resp, strlen(resp));
    } else if (strncmp(buffer, "GET /_shnet/status", 18) == 0) {
        char resp_body[256];
        snprintf(resp_body, sizeof(resp_body),
                 "{\"status\":\"running\",\"service\":\"girchat-c\",\"version\":\"%s\",\"port\":%d}",
                 GIRCHAT_VERSION, g_local_port);
        char resp[512];
        snprintf(resp, sizeof(resp),
                 "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n%s",
                 strlen(resp_body), resp_body);
        safe_write(client_sock, resp, strlen(resp));
    } else {
        bool is_head = (strncmp(buffer, "HEAD ", 5) == 0);
        const char *p = strchr(buffer, ' ');
        char req_path[512] = "/";
        if (p) {
            p++;
            const char *end = strchr(p, ' ');
            if (end && (size_t)(end - p) < sizeof(req_path)) {
                strncpy(req_path, p, end - p);
                req_path[end - p] = '\0';
            }
        }

        if (strcmp(req_path, "/") == 0 && g_static_dir[0] == '\0') {
            const char *resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 22\r\nConnection: close\r\n\r\nhello from girchat (C)";
            if (is_head) {
                const char *hresp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 22\r\nConnection: close\r\n\r\n";
                safe_write(client_sock, hresp, strlen(hresp));
            } else {
                safe_write(client_sock, resp, strlen(resp));
            }
        } else if (g_static_dir[0] != '\0') {
            handle_static_file(client_sock, req_path, is_head);
        } else {
            send_404(client_sock);
        }
    }

    close(client_sock);
    return NULL;
}

static void *server_thread(void *arg) {
    int server_sock = (int)(intptr_t)arg;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 1024 * 1024);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

    while (g_running) {
        struct sockaddr_storage client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_sock = accept(server_sock, (struct sockaddr *)&client_addr, &client_len);
        if (client_sock < 0) {
            if (!g_running) break;
            continue;
        }
        pthread_t tid;
        if (pthread_create(&tid, &attr, handle_client, (void *)(intptr_t)client_sock) != 0) {
            close(client_sock);
        }
    }
    pthread_attr_destroy(&attr);
    close(server_sock);
    return NULL;
}

static int bind_dual_stack_server(int preferred_port, int *out_port) {
    for (int p = preferred_port; p < preferred_port + 50; p++) {
        int s = socket(AF_INET6, SOCK_STREAM, 0);
        if (s >= 0) {
            int opt = 1;
            setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
            int v6only = 0; // Dual-stack IPv6 & IPv4!
            setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));

            struct sockaddr_in6 addr6;
            memset(&addr6, 0, sizeof(addr6));
            addr6.sin6_family = AF_INET6;
            addr6.sin6_addr = in6addr_any;
            addr6.sin6_port = htons(p);

            if (bind(s, (struct sockaddr *)&addr6, sizeof(addr6)) == 0) {
                listen(s, 20);
                *out_port = p;
                return s;
            }
            close(s);
        }

        s = socket(AF_INET, SOCK_STREAM, 0);
        if (s >= 0) {
            int opt = 1;
            setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

            struct sockaddr_in addr4;
            memset(&addr4, 0, sizeof(addr4));
            addr4.sin_family = AF_INET;
            addr4.sin_addr.s_addr = htonl(INADDR_ANY);
            addr4.sin_port = htons(p);

            if (bind(s, (struct sockaddr *)&addr4, sizeof(addr4)) == 0) {
                listen(s, 20);
                *out_port = p;
                return s;
            }
            close(s);
        }
    }
    return -1;
}

static void print_prompt(void) {
    if (!isatty(STDIN_FILENO)) return;
    char dname[128] = "";
    char apeer[256] = "";

    pthread_mutex_lock(&g_peer_mutex);
    if (g_active_peer_idx >= 0 && g_active_peer_idx < g_peer_count) {
        strncpy(dname, g_peers[g_active_peer_idx].name, sizeof(dname) - 1);
        dname[sizeof(dname) - 1] = '\0';
        strncpy(apeer, g_peers[g_active_peer_idx].url, sizeof(apeer) - 1);
        apeer[sizeof(apeer) - 1] = '\0';
    } else if (g_peer_name[0]) {
        strncpy(dname, g_peer_name, sizeof(dname) - 1);
        dname[sizeof(dname) - 1] = '\0';
        strncpy(apeer, g_active_peer, sizeof(apeer) - 1);
        apeer[sizeof(apeer) - 1] = '\0';
    } else if (g_active_peer[0]) {
        strncpy(apeer, g_active_peer, sizeof(apeer) - 1);
        apeer[sizeof(apeer) - 1] = '\0';
    }
    pthread_mutex_unlock(&g_peer_mutex);

    if (dname[0]) {
        char fmt_name[128];
        format_peer_display_name(dname, fmt_name, sizeof(fmt_name));
        printf("%s[%s]> ", g_local_name, fmt_name);
    } else if (apeer[0]) {
        printf("%s[%s]> ", g_local_name, apeer);
    } else {
        printf("%s> ", g_local_name);
    }
    fflush(stdout);
}

/* ------------------------------------------------------------------------- */
/* Interactive REPL Chat                                                     */
/* ------------------------------------------------------------------------- */

static void print_chat_help(void) {
    printf("commands:\n");
    printf("  /peers                list all connected and discovered peers\n");
    printf("  /peer <#|name|url>    switch active peer or connect to new URL\n");
    printf("  /switch <#|name>      alias for /peer\n");
    printf("  /scan [port]          scan local subnet and localhost for nodes\n");
    printf("  /replace <#> <url>    replace a peer slot with a new address\n");
    printf("  /all <message>        broadcast message to all online peers\n");
    printf("  /disconnect [target]  disconnect peer without affecting others\n");
    printf("  /version, /ver        show version & build details\n");
    printf("  /check [target]       check remote node or binary server version\n");
    printf("  /nick <name>          set persistent nickname\n");
    printf("  /me <action>          send action message\n");
    printf("  /whois                show active peer and connection info\n");
    printf("  /history [n]          show last [n] messages\n");
    printf("  /info, /status        show node status & links\n");
    printf("  /qr                   show terminal QR code\n");
    printf("  /clear                clear screen\n");
    printf("  /help                 show command help\n");
    printf("  /exit, /quit          disconnect and exit\n");
}

static void print_whois(void) {
    char primary_url[128];
    get_primary_url(g_local_port, primary_url, sizeof(primary_url));
    printf("[WHOIS]\n");
    printf("  local:    %s (port %d, v%s)\n", g_local_name, g_local_port, GIRCHAT_VERSION);
    printf("  endpoint: %s\n", primary_url);

    pthread_mutex_lock(&g_peer_mutex);
    if (g_active_peer_idx >= 0 && g_active_peer_idx < g_peer_count) {
        char dname[128];
        format_peer_display_name(g_peers[g_active_peer_idx].name, dname, sizeof(dname));
        const char *pver = g_peers[g_active_peer_idx].version[0] ? g_peers[g_active_peer_idx].version : "unknown";
        printf("  active:   [%d] %s (%s, v%s) - %s\n",
               g_active_peer_idx + 1, dname,
               g_peers[g_active_peer_idx].url,
               pver,
               g_peers[g_active_peer_idx].online ? "online" : "offline");
    } else if (g_active_peer[0]) {
        printf("  active:   %s\n", g_active_peer);
    } else {
        printf("  active:   none\n");
    }
    printf("  peers:    %d registered (use /peers to list)\n", g_peer_count);
    pthread_mutex_unlock(&g_peer_mutex);
}

static void check_version_remote(const char *target) {
    char target_buf[256] = "";
    if (!target || !target[0]) {
        pthread_mutex_lock(&g_peer_mutex);
        if (g_active_peer[0]) {
            safe_strncpy(target_buf, g_active_peer, sizeof(target_buf));
        } else if (g_peer_count > 0) {
            safe_strncpy(target_buf, g_peers[0].url, sizeof(target_buf));
        } else {
            safe_strncpy(target_buf, "http://127.0.0.1:8723", sizeof(target_buf));
        }
        pthread_mutex_unlock(&g_peer_mutex);
        target = target_buf;
    }

    char norm[256];
    normalize_peer_url(target, norm, sizeof(norm));
    printf("[VERSION CHECK] Target: %s\n", norm);

    char name[128] = "";
    char ver[64] = "";
    if (ping_peer(norm, name, sizeof(name), ver, sizeof(ver))) {
        char dname[128];
        format_peer_display_name(name, dname, sizeof(dname));
        printf("  Node Name:       %s\n", dname);
        printf("  Remote Version:  v%s\n", ver[0] ? ver : "1.0.0 (or legacy)");
        printf("  Local Version:   v%s\n", GIRCHAT_VERSION);
        if (ver[0] && strcmp(ver, GIRCHAT_VERSION) == 0) {
            printf("  Status:          MATCH (Both nodes are running v%s)\n", GIRCHAT_VERSION);
        } else if (ver[0]) {
            printf("  Status:          MISMATCH (Remote v%s != Local v%s)\n", ver, GIRCHAT_VERSION);
        } else {
            printf("  Status:          ONLINE (Target did not report explicit version)\n");
        }
        return;
    }

    // Try download server HEAD check
    char host[256] = "";
    int port = 8080;
    parse_host_port(norm, host, &port);
    int sock = connect_to_host(host, port, 3);
    if (sock >= 0) {
        char req[512];
        snprintf(req, sizeof(req),
                 "HEAD /girchat HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\n\r\n",
                 host, port);
        safe_write(sock, req, strlen(req));
        char resp[2048];
        ssize_t n = read_http_response(sock, resp, sizeof(resp));
        close(sock);
        if (n > 0 && strstr(resp, "200 OK")) {
            long clen = 0;
            char *cl = strcasestr(resp, "Content-Length:");
            if (cl) clen = atol(cl + 15);
            printf("  Service:         HTTP Download Server (%s:%d)\n", host, port);
            printf("  Remote Binary:   girchat (%ld bytes)\n", clen);
            printf("  Local Version:   v%s\n", GIRCHAT_VERSION);
            printf("  Status:          Update source available (run 'girchat update %s/girchat' to install)\n", norm);
            return;
        }
    }

    printf("  Status:          UNREACHABLE (Failed to connect to %s)\n", norm);
}

static int update_binary_from_url(const char *url) {
    if (!url || !url[0]) {
        printf("Usage: girchat update <http://host:8080/girchat>\n");
        return 1;
    }
    char norm[256];
    normalize_peer_url(url, norm, sizeof(norm));
    printf("[UPDATE] Downloading latest binary from %s...\n", norm);

    char host[256] = "";
    int port = 8080;
    parse_host_port(norm, host, &port);

    const char *path = strchr(norm + 7, '/');
    if (!path || !path[0]) path = "/girchat";

    int sock = connect_to_host(host, port, 5);
    if (sock < 0) {
        printf("[UPDATE ERROR] Failed to connect to %s:%d\n", host, port);
        return 1;
    }

    char req[512];
    snprintf(req, sizeof(req),
             "GET %s HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\n\r\n",
             path, host, port);
    safe_write(sock, req, strlen(req));

    char hdr[2048];
    size_t hdr_len = 0;
    while (hdr_len < sizeof(hdr) - 1) {
        char c;
        ssize_t r = read(sock, &c, 1);
        if (r <= 0) break;
        hdr[hdr_len++] = c;
        if (hdr_len >= 4 && memcmp(hdr + hdr_len - 4, "\r\n\r\n", 4) == 0) break;
    }
    hdr[hdr_len] = '\0';

    if (!strstr(hdr, "200 OK")) {
        close(sock);
        printf("[UPDATE ERROR] Server returned non-200 response: %.64s\n", hdr);
        return 1;
    }

    char self_path[512] = "";
#if defined(__APPLE__)
    uint32_t size = sizeof(self_path);
    if (_NSGetExecutablePath(self_path, &size) != 0) {
        safe_strncpy(self_path, "./girchat", sizeof(self_path));
    }
#else
    ssize_t slen = readlink("/proc/self/exe", self_path, sizeof(self_path) - 1);
    if (slen > 0) {
        self_path[slen] = '\0';
    } else {
        safe_strncpy(self_path, "./girchat", sizeof(self_path));
    }
#endif

    char tmp_path[560];
    snprintf(tmp_path, sizeof(tmp_path), "%s.update.tmp", self_path);
    FILE *out = fopen(tmp_path, "wb");
    if (!out) {
        close(sock);
        printf("[UPDATE ERROR] Cannot open %s for writing\n", tmp_path);
        return 1;
    }

    char buf[8192];
    ssize_t bytes;
    size_t total = 0;
    while ((bytes = read(sock, buf, sizeof(buf))) > 0) {
        fwrite(buf, 1, bytes, out);
        total += bytes;
    }
    fclose(out);
    close(sock);

    if (total < 1000) {
        unlink(tmp_path);
        printf("[UPDATE ERROR] Downloaded file too small (%zu bytes), update aborted\n", total);
        return 1;
    }

    chmod(tmp_path, 0755);
    unlink(self_path);
    if (rename(tmp_path, self_path) != 0) {
        FILE *src = fopen(tmp_path, "rb");
        FILE *dst = fopen(self_path, "wb");
        if (src && dst) {
            while ((bytes = fread(buf, 1, sizeof(buf), src)) > 0) {
                fwrite(buf, 1, bytes, dst);
            }
        }
        if (src) fclose(src);
        if (dst) fclose(dst);
        chmod(self_path, 0755);
        unlink(tmp_path);
    }

    printf("[UPDATE SUCCESS] Updated %s (%zu bytes). Please restart girchat.\n", self_path, total);
    return 0;
}

static void run_chat_repl(const char *initial_peer, int preferred_port, const char *cli_name) {
    resolve_nickname(cli_name, g_local_name, sizeof(g_local_name));

    load_history_peers();

    if (initial_peer && initial_peer[0]) {
        pthread_mutex_lock(&g_peer_mutex);
        int idx = peer_upsert_locked(initial_peer, NULL, NULL, true);
        peer_switch_locked(idx);
        pthread_mutex_unlock(&g_peer_mutex);
        g_is_host = false;
    } else {
        g_is_host = true;
    }

    if (g_active_peer[0] && (strstr(g_active_peer, "127.0.0.1:8723") || strstr(g_active_peer, "localhost:8723"))) {
        preferred_port = DEFAULT_PORT + 1;
    }

    int server_sock = bind_dual_stack_server(preferred_port, &g_local_port);
    if (server_sock < 0) {
        fprintf(stderr, "Failed to bind dual-stack server socket.\n");
        return;
    }

    pthread_attr_t sattr;
    pthread_attr_init(&sattr);
    pthread_attr_setstacksize(&sattr, 1024 * 1024);
    pthread_t stid;
    pthread_create(&stid, &sattr, server_thread, (void *)(intptr_t)server_sock);
    pthread_attr_destroy(&sattr);

    char primary_url[128];
    get_primary_url(g_local_port, primary_url, sizeof(primary_url));

    printf("girchat %s [dual-stack ipv6]\n", GIRCHAT_VERSION);
    printf("node: %s | port: %d\n", g_local_name, g_local_port);
    printf("endpoint: %s\n", primary_url);
    print_links(g_local_port);

    pthread_mutex_lock(&g_peer_mutex);
    if (g_active_peer_idx >= 0 && g_active_peer_idx < g_peer_count) {
        char dname[128];
        format_peer_display_name(g_peers[g_active_peer_idx].name, dname, sizeof(dname));
        printf("peer: [%d] %s @ %s\n", g_active_peer_idx + 1, dname, g_peers[g_active_peer_idx].url);
    } else if (g_active_peer[0]) {
        printf("peer: %s\n", g_active_peer);
    } else {
        printf("state: listening (use /scan to discover peers)\n");
    }
    pthread_mutex_unlock(&g_peer_mutex);

    printf("commands: /peers /peer /scan /all /replace /disconnect /whois /version /check /history /qr /exit\n\n");

    if (g_active_peer[0]) {
        char peer_ver[32] = "";
        if (send_connect(g_active_peer, g_local_name, g_local_port, g_peer_name, sizeof(g_peer_name), peer_ver, sizeof(peer_ver))) {
            pthread_mutex_lock(&g_peer_mutex);
            if (g_active_peer_idx >= 0 && g_active_peer_idx < g_peer_count) {
                safe_strncpy(g_peers[g_active_peer_idx].name, g_peer_name, sizeof(g_peers[g_active_peer_idx].name));
                if (peer_ver[0]) safe_strncpy(g_peers[g_active_peer_idx].version, peer_ver, sizeof(g_peers[g_active_peer_idx].version));
                g_peers[g_active_peer_idx].online = true;
            }
            pthread_mutex_unlock(&g_peer_mutex);
            char dname[128];
            format_peer_display_name(g_peer_name, dname, sizeof(dname));
            printf("[CONNECT] %s <%s> (v%s)\n", g_active_peer, dname, peer_ver[0] ? peer_ver : "-");
        } else if (ping_peer(g_active_peer, g_peer_name, sizeof(g_peer_name), peer_ver, sizeof(peer_ver))) {
            pthread_mutex_lock(&g_peer_mutex);
            if (g_active_peer_idx >= 0 && g_active_peer_idx < g_peer_count) {
                safe_strncpy(g_peers[g_active_peer_idx].name, g_peer_name, sizeof(g_peers[g_active_peer_idx].name));
                if (peer_ver[0]) safe_strncpy(g_peers[g_active_peer_idx].version, peer_ver, sizeof(g_peers[g_active_peer_idx].version));
                g_peers[g_active_peer_idx].online = true;
            }
            pthread_mutex_unlock(&g_peer_mutex);
            char dname[128];
            format_peer_display_name(g_peer_name, dname, sizeof(dname));
            printf("[ONLINE] %s <%s> (v%s)\n", g_active_peer, dname, peer_ver[0] ? peer_ver : "-");
        } else {
            printf("[OFFLINE] %s\n", g_active_peer);
        }
        print_history(g_active_peer, 10);
    }

    char line[4096];
    bool tty = isatty(STDIN_FILENO);
    while (g_running) {
        if (tty) {
            print_prompt();
        }
        if (!fgets(line, sizeof(line), stdin)) break;
        char *nl = strchr(line, '\n');
        if (nl) *nl = '\0';
        char *cr = strchr(line, '\r');
        if (cr) *cr = '\0';
        if (!line[0]) continue;

        if (line[0] == '/') {
            if (strncmp(line, "/peer ", 6) == 0 || strncmp(line, "/connect ", 9) == 0 || strncmp(line, "/switch ", 8) == 0) {
                const char *tgt = (line[1] == 'p') ? line + 6 : ((line[1] == 'c') ? line + 9 : line + 8);
                while (*tgt == ' ') tgt++;
                if (!*tgt) {
                    print_peers_table();
                    continue;
                }

                pthread_mutex_lock(&g_peer_mutex);
                int idx = peer_find_locked(tgt);
                if (idx == -2) {
                    int total = g_peer_count;
                    pthread_mutex_unlock(&g_peer_mutex);
                    printf("[ERROR] Peer index out of range (1-%d registered)\n", total);
                    continue;
                }
                if (idx >= 0) {
                    peer_switch_locked(idx);
                    char sel_url[256], sel_name[128];
                    safe_strncpy(sel_url, g_peers[idx].url, sizeof(sel_url));
                    safe_strncpy(sel_name, g_peers[idx].name, sizeof(sel_name));
                    pthread_mutex_unlock(&g_peer_mutex);

                    char dname[128];
                    format_peer_display_name(sel_name, dname, sizeof(dname));
                    printf("[ACTIVE] Switched active peer to [%d] %s @ %s\n", idx + 1, dname, sel_url);
                } else {
                    pthread_mutex_unlock(&g_peer_mutex);
                    char norm[256];
                    normalize_peer_url(tgt, norm, sizeof(norm));
                    char remote_name[128] = "";
                    char remote_ver[64] = "";
                    if (send_connect(norm, g_local_name, g_local_port, remote_name, sizeof(remote_name), remote_ver, sizeof(remote_ver))) {
                        pthread_mutex_lock(&g_peer_mutex);
                        int n_idx = peer_upsert_locked(norm, remote_name, remote_ver, false);
                        peer_switch_locked(n_idx);
                        pthread_mutex_unlock(&g_peer_mutex);
                        char dname[128];
                        format_peer_display_name(remote_name, dname, sizeof(dname));
                        printf("[CONNECT] Connected & switched to [%d] %s <%s> (v%s)\n", n_idx + 1, norm, dname, remote_ver[0] ? remote_ver : "-");
                    } else if (ping_peer(norm, remote_name, sizeof(remote_name), remote_ver, sizeof(remote_ver))) {
                        pthread_mutex_lock(&g_peer_mutex);
                        int n_idx = peer_upsert_locked(norm, remote_name, remote_ver, false);
                        peer_switch_locked(n_idx);
                        pthread_mutex_unlock(&g_peer_mutex);
                        char dname[128];
                        format_peer_display_name(remote_name, dname, sizeof(dname));
                        printf("[ONLINE] Added & switched to [%d] %s <%s> (v%s)\n", n_idx + 1, norm, dname, remote_ver[0] ? remote_ver : "-");
                    } else {
                        pthread_mutex_lock(&g_peer_mutex);
                        int n_idx = peer_upsert_locked(norm, "peer", NULL, false);
                        peer_switch_locked(n_idx);
                        pthread_mutex_unlock(&g_peer_mutex);
                        printf("[PEER] Switched to [%d] %s (unreachable/offline)\n", n_idx + 1, norm);
                    }
                }
                print_history(g_active_peer, 10);
            } else if (strcmp(line, "/peer") == 0 || strcmp(line, "/connect") == 0 || strcmp(line, "/switch") == 0) {
                print_peers_table();
            } else if (strcmp(line, "/peers") == 0) {
                print_peers_table();
            } else if (strncmp(line, "/scan", 5) == 0 || strncmp(line, "/discover", 9) == 0) {
                int sport = DEFAULT_PORT;
                const char *sp = strchr(line, ' ');
                if (sp) sport = atoi(sp + 1);
                if (sport <= 0) sport = DEFAULT_PORT;
                scan_network(sport);
                print_peers_table();
            } else if (strncmp(line, "/replace ", 9) == 0) {
                char target[128] = "", new_url[256] = "";
                if (sscanf(line + 9, "%127s %255s", target, new_url) == 2) {
                    pthread_mutex_lock(&g_peer_mutex);
                    int idx = peer_find_locked(target);
                    if (idx == -2) {
                        int total = g_peer_count;
                        pthread_mutex_unlock(&g_peer_mutex);
                        printf("[ERROR] Peer index out of range (1-%d registered)\n", total);
                        continue;
                    }
                    if (idx >= 0) {
                        pthread_mutex_unlock(&g_peer_mutex);
                        char rem_name[128] = "";
                        char rem_ver[64] = "";
                        ping_peer(new_url, rem_name, sizeof(rem_name), rem_ver, sizeof(rem_ver));
                        pthread_mutex_lock(&g_peer_mutex);
                        peer_replace_locked(idx, new_url, rem_name, rem_ver);
                        pthread_mutex_unlock(&g_peer_mutex);
                        printf("[REPLACE] Peer [%d] replaced with %s (v%s)\n", idx + 1, new_url, rem_ver[0] ? rem_ver : "-");
                    } else {
                        pthread_mutex_unlock(&g_peer_mutex);
                        printf("[ERROR] Peer '%s' not found in peer table\n", target);
                    }
                } else {
                    printf("usage: /replace <#|name> <new_url>\n");
                }
            } else if (strncmp(line, "/all ", 5) == 0 || strncmp(line, "/broadcast ", 11) == 0) {
                const char *msg = (strncmp(line, "/all ", 5) == 0) ? line + 5 : line + 11;
                while (*msg == ' ') msg++;
                if (*msg) {
                    send_broadcast(msg);
                } else {
                    printf("usage: /all <message>\n");
                }
            } else if (strncmp(line, "/disconnect", 11) == 0) {
                const char *tgt = line + 11;
                while (*tgt == ' ') tgt++;
                pthread_mutex_lock(&g_peer_mutex);
                int idx = (*tgt) ? peer_find_locked(tgt) : g_active_peer_idx;
                if (idx == -2) {
                    int total = g_peer_count;
                    pthread_mutex_unlock(&g_peer_mutex);
                    printf("[ERROR] Peer index out of range (1-%d registered)\n", total);
                    continue;
                }
                if (idx >= 0 && idx < g_peer_count) {
                    char d_url[256];
                    safe_strncpy(d_url, g_peers[idx].url, sizeof(d_url));
                    g_peers[idx].online = false;
                    if (g_active_peer_idx == idx) {
                        g_active_peer_idx = -1;
                        g_active_peer[0] = '\0';
                        g_peer_name[0] = '\0';
                        for (int k = 0; k < g_peer_count; k++) {
                            if (g_peers[k].online) {
                                peer_switch_locked(k);
                                break;
                            }
                        }
                    }
                    pthread_mutex_unlock(&g_peer_mutex);
                    send_disconnect(d_url, g_local_name);
                    printf("[DISCONNECT] Peer [%d] %s disconnected\n", idx + 1, d_url);
                } else {
                    pthread_mutex_unlock(&g_peer_mutex);
                    printf("[DISCONNECT] No active peer to disconnect\n");
                }
            } else if (strcmp(line, "/version") == 0 || strcmp(line, "/ver") == 0) {
                printf("[VERSION]\n");
                printf("  local binary:  girchat %s (Built %s %s)\n", GIRCHAT_VERSION, __DATE__, __TIME__);
                printf("  architecture:  Linux aarch64 musl-static\n");
                pthread_mutex_lock(&g_peer_mutex);
                if (g_active_peer_idx >= 0 && g_active_peer_idx < g_peer_count) {
                    const char *pver = g_peers[g_active_peer_idx].version[0] ? g_peers[g_active_peer_idx].version : "unknown";
                    printf("  active peer:   [%d] %s (v%s @ %s)\n",
                           g_active_peer_idx + 1, g_peers[g_active_peer_idx].name, pver, g_peers[g_active_peer_idx].url);
                } else {
                    printf("  active peer:   none\n");
                }
                pthread_mutex_unlock(&g_peer_mutex);
            } else if (strncmp(line, "/check", 6) == 0) {
                const char *tgt = line + 6;
                while (*tgt == ' ') tgt++;
                check_version_remote(*tgt ? tgt : NULL);
            } else if (strcmp(line, "/whois") == 0) {
                print_whois();
            } else if (strcmp(line, "/info") == 0 || strcmp(line, "/status") == 0) {
                char cur_url[128];
                get_primary_url(g_local_port, cur_url, sizeof(cur_url));
                printf("[STATUS]\n");
                printf("  node:     %s\n", g_local_name);
                printf("  role:     %s\n", g_is_host ? "host" : "client/host");
                printf("  port:     %d\n", g_local_port);
                printf("  endpoint: %s\n", cur_url);
                if (g_peer_name[0]) {
                    char dname[128];
                    format_peer_display_name(g_peer_name, dname, sizeof(dname));
                    printf("  peer:     %s @ %s\n", dname, g_active_peer);
                } else if (g_active_peer[0]) {
                    printf("  peer:     %s\n", g_active_peer);
                } else {
                    printf("  peer:     none\n");
                }
                printf("  links:\n");
                print_links(g_local_port);
            } else if (strcmp(line, "/qr") == 0) {
                char url[128];
                get_primary_url(g_local_port, url, sizeof(url));
                printf("[QR] %s\n", url);
                print_qr_ansi(url);
            } else if (strcmp(line, "/clear") == 0) {
                printf("\033[H\033[2J");
                fflush(stdout);
            } else if (strcmp(line, "/help") == 0) {
                print_chat_help();
            } else if (strcmp(line, "/exit") == 0 || strcmp(line, "/quit") == 0) {
                break;
            } else {
                printf("[ERROR] unknown command: %s (try /help)\n", line);
            }
        } else {
            if (!g_active_peer[0]) {
                printf("[ERROR] no active peer (use /peer <url>)\n");
            } else {
                if (send_http_message(g_active_peer, g_local_name, line, g_local_port)) {
                    time_t now = time(NULL);
                    struct tm *tm = localtime(&now);
                    printf("[SEND] %02d:%02d:%02d <%s> %s\n",
                           tm->tm_hour, tm->tm_min, tm->tm_sec,
                           g_local_name, line);
                } else {
                    printf("[ERROR] send failed: delivery failed to %s\n", g_active_peer);
                }
            }
        }
    }

    if (g_active_peer[0]) {
        send_disconnect(g_active_peer, g_local_name);
    }
    g_running = false;
    printf("[STOP] node closed\n");
}

static void run_serve(const char *dir, int preferred_port, bool show_qr) {
    char *resolved = realpath(dir, NULL);
    if (resolved) {
        strncpy(g_static_dir, resolved, sizeof(g_static_dir) - 1);
        g_static_dir[sizeof(g_static_dir) - 1] = '\0';
        free(resolved);
    } else {
        strncpy(g_static_dir, dir, sizeof(g_static_dir) - 1);
        g_static_dir[sizeof(g_static_dir) - 1] = '\0';
    }

    int server_sock = bind_dual_stack_server(preferred_port, &g_local_port);
    if (server_sock < 0) {
        fprintf(stderr, "Failed to bind dual-stack server socket.\n");
        return;
    }

    printf("serving %s on port %d [dual-stack ipv6]\n", g_static_dir, g_local_port);
    print_links(g_local_port);

    if (show_qr) {
        char url[128];
        get_primary_url(g_local_port, url, sizeof(url));
        printf("\nQR Code for: %s\n", url);
        print_qr_ansi(url);
    }
    printf("\nPress Ctrl+C to stop.\n\n");

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 1024 * 1024);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

    while (g_running) {
        struct sockaddr_storage client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_sock = accept(server_sock, (struct sockaddr *)&client_addr, &client_len);
        if (client_sock < 0) {
            if (!g_running) break;
            continue;
        }
        pthread_t tid;
        if (pthread_create(&tid, &attr, handle_client, (void *)(intptr_t)client_sock) != 0) {
            close(client_sock);
        }
    }
    pthread_attr_destroy(&attr);
    close(server_sock);
}

/* ------------------------------------------------------------------------- */
/* Main                                                                      */
/* ------------------------------------------------------------------------- */

int main(int argc, char *argv[]) {
    signal(SIGPIPE, SIG_IGN);
    if (argc < 2) {
        run_chat_repl(NULL, DEFAULT_PORT, NULL);
        return 0;
    }

    const char *cmd = argv[1];

    if (strcmp(cmd, "--help") == 0 || strcmp(cmd, "-h") == 0 || strcmp(cmd, "help") == 0) {
        printf("girchat / shnet (Native C) - Dual-Stack IPv6 Peer Mesh & Node Runtime\n\n"
               "Usage:\n"
               "  girchat [peer] [-p port] [-n name]      Interactive CLI chat & peer mesh\n"
               "  girchat scan [port]                     Scan local network for active nodes\n"
               "  girchat peers                           List known & registered peers\n"
               "  girchat check [target]                  Check version of target node or binary server\n"
               "  girchat update [server_url]             Self-update binary from server URL\n"
               "  girchat version, -v                     Show version and build info\n"
               "  shnet chat [peer] [-p port] [-n name]   Interactive CLI chat\n"
               "  shnet serve [dir] [-p port] [-q]        Serve static files from directory\n"
               "  shnet send <peer> <message>             Send a one-shot message\n"
               "  shnet history [peer]                    View stored chat history\n"
               "  shnet links [-p port]                   Discover IPv6 and IPv4 links\n"
               "  shnet qr [text]                         Print ANSI QR code\n");
        return 0;
    }

    if (strcmp(cmd, "version") == 0 || strcmp(cmd, "--version") == 0 ||
        strcmp(cmd, "-v") == 0 || strcmp(cmd, "-V") == 0) {
        printf("girchat version %s (Built %s %s / Linux aarch64 musl-static / Dual-Stack IPv6)\n",
               GIRCHAT_VERSION, __DATE__, __TIME__);
        return 0;
    }

    if (strcmp(cmd, "check") == 0 || strcmp(cmd, "--check") == 0) {
        const char *target = (argc >= 3) ? argv[2] : NULL;
        check_version_remote(target);
        return 0;
    }

    if (strcmp(cmd, "update") == 0 || strcmp(cmd, "--update") == 0) {
        const char *url = (argc >= 3) ? argv[2] : "http://192.168.89.60:8080/girchat";
        return update_binary_from_url(url);
    }

    if (strcmp(cmd, "scan") == 0 || strcmp(cmd, "--scan") == 0 || strcmp(cmd, "discover") == 0) {
        int port = DEFAULT_PORT;
        if (argc >= 3) port = atoi(argv[2]);
        load_history_peers();
        scan_network(port);
        print_peers_table();
        return 0;
    }

    if (strcmp(cmd, "peers") == 0) {
        load_history_peers();
        print_peers_table();
        return 0;
    }

    if (strcmp(cmd, "serve") == 0) {
        const char *dir = ".";
        int port = 8080;
        bool show_qr = false;
        for (int i = 2; i < argc; i++) {
            if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) port = atoi(argv[++i]);
            else if (strcmp(argv[i], "-q") == 0 || strcmp(argv[i], "--qr") == 0) show_qr = true;
            else if (argv[i][0] != '-') dir = argv[i];
        }
        run_serve(dir, port, show_qr);
        return 0;
    }

    if (strcmp(cmd, "links") == 0) {
        int port = DEFAULT_PORT;
        for (int i = 2; i < argc; i++) {
            if ((strcmp(argv[i], "-p") == 0 || strcmp(argv[i], "--port") == 0) && i + 1 < argc) {
                port = atoi(argv[++i]);
            } else if (argv[i][0] != '-') {
                port = atoi(argv[i]);
            }
        }
        print_links(port);
        return 0;
    }

    if (strcmp(cmd, "qr") == 0) {
        char text[128];
        if (argc >= 3) {
            snprintf(text, sizeof(text), "%s", argv[2]);
        } else {
            get_primary_url(DEFAULT_PORT, text, sizeof(text));
        }
        printf("QR Code for: %s\n", text);
        print_qr_ansi(text);
        return 0;
    }

    if (strcmp(cmd, "send") == 0) {
        if (argc < 4) {
            fprintf(stderr, "Usage: shnet send <peer> <message> [-n name]\n");
            return 1;
        }
        const char *peer = argv[2];
        const char *message = argv[3];
        const char *explicit_name = NULL;
        for (int i = 4; i < argc; i++) {
            if ((strcmp(argv[i], "-n") == 0 || strcmp(argv[i], "--name") == 0) && i + 1 < argc) {
                explicit_name = argv[++i];
            }
        }
        resolve_nickname(explicit_name, g_local_name, sizeof(g_local_name));
        if (send_http_message(peer, g_local_name, message, 0)) {
            printf("[OK] sent %s\n", peer);
            return 0;
        } else {
            fprintf(stderr, "[ERROR] delivery failed %s\n", peer);
            return 1;
        }
    }

    if (strcmp(cmd, "history") == 0) {
        const char *peer = argc >= 3 ? argv[2] : NULL;
        print_history(peer, 50);
        return 0;
    }

    if (strcmp(cmd, "chat") == 0 || strcmp(cmd, "listen") == 0 || strcmp(cmd, "node") == 0) {
        const char *peer = NULL;
        int port = DEFAULT_PORT;
        const char *explicit_name = NULL;
        for (int i = 2; i < argc; i++) {
            if ((strcmp(argv[i], "-p") == 0 || strcmp(argv[i], "--port") == 0) && i + 1 < argc) {
                port = atoi(argv[++i]);
            } else if ((strcmp(argv[i], "-n") == 0 || strcmp(argv[i], "--name") == 0) && i + 1 < argc) {
                explicit_name = argv[++i];
            } else if (argv[i][0] != '-' && !peer) {
                peer = argv[i];
            }
        }
        run_chat_repl(peer, port, explicit_name);
        return 0;
    }

    // Default: treat arguments as chat options/peer
    {
        const char *peer = NULL;
        int port = DEFAULT_PORT;
        const char *explicit_name = NULL;
        for (int i = 1; i < argc; i++) {
            if ((strcmp(argv[i], "-p") == 0 || strcmp(argv[i], "--port") == 0) && i + 1 < argc) {
                port = atoi(argv[++i]);
            } else if ((strcmp(argv[i], "-n") == 0 || strcmp(argv[i], "--name") == 0) && i + 1 < argc) {
                explicit_name = argv[++i];
            } else if (argv[i][0] != '-' && !peer) {
                peer = argv[i];
            }
        }
        run_chat_repl(peer, port, explicit_name);
        return 0;
    }
}

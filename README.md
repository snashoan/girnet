# girchat / shnet

```text
   ____ ___ ____   ____ _   _    _  _____ 
  / ___|_ _|  _ \ / ___| | | |  / \|_   _|
 | |  _ | || |_) | |   | |_| | / _ \ | |  
 | |_| || ||  _ <| |___|  _  |/ ___ \| |  
  \____|___|_| \_\\____|_| |_/_/   \_\_|  v1.1.0
```

**girchat** is a high-performance, dual-stack IPv6 peer mesh communicator and node runtime designed for device-owned applications. It runs anywhere with zero external dependencies: standalone Native C (musl-static), pure Java, and single-file Python 3.

---

## Installation

Install `girchat` / `shnet` directly from GitHub with a single command:

```bash
curl -sSL https://raw.githubusercontent.com/snashoan/girnet/main/install.sh | bash
```

*(On Termux or environments without bash, run with `sh`: `curl -sSL https://raw.githubusercontent.com/snashoan/girnet/main/install.sh | sh`)*

### Platform Strategy:
- **Android (Termux) / Linux aarch64**: Automatically downloads and installs the prebuilt 100% static musl binary (194 KB, zero dependencies).
- **macOS / BSD / Linux x86_64**: Automatically builds the high-performance native C binary locally from source using `clang`, `gcc`, or `cc`.
  - *macOS Note*: If no C compiler is detected, install Xcode Command Line Tools via `xcode-select --install`.
- **Universal Python 3 Fallback**: If no C compiler is found on non-Linux devices, the installer seamlessly falls back to the zero-dependency pure Python 3 edition so `girchat` runs immediately out of the box.
- **Force Native C Edition**:
  ```bash
  curl -sSL https://raw.githubusercontent.com/snashoan/girnet/main/install.sh | bash -s -- --c
  ```

Verify installation:
```bash
girchat -v
# girchat version 1.1.0 (Built Oct 29 2026 / Linux aarch64 musl-static / Dual-Stack IPv6)
```

---

## Key Capabilities

- **Dual-Stack IPv6 Peer Mesh & Priority**: Strongly prioritizes global routable IPv6 (`2409:...`, `2000::/3`, `fc00::/7`, `fd00::/8`) followed by link-local IPv6 (`fe80::`), and dual-stack socket binding (`IPV6_V6ONLY = 0`).
- **Concurrent Multi-Peer Registry**: Maintains a thread-safe registry table (`MAX_PEERS = 64`). Incoming connections and messages never overwrite or hijack active conversations.
- **Fast Non-Blocking Network Discovery (`/scan`, `girchat scan`)**: Probes local IPv4 subnets and localhost ports concurrently via non-blocking socket polling in ~1 second.
- **Peer Switching & Replacement (`/peers`, `/peer <#>`, `/replace`)**: Switch conversation targets by number, name, or endpoint, or replace existing slots in-place.
- **Version Checking & In-Place Self-Update (`/version`, `girchat check`, `girchat update`)**: Live version negotiation across nodes and one-command binary updates.
- **Identity Persistence & Nickname Separation**: Persistent nickname stored in `~/.shnet/identity`. If local and peer nicknames match, girchat disambiguates logs as `rei#peer` vs `rei`.
- **IRC Slash Commands**:
  - `/scan [port]` - Probe subnet and localhost for active nodes.
  - `/peers` - List known and online peers with status, version, and active target.
  - `/peer <#|name|url>` / `/switch <#>` - Switch active conversation target.
  - `/replace <#> <url>` - Replace peer slot with new node address.
  - `/all <message>` - Broadcast message across all online peers.
  - `/disconnect [target]` - Disconnect specific peer cleanly.
  - `/version`, `/ver` - Inspect local binary and active peer version.
  - `/check [target]` - Check remote node or binary server version.
  - `/nick <name>` - Update and persist your nickname across sessions.
  - `/me <action>` - Send action message (`[ACTION] 12:00:00 * rei waves to peer`).
  - `/whois` - Inspect identity, endpoints, and latency for active peer.
  - `/history [limit]` - Inspect chat history.
  - `/qr` - Display terminal ANSI QR code of your node URL.
  - `/info` / `/status` - Display IPv6 and IPv4 listening addresses.
  - `/clear` - Clear terminal screen.
  - `/help` - Command reference.
  - `/exit` / `/quit` - Clean disconnect and node shutdown.
- **Multi-Platform Standalone Deployments**:
  - **Native C Binary**: `bin/girchat` / `bin/shnet` (194 KB, static musl, zero runtime required).
  - **Java Fat JAR**: `shnet.jar` / `girchat.jar` (1.4 MB, self-contained, Java 11+).
  - **Pure Python 3**: `python/shnet.py` (87 KB, single-file, zero pip dependencies).

---

## Quick Start

### 1. Launch Interactive Chat

```bash
# Start node (listens on Dual-Stack IPv6 & IPv4, default port 8723):
girchat

# Connect directly to a peer on launch:
girchat http://192.168.89.60:8723

# Specify custom port and nickname:
girchat -p 8725 -n alice
```

### 2. Network Discovery & Peer Switching

```irc
rei> /scan
[SCAN] Probing localhost and local network on port 8723...
    [FOUND] [1] monitor        (v1.1.0) http://192.168.89.135:8723
[SCAN] Finished. 1 node(s) discovered.

rei> /peers
[PEERS] (1 registered)
  #   STATUS    VER       NAME             ENDPOINT
* [1] [online ] v1.1.0    monitor          http://192.168.89.135:8723 (ACTIVE)

rei[monitor]> Hello from Termux!
[SEND] 14:00:00 <rei> Hello from Termux!
```

### 3. Check Versions & Update

```bash
# Check version compatibility against another node:
girchat check http://192.168.89.60:8723

# In-place self-update from a server:
girchat update http://192.168.89.60:8080/girchat
```

### 4. One-Shot Send & History

```bash
# Send a message to an IPv6 or IPv4 node:
girchat send http://192.168.89.60:8723 "Hello via mesh!"

# View stored chat history:
girchat history
```

### 5. Static File Hosting

```bash
# Serve static directory over dual-stack IPv6/IPv4 with terminal QR code:
shnet serve ./public -p 8080 --qr
```

---

## Standalone Binaries & Distribution

All binaries are self-contained and pre-built in `/root/workspace/download/`:

| Format | File | Size | Requirements |
|---|---|---|---|
| **Native C Binary** | `shnet` / `girchat` | 76 KB | Linux (glibc/musl) |
| **Java Fat JAR** | `shnet.jar` / `girchat.jar` | 1.4 MB | Java 11+ |
| **Pure Python** | `shnet.py` / `girchat.py` | 87 KB | Python 3.8+ |

---

## Building from Source

### Java Fat JAR & Test Suite
```bash
# Run test suite
./gradlew test

# Build fat JAR (cli/build/libs/shnet.jar)
./gradlew :cli:jar
```

### Native C Binary
```bash
gcc -O2 -pthread c/shnet.c c/qrcodegen.c -o c/shnet
```

---

## License

MIT License. Designed and maintained for device-owned computing in GIR.

# girchat / shnet

```text
   ____ ___ ____   ____ _   _    _  _____ 
  / ___|_ _|  _ \ / ___| | | |  / \|_   _|
 | |  _ | || |_) | |   | |_| | / _ \ | |  
 | |_| || ||  _ <| |___|  _  |/ ___ \| |  
  \____|___|_| \_\\____|_| |_/_/   \_\_|  v1.0.0
```

**girchat** is a high-performance, dual-stack IPv6 peer mesh communicator and node runtime designed for device-owned applications. It runs anywhere with zero external dependencies: pure Java, standalone Native C, and single-file Python 3.

---

## Key Capabilities

- **Dual-Stack IPv6 Peer Mesh & Priority**: Strongly prioritizes global routable IPv6 (`2409:...`, `2000::/3`, `fc00::/7`, `fd00::/8`) followed by link-local IPv6 (`fe80::`), and dual-stack socket binding (`IPV6_V6ONLY = 0`).
- **Clean Protocol-Style Terminal Logging**: Crisp, zero-emoji daemon protocol output (`[CONNECT]`, `[DISCONNECT]`, `[RECV]`, `[SEND]`, `[ACTION]`, `[NICK]`, `[STATUS]`, `[WHOIS]`) with compact prompt `rei[peer]> ` or `rei> `.
- **Identity Persistence & Nickname Separation**: Persistent nickname stored in `~/.shnet/identity`. If local and peer nicknames match (e.g. both named `rei`), girchat disambiguates incoming messages as `rei#peer` vs `rei` so chat logs are always distinct.
- **IRC Slash Commands**: Full interactive slash commands:
  - `/nick <name>` - Update and persist your nickname across sessions.
  - `/me <action>` - Send action message (`[ACTION] 12:00:00 * rei waves to peer`).
  - `/whois` - Inspect identity, connection state, and latency for active peer.
  - `/peer <url>` - Connect to target peer (supports bracketed IPv6 `[2409:...]:8723`).
  - `/peers` - List recent mesh peers from storage history.
  - `/history [limit]` - Inspect chat history.
  - `/qr` - Display terminal ANSI QR code of your node URL.
  - `/info` / `/status` - Display IPv6 and IPv4 listening addresses.
  - `/clear` - Clear terminal screen.
  - `/help` - Command reference.
  - `/exit` / `/quit` - Clean disconnect and node shutdown.
- **Multi-Platform Standalone Deployments**:
  - **Java Fat JAR**: `shnet.jar` / `girchat.jar` (1.4MB, self-contained, zero Android SDK required).
  - **Native C Binary**: `shnet` / `girchat` (76KB, zero external dependencies, embedded QR generator).
  - **Pure Python 3**: `shnet.py` / `girchat.py` (87KB, single-file, zero pip dependencies).

---

## Quick Start

### 1. Launch Interactive Chat

```bash
# Terminal 1 / Device 1 (starts Host mode on port 8723, listening on Dual-Stack IPv6 & IPv4):
girchat

# Terminal 2 / Device 2 (auto-discovers local or LAN host, meshes immediately):
girchat --name alice

# Or specify a direct target address (IPv6 or IPv4):
girchat [2409:4055:2e3d:88c4::d5]:8723
```

### 2. Slash Commands in Chat

```irc
rei[alice]> /me smiles at the mesh
[ACTION] 12:00:00 * rei smiles at the mesh

rei[alice]> /whois
[WHOIS]
  local: rei (port 8723)
  peer:  alice (http://[2409:4055:2e3d:88c4::d5]:8723/)
  state: online

rei[alice]> /nick hoan
[NICK] rei -> hoan
```

### 3. One-Shot Send & History

```bash
# Send a message to an IPv6 or IPv4 node:
girchat send [2409:4055:2e3d:88c4::d5]:8723 "Hello via IPv6 mesh!"

# View stored chat history:
girchat history
```

### 4. Static File Hosting

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

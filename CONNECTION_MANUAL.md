# girchat / shnet: Complete Connection Manual

An authoritative operational and technical guide to connecting nodes peer-to-peer across the public IPv6 Internet without relays, NAT traversal, or cryptographic overhead.

---

## Table of Contents

1. [Architecture & Protocol Overview](#1-architecture--protocol-overview)
2. [Network Verification & Addressing](#2-network-verification--addressing)
3. [Starting a Node (Host / Listener)](#3-starting-a-node-host--listener)
4. [Connecting to a Peer (Client / Peer)](#4-connecting-to-a-peer-client--peer)
5. [In-Chat Protocol Commands](#5-in-chat-protocol-commands)
6. [One-Shot Headless Messaging](#6-one-shot-headless-messaging)
7. [QR Code Discovery](#7-qr-code-discovery)
8. [Cross-Platform Execution Matrix](#8-cross-platform-execution-matrix)
9. [Troubleshooting & Diagnostics](#9-troubleshooting--diagnostics)

---

## 1. Architecture & Protocol Overview

`girchat` is designed for device-owned applications requiring direct terminal-to-terminal communication without centralized servers.

### Socket Binding
Every `girchat` node starts a lightweight, non-blocking HTTP daemon listening on `in6addr_any` (`::`) with `IPV6_V6ONLY = 0`. This dual-stack configuration allows a single listening socket to simultaneously accept incoming connections over:
- **Global Unicast IPv6** (public internet routing)
- **Link-Local IPv6** (`fe80::`, ad-hoc local mesh)
- **IPv4-Mapped IPv6** (legacy LAN routing via `::ffff:x.x.x.x`)

### Protocol Handshake Flow
Communication uses raw HTTP/JSON over standard TCP streams:

1. **Discovery / Ping (`GET /api/chat/info`)**:
   Returns node identity, daemon version, and port:
   ```json
   {"name":"rei","version":"1.0.0","service":"girchat-c","port":8723}
   ```
2. **Connect (`POST /api/chat/connect`)**:
   Initiates session handshake. Transmits caller's name, local listening port, and advertised Global Internet IPv6 URL (`internet_url`):
   ```json
   {"name":"alice","port":8723,"internet_url":"http://[2409:4055:2db3:b379::d4]:8723/"}
   ```
   The recipient records the peer and returns its own identity in a `200 OK` response.
3. **Message Delivery (`POST /api/chat/message`)**:
   Transmits messages and IRC-style action events (`/me`):
   ```json
   {"id":"1793211234-c01a","sender":"alice","peer":"[2409:...]:8723","text":"hello","reply_port":8723}
   ```
4. **Disconnect (`POST /api/chat/disconnect`)**:
   Notifies peer of graceful termination.

---

## 2. Network Verification & Addressing

### Identifying Routable Internet IPv6
To connect over the outside internet, both devices must have a Global Unicast Address (GUA), defined in RFC 4291 as starting within the `2000::/3` prefix (e.g. `2400:...`, `2600:...`, `2800:...`, `2a00:...`).

Run the built-in link detector:
```bash
girchat links
```
Output:
```text
    [internet-v6] http://[2409:4055:2db3:b379::d4]:8723/
    [local-v6]    http://[fe80::2816:4aff:fe50:14aa]:8723/
    [local-v4]    http://192.168.89.60:8723/
```

- **`[internet-v6]`**: Your routable public IPv6 endpoint. Share this address with outside peers.
- **`[local-v6]`**: Link-local IPv6 (`fe80::`). Valid only within the same physical link or Wi-Fi network.
- **`[local-v4]`**: Private LAN IPv4 (e.g. `192.168.x.x` or `10.x.x.x`).

### Bracket Notation Requirement
In accordance with RFC 3986, all IPv6 numeric literals in URIs must be enclosed in square brackets:
- **Correct**: `http://[2409:4055:2db3:b379::d4]:8723/`
- **Incorrect**: `http://2409:4055:2db3:b379::d4:8723/` (the final hexadecimal chunk `::d4` is ambiguous with the port).

---

## 3. Starting a Node (Host / Listener)

### Default Launch
To start a node listening for incoming peer connections on the default port (`8723`):
```bash
girchat
```

### Custom Nickname and Port
To bind to a specific port and specify a persistent display identity:
```bash
girchat -p 8723 -n rei
```

### Terminal Banner
When running, the header displays your node parameters and listening URLs:
```text
girchat 1.0.0 [dual-stack ipv6]
node: rei | port: 8723
endpoint: http://[2409:4055:2db3:b379::d4]:8723/
    [internet-v6] http://[2409:4055:2db3:b379::d4]:8723/
    [local-v6]    http://[fe80::2816:4aff:fe50:14aa]:8723/
    [local-v4]    http://192.168.89.60:8723/
state: listening
commands: /nick /me /whois /peer /peers /history /qr /exit

rei> 
```

The node is now actively listening on `::` and ready for external connections.

---

## 4. Connecting to a Peer (Client / Peer)

### Method A: Connect on Startup
Provide the remote peer's bracketed IPv6 URL as the first argument:
```bash
girchat http://[2409:4055:2db3:b379::d4]:8723 -n alice
```
Or with custom local port:
```bash
girchat http://[2409:4055:2db3:b379::d4]:8723 -p 8724 -n alice
```

### Method B: Connect Interactively in Chat
Launch `girchat` in listening mode:
```bash
girchat -n alice
```
Then use the `/peer` or `/connect` command inside the prompt:
```text
alice> /peer http://[2409:4055:2db3:b379::d4]:8723
```

### Handshake Confirmation
Once connected, both nodes update their prompt and print protocol event logs:
```text
[CONNECT] http://[2409:4055:2db3:b379::d4]:8723/ <rei>
alice[rei]> 
```
On Node 1 (`rei`):
```text
[CONNECT] http://[2409:4015:2dc5:f71c::1]:8724/ <alice>
rei[alice]> 
```

### Disambiguating Matching Names
If both peers choose the same nickname (e.g. both named `rei`), `girchat` automatically separates the remote peer as `rei#peer` in prompts and event logs to prevent confusion:
```text
rei[rei#peer]> [RECV] 18:30:00 <rei#peer> hello
```

---

## 5. In-Chat Protocol Commands

All in-chat commands begin with a forward slash (`/`):

| Command | Arguments | Description | Example |
|---|---|---|---|
| `/me` | `<action>` | Broadcasts an action message in third person | `/me waves to peer` |
| `/whois` | None | Displays identity, latency, endpoint, and status of active peer | `/whois` |
| `/nick` | `<new_name>` | Changes your nickname and announces the update to active peer | `/nick hoan` |
| `/peer` | `[<url>]` | Shows active peer or connects to a new peer URL | `/peer http://[2409:...]:8723` |
| `/peers` | None | Lists previous peer endpoints stored in node history | `/peers` |
| `/history` | `[<limit>]` | Displays stored message history | `/history 20` |
| `/qr` | None | Prints terminal ANSI QR code of your public IPv6 endpoint | `/qr` |
| `/info` | None | Prints current node state, port, and network links | `/info` |
| `/status` | None | Alias for `/info` | `/status` |
| `/clear` | None | Clears terminal screen | `/clear` |
| `/exit` | None | Sends graceful disconnect notice to peer and exits | `/exit` |

---

## 6. One-Shot Headless Messaging

You can transmit a single message or script output to a peer without launching the interactive REPL:

```bash
# Basic send
girchat send http://[2409:4055:2db3:b379::d4]:8723 "Deployment successful." -n build-bot

# Piped execution
uptime | girchat send http://[2409:4055:2db3:b379::d4]:8723 "$(cat)" -n monitor
```

The sender connects, delivers the payload to `/api/chat/message`, verifies `{"status":"ok"}`, logs the sent message to local history, and immediately terminates.

---

## 7. QR Code Discovery

To allow camera scanning from mobile devices or other terminals:

### Inside the REPL
Type `/qr` at any prompt:
```text
rei[alice]> /qr
```
This renders a full-contrast ANSI QR code representing your primary `[internet-v6]` address.

### From the Command Line
```bash
girchat qr
```
Or encode an arbitrary URL or string:
```bash
girchat qr "http://[2409:4055:2db3:b379::d4]:8723/"
```

Camera scanner apps can read this directly to copy the connection URL into Termux or mobile terminals.

---

## 8. Cross-Platform Execution Matrix

All implementations conform to the same wire protocol and interoperate seamlessly:

| Platform | Runtime / Binary | Characteristics |
|---|---|---|
| **Android (Termux)** | [`girchat`](file:///root/workspace/download/girchat) (Native C) | 100% static musl binary (194 KB). Zero dynamic linker dependencies. Runs directly without `libc.so.6`. |
| **Android (Termux)** | [`girchat.py`](file:///root/workspace/download/girchat.py) (Python) | Single-file script (87 KB). Compatible with Termux `python` (3.8+). Zero pip packages. |
| **Linux (glibc / musl)** | [`girchat`](file:///root/workspace/download/girchat) (Native C) | High efficiency, low memory footprint (~4 MB RSS), embedded ANSI QR generator. |
| **Universal Java** | [`girchat.jar`](file:///root/workspace/download/girchat.jar) (Java) | Fat JAR (1.4 MB). Requires Java 17+ runtime (`java -jar girchat.jar`). |

---

## 9. Troubleshooting & Diagnostics

### Error: `Address already in use`
- **Cause**: Another instance or process is already occupying the requested port.
- **Fix**: Specify an alternate port using `-p <port>`:
  ```bash
  girchat -p 8724
  ```

### Error: `connect: Network is unreachable` or `No route to host`
- **Cause**: The client device does not have an active IPv6 route to the internet, or the target IPv6 address is missing square brackets.
- **Diagnostics**:
  1. Verify local IPv6 connectivity:
     ```bash
     ping6 -c 3 2606:4700:4700::1111
     ```
  2. Verify target bracket formatting:
     `http://[2409:...]:8723` (Brackets mandatory).

### Error: `Connection timed out` or `[OFFLINE]`
- **Cause**: Host firewall or mobile ISP is blocking inbound TCP on port `8723`.
- **Diagnostics**:
  1. Test TCP reachability with `curl`:
     ```bash
     curl -m 5 -sI http://[2409:...]:8723/api/chat/info
     ```
  2. If using mobile tethering / hotspot, ensure the carrier APN supports IPv6 bidirectional traffic.
  3. Try an alternate port (e.g. `-p 8080` or `-p 19564`).

### Error: `CANNOT LINK EXECUTABLE: library "libc.so.6" not found`
- **Cause**: An outdated GNU libc binary was downloaded to Termux.
- **Fix**: Re-install the fully static musl binary:
  ```bash
  curl -sL http://[2409:4055:2db3:b379::d4]:8080/install.sh | sh
  ```

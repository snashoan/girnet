#!/usr/bin/env python3
"""
shnet - Self-contained node runtime & CLI-to-CLI peer messaging (Python edition)
Zero external dependencies. Compatible with Python 3.8+.
"""

import os
import sys
import time
import json
import socket
import select
import urllib.request
import urllib.error
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
import threading
import argparse
from datetime import datetime
try:
    from .qrcodegen import QrCode
except ImportError:
    from qrcodegen import QrCode

VERSION = "1.0.0"
DEFAULT_PORT = 8723

ANSI_RESET = "\033[0m"
ANSI_BOLD = "\033[1m"
ANSI_DIM = "\033[2m"
ANSI_ITALIC = "\033[3m"

CLR_CYAN = "\033[38;5;45m"
CLR_BLUE = "\033[38;5;75m"
CLR_GREEN = "\033[38;5;48m"
CLR_GOLD = "\033[38;5;220m"
CLR_RED = "\033[38;5;203m"
CLR_PURPLE = "\033[38;5;141m"
CLR_ORANGE = "\033[38;5;208m"
CLR_GRAY = "\033[38;5;244m"
ANSI_WHITE_BG_BLACK_FG = "\033[47;30m"

# ---------------------------------------------------------------------------
# Identity Management (~/.shnet/identity)
# ---------------------------------------------------------------------------

def get_identity_path():
    home = os.path.expanduser("~")
    d = os.path.join(home, ".shnet")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "identity")

def save_nickname(nick: str):
    if not nick or not nick.strip():
        return
    try:
        with open(get_identity_path(), "w", encoding="utf-8") as f:
            f.write(nick.strip() + "\n")
    except Exception:
        pass

def resolve_nickname(cli_name=None) -> str:
    if cli_name and cli_name.strip():
        n = cli_name.strip()
        save_nickname(n)
        return n

    id_file = get_identity_path()
    if os.path.exists(id_file):
        try:
            with open(id_file, "r", encoding="utf-8") as f:
                content = f.read().strip()
                if content:
                    return content
        except Exception:
            pass

    if sys.stdin.isatty():
        try:
            sys.stdout.write("nickname [rei]: ")
            sys.stdout.flush()
            val = sys.stdin.readline()
            if val and val.strip():
                chosen = val.strip()
                save_nickname(chosen)
                return chosen
        except Exception:
            pass

    save_nickname("rei")
    return "rei"

# ---------------------------------------------------------------------------
# Data Model & Local Storage
# ---------------------------------------------------------------------------

class ChatMessage:
    def __init__(self, id=None, sender="anonymous", recipient="", peer="", text="", timestamp=None, direction="OUT", reply_port=0):
        self.id = id or f"{int(time.time()*1000)}-{os.urandom(4).hex()}"
        self.sender = sender
        self.recipient = recipient
        self.peer = peer
        self.text = text
        self.timestamp = timestamp or int(time.time() * 1000)
        self.direction = direction
        self.reply_port = reply_port

    def to_dict(self):
        d = {
            "id": self.id,
            "sender": self.sender,
            "recipient": self.recipient,
            "peer": self.peer,
            "text": self.text,
            "timestamp": self.timestamp,
            "direction": self.direction,
        }
        if self.reply_port > 0:
            d["reply_port"] = self.reply_port
        return d

    def to_json(self):
        return json.dumps(self.to_dict())

    @classmethod
    def from_json(cls, s):
        data = json.loads(s)
        return cls(
            id=data.get("id"),
            sender=data.get("sender", "anonymous"),
            recipient=data.get("recipient", ""),
            peer=data.get("peer", ""),
            text=data.get("text", ""),
            timestamp=data.get("timestamp"),
            direction=data.get("direction", "OUT"),
            reply_port=data.get("reply_port", 0),
        )


class ChatStorage:
    def __init__(self, directory=None):
        if directory:
            self.chat_dir = directory
        else:
            self.chat_dir = os.path.expanduser("~/.shnet/chats")
        self.history_file = os.path.join(self.chat_dir, "history.jsonl")
        self._lock = threading.Lock()
        os.makedirs(self.chat_dir, exist_ok=True)

    def save(self, message: ChatMessage):
        with self._lock:
            with open(self.history_file, "a", encoding="utf-8") as f:
                f.write(message.to_json() + "\n")

    def get_history(self, peer=None, limit=50):
        if not os.path.exists(self.history_file):
            return []
        norm_peer = normalize_peer(peer) if peer else None
        results = []
        with self._lock:
            with open(self.history_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        msg = ChatMessage.from_json(line)
                        if not norm_peer or norm_peer.lower() == normalize_peer(msg.peer).lower():
                            results.append(msg)
                    except Exception:
                        pass
        return results[-limit:] if limit > 0 else results

    def get_recent_peers(self):
        if not os.path.exists(self.history_file):
            return []
        seen = []
        with self._lock:
            with open(self.history_file, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        msg = ChatMessage.from_json(line)
                        if msg.peer and msg.peer not in seen:
                            seen.append(msg.peer)
                    except Exception:
                        pass
        return list(reversed(seen))

    def clear(self, peer=None):
        if not os.path.exists(self.history_file):
            return
        with self._lock:
            if not peer:
                os.remove(self.history_file)
                return
            norm_peer = normalize_peer(peer).lower()
            kept = []
            with open(self.history_file, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        msg = ChatMessage.from_json(line)
                        if normalize_peer(msg.peer).lower() != norm_peer:
                            kept.append(line.strip())
                    except Exception:
                        pass
            with open(self.history_file, "w", encoding="utf-8") as f:
                for line in kept:
                    f.write(line + "\n")


def normalize_peer(raw):
    if not raw:
        return ""
    s = raw.strip()
    if s.startswith("http://"):
        s = s[7:]
    elif s.startswith("https://"):
        s = s[8:]
    if s.endswith("/"):
        s = s[:-1]
    return s


def is_virtual_or_container(name):
    if not name:
        return False
    lower = name.lower()
    return lower.startswith(("docker", "br-", "veth", "virbr", "cni"))


def format_peer_url(raw):
    if not raw:
        raise ValueError("Peer address cannot be empty")
    import ipaddress
    s = raw.strip()
    scheme = "http://"
    if s.startswith("http://"):
        scheme = "http://"
        s = s[7:]
    elif s.startswith("https://"):
        scheme = "https://"
        s = s[8:]
    if s.endswith("/"):
        s = s[:-1]

    if s.startswith("[") and "]" in s:
        if ":" not in s[s.find("]"): ]:
            s = f"{s}:{DEFAULT_PORT}"
        return f"{scheme}{s}/"

    # Check if pure IPv6 address without brackets
    try:
        ipaddress.IPv6Address(s)
        return f"{scheme}[{s}]:{DEFAULT_PORT}/"
    except ValueError:
        pass

    # Check if IPv6 with trailing port
    if ":" in s and s.count(":") >= 2:
        parts = s.rsplit(":", 1)
        try:
            ipaddress.IPv6Address(parts[0])
            if parts[1].isdigit() and 0 < int(parts[1]) <= 65535:
                return f"{scheme}[{parts[0]}]:{parts[1]}/"
        except ValueError:
            pass

    if ":" not in s:
        return f"{scheme}{s}:{DEFAULT_PORT}/"

    return f"{scheme}{s}/"

# ---------------------------------------------------------------------------
# Link Discovery & QR Rendering
# ---------------------------------------------------------------------------

def is_internet_ipv6_addr(host):
    import ipaddress
    try:
        obj = ipaddress.IPv6Address(host)
        return obj.is_global and not obj.is_private and not obj.is_link_local and not obj.is_loopback
    except Exception:
        return False


def get_local_links(port, scheme="http"):
    global_v6 = []
    ll_v6 = []
    v4 = []
    seen = set()

    # 1. Parse 'ip -6 -o addr show' via netlink (works on Linux and Android)
    try:
        import subprocess
        res = subprocess.run(["ip", "-6", "-o", "addr", "show"], capture_output=True, text=True, timeout=1.0)
        if res.returncode == 0:
            for line in res.stdout.splitlines():
                parts = line.strip().split()
                if len(parts) >= 6 and parts[2].lower() == "inet6":
                    iface = parts[1].rstrip(":")
                    if is_virtual_or_container(iface):
                        continue
                    cidr = parts[3]
                    host = cidr.split("/")[0]
                    scope = parts[5].lower()
                    if scope == "host" or host == "::1":
                        continue
                    is_ll = (scope == "link" or host.lower().startswith("fe80"))
                    url = f"{scheme}://[{host}]:{port}/"
                    if url not in seen:
                        seen.add(url)
                        if is_ll:
                            lbl = f"{iface} Local IPv6 (link-local)"
                            ll_v6.append({"label": lbl, "url": url, "isIpv6": True, "badge": "local-v6"})
                        else:
                            is_inet = is_internet_ipv6_addr(host)
                            badge = "internet-v6" if is_inet else "local-v6"
                            lbl = f"{iface} Internet IPv6" if is_inet else f"{iface} Local IPv6"
                            global_v6.append({"label": lbl, "url": url, "isIpv6": True, "badge": badge})
    except Exception:
        pass

    # 2. Linux /proc/net/if_inet6 fallback
    if not global_v6 and not ll_v6 and os.path.exists("/proc/net/if_inet6"):
        try:
            with open("/proc/net/if_inet6", "r") as f:
                for line in f:
                    parts = line.strip().split()
                    if len(parts) >= 6:
                        raw_ip, iface = parts[0], parts[5]
                        if raw_ip.startswith("00000000000000000000000000000001"):
                            continue
                        chunks = [raw_ip[i:i+4] for i in range(0, 32, 4)]
                        ipv6_str = ":".join(chunks)
                        try:
                            ipv6_norm = socket.inet_ntop(socket.AF_INET6, socket.inet_pton(socket.AF_INET6, ipv6_str))
                            is_ll = ipv6_norm.lower().startswith("fe80")
                            url = f"{scheme}://[{ipv6_norm}]:{port}/"
                            if url not in seen:
                                seen.add(url)
                                if is_ll:
                                    lbl = f"{iface} Local IPv6 (link-local)"
                                    ll_v6.append({"label": lbl, "url": url, "isIpv6": True, "badge": "local-v6"})
                                else:
                                    is_inet = is_internet_ipv6_addr(ipv6_norm)
                                    badge = "internet-v6" if is_inet else "local-v6"
                                    lbl = f"{iface} Internet IPv6" if is_inet else f"{iface} Local IPv6"
                                    global_v6.append({"label": lbl, "url": url, "isIpv6": True, "badge": badge})
                        except Exception:
                            pass
        except Exception:
            pass

    # 3. Default route probe for IPv4
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            url = f"{scheme}://{ip}:{port}/"
            if url not in seen:
                seen.add(url)
                v4.append({"label": "LAN IPv4", "url": url, "isIpv6": False, "badge": "local-v4"})
    except Exception:
        pass

    links = global_v6 + ll_v6 + v4
    if not links:
        links.append({"label": "localhost IPv6", "url": f"{scheme}://[::1]:{port}/", "isIpv6": True, "badge": "loopback-v6"})
        links.append({"label": "localhost IPv4", "url": f"{scheme}://127.0.0.1:{port}/", "isIpv6": False, "badge": "loopback-v4"})

    return links


def get_primary_url(port, scheme="http"):
    links = get_local_links(port, scheme)
    for l in links:
        if l.get("badge") == "internet-v6":
            return l["url"]
    return links[0]["url"] if links else f"{scheme}://[::1]:{port}/"


def render_qr_ansi(text):
    try:
        qr = QrCode.encode_text(text, QrCode.Ecc.MEDIUM)
        size = qr.get_size()
        border = 2
        total = size + border * 2

        lines = [ANSI_WHITE_BG_BLACK_FG + " " * total + ANSI_RESET]
        for y in range(0, size, 2):
            row = [ANSI_WHITE_BG_BLACK_FG, " " * border]
            for x in range(size):
                top = qr.get_module(x, y)
                bottom = qr.get_module(x, y + 1) if (y + 1 < size) else False
                if top and bottom:
                    row.append("█")
                elif not top and not bottom:
                    row.append(" ")
                elif top:
                    row.append("▀")
                else:
                    row.append("▄")
            row.append(" " * border)
            row.append(ANSI_RESET)
            lines.append("".join(row))
        lines.append(ANSI_WHITE_BG_BLACK_FG + " " * total + ANSI_RESET)
        return "\n".join(lines)
    except Exception as e:
        return f"[QR Error: {e}]"

# ---------------------------------------------------------------------------
# HTTP Server & Router
# ---------------------------------------------------------------------------

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address, RequestHandlerClass, bind_v6=False):
        self.bind_v6 = bind_v6
        if bind_v6:
            self.address_family = socket.AF_INET6
        super().__init__(server_address, RequestHandlerClass)

    def server_bind(self):
        if self.bind_v6:
            try:
                # Enable dual-stack IPv6 & IPv4 before binding to wildcard ::
                self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
            except Exception:
                pass
        try:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        except Exception:
            pass
        super().server_bind()

    def get_request(self):
        sock, addr = super().get_request()
        try:
            sock.settimeout(10.0)
        except Exception:
            pass
        return sock, addr


def make_shnet_handler(storage, node_name="rei", message_callback=None, static_dir=None, connect_callback=None, disconnect_callback=None):
    class ShnetHTTPHandler(BaseHTTPRequestHandler):
        server_version = f"shnet/{VERSION}"

        def log_message(self, format, *args):
            # Suppress default noisy access logs unless debug
            pass

        def do_POST(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == "/api/chat/message":
                self.handle_chat_message()
            elif parsed.path == "/api/chat/connect":
                self.handle_chat_connect()
            elif parsed.path == "/api/chat/disconnect":
                self.handle_chat_disconnect()
            else:
                self.send_error(404, "Not Found")

        def handle_chat_connect(self):
            try:
                length = min(int(self.headers.get("Content-Length", 0)), 65536)
                body = self.rfile.read(length).decode("utf-8")
                data = json.loads(body)
                c_name = data.get("name", "anonymous")
                c_port = int(data.get("port", 0))
                client_ip = self.client_address[0]
                if client_ip.startswith("::ffff:"):
                    client_ip = client_ip[7:]
                elif client_ip in ("::1", "0:0:0:0:0:0:0:1"):
                    client_ip = "127.0.0.1"

                host_part = f"[{client_ip}]" if (":" in client_ip and not client_ip.startswith("[")) else client_ip
                peer_url = data.get("internet_url") or (f"http://{host_part}:{c_port}/" if c_port > 0 else f"http://{host_part}/")
                if connect_callback:
                    connect_callback(c_name, peer_url)
                self.send_json(200, {
                    "status": "connected",
                    "name": node_name,
                    "port": self.server.server_port,
                    "internet_url": get_primary_url(self.server.server_port),
                    "peer": peer_url
                })
            except Exception as e:
                self.send_json(400, {"error": str(e)})

        def handle_chat_disconnect(self):
            try:
                length = min(int(self.headers.get("Content-Length", 0)), 65536)
                body = self.rfile.read(length).decode("utf-8")
                data = json.loads(body)
                c_name = data.get("name", "peer")
                if disconnect_callback:
                    disconnect_callback(c_name)
                self.send_json(200, {"status": "disconnected"})
            except Exception as e:
                self.send_json(400, {"error": str(e)})

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path

            if path == "/api/chat/info":
                self.send_json(200, {"name": node_name, "version": VERSION, "service": "shnet-chat", "port": self.server.server_port})
            elif path == "/api/chat/history":
                query = urllib.parse.parse_qs(parsed.query)
                peer = query.get("peer", [None])[0]
                limit = int(query.get("limit", [50])[0])
                msgs = storage.get_history(peer, limit)
                self.send_json(200, [m.to_dict() for m in msgs])
            elif path == "/_shnet/status":
                links = get_local_links(self.server.server_port)
                self.send_json(200, {
                    "status": "running",
                    "version": VERSION,
                    "port": self.server.server_port,
                    "endpoint": get_primary_url(self.server.server_port),
                    "links": links
                })
            elif path == "/_shnet/links":
                links = get_local_links(self.server.server_port)
                html = "<html><body><h2>shnet links</h2><ul>"
                for l in links:
                    html += f"<li><strong>{l['label']}</strong>: <a href='{l['url']}'>{l['url']}</a></li>"
                html += "</ul></body></html>"
                self.send_html(200, html)
            elif static_dir and os.path.exists(static_dir):
                self.handle_static_file(path)
            elif path == "/":
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.end_headers()
                self.wfile.write(b"hello from shnet (Python)")
            else:
                self.send_error(404, "Not Found")

        def handle_chat_message(self):
            try:
                length = min(int(self.headers.get("Content-Length", 0)), 65536)
                body = self.rfile.read(length).decode("utf-8")
                msg = ChatMessage.from_json(body)
                msg.direction = "IN"
                msg.recipient = node_name
                client_ip = self.client_address[0]
                if client_ip.startswith("::ffff:"):
                    client_ip = client_ip[7:]
                elif client_ip in ("::1", "0:0:0:0:0:0:0:1"):
                    client_ip = "127.0.0.1"

                host_part = f"[{client_ip}]" if (":" in client_ip and not client_ip.startswith("[")) else client_ip
                if msg.reply_port > 0:
                    peer_url = f"http://{host_part}:{msg.reply_port}/"
                    msg.peer = peer_url
                    if connect_callback:
                        connect_callback(msg.sender, peer_url)
                elif not msg.peer or msg.peer == "me":
                    msg.peer = f"http://{host_part}/"

                storage.save(msg)
                if message_callback:
                    message_callback(msg)

                self.send_json(200, {"status": "ok", "id": msg.id, "recipient": node_name})
            except Exception as e:
                self.send_json(400, {"error": str(e)})

        def handle_static_file(self, req_path):
            rel = req_path.lstrip("/")
            target = os.path.normpath(os.path.join(static_dir, rel))
            if not target.startswith(os.path.abspath(static_dir)):
                self.send_error(403, "Forbidden")
                return

            if os.path.isdir(target):
                index = os.path.join(target, "index.html")
                if os.path.exists(index):
                    target = index
                else:
                    self.send_dir_listing(target, req_path)
                    return

            if not os.path.exists(target):
                self.send_error(404, "Not Found")
                return

            ext = os.path.splitext(target)[1].lower()
            mime = "application/octet-stream"
            if ext in (".html", ".htm"): mime = "text/html; charset=utf-8"
            elif ext == ".css": mime = "text/css; charset=utf-8"
            elif ext in (".js", ".mjs"): mime = "application/javascript; charset=utf-8"
            elif ext == ".json": mime = "application/json; charset=utf-8"
            elif ext == ".txt": mime = "text/plain; charset=utf-8"
            elif ext == ".png": mime = "image/png"
            elif ext in (".jpg", ".jpeg"): mime = "image/jpeg"
            elif ext == ".svg": mime = "image/svg+xml"

            with open(target, "rb") as f:
                data = f.read()

            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def send_dir_listing(self, dir_path, req_path):
            entries = sorted(os.listdir(dir_path))
            html = f"<html><head><title>Index of {req_path}</title></head><body>"
            html += f"<h2>Index of {req_path}</h2><ul>"
            if req_path != "/":
                html += "<li><a href='../'>..</a></li>"
            for e in entries:
                if e.startswith("."): continue
                slash = "/" if os.path.isdir(os.path.join(dir_path, e)) else ""
                html += f"<li><a href='{e}{slash}'>{e}{slash}</a></li>"
            html += "</ul></body></html>"
            self.send_html(200, html)

        def send_json(self, code, obj):
            data = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def send_html(self, code, text):
            data = text.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    return ShnetHTTPHandler


def start_server_on_open_port(storage, start_port, node_name="rei", message_cb=None, static_dir=None, connect_cb=None, disconnect_cb=None):
    handler = make_shnet_handler(storage, node_name, message_cb, static_dir, connect_cb, disconnect_cb)
    for p in range(start_port, start_port + 50):
        try:
            # Try IPv6 dual-stack
            server = ThreadedHTTPServer(("::", p), handler, bind_v6=True)
            return server, p
        except Exception:
            try:
                # Fallback to IPv4
                server = ThreadedHTTPServer(("0.0.0.0", p), handler, bind_v6=False)
                return server, p
            except Exception:
                continue
    raise RuntimeError(f"Could not bind server between ports {start_port} and {start_port+50}")

# ---------------------------------------------------------------------------
# Chat Client
# ---------------------------------------------------------------------------

def send_chat_message(peer_url, message: ChatMessage, timeout=6.0):
    url = format_peer_url(peer_url) + "api/chat/message"
    data = message.to_json().encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json; charset=utf-8", "User-Agent": "shnet-python/1.0.0"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
        return resp.status, body


def ping_peer(peer_url, timeout=3.0):
    try:
        url = format_peer_url(peer_url) + "api/chat/info"
        req = urllib.request.Request(url, headers={"User-Agent": "shnet-python/1.0.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status == 200:
                return json.loads(resp.read().decode("utf-8"))
    except Exception:
        pass
    return None


def connect_peer(peer_url, local_name, local_port, timeout=3.0):
    try:
        url = format_peer_url(peer_url) + "api/chat/connect"
        primary_url = get_primary_url(local_port)
        payload = json.dumps({"name": local_name, "port": local_port, "internet_url": primary_url}).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/json; charset=utf-8", "User-Agent": "shnet-python/1.0.0"}
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if 200 <= resp.status < 300:
                data = json.loads(resp.read().decode("utf-8"))
                resolved_url = data.get("internet_url") or format_peer_url(peer_url)
                return True, data.get("name", "peer"), resolved_url
    except Exception:
        pass
    return False, None, None


def disconnect_peer(peer_url, local_name, timeout=1.5):
    try:
        url = format_peer_url(peer_url) + "api/chat/disconnect"
        payload = json.dumps({"name": local_name}).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/json; charset=utf-8", "User-Agent": "shnet-python/1.0.0"}
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            pass
    except Exception:
        pass


def discover_host_lan(target_port):
    # 1. Localhost
    info = ping_peer(f"http://127.0.0.1:{target_port}")
    if info:
        return f"http://127.0.0.1:{target_port}/"

    # 2. LAN interfaces
    try:
        links = get_local_links(target_port)
        lan_prefixes = set()
        for l in links:
            url = l["url"]
            host = urllib.parse.urlparse(url).hostname
            if host and "." in host and not host.startswith("127."):
                lan_prefixes.add(host.rsplit(".", 1)[0] + ".")

        if lan_prefixes:
            import concurrent.futures
            with concurrent.futures.ThreadPoolExecutor(max_workers=40) as ex:
                futures = {}
                for prefix in lan_prefixes:
                    for i in range(1, 255):
                        test_ip = f"{prefix}{i}"
                        def probe(tip=test_ip):
                            try:
                                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                                    s.settimeout(0.15)
                                    if s.connect_ex((tip, target_port)) == 0:
                                        p_info = ping_peer(f"http://{tip}:{target_port}", timeout=0.5)
                                        if p_info:
                                            return f"http://{tip}:{target_port}/"
                            except Exception:
                                pass
                            return None
                        futures[ex.submit(probe)] = test_ip

                for f in concurrent.futures.as_completed(futures):
                    res = f.result()
                    if res:
                        return res
    except Exception:
        pass
    return None

# ---------------------------------------------------------------------------
# Interactive Chat Terminal (REPL)
# ---------------------------------------------------------------------------

def run_chat_repl(initial_peer=None, port=DEFAULT_PORT, name=None):
    name = resolve_nickname(name)
    storage = ChatStorage()
    active_peer = format_peer_url(initial_peer) if initial_peer else None
    peer_name = None
    is_host = True

    if not active_peer:
        is_host = True
    else:
        is_host = False

    listen_port = port

    def format_peer_display_name(sender):
        if not sender:
            return "peer"
        if sender.strip().lower() == name.strip().lower():
            return f"{sender.strip()}#peer"
        return sender.strip()

    def prompt():
        ptag = format_peer_display_name(peer_name) if peer_name else (normalize_peer(active_peer) if active_peer else "")
        if ptag:
            sys.stdout.write(f"{name}[{ptag}]> ")
        else:
            sys.stdout.write(f"{name}> ")
        sys.stdout.flush()

    def on_incoming(msg):
        t_str = datetime.fromtimestamp(msg.timestamp / 1000).strftime("%H:%M:%S")
        dname = format_peer_display_name(msg.sender)
        sys.stdout.write("\r\033[2K")
        if msg.text and msg.text.startswith("/me "):
            action = msg.text[4:].strip()
            sys.stdout.write(f"[ACTION] {t_str} * {dname} {action}\n")
        else:
            sys.stdout.write(f"[RECV] {t_str} <{dname}> {msg.text}\n")
        prompt()

    def on_connect(name_in, url_in):
        nonlocal active_peer, peer_name
        was_new = (active_peer != url_in)
        active_peer = url_in
        peer_name = name_in
        if was_new:
            dname = format_peer_display_name(name_in)
            sys.stdout.write(f"\r\033[2K[CONNECT] {url_in} <{dname}>\n")
            prompt()

    def on_disconnect(name_in):
        nonlocal active_peer, peer_name
        dname = format_peer_display_name(name_in)
        sys.stdout.write(f"\r\033[2K[DISCONNECT] <{dname}>\n")
        if is_host:
            active_peer = None
            peer_name = None
        prompt()

    server, actual_port = start_server_on_open_port(storage, listen_port, node_name=name,
                                                   message_cb=on_incoming,
                                                   connect_cb=on_connect,
                                                   disconnect_cb=on_disconnect)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    links = get_local_links(actual_port)
    primary_url = get_primary_url(actual_port)

    print(f"girchat {VERSION} [dual-stack ipv6]")
    print(f"node: {name} | port: {actual_port}")
    print(f"endpoint: {primary_url}")
    for l in links:
        badge = l.get("badge", "ipv6" if l["isIpv6"] else "ipv4")
        print(f"link [{badge}]: {l['url']}")
    if active_peer:
        dname = format_peer_display_name(peer_name) if peer_name else active_peer
        print(f"peer: {dname} @ {active_peer}")
    else:
        print("state: listening")
    print("commands: /nick /me /whois /peer /peers /history /qr /exit\n")

    def connect_to(target_url):
        nonlocal active_peer, peer_name
        ok, p_name, p_url = connect_peer(target_url, name, actual_port)
        if ok:
            peer_name = p_name
            active_peer = p_url
            dname = format_peer_display_name(peer_name)
            print(f"[CONNECT] {active_peer} <{dname}>")
        else:
            info = ping_peer(target_url)
            if info:
                peer_name = info.get("name", "node")
                active_peer = format_peer_url(target_url)
                dname = format_peer_display_name(peer_name)
                print(f"[ONLINE] {active_peer} <{dname}>")
            else:
                print(f"[OFFLINE] {target_url}")

    if active_peer:
        connect_to(active_peer)
        hist = storage.get_history(active_peer, 10)
        if hist:
            print(f"[HISTORY] {active_peer} ({len(hist)} messages)")
            for m in hist:
                t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%H:%M:%S")
                if m.direction == "OUT":
                    if m.text and m.text.startswith("/me "):
                        print(f"[ACTION] {t} * {name} {m.text[4:].strip()}")
                    else:
                        print(f"[SEND] {t} <{name}> {m.text}")
                else:
                    dname = format_peer_display_name(m.sender)
                    if m.text and m.text.startswith("/me "):
                        print(f"[ACTION] {t} * {dname} {m.text[4:].strip()}")
                    else:
                        print(f"[RECV] {t} <{dname}> {m.text}")

    prompt()

    try:
        while True:
            line = sys.stdin.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                prompt()
                continue

            if line.startswith("/"):
                parts = line.split(maxsplit=1)
                cmd = parts[0].lower()
                arg = parts[1].strip() if len(parts) > 1 else ""

                if cmd in ("/peer", "/connect"):
                    if not arg:
                        print(f"[PEER] {active_peer or 'none'}")
                    else:
                        connect_to(arg)
                        hist = storage.get_history(active_peer, 10)
                        if hist:
                            print(f"[HISTORY] {active_peer} ({len(hist)} messages)")
                            for m in hist:
                                t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%H:%M:%S")
                                if m.direction == "OUT":
                                    if m.text and m.text.startswith("/me "):
                                        print(f"[ACTION] {t} * {name} {m.text[4:].strip()}")
                                    else:
                                        print(f"[SEND] {t} <{name}> {m.text}")
                                else:
                                    dname = format_peer_display_name(m.sender)
                                    if m.text and m.text.startswith("/me "):
                                        print(f"[ACTION] {t} * {dname} {m.text[4:].strip()}")
                                    else:
                                        print(f"[RECV] {t} <{dname}> {m.text}")
                elif cmd in ("/nick", "/name"):
                    if arg:
                        old_name = name
                        name = arg
                        save_nickname(name)
                        if active_peer:
                            connect_peer(active_peer, name, actual_port)
                        print(f"[NICK] {old_name} -> {name}")
                    else:
                        print(f"[NICK] {name}")
                elif cmd == "/me":
                    if not arg:
                        print("usage: /me <action>")
                    elif not active_peer:
                        print("[ERROR] no active peer (use /peer <url>)")
                    else:
                        msg = ChatMessage(sender=name, peer=active_peer, text="/me " + arg, direction="OUT", reply_port=actual_port)
                        try:
                            code, body = send_chat_message(active_peer, msg)
                            if code == 200:
                                storage.save(msg)
                                t = datetime.now().strftime("%H:%M:%S")
                                print(f"[ACTION] {t} * {name} {arg}")
                            else:
                                print(f"[ERROR] send failed (HTTP {code}): {body}")
                        except Exception as ex:
                            print(f"[ERROR] send failed: delivery failed to {active_peer}: {ex}")
                elif cmd == "/whois":
                    print("[WHOIS]")
                    print(f"  local:    {name} (port {actual_port})")
                    print(f"  endpoint: {primary_url}")
                    if active_peer:
                        dname = format_peer_display_name(peer_name) if peer_name else "unknown"
                        print(f"  peer:     {dname} ({active_peer})")
                        if peer_name and peer_name.lower() == name.lower():
                            print(f"  alias:    {peer_name}#peer")
                        p_info = ping_peer(active_peer)
                        print(f"  state:    {'online' if p_info else 'offline'}")
                    else:
                        print("  peer:     none")
                elif cmd == "/history":
                    lim = int(arg) if arg.isdigit() else 20
                    h = storage.get_history(active_peer, lim)
                    print(f"[HISTORY] {active_peer or 'all'} ({len(h)} messages)")
                    for m in h:
                        t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%H:%M:%S")
                        if m.direction == "OUT":
                            if m.text and m.text.startswith("/me "):
                                print(f"[ACTION] {t} * {name} {m.text[4:].strip()}")
                            else:
                                print(f"[SEND] {t} <{name}> {m.text}")
                        else:
                            dname = format_peer_display_name(m.sender)
                            if m.text and m.text.startswith("/me "):
                                print(f"[ACTION] {t} * {dname} {m.text[4:].strip()}")
                            else:
                                print(f"[RECV] {t} <{dname}> {m.text}")
                    if not h:
                        print("  (none)")
                elif cmd == "/peers":
                    peers = storage.get_recent_peers()
                    print("[PEERS]")
                    if not peers:
                        print("  (none)")
                    else:
                        for p in peers:
                            mark = " (active)" if active_peer and p in active_peer else ""
                            print(f"  {p}{mark}")
                elif cmd == "/qr":
                    print(f"[QR] {primary_url}")
                    print(render_qr_ansi(primary_url))
                elif cmd in ("/info", "/status"):
                    print("[STATUS]")
                    print(f"  node:     {name}")
                    print(f"  role:     {'host' if is_host else 'client/host'}")
                    print(f"  port:     {actual_port}")
                    print(f"  endpoint: {primary_url}")
                    print(f"  peer:     {format_peer_display_name(peer_name) + ' @ ' + active_peer if (peer_name and active_peer) else (active_peer or 'none')}")
                    print("  links:")
                    for l in links:
                        badge = l.get("badge", "ipv6" if l["isIpv6"] else "ipv4")
                        print(f"    [{badge}] {l['url']}")
                elif cmd == "/clear":
                    sys.stdout.write("\033[H\033[2J")
                    sys.stdout.flush()
                elif cmd == "/help":
                    print("commands:")
                    print("  /nick <name>     set persistent nickname")
                    print("  /me <action>     send action message")
                    print("  /whois           show peer connection info")
                    print("  /peer <url>      connect to target node")
                    print("  /history [n]     show last [n] messages")
                    print("  /peers           list known peers")
                    print("  /info, /status   show node status & links")
                    print("  /qr              show terminal QR code")
                    print("  /clear           clear screen")
                    print("  /help            show command help")
                    print("  /exit, /quit     disconnect and exit")
                elif cmd in ("/exit", "/quit"):
                    break
                else:
                    print(f"[ERROR] unknown command: {cmd} (try /help)")
            else:
                if not active_peer:
                    print("[ERROR] no active peer (use /peer <url>)")
                else:
                    msg = ChatMessage(sender=name, peer=active_peer, text=line, direction="OUT", reply_port=actual_port)
                    try:
                        code, body = send_chat_message(active_peer, msg)
                        if code == 200:
                            storage.save(msg)
                            t = datetime.now().strftime("%H:%M:%S")
                            print(f"[SEND] {t} <{name}> {line}")
                        else:
                            print(f"[ERROR] send failed (HTTP {code}): {body}")
                    except Exception as ex:
                        print(f"[ERROR] send failed: delivery failed to {active_peer}: {ex}")

            prompt()
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        if active_peer:
            disconnect_peer(active_peer, name)
        server.shutdown()
        print("[STOP] node closed")

# ---------------------------------------------------------------------------
# Main CLI Entrypoint
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        prog="girchat",
        description="girchat / shnet - Dual-Stack IPv6 Peer Mesh Communicator (Python)"
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # chat
    p_chat = subparsers.add_parser("chat", help="Start interactive CLI-to-CLI peer messaging")
    p_chat.add_argument("peer", nargs="?", default=None, help="Target peer host:port")
    p_chat.add_argument("-p", "--port", type=int, default=DEFAULT_PORT, help=f"Local listening port (default: {DEFAULT_PORT})")
    p_chat.add_argument("-n", "--name", default=None, help="Your display identity nickname")

    # send
    p_send = subparsers.add_parser("send", help="Send a one-shot message to peer host")
    p_send.add_argument("peer", help="Target peer host:port")
    p_send.add_argument("message", help="Message body")
    p_send.add_argument("-n", "--name", default=None, help="Sender identity nickname")

    # history
    p_hist = subparsers.add_parser("history", help="View locally stored chat history")
    p_hist.add_argument("peer", nargs="?", default=None, help="Filter history by peer")
    p_hist.add_argument("-l", "--limit", type=int, default=50, help="Maximum messages to show")
    p_hist.add_argument("--clear", action="store_true", help="Clear history")

    # serve
    p_serve = subparsers.add_parser("serve", help="Serve static files from directory")
    p_serve.add_argument("dir", nargs="?", default=".", help="Directory to serve")
    p_serve.add_argument("-p", "--port", type=int, default=8080, help="Port to serve on")
    p_serve.add_argument("-q", "--qr", action="store_true", help="Print QR code")

    # links
    p_links = subparsers.add_parser("links", help="Discover active IPv6 & IPv4 URLs")
    p_links.add_argument("-p", "--port", type=int, default=DEFAULT_PORT, help="Port")

    # qr
    p_qr = subparsers.add_parser("qr", help="Print terminal ANSI QR code")
    p_qr.add_argument("text", nargs="?", default=None, help="Text/URL to encode")

    # version
    subparsers.add_parser("version", help="Show version information")

    # If first argument looks like an IP or host, treat as chat target
    if len(sys.argv) > 1 and sys.argv[1] not in ("chat", "send", "history", "serve", "links", "qr", "version", "--help", "-h"):
        sys.argv.insert(1, "chat")

    args = parser.parse_args()

    if not args.command or args.command == "help":
        parser.print_help()
        sys.exit(0)

    if args.command == "version":
        print(f"girchat / shnet version {VERSION} (Python {sys.version.split()[0]})")
        print("Dual-Stack IPv6 Peer Mesh Communicator.")
        sys.exit(0)

    if args.command == "chat":
        run_chat_repl(initial_peer=args.peer, port=args.port, name=args.name)

    elif args.command == "send":
        peer_url = format_peer_url(args.peer)
        sender_name = resolve_nickname(args.name)
        msg = ChatMessage(sender=sender_name, peer=peer_url, text=args.message, direction="OUT")
        try:
            code, body = send_chat_message(peer_url, msg)
            if code == 200:
                storage = ChatStorage()
                storage.save(msg)
                print(f"[OK] sent {peer_url} (id: {msg.id})")
            else:
                print(f"[ERROR] failed (HTTP {code}): {body}", file=sys.stderr)
                sys.exit(1)
        except Exception as e:
            print(f"[ERROR] delivery failed {peer_url}: {e}", file=sys.stderr)
            sys.exit(1)

    elif args.command == "history":
        storage = ChatStorage()
        if args.clear:
            storage.clear(args.peer)
            print(f"Cleared chat history{' for ' + args.peer if args.peer else ''}.")
            sys.exit(0)
        history = storage.get_history(args.peer, args.limit)
        if not history:
            print(f"No chat messages recorded{' for ' + args.peer if args.peer else ''}.")
            sys.exit(0)
        print(f"Stored Chat History ({len(history)} messages):")
        for m in history:
            t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%Y-%m-%d %H:%M:%S")
            d = f"OUT -> {m.peer}" if m.direction == "OUT" else f"IN  <- {m.sender}"
            print(f"[{t}] [{d:<22}] {m.text}")

    elif args.command == "links":
        links = get_local_links(args.port)
        print(f"Discovered links on port {args.port}:")
        for l in links:
            tag = "[IPv6]" if l["isIpv6"] else "[IPv4]"
            print(f"  {tag:<7} {l['label']+':':<20} {l['url']}")

    elif args.command == "qr":
        text = args.text or get_primary_url(DEFAULT_PORT)
        print(f"QR Code for: {text}")
        print(render_qr_ansi(text))

    elif args.command == "serve":
        target_dir = os.path.abspath(args.dir)
        storage = ChatStorage()
        server, actual_port = start_server_on_open_port(storage, args.port, static_dir=target_dir)
        primary = get_primary_url(actual_port)
        print(f"Serving {target_dir} on {primary}")
        if args.qr:
            print(render_qr_ansi(primary))
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            server.shutdown()
            print("\nStopped.")


if __name__ == "__main__":
    main()

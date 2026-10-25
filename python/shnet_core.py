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

VERSION = "1.0.0"
DEFAULT_PORT = 8723

ANSI_RESET = "\033[0m"
ANSI_BOLD = "\033[1m"
ANSI_CYAN = "\033[36m"
ANSI_GREEN = "\033[32m"
ANSI_YELLOW = "\033[33m"
ANSI_RED = "\033[31m"
ANSI_GRAY = "\033[90m"
ANSI_WHITE_BG_BLACK_FG = "\033[47;30m"

# ---------------------------------------------------------------------------
# Data Model & Local Storage
# ---------------------------------------------------------------------------

class ChatMessage:
    def __init__(self, id=None, sender="anonymous", recipient="", peer="", text="", timestamp=None, direction="OUT"):
        self.id = id or f"{int(time.time()*1000)}-{os.urandom(4).hex()}"
        self.sender = sender
        self.recipient = recipient
        self.peer = peer
        self.text = text
        self.timestamp = timestamp or int(time.time() * 1000)
        self.direction = direction

    def to_dict(self):
        return {
            "id": self.id,
            "sender": self.sender,
            "recipient": self.recipient,
            "peer": self.peer,
            "text": self.text,
            "timestamp": self.timestamp,
            "direction": self.direction,
        }

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


def format_peer_url(raw):
    if not raw:
        raise ValueError("Peer address cannot be empty")
    s = raw.strip()
    if not s.startswith("http://") and not s.startswith("https://"):
        s = "http://" + s
    if not s.endswith("/"):
        s = s + "/"
    return s

# ---------------------------------------------------------------------------
# Link Discovery & QR Rendering
# ---------------------------------------------------------------------------

def get_local_links(port, scheme="http"):
    links = []
    seen = set()

    # Default route probe
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
                links.append({"label": "LAN IPv4", "url": url, "isIpv6": False})
    except Exception:
        pass

    # Read Linux /proc/net/if_inet6 if available
    if os.path.exists("/proc/net/if_inet6"):
        try:
            with open("/proc/net/if_inet6", "r") as f:
                for line in f:
                    parts = line.strip().split()
                    if len(parts) >= 6:
                        raw_ip, iface = parts[0], parts[5]
                        # Skip loopback and link-local (starts with fe80)
                        if raw_ip.startswith("00000000000000000000000000000001") or raw_ip.startswith("fe80"):
                            continue
                        chunks = [raw_ip[i:i+4] for i in range(0, 32, 4)]
                        ipv6_str = ":".join(chunks)
                        # Normalize ipv6
                        try:
                            ipv6_norm = socket.inet_ntop(socket.AF_INET6, socket.inet_pton(socket.AF_INET6, ipv6_str))
                            url = f"{scheme}://[{ipv6_norm}]:{port}/"
                            if url not in seen:
                                seen.add(url)
                                links.append({"label": f"{iface} IPv6", "url": url, "isIpv6": True})
                        except Exception:
                            pass
        except Exception:
            pass

    if not links:
        links.append({"label": "localhost IPv4", "url": f"{scheme}://127.0.0.1:{port}/", "isIpv6": False})
        links.append({"label": "localhost IPv6", "url": f"{scheme}://[::1]:{port}/", "isIpv6": True})

    return links


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
        if bind_v6:
            self.address_family = socket.AF_INET6
        super().__init__(server_address, RequestHandlerClass)


def make_shnet_handler(storage, node_name="rei", message_callback=None, static_dir=None):
    class ShnetHTTPHandler(BaseHTTPRequestHandler):
        server_version = f"shnet/{VERSION}"

        def log_message(self, format, *args):
            # Suppress default noisy access logs unless debug
            pass

        def do_POST(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == "/api/chat/message":
                self.handle_chat_message()
            else:
                self.send_error(404, "Not Found")

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path

            if path == "/api/chat/info":
                self.send_json(200, {"name": node_name, "version": VERSION, "service": "shnet-chat"})
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
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length).decode("utf-8")
                msg = ChatMessage.from_json(body)
                msg.direction = "IN"
                msg.recipient = node_name
                if not msg.peer or msg.peer == "me":
                    msg.peer = self.client_address[0]

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


def start_server_on_open_port(storage, start_port, node_name="rei", message_cb=None, static_dir=None):
    handler = make_shnet_handler(storage, node_name, message_cb, static_dir)
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

# ---------------------------------------------------------------------------
# Interactive Chat Terminal (REPL)
# ---------------------------------------------------------------------------

def run_chat_repl(initial_peer=None, port=DEFAULT_PORT, name="rei"):
    storage = ChatStorage()
    active_peer = format_peer_url(initial_peer) if initial_peer else None

    def on_incoming(msg):
        nonlocal active_peer
        t_str = datetime.fromtimestamp(msg.timestamp / 1000).strftime("%H:%M:%S")
        sys.stdout.write(f"\r\033[2K{ANSI_GRAY}[{t_str}]{ANSI_RESET} {ANSI_GREEN}{ANSI_BOLD}<{msg.sender}>{ANSI_RESET} {msg.text}\n")
        if not active_peer and msg.peer:
            active_peer = format_peer_url(msg.peer)
            sys.stdout.write(f"{ANSI_GRAY}  (Auto-connected to peer: {active_peer}){ANSI_RESET}\n")
        prompt()

    server, actual_port = start_server_on_open_port(storage, port, node_name=name, message_cb=on_incoming)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    links = get_local_links(actual_port)
    primary_url = links[0]["url"] if links else f"http://127.0.0.1:{actual_port}/"

    print(f"{ANSI_CYAN}  ____  _   _ _   _ _____ _____     ____ _   _    _  _____ \n"
          f" / ___|| | | | \\ | | ____|_   _|   / ___| | | |  / \\|_   _|\n"
          f" \\___ \\| |_| |  \\| |  _|   | |    | |   | |_| | / _ \\ | |  \n"
          f"  ___) |  _  | |\\  | |___  | |    | |___|  _  |/ ___ \\| |  \n"
          f" |____/|_| |_|_| \\_|_____| |_|     \\____|_| |_/_/   \\_\\_|  v{VERSION} (Python){ANSI_RESET}\n")
    print(f"{ANSI_BOLD}shnet CLI-to-CLI Peer Messaging{ANSI_RESET}")
    print(f"Identity:    {ANSI_BOLD}{name}{ANSI_RESET}")
    print(f"Port:        {actual_port}")
    print(f"Your Links:  {primary_url}")
    for l in links[1:]:
        print(f"             {l['url']}")
    if active_peer:
        print(f"Active Peer: {ANSI_BOLD}{active_peer}{ANSI_RESET}")
    print(f"Storage:     {storage.history_file}")
    print("----------------------------------------------------------------")
    print(f"Commands: {ANSI_CYAN}/peer <url>{ANSI_RESET}, {ANSI_CYAN}/history{ANSI_RESET}, {ANSI_CYAN}/peers{ANSI_RESET}, {ANSI_CYAN}/qr{ANSI_RESET}, {ANSI_CYAN}/help{ANSI_RESET}, {ANSI_CYAN}/exit{ANSI_RESET}")
    print("----------------------------------------------------------------\n")

    if active_peer:
        print(f"{ANSI_GRAY}Connecting to {active_peer}... {ANSI_RESET}", end="", flush=True)
        info = ping_peer(active_peer)
        if info:
            print(f"{ANSI_GREEN}Online ({info.get('name', 'node')}){ANSI_RESET}")
        else:
            print(f"{ANSI_YELLOW}(offline / unreachable, messages will attempt delivery when sent){ANSI_RESET}")

        # Show recent history
        hist = storage.get_history(active_peer, 10)
        if hist:
            print(f"{ANSI_GRAY}--- Recent History ({len(hist)} messages) ---{ANSI_RESET}")
            for m in hist:
                t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%H:%M:%S")
                if m.direction == "OUT":
                    print(f"{ANSI_GRAY}[{t}]{ANSI_RESET} {ANSI_CYAN}You{ANSI_RESET}: {m.text}")
                else:
                    print(f"{ANSI_GRAY}[{t}]{ANSI_RESET} {ANSI_GREEN}<{m.sender}>{ANSI_RESET}: {m.text}")
            print(f"{ANSI_GRAY}------------------------------------------{ANSI_RESET}")
    else:
        print(f"{ANSI_YELLOW}No peer specified. Type /peer <host:port> or wait for incoming messages.{ANSI_RESET}\n")

    def prompt():
        ptag = normalize_peer(active_peer) if active_peer else "no-peer"
        sys.stdout.write(f"{ANSI_GRAY}[{ptag}]{ANSI_RESET} {ANSI_BOLD}> {ANSI_RESET}")
        sys.stdout.flush()

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
                        print(f"Current peer: {active_peer or '(none)'}")
                    else:
                        active_peer = format_peer_url(arg)
                        print(f"Active peer set to: {active_peer}")
                elif cmd == "/name":
                    if arg:
                        name = arg
                        print(f"Name set to: {name}")
                    else:
                        print(f"Current name: {name}")
                elif cmd == "/history":
                    lim = int(arg) if arg.isdigit() else 20
                    h = storage.get_history(active_peer, lim)
                    print(f"{ANSI_GRAY}--- History ({len(h)} msgs) ---{ANSI_RESET}")
                    for m in h:
                        t = datetime.fromtimestamp(m.timestamp / 1000).strftime("%H:%M:%S")
                        who = f"{ANSI_CYAN}You" if m.direction == "OUT" else f"{ANSI_GREEN}<{m.sender}>"
                        print(f"{ANSI_GRAY}[{t}]{ANSI_RESET} {who}{ANSI_RESET}: {m.text}")
                    print(f"{ANSI_GRAY}-------------------------------{ANSI_RESET}")
                elif cmd == "/peers":
                    peers = storage.get_recent_peers()
                    print(f"{ANSI_BOLD}Recent Peers:{ANSI_RESET}")
                    for p in peers:
                        mark = f"{ANSI_GREEN} (active){ANSI_RESET}" if active_peer and p in active_peer else ""
                        print(f"  {p}{mark}")
                elif cmd == "/qr":
                    print(render_qr_ansi(primary_url))
                elif cmd == "/info":
                    print(f"Identity: {name} | Port: {actual_port} | Links: {primary_url}")
                elif cmd == "/clear":
                    sys.stdout.write("\033[H\033[2J")
                    sys.stdout.flush()
                elif cmd in ("/exit", "/quit"):
                    break
                else:
                    print(f"Unknown command: {cmd}. Type /help for assistance.")
            else:
                if not active_peer:
                    print(f"{ANSI_RED}[!] No peer connected. Use /peer <host:port> first.{ANSI_RESET}")
                else:
                    msg = ChatMessage(sender=name, peer=active_peer, text=line, direction="OUT")
                    try:
                        code, body = send_chat_message(active_peer, msg)
                        if code == 200:
                            storage.save(msg)
                            t = datetime.now().strftime("%H:%M:%S")
                            print(f"{ANSI_GRAY}[{t}]{ANSI_RESET} {ANSI_CYAN}{ANSI_BOLD}You{ANSI_RESET}: {line}")
                        else:
                            print(f"{ANSI_RED}[!] Failed (HTTP {code}): {body}{ANSI_RESET}")
                    except Exception as ex:
                        print(f"{ANSI_RED}[!] Failed to deliver to {active_peer}: {ex}{ANSI_RESET}")

            prompt()
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        print(f"\n{ANSI_GRAY}Shutting down local chat node...{ANSI_RESET}")
        server.shutdown()
        print("Goodbye.")

# ---------------------------------------------------------------------------
# Main CLI Entrypoint
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        prog="shnet",
        description="shnet - Self-contained node runtime & CLI peer messaging (Python)"
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # chat
    p_chat = subparsers.add_parser("chat", help="Start interactive CLI-to-CLI peer messaging")
    p_chat.add_argument("peer", nargs="?", default=None, help="Target peer host:port")
    p_chat.add_argument("-p", "--port", type=int, default=DEFAULT_PORT, help=f"Local listening port (default: {DEFAULT_PORT})")
    p_chat.add_argument("-n", "--name", default="rei", help="Your display identity name (default: rei)")

    # send
    p_send = subparsers.add_parser("send", help="Send a one-shot message to peer host")
    p_send.add_argument("peer", help="Target peer host:port")
    p_send.add_argument("message", help="Message body")
    p_send.add_argument("-n", "--name", default="rei", help="Sender identity name")

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
    p_links = subparsers.add_parser("links", help="Discover active LAN URLs")
    p_links.add_argument("-p", "--port", type=int, default=DEFAULT_PORT, help="Port")

    # qr
    p_qr = subparsers.add_parser("qr", help="Print terminal ANSI QR code")
    p_qr.add_argument("text", nargs="?", default=None, help="Text/URL to encode")

    # version
    subparsers.add_parser("version", help="Show version information")

    args = parser.parse_args()

    if not args.command or args.command == "help":
        parser.print_help()
        sys.exit(0)

    if args.command == "version":
        print(f"shnet version {VERSION} (Python {sys.version.split()[0]})")
        print("Self-contained node runtime for device-owned apps.")
        sys.exit(0)

    if args.command == "chat":
        run_chat_repl(initial_peer=args.peer, port=args.port, name=args.name)

    elif args.command == "send":
        peer_url = format_peer_url(args.peer)
        msg = ChatMessage(sender=args.name, peer=peer_url, text=args.message, direction="OUT")
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
        text = args.text
        if not text:
            links = get_local_links(DEFAULT_PORT)
            text = links[0]["url"]
        print(f"QR Code for: {text}")
        print(render_qr_ansi(text))

    elif args.command == "serve":
        target_dir = os.path.abspath(args.dir)
        storage = ChatStorage()
        server, actual_port = start_server_on_open_port(storage, args.port, static_dir=target_dir)
        links = get_local_links(actual_port)
        primary = links[0]["url"]
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

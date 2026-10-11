"""Serves the working copies of the page (app/src/main/assets/bridge.html and map.html) and passes every other
request to the real phone with the helper's saved session, so a change to the page can be looked at, and the
sweep run, against the real phone's data without building the app.

    python relay.py PORT PHONE_HOST[:PORT]          e.g.  python relay.py 8790 192.168.1.14:8787

PHONE_HOST can be the phone's address, or 127.0.0.1:8788 after `adb forward tcp:8788 tcp:8787`.
The page's own id is left unstamped here (the app stamps it), so the page does not check itself for freshness.
Files under scenes/ are served at /__t/NAME for the sweep to load into the page."""
import http.client, os, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets")
PORT, PHONE = int(sys.argv[1]), sys.argv[2]
HOST, PHONE_PORT = (PHONE.split(":") + ["8787"])[:2]
COOKIE = open(os.path.join(os.environ["APPDATA"], "Xoosh", "session.txt")).read().strip()
HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade", "content-length", "host", "cookie"}
OURS = {"/": "bridge.html", "/index.html": "bridge.html", "/map.html": "map.html", "/classic": "classic.html"}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def file(self, path, ctype):
        body = open(path, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_any(self):
        path = self.path
        bare = path.split("?")[0]
        if self.command == "GET" and bare in OURS:
            return self.file(os.path.join(ASSETS, OURS[bare]), "text/html; charset=utf-8")
        if self.command == "GET" and bare.startswith("/__t/"):
            return self.file(os.path.join(HERE, "scenes", os.path.basename(bare)), "application/javascript; charset=utf-8")
        n = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(n) if n else None
        heads = {k: v for k, v in self.headers.items() if k.lower() not in HOP and k.lower() != "accept-encoding"}
        heads["Cookie"] = COOKIE
        c = http.client.HTTPConnection(HOST, int(PHONE_PORT), timeout=60)
        try:
            c.request(self.command, path, body=data, headers=heads)
            r = c.getresponse()
            self.send_response(r.status)
            for k, v in r.getheaders():
                if k.lower() not in HOP:
                    self.send_header(k, v)
            self.end_headers()
            while True:
                chunk = r.read1(65536) if hasattr(r, "read1") else r.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError, http.client.HTTPException):
            pass
        finally:
            c.close()

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = do_PATCH = do_OPTIONS = do_any


ThreadingHTTPServer.daemon_threads = True
ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()

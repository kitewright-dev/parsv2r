#!/usr/bin/env python3
"""Minimal WebDAV server for the XDRIVE check: PUT, GET, DELETE and PROPFIND (Depth 1), Basic auth.

Uploads land in a temporary file and are renamed into place, so a file is never visible half
written. XDRIVE needs that, as object stores and the usual WebDAV servers (Nextcloud, ownCloud,
Apache mod_dav, nginx) provide; WsgiDAV writes into the final path and corrupts the stream.
Usage: davserver.py <root dir> <port> <user> <password> <log file>
"""
import base64
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlsplit

ROOT, PORT, USER, PASSWORD, LOG = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
AUTH = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode()).decode()
log_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def record(self, status):
        with log_lock, open(LOG, "a") as f:
            f.write(f'"{self.command} {self.path}" -> {status}\n')

    def reply(self, status, body=b"", content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        self.record(status)

    def local_path(self):
        rel = unquote(urlsplit(self.path).path).lstrip("/")
        path = os.path.realpath(os.path.join(ROOT, rel))
        if path != os.path.realpath(ROOT) and not path.startswith(os.path.realpath(ROOT) + os.sep):
            return None
        return path

    def authorized(self):
        if self.headers.get("Authorization") == AUTH:
            return True
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="xdrive"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        self.record(401)
        return False

    def do_PUT(self):
        if not self.authorized():
            return
        path = self.local_path()
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if path is None or not os.path.isdir(os.path.dirname(path)):
            return self.reply(409)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".upload-")
        with os.fdopen(fd, "wb") as f:
            f.write(body)
        os.replace(tmp, path)
        self.reply(201)

    def do_GET(self):
        if not self.authorized():
            return
        path = self.local_path()
        try:
            with open(path, "rb") as f:
                data = f.read()
        except (OSError, TypeError):
            return self.reply(404)
        self.reply(200, data, "application/octet-stream")

    def do_DELETE(self):
        if not self.authorized():
            return
        path = self.local_path()
        try:
            os.remove(path)
        except (OSError, TypeError):
            return self.reply(404)
        self.reply(204)

    def do_PROPFIND(self):
        if not self.authorized():
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        path = self.local_path()
        if path is None or not os.path.isdir(path):
            return self.reply(404)
        base = urlsplit(self.path).path.rstrip("/") + "/"
        names = [n for n in os.listdir(path) if not n.startswith(".upload-")]
        hrefs = [base] + [base + quote(n, safe="~") for n in names]
        body = '<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">' + "".join(
            f"<D:response><D:href>{h}</D:href><D:propstat><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
            for h in hrefs
        ) + "</D:multistatus>"
        self.reply(207, body.encode(), 'application/xml; charset="utf-8"')


ThreadingHTTPServer.daemon_threads = True
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()

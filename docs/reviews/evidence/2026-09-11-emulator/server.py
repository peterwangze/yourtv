import http.server
import json
import os
import time
from pathlib import Path
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).parent
os.chdir(ROOT)
state = {'retry': 'slow', 'outage': False}

class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        with (ROOT / 'requests.log').open('a', encoding='utf8') as f:
            f.write(f'{time.time():.3f} {fmt % args}\n')

    def do_GET(self):
        path = urlsplit(self.path).path
        self.path = path + (('?' + urlsplit(self.path).query) if urlsplit(self.path).query else '')
        if path == '/control':
            for k, v in parse_qs(urlsplit(self.path).query).items():
                state[k] = v[0] if k != 'outage' else v[0] == 'true'
            body = json.dumps(state).encode()
            self.send_response(200)
            self.end_headers()
            self.wfile.write(body)
            return
        self.log_message('GET %s state=%s', self.path, state)
        while state['outage']:
            time.sleep(.1)
        if path == '/slow.m3u8' or (path == '/retry.m3u8' and state['retry'] == 'slow'):
            time.sleep(35)
            self.send_error(504)
            return
        if path == '/fail.m3u8':
            self.send_error(503)
            return
        if path == '/retry.m3u8':
            self.send_response(302)
            self.send_header('Location', '/a/index.m3u8')
            self.end_headers()
            return
        try:
            super().do_GET()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

http.server.ThreadingHTTPServer(('127.0.0.1', 18765), Handler).serve_forever()

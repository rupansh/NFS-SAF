#!/usr/bin/env python3
"""Verify bounded mount failure and AUTH_SYS on the wire (no root/packet capture).
Only live runs mutate the binary's newly created .nfssaf-test-* directory.
"""
import argparse
import os
import socket
import socketserver
import struct
import subprocess
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument('--binary', default='out/native/host.dest/storage_tests')
parser.add_argument('--host')
parser.add_argument('--export')
args = parser.parse_args()

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

class Blackhole(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(12)
        try:
            while self.request.recv(65536):
                pass
        except (TimeoutError, OSError):
            pass

def start(handler):
    server = Server(('127.0.0.1', 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server

server = start(Blackhole)
start_time = time.monotonic()
try:
    result = subprocess.run([args.binary, '127.0.0.1', '/', '42'],
        env=dict(os.environ, NFS_TEST_PORT=str(server.server_address[1]), NFS_TEST_TIMEOUT_MS='3000'),
        capture_output=True, text=True, timeout=12)
    elapsed = time.monotonic() - start_time
    assert result.returncode != 0, 'Unresponsive server must not succeed'
    assert elapsed < 8, f'Mount timeout exceeded bound: {elapsed:.2f}s'
    assert 'tim' in result.stderr.lower(), result.stderr
    print(f'PASS blackhole mount timed out in {elapsed:.3f}s: {result.stderr.strip()}')
finally:
    server.shutdown()
    server.server_close()

if not args.host or not args.export:
    raise SystemExit(0)

credentials = []
class Proxy(socketserver.BaseRequestHandler):
    def handle(self):
        upstream = socket.create_connection((args.host, 2049), timeout=10)
        upstream.settimeout(None)
        def replies():
            try:
                while data := upstream.recv(1024 * 1024):
                    self.request.sendall(data)
            except OSError:
                pass
            finally:
                try: self.request.shutdown(socket.SHUT_WR)
                except OSError: pass
        reply_thread = threading.Thread(target=replies, daemon=True)
        reply_thread.start()
        pending = bytearray()
        record = bytearray()
        try:
            while data := self.request.recv(1024 * 1024):
                upstream.sendall(data)
                pending.extend(data)
                while len(pending) >= 4:
                    header, = struct.unpack_from('!I', pending)
                    length = header & 0x7fffffff
                    if len(pending) < 4 + length: break
                    record.extend(pending[4:4+length]); del pending[:4+length]
                    if not header & 0x80000000: continue
                    if len(record) >= 40:
                        flavor, auth_length = struct.unpack_from('!II', record, 24)
                        if flavor == 1:  # AUTH_SYS per RFC 5531
                            name_length, = struct.unpack_from('!I', record, 36)
                            offset = 40 + ((name_length + 3) & ~3)
                            uid, gid, n = struct.unpack_from('!III', record, offset)
                            groups = struct.unpack_from('!' + 'I' * n, record, offset+12)
                            credentials.append((uid,gid,groups))
                    record.clear()
        except OSError:
            pass
        finally:
            upstream.close()

server = start(Proxy)
try:
    result = subprocess.run([args.binary, '127.0.0.1', args.export, '42'],
        env=dict(os.environ, NFS_TEST_PORT=str(server.server_address[1]), NFS_TEST_UID='4000000000', NFS_TEST_GID='3000000000', NFS_TEST_GROUPS='yes'),
        capture_output=True, text=True, timeout=45)
    assert result.returncode == 0, result.stdout + result.stderr
    expected = (4000000000,3000000000,(17,42,4000000001))
    assert credentials, 'No AUTH_SYS credentials observed'
    assert all(c == expected for c in credentials), set(credentials)
    print(f'PASS AUTH_SYS uid/gid/supplementary-gids: {len(credentials)} RPC calls; {result.stdout.strip()}')
finally:
    server.shutdown()
    server.server_close()

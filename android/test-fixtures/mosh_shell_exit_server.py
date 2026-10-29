"""Loopback SSH bootstrap for a real local mosh-server and isolated /bin/sh.

Runs only a fixed test shell, never commands supplied by the SSH client.
Mosh session keys stay in pipes/the SSH channel and are never logged or saved.
"""

import atexit
import io
import os
import re
import signal
import socket
import subprocess
import tempfile
import threading
import time

import paramiko
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from ssh_shell_exit_server import ShellServer


SERVER = os.path.expanduser("~/.nix-profile/bin/mosh-server")
PIDS = set()
HOME = tempfile.TemporaryDirectory(prefix="ichuchu-mosh-fixture-")


def cleanup():
    for pid in PIDS.copy():
        try:
            command = subprocess.run(["/bin/ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True).stdout
            if "mosh-server" in command and "MOSH_FIXTURE_READY" in command:
                os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    HOME.cleanup()


atexit.register(cleanup)
signal.signal(signal.SIGTERM, lambda *_: exit(0))


class BootstrapServer(ShellServer):
    def check_channel_exec_request(self, channel, command):
        self.shell.set()
        return True


def serve(sock, host_key):
    with sock:
        transport = paramiko.Transport(sock)
        transport.get_security_options().ciphers = ("aes256-ctr", "aes128-ctr")
        transport.add_server_key(host_key)
        try:
            server = BootstrapServer()
            transport.start_server(server=server)
            channel = transport.accept(10)
            if channel is None or not server.shell.wait(10):
                return
            result = subprocess.run(
                [SERVER, "new", "-s", "-i", "127.0.0.1", "-p", "63000:63099",
                 "--", "/bin/sh", "-c", "printf 'MOSH_FIXTURE_READY\\n'; exec /bin/sh"],
                cwd=HOME.name,
                env={"HOME": HOME.name, "PATH": "/usr/bin:/bin", "TERM": "xterm-256color",
                     "LANG": "en_US.UTF-8", "LC_ALL": "en_US.UTF-8",
                     "MOSH_SERVER_NETWORK_TMOUT": "20"},
                capture_output=True,
                timeout=8,
            )
            for match in re.finditer(rb"pid\s*[= ]\s*(\d+)", result.stderr):
                PIDS.add(int(match[1]))
            if result.returncode or b"MOSH CONNECT " not in result.stdout:
                channel.sendall(b"Synthetic mosh bootstrap failed\n")
                channel.send_exit_status(1)
            else:
                channel.sendall(result.stdout)
                channel.send_exit_status(0)
            channel.close()
            deadline = time.monotonic() + 10
            while transport.is_active() and time.monotonic() < deadline:
                time.sleep(0.01)
        except (OSError, EOFError, paramiko.SSHException, subprocess.TimeoutExpired) as error:
            print("fixture failure:", type(error).__name__, flush=True)
        finally:
            transport.close()


def main():
    pem = Ed25519PrivateKey.generate().private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH,
        serialization.NoEncryption(),
    )
    host_key = paramiko.Ed25519Key.from_private_key(io.StringIO(pem.decode()))
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 22226))
        listener.listen()
        print("real Mosh fixture bootstrap ready on loopback:22226", flush=True)
        while True:
            sock, _ = listener.accept()
            threading.Thread(target=serve, args=(sock, host_key), daemon=True).start()


if __name__ == "__main__":
    main()

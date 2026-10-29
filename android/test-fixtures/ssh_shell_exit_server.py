"""Synthetic SSH shell for emulator EOF tests; never executes commands.

Requires paramiko==4.0.0. Binds only to loopback. Host key exists only in memory.
"""

import io
import socket
import threading
import time

import paramiko
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


class ShellServer(paramiko.ServerInterface):
    def __init__(self):
        self.shell = threading.Event()
        self.username = None

    def check_auth_none(self, username):
        self.username = username
        return paramiko.AUTH_SUCCESSFUL

    def get_allowed_auths(self, username):
        return "none"

    def check_channel_request(self, kind, chanid):
        return (
            paramiko.OPEN_SUCCEEDED
            if kind == "session"
            else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        )

    def check_channel_pty_request(self, *args):
        return True

    def check_channel_shell_request(self, channel):
        self.shell.set()
        return True

    def check_channel_window_change_request(self, *args):
        return True


def serve(sock, host_key):
    with sock:
        transport = paramiko.Transport(sock)
        # Keep this EOF fixture independent of AES-GCM interoperability.
        transport.get_security_options().ciphers = ("aes256-ctr", "aes128-ctr")
        transport.add_server_key(host_key)
        server = ShellServer()
        try:
            transport.start_server(server=server)
            channel = transport.accept(10)
            if channel is None or not server.shell.wait(10):
                return
            channel.sendall(b"SYNTHETIC SHELL READY\r\n$ ")
            text = b""
            while data := channel.recv(4096):
                text += data
                if b"\x04" in text or b"exit\n" in text or b"exit\r" in text:
                    if server.username == "synthetic-drop":
                        sock.shutdown(socket.SHUT_RDWR)
                        return
                    channel.sendall(b"logout\r\n")
                    channel.send_exit_status(0)
                    channel.shutdown_write()
                    channel.close()
                    break
            # A finished channel must be recognized while the SSH transport is alive.
            deadline = time.monotonic() + 20
            while transport.is_active() and time.monotonic() < deadline:
                time.sleep(0.05)
        except (OSError, EOFError, paramiko.SSHException):
            pass
        finally:
            transport.close()


def main():
    pem = Ed25519PrivateKey.generate().private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.OpenSSH,
        serialization.NoEncryption(),
    )
    host_key = paramiko.Ed25519Key.from_private_key(io.StringIO(pem.decode()))
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 22225))
        listener.listen()
        print("synthetic SSH shell ready on loopback:22225", flush=True)
        while True:
            sock, _ = listener.accept()
            threading.Thread(target=serve, args=(sock, host_key), daemon=True).start()


if __name__ == "__main__":
    main()

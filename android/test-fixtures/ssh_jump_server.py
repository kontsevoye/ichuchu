"""Loopback-only nested SSH fixture. Virtual destinations have no TCP listeners.

Only synthetic passwords and generated keys; no shell commands are executed.
Requires paramiko==4.0.0. Each forwarding channel hosts an independent SSH server.
"""
import io
import os
import stat
import socket
import threading
import time

import paramiko
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def host_key():
    pem = Ed25519PrivateKey.generate().private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH,
        serialization.NoEncryption(),
    )
    return paramiko.Ed25519Key.from_private_key(io.StringIO(pem.decode()))


KEYS = [host_key() for _ in range(4)]


class MemoryHandle(paramiko.SFTPHandle):
    def __init__(self, files, path):
        super().__init__()
        self.files, self.path = files, path

    def read(self, offset, length):
        return self.files[self.path][offset:offset + length]

    def write(self, offset, data):
        old = self.files[self.path]
        self.files[self.path] = old[:offset].ljust(offset, b"\0") + data + old[offset + len(data):]
        return paramiko.SFTP_OK

    def stat(self):
        attr = paramiko.SFTPAttributes()
        attr.st_size = len(self.files[self.path])
        attr.st_mode = stat.S_IFREG | 0o600
        return attr


class MemorySftp(paramiko.SFTPServerInterface):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.files = {}

    def open(self, path, flags, attr):
        if flags & os.O_CREAT:
            self.files.setdefault(path, b"")
        if path not in self.files:
            return paramiko.SFTP_NO_SUCH_FILE
        if flags & os.O_TRUNC:
            self.files[path] = b""
        return MemoryHandle(self.files, path)

    def canonicalize(self, path):
        return path


class JumpServer(paramiko.ServerInterface):
    def __init__(self, depth):
        self.depth = depth
        self.forward = set()
        self.username = ""
        self.actions = {}

    def get_allowed_auths(self, username):
        return "password,publickey"

    def check_auth_password(self, username, password):
        self.username = username
        return paramiko.AUTH_SUCCESSFUL if password == f"fixture-{self.depth}" else paramiko.AUTH_FAILED

    def check_auth_publickey(self, username, key):
        self.username = username
        return paramiko.AUTH_SUCCESSFUL

    def check_channel_direct_tcpip_request(self, chanid, origin, destination):
        if self.username == "deny-forward" or self.depth >= 3:
            return paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        if destination != (f"jump-{self.depth + 1}.internal", 2200 + self.depth + 1):
            return paramiko.OPEN_FAILED_CONNECT_FAILED
        self.forward.add(chanid)
        return paramiko.OPEN_SUCCEEDED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_pty_request(self, *args):
        return True

    def check_channel_window_change_request(self, *args):
        return True

    def check_channel_exec_request(self, channel, command):
        if command != b"jump-probe":
            return False
        self.actions[channel.chanid] = "exec"
        return True

    def check_channel_subsystem_request(self, channel, name):
        if name != "sftp": return False
        self.actions[channel.chanid] = "sftp"
        return super().check_channel_subsystem_request(channel, name)

    def check_channel_shell_request(self, channel):
        self.actions[channel.chanid] = "shell"
        return True


def endpoint(channel, server):
    deadline = time.monotonic() + 10
    while channel.chanid not in server.actions and time.monotonic() < deadline:
        time.sleep(0.01)
    if server.actions.get(channel.chanid) not in ("exec", "shell"): return
    try:
        channel.sendall(f"JUMP_READY:{server.depth}\n".encode())
        if server.actions.get(channel.chanid) == "shell":
            while data := channel.recv(32768):
                if server.username == "drop-on-input":
                    channel.transport.close()
                    return
                if b"exit" in data or b"\x04" in data:
                    break
                channel.sendall(data)
        channel.send_exit_status(0)
        channel.shutdown_write()
    except (OSError, EOFError):
        pass
    finally:
        try:
            channel.close()
        except (OSError, EOFError):
            pass


class ChannelSocket:
    """Paramiko may close a child after its parent has already disconnected."""
    def __init__(self, channel):
        self.channel = channel

    def __getattr__(self, name):
        return getattr(self.channel, name)

    def close(self):
        try:
            self.channel.close()
        except (EOFError, OSError):
            pass


def serve(sock, depth=0):
    if isinstance(sock, paramiko.Channel):
        sock = ChannelSocket(sock)
    transport = paramiko.Transport(sock)
    transport.packetizer.REKEY_BYTES = 65536
    transport.get_security_options().ciphers = ("aes256-ctr", "aes128-ctr")
    transport.add_server_key(KEYS[depth])
    transport.set_subsystem_handler("sftp", paramiko.SFTPServer, MemorySftp)
    server = JumpServer(depth)
    try:
        transport.start_server(server=server)
        while transport.is_active():
            channel = transport.accept(1)
            if channel is None:
                continue
            if channel.chanid in server.forward and server.username == "stall-next":
                target, args = (lambda ch: (time.sleep(20), ch.close())), (channel,)
            else:
                target, args = (serve, (channel, depth + 1)) if channel.chanid in server.forward else (endpoint, (channel, server))
            threading.Thread(target=target, args=args, daemon=True).start()
    except (OSError, EOFError, paramiko.SSHException):
        pass
    finally:
        transport.close()
        sock.close()


def main():
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 22227))
        listener.listen()
        print("SSH jump fixture ready on loopback:22227", flush=True)
        while True:
            sock, _ = listener.accept()
            threading.Thread(target=serve, args=(sock,), daemon=True).start()


if __name__ == "__main__":
    main()

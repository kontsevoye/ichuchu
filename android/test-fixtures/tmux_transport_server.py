"""Opt-in loopback fixture: real isolated tmux, SSH PTYs and Mosh UDP.

Exec requests accept only the app's fixed tmux discovery/startup templates with
generated fixture session names. Interactive PTYs run a disposable shell with
synthetic test input, without personal credentials, startup files or tmux sockets.
Requires paramiko==4.0.0, /bin/zsh, tmux and mosh-server.
Mosh keys stay in memory/the SSH channel and are never printed or saved.
"""

import atexit
import fcntl
import io
import os
from pathlib import Path
import re
import select
import shlex
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import termios
import threading
import time

import paramiko
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


ROOT = tempfile.TemporaryDirectory(prefix="ichuchu-tmux-fixture-")
DIRECTORY = Path(ROOT.name)
SOCKET = str(DIRECTORY / "tmux.sock")
TMUX = shutil.which("tmux")
MOSH = shutil.which("mosh-server")
if not TMUX or not MOSH:
    raise SystemExit("tmux and mosh-server must be on PATH")
TOKEN = DIRECTORY.name
MOSH_PIDS = set()
CHILDREN = set()
CONFIG = DIRECTORY / "tmux.conf"
CONFIG.write_text("set -g default-shell /bin/sh\nset -g default-command 'exec /bin/sh'\n")
BIN = DIRECTORY / "bin"
BIN.mkdir()
(BIN / "tmux").write_text(
    "#!/bin/sh\nexec " + shlex.join([TMUX, "-S", SOCKET, "-f", str(CONFIG)]) + ' "$@"\n'
)
(BIN / "tmux").chmod(0o700)
ENV = {
    "HOME": ROOT.name,
    "PATH": f"{BIN}:/usr/bin:/bin",
    "SHELL": "/bin/sh",
    "TERM": "xterm-256color",
    "LANG": "en_US.UTF-8",
    "LC_ALL": "en_US.UTF-8",
    "MOSH_SERVER_NETWORK_TMOUT": "20",
}
ENVELOPE = "; printf '\nICHUCHU_EXIT:%s\n' \"$?\""
AVAILABILITY = "command -v tmux >/dev/null 2>&1"
LIST = (
    "if ! command -v tmux >/dev/null 2>&1; then printf 'tmux executable not found\\n' >&2; false; "
    "else tmux list-sessions -F '#{session_name}\t#{session_attached}' 2>/dev/null; "
    'tmux_exit_code=$?; if [ "$tmux_exit_code" -eq 1 ]; then true; '
    'else [ "$tmux_exit_code" -eq 0 ]; fi; fi'
)


def valid_startup(command):
    names = set(re.findall(r"ichuchu-[1-9][0-9]*", command))
    if len(names) != 1:
        return False
    name = names.pop()
    prefix = f'if [ -n "$TMUX" ]; then tmux switch-client -t \'={name}\'; '
    create = prefix + f"else exec tmux new-session -A -s '{name}'; fi"
    attach = (
        prefix + f"elif tmux has-session -t '={name}' 2>/dev/null; "
        f"then exec tmux attach-session -t '={name}'; "
        f"else printf 'tmux session %s is no longer available\\n' '{name}'; "
        'exec "${SHELL:-/bin/sh}" -l; fi'
    )
    return command in (create, attach)


def cleanup():
    subprocess.run([TMUX, "-S", SOCKET, "kill-server"], capture_output=True)
    for child in list(CHILDREN):
        if child.poll() is None:
            child.terminate()
    for pid in MOSH_PIDS:
        identity = subprocess.run(
            ["/bin/ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True
        ).stdout
        if "mosh-server" in identity and TOKEN in identity:
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    ROOT.cleanup()


atexit.register(cleanup)
signal.signal(signal.SIGTERM, lambda *_: exit(0))


class Server(paramiko.ServerInterface):
    def __init__(self):
        self.ready = threading.Event()
        self.command = None
        self.pty = False
        self.master = None
        self.size = (80, 24)

    def check_auth_none(self, username):
        return paramiko.AUTH_SUCCESSFUL if username == "tmux-fixture" else paramiko.AUTH_FAILED

    def get_allowed_auths(self, username):
        return "none"

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def resize(self, width, height):
        self.size = (width, height)
        if self.master is not None:
            fcntl.ioctl(self.master, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))

    def check_channel_pty_request(self, channel, term, width, height, *args):
        self.pty = True
        self.resize(width, height)
        return True

    def check_channel_window_change_request(self, channel, width, height, *args):
        self.resize(width, height)
        return True

    def check_channel_exec_request(self, channel, command):
        self.command = command.decode("utf-8")
        self.ready.set()
        return True


def run_pty(channel, server):
    master, slave = os.openpty()
    server.master = master
    server.resize(*server.size)

    def controlling_terminal():
        os.setsid()
        fcntl.ioctl(0, termios.TIOCSCTTY, 0)

    child = subprocess.Popen(
        ["/bin/zsh", "-f", "-c", server.command], env=ENV, cwd=ROOT.name,
        stdin=slave, stdout=slave, stderr=slave, preexec_fn=controlling_terminal,
    )
    CHILDREN.add(child)
    os.close(slave)
    try:
        while not channel.closed:
            if channel.recv_ready():
                data = channel.recv(4096)
                if not data:
                    break
                os.write(master, data)
            readable, _, _ = select.select([master], [], [], 0.02)
            if readable:
                try:
                    data = os.read(master, 16384)
                except OSError:
                    break
                if not data:
                    break
                channel.sendall(data)
            if child.poll() is not None and not readable:
                break
        channel.send_exit_status(child.poll() or 0)
    finally:
        os.close(master)
        server.master = None
        if child.poll() is None:
            child.terminate()
        child.wait(timeout=5)
        CHILDREN.discard(child)


def run_exec(channel, command):
    if command in (AVAILABILITY + ENVELOPE, LIST + ENVELOPE):
        result = subprocess.run(
            ["/bin/zsh", "-f", "-c", command], env=ENV, cwd=ROOT.name,
            capture_output=True, timeout=5,
        )
    else:
        args = shlex.split(command)
        expected = [
            "env", "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "MOSH_SERVER_NETWORK_TMOUT=2592000",
            "PATH=/opt/homebrew/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin:/opt/local/bin:$PATH",
            "mosh-server", "new", "-s", "-c", "256", "--", "/bin/sh", "-c",
        ]
        if args[:-1] != expected or not valid_startup(args[-1]):
            channel.sendall(b"Fixture rejected unexpected exec command\n")
            channel.send_exit_status(1)
            return
        result = subprocess.run(
            [MOSH, "new", "-s", "-c", "256", "-i", "127.0.0.1", "-p", "63100:63199",
             "--", "/bin/sh", "-c", args[-1], TOKEN],
            env=ENV, cwd=ROOT.name, capture_output=True, timeout=8,
        )
        for match in re.finditer(rb"pid\s*[= ]\s*(\d+)", result.stderr):
            MOSH_PIDS.add(int(match[1]))
    channel.sendall(result.stdout)
    if result.stderr:
        channel.sendall_stderr(result.stderr)
    channel.send_exit_status(result.returncode)


def serve(sock, host_key):
    transport = paramiko.Transport(sock)
    transport.get_security_options().ciphers = ("aes256-ctr", "aes128-ctr")
    transport.add_server_key(host_key)
    try:
        server = Server()
        transport.start_server(server=server)
        channel = transport.accept(10)
        if channel is None or not server.ready.wait(10):
            return
        if server.pty and valid_startup(server.command):
            run_pty(channel, server)
        elif not server.pty:
            run_exec(channel, server.command)
        else:
            channel.sendall(b"Fixture rejected unexpected PTY command\n")
            channel.send_exit_status(1)
        channel.shutdown_write()
        channel.close()
        deadline = time.monotonic() + 5
        while transport.is_active() and time.monotonic() < deadline:
            time.sleep(0.02)
    except (OSError, EOFError, paramiko.SSHException, subprocess.TimeoutExpired, ValueError) as error:
        print("fixture connection ended:", type(error).__name__, flush=True)
    finally:
        transport.close()
        sock.close()


def main():
    pem = Ed25519PrivateKey.generate().private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH, serialization.NoEncryption()
    )
    host_key = paramiko.Ed25519Key.from_private_key(io.StringIO(pem.decode()))
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 22228))
        listener.listen()
        print("isolated real tmux SSH/Mosh fixture ready on loopback:22228", flush=True)
        while True:
            sock, _ = listener.accept()
            threading.Thread(target=serve, args=(sock, host_key), daemon=True).start()


if __name__ == "__main__":
    main()

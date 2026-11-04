"""Opt-in emulator fixture: synthetic Deck JSON over real SSH, never executes shell input.

Requires paramiko==4.0.0. Only loopback:22229 and the deck-fixture user are accepted.
All messages, host keys and session state are synthetic and held in memory.
"""

import io
import hashlib
import json
import shlex
import socket
import threading
import time

import paramiko
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


LOCK = threading.Lock()
SESSIONS = [{"id": "fixture-existing-full-id", "title": "Fix draft recovery", "path": "/fixture/chuchu",
             "tool": "codex", "profile": "default", "group": "work", "status": "waiting",
             "codex_session_id": "fixture-linked-native",
             "substate": "idle-at-empty-prompt", "status_source": "live"}]
ROWS = [
    {"id": "user-1", "kind": "user", "body": "Keep my draft when the Mac disconnects."},
    {"id": "assistant-1", "kind": "assistant", "body": "I will check **draft recovery** and the reconnect flow."},
    {"id": "tool-1", "kind": "read", "title": "Read connection recovery", "body": "Synthetic file contents", "finished": True},
    {"id": "tool-2", "kind": "edit", "title": "Update draft persistence", "summary": "AgentDraftStore.kt · +12 −3", "finished": True},
    {"id": "assistant-2", "kind": "assistant", "body": "The draft survives reconnect.\n\n```text\nBUILD SUCCESSFUL\n```"},
]
HISTORY = {"fixture-existing-full-id": ROWS}
HISTORY["fixture-native-thread"] = [{"id": "native-exact", "kind": "assistant", "body": "Exact linked Codex history"}]
GROUPS = {"work": "/fixture/chuchu", "Empty project": "/fixture/empty-project", "parent": "", "parent/child": "/fixture/child"}
SENDS = {}
QUEUE_PAUSES = {}
RECALL = True
DROP_NEXT_LIST = False
EVENTS = []
UPLOADS = {}
MODEL_SETTINGS = {}


def encoded(value):
    return (json.dumps(value, ensure_ascii=False) + "\n").encode()


class Server(paramiko.ServerInterface):
    def __init__(self):
        self.ready = threading.Event()
        self.command = ""

    def check_auth_none(self, username):
        return paramiko.AUTH_SUCCESSFUL if username == "deck-fixture" else paramiko.AUTH_FAILED

    def get_allowed_auths(self, username):
        return "none"

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_exec_request(self, channel, command):
        self.command = command.decode("utf-8")
        self.ready.set()
        return True


def process(channel, command):
    global RECALL, DROP_NEXT_LIST
    channel.settimeout(15)
    upload = shlex.split(command)
    if upload[:1] == ["fixture-model-reset"]:
        with LOCK:
            MODEL_SETTINGS.pop(upload[1], None)
        channel.sendall(b"reset\n")
        return 0
    if upload[:3] == ["exec", "python3", "-c"] and upload[3].startswith("# ichuchu-model-control-v1\n"):
        assert len(upload) == 9
        session, model, effort = upload[6:]
        with LOCK:
            current = MODEL_SETTINGS.get(session, ("fixture-model", "medium"))
            if model or effort:
                current = (model or current[0], effort or "low")
                MODEL_SETTINGS[session] = current
        channel.sendall(encoded({"success": True, "confirmed": bool(model or effort),
                                "model": current[0], "effort": current[1],
                                "models": {"fixture-model": ["medium", "high"],
                                           "fixture-second-model": ["low", "high"]}}))
        return 0
    if upload[:1] == ["fixture-queue-seed"]:
        ident = "pending-" + upload[1]
        with LOCK:
            SENDS[ident] = {"send_id": ident, "session_id": upload[1],
                            "message": "Retain this undelivered request", "state": "failed",
                            "reason": "Codex session identity is unavailable; nothing was typed"}
        channel.sendall(encoded({"id": ident}))
        return 0
    if upload[:1] == ["fixture-queue-pause"]:
        QUEUE_PAUSES[upload[1]] = (threading.Event(), threading.Event())
        channel.sendall(b"armed\n")
        return 0
    if upload[:1] == ["fixture-queue-wait"]:
        pause = QUEUE_PAUSES[upload[1]]
        channel.sendall(b"entered\n" if pause[0].wait(12) else b"timeout\n")
        return 0
    if upload[:1] == ["fixture-queue-release"]:
        QUEUE_PAUSES[upload[1]][1].set()
        channel.sendall(b"released\n")
        return 0
    if upload[:3] == ["exec", "sh", "-c"] and upload[3].startswith("# ichuchu-upload-v1\n"):
        size = int(upload[-1])
        if not 0 <= size <= 20 * 1024 * 1024:
            return 2
        body = bytearray()
        while data := channel.recv(8192):
            body.extend(data)
            if len(body) > size:
                return 1
        if len(body) != size:
            return 1
        with LOCK:
            path = f"/tmp/ichuchu-attachment.fixture-{len(UPLOADS)}/{upload[-2]}"
            UPLOADS[path] = bytes(body)
        channel.sendall((path + "\n").encode())
        return 0
    if upload[:1] == ["fixture-upload-info"]:
        with LOCK:
            body = UPLOADS.get(upload[1])
        channel.sendall(encoded({"bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()} if body is not None else {}))
        return 0
    if command == "fixture-streams":
        body = b""
        while data := channel.recv(8192):
            body += data
        channel.sendall_stderr(b"synthetic warning\n" * 1000)
        channel.sendall(encoded({"received": body.decode("utf-8"), "ok": True}))
        return 7
    if command == "fixture-flood":
        for _ in range(128):
            channel.sendall_stderr(b"w" * 128)
            channel.sendall(b"o" * 8192)
        return 9
    if command == "fixture-idle":
        deadline = time.monotonic() + 30
        while not channel.closed and time.monotonic() < deadline:
            time.sleep(0.02)
        return 0
    if command.startswith("fixture-fast-turn "):
        ident = shlex.split(command)[1]
        with LOCK:
            cursor = 11 + len(EVENTS)
            EVENTS.append({"cursor": cursor, "kind": "session.transition", "session_id": ident, "data": {"from_status": "running", "to_status": "waiting", "substate": "idle-at-empty-prompt", "last_output_hash": f"turn-{cursor}"}})
        channel.sendall(b"ok\n")
        return 0
    if command.startswith("fixture-status "):
        _, ident, status = shlex.split(command)
        with LOCK:
            for session in SESSIONS:
                if session["id"] == ident:
                    session["status"] = "running" if status == "running" else "waiting"
                    session["substate"] = {"running": "running", "ready": "idle-at-empty-prompt", "attention": "interactive-menu"}[status]
        channel.sendall(b"ok\n")
        return 0
    if command == "fixture-drop-next-list":
        DROP_NEXT_LIST = True
        channel.sendall(b"armed\n")
        return 0
    if command.startswith("command -v ") or command.startswith("if command -v agent-deck "):
        channel.sendall(b"/fixture/agent-deck\n")
        return 0
    if command.startswith("exec sh -c "):
        # Read the fixed lease wrapper's command, without executing that wrapper or any shell.
        script = shlex.split(command)[3]
        if " & deck_reader=" not in script:
            for line in script.splitlines():
                if line.startswith("(exec ") and line.endswith(")"):
                    tokens = shlex.split(line[1:-1])
                    if tokens[:6] != ["exec", "/fixture/agent-deck", "-p", "default", "group", "show"]:
                        return 2
                    group = tokens[6]
                    channel.sendall(encoded({"success": True, "path": group, "default_path": GROUPS[group]}))
                    channel.sendall(b"\x1e")
            return 0
        command = script.split(" & deck_reader=", 1)[0]
    args = shlex.split(command)
    if args[:4] != ["exec", "/fixture/agent-deck", "-p", "default"]:
        channel.sendall_stderr(b"fixture refused command\n")
        return 2
    args = args[4:]
    channel.sendall_stderr(b"synthetic Deck warning\n")
    output = None
    code = 0
    if args == ["version"]:
        channel.sendall(b"Agent Deck v1.16.35\n")
        return 0
    if args[:2] == ["launch", "--capabilities"]:
        output = {"version": 1, "commands": {"launch": [{"name": x} for x in ["c", "no-parent", "json", "title", "group", "model", "effort"]]},
                  "tools": [{"name": name, "models": ["fixture-model"], "reasoning_efforts": ["medium", "high"]} for name in ["claude", "codex"]], "accounts": []}
    elif args[:2] == ["harness", "list"]:
        output = [{"name": name, "installed": True, "state": "ready"} for name in ["claude", "codex"]]
    elif args[:3] == ["session", "send", "--help"]:
        channel.sendall(b"-require-input-prompt -queue -message-file -no-tag -json -codex-composer-fallback -no-wait -defer-if-busy -defer-timeout -timeout\n")
        return 0
    elif args[:2] == ["config", "get"]:
        output = {"key": "recall.enabled", "value": RECALL}
    elif args[:2] == ["config", "set"]:
        RECALL = True
        output = {"success": True}
    elif args[:2] == ["events", "stats"]:
        output = {"enabled": True, "cursor": 10 + len(EVENTS)}
    elif args[:2] == ["events", "follow"]:
        cursor = int(args[args.index("--after") + 1]) if "--after" in args else 0
        deadline = time.monotonic() + 8
        while not channel.closed and time.monotonic() < deadline:
            with LOCK:
                frames = [event for event in EVENTS if event["cursor"] > cursor]
            for event in frames:
                channel.sendall(encoded(event))
                cursor = event["cursor"]
            time.sleep(0.05)
    elif args[:1] == ["list"]:
        if DROP_NEXT_LIST:
            DROP_NEXT_LIST = False
            channel.transport.close()
            raise EOFError("synthetic connection loss")
        with LOCK:
            output = list(SESSIONS)
    elif args[:2] == ["group", "list"]:
        output = {"groups": [{"name": "work", "path": "work"}, {"name": "Empty project", "path": "Empty project"}, {"name": "parent", "path": "parent", "children": [{"name": "child", "path": "parent/child"}]}]}
    elif args[:2] == ["recall", "sessions"]:
        output = {"success": True, "sessions": [{"native_id": "fixture-native-thread", "harness": "codex", "cwd": "/fixture/chuchu", "title": "Exact conversation", "started_at": 1793629910}]}
    elif args[:2] == ["session", "set"]:
        if args[3] != "codex-session-id" or args[4] not in HISTORY:
            return 2
        with LOCK:
            HISTORY[args[2]] = HISTORY[args[4]]
            next(session for session in SESSIONS if session["id"] == args[2])["codex_session_id"] = args[4]
        output = {"success": True}
    elif args[:2] == ["recall", "timeline"]:
        with LOCK:
            rows = HISTORY.get(args[2])
            if rows is None:
                output = {"success": False, "code": "NOT_FOUND", "error": "session has no native transcript on this machine yet"}
                code = 1
            else:
                output = {"schema": "agent-deck.recall.rows/v2", "turns": list(rows), "through_cursor": str(len(rows))}
    elif args[:2] == ["recall", "follow"]:
        cursor = int(args[args.index("--after") + 1]) if "--after" in args else 0
        deadline = time.monotonic() + 8
        while not channel.closed and time.monotonic() < deadline:
            with LOCK:
                rows = list(HISTORY.get(args[2], [])[cursor:])
            for row in rows:
                cursor += 1
                channel.sendall(encoded({"frame": "row", "row": row, "cursor": str(cursor)}))
            time.sleep(0.05)
    elif args[:2] == ["session", "send"]:
        required = ["--message-file", "-", "--require-input-prompt", "--no-tag"]
        if not all(item in args for item in required):
            return 2
        body = b""
        while data := channel.recv(8192):
            body += data
        message = body.decode("utf-8").rstrip("\r\n")
        with LOCK:
            ident = f"send-{len(SENDS) + 1}"
            row_id = "landed-" + ident
            output = {"send_id": ident, "session_id": args[2], "message": message,
                      "state": "landed", "landed_row_id": row_id}
            session = next(s for s in SESSIONS if s["id"] == args[2])
            if session["tool"] == "codex" and not session.get("codex_session_id"):
                # The real Deck queue worker does not forward --codex-composer-fallback.
                if "--queue" in args or "--no-wait" not in args or "--codex-composer-fallback" not in args:
                    output.update(state="failed", landed_row_id="", reason="Codex session identity is unavailable; nothing was typed")
                    SENDS[ident] = output
                    channel.sendall(encoded(dict(output, state="queued")))
                    return 0
                output = {"session_id": args[2], "success": True, "delivery": "submitted", "submitted": True, "confirmation": "confirmed"}
            SENDS[ident] = {"send_id": ident, "session_id": args[2], "message": message,
                            "state": "landed", "landed_row_id": row_id}
            HISTORY.setdefault(args[2], []).append({"id": row_id, "kind": "user", "body": message})
            HISTORY.setdefault(args[2], []).append({"id": "reply-" + ident, "kind": "assistant", "body": "Synthetic reply: message received."})
    elif args[:2] == ["session", "send-status"]:
        output = SENDS.get(args[2], {"success": False, "code": "NOT_FOUND", "error": "receipt not found"})
        code = 0 if args[2] in SENDS else 1
    elif args[:3] == ["session", "queue", "list"]:
        pause = QUEUE_PAUSES.get(args[3])
        if pause:
            pause[0].set()
            pause[1].wait(15)
            QUEUE_PAUSES.pop(args[3], None)
        with LOCK:
            output = {"session_id": args[3], "queue": [
                {"id": ident, "text_preview": receipt["message"], "state": receipt["state"]}
                for ident, receipt in SENDS.items()
                if receipt["session_id"] == args[3] and receipt["state"] != "landed"
            ]}
    elif args[:3] == ["session", "queue", "cancel"]:
        output = {"id": args[3], "outcome": "not_found"}
    elif args[:1] == ["launch"]:
        if "--no-parent" not in args:
            return 2
        with LOCK:
            row = dict(SESSIONS[0], id=f"fixture-created-{len(SESSIONS)}", title=args[args.index("--title") + 1] if "--title" in args else "New fixture session", tool=args[args.index("-c") + 1])
            row["status"] = "waiting"
            row["substate"] = "idle-at-empty-prompt"
            row.pop("codex_session_id", None)
            row["path"] = args[1]
            row["group"] = args[args.index("--group") + 1] if "--group" in args else ""
            SESSIONS.append(row)
            output = dict(row, success=True, session_id=row["id"])
    else:
        output = {"success": False, "code": "INVALID_OPERATION", "error": "fixture command unavailable"}
        code = 2
    if output is not None:
        channel.sendall(encoded(output))
    return code


def serve(sock, key):
    transport = paramiko.Transport(sock)
    transport.get_security_options().ciphers = ("aes256-ctr", "aes128-ctr")
    transport.packetizer.REKEY_BYTES = 64 * 1024
    transport.add_server_key(key)
    try:
        server = Server()
        transport.start_server(server=server)
        channel = transport.accept(10)
        if channel is None or not server.ready.wait(10):
            return
        code = process(channel, server.command)
        if not channel.closed:
            channel.send_exit_status(code)
            channel.shutdown_write()
            channel.close()
        # Prove EOF/completion works without depending on transport disconnection.
        deadline = time.monotonic() + 3
        while transport.is_active() and time.monotonic() < deadline:
            time.sleep(0.02)
    except (OSError, EOFError, paramiko.SSHException, ValueError):
        pass
    finally:
        transport.close()
        sock.close()


def main():
    pem = Ed25519PrivateKey.generate().private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.OpenSSH, serialization.NoEncryption())
    key = paramiko.Ed25519Key.from_private_key(io.StringIO(pem.decode()))
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 22229))
        listener.listen()
        print("Synthetic Agent Deck SSH fixture ready on loopback:22229", flush=True)
        while True:
            sock, _ = listener.accept()
            threading.Thread(target=serve, args=(sock, key), daemon=True).start()


if __name__ == "__main__":
    main()

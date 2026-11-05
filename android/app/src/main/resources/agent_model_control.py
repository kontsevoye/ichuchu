# ichuchu-model-control-v1
"""Session controls and one-shot native Codex input on the SSH host.

Codex 0.166's /model arguments apply to the current conversation. Keep them out
of Deck's accepted-turn protocol: control commands do not create a user turn.
The shared Deck send flock serializes this operation with normal deliveries.
"""
import fcntl
from datetime import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid


class Refused(Exception):
    pass


def run(args, timeout=8):
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        try:
            detail = json.loads(result.stdout).get('error', '')
        except (ValueError, AttributeError):
            detail = ''
        raise Refused(detail or result.stderr.strip()[:500] or 'Remote command failed')
    return result.stdout


def plain_frame(frame):
    # tmux -e retains both SGR colors and OSC hyperlinks around pasted URLs.
    frame = re.sub(r'\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)', '', frame)
    return re.sub(r'\x1b\[[0-9;:]*[A-Za-z]', '', frame)


def ordinary_footer(line, allow_queue=False):
    """Known passive hints, including shared-server agent navigation, never dialogs."""
    warning = r'⚠ \d+(?: warnings?)? · f2(?: to view)?'
    hint = r'(?:← for agents(?: · \? for shortcuts)?|\? for shortcuts)'
    if allow_queue:
        hint = r'(?:' + hint + r'|tab to queue(?: message)?)'
    return bool(re.fullmatch(r'(?:' + hint + r')(?:\s+' + warning + r')?|' + warning, line))


def ready_frame(frame, expected='Ask Codex to do anything'):
    """Fail closed on busy, unknown footer, menus, multiline input and drafts."""
    raw_rows = frame.splitlines()
    rows = plain_frame(frame).splitlines()
    prompts = [i for i, line in enumerate(rows) if line.startswith(('›', '»'))]
    if not prompts:
        raise Refused('Open terminal and close the current dialog first.')
    index = prompts[-1]
    body = rows[index][1:].strip()
    tail = [line.strip() for line in rows[index + 1:] if line.strip()]
    recent = '\n'.join(rows[max(0, index - 5):]).lower()
    pending = '\n'.join(rows[max(0, index - 30):index]).lower()
    if any(word in recent for word in ('to interrupt', 'esc to')) or any(
            word in pending for word in ('queued follow-up inputs', 'edit last queued message')):
        raise Refused('Wait for the current turn or close the terminal dialog first.')
    if body != expected:
        raise Refused('The terminal contains a draft. Send or clear it there first.')
    if expected == 'Ask Codex to do anything' and not dim_placeholder(raw_rows[index]):
        raise Refused('The terminal contains a draft, not an empty placeholder.')
    # Current model/context row and the one optional shortcut/warning row only.
    model_line = re.match(r'(\S+)\s+(none|minimal|low|medium|high|xhigh|max|ultra)\b', tail[0], re.I) if tail else None
    standard_footer = tail and (re.search(r'\bContext \d+% left\b', tail[0]) or (
        model_line and ' · ' in tail[0] and tail[0].endswith('…')))
    if not 1 <= len(tail) <= 2 or not standard_footer or not model_line:
        raise Refused('Cannot verify an empty Codex composer. Check the terminal.')
    allowed_footer = ordinary_footer(tail[-1], allow_queue=expected.startswith('/model '))
    if len(tail) == 2 and not allowed_footer:
        raise Refused('Close the terminal menu before changing the model.')
    return (model_line.group(1).lower(), model_line.group(2).lower()) if model_line else ('', '')


def dim_placeholder(line):
    dim = False
    for part in re.split(r'(\x1b\[[0-9;]*m)', re.split('[›»]', line, maxsplit=1)[-1]):
        if part.startswith('\x1b['):
            values = [int(v or 0) for v in part[2:-1].split(';')]
            index = 0
            while index < len(values):
                value = values[index]
                if value in (0, 22):
                    dim = False
                elif value == 2:
                    dim = True
                elif value in (38, 48):
                    # RGB/indexed color components are not SGR style numbers.
                    index += 4 if values[index + 1:index + 2] == [2] else 2
                index += 1
        elif part.strip():
            return dim
    return False


def send_lock(session_id):
    # Agent Deck 1.16.36 EffectiveDataPath("runtime/send-locks", "runtime").
    root = Path(os.environ.get('XDG_DATA_HOME') or Path.home() / '.local/share') / 'agent-deck'
    legacy = Path.home() / '.agent-deck'
    if not (root / 'runtime').exists() and (legacy / 'runtime').exists():
        root = legacy
    folder = root / 'runtime/send-locks'
    folder.mkdir(parents=True, exist_ok=True)
    handle = (folder / (session_id + '.lock')).open('a')
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        handle.close()
        raise Refused('Another message is being delivered. Try again when it finishes.')
    return handle


def available_models(session):
    home = Path(session.get('resolved_codex_home') or os.environ.get('CODEX_HOME') or Path.home() / '.codex')
    path = home / 'models_cache.json'
    if not path.is_file() or path.stat().st_size > 5 * 1024 * 1024:
        raise Refused('Codex model catalog is unavailable. Open /model in the terminal first.')
    catalog = json.loads(path.read_text())
    # Ultra remains terminal-only until its input semantics have live acceptance.
    models = {m['slug']: [e['effort'] for e in m.get('supported_reasoning_levels', []) if e['effort'] != 'ultra']
              for m in catalog.get('models', []) if m.get('visibility') == 'list' and m.get('slug')}
    if not models:
        raise Refused('Codex has no available models in its catalog.')
    return models


def input_frame(frame, expected=None):
    """Recognize Codex's ordinary composer even while a turn is running."""
    raw = frame.splitlines()
    rows = plain_frame(frame).splitlines()
    prompts = [i for i, line in enumerate(rows) if line.startswith(('›', '»'))]
    if not prompts:
        raise Refused('Open the terminal and close its dialog first.')
    index = prompts[-1]
    footer = next((i for i in range(index + 1, len(rows)) if re.match(
        r'^\s+\S+\s+(none|minimal|low|medium|high|xhigh|max|ultra)\s+· ', rows[i], re.I)), None)
    if footer is None:
        raise Refused('Cannot verify the Codex composer. Open the terminal.')
    tail = [line.strip() for line in rows[footer + 1:] if line.strip()]
    if len(tail) > 1 or any(not ordinary_footer(line, allow_queue=True) for line in tail):
        raise Refused('Close the terminal menu before sending.')
    body = '\n'.join([rows[index][1:].strip()] + [line[2:].rstrip() if line.startswith('  ') else line.rstrip()
                     for line in rows[index + 1:footer]]).strip()
    if expected is None:
        if body and not (body == 'Ask Codex to do anything' and dim_placeholder(raw[index]) and
                         not any(line.strip() for line in rows[index + 1:footer])):
            raise Refused('The terminal contains a draft. Send or clear it there first.')
    else:
        # TUI soft wrapping can split a URL/word or replace a word separator.
        # Permit either only at visible line boundaries, never inside a line.
        lines = [' '.join(line.split()) for line in body.splitlines() if line.strip()]
        same = re.fullmatch(' ?'.join(map(re.escape, lines)), ' '.join(expected.split())) is not None
        pasted = re.fullmatch(r'\[Pasted Content ' + str(len(expected)) + r' chars\](?: #\d+)?', body)
        if not same and not (len(expected) > 1000 and pasted):
            raise Refused('The pasted message could not be verified. Check the terminal before trying again.')
    return rows[footer]


def frame_model(frame):
    """Read current settings without treating drafts or busy turns as mutations."""
    for line in reversed(plain_frame(frame).splitlines()[-4:]):
        match = re.match(r'^\s+(\S+)\s+(none|minimal|low|medium|high|xhigh|max|ultra)\s+· ', line, re.I)
        if match:
            return dict(model=match[1].lower(), effort=match[2].lower())
    return {}


def context_percent(session, frame):
    # Prefer the actual TUI value; the rollout fallback uses Codex 0.166's own formula.
    rows = plain_frame(frame).splitlines()
    for line in reversed(rows[-4:]):
        match = re.search(r'^\s+\S+\s+(?:none|minimal|low|medium|high|xhigh|max|ultra)\s+· .*\bContext (\d{1,3})% left\b', line, re.I)
        if match and 0 <= int(match[1]) <= 100:
            return int(match[1])
    native_id = session.get('codex_session_id')
    path = Path(session.get('transcript_path') or '')
    if not native_id or not path.is_file():
        return None
    with path.open('rb') as stream:
        first = json.loads(stream.readline(1024 * 1024))
        if first.get('type') != 'session_meta' or first.get('payload', {}).get('id') != native_id:
            return None
        stream.seek(0, 2)
        start = max(0, stream.tell() - 2 * 1024 * 1024)
        stream.seek(start)
        if start:
            stream.readline()
        lines = stream.read().splitlines()
    for line in reversed(lines):
        try:
            event = json.loads(line)
        except ValueError:
            continue
        payload = event.get('payload') or {}
        if event.get('type') != 'event_msg' or payload.get('type') != 'token_count':
            continue
        info = payload.get('info') or {}
        window = info.get('model_context_window')
        used = (info.get('last_token_usage') or {}).get('total_tokens')
        if not isinstance(window, int) or not isinstance(used, int) or window <= 0 or used < 0:
            return None
        if window <= 12000:
            return 0
        remaining = max(0, window - 12000 - max(0, used - 12000))
        return min(100, math.floor(100 * remaining / (window - 12000) + .5))
    return None


def native_rows(deck, session_id):
    result = subprocess.run(deck + ['recall', 'timeline', session_id, '--tail', '80', '--json'],
                            text=True, capture_output=True, timeout=8)
    value = json.loads(result.stdout)
    if result.returncode and value.get('code') != 'NOT_FOUND':
        raise Refused(value.get('error') or 'Cannot read native delivery evidence.')
    rows = value.get('turns', [])
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise Refused('Native history returned an unrecognized result.')
    return rows


def message_digest(text):
    return hashlib.sha256(text.replace('\r\n', '\n').strip().encode()).hexdigest()


def native_receipt(session, deck, pid, receipt):
    if not isinstance(receipt, dict):
        raise Refused('Native delivery receipt is invalid. Check history before resending.')
    baseline = receipt.get('native_baseline')
    since = receipt.get('native_not_before')
    if (not isinstance(baseline, list) or len(baseline) > 80 or
            any(not isinstance(row, str) for row in baseline) or
            not isinstance(since, (int, float)) or not math.isfinite(since) or since <= 0 or
            not re.fullmatch(r'[a-f0-9]{64}', receipt.get('native_digest', ''))):
        return dict(receipt, state='unknown', settled=True, reason='Native delivery evidence is incomplete. Check history before resending.')
    if (receipt.get('native_pid') != pid or (receipt.get('native_thread') and
            receipt['native_thread'] != session.get('codex_session_id'))):
        return dict(receipt, state='unknown', settled=True, reason='Session changed before delivery could be confirmed. Do not resend without checking history.')
    def is_new(row):
        try:
            return datetime.fromisoformat(row.get('ts', '').replace('Z', '+00:00')).timestamp() >= since
        except (TypeError, ValueError):
            return False

    baseline = set(baseline)
    found = [row for row in native_rows(deck, session['id']) if row.get('kind') == 'user' and
             row.get('id') and row['id'] not in baseline and is_new(row) and
             message_digest(row.get('body', '')) == receipt['native_digest']]
    if len(found) == 1:
        return dict(receipt, state='landed', landed_row_id=found[0]['id'], settled=True, reason='')
    if len(found) > 1:
        return dict(receipt, state='unknown', settled=True, reason='Several matching native inputs appeared. Check history before resending.')
    return receipt


def native_send(session, deck, tmux, pane, pid, capture, message):
    if not message.strip() or len(message.encode()) > 128 * 1024:
        raise Refused('Message must contain text and fit within 128 KiB.')
    if any(ord(char) < 32 and char not in '\n\t' for char in message):
        raise Refused('The message contains unsupported terminal control characters.')
    if message.lstrip().startswith('!') or re.match(r'^/[A-Za-z-]+(?:\s|$)', message.lstrip()):
        raise Refused('Use the terminal for native slash or shell commands.')
    since = math.floor(time.time() * 1000) / 1000
    baseline = [row['id'] for row in native_rows(deck, session['id']) if row.get('id')]
    queue = json.loads(run(deck + ['session', 'queue', 'list', session['id'], '--json']))
    entries = queue if isinstance(queue, list) else queue.get('entries', queue.get('queue', []))
    if any(row.get('state', row.get('status', '')) not in ('landed', 'failed', 'cancelled', 'canceled') for row in entries):
        raise Refused('An older Deck message is still queued. Cancel or resolve it before sending another.')
    input_frame(capture())
    buffer = 'ichuchu-' + uuid.uuid4().hex
    typed = False
    receipt = dict(state='unknown', settled=True, delivery='codex_native', native_pid=pid,
                   native_thread=session.get('codex_session_id', ''), native_baseline=baseline,
                   native_digest=message_digest(message), native_not_before=since)
    try:
        subprocess.run(tmux + ['load-buffer', '-b', buffer, '-'], input=message.encode(),
                       capture_output=True, check=True, timeout=8)
        input_frame(capture())
        # Bracketed paste keeps multiline/untrusted text out of the terminal key parser.
        typed = True
        run(tmux + ['paste-buffer', '-p', '-d', '-b', buffer, '-t', pane])
        time.sleep(.25)
        input_frame(capture(), expected=message)
        run(tmux + ['send-keys', '-t', pane, 'Enter'])
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            try:
                input_frame(capture())
                receipt.update(state='submitted', submitted=True, confirmation='confirmed', settled=False)
                return native_receipt(session, deck, pid, receipt)
            except Refused:
                time.sleep(.1)
        receipt['reason'] = 'Submission could not be confirmed. Check the terminal before resending.'
        return receipt
    except (Refused, OSError, ValueError, subprocess.SubprocessError) as error:
        return dict(receipt, state='typed' if typed else 'failed', reason=str(error))
    finally:
        try:
            subprocess.run(tmux + ['delete-buffer', '-b', buffer], capture_output=True, timeout=8)
        except (OSError, subprocess.SubprocessError):
            pass


def control(executable, profile, session_id, model='', effort='', operation='model', payload=None):
    for value in (profile, session_id):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value):
            raise Refused('Invalid session identity')
    if model and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,99}', model):
        raise Refused('Invalid model')
    if effort and effort not in ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'):
        raise Refused('Invalid reasoning effort')
    if effort == 'ultra':
        raise Refused('Use the terminal for Ultra; in-app controls support the other reasoning levels.')
    deck = [executable, '-p', profile]
    session = json.loads(run(deck + ['session', 'show', session_id, '--json']))
    if session.get('id') != session_id or session.get('ssh_host') or session.get('wrapper'):
        raise Refused('Manage this session in its owning terminal.')
    if operation == 'context' and session.get('tool') != 'codex':
        return dict(remaining=None)
    if session.get('tool') == 'claude':
        if effort:
            raise Refused('Use the Claude terminal to change reasoning effort.')
        if not model:
            return dict(model=session.get('model', ''), effort='', tool='claude')
        outcome = json.loads(run(deck + ['session', 'command', session_id, '/model ' + model,
                                        '--json', '--timeout', '15s'], timeout=22))
        if not outcome.get('ok'):
            raise Refused(outcome.get('error') or outcome.get('message') or 'Model change was not confirmed; check the terminal.')
        return dict(model=model, effort='', tool='claude', confirmed=True)
    if session.get('tool') != 'codex':
        raise Refused('Model controls are available for Codex and Claude.')
    # A dedicated command is only supported in the tested Codex command contract.
    codex = shutil.which('codex') or next((str(path) for path in (
        Path.home() / '.codex/packages/standalone/current/bin/codex',
        Path.home() / '.nix-profile/bin/codex', Path('/opt/homebrew/bin/codex'),
        Path('/usr/local/bin/codex')) if path.is_file() and os.access(path, os.X_OK)), None)
    if not codex:
        raise Refused('Codex executable is unavailable over SSH. Use the terminal.')
    version = run([codex, '--version']).strip()
    if not re.search(r'\b0\.166\.\d+\b', version):
        raise Refused('In-app controls currently require Codex 0.166.x. Open the terminal for this session.')
    models = available_models(session) if operation == 'model' else {}
    if model and (model not in models or (effort and effort not in models[model])):
        raise Refused('This model/reasoning combination is unavailable. Read current settings again.')
    socket = session.get('tmux_socket') or 'agent-deck'
    tmux = ['tmux', '-L', socket]
    name = session.get('tmux_session')
    if not name:
        raise Refused('Session has no live terminal.')
    identity = run(tmux + ['show-environment', '-t', '=' + name, 'AGENTDECK_INSTANCE_ID']).strip()
    if identity != 'AGENTDECK_INSTANCE_ID=' + session_id:
        raise Refused('Terminal identity changed. Reopen this session.')
    panes = run(tmux + ['list-panes', '-t', '=' + name, '-F', '#{pane_id} #{pane_pid} #{pane_dead}']).strip().splitlines()
    if len(panes) != 1 or len(panes[0].split()) != 3 or panes[0].split()[2] != '0':
        raise Refused('Cannot identify one live session pane.')
    pane, pid, _ = panes[0].split()

    def capture():
        live = run(tmux + ['display-message', '-p', '-t', pane, '#{pane_pid} #{pane_dead}']).strip()
        if live != pid + ' 0':
            raise Refused('Session restarted. Reopen model settings.')
        return run(tmux + ['capture-pane', '-p', '-e', '-J', '-t', pane])

    def history():
        return run(tmux + ['capture-pane', '-p', '-J', '-S', '-200', '-t', pane])

    if operation == 'context':
        frame = capture()
        return dict(remaining=context_percent(session, frame), **frame_model(frame))
    if operation == 'receipt':
        capture()  # The pane identity must still match before resolving a pending send.
        return native_receipt(session, deck, pid, payload)
    with send_lock(session_id):
        if operation == 'send':
            return native_send(session, deck, tmux, pane, pid, capture, payload)
        before = capture()
        current_model, current_effort = ready_frame(before)
        if not model and not effort:
            return dict(model=current_model, effort=current_effort, tool='codex', models=models)
        if not model and effort not in models.get(current_model, []):
            raise Refused('This reasoning level is unavailable for the current model.')
        # Prevent changing beneath a queued app message or a concurrent Deck delivery.
        queue = json.loads(run(deck + ['session', 'queue', 'list', session_id, '--json']))
        entries = queue if isinstance(queue, list) else queue.get('entries', queue.get('queue', []))
        if any(item.get('state', item.get('status', '')) not in ('landed', 'failed', 'cancelled', 'canceled') for item in entries):
            raise Refused('Wait for queued messages to finish before changing the model.')
        time.sleep(0.15)
        if capture() != before:
            raise Refused('The terminal is changing. Try again when the turn finishes.')
        if model == current_model and effort == current_effort:
            return dict(model=model, effort=effort, tool='codex', confirmed=True)
        model_arg = model if model != current_model or not effort else ''
        command = '/model' + (' ' + model_arg if model_arg else '') + (' --reasoning=' + effort if effort else '')
        width = int(run(tmux + ['display-message', '-p', '-t', pane, '#{pane_width}']).strip())
        if len(command) > width - 4:
            raise Refused('The terminal is too narrow. Widen it or use its /model menu.')
        previous = ' '.join(history().split())
        # Literal text only, one Enter, no Ctrl+C, no retries and no default config write.
        run(tmux + ['send-keys', '-t', pane, '-l', '--', command])
        time.sleep(0.15)
        ready_frame(capture(), expected=command)
        run(tmux + ['send-keys', '-t', pane, 'Enter'])
        wanted = model or current_model
        pattern = re.compile(r'Model changed to ' + re.escape(wanted) +
                             (r' ' + re.escape(effort) if effort else r'(?: \w+)?') +
                             r' for this session only')
        old_count = len(pattern.findall(previous))
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            frame = capture()
            if len(pattern.findall(' '.join(history().split()))) > old_count:
                try:
                    actual_model, actual_effort = ready_frame(frame)
                    if actual_model == wanted and (not effort or actual_effort == effort):
                        return dict(model=actual_model, effort=actual_effort, tool='codex', confirmed=True)
                except Refused:
                    # The acknowledgement can precede a settled footer. Only reread;
                    # never type, cancel or repeat the already submitted command.
                    pass
            time.sleep(0.2)
        raise Refused('Model change was not confirmed. Check the terminal before trying again.')


def main():
    try:
        if sys.argv[1] in ('--send', '--receipt', '--context'):
            operation = sys.argv[1][2:]
            payload = sys.stdin.read() if operation in ('send', 'receipt') else None
            if operation == 'receipt':
                payload = json.loads(payload)
            result = control(*sys.argv[2:], operation=operation, payload=payload)
        else:
            result = control(*sys.argv[1:])
        print(json.dumps(dict(result, success=True)))
    except (Refused, OSError, ValueError, subprocess.TimeoutExpired) as error:
        result = dict(success=False, error=str(error))
        if sys.argv[1] in ('--send', '--receipt'):
            result.update(state='failed' if sys.argv[1] == '--send' else 'unknown',
                          settled=True, reason=str(error), delivery='codex_native')
        print(json.dumps(result))
        sys.exit(1)


if __name__ == '__main__':
    main()

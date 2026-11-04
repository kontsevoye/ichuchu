# ichuchu-model-control-v1
"""Session-only model controls. Runs on the SSH host; never submits a model prompt.

Codex 0.166's /model arguments apply to the current conversation. Keep them out
of Deck's accepted-turn protocol: control commands do not create a user turn.
The shared Deck send flock serializes this operation with normal deliveries.
"""
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


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


def ready_frame(frame, expected='Ask Codex to do anything'):
    """Fail closed on busy, unknown footer, menus, multiline input and drafts."""
    raw_rows = frame.splitlines()
    rows = re.sub(r'\x1b\[[0-9;:]*[A-Za-z]', '', frame).splitlines()
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
    allowed_footer = tail[-1].startswith('? for shortcuts') or (
        expected.startswith('/model ') and tail[-1] == 'tab to queue message') or bool(
            re.fullmatch(r'⚠ \d+(?: warnings?)? · f2(?: to view)?', tail[-1]))
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
    # Deck 1.16.36 only recognizes the normal Codex prompt for message delivery.
    # Ultra uses »; permit reading/exiting that mode, but don't enable a broken chat.
    models = {m['slug']: [e['effort'] for e in m.get('supported_reasoning_levels', []) if e['effort'] != 'ultra']
              for m in catalog.get('models', []) if m.get('visibility') == 'list' and m.get('slug')}
    if not models:
        raise Refused('Codex has no available models in its catalog.')
    return models


def control(executable, profile, session_id, model='', effort=''):
    for value in (profile, session_id):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', value):
            raise Refused('Invalid session identity')
    if model and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,99}', model):
        raise Refused('Invalid model')
    if effort and effort not in ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'):
        raise Refused('Invalid reasoning effort')
    if effort == 'ultra':
        raise Refused('Use the terminal for Ultra; Agent Deck cannot deliver chat messages in that mode.')
    deck = [executable, '-p', profile]
    session = json.loads(run(deck + ['session', 'show', session_id, '--json']))
    if session.get('id') != session_id or session.get('ssh_host') or session.get('wrapper'):
        raise Refused('This session requires the terminal to change its model.')
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
        raise Refused('In-app controls currently require Codex 0.166.x. Use the terminal /model menu.')
    models = available_models(session)
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

    with send_lock(session_id):
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
        result = control(*sys.argv[1:])
        print(json.dumps(dict(success=True, **result)))
    except (Refused, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(json.dumps(dict(success=False, error=str(error))))
        sys.exit(1)


if __name__ == '__main__':
    main()

import json
from pathlib import Path
import tempfile
import unittest
from contextlib import nullcontext
from unittest.mock import patch
import agent_model_control as control

FRAME = 'Answer\n\n› \x1b[2mAsk Codex to do anything\x1b[0m\n\n  GPT-6.1-Sol high · ~/project · main · Context 96% left\n  ? for shortcuts\n'


class GuardTest(unittest.TestCase):
    def test_acknowledgement_before_footer_settles_never_resubmits(self):
        phase = 'idle'
        captures = 0
        keys = []
        command = '/model --reasoning=low'

        def run(args, timeout=8):
            nonlocal phase, captures
            if '--version' in args:
                return 'codex-cli 0.166.0'
            if 'show' in args:
                return json.dumps({'id': 'qa', 'tool': 'codex', 'tmux_session': 'qa'})
            if 'queue' in args:
                return '[]'
            if 'show-environment' in args:
                return 'AGENTDECK_INSTANCE_ID=qa'
            if 'list-panes' in args:
                return '%1 123 0'
            if 'display-message' in args:
                return '200' if args[-1] == '#{pane_width}' else '123 0'
            if 'send-keys' in args:
                keys.append(args[-1])
                phase = 'submitted' if args[-1] == 'Enter' else 'typed'
                return ''
            if 'capture-pane' in args:
                if '-S' in args:
                    return 'Model changed to gpt-6.1-sol low for this session only' if phase == 'submitted' else 'Answer'
                if phase == 'typed':
                    return FRAME.replace('Ask Codex to do anything', command)
                if phase == 'submitted':
                    captures += 1
                    return FRAME.replace('high', 'low') if captures > 1 else '› \x1b[2mAsk Codex to do anything\x1b[0m\n'
                return FRAME
            raise AssertionError(args)

        with patch.object(control, 'run', side_effect=run), patch.object(control, 'send_lock', return_value=nullcontext()), \
                patch.object(control.shutil, 'which', return_value='codex'), \
                patch.object(control, 'available_models', return_value={'gpt-6.1-sol': ['low', 'high']}), \
                patch.object(control.time, 'sleep'):
            result = control.control('deck', 'qa', 'qa', 'gpt-6.1-sol', 'low')
        self.assertTrue(result['confirmed'])
        self.assertEqual([command, 'Enter'], keys)

    def test_ready(self):
        self.assertEqual(('gpt-6.1-sol', 'high'), control.ready_frame(FRAME))

    def test_ultra_can_be_read_to_switch_back_to_chat(self):
        self.assertEqual(('gpt-6.1-sol', 'ultra'), control.ready_frame(FRAME.replace('›', '»').replace('high', 'ultra')))

    def test_ordinary_answer_words_are_not_interactive_prompts(self):
        self.assertEqual(('gpt-6.1-sol', 'high'), control.ready_frame(FRAME.replace('Answer', 'Please confirm that the job was queued.')))

    def test_pending_native_inputs_are_not_switched_underneath(self):
        with self.assertRaises(control.Refused):
            control.ready_frame(FRAME.replace('Answer', 'Queued follow-up inputs\n  pending request'))

    def test_narrow_terminal_keeps_model_but_truncates_context(self):
        frame = FRAME.replace('~/project · main · Context 96% left', '~/long/project/path…')
        self.assertEqual(('gpt-6.1-sol', 'high'), control.ready_frame(frame))
        command = '/model gpt-6.1-sol --reasoning=low'
        self.assertEqual(('gpt-6.1-sol', 'high'), control.ready_frame(
            frame.replace('Ask Codex to do anything', command).replace('? for shortcuts', '⚠ 2 · f2'), command))

    def test_draft_is_never_placeholder(self):
        for text in ['my unsent message', '/model', '/model gpt-6.1-sol --reasoning=low']:
            with self.assertRaises(control.Refused):
                control.ready_frame(FRAME.replace('Ask Codex to do anything', text))

    def test_literal_placeholder_words_are_still_a_draft(self):
        for style in ['', '\x1b[38;2;2;20;20m', '\x1b[2;22m']:
            with self.assertRaises(control.Refused):
                control.ready_frame(FRAME.replace('\x1b[2m', style))

    def test_multiline_draft(self):
        with self.assertRaises(control.Refused):
            control.ready_frame(FRAME.replace('\n\n  GPT', '\n  second draft line\n\n  GPT'))

    def test_busy_even_with_empty_composer(self):
        with self.assertRaises(control.Refused):
            control.ready_frame(FRAME.replace('Answer', 'Working (esc to interrupt)'))

    def test_menu_with_history_prompt(self):
        with self.assertRaises(control.Refused):
            control.ready_frame(FRAME + '\nSelect model\n› 1. GPT-6\n  2. Other\nEnter to confirm')

    def test_unknown_footer_and_no_prompt(self):
        for frame in [FRAME.replace('Context 96% left', 'Unknown future footer'), 'shell $', FRAME + 'new dialog']:
            with self.assertRaises(control.Refused):
                control.ready_frame(frame)

    def test_exact_owned_command_can_be_submitted(self):
        command = '/model gpt-6.1-sol --reasoning=low'
        for footer in ['tab to queue message', '⚠ 2 warnings · f2 to view', '? for shortcuts']:
            frame = FRAME.replace('Ask Codex to do anything', command).replace('? for shortcuts', footer)
            self.assertEqual(('gpt-6.1-sol', 'high'), control.ready_frame(frame, command))
        with self.assertRaises(control.Refused):
            control.ready_frame(FRAME.replace('Ask Codex to do anything', command + ' added'), command)

    def test_invalid_arguments_never_execute(self):
        for args in [('deck', 'default', '../escape'), ('deck', 'default', 'id', 'x; rm'),
                     ('deck', 'default', 'id', 'model', 'weird'), ('deck', 'default', 'id', '', 'ultra')]:
            with patch.object(control, 'run') as execute:
                with self.assertRaises(control.Refused):
                    control.control(*args)
                execute.assert_not_called()

    def test_catalog_is_session_home_and_excludes_hidden_models(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'models_cache.json'
            path.write_text(json.dumps({'models': [
                {'slug': 'current', 'visibility': 'list', 'supported_reasoning_levels': [{'effort': 'low'}, {'effort': 'ultra'}]},
                {'slug': 'hidden', 'visibility': 'hide'}]}))
            self.assertEqual({'current': ['low']}, control.available_models({'resolved_codex_home': folder}))
            path.unlink()
            with self.assertRaises(control.Refused):
                control.available_models({'resolved_codex_home': folder})

    def test_shared_send_lock_refuses_second_writer(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(control.os.environ, {'XDG_DATA_HOME': folder}):
            (Path(folder) / 'agent-deck/runtime').mkdir(parents=True)
            with control.send_lock('owned-test'):
                with self.assertRaises(control.Refused):
                    control.send_lock('owned-test')
            with control.send_lock('owned-test'):
                pass


if __name__ == '__main__':
    unittest.main()

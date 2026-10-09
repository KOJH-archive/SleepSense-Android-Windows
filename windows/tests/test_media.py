import unittest
from dataclasses import replace
from sleepsense.media import MediaAccess
from sleepsense.policy import Snapshot


class FakeSession:
    def __init__(self):
        self.pause_calls = self.play_calls = 0
        self.result = True

    async def try_pause_async(self):
        self.pause_calls += 1
        return self.result

    async def try_play_async(self):
        self.play_calls += 1
        return self.result


class CommandSafetyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.media = MediaAccess()
        self.selected = FakeSession()
        self.other = FakeSession()
        self.media.entries = {'selected': {'session': self.selected}, 'other': {'session': self.other}}
        self.state = Snapshot('selected', 1, True, False, True, True, 0)
        self.media.snapshot = lambda key: self.state if key == 'selected' else None

    async def test_pause_targets_only_selected_session(self):
        self.assertTrue(await self.media.command('selected', 1))
        self.assertEqual(self.selected.pause_calls, 1)
        self.assertEqual(self.other.pause_calls + self.other.play_calls, 0)

    async def test_new_item_rejects_command(self):
        self.assertFalse(await self.media.command('selected', 2))
        self.assertEqual(self.selected.pause_calls, 0)

    async def test_missing_session_rejects_command(self):
        self.assertFalse(await self.media.command('missing', 1))

    async def test_already_paused_does_not_toggle(self):
        self.state = replace(self.state, playing=False, paused=True)
        self.assertFalse(await self.media.command('selected', 1))
        self.assertEqual(self.selected.pause_calls, 0)

    async def test_pause_capability_required(self):
        self.state = replace(self.state, can_pause=False)
        self.assertFalse(await self.media.command('selected', 1))

    async def test_play_does_not_toggle_playing_session(self):
        self.assertFalse(await self.media.command('selected', 1, play=True))
        self.assertEqual(self.selected.play_calls, 0)

    async def test_external_event_prevents_resume(self):
        self.state = replace(self.state, playing=False, paused=True, revision=2)
        self.assertFalse(await self.media.command('selected', 1, play=True, expected_revision=1))
        self.assertEqual(self.selected.play_calls, 0)

    async def test_explicit_resume_same_item(self):
        self.state = replace(self.state, playing=False, paused=True, revision=1)
        self.assertTrue(await self.media.command('selected', 1, play=True, expected_revision=1))
        self.assertEqual(self.selected.play_calls, 1)
        self.assertEqual(self.other.play_calls, 0)

    async def test_false_api_result_is_false(self):
        self.selected.result = False
        self.assertFalse(await self.media.command('selected', 1))

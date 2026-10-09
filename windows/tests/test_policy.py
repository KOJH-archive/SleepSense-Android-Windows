import unittest
from dataclasses import replace
from sleepsense.policy import NightPolicy, Snapshot

PLAYING = Snapshot('one', 1, True, False, True, True)
PAUSED = replace(PLAYING, playing=False, paused=True, revision=1)


class SafetyTests(unittest.TestCase):
    def arm(self, idle=30, limit=0, quick=False):
        p = NightPolicy()
        p.arm(0, PLAYING, idle, limit, quick)
        return p

    def advance(self, p, end, current=PLAYING, age=None):
        for t in range(1, end + 1):
            decision = p.tick(t, current, t if age is None else age)
        return decision

    def paused(self):
        p = self.arm()
        self.assertEqual(self.advance(p, 45), 'PAUSE')
        p.pause_result(45, PAUSED, True)
        return p

    def test_no_pause_before_idle_and_warning(self):
        p = self.arm()
        self.assertIsNone(self.advance(p, 29))
        self.assertEqual(p.phase, 'WATCH')
        p.tick(30, PLAYING, 30)
        self.assertEqual(p.phase, 'WARNING')
        self.assertIsNone(p.tick(40, PLAYING, 40))
        self.assertEqual(p.tick(45, PLAYING, 45), 'PAUSE')

    def test_recent_input_resets_idle(self):
        p = self.arm()
        self.advance(p, 80, age=1)
        self.assertEqual(p.phase, 'WATCH')

    def test_unknown_input_does_not_trigger_idle(self):
        p = self.arm()
        for t in range(100):
            p.tick(t, PLAYING, None)
        self.assertEqual(p.phase, 'WATCH')

    def test_explicit_limit_works_without_input_signal(self):
        p = self.arm(idle=0, limit=30)
        for t in range(46):
            result = p.tick(t, PLAYING, None)
        self.assertEqual(result, 'PAUSE')

    def test_warning_input_cancels(self):
        p = self.arm()
        self.advance(p, 30)
        p.tick(31, PLAYING, 0)
        self.assertEqual(p.phase, 'COOLDOWN')

    def test_replaced_session_cannot_pause(self):
        p = self.arm()
        p.tick(1, replace(PLAYING, identity='two'), 100)
        self.assertEqual(p.phase, 'DONE')

    def test_new_item_cannot_pause(self):
        p = self.arm()
        p.tick(1, replace(PLAYING, generation=2), 100)
        self.assertEqual(p.phase, 'DONE')

    def test_disappeared_session_stops(self):
        p = self.arm()
        p.tick(1, None, 100)
        self.assertEqual(p.phase, 'DONE')

    def test_manual_pause_stops(self):
        p = self.arm()
        p.tick(1, PAUSED, 100)
        self.assertEqual(p.phase, 'DONE')

    def test_lost_capability_stops(self):
        p = self.arm()
        p.tick(1, replace(PLAYING, can_pause=False), 100)
        self.assertEqual(p.phase, 'DONE')

    def test_suspend_gap_cannot_count_as_idle(self):
        p = self.arm()
        p.tick(60, PLAYING, 60)
        self.assertEqual(p.phase, 'DONE')

    def test_clock_reversal_stops(self):
        p = self.arm()
        p.tick(-1, PLAYING, 100)
        self.assertEqual(p.phase, 'DONE')

    def test_pause_ack_must_be_confirmed(self):
        p = self.arm()
        self.advance(p, 45)
        p.pause_result(45, PLAYING, True)
        self.assertEqual(p.phase, 'FAULT')

    def test_pause_false_cannot_grant_undo(self):
        p = self.arm()
        self.advance(p, 45)
        p.pause_result(45, PAUSED, False)
        self.assertFalse(p.may_undo(46, PAUSED))

    def test_undo_only_confirmed_same_item_within_grace(self):
        p = self.paused()
        self.assertTrue(p.may_undo(46, PAUSED))
        self.assertFalse(p.may_undo(105, PAUSED))
        self.assertFalse(p.may_undo(46, replace(PAUSED, identity='two')))
        self.assertFalse(p.may_undo(46, replace(PAUSED, generation=2)))
        self.assertFalse(p.may_undo(46, replace(PAUSED, can_play=False)))

    def test_intervening_playback_event_revokes_undo(self):
        p = self.paused()
        changed = replace(PAUSED, revision=2)
        self.assertFalse(p.may_undo(46, changed))
        p.tick(46, changed, 10)
        self.assertEqual(p.phase, 'DONE')

    def test_grace_expiry_finishes(self):
        p = self.paused()
        for t in range(46, 106):
            p.tick(t, PAUSED, 10)
        self.assertEqual(p.phase, 'DONE')

    def test_restart_cannot_resume_old_pause(self):
        self.assertFalse(NightPolicy().may_undo(46, PAUSED))

    def test_cancel_twice_finishes(self):
        p = self.arm()
        p.cancel(1)
        p.cancel(2)
        self.assertEqual(p.phase, 'DONE')

    def test_quick_test_cancel_finishes(self):
        p = self.arm(0, 120, True)
        p.cancel(1)
        self.assertEqual(p.phase, 'DONE')

    def test_stopped_policy_never_issues_command(self):
        p = self.arm()
        p.stop()
        self.assertIsNone(p.tick(100, PLAYING, 100))

    def test_arm_requires_playing_capable_session(self):
        with self.assertRaises(ValueError):
            NightPolicy().arm(0, PAUSED, 30, 0)


if __name__ == '__main__':
    unittest.main()

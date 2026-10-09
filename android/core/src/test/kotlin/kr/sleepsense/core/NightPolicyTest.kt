package kr.sleepsense.core

import org.junit.Assert.*
import org.junit.Test

class NightPolicyTest {
    private val playing = TargetSnapshot("chosen", Playback.PLAYING, true, true, 7)
    private val paused = playing.copy(playback = Playback.PAUSED)
    private val cfg = NightConfig(inactivityMs = 10_000, warningMs = 3_000, cooldownMs = 10_000)
    private fun fresh(t: Long, active: Long = 0) = MotionSnapshot(t, active, true)
    private fun armed(config: NightConfig = cfg) = NightPolicy(config, "chosen").also { it.arm(0) }
    private fun candidate(p: NightPolicy) { for (t in 0L..10_000L step 1_000) p.tick(t, playing, fresh(t)) }
    private fun pausing(p: NightPolicy) { candidate(p); for (t in 11_000L..13_000L step 1_000) p.tick(t, playing, fresh(t)) }
    private fun confirmed(p: NightPolicy) { pausing(p); p.tick(14_000, paused, null) }

    @Test fun validIdlePausesExactlyOnce() {
        val p = armed(); candidate(p)
        assertEquals(Phase.PRE_PAUSE, p.view.phase)
        assertNull(p.tick(11_000, playing, fresh(11_000)))
        assertNull(p.tick(12_000, playing, fresh(12_000)))
        assertEquals(Command.PAUSE, p.tick(13_000, playing, fresh(13_000)))
        assertNull(p.tick(14_000, playing, fresh(14_000)))
    }
    @Test fun missingSensorNeverMeansIdle() {
        val p = armed(); for (t in 0L..120_000L step 1_000) assertNull(p.tick(t, playing, null))
        assertEquals(Phase.DEGRADED, p.view.phase)
    }
    @Test fun sensorGapResetsEntireIdleWindow() {
        val p = armed(); for (t in 0L..9_000L step 1_000) p.tick(t, playing, fresh(t))
        p.tick(15_000, playing, MotionSnapshot(9_000, 0, true))
        p.tick(16_000, playing, fresh(16_000))
        assertEquals(Phase.OBSERVING, p.view.phase)
        assertTrue(p.view.remainingMs!! >= 9_000)
    }
    @Test fun callbackGapDoesNotExtendIdle() {
        val p = armed(); p.tick(0, playing, fresh(0)); p.tick(50_000, playing, fresh(50_000))
        assertEquals(Phase.OBSERVING, p.view.phase)
    }
    @Test fun movementCancelsWarning() {
        val p = armed(); candidate(p); p.tick(11_000, playing, fresh(11_000, 11_000))
        assertEquals(Phase.OBSERVING, p.view.phase)
    }
    @Test fun explicitContinueCancelsWarningAndCoolsDown() {
        val p = armed(); candidate(p); p.activity(11_000, true)
        assertEquals(Phase.COOLDOWN, p.view.phase)
        assertNull(p.tick(12_000, playing, fresh(12_000)))
    }
    @Test fun manualPauseNeverCreatesUndo() {
        val p = armed(); candidate(p); p.tick(11_000, paused, fresh(11_000))
        assertFalse(p.canUndo(12_000, paused))
        assertEquals(Phase.OBSERVING, p.view.phase)
    }
    @Test fun targetReplacementFinishesWithoutCommand() {
        val p = armed(); candidate(p)
        assertNull(p.tick(13_000, playing.copy(id = "new"), fresh(13_000)))
        assertEquals(Phase.FINISHED, p.view.phase)
    }
    @Test fun destroyedSessionDiscardsUndo() {
        val p = armed(); confirmed(p); p.tick(15_000, null, null)
        assertFalse(p.canUndo(16_000, paused))
    }
    @Test fun onlyConfirmedSameItemCanUndo() {
        val p = armed(); confirmed(p)
        assertTrue(p.canUndo(15_000, paused))
        assertFalse(p.canUndo(15_000, paused.copy(id = "other")))
        assertFalse(p.canUndo(15_000, paused.copy(mediaGeneration = 8)))
        assertFalse(p.canUndo(15_000, playing))
        assertFalse(p.canUndo(15_000, paused.copy(canPlay = false)))
    }
    @Test fun undoExpiresAtExactDeadline() {
        val p = armed(); confirmed(p)
        assertFalse(p.canUndo(74_000, paused)); p.tick(74_000, paused, null)
        assertEquals(Phase.FINISHED, p.view.phase)
    }
    @Test fun mediaChangeDiscardsOwnership() {
        val p = armed(); confirmed(p); p.tick(15_000, paused.copy(mediaGeneration = 8), null)
        assertEquals(Phase.FINISHED, p.view.phase)
    }
    @Test fun retryIsBoundedAndFailureIsTerminal() {
        val p = armed(); pausing(p)
        assertEquals(Command.RETRY_PAUSE, p.tick(18_000, playing, null))
        assertNull(p.tick(23_000, playing, null))
        assertEquals(Phase.FAULTED, p.view.phase)
        assertNull(p.tick(1_000_000, playing, fresh(1_000_000)))
    }
    @Test fun activityRacingPauseInvalidatesUndo() {
        val p = armed(); pausing(p); p.activity(13_500)
        p.tick(14_000, paused, null)
        assertEquals(Phase.FAULTED, p.view.phase)
        assertFalse(p.canUndo(15_000, paused))
    }
    @Test fun maxTimeWorksWithoutMotionAndDoesNotClaimSleep() {
        val p = armed(cfg.copy(inactivityMs = 0, maxPlaybackMs = 10_000))
        for (t in 0L..10_000L step 1_000) p.tick(t, playing, null)
        assertEquals(Phase.PRE_PAUSE, p.view.phase)
        assertEquals(Reason.MAX_PLAYBACK, p.view.reason)
    }
    @Test fun maxTimeDoesNotCountPausedIntervals() {
        val p = armed(cfg.copy(inactivityMs = 0, maxPlaybackMs = 10_000))
        p.tick(0, playing, null); p.tick(4_000, playing, null)
        p.tick(5_000, paused, null); p.tick(40_000, paused, null)
        p.tick(41_000, playing, null); p.tick(42_000, playing, null)
        assertEquals(Phase.OBSERVING, p.view.phase)
        assertEquals(5_000L, p.view.remainingMs)
    }
    @Test fun independentMaxTimerSurvivesSensorAndSchedulingGap() {
        val p = armed(cfg.copy(inactivityMs = 0, maxPlaybackMs = 10_000))
        for (t in 0L..10_000L step 1_000) p.tick(t, playing, null)
        assertEquals(Command.PAUSE, p.tick(20_000, playing, null))
    }
    @Test fun futureMotionSampleIsInvalid() {
        val p = armed(); p.tick(0, playing, MotionSnapshot(999_999, 0, true))
        assertEquals(Phase.DEGRADED, p.view.phase)
    }
    @Test fun uncalibratedMotionIsNotEvidence() {
        val p = armed(); p.tick(0, playing, MotionSnapshot(0, 0, false))
        assertEquals(Phase.DEGRADED, p.view.phase)
    }
    @Test fun unsupportedPauseFaultsBeforeAnyCommand() {
        val p = armed(); assertNull(p.tick(0, playing.copy(canPause = false), fresh(0)))
        assertEquals(Phase.FAULTED, p.view.phase)
    }
    @Test fun recreatedPolicyCannotUndoPreviousPause() {
        val p = armed(); assertFalse(p.canUndo(1, paused))
    }
    @Test fun backwardsClockFailsClosed() {
        val p = armed(); p.tick(100, playing, fresh(100)); p.tick(99, playing, fresh(99))
        assertEquals(Phase.FAULTED, p.view.phase)
    }
    @Test fun explicitUndoStartsCooldownAndRenewsAllowance() {
        val p = armed(); confirmed(p); p.undoConfirmed(15_000)
        assertEquals(Phase.COOLDOWN, p.view.phase)
        assertFalse(p.canUndo(15_001, paused))
    }
}

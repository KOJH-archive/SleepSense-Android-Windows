package kr.sleepsense.core

enum class Phase { DISARMED, OBSERVING, DEGRADED, PRE_PAUSE, PAUSING, PAUSED_GRACE, COOLDOWN, FINISHED, FAULTED }
enum class Playback { PLAYING, PAUSED, OTHER }
enum class Reason { INACTIVITY, MAX_PLAYBACK }
enum class Command { PAUSE, RETRY_PAUSE }

data class NightConfig(
    val inactivityMs: Long = 45 * 60_000L,
    val maxPlaybackMs: Long = 0,
    val warningMs: Long = 15_000,
    val recoveryMs: Long = 60_000,
    val cooldownMs: Long = 15 * 60_000L,
    val motionStaleMs: Long = 5_000,
    val acknowledgeMs: Long = 5_000,
) {
    init {
        require(inactivityMs >= 0 && maxPlaybackMs >= 0)
        require(inactivityMs > 0 || maxPlaybackMs > 0)
        require(warningMs > 0 && recoveryMs > 0 && cooldownMs > 0)
        require(motionStaleMs > 0 && acknowledgeMs > 0)
    }
}

data class TargetSnapshot(
    val id: String,
    val playback: Playback,
    val canPause: Boolean,
    val canPlay: Boolean,
    val mediaGeneration: Long = 0,
)

data class MotionSnapshot(val measuredAt: Long, val lastActivityAt: Long, val ready: Boolean)
data class PolicyView(
    val phase: Phase = Phase.DISARMED,
    val reason: Reason? = null,
    val remainingMs: Long? = null,
    val message: String = "오늘 밤 보호를 시작해 주세요",
    val canUndo: Boolean = false,
)

/** All durations use a single monotonic clock; no Android or wall-clock dependencies. */
class NightPolicy(private val config: NightConfig, private val targetId: String) {
    var view = PolicyView()
        private set
    private var lastTick = 0L
    private var lastActivity = 0L
    private var playedMs = 0L
    private var wasPlaying = false
    private var deadline = 0L
    private var pauseGeneration: Long? = null
    private var retryCount = 0
    private var recoveries = 0
    private var pauses = 0
    private var reason: Reason? = null

    fun arm(now: Long) {
        require(view.phase == Phase.DISARMED)
        lastTick = now
        lastActivity = now
        view = PolicyView(Phase.OBSERVING, message = "선택한 앱을 보호하고 있어요")
    }

    fun stop(message: String = "오늘 밤 보호를 종료했어요") {
        pauseGeneration = null
        wasPlaying = false
        view = PolicyView(Phase.FINISHED, reason, message = message)
    }

    fun fail(message: String) {
        pauseGeneration = null
        wasPlaying = false
        view = PolicyView(Phase.FAULTED, reason, message = message)
    }

    fun activity(now: Long, explicitContinue: Boolean = false) {
        lastActivity = now
        if (explicitContinue) playedMs = 0
        if (view.phase == Phase.PRE_PAUSE) {
            recoveries++
            if (recoveries >= 2) stop("반복 취소로 오늘 밤 보호를 종료했어요")
            else {
                deadline = now + config.cooldownMs
                view = PolicyView(Phase.COOLDOWN, message = "계속 시청할 수 있도록 잠시 쉬고 있어요")
            }
        } else if (view.phase == Phase.PAUSING) {
            // We cannot prove causality when input races with a pause acknowledgement.
            fail("사용자 활동과 정지 요청이 겹쳐 보호를 종료했어요")
        }
    }

    fun canUndo(now: Long, target: TargetSnapshot?): Boolean =
        view.phase == Phase.PAUSED_GRACE && now < deadline &&
            target?.id == targetId && target.playback == Playback.PAUSED &&
            target.canPlay && pauseGeneration == target.mediaGeneration

    fun undoConfirmed(now: Long) {
        if (view.phase != Phase.PAUSED_GRACE) return
        recoveries++
        pauseGeneration = null
        lastActivity = now
        lastTick = now
        playedMs = 0
        wasPlaying = false
        if (recoveries >= 2) stop("반복 복구로 오늘 밤 보호를 종료했어요")
        else {
            deadline = now + config.cooldownMs
            view = PolicyView(Phase.COOLDOWN, message = "재생을 되돌렸어요. 15분 동안 다시 멈추지 않아요")
        }
    }

    fun tick(now: Long, target: TargetSnapshot?, motion: MotionSnapshot?): Command? {
        if (view.phase in setOf(Phase.DISARMED, Phase.FINISHED, Phase.FAULTED)) return null
        if (now < lastTick) { fail("시간 기준이 변경되어 보호를 종료했어요"); return null }
        val dt = now - lastTick
        lastTick = now
        if (target?.id != targetId) { stop("선택한 미디어 세션이 바뀌어 보호를 종료했어요"); return null }

        if (view.phase == Phase.PAUSED_GRACE) {
            if (target.mediaGeneration != pauseGeneration) {
                stop("재생 항목이 바뀌어 되돌리기를 종료했어요")
            } else if (target.playback != Playback.PAUSED) {
                stop("재생 상태가 바뀌어 오늘 밤 보호를 종료했어요")
            } else if (now >= deadline) {
                stop("재생을 멈추고 감지도 종료했어요")
            } else view = view.copy(remainingMs = deadline - now, canUndo = target.canPlay)
            return null
        }

        if (view.phase == Phase.PAUSING) {
            if (target.mediaGeneration != pauseGeneration) {
                fail("정지 중 재생 항목이 바뀌었어요")
            } else if (target.playback == Playback.PAUSED) {
                pauses++
                deadline = now + config.recoveryMs
                view = PolicyView(Phase.PAUSED_GRACE, reason, config.recoveryMs, "재생을 멈췄어요. 1분 안에 되돌릴 수 있어요", target.canPlay)
            } else if (target.playback != Playback.PLAYING || !target.canPause) {
                fail("미디어 상태를 확인하지 못해 보호를 종료했어요")
            } else if (now >= deadline) {
                if (retryCount == 0) {
                    retryCount = 1
                    deadline = now + config.acknowledgeMs
                    return Command.RETRY_PAUSE
                } else fail("이 앱의 재생을 멈추지 못했어요")
            }
            return null
        }

        if (wasPlaying && target.playback == Playback.PLAYING && dt <= 60_000) playedMs += dt
        wasPlaying = target.playback == Playback.PLAYING
        if (!wasPlaying) {
            lastActivity = now
            reason = null
            if (view.phase != Phase.COOLDOWN) view = PolicyView(Phase.OBSERVING, message = "재생이 돌아오면 새로 관찰해요")
            return null
        }
        if (!target.canPause) { fail("이 미디어는 일시정지 명령을 지원하지 않아요"); return null }

        val motionValid = motion?.ready == true && motion.measuredAt <= now &&
            now - motion.measuredAt <= config.motionStaleMs
        if (config.inactivityMs > 0) {
            if (!motionValid || dt > config.motionStaleMs) lastActivity = now
            else lastActivity = maxOf(lastActivity, motion!!.lastActivityAt.coerceAtMost(now))
        }
        if (view.phase == Phase.COOLDOWN) {
            if (now < deadline) { view = view.copy(remainingMs = deadline - now); return null }
            lastActivity = now
            view = PolicyView(Phase.OBSERVING, message = "보호를 다시 관찰하고 있어요")
        }

        val maxDue = config.maxPlaybackMs > 0 && playedMs >= config.maxPlaybackMs
        val inactivityDue = config.inactivityMs > 0 && motionValid && now - lastActivity >= config.inactivityMs
        val remaining = listOfNotNull(
            if (config.maxPlaybackMs > 0) (config.maxPlaybackMs - playedMs).coerceAtLeast(0) else null,
            if (config.inactivityMs > 0 && motionValid) (config.inactivityMs - (now - lastActivity)).coerceAtLeast(0) else null,
        ).minOrNull()

        if (view.phase == Phase.PRE_PAUSE) {
            val stillDue = if (reason == Reason.MAX_PLAYBACK) maxDue else inactivityDue
            if (!stillDue || (reason == Reason.INACTIVITY && dt > config.motionStaleMs)) {
                reason = null
                view = PolicyView(if (motionValid) Phase.OBSERVING else Phase.DEGRADED, remainingMs = remaining, message = "종료 조건을 다시 확인하고 있어요")
            } else if (now >= deadline) {
                if (pauses >= 3) { stop("오늘 밤 자동 종료 한도에 도달했어요"); return null }
                pauseGeneration = target.mediaGeneration
                retryCount = 0
                deadline = now + config.acknowledgeMs
                view = PolicyView(Phase.PAUSING, reason, message = "재생 중단을 확인하고 있어요")
                return Command.PAUSE
            } else view = view.copy(remainingMs = deadline - now)
            return null
        }
        if (maxDue || inactivityDue) {
            reason = if (maxDue) Reason.MAX_PLAYBACK else Reason.INACTIVITY
            deadline = now + config.warningMs
            view = PolicyView(Phase.PRE_PAUSE, reason, config.warningMs, "15초 뒤 재생을 멈춰요. 계속 재생으로 취소할 수 있어요")
        } else {
            val degraded = config.inactivityMs > 0 && !motionValid
            view = PolicyView(
                if (degraded) Phase.DEGRADED else Phase.OBSERVING,
                remainingMs = remaining,
                message = if (degraded) "움직임 센서를 준비하거나 확인하고 있어요" else "선택한 앱을 보호하고 있어요",
            )
        }
        return null
    }
}

package kr.sleepsense.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.sleepsense.app.MainActivity
import kr.sleepsense.app.BuildConfig
import kr.sleepsense.app.R
import kr.sleepsense.app.SessionUi
import kr.sleepsense.app.graph
import kr.sleepsense.app.protectionNotificationsEnabled
import kr.sleepsense.app.sensors.MotionSource
import kr.sleepsense.core.Command
import kr.sleepsense.core.NightConfig
import kr.sleepsense.core.NightPolicy
import kr.sleepsense.core.Phase
import kr.sleepsense.core.Playback
import java.util.UUID

class NightService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
        policy?.fail("내부 오류로 보호를 종료했어요")
        if (policy != null) finishNight("내부 오류로 보호를 종료했어요")
    })
    private lateinit var motion: MotionSource
    private lateinit var notifications: NotificationManager
    private var policy: NightPolicy? = null
    private var targetId = ""
    private var armId = ""
    private var targetLabel = ""
    private var armedAt = 0L
    private var lastNotificationKey = ""
    private var ticker: Job? = null
    private var undoJob: Job? = null
    private var motionEnabled = false
    private var quickTest = false
    private var receiverRegistered = false
    private var finishing = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                // Screen-on is intentionally not registered: notifications may wake the screen.
                val now = SystemClock.elapsedRealtime()
                motion.markActivity(now)
                policy?.activity(now)
                evaluate()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        motion = MotionSource(this)
        notifications = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "야간 재생 보호", NotificationManager.IMPORTANCE_LOW)
        channel.description = "사용자가 시작한 야간 보호와 되돌리기 상태"
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        notifications.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNight(intent.getStringExtra(EXTRA_TARGET).orEmpty(), BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_QUICK_TEST, false))
            ACTION_STOP -> if (intent.getStringExtra(EXTRA_ARM) == armId && policy != null) finishNight("오늘 밤 보호를 종료했어요") else if (policy == null) stopSelf()
            ACTION_CONTINUE -> if (intent.getStringExtra(EXTRA_ARM) == armId && policy != null) continuePlayback() else if (policy == null) stopSelf()
            else -> if (policy == null) stopSelf()
        }
        // Never reconstruct ownership or re-arm after Android kills the process.
        return START_NOT_STICKY
    }

    private fun startNight(selected: String, test: Boolean) {
        if (policy != null) return
        if (!protectionNotificationsEnabled()) { startFailure("알림을 허용한 뒤 보호를 시작해 주세요"); return }
        val target = graph.media.snapshot(selected)
        if (target == null || target.playback != Playback.PLAYING || !target.canPause) {
            startFailure("재생 중이며 정지 가능한 앱을 선택해 주세요"); return
        }
        val settings = graph.settings.state.value
        if (!test && settings.inactivityMinutes == 0 && settings.maxMinutes == 0) {
            startFailure("움직임 보호 또는 최대 재생시간을 선택해 주세요"); return
        }
        targetId = selected
        armId = UUID.randomUUID().toString()
        targetLabel = graph.media.label(selected) ?: "선택한 앱"
        armedAt = SystemClock.elapsedRealtime()
        quickTest = test
        motionEnabled = !test && settings.inactivityMinutes > 0
        val p = NightPolicy(NightConfig(
            inactivityMs = if (test) 0 else settings.inactivityMinutes * 60_000L,
            maxPlaybackMs = if (test) 120_000 else settings.maxMinutes * 60_000L,
        ), targetId)
        p.arm(armedAt)
        policy = p
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            p.fail("이 기기에서 야간 서비스를 시작하지 못했어요")
            finishNight(p.view.message)
            return
        }
        if (motionEnabled && !motion.start() && settings.maxMinutes == 0) {
            p.fail("움직임 센서를 사용할 수 없어요. 최대 재생시간을 선택해 주세요")
            finishNight(p.view.message)
            return
        }
        ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        notifications.cancel(RESULT_NOTIFICATION_ID)
        publish()
        ticker = scope.launch {
            try {
                while (isActive && !finishing) {
                    evaluate()
                    val phase = policy?.view?.phase
                    delay(if (phase in setOf(Phase.PRE_PAUSE, Phase.PAUSING, Phase.PAUSED_GRACE)) 500 else 2_000)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                policy?.fail("내부 오류로 보호를 종료했어요")
                finishNight(policy?.view?.message ?: "보호를 종료했어요")
            }
        }
    }

    private fun evaluate() {
        if (finishing || undoJob?.isActive == true) return
        val p = policy ?: return
        val now = SystemClock.elapsedRealtime()
        if (!graph.media.accessGranted() || !graph.media.state.value.connected) {
            p.fail("미디어 제어 연결이 끊겨 보호를 종료했어요")
        } else if (!protectionNotificationsEnabled()) {
            p.fail("알림이 꺼져 보호를 종료했어요")
        } else if (now - armedAt >= 8 * 60 * 60_000L) {
            p.stop("8시간 보호 세션을 마쳤어요")
        } else {
            val target = graph.media.snapshot(targetId)
            val command = p.tick(now, target, motion.snapshot())
            if (command == Command.PAUSE || command == Command.RETRY_PAUSE) {
                // Core already rechecked state; the adapter checks identity/actions again.
                if (target == null || !graph.media.pause(targetId, target.mediaGeneration)) p.fail("정지 명령을 전달하지 못했어요")
            }
        }
        when (p.view.phase) {
            Phase.PAUSED_GRACE -> motion.stop()
            Phase.FINISHED, Phase.FAULTED -> { finishNight(p.view.message); return }
            else -> Unit
        }
        publish()
        val notificationKey = "${p.view.phase}:${p.view.remainingMs?.let { if (p.view.phase == Phase.PRE_PAUSE || p.view.phase == Phase.PAUSED_GRACE) it / 1_000 else it / 60_000 }}:${p.view.canUndo}"
        if (notificationKey != lastNotificationKey) {
            notifications.notify(NOTIFICATION_ID, buildNotification())
            lastNotificationKey = notificationKey
        }
    }

    private fun continuePlayback() {
        if (finishing || undoJob?.isActive == true) return
        val p = policy ?: return
        val now = SystemClock.elapsedRealtime()
        if (p.view.phase != Phase.PAUSED_GRACE) {
            if (quickTest) {
                p.stop("2분 기능 확인을 취소했어요")
                finishNight(p.view.message)
                return
            }
            motion.markActivity(now)
            p.activity(now, explicitContinue = true)
            evaluate()
            return
        }
        val target = graph.media.snapshot(targetId)
        if (!p.canUndo(now, target) || target == null) {
            p.stop("되돌리기 조건이 바뀌었어요. 앱에서 다시 재생해 주세요")
            finishNight(p.view.message)
            return
        }
        if (!graph.media.play(targetId, target.mediaGeneration)) {
            p.stop("이 앱에서 재생을 되돌릴 수 없어요")
            finishNight(p.view.message)
            return
        }
        undoJob = scope.launch {
            val until = SystemClock.elapsedRealtime() + 5_000
            while (isActive && !finishing && SystemClock.elapsedRealtime() < until) {
                val current = graph.media.snapshot(targetId)
                if (current == null || current.mediaGeneration != target.mediaGeneration) break
                if (current.playback == Playback.PLAYING) {
                    p.undoConfirmed(SystemClock.elapsedRealtime())
                    if (quickTest) p.stop("재생 정지와 되돌리기 기능 확인을 마쳤어요")
                    if (p.view.phase == Phase.FINISHED) finishNight(p.view.message)
                    else {
                        if (motionEnabled) motion.start()
                        publish()
                    }
                    return@launch
                }
                delay(200)
            }
            if (isActive && !finishing) {
                p.stop("재생 복구를 확인하지 못했어요. 앱에서 확인해 주세요")
                finishNight(p.view.message)
            }
        }
    }

    private fun publish() {
        val p = policy ?: return
        graph.session.value = SessionUi(true, armId, targetLabel, p.view,
            motion.snapshot()?.let { it.ready && SystemClock.elapsedRealtime() - it.measuredAt <= 5_000 } == true,
            motion.available, motionEnabled, quickTest)
    }

    private fun finishNight(message: String) {
        if (finishing) return
        finishing = true
        val p = policy
        if (p?.view?.phase !in setOf(Phase.FINISHED, Phase.FAULTED)) p?.stop(message)
        motion.stop()
        if (receiverRegistered) { unregisterReceiver(unlockReceiver); receiverRegistered = false }
        graph.session.value = SessionUi(false, targetLabel = targetLabel, policy = p?.view ?: kr.sleepsense.core.PolicyView(), message = message)
        graph.settings.saveResult(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.notify(RESULT_NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon).setContentTitle("SleepSense · 보호 종료")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp()).setAutoCancel(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        ticker?.cancel()
        undoJob?.cancel()
        stopSelf()
    }

    private fun startFailure(message: String) {
        graph.session.value = SessionUi(message = message)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val view = policy?.view
        val remaining = view?.remainingMs?.let { if (view.phase in setOf(Phase.PRE_PAUSE, Phase.PAUSED_GRACE)) "${(it + 999) / 1_000}초" else "${(it + 59_999) / 60_000}분" }
        val line = listOfNotNull(view?.message, remaining).joinToString(" · ")
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon).setContentTitle("SleepSense · $targetLabel")
            .setContentText(line).setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "오늘 밤 종료", controlIntent(ACTION_STOP))
        if (view?.phase != Phase.PAUSED_GRACE || view.canUndo) {
            builder.addAction(0, if (view?.phase == Phase.PAUSED_GRACE) "되돌리기" else if (quickTest) "기능 확인 취소" else "계속 재생", controlIntent(ACTION_CONTINUE))
        }
        return builder.build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun controlIntent(action: String): PendingIntent = PendingIntent.getService(this, 0,
        Intent(this, NightService::class.java).setAction(action).putExtra(EXTRA_ARM, armId)
            .setData("sleepsense://control/$armId/$action".toUri()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun onDestroy() {
        motion.stop()
        if (receiverRegistered) { unregisterReceiver(unlockReceiver); receiverRegistered = false }
        scope.cancel()
        if (!finishing && policy != null) {
            graph.session.value = SessionUi(message = "서비스가 중단됐어요. 다시 시작해 주세요")
        }
        policy = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "night_protection"
        private const val NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        const val ACTION_START = "kr.sleepsense.START"
        const val ACTION_STOP = "kr.sleepsense.STOP"
        const val ACTION_CONTINUE = "kr.sleepsense.CONTINUE"
        const val EXTRA_TARGET = "target_id"
        const val EXTRA_ARM = "arm_id"
        const val EXTRA_QUICK_TEST = "quick_test"
    }
}

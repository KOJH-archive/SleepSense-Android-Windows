package kr.sleepsense.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.sleepsense.app.service.NightService
import kr.sleepsense.core.Phase
import kr.sleepsense.core.Playback

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0xFF080B12.toInt()), SystemBarStyle.dark(0xFF080B12.toInt()))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFC0B5F5), onPrimary = Color(0xFF17102D),
                background = Color(0xFF080B12), onBackground = Color(0xFFE5E7F1),
                surface = Color(0xFF121722), onSurface = Color(0xFFE5E7F1),
                surfaceVariant = Color(0xFF1D2331), onSurfaceVariant = Color(0xFFA3ADBF),
                outline = Color(0xFF394255), secondary = Color(0xFF8FD2BF),
            )) { NightScreen() }
        }
    }

    @Composable private fun NightScreen() {
        val media by graph.media.state.collectAsStateWithLifecycle()
        val session by graph.session.collectAsStateWithLifecycle()
        val config by graph.settings.state.collectAsStateWithLifecycle()
        var notificationReady by remember { mutableStateOf(notificationsEnabled()) }
        var accessReady by remember { mutableStateOf(graph.media.accessGranted()) }
        var showAccessDisclosure by remember { mutableStateOf(false) }
        var askedNotification by rememberSaveable { mutableStateOf(false) }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var summary by remember { mutableStateOf(graph.settings.lastResult()) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationReady = notificationsEnabled()
        }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    accessReady = graph.media.accessGranted()
                    notificationReady = notificationsEnabled()
                    graph.media.refresh()
                    summary = graph.settings.lastResult()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(media.choices) {
            if (!session.running && media.choices.none { it.snapshot.id == selected }) {
                selected = media.choices.singleOrNull { it.snapshot.playback == Playback.PLAYING && it.snapshot.canPause }?.snapshot?.id
            }
        }
        val chosen = media.choices.firstOrNull { it.snapshot.id == selected }
        val controlReady = accessReady && media.connected && notificationReady &&
            chosen?.snapshot?.playback == Playback.PLAYING && chosen.snapshot.canPause
        val startAllowed = controlReady && (config.inactivityMinutes > 0 || config.maxMinutes > 0)

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SleepSense", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text("NIGHT  /  0.1", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("잠든 뒤에도\n계속 재생되지 않도록", fontSize = 28.sp, lineHeight = 39.sp, fontWeight = FontWeight.Medium)
                    Text("카메라 없이, 어두운 밤에도 재생을 보호해요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (session.running && session.quickTest) "2분 기능 확인 중" else if (session.running) "오늘 밤 보호 중" else "오늘 밤의 재생 보호", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                        val remaining = session.policy.remainingMs
                        Text(if (session.running && remaining != null) "${(remaining + 59_999) / 60_000}분" else if (session.running) "확인 중" else "어두워도 괜찮아요", fontSize = 30.sp, fontWeight = FontWeight.Medium)
                        Text(if (session.running) session.policy.message else "휴대폰의 움직임과 선택한 시간을 기준으로\n동영상이나 음악을 멈춰요.", fontSize = 14.sp, lineHeight = 23.sp)
                        if (session.running) {
                            Text(session.targetLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            if (session.inactivityEnabled && session.policy.phase != Phase.PAUSED_GRACE) {
                                Text(if (session.sensorReady) "움직임 센서 관찰 중" else "처음 30초는 휴대폰을 내려놓아 주세요", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (!accessReady || !media.connected || !notificationReady) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("시작 준비", fontWeight = FontWeight.SemiBold)
                        if (!accessReady) {
                            Text("선택한 앱의 재생 상태를 확인하려면 알림 접근이 필요해요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { showAccessDisclosure = true }, modifier = Modifier.fillMaxWidth()) { Text("미디어 제어 연결하기") }
                        } else if (!media.connected) {
                            Text("시스템 연결을 기다리고 있어요. 연결이 지속되지 않으면 알림 접근을 껐다 다시 켜 주세요.", fontSize = 13.sp)
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("연결 설정 열기") }
                        }
                        if (!notificationReady) OutlinedButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && !askedNotification && !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                                askedNotification = true
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }, modifier = Modifier.fillMaxWidth()) { Text("보호 상태 알림 허용") }
                    }
                }

                Text("보호할 재생 앱", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (media.choices.isEmpty()) {
                    Text("YouTube 또는 음악 앱에서 재생을 시작한 뒤 돌아와 주세요. 제어 가능한 앱만 표시돼요.", fontSize = 13.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else media.choices.forEach { choice ->
                    val actionable = choice.snapshot.playback == Playback.PLAYING && choice.snapshot.canPause
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == choice.snapshot.id, onClick = { selected = choice.snapshot.id }, enabled = !session.running && actionable)
                        Column(Modifier.weight(1f)) {
                            Text(choice.label, fontSize = 15.sp)
                            Text(if (actionable) "재생 중 · 정지 가능" else "재생 중이 아니거나 정지 미지원", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Text("오늘 밤 설정", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !config.musicMode, onClick = { graph.settings.save(config.copy(musicMode = false)) }, label = { Text("동영상") }, enabled = !session.running)
                    FilterChip(selected = config.musicMode, onClick = { graph.settings.save(config.copy(musicMode = true)) }, label = { Text("음악") }, enabled = !session.running)
                }
                DurationSetting("기기 움직임이 없으면", config.inactivityMinutes, listOf(0, 15, 30, 45, 60, 90, 120), !session.running) {
                    graph.settings.save(config.copy(inactivityMinutes = it))
                }
                DurationSetting("최대 재생시간", config.maxMinutes, listOf(0, 30, 60, 90, 120, 180), !session.running) {
                    graph.settings.save(config.copy(maxMinutes = it))
                }
                Text("휴대폰을 거치해 두고 깨어 있어도 설정 시간이 지나면 멈출 수 있어요. 실제 수면을 확정하는 기능은 아니에요.", fontSize = 12.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (config.inactivityMinutes == 0 && config.maxMinutes == 0) Text("보호 기준을 하나 이상 선택해 주세요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)

                if (session.running) {
                    if (session.policy.phase != Phase.PAUSED_GRACE || session.policy.canUndo) Button(
                        onClick = { control(NightService.ACTION_CONTINUE, session.armId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text(if (session.policy.phase == Phase.PAUSED_GRACE) "재생 되돌리기" else if (session.quickTest) "기능 확인 취소" else "계속 재생 · 시간 새로 시작") }
                    OutlinedButton(onClick = { control(NightService.ACTION_STOP, session.armId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("오늘 밤 보호 종료") }
                } else Button(onClick = {
                    error = null
                    val id = selected
                    if (id != null) runCatching {
                        ContextCompat.startForegroundService(this@MainActivity,
                            Intent(this@MainActivity, NightService::class.java).setAction(NightService.ACTION_START).putExtra(NightService.EXTRA_TARGET, id))
                    }.onFailure { error = "보호 서비스를 시작하지 못했어요. 설정을 확인해 주세요." }
                }, enabled = startAllowed, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) { Text("오늘 밤 보호 시작", fontWeight = FontWeight.SemiBold) }

                if (BuildConfig.DEBUG && !session.running) OutlinedButton(onClick = {
                    error = null
                    selected?.let { id -> runCatching {
                        ContextCompat.startForegroundService(this@MainActivity,
                            Intent(this@MainActivity, NightService::class.java).setAction(NightService.ACTION_START)
                                .putExtra(NightService.EXTRA_TARGET, id).putExtra(NightService.EXTRA_QUICK_TEST, true))
                    }.onFailure { error = "기능 확인을 시작하지 못했어요." } }
                }, enabled = controlReady, modifier = Modifier.fillMaxWidth()) { Text("2분 기능 확인 · 실제 재생이 멈춰요") }

                val notice = error ?: session.message ?: if (!session.running) summary else null
                if (notice != null) Text(notice, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                if (summary != null && !session.running) TextButton(onClick = { graph.settings.deleteResult(); summary = null }) { Text("최근 기록 지우기") }
                Text("카메라·마이크·인터넷 권한 없이 작동해요. 화면을 강제로 끄거나 앱을 종료하지 않아요.", fontSize = 12.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("갤럭시에서 감지가 자주 중단되면 앱 정보 → 배터리 설정을 확인해 주세요. 설정 변경 후 실제 재생 중에도 계속 동작하는지 확인이 필요해요.", fontSize = 12.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) }) { Text("앱 권한과 배터리 설정") }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (showAccessDisclosure) AlertDialog(
            onDismissRequest = { showAccessDisclosure = false },
            title = { Text("미디어 제어 연결") },
            text = { Text("Android는 미디어 제어를 위해 넓은 알림 접근 권한을 제공해요. SleepSense는 미디어 세션과 재생 상태만 사용하고 대화·알림 본문은 읽거나 저장하지 않아요. 설정에서 언제든 해제할 수 있어요.") },
            confirmButton = { TextButton(onClick = { showAccessDisclosure = false; startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("설정으로 이동") } },
            dismissButton = { TextButton(onClick = { showAccessDisclosure = false }) { Text("취소") } },
        )
    }

    @Composable private fun DurationSetting(title: String, selected: Int, values: List<Int>, enabled: Boolean, change: (Int) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 14.sp)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(if (selected == 0) "사용 안 함" else "${selected}분") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    values.forEach { value -> DropdownMenuItem(text = { Text(if (value == 0) "사용 안 함" else "${value}분") }, onClick = { expanded = false; change(value) }) }
                }
            }
        }
    }

    private fun notificationsEnabled() = protectionNotificationsEnabled()
    private fun control(action: String, arm: String) {
        startService(Intent(this, NightService::class.java).setAction(action).putExtra(NightService.EXTRA_ARM, arm))
    }
}

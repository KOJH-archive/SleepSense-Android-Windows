package kr.sleepsense.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.sleepsense.app.media.MediaRepository
import kr.sleepsense.core.PolicyView

class SleepSenseApp : Application() {
    val graph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as SleepSenseApp).graph

fun Context.protectionNotificationsEnabled(): Boolean {
    val manager = getSystemService(NotificationManager::class.java)
    return manager.areNotificationsEnabled() &&
        manager.getNotificationChannel("night_protection")?.importance != NotificationManager.IMPORTANCE_NONE
}

class AppGraph(context: Context) {
    val media = MediaRepository(context)
    val settings = SettingsStore(context)
    val session = MutableStateFlow(SessionUi())
}

data class SessionUi(
    val running: Boolean = false,
    val armId: String = "",
    val targetLabel: String = "",
    val policy: PolicyView = PolicyView(),
    val sensorReady: Boolean = false,
    val sensorAvailable: Boolean = true,
    val inactivityEnabled: Boolean = false,
    val quickTest: Boolean = false,
    val message: String? = null,
)

data class NightSettings(
    val inactivityMinutes: Int = 45,
    val maxMinutes: Int = 0,
    val musicMode: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("night_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(NightSettings(
        prefs.getInt("idle", 45).takeIf { it in setOf(0, 15, 30, 45, 60, 90, 120) } ?: 45,
        prefs.getInt("max", 0).takeIf { it in setOf(0, 30, 60, 90, 120, 180) } ?: 0,
        prefs.getBoolean("music", false),
    ))
    val state = mutable.asStateFlow()
    fun save(value: NightSettings) {
        mutable.value = value
        prefs.edit { putInt("idle", value.inactivityMinutes); putInt("max", value.maxMinutes); putBoolean("music", value.musicMode) }
    }
    fun saveResult(message: String) {
        // One local summary only; no media title, notification body, or sensor measurements.
        prefs.edit { putString("last_result", message); putLong("last_result_at", System.currentTimeMillis()) }
    }
    fun lastResult(): String? {
        val time = prefs.getLong("last_result_at", 0)
        if (time <= 0 || System.currentTimeMillis() - time !in 0..7 * 24 * 60 * 60_000L) return null
        return prefs.getString("last_result", null)
    }
    fun deleteResult() { prefs.edit { remove("last_result"); remove("last_result_at") } }
}

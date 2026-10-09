package kr.sleepsense.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kr.sleepsense.core.MotionSnapshot
import kr.sleepsense.core.MotionQuality
import kotlin.math.abs
import kotlin.math.sqrt

/** No wake lock: a suspended sensor becomes stale rather than evidence of inactivity. */
class MotionSource(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available get() = sensor != null
    private var registered = false
    private var startAt = 0L
    private var sampleAt = -1L
    private var activityAt = 0L
    private var movingSince: Long? = null
    private var threshold = 0.35
    private var calibrated = false
    private val baseline = ArrayList<Double>(320)
    private val gravity = FloatArray(3)

    fun start(): Boolean {
        if (registered) return true
        if (sensor == null) return false
        startAt = SystemClock.elapsedRealtime()
        sampleAt = -1
        activityAt = startAt
        calibrated = false
        movingSince = null
        baseline.clear()
        gravity.fill(0f)
        registered = manager.registerListener(this, sensor, 100_000, 1_000_000)
        return registered
    }

    fun snapshot(): MotionSnapshot? = if (registered && sampleAt >= 0) MotionSnapshot(sampleAt, activityAt, calibrated) else null
    fun markActivity(now: Long) { activityAt = now }
    fun stop() { manager.unregisterListener(this); registered = false; baseline.clear(); sampleAt = -1; calibrated = false }

    override fun onSensorChanged(event: SensorEvent) {
        val now = event.timestamp / 1_000_000
        if (!registered || now < sampleAt) return
        if (!MotionQuality.validAcceleration(event.values)) {
            calibrated = false
            baseline.clear()
            startAt = now
            sampleAt = now
            activityAt = now
            movingSince = null
            return
        }
        if (sampleAt < 0 || now - sampleAt > 1_000) {
            for (i in 0..2) gravity[i] = event.values[i]
            movingSince = null
        }
        sampleAt = now
        var square = 0.0
        for (i in 0..2) {
            gravity[i] = 0.8f * gravity[i] + 0.2f * event.values[i]
            val v = (event.values[i] - gravity[i]).toDouble()
            square += v * v
        }
        val magnitude = sqrt(square)
        if (!calibrated) {
            activityAt = now
            if (baseline.size < 320) baseline.add(magnitude)
            if (now - startAt >= 30_000 && baseline.size >= 100) {
                val sorted = baseline.sorted()
                val median = sorted[sorted.size / 2]
                val deviations = baseline.map { abs(it - median) }.sorted()
                threshold = (median + 6 * deviations[deviations.size / 2]).coerceIn(0.15, 1.5)
                calibrated = true
                baseline.clear()
            }
            return
        }
        if (magnitude > threshold) {
            val since = movingSince ?: now.also { movingSince = it }
            if (now - since >= 2_000) activityAt = now
        } else movingSince = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

package kr.sleepsense.core

object MotionQuality {
    /** Accelerometer input includes gravity. Missing, non-finite, or impossible readings aren't idle. */
    fun validAcceleration(values: FloatArray): Boolean {
        if (values.size < 3 || (0..2).any { !values[it].isFinite() }) return false
        val square = (0..2).sumOf { values[it].toDouble() * values[it] }
        return square in 4.0..10_000.0
    }
}

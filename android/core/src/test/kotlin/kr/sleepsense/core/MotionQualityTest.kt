package kr.sleepsense.core

import org.junit.Assert.*
import org.junit.Test

class MotionQualityTest {
    @Test fun gravityAndModerateMovementAreValid() {
        assertTrue(MotionQuality.validAcceleration(floatArrayOf(0f, 0f, 9.81f)))
        assertTrue(MotionQuality.validAcceleration(floatArrayOf(3f, -2f, 10f)))
    }
    @Test fun zeroOrIncompleteSensorInputIsNotInactivity() {
        assertFalse(MotionQuality.validAcceleration(floatArrayOf(0f, 0f, 0f)))
        assertFalse(MotionQuality.validAcceleration(floatArrayOf(9.81f, 0f)))
    }
    @Test fun nonFiniteValuesAreRejected() {
        assertFalse(MotionQuality.validAcceleration(floatArrayOf(Float.NaN, 0f, 9f)))
        assertFalse(MotionQuality.validAcceleration(floatArrayOf(0f, Float.POSITIVE_INFINITY, 9f)))
    }
    @Test fun outOfRangeReadingIsRejected() {
        assertFalse(MotionQuality.validAcceleration(floatArrayOf(1f, 0f, 0f)))
        assertFalse(MotionQuality.validAcceleration(floatArrayOf(999f, 0f, 9f)))
    }
}

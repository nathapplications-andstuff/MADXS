package com.nathangamalnasser.natapps.recorder

/**
 * Detects a physical double-tap on the phone — used to pause/resume a recording
 * session without touching the screen (phone stays in the pocket).
 *
 * Two conditions must both hold, not just one:
 *  1. Shape: a tap is a brief, sharp deviation in accel magnitude from the resting
 *     baseline that resolves within [tapMaxDurationMs]. Sustained motion never resolves
 *     that fast and never counts.
 *  2. Stationary gate: gyro magnitude must be near zero — i.e. you're not turning,
 *     carving, or throwing a punch. Skating and boxing both produce real rotation;
 *     a tap on a stationary phone doesn't. If gyro is elevated, tap detection doesn't
 *     arm at all, regardless of what the accelerometer shape looks like.
 *
 * Pure and stateful but I/O-free on purpose — testable with synthetic samples,
 * no sensor/service dependency.
 */
class DoubleTapDetector(
    private val tapTriggerMag: Double = 6.0,
    private val tapMaxDurationMs: Long = 180L,
    private val doubleTapMinGapMs: Long = 100L,
    private val doubleTapMaxGapMs: Long = 600L,
    private val baselineAlpha: Double = 0.05,
    private val stationaryGyroMaxRadS: Double = 0.4
) {
    private var baseline = 9.81
    private var inTap = false
    private var tapStartMs = 0L
    private var lastSingleTapMs = 0L

    /** Feed one sample (accel magnitude + gyro magnitude); returns true exactly when a double-tap completes. */
    fun onSample(nowMs: Long, accelMag: Double, gyroMag: Double): Boolean {
        val stationary = gyroMag <= stationaryGyroMaxRadS

        if (!inTap) {
            if (!stationary) return false   // moving/turning — never arm a tap candidate
            val dev = Math.abs(accelMag - baseline)
            if (dev > tapTriggerMag) {
                inTap = true
                tapStartMs = nowMs
            } else {
                baseline += baselineAlpha * (accelMag - baseline)
            }
            return false
        }

        // Currently inside a candidate tap spike
        if (!stationary) {
            // rotation appeared mid-spike — not a clean stationary tap, abort
            inTap = false
            return false
        }
        val elapsed = nowMs - tapStartMs
        val dev = Math.abs(accelMag - baseline)
        if (dev <= tapTriggerMag * 0.5) {
            inTap = false
            if (elapsed > tapMaxDurationMs) return false   // resolved too slowly — not a tap
            return registerSingleTap(nowMs)
        }
        if (elapsed > tapMaxDurationMs) {
            // still elevated well past a tap's duration — sustained motion, abort
            inTap = false
        }
        return false
    }

    private fun registerSingleTap(nowMs: Long): Boolean {
        val prev = lastSingleTapMs
        if (prev != 0L) {
            val gap = nowMs - prev
            if (gap in doubleTapMinGapMs..doubleTapMaxGapMs) {
                lastSingleTapMs = 0L
                return true
            }
        }
        // Either the first tap seen, or the pairing failed (too close/stale) —
        // this tap becomes the new "first" to pair against.
        lastSingleTapMs = nowMs
        return false
    }
}

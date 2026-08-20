package com.nathangamalnasser.natapps.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapDetectorTest {

    private val REST = 9.81
    private val STILL_GYRO = 0.0

    /** Feeds a resolved spike (up then back down) as two samples: peak, then back-to-rest. */
    private fun tap(
        d: DoubleTapDetector,
        peakAtMs: Long,
        resolveAtMs: Long,
        peakMag: Double = REST + 8.0,
        gyroMag: Double = STILL_GYRO
    ): Boolean {
        d.onSample(peakAtMs, peakMag, gyroMag)
        return d.onSample(resolveAtMs, REST, gyroMag)
    }

    @Test
    fun twoCleanTapsWithinWindow_detectsDoubleTap() {
        val d = DoubleTapDetector()
        assertFalse(tap(d, 1000, 1030))                 // first tap resolves — no pair yet
        assertTrue(tap(d, 1330, 1360))                   // second tap ~300ms later — pairs
    }

    @Test
    fun singleIsolatedTap_neverTriggers() {
        val d = DoubleTapDetector()
        assertFalse(tap(d, 1000, 1030))
        // idle rest samples afterward, no second tap
        assertFalse(d.onSample(2000, REST, STILL_GYRO))
        assertFalse(d.onSample(3000, REST, STILL_GYRO))
    }

    @Test
    fun sustainedElevatedMotion_neverCountsAsTap() {
        // Simulates active skating: magnitude stays elevated well past tapMaxDurationMs
        val d = DoubleTapDetector(tapMaxDurationMs = 180L)
        var triggered = false
        var t = 1000L
        while (t < 1000 + 2000) {
            if (d.onSample(t, REST + 8.0, STILL_GYRO)) triggered = true
            t += 20
        }
        assertFalse(triggered)
    }

    @Test
    fun tapsTooCloseTogether_treatedAsBounceNotDoubleTap() {
        // Mechanical bounce of a single physical tap arriving as two spikes 50ms apart
        // (below doubleTapMinGapMs=100) must not itself register as the double-tap.
        val d = DoubleTapDetector(doubleTapMinGapMs = 100L, doubleTapMaxGapMs = 600L)
        assertFalse(tap(d, 1000, 1020))
        assertFalse(tap(d, 1050, 1070))   // 30ms gap from previous resolve — too close, rejected
        // but a real second tap at a valid gap from THIS one still pairs
        assertTrue(tap(d, 1350, 1370))
    }

    @Test
    fun tapsTooFarApart_treatedAsSeparateSingles() {
        val d = DoubleTapDetector(doubleTapMinGapMs = 100L, doubleTapMaxGapMs = 600L)
        assertFalse(tap(d, 1000, 1020))
        assertFalse(tap(d, 2000, 2020))   // ~1000ms later — stale, not paired
        // resets as a fresh "first" tap; a prompt follow-up now pairs
        assertTrue(tap(d, 2300, 2320))
    }

    @Test
    fun quietBaselineDrift_doesNotFalseTrigger() {
        val d = DoubleTapDetector()
        var triggered = false
        var mag = REST
        var t = 1000L
        // Slow walk-like drift in resting magnitude, never spiking
        repeat(200) {
            mag += 0.02
            if (d.onSample(t, mag, STILL_GYRO)) triggered = true
            t += 20
        }
        assertFalse(triggered)
    }

    @Test
    fun tapShapedSpikesWhileRotating_neverTrigger() {
        // Simulates active skating/turning: gyro is elevated (real rotation) the whole
        // time, even though the accel signal has a clean tap-shaped double-spike. The
        // stationary gate must reject this outright regardless of accel shape.
        val d = DoubleTapDetector(stationaryGyroMaxRadS = 0.4)
        val activeGyro = 1.2
        assertFalse(tap(d, 1000, 1030, gyroMag = activeGyro))
        assertFalse(tap(d, 1330, 1360, gyroMag = activeGyro))
    }

    @Test
    fun rotationMidSpike_abortsThatCandidateTap() {
        // Gyro is calm when the spike starts but picks up rotation before it resolves —
        // e.g. a bump while just starting to push off. Must not resolve as a tap.
        val d = DoubleTapDetector()
        d.onSample(1000, REST + 8.0, STILL_GYRO)   // spike starts, stationary
        val resolved = d.onSample(1030, REST, 1.5)  // resolves back to rest, but now rotating
        assertFalse(resolved)
    }

    @Test
    fun tapsAfterMotionStops_stillDetected() {
        // Real flow: skating (elevated gyro) then coming to a stop (gyro settles),
        // then tapping. The prior motion must not have poisoned the baseline/state.
        val d = DoubleTapDetector()
        var t = 1000L
        repeat(50) { d.onSample(t, REST + 3.0, 2.0); t += 20 }   // skating, gyro elevated
        t += 500
        assertFalse(tap(d, t, t + 30))                 // now stationary — first tap
        assertTrue(tap(d, t + 330, t + 360))            // second tap pairs
    }
}

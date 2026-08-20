package com.nathangamalnasser.natapps.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class UisScorerTest {

    @Test
    fun boxing_usesAccelWeightedWeightsAndMax200() {
        // rawScore = 0.65*peakAccel + 0.35*peakGyro, per CLAUDE.md
        val r = UisScorer.compute("boxing", peakAccelMag = 100.0, peakGyroMag = 100.0)
        assertEquals(100.0, r.raw, 1e-9)          // 0.65*100 + 0.35*100 = 100
        assertEquals(500, r.score)                 // 100/200*1000 = 500
    }

    @Test
    fun rollerblade_usesGyroWeightedWeightsAndMax100() {
        // any sport string other than "boxing" falls through to rollerblade weights
        val r = UisScorer.compute("rollerblade", peakAccelMag = 100.0, peakGyroMag = 100.0)
        assertEquals(100.0, r.raw, 1e-9)          // 0.45*100 + 0.55*100 = 100
        assertEquals(1000, r.score)                // 100/100*1000 = 1000, at the ceiling exactly
    }

    @Test
    fun zeroPeaks_scoreZero_bothSports() {
        assertEquals(0, UisScorer.compute("boxing", 0.0, 0.0).score)
        assertEquals(0, UisScorer.compute("rollerblade", 0.0, 0.0).score)
    }

    @Test
    fun scoreClampsAtCeiling_neverExceeds1000() {
        val r = UisScorer.compute("boxing", peakAccelMag = 9999.0, peakGyroMag = 9999.0)
        assertEquals(1000, r.score)
    }

    @Test
    fun scoreNeverNegative_withZeroOrPositiveInputs() {
        // peaks are magnitudes (sqrt of squares) so can't be negative in real use,
        // but the clamp itself must still hold at the floor
        val r = UisScorer.compute("rollerblade", 0.0, 0.0)
        assertEquals(0, r.score)
        assert(r.score >= 0)
    }

    @Test
    fun unknownSportString_fallsThroughToRollerbladeWeights() {
        // compute() only special-cases the literal string "boxing" — anything else,
        // including a typo or unset value, must not silently pick boxing's weights
        val known = UisScorer.compute("rollerblade", 50.0, 50.0)
        val unknown = UisScorer.compute("not-a-real-sport", 50.0, 50.0)
        assertEquals(known.raw, unknown.raw, 1e-9)
        assertEquals(known.score, unknown.score)
    }
}

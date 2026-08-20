package com.nathangamalnasser.natapps.recorder

/**
 * Universal Impact Score — weights peak accel/gyro magnitude to a 0-1000 scale.
 * Weights and calibration per CLAUDE.md.
 */
object UisScorer {

    private const val WA_BOXING = 0.65
    private const val WG_BOXING = 0.35
    private const val MAX_BOXING = 200.0

    private const val WA_ROLLER = 0.45
    private const val WG_ROLLER = 0.55
    private const val MAX_ROLLER = 100.0

    data class Result(val raw: Double, val score: Int)

    fun compute(sport: String, peakAccelMag: Double, peakGyroMag: Double): Result {
        val (wa, wg, max) = if (sport == "boxing") Triple(WA_BOXING, WG_BOXING, MAX_BOXING)
                            else Triple(WA_ROLLER, WG_ROLLER, MAX_ROLLER)
        val raw = wa * peakAccelMag + wg * peakGyroMag
        val score = Math.round(raw / max * 1000.0).toInt().coerceIn(0, 1000)
        return Result(raw, score)
    }
}

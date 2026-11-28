// SPDX-License-Identifier: MIT
package app.fieldwatch.sweep.radio.monas

import kotlin.math.abs
import kotlin.math.hypot

enum class SeparationVerdict(val label: String, val explanation: String) {
    DISTINCT_OSCILLATORS("Distinct oscillators", "At least 5σ CFO separation supports distinct crystals during overlapping observations. A rapid thermal step can shift a crystal."),
    MODERATE_SEPARATION("Moderate separation", "2σ to under 5σ is inconclusive. Handling or pocket warmth can shift a crystal by +0.8 to +2.9 ppm."),
    OVERLAPPING_CLUSTER("Indistinguishable cluster", "Under 2σ, the offsets overlap. Commercial crystals cluster around ±3.7 ppm. One crowd model exceeds 50% collision probability at nine devices. Overlap never proves shared identity."),
}

data class PairSeparation(val zCfo: Double, val zCombined: Double?, val verdict: SeparationVerdict) {
    val explanation get() = verdict.explanation
}
data class MonasPairCounts(val rotatingRows: Int, val distinct: Int, val overlapping: Int,
                           val rotatingDistinct: Int, val rotatingOverlapping: Int, val evaluated: Int)

object MonasdAnalyzer {
    internal fun healthChange(a: MonasHealth, b: MonasHealth): String? = when {
        a.radio != b.radio || a.view != b.view || a.sampleRateHz != b.sampleRateHz -> "Radio, view or sample rate changed."
        a.stale != b.stale || a.streaming != b.streaming -> "Radio stream continuity changed."
        a.lostSamples != null && b.lostSamples != null && b.lostSamples < a.lostSamples -> "Radio loss counters restarted."
        else -> null
    }

    fun continuity(previous: MonasSnapshot?, current: MonasSnapshot): String? {
        current.continuityIssue?.let { return it }
        if (previous == null) return null
        previous.continuityIssue?.let { return it }
        healthChange(previous.health, current.health)?.let { return it }
        val old = previous.provenance
        val new = current.provenance
        return when {
            previous.sample != current.sample -> "Census source changed."
            previous.radios.filter { it.open } != current.radios.filter { it.open } -> "Active receiver changed."
            previous.census.testimony.stale != current.census.testimony.stale ||
                previous.ble.testimony.stale != current.ble.testimony.stale ||
                previous.census.testimony.feedLoss != current.census.testimony.feedLoss ||
                previous.ble.testimony.feedLoss != current.ble.testimony.feedLoss -> "Staleness or feed integrity changed."
            old.tier != new.tier || old.mode != new.mode || old.masked != new.masked || old.addressesMode != new.addressesMode ->
                "Reference, address privacy or receiver mode changed."
            old.sessionSeconds != null && new.sessionSeconds != null && new.sessionSeconds < old.sessionSeconds -> "Receiver session restarted."
            else -> null
        }
    }

    fun unavailable(s: MonasSnapshot): String? = when {
        s.continuityIssue != null -> s.continuityIssue + " Reconnect before comparing."
        s.stale -> "The receiver marked this snapshot stale or stopped streaming."
        s.health.sampleRateHz == null || s.health.sampleRateHz <= 0.0 -> "Receiver IQ sample rate is unavailable."
        s.census.testimony.absent != null || s.ble.testimony.absent != null -> "The receiver reports absent data."
        s.provenance.expired || (s.provenance.referenceAgeSec ?: 0) > 900 -> "The frequency reference has expired."
        s.provenance.tier == MonasReferenceTier.UNKNOWN -> "Reference provenance is unknown."
        s.provenance.tier != MonasReferenceTier.RELATIVE && s.provenance.referenceAgeSec == null -> "Reference age is unavailable."
        else -> null
    }

    /** Both indices belong to this single census. There is deliberately no cross-snapshot pairing API. */
    fun pair(s: MonasSnapshot, first: Int, second: Int): PairSeparation? {
        if (unavailable(s) != null || first == second) return null
        val a = s.census.devices.getOrNull(first) ?: return null
        val b = s.census.devices.getOrNull(second) ?: return null
        return separate(a, b)
    }

    private fun separate(a: MonasCensusDevice, b: MonasCensusDevice): PairSeparation? {
        if (a.address.equals(b.address, true)) return null
        val aFirst = a.firstSeenSec ?: return null
        val aLast = a.lastSeenSec ?: return null
        val bFirst = b.firstSeenSec ?: return null
        val bLast = b.lastSeenSec ?: return null
        // Interval intersection works for both seconds-since-start and ages before this census.
        if (maxOf(minOf(aFirst, aLast), minOf(bFirst, bLast)) > minOf(maxOf(aFirst, aLast), maxOf(bFirst, bLast))) return null
        val cfo = distance(a.crystalOffsetPpm, b.crystalOffsetPpm) ?: return null
        val modulation = distance(a.modulationIndex, b.modulationIndex)
        val verdict = when {
            cfo >= 5 -> SeparationVerdict.DISTINCT_OSCILLATORS
            cfo >= 2 -> SeparationVerdict.MODERATE_SEPARATION
            else -> SeparationVerdict.OVERLAPPING_CLUSTER
        }
        return PairSeparation(cfo, modulation?.let { hypot(cfo, it).takeIf(Double::isFinite) }, verdict)
    }

    private fun distance(a: MonasSigma?, b: MonasSigma?): Double? {
        if (a == null || b == null || !a.value.isFinite() || !b.value.isFinite() ||
            !a.sigma.isFinite() || !b.sigma.isFinite() || a.sigma <= 0 || b.sigma <= 0) return null
        val uncertainty = hypot(a.sigma, b.sigma).takeIf(Double::isFinite) ?: return null
        return (abs(a.value - b.value) / uncertainty).takeIf(Double::isFinite)
    }

    fun counts(s: MonasSnapshot): MonasPairCounts {
        val rows = s.census.devices
        var distinct = 0
        var overlapping = 0
        var rotatingDistinct = 0
        var rotatingOverlapping = 0
        var evaluated = 0
        if (unavailable(s) == null) for (i in rows.indices) for (j in 0 until i) {
            val result = separate(rows[i], rows[j]) ?: continue
            evaluated++
            val rotating = rows[i].rotating && rows[j].rotating
            when (result.verdict) {
                SeparationVerdict.DISTINCT_OSCILLATORS -> { distinct++; if (rotating) rotatingDistinct++ }
                SeparationVerdict.OVERLAPPING_CLUSTER -> { overlapping++; if (rotating) rotatingOverlapping++ }
                SeparationVerdict.MODERATE_SEPARATION -> Unit
            }
        }
        return MonasPairCounts(rows.count { it.rotating }, distinct, overlapping, rotatingDistinct, rotatingOverlapping, evaluated)
    }
}

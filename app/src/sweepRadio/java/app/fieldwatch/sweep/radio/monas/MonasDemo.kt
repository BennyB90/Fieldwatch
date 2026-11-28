// SPDX-License-Identifier: MIT
package app.fieldwatch.sweep.radio.monas

object MonasDemo {
    const val TITLE = "SAMPLE · SYNTHETIC MONAS CENSUS"

    fun snapshot(): MonasSnapshot {
        fun row(id: Int, kind: String, ppm: Double, sigma: Double, h: Double) = MonasCensusDevice(
            "02:00:00:00:00:0$id", kind, "Sample beacon $id", "Example lab", 40L + id, 98.0, 23.0,
            MonasSigma(17.5, 0.4), MonasSigma(ppm, sigma), MonasSigma(h, 0.0006), MonasSigma(120.0, 1.2),
            firstSeenSec = 2, lastSeenSec = 35, maskedAddress = false,
        )
        val devices = listOf(
            row(1, "static", -10.84, 0.055, 0.4985), row(2, "rpa", -1.62, 0.015, 0.5015),
            row(3, "public", -0.72, 0.05, 0.4998), row(4, "nrpa", -0.69, 0.05, 0.4996),
            row(5, "rpa", -0.78, 0.05, 0.5001),
        )
        val t = MonasTestimony(null, false, false, null)
        val packets = devices.mapIndexed { index, d ->
            MonasBlePacket(5L - index, 37L + index % 3, d.address, true, 16.0 + index, d.crystalOffsetPpm, d.modulationIndex)
        }
        return MonasSnapshot(MonasHealth("Sample receiver", "NET", 20e6, true, false, 0),
            MonasProvenance(MonasReferenceTier.RELATIVE, null, false, "LOCK", 35), emptyList(),
            MonasCensus(t, devices, devices.size), MonasPacketPage(t, packets, packets.size), sample = true)
    }
}

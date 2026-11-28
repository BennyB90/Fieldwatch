// SPDX-License-Identifier: MIT
package app.fieldwatch.sweep.radio.monas

import app.fieldwatch.sweep.SweepPrivateFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.UUID

object MonasReports {
    const val LIMITS = "Counts describe observed address rows. A receiver can miss packets. " +
        "Concurrent separation supports negative exclusion, subject to thermal drift. " +
        "Overlap never proves shared identity. Rotating addresses are never linked. " +
        "These observations cannot establish a device owner, unwanted tracking or the absence of a transmitter."

    fun text(s: MonasSnapshot, includeIdentifiers: Boolean = false): String = buildString {
        val counts = MonasdAnalyzer.counts(s)
        appendLine(if (s.sample) MonasDemo.TITLE else "SWEEP · Monas SDR snapshot")
        appendLine("Observed address rows: ${s.census.devices.size} of ${s.census.total}")
        appendLine("Rotating address rows: ${counts.rotatingRows}")
        appendLine("Reference tier: ${s.provenance.tier}")
        appendLine("stale=${s.stale}, feed_loss=${s.feedLoss}, lost_samples=${s.health.lostSamples ?: "unknown"}")
        if (s.feedLoss) appendLine("Feed loss makes the observed counts lower bounds.")
        if (MonasdAnalyzer.unavailable(s) != null) appendLine("Pair comparison unavailable for this snapshot.")
        appendLine("Eligible pairs: ${counts.evaluated}")
        appendLine("≥5σ distinct-oscillator pairs: ${counts.distinct} (${counts.rotatingDistinct} rotating pairs)")
        appendLine("<2σ overlapping pairs: ${counts.overlapping} (${counts.rotatingOverlapping} rotating pairs)")
        val packets = s.ble.packets
        appendLine("Packet page: ${packets.size} of ${s.ble.total} returned")
        appendLine("CRC pass: ${packets.count { it.crcOk }}, fail: ${packets.count { !it.crcOk }}")
        val snr = packets.mapNotNull { it.snrDb }.average()
        appendLine("Mean packet SNR: " + if (snr.isFinite()) String.format(Locale.ROOT, "%.1f dB", snr) else "unavailable")
        appendLine(LIMITS)
        if (includeIdentifiers) {
            appendLine()
            appendLine("Address rows and measurements (±1σ)")
            for (row in s.census.devices) {
                appendLine(listOfNotNull(row.address, row.kind, row.name, row.company).joinToString(" · "))
                row.crystalOffsetPpm?.let { appendLine("  CFO ${it.value} ± ${it.sigma} ppm") }
                row.modulationIndex?.let { appendLine("  Modulation index ${it.value} ± ${it.sigma}") }
            }
        }
    }

    @Synchronized
    fun shareFile(cache: File, s: MonasSnapshot, includeIdentifiers: Boolean): File {
        val directory = File(cache, "export")
        SweepPrivateFiles.directory(directory)
        val entries = directory.listFiles() ?: throw IOException("Report cache unavailable.")
        val old = entries.filter { it.name.startsWith("Monas-") }.sortedByDescending { it.lastModified() }
        val cutoff = System.currentTimeMillis() - 86_400_000
        old.forEachIndexed { i, f -> if ((i >= 7 || f.lastModified() < cutoff) && !f.delete()) throw IOException("Report cleanup failed.") }
        val temp = SweepPrivateFiles.temporary(directory, "Monas-")
        val report = File(directory, "Monas-${UUID.randomUUID()}.txt")
        try {
            temp.writeText(text(s, includeIdentifiers), Charsets.UTF_8)
            Files.move(temp.toPath(), report.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.getFileAttributeView(report.toPath(), PosixFileAttributeView::class.java)
                ?.setPermissions(PosixFilePermissions.fromString("rw-------"))
            return report
        } catch (e: Exception) {
            temp.delete()
            report.delete()
            throw e
        }
    }
}

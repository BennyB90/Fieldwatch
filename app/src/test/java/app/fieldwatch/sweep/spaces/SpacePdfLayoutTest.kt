package app.fieldwatch.sweep.spaces

import org.junit.Assert.*
import org.junit.Test

class SpacePdfLayoutTest {
    private fun width(text: String, style: PdfLineStyle) = text.codePointCount(0, text.length) * style.size

    @Test fun longReportPaginatesWithinMarginsAndPreservesOrderAndDigest() {
        val items = (1..152).map { "Observation $it" }
        val report = SpaceReport(SpaceReportPayload(1, "Inspection",
            listOf(SpaceReportSection("Findings", items))), "a".repeat(64))
        val pages = SpacePdfLayout.pages(report, ::width)
        assertTrue(pages.size >= 4)
        pages.forEach { page ->
            assertTrue(page.isNotEmpty())
            assertTrue(page.first().baseline >= SpacePdfLayout.FIRST_LINE)
            assertTrue(page.last().baseline <= SpacePdfLayout.LAST_LINE)
            assertTrue(page.zipWithNext().all { (a, b) -> a.baseline < b.baseline })
            assertTrue(page.all { width(it.text, it.style) <= SpacePdfLayout.WIDTH - 2 * SpacePdfLayout.MARGIN })
            assertFalse(page.last().style == PdfLineStyle.HEADING)
        }
        assertEquals(items, pages.flatten().map { it.text }.filter { it.startsWith("Observation") })
        assertTrue(pages.last().joinToString("") { it.text }.endsWith(report.sha256))
    }

    @Test fun overflowingUnicodeTokenNeverSplitsSurrogatePairsAndWrapsAtSpaces() {
        val emoji = String(Character.toChars(0x1F4F7))
        val long = emoji.repeat(180)
        val report = SpaceReport(SpaceReportPayload(1, "Log", listOf(SpaceReportSection("H", listOf("room $long end")))), "abc")
        val body = SpacePdfLayout.pages(report, ::width).flatten().drop(1).takeWhile { it.style == PdfLineStyle.BODY }
        assertEquals("room${long}end", body.joinToString("") { it.text }.replace(" ", ""))
        assertTrue(body.none { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() })
    }

    @Test fun headingsMoveWithTheirFirstBodyLineAtPageBoundary() {
        val report = SpaceReport(SpaceReportPayload(1, "Log", listOf(
            SpaceReportSection("First", (1..41).map { "Line $it" }),
            SpaceReportSection("Second", listOf("Start here")))), "abc")
        val pages = SpacePdfLayout.pages(report, ::width)
        assertEquals("Second", pages[1].first().text)
        assertEquals("Start here", pages[1][1].text)
    }
}

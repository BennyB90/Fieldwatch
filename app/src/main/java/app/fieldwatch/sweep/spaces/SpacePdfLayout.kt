package app.fieldwatch.sweep.spaces

internal enum class PdfLineStyle(val size: Float, val advance: Int) {
    HEADING(14f, 21), BODY(10.5f, 16)
}

internal data class SpacePdfLine(val text: String, val style: PdfLineStyle, val baseline: Int)

/** All coordinates are PDF points. Font measurement is supplied by the Android renderer. */
internal object SpacePdfLayout {
    const val WIDTH = 595
    const val HEIGHT = 842
    const val MARGIN = 44
    const val FIRST_LINE = 100
    const val LAST_LINE = 780

    fun pages(report: SpaceReport, measure: (String, PdfLineStyle) -> Float): List<List<SpacePdfLine>> {
        val pages = mutableListOf<List<SpacePdfLine>>()
        var lines = mutableListOf<SpacePdfLine>()
        var y = FIRST_LINE

        fun nextPage() {
            if (lines.isEmpty()) return
            pages.add(lines)
            lines = mutableListOf()
            y = FIRST_LINE
        }

        fun draw(text: String, style: PdfLineStyle) {
            if (y > LAST_LINE) nextPage()
            lines.add(SpacePdfLine(text, style, y))
            y += style.advance
        }

        val digest = SpaceReportSection("Integrity digest", listOf("SHA-256 of the canonical JSON payload (UTF-8)", report.sha256))
        (report.payload.sections + digest).forEach { section ->
            if (lines.isNotEmpty()) y += 10
            val heading = wrap(section.heading, PdfLineStyle.HEADING, measure)
            if (y + heading.size * PdfLineStyle.HEADING.advance > LAST_LINE) nextPage()
            heading.forEach { draw(it, PdfLineStyle.HEADING) }
            section.lines.forEach { line -> wrap(line, PdfLineStyle.BODY, measure).forEach { draw(it, PdfLineStyle.BODY) } }
        }
        nextPage()
        return pages
    }

    private fun wrap(text: String, style: PdfLineStyle, measure: (String, PdfLineStyle) -> Float): List<String> {
        val width = WIDTH - 2 * MARGIN
        val lines = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (measure(remaining, style) <= width) {
                lines.add(remaining)
                break
            }
            // Cut at a code point boundary even for a long unbroken SSID or note.
            var end = 0
            var lastSpace = -1
            while (end < remaining.length) {
                val next = remaining.offsetByCodePoints(end, 1)
                if (end > 0 && measure(remaining.substring(0, next), style) > width) break
                if (remaining[end].isWhitespace()) lastSpace = end
                end = next
            }
            val cut = if (lastSpace > 0) lastSpace else end
            lines.add(remaining.substring(0, cut).trimEnd())
            remaining = remaining.substring(cut).trimStart()
        }
        return lines
    }
}

package app.fieldwatch.sweep.spaces

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.fieldwatch.sweep.SweepPrivateFiles
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal enum class SpaceExportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"), TEXT("txt", "text/plain"), JSON("json", "application/json")
}

/** Called exclusively on the Spaces queue, including expiry, deletion and export. */
internal class SpacePdfExporter(private val directory: File) {
    fun export(spaceId: String, report: SpaceReport, format: SpaceExportFormat): File {
        require(spaceId.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        SweepPrivateFiles.directory(directory)
        prune(reserve = 1)
        val temp = SweepPrivateFiles.temporary(directory, TEMP)
        val target = File(directory, "$PREFIX$spaceId.${UUID.randomUUID()}.${format.extension}")
        try {
            FileOutputStream(temp).use { output ->
                when (format) {
                    SpaceExportFormat.PDF -> render(report, output)
                    SpaceExportFormat.TEXT -> output.write(report.text().toByteArray(Charsets.UTF_8))
                    SpaceExportFormat.JSON -> output.write(report.json().toByteArray(Charsets.UTF_8))
                }
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
        return target
    }

    fun prune(reserve: Int = 0) {
        val files = ownedFiles()
        val expired = System.currentTimeMillis() - 24 * 60 * 60_000L
        val (remove, live) = files.partition { it.name.startsWith(TEMP) || it.lastModified() < expired }
        (remove + live.sortedByDescending { it.lastModified() }.drop(8 - reserve)).forEach { Files.deleteIfExists(it.toPath()) }
    }

    fun clear(spaceId: String? = null) {
        ownedFiles().filter { spaceId == null || it.name.startsWith("$PREFIX$spaceId.") || it.name.startsWith(TEMP) }
            .forEach { Files.deleteIfExists(it.toPath()) }
    }

    private fun ownedFiles(): List<File> {
        if (Files.notExists(directory.toPath())) return emptyList()
        return directory.listFiles()?.filter { it.name.startsWith(PREFIX) || it.name.startsWith(TEMP) }
            ?: throw IOException("Cannot access inspection shares.")
    }

    private fun render(report: SpaceReport, output: FileOutputStream) {
        val ink = Color.rgb(24, 59, 57)
        val teal = Color.rgb(8, 109, 100)
        fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val paints = PdfLineStyle.entries.associateWith {
            paint(it.size, if (it == PdfLineStyle.HEADING) teal else ink, it == PdfLineStyle.HEADING)
        }
        val pages = SpacePdfLayout.pages(report) { text, style -> paints.getValue(style).measureText(text) }
        val left = SpacePdfLayout.MARGIN.toFloat()
        val right = SpacePdfLayout.WIDTH - left
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, lines ->
                val info = PdfDocument.PageInfo.Builder(SpacePdfLayout.WIDTH, SpacePdfLayout.HEIGHT, index + 1).create()
                val page = document.startPage(info)
                try {
                    val canvas = page.canvas
                    canvas.drawText("SWEEP", left, 34f, paint(10f, teal, true))
                    canvas.drawText(report.payload.title, left, 61f, paint(21f, ink, true))
                    val rule = paint(1f, Color.rgb(195, 210, 202))
                    canvas.drawLine(left, 75f, right, 75f, rule)
                    lines.forEach { canvas.drawText(it.text, left, it.baseline.toFloat(), paints.getValue(it.style)) }
                    canvas.drawLine(left, 797f, right, 797f, rule)
                    val footer = paint(9f, ink)
                    canvas.drawText("SWEEP  /  Inspection log", left, 817f, footer)
                    footer.textAlign = Paint.Align.RIGHT
                    canvas.drawText("${index + 1} / ${pages.size}", right, 817f, footer)
                } finally {
                    document.finishPage(page)
                }
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private companion object {
        const val PREFIX = "inspection-"
        const val TEMP = ".inspection-"
    }
}

package app.fieldwatch.sweep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fieldwatch.domain.Rssi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SweepSignalChart(finding: SweepFinding, sample: Boolean = false) {
    val points = finding.signalSamples.filter { Rssi.measured(it.rssi) }.sortedBy { it.observedAt }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(if (sample) "Sample signal history" else "Measured signal history", style = MaterialTheme.typography.titleMedium)
        if (points.isEmpty()) {
            Text("No signal history was recorded. Older reports and Wi-Fi NAN observations may have no measured signal strength.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
            return@Column
        }
        val low = points.minOf { it.rssi }
        val high = points.maxOf { it.rssi }
        Text("${points.size} recorded measurements · $low to $high dBm", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
        val first = points.first().observedAt
        val last = points.last().observedAt
        val span = (last - first).coerceAtLeast(1).toDouble()
        val floor = low - 3
        val ceiling = high + 3
        val ink = SweepTeal
        val grid = SweepLine
        Canvas(Modifier.fillMaxWidth().height(150.dp).semantics {
            contentDescription = "Received signal strength over time: ${points.size} samples, $low to $high dBm. No distance estimate."
        }) {
            val pad = 8.dp.toPx()
            val width = (size.width - 2 * pad).coerceAtLeast(1f)
            val height = (size.height - 2 * pad).coerceAtLeast(1f)
            repeat(3) { i ->
                val y = pad + height * i / 2
                drawLine(grid, Offset(pad, y), Offset(pad + width, y), 1.dp.toPx())
            }
            fun position(row: SweepSignalSample) = Offset(
                pad + (((row.observedAt - first) / span) * width).toFloat(),
                pad + height * (ceiling - row.rssi).toFloat() / (ceiling - floor),
            )
            val path = Path()
            points.forEachIndexed { i, point ->
                val p = position(point)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            points.forEach { drawCircle(ink, 2.5.dp.toPx(), position(it)) }
        }
        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock.format(Date(first)), style = MaterialTheme.typography.labelSmall)
            Text(clock.format(Date(last)), style = MaterialTheme.typography.labelSmall)
        }
        Text("Horizontal axis: observation time. Vertical axis: received strength (dBm). Stronger readings may help investigation; walls, orientation and interference affect them. This is not distance or direction.", style = MaterialTheme.typography.bodySmall, color = SweepMuted)
    }
}

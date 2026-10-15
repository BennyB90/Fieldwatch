package app.fieldwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** First on-demand shell for the rig. The inherited Fieldwatch UI remains the instrument view. */
@Composable
fun SweepSpikeShell(active: Boolean, onStart: () -> Unit, onStop: () -> Unit, instrument: @Composable () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (active) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("SWEEP Spike", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = onStop) { Text("End session") }
                    }
                    Box(modifier = Modifier.weight(1f)) { instrument() }
                } else {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("SWEEP detection spike", style = MaterialTheme.typography.headlineSmall)
                        Text("Start a session to observe nearby Wi-Fi access points and Bluetooth LE advertisements.")
                        Text("A signature match is an indicator to investigate. This test build has not been validated against the hardware rig.")
                        Button(onClick = onStart) { Text("Start scan session") }
                        Text("Built on Fieldwatch by Off Grid Pete LLC · MIT", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

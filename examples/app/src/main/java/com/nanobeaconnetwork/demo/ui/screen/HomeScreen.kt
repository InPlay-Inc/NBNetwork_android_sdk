package com.nanobeaconnetwork.demo.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nanobeaconnetwork.demo.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(navController: NavController, vm: HomeViewModel = viewModel()) {
    val scanState by vm.scanState.collectAsState()
    val stats by vm.reportStats.collectAsState()
    val logs by vm.scanLogs.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Status bar
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot("BLE", scanState.bleEnabled)
            StatusDot("GPS", scanState.gpsEnabled)
            StatusDot("Perms", scanState.hasPermissions)
            if (stats.rateLimited) {
                Surface(color = MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.small) {
                    Text("Rate Limited", modifier = Modifier.padding(4.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Scan toggle
        Button(
            onClick = { vm.toggleScan() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (scanState.isScanning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (scanState.isScanning) "Stop Scan" else "Start Scan")
        }

        Spacer(Modifier.height(12.dp))

        // Stats card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today's Stats", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Scanned: ${stats.todayScanCount}")
                    Text("Reported: ${stats.todayReportCount}")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pending: ${stats.pendingCount}")
                    Text("Success: ${"%.0f".format(stats.successRate * 100)}%")
                }
                if (stats.droppedCount > 0) {
                    // Uploads reached the server but it silently dropped these (unknown/forged EID).
                    Text("Dropped by server: ${stats.droppedCount}",
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("Scan Log", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(logs) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(entry.time, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(entry.eidPrefix, style = MaterialTheme.typography.bodySmall)
                    Text("rssi:${entry.rssi}", style = MaterialTheme.typography.bodySmall)
                    Text(entry.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entry.status) {
                            "Reported" -> Color(0xFF2E7D32)
                            "Queued" -> Color(0xFFE65100) // amber: enqueued, not yet server-confirmed
                            "Duplicate" -> Color.Gray
                            else -> MaterialTheme.colorScheme.error
                        })
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun StatusDot(label: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (active) Color(0xFF2E7D32) else Color(0xFFB71C1C),
            modifier = Modifier.size(8.dp),
        ) {}
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

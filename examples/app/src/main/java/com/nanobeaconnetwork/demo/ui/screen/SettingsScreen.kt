package com.nanobeaconnetwork.demo.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nanobeaconnetwork.NbnClient

@Composable
fun SettingsScreen(navController: NavController) {
    val reportStats by NbnClient.reportStats.collectAsState()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // Status
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Status", style = MaterialTheme.typography.titleSmall)
                Text("Queue: ${reportStats.pendingCount}")
                Text("Rate Limited: ${reportStats.rateLimited}")
                Text("NanoBeaconNetwork v0.1.0")
            }
        }
    }
}

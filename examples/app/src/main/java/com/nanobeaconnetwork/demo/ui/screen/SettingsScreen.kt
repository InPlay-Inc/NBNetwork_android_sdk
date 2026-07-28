package com.nanobeaconnetwork.demo.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nanobeaconnetwork.NbnSdk
import com.nanobeaconnetwork.demo.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(navController: NavController, vm: SettingsViewModel = viewModel()) {
    val settings by vm.state.collectAsState()
    val reportStats by NbnSdk.reportStats.collectAsState()

    var serverUrlDraft by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var serverUrlWasFocused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // Configuration — server URL only
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Configuration", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = serverUrlDraft,
                    onValueChange = { serverUrlDraft = it },
                    label = { Text("Server URL") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { fs ->
                        if (serverUrlWasFocused && !fs.isFocused && serverUrlDraft != settings.serverUrl) {
                            vm.saveServerUrl(serverUrlDraft) // Lost focus with changes -> auto-save
                        }
                        serverUrlWasFocused = fs.isFocused
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.saveServerUrl(serverUrlDraft) }),
                )
                Button(
                    onClick = { vm.saveServerUrl(serverUrlDraft) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save Server URL") }
            }
        }

        // Status
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Status", style = MaterialTheme.typography.titleSmall)
                Text("Queue: ${reportStats.pendingCount}")
                Text("Rate Limited: ${reportStats.rateLimited}")
                Text("SDK v1.0.0")
            }
        }
    }
}

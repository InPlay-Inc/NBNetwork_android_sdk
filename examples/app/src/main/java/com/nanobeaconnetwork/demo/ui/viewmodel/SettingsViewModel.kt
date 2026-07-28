package com.nanobeaconnetwork.demo.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.nanobeaconnetwork.NbnConfig
import com.nanobeaconnetwork.NbnClient
import com.nanobeaconnetwork.demo.DemoApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SettingsState(
    val serverUrl: String = DemoApp.DEFAULT_SERVER_URL,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs: SharedPreferences = (app as DemoApp).appPrefs

    private val _state = MutableStateFlow(
        SettingsState(
            serverUrl = prefs.getString("server_url", DemoApp.DEFAULT_SERVER_URL) ?: DemoApp.DEFAULT_SERVER_URL,
        )
    )
    val state: StateFlow<SettingsState> = _state

    fun saveServerUrl(url: String) {
        prefs.edit().putString("server_url", url).apply()
        _state.value = _state.value.copy(serverUrl = url)
        NbnClient.updateConfig(NbnConfig.Builder().serverUrl(url).build())
    }
}

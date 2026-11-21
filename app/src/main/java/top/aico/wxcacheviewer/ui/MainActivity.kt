package top.aico.wxcacheviewer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.aico.wxcacheviewer.data.log.AppLog
import top.aico.wxcacheviewer.data.repository.SettingsRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        AppLog.log("app", "MainActivity.onCreate")
        val settings = SettingsRepository(this)
        setContent {
            MaterialTheme {
                var selectedAccountHash by remember { mutableStateOf(settings.selectedAccountHash) }
                AppRoot(
                    selectedAccountHash = selectedAccountHash,
                    onSelectedAccountChanged = {
                        selectedAccountHash = it
                        settings.selectedAccountHash = it
                    }
                )
            }
        }
    }
}

package com.remoteunlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.remoteunlock.ui.RemoteUnlockNavHost
import com.remoteunlock.ui.theme.RemoteUnlockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteUnlockTheme {
                RemoteUnlockNavHost()
            }
        }
    }
}

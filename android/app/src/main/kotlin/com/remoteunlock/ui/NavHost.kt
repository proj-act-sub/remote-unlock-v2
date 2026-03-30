package com.remoteunlock.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remoteunlock.ui.screens.DevicesScreen
import com.remoteunlock.ui.screens.PairScreen

@Composable
fun RemoteUnlockNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "devices") {
        composable("devices") {
            DevicesScreen(onPairNew = { navController.navigate("pair") })
        }
        composable("pair") {
            PairScreen(onDone = { navController.popBackStack() })
        }
    }
}

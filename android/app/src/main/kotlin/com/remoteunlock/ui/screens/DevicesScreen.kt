package com.remoteunlock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remoteunlock.data.Peer
import com.remoteunlock.tunnel.TunnelClient
import com.remoteunlock.biometric.BiometricHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onPairNew: () -> Unit,
    vm: DevicesViewModel = viewModel()
) {
    val peers by vm.peers.collectAsState()
    val unlockState by vm.unlockState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("RemoteUnlock") })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPairNew,
                icon    = { Icon(Icons.Filled.Add, contentDescription = null) },
                text    = { Text("Pair Phone") }
            )
        }
    ) { innerPadding ->
        if (peers.isEmpty()) {
            EmptyState(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                contentPadding = innerPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                items(peers) { peer ->
                    PeerCard(
                        peer = peer,
                        unlockState = unlockState[peer.id],
                        onUnlock = {
                            BiometricHelper.authenticate(
                                context = context,
                                title   = "Unlock ${peer.name}",
                                onSuccess = { vm.unlock(peer) },
                                onError   = { /* show toast */ }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: Peer,
    unlockState: UnlockState?,
    onUnlock: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(peer.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        peer.fingerprintShort,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (unlockState) {
                UnlockState.Loading -> CircularProgressIndicator(modifier = Modifier.size(36.dp))
                UnlockState.Success -> Icon(
                    imageVector = Icons.Filled.PhoneAndroid, // replace with check icon
                    contentDescription = "Unlocked",
                    tint = MaterialTheme.colorScheme.primary
                )
                else -> Button(onClick = onUnlock) { Text("Unlock") }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.PhoneAndroid,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))
        Text("No devices paired", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tap + to pair your Android phone",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

enum class UnlockState { Loading, Success, Error }

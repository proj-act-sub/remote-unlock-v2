package com.remoteunlock.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remoteunlock.data.Peer
import com.remoteunlock.data.PeerStore
import com.remoteunlock.tunnel.TunnelClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DevicesViewModel(app: Application) : AndroidViewModel(app) {
    private val peerStore = PeerStore(app)

    val peers: StateFlow<List<Peer>> = peerStore.peersFlow

    private val _unlockState = MutableStateFlow<Map<String, UnlockState>>(emptyMap())
    val unlockState: StateFlow<Map<String, UnlockState>> = _unlockState.asStateFlow()

    fun unlock(peer: Peer) {
        viewModelScope.launch {
            _unlockState.value = _unlockState.value + (peer.id to UnlockState.Loading)
            try {
                val client = TunnelClient(peer)
                val ok = client.sendUnlockCommand()
                _unlockState.value = _unlockState.value + (peer.id to
                    if (ok) UnlockState.Success else UnlockState.Error)
            } catch (e: Exception) {
                _unlockState.value = _unlockState.value + (peer.id to UnlockState.Error)
            }
        }
    }
}

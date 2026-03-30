package com.remoteunlock.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remoteunlock.data.Peer
import com.remoteunlock.data.PeerStore
import com.remoteunlock.qr.QrPayload
import com.remoteunlock.tunnel.TunnelClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

sealed class PairingState {
    object Idle : PairingState()
    object Scanning : PairingState()
    object Connecting : PairingState()
    data class Success(val peerName: String) : PairingState()
    data class Error(val message: String) : PairingState()
}

class PairViewModel(app: Application) : AndroidViewModel(app) {
    private val peerStore = PeerStore(app)
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state

    fun onQrScanned(json: String) {
        if (_state.value is PairingState.Connecting) return
        _state.value = PairingState.Connecting
        viewModelScope.launch {
            try {
                val payload = Json.decodeFromString<QrPayload>(json)
                if (payload.v != 1) throw Exception("Unsupported QR version")

                val client = TunnelClient.fromQrPayload(payload)
                val peer = client.pair()

                peerStore.addPeer(peer)
                _state.value = PairingState.Success(peer.name)
            } catch (e: Exception) {
                _state.value = PairingState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() { _state.value = PairingState.Idle }
}

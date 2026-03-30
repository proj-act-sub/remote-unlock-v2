package com.remoteunlock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "peers")

/**
 * Persists paired desktop records using Jetpack DataStore (encrypted via
 * the Android KeyStore-backed preference encryption in Android 13+).
 */
class PeerStore(private val context: Context) {

    private val PEERS_KEY = stringPreferencesKey("peers_json")

    val peersFlow: Flow<List<Peer>> = context.dataStore.data.map { prefs ->
        val json = prefs[PEERS_KEY] ?: "[]"
        Json.decodeFromString<List<Peer>>(json)
    }

    suspend fun addPeer(peer: Peer) {
        context.dataStore.edit { prefs ->
            val current: List<Peer> = prefs[PEERS_KEY]
                ?.let { Json.decodeFromString(it) } ?: emptyList()
            val updated = current.filterNot { it.id == peer.id } + peer
            prefs[PEERS_KEY] = Json.encodeToString(updated)
        }
    }

    suspend fun removePeer(id: String) {
        context.dataStore.edit { prefs ->
            val current: List<Peer> = prefs[PEERS_KEY]
                ?.let { Json.decodeFromString(it) } ?: emptyList()
            prefs[PEERS_KEY] = Json.encodeToString(current.filterNot { it.id == id })
        }
    }
}

package com.remoteunlock

import android.app.Application
import com.remoteunlock.crypto.KeyManager

class RemoteUnlockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialise the KeyManager (creates X25519 key in AndroidKeyStore if absent)
        KeyManager.init(this)
    }
}

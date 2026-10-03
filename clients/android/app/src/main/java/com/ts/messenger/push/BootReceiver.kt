package com.ts.messenger.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ts.messenger.security.SecureStore

/** Restarts the background connection after a reboot, if the user is signed in and has it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = SecureStore(context)
        if (store.getString("bg.enabled") == "0" || store.getString("auth.refresh") == null) return
        ListenService.start(context)
    }
}

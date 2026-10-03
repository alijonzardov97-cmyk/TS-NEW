package com.ts.messenger

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.ts.messenger.security.AppLock
import com.ts.messenger.ui.AppContent
import com.ts.messenger.ui.LockScreen
import com.ts.messenger.ui.TsTheme

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()
    private var unlockFailed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // No screenshots, no screen recording, and a blank thumbnail in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        // Ignore taps while another window overlays this one (tapjacking protection).
        window.decorView.filterTouchesWhenObscured = true

        setContent {
            TsTheme {
                val state by vm.state.collectAsState()
                val lockAvailable = remember { AppLock.isAvailable(this) }
                if (state.unlocked) {
                    AppContent(state, vm)
                } else {
                    LockScreen(noLockSet = !lockAvailable, failed = unlockFailed, onUnlock = ::promptUnlock)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!vm.state.value.unlocked && AppLock.isAvailable(this)) promptUnlock()
    }

    /** Re-lock whenever the app leaves the foreground. */
    override fun onStop() {
        super.onStop()
        vm.lock()
    }

    private fun promptUnlock() {
        unlockFailed = false
        AppLock.prompt(
            activity = this,
            title = getString(R.string.unlock_title),
            subtitle = getString(R.string.unlock_subtitle),
            onSuccess = { vm.onUnlocked() },
            onFailure = { unlockFailed = true },
        )
    }
}

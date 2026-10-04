package com.ts.messenger

import android.os.Bundle
import android.view.WindowManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    // Android 13+ asks for notification permission at runtime. Either answer continues: without
    // the permission the push is registered but nothing is displayed.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.enablePush() }

    // Pickers live in the activity: the app locks itself while the system picker is in front, and
    // a result must still arrive after the unlock.
    private val pickAvatar =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.uploadAvatar(uri) }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.sendFile(uri) }

    private var fileToSave: com.ts.messenger.net.FileRef? = null
    private val saveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val ref = fileToSave
            fileToSave = null
            if (uri != null && ref != null) vm.saveFile(ref, uri)
        }

    private fun saveFile(ref: com.ts.messenger.net.FileRef) {
        fileToSave = ref
        saveFile.launch(ref.name)
    }

    private var afterMic: (() -> Unit)? = null
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterMic
            afterMic = null
            if (granted) action?.invoke()
        }

    /** Runs [action] once the microphone permission is available. */
    private fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterMic = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private var afterCamera: (() -> Unit)? = null
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterCamera
            afterCamera = null
            if (granted) action?.invoke()
        }

    private fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterCamera = action
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val id = Regex("[0-9a-fA-F-]{36}")
        intent.getStringExtra(com.ts.messenger.push.Notifications.EXTRA_CHAT_CHANNEL)?.let { ch ->
            intent.removeExtra(com.ts.messenger.push.Notifications.EXTRA_CHAT_CHANNEL)
            if (ch.matches(id)) vm.openChatFromNotification(ch)
        }
        intent.getStringExtra(com.ts.messenger.push.Notifications.EXTRA_CALL_CHANNEL)?.let { ch ->
            intent.removeExtra(com.ts.messenger.push.Notifications.EXTRA_CALL_CHANNEL)
            if (ch.matches(id)) vm.callFromNotification(ch)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun enablePush() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.enablePush()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No screenshots, no screen recording, and a blank thumbnail in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        // Ignore taps while another window overlays this one (tapjacking protection).
        window.decorView.filterTouchesWhenObscured = true
        handleIntent(intent)

        setContent {
            TsTheme {
                val state by vm.state.collectAsState()
                val lockAvailable = remember { AppLock.isAvailable(this) }
                androidx.compose.animation.Crossfade(
                    targetState = state.unlocked,
                    animationSpec = androidx.compose.animation.core.tween(380),
                    label = "unlock",
                ) { open ->
                    if (open) {
                        AppContent(state, vm, ::enablePush, { pickFile.launch("*/*") }, ::saveFile, ::withMic, ::withCamera, { pickAvatar.launch("image/*") })
                    } else {
                        LockScreen(noLockSet = !lockAvailable, failed = unlockFailed, onUnlock = ::promptUnlock)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.ts.messenger.push.Notifications.appVisible = true
        if (!vm.state.value.unlocked && AppLock.isAvailable(this)) promptUnlock()
    }

    /** Re-lock whenever the app leaves the foreground. */
    override fun onStop() {
        super.onStop()
        com.ts.messenger.push.Notifications.appVisible = false
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

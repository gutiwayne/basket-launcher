package app.olauncher.helper

import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import app.olauncher.R

class PinItemActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pin requests only exist since Android 8.0; the system never starts this activity earlier.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            finish()
            return
        }
        
        window.setBackgroundDrawable(null)

        val launcherApps = getSystemService(LauncherApps::class.java)
        val pinItemRequest = launcherApps.getPinItemRequest(intent)

        when (pinItemRequest != null) {
            true -> handleRequestType(pinItemRequest)
            false -> showToast(R.string.invalid_pin_request)
        }

        finish()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleRequestType(pinItemRequest: LauncherApps.PinItemRequest) {
        when (pinItemRequest.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT ->
                handleShortcutRequest(pinItemRequest)

            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET ->
                showToast(R.string.widgets_not_supported)

            else -> showToast(R.string.pin_action_not_supported)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleShortcutRequest(pinItemRequest: LauncherApps.PinItemRequest) {
        val shortcutInfo = pinItemRequest.shortcutInfo
        if (shortcutInfo != null) {
            val success = runCatching { pinItemRequest.accept() }.getOrDefault(false)
            val message = when (success) {
                true -> R.string.shortcut_pinned
                false -> R.string.shortcut_pin_failed
            }
            showToast(message)
        } else {
            showToast(R.string.invalid_shortcut)
        }
    }
}

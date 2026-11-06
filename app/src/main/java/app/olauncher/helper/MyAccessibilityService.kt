package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import app.olauncher.R
import app.olauncher.data.Prefs

class MyAccessibilityService : AccessibilityService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        Prefs(applicationContext).lockModeOn = true
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            // The click event already carries the clicked view's class and content description,
            // so the service does not need to read window content (canRetrieveWindowContent=false).
            if (event.className != "android.widget.FrameLayout") return

            when (event.contentDescription) {
                getString(R.string.lock_layout_description) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                // Home button for recents feature disabled
                // getString(R.string.recents_layout_description) -> {
                //     performGlobalAction(GLOBAL_ACTION_RECENTS)
                // }
            }
        } catch (e: Exception) {
            return
        }
    }

    override fun onInterrupt() {

    }
}
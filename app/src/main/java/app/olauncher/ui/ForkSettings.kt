package app.olauncher.ui

import androidx.core.view.isVisible
import app.olauncher.databinding.FragmentSettingsBinding

/** Basket: hide upstream promotions and links that only make sense for Olauncher on the Play Store. */
fun FragmentSettingsBinding.hideUpstreamPromos() {
    moreFeatures.isVisible = false // Olauncher Pro ad
    rate.isVisible = false         // Play Store rating
    twitter.isVisible = false      // upstream author's account
    footer.isVisible = false       // upstream author's other apps
    // Side swipes on the home screen switch app lists, so the per-swipe app settings do nothing.
    (swipeLeftApp.parent as? android.view.View)?.isVisible = false
    (swipeRightApp.parent as? android.view.View)?.isVisible = false
}

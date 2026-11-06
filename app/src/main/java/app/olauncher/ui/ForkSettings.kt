package app.olauncher.ui

import androidx.core.view.isVisible
import app.olauncher.databinding.FragmentSettingsBinding

/** Cesta: hide upstream promotions and links that only make sense for Olauncher on the Play Store. */
fun FragmentSettingsBinding.hideUpstreamPromos() {
    moreFeatures.isVisible = false // Olauncher Pro ad
    rate.isVisible = false         // Play Store rating
    twitter.isVisible = false      // upstream author's account
    footer.isVisible = false       // upstream author's other apps
}

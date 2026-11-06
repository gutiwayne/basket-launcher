package app.olauncher.data

// Cesta Launcher: fork-specific switches kept apart from upstream code.
object Fork {
    /** Upstream prompts that point to the Olauncher Play Store listing or promote other apps. */
    val DISABLED_DIALOGS = setOf(
        Constants.Dialog.REVIEW,
        Constants.Dialog.RATE,
        Constants.Dialog.SHARE,
        Constants.Dialog.PRO_MESSAGE,
    )
}

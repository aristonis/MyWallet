package org.aristonis.mywallet.ui.theme

import android.app.UiModeManager
import org.aristonis.mywallet.domain.model.ThemePreference

/** Retained with the app ViewModel so configuration recreation does not repeat a mode change. */
internal class SplashNightMode {
    private var lastAppliedMode: Int? = null

    fun apply(theme: ThemePreference, sdkInt: Int, setMode: (Int) -> Unit) {
        if (sdkInt < APPLICATION_NIGHT_MODE_API) return
        val mode = when (theme) {
            ThemePreference.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemePreference.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemePreference.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        if (mode == lastAppliedMode) return
        setMode(mode)
        lastAppliedMode = mode
    }

    private companion object {
        const val APPLICATION_NIGHT_MODE_API = 31
    }
}

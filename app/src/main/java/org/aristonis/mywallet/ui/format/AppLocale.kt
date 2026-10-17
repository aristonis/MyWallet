package org.aristonis.mywallet.ui.format

import java.util.Locale

/** Keeps the selected resource language and region while displaying Latin digits. */
internal object AppLocale {
    fun from(incoming: Locale): Locale = Locale.Builder()
        .setLocale(incoming)
        .setUnicodeLocaleKeyword("nu", "latn")
        .build()

    fun current(): Locale = from(Locale.getDefault())
}

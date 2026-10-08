package org.aristonis.mywallet.ui.window

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Runs [onStart] every time the screen comes into view: on first show, on a return from another tab,
 * and on a return from the background.
 *
 * A date window is only as current as the last time it looked at the clock. An app left open past
 * midnight, or resumed days later, would otherwise keep showing yesterday's "today", so each screen
 * that shows dates calls through here to catch up whenever it is seen again.
 */
@Composable
fun RefreshOnStart(onStart: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_START, onEvent = onStart)
}

package org.aristonis.mywallet.ui.window

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The date windows catch up with today when their screen comes back into view. That depends on this
 * one hook firing on every start, so it is pinned here rather than trusted inside each screen.
 */
@RunWith(AndroidJUnit4::class)
class RefreshOnStartTest {

    @get:Rule val compose = createComposeRule()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun firesOnEveryStart() {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.CREATED }
        var starts = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RefreshOnStart { starts++ }
            }
        }
        compose.runOnIdle { assertEquals(0, starts) }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(1, starts) }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(2, starts) }
    }
}

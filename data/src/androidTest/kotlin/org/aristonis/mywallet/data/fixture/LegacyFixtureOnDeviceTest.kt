package org.aristonis.mywallet.data.fixture

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The upgrade test runs on a device, so the shared fixture has to be compiled into the device tests too. */
@RunWith(AndroidJUnit4::class)
class LegacyFixtureOnDeviceTest {
    @Test
    fun theFixtureIsOnTheInstrumentedClasspath() {
        assertTrue(LegacyFixture.cases.isNotEmpty())
    }
}

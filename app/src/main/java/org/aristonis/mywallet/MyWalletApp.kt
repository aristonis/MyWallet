package org.aristonis.mywallet

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Entry point for Hilt's dependency-injection container. Registered as `android:name` in the manifest. */
@HiltAndroidApp
class MyWalletApp : Application()

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Adapters layer — Room + repo implementations land here from SG-4. Depends only on :domain.
android {
    namespace = "org.aristonis.mywallet.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    api(libs.androidx.room.runtime) // exposed so :app can reference WalletDatabase (its RoomDatabase supertype)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: real in-memory Room exercising the backup/restore transaction on-device.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // The test-runner graph transitively pins a few artifacts whose exact versions aren't in the
    // offline cache (only newer ones are). Constrain those already-present transitives to the
    // resolvable versions so the instrumented test APK links without network access.
    constraints {
        androidTestImplementation("androidx.tracing:tracing:1.2.0") {
            because("only tracing 1.2.0 is available in the offline cache; the default 1.1.0 is not")
        }
        androidTestImplementation("androidx.lifecycle:lifecycle-common") {
            version { strictly("2.6.2") }
            because("lifecycle-common 2.6.2 is the resolvable jar in the offline cache; the default strict 2.3.1 is not")
        }
    }
}

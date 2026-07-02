plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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

    api(libs.androidx.room.runtime) // exposed so :app can reference WalletDatabase (its RoomDatabase supertype)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

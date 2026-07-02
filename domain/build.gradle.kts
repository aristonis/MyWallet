plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-Kotlin domain core — NO Android, Room, or Compose here (hexagonal core, NFR-8).
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    jacoco
}

// Adapters layer: Room and the repository implementations. Depends only on :domain.
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

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }

    // Pin AGP's coverage agent to the version already available; no extra fetch.
    testCoverage {
        jacocoVersion = "0.8.14"
    }
}

kotlin {
    jvmToolchain(17)
}

// Room writes one JSON file per schema version here, and that file is the only durable record of
// what a given version's tables actually were. Without it a later migration has nothing to be
// written against and nothing to be tested against, because the old shape exists only in whatever
// databases are already installed on people's phones. The directory is committed for that reason.
ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path)
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

// Coverage: the JaCoCo runtime is a resolved dependency (not bundled with Gradle) — it needs one
// online run to land in the Gradle cache, after which coverage reports run fully offline.
jacoco {
    toolVersion = "0.8.14"
}

// Generated / non-logic code kept out of the coverage denominator so the number is real logic.
val coverageExclusions = listOf(
    "**/*_Factory*",
    "**/*_MembersInjector*",
    "**/*_HiltModules*",
    "**/Hilt_*",
    "**/*_GeneratedInjector*",
    "**/*_Impl*",
    "**/dagger/**",
    "dagger/**",
    "**/hilt_aggregated_deps/**",
    "hilt_aggregated_deps/**",
    "**/ComposableSingletons*",
    "**/*ComposableSingletons*",
    "**/*Kt\$*",
    "**/R.class",
    "**/R\$*.class",
    "**/BuildConfig.*",
    "**/di/**",
)

tasks.register<JacocoReport>("jacocoDebugReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates JaCoCo coverage for the debug unit tests."
    reports {
        html.required.set(true)
        xml.required.set(true)
    }

    val buildDirFile = layout.buildDirectory.get().asFile
    classDirectories.setFrom(
        fileTree(buildDirFile.resolve("tmp/kotlin-classes/debug")) {
            exclude(coverageExclusions)
        }
    )
    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
    executionData.setFrom(
        fileTree(buildDirFile) {
            include(
                "outputs/unit_test_code_coverage/**/*.exec",
                "jacoco/*.exec",
            )
        }
    )
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    jacoco
}

android {
    namespace = "org.aristonis.mywallet"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.aristonis.mywallet"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // The About screen shows the version name, which lives on the generated BuildConfig.
        buildConfig = true
    }

    // Pin AGP's coverage agent to the version already available; no extra fetch.
    testCoverage {
        jacocoVersion = "0.8.14"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.material)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
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

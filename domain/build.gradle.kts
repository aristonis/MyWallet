plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

// Pure-Kotlin domain core. No Android, Room or Compose here: keeping it plain JVM is what lets
// the business rules be tested without a device.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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

// The jacoco plugin auto-creates jacocoTestReport bound to `test`; refine it here.
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { dir ->
            fileTree(dir) { exclude(coverageExclusions) }
        })
    )
}

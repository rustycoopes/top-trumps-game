plugins {
    id("toptrumps.android-library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.toptrumps.feature.history"

    // Room's queries need a real SQLite, which means a real Context — Robolectric gives the DAO
    // tests one without an emulator (WBS: "testable in Room's in-memory database on the JVM").
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Room's KSP processor, not the (separate, unneeded here) Room Gradle plugin — schema JSONs land
// in schemas/, one per exported version, and get committed (the WBS's "standard Room regret").
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // api, not implementation: MatchHistoryRepository's public API takes a HistoryDatabase (a
    // RoomDatabase) and returns Flow<...> — both types are otherwise invisible to a consumer's
    // own compile classpath.
    api(libs.androidx.room.runtime)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // JUnit4, not the rest of the repo's Jupiter — Robolectric's TestRunner only integrates with
    // JUnit4's @RunWith, confined to this one module.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

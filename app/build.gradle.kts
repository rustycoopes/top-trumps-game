plugins {
    id("toptrumps.android-application")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

composeCompiler {
    // compose-stability-config.conf declares com.toptrumps.rules.* and com.toptrumps.session.*
    // stable: those modules compile without the Compose compiler (module-structure ADR), so every
    // type they declare (PlayerView, MatchView, ConnectionState, ...) is inferred unstable by
    // default even though every field is an immutable `val` — and the match screen would
    // recompose in full on every state emission without this — TDD §10. The config file itself
    // can't carry a comment (the compiler rejects `#` lines as an invalid pattern).
    //
    // The WBS's other half, strong skipping, needs no setting here: this compiler version enables
    // it unconditionally (the toggle is deprecated and slated for removal) — it's what lets a
    // composable taking one of those now-"stable" types skip on structural equality rather than
    // refusing to skip an unstable-typed parameter outright.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability-config.conf"))
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
}

android {
    namespace = "com.toptrumps.app"

    defaultConfig {
        applicationId = "com.toptrumps.app"
        versionCode = 1
        versionName = "0.7.0-slice8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Deck content lives at the repo root, outside every module — see the deck-storage ADR.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("decks"))

    // :app diverges to JUnit 4 + Robolectric + Compose UI testing here — see the app-test-framework
    // ADR. Still defaults to false under AGP 9; without it Robolectric can't resolve the merged
    // manifest/resources it needs for a real Context.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:rules"))
    implementation(project(":core:decks"))
    implementation(project(":core:session"))
    implementation(project(":core:ai"))
    implementation(project(":platform:net"))
    implementation(project(":feature:history"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)

    // JUnit4, not the rest of the repo's Jupiter — createComposeRule() is a JUnit 4 TestRule and
    // RobolectricTestRunner is a JUnit 4 Runner. Confined to :app; :core:* stays on Jupiter.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    // debugImplementation, not testImplementation: this contributes the ComponentActivity
    // declaration createComposeRule() launches, so it must reach the merged debug manifest.
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // CI runs JDK 17; the local Android Studio JBR is JDK 25. Robolectric's bytecode
    // instrumentation needs these opens on newer JDKs, or it's "green in CI,
    // InaccessibleObjectException in Studio" (or the reverse) — see the app-test-framework ADR.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED")
}

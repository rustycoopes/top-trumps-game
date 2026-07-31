plugins {
    id("toptrumps.android-application")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.toptrumps.app"

    defaultConfig {
        applicationId = "com.toptrumps.app"
        versionCode = 1
        versionName = "0.1.0-slice1"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Deck content lives at the repo root, outside every module — see the deck-storage ADR.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("decks"))
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}

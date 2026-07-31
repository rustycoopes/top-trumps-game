plugins {
    id("toptrumps.jvm-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:rules"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The real /decks content lives at the repo root, outside every module (deck-storage ADR).
// Tests read it via FileDeckSource, taking the path from a system property Gradle injects here
// rather than a relative path, which breaks the moment a test runs from the IDE.
tasks.named<Test>("test") {
    systemProperty("decksRootDir", rootProject.layout.projectDirectory.dir("decks").asFile.absolutePath)
}

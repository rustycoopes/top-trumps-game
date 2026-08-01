plugins {
    id("toptrumps.jvm-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:rules"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    testImplementation(libs.kotlinx.coroutines.test)
}

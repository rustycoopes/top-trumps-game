plugins {
    id("toptrumps.jvm-library")
}

dependencies {
    implementation(project(":core:rules"))
    implementation(project(":core:session"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

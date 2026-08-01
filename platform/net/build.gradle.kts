plugins {
    id("toptrumps.android-library")
}

android {
    namespace = "com.toptrumps.platform.net"
}

dependencies {
    api(project(":core:session"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
}

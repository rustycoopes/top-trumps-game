plugins {
    id("toptrumps.jvm-library")
}

dependencies {
    api(libs.kotlinx.datetime)
    testImplementation(libs.kotlinx.coroutines.test)
}

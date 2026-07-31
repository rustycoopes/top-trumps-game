import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }
    }
}

// :core:* is JVM-only by construction (this plugin never touches android.jar), and this task
// additionally enforces the TDD's narrower allowlist: only the four named kotlinx/androidx
// coordinates may appear on a :core:* compile classpath.
// org.jetbrains (not just org.jetbrains.kotlin/kotlinx) covers the transitive JetBrains
// nullability annotations that kotlin-stdlib itself pulls in.
val allowedGroups = setOf("org.jetbrains.kotlinx", "org.jetbrains.kotlin", "org.jetbrains")
val allowedGavs = setOf("androidx.annotation:annotation")

val checkCoreDependencyAllowlist = tasks.register("checkCoreDependencyAllowlist") {
    group = "verification"
    description = "Fails if this core module depends on anything outside the TDD's allowlist."

    val compileClasspath = configurations.getByName("compileClasspath")
    inputs.files(compileClasspath)

    doLast {
        val offenders = compileClasspath.incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            // "unspecified" version identifies this build's own project components (e.g. a
            // :core:rules -> :core:decks project dependency), not a real external artifact.
            .filter { it.version != "unspecified" }
            .filter { moduleVersion ->
                val gav = "${moduleVersion.group}:${moduleVersion.name}"
                moduleVersion.group !in allowedGroups && gav !in allowedGavs
            }
            .map { "${it.group}:${it.name}:${it.version}" }
            .distinct()

        check(offenders.isEmpty()) {
            ":core:* may only depend on kotlinx-coroutines-core, kotlinx-serialization-json, " +
                "kotlinx-datetime and androidx.annotation. Found disallowed dependencies: $offenders"
        }
    }
}

tasks.named("check") {
    dependsOn(checkCoreDependencyAllowlist)
}

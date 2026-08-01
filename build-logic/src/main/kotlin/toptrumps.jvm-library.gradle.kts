import org.gradle.api.artifacts.component.ProjectComponentIdentifier
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

// The match-history ADR's other whole premise, alongside the allowlist above: :feature:history is
// a collector of :core:* values, never a dependency of one. Project components carry
// version == "unspecified" and so are invisible to the allowlist check below (which only ever
// sees real external artifacts) — this is the one place that gap matters, since a project
// dependency is exactly the shape a stray ":feature:history" reference would take.
val forbiddenProjectPaths = setOf(":feature:history")

val checkCoreDependencyAllowlist = tasks.register("checkCoreDependencyAllowlist") {
    group = "verification"
    description = "Fails if this core module depends on anything outside the TDD's allowlist, or on :feature:history."

    val compileClasspath = configurations.getByName("compileClasspath")
    inputs.files(compileClasspath)

    doLast {
        val allComponents = compileClasspath.incoming.resolutionResult.allComponents

        val offenders = allComponents
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

        val forbiddenProjectOffenders = allComponents
            .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
            .filter { it in forbiddenProjectPaths }
            .distinct()

        check(forbiddenProjectOffenders.isEmpty()) {
            ":core:* must never depend on :feature:history — deleting that module must leave " +
                ":core:* compiling (the match-history ADR). Found: $forbiddenProjectOffenders"
        }
    }
}

tasks.named("check") {
    dependsOn(checkCoreDependencyAllowlist)
}

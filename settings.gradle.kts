pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "top-trumps-game"

include(":core:rules")
include(":core:decks")
include(":core:session")
include(":core:ai")
include(":platform:net")
include(":feature:history")
include(":app")

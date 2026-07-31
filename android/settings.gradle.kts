pluginManagement {
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

rootProject.name = "Antimatter"
include(":app")
include(":core:data")
include(":core:network")
include(":core:ui")
include(":feature:connect")
include(":feature:chat")
include(":feature:files")

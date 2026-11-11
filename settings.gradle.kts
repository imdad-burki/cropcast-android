pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "cropcast"
include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:datastore")
include(":core:media")
include(":core:permissions")
include(":core:storage")
include(":feature:home")
include(":feature:onboarding")
include(":feature:region")
include(":feature:recording")
include(":feature:library")
include(":feature:player")
include(":feature:editor")
include(":feature:settings")
include(":service:recording")
include(":media:renderer")
include(":media:encoder")
include(":media:audio")

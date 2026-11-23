pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://nexus-external.vkteam.ru/repository/maven/") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://nexus-external.vkteam.ru/repository/maven/") }
    }
}
rootProject.name = "MOMENTO"
include(":app")

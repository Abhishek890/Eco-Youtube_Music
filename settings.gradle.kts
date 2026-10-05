pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.syk.sh") }
    }
}

rootProject.name = "Youtube Music Extension"
include(":app")
include(":ext")
include(":ytm-kt-local")
project(":ytm-kt-local").projectDir = file("ytm-kt-build")

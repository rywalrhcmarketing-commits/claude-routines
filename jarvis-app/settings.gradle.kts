pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Wiązanie Kotlin do llama.cpp (lokalny model AI) - nie jest na Maven
        // Central, tylko na JitPacku (io.github.ljcamargo:llamacpp-kotlin).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "VICTOR"
include(":app")

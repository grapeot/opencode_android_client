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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // VoiceFlowKit is published from grapeot/voiceflow-android via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "opencode_client"
include(":app")

// Local dual-strategy development: when the sibling monorepo is present, compile
// against it instead of waiting for JitPack. CI / clean checkouts fall back to
// the JitPack pin in app/build.gradle.kts.
val localVoiceflowAndroid = file("../brainwave_mobile/brainwave_public_android")
if (localVoiceflowAndroid.resolve("settings.gradle.kts").isFile) {
    includeBuild(localVoiceflowAndroid) {
        dependencySubstitution {
            substitute(module("com.github.grapeot:voiceflow-android"))
                .using(project(":voiceflowkit"))
        }
    }
}

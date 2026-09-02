// Top-level build file
plugins {
    id("com.android.application") version "8.5.0" apply false
    // 2.3.20, nie 1.9.24: llamacpp-kotlin (lokalny model AI, patrz app/build.gradle.kts)
    // jest skompilowane Kotlinem 2.3.x - kapt starszym kompilatorem nie umie
    // odczytać jego metadanych (.kotlin_module w wersji binarnej 2.3.0).
    // Gradle 8.7 i AGP 8.5.0 są nadal wspierane przez Kotlina 2.3.20 - nie trzeba
    // ich ruszać.
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    // Od Kotlina 2.0 kompilator Compose jest częścią repo Kotlina, nie osobnym
    // artefaktem sterowanym przez composeOptions.kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

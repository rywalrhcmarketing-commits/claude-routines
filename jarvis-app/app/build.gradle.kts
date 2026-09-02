plugins {
    id("com.android.application") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    kotlin("kapt")
}

// android.kotlinOptions{} zostało usunięte w Kotlinie 2.2 - to jego zamiennik.
// Na poziomie zadania zamiast rozszerzenia kotlin{}, żeby nie zgadywać, jaki
// dokładnie kształt DSL wystawia akurat ta kombinacja pluginów.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Material3 i część API Compose są nadal oznaczone jako eksperymentalne.
        // Włączamy je raz dla całego modułu zamiast dopisywać @OptIn przy
        // każdej funkcji, która używa np. ExposedDropdownMenuBox.
        freeCompilerArgs.addAll(
            listOf(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
            )
        )
    }
}

android {
    namespace = "pl.victor.app"
    // 35, nie 34: media3 1.5.1 (Live Stream Lab) wymaga compileSdk >= 35 w
    // metadanych AAR. To tylko podnosi zbiór API dostępnych przy kompilacji -
    // targetSdk zostaje na 34, więc zachowanie apki w runtime się nie zmienia.
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.victor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Od Kotlina 2.0 kompilator Compose idzie z pluginem
    // org.jetbrains.kotlin.plugin.compose (patrz plugins{} wyżej) - osobne
    // composeOptions.kotlinCompilerExtensionVersion nie jest już potrzebne.

    testOptions {
        unitTests {
            // Testy jednostkowe działają na JVM, gdzie android.util.Log to pusty
            // stub rzucający wyjątkiem. Bez tego każdy test klasy, która loguje,
            // wywala się na RuntimeException zamiast sprawdzić asercje.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            // Biblioteki Google API i Apache HttpClient wnoszą własne kopie
            // plików META-INF; bez wykluczenia mergeDebugJavaResource przerywa
            // build z powodu duplikatów.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA"
            )
        }
    }
}

dependencies {
    // HeyCyan vendor SDK (AAR z FerSaiyan repo)
    implementation(files("libs/glasses_sdk_20250723_v01.aar"))

    // EventBus - używany przez vendor SDK do komunikacji wewnętrznej
    implementation("org.greenrobot:eventbus:3.3.1")

    // GSON - do parsowania JSON (Room, Gemini)
    implementation("com.google.code.gson:gson:2.10.1")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Lokalny model AI (offline) - wiązanie Kotlin do llama.cpp (repo JitPack,
    // patrz settings.gradle.kts). Pinowane na sztywno - nowsze wersje zmieniały
    // kształt API (patrz LlamaCppInferenceEngine).
    //
    // Wyklucza własne androidx.core/core-ktx tej biblioteki (ciągnie 1.18.0,
    // które wymaga compileSdk 36 + AGP 8.9.1 - projekt ma 35/8.5.0) - zostaje
    // jawna, już sprawdzona wersja 1.13.1 z tego pliku. To mały wrapper JNI,
    // korzysta z core-ktx co najwyżej po wierzchu, nie z czegoś nowego w 1.18.
    implementation("io.github.ljcamargo:llamacpp-kotlin:0.4.0") {
        exclude(group = "androidx.core", module = "core-ktx")
        exclude(group = "androidx.core", module = "core")
    }

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Camera (fallback jeśli chcemy używać kamery telefonu)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Porcupine - on-device wake word
    implementation("ai.picovoice:porcupine-android:3.0.0")
    // Porcupine recorder (do audio capture)
    implementation("ai.picovoice:android-voice-processor:1.0.0")
    // ML Kit - Barcode scanning (QR)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ML Kit - Text Recognition (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // ML Kit - Translation (offline)
    implementation("com.google.mlkit:translate:17.0.2")

    // Google Sign-In + Calendar + Gmail API (jedno konto, wspólne logowanie)
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")
    implementation("com.google.apis:google-api-services-calendar:v3-rev20260708-2.0.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20260727-2.0.0")

    // WorkManager - do scheduled tasks (proactive alerts)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Media3/ExoPlayer + RTSP - wyłącznie dla gated Live Stream Lab (Opcje
    // programistyczne). Odtwarzacz i próbnik RTSP same w sobie nic nie wysyłają
    // do okularów - patrz pl.victor.app.livestream.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

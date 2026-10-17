import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        // The cloud build passes -PbuildNumber=N so every release has a higher version than the last.
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    // One fixed key for every build, so Android accepts each new version as an update of the last one.
    val keystorePath = System.getenv("JARVIS_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("jarvis") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("JARVIS_KS_PASS")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS") ?: "jarvis"
                keyPassword = System.getenv("JARVIS_KS_PASS")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("jarvis")
        }
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK is directly installable on your own phone.
            signingConfig = signingConfigs.findByName("jarvis") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Optional more-accurate wake word, free tier: https://console.picovoice.ai
    implementation("ai.picovoice:porcupine-android:3.0.2")
    // Free offline wake word with no account or key (Vosk). The voice model is downloaded once from inside the app.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    // On-device photo understanding (scenes + counting faces). Nothing is uploaded.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.7")

    testImplementation("junit:junit:4.13.2")
}

// Fonts (Fraunces for titles, Inter for text, JetBrains Mono for small labels) are fetched while building,
// so no font files need to be uploaded by hand. If a download fails the app falls back to the system font.
val fontsDir = layout.buildDirectory.dir("generated/fontAssets")
val fetchFonts = tasks.register("fetchFonts") {
    val out = fontsDir.get().asFile
    outputs.dir(out)
    doLast {
        val base = "https://raw.githubusercontent.com/google/fonts/main/ofl/"
        val files = mapOf(
            "fraunces.ttf" to "fraunces/Fraunces%5BSOFT%2CWONK%2Copsz%2Cwght%5D.ttf",
            "fraunces_italic.ttf" to "fraunces/Fraunces-Italic%5BSOFT%2CWONK%2Copsz%2Cwght%5D.ttf",
            "inter.ttf" to "inter/Inter%5Bopsz%2Cwght%5D.ttf",
            "jetbrainsmono.ttf" to "jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"
        )
        val dir = File(out, "fonts")
        dir.mkdirs()
        files.forEach { (name, path) ->
            val f = File(dir, name)
            if (!f.exists() || f.length() < 10_000) {
                try {
                    URL(base + path).openStream().use { input -> f.outputStream().use { output -> input.copyTo(output) } }
                } catch (e: Exception) {
                    f.delete()
                    println("Font $name not downloaded: ${e.message}")
                }
            }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(fontsDir)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(fetchFonts) }

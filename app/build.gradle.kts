import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

fun readLocalProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    val props = Properties()
    file.inputStream().use { props.load(it) }
    return props.getProperty(name)
}

// Signing keystore (neither the keystore nor its properties are committed).
fun readKeystoreProperty(name: String): String? {
    val file = rootProject.file("keystore.properties")
    if (!file.exists()) return null
    val props = Properties()
    file.inputStream().use { props.load(it) }
    return props.getProperty(name)
}

android {
    namespace = "com.chrispixel.chrisai"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.chrispixel.chrisai"
        minSdk = 23
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val openrouterApiKey = readLocalProperty("OPENROUTER_API_KEY").orEmpty()
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openrouterApiKey\"")
        buildConfigField("String", "DEFAULT_MODEL", "\"openrouter/free\"")
        buildConfigField("String", "OPENROUTER_BASE_URL", "\"https://openrouter.ai/api/v1\"")

        // Official update source: the ChrisAI GitHub repository (releases only).
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"ElChrispixeloficial\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"ChrisAi\"")
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://api.github.com\"")

        // Single native module; arm64-v8a only to keep the APK lean.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters("arm64-v8a")
            }
        }
        ndk {
            // Also trim native libraries shipped by dependencies (e.g. DataStore).
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val storeFile = readKeystoreProperty("keystore")
            val storePassword = readKeystoreProperty("storePassword")
            val keyAlias = readKeystoreProperty("keyAlias")
            val keyPassword = readKeystoreProperty("keyPassword")
            if (storeFile != null) {
                this.storeFile = rootProject.file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties.markwon:core:4.6.2")

    // Persistence: Room for conversations/messages/memories, DataStore for settings.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.3.0")
}
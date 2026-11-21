plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tecsattv.afinador"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tecsattv.afinador"
        minSdk = 21
        targetSdk = 34
        versionCode = 9
        versionName = "1.6.0"
    }

    signingConfigs {
        create("fixa") {
            storeFile = file("afinador.keystore")
            storePassword = "afinador123"
            keyAlias = "afinador"
            keyPassword = "afinador123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixa")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // leitura do texto dos PDFs (títulos das músicas) e separação de páginas
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // reconhecimento de texto (OCR) offline para PDFs escaneados
    implementation("com.google.mlkit:text-recognition:16.0.0")
}

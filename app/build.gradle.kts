plugins {
    id("com.android.application")
}

android {
    namespace = "ru.ilya.shortsguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.ilya.shortsguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // Релизный ключ хранится не в репозитории, а в секретах GitHub
    // (SIGNING_KEYSTORE_BASE64, SIGNING_PASSWORD). Сборка в Actions передаёт их через переменные окружения.
    signingConfigs {
        create("release") {
            val ks = System.getenv("SIGNING_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "shortsguard"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

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
        versionCode = 2
        versionName = "1.1"
    }

    // Постоянный ключ подписи: иначе каждая сборка на GitHub подписана новым случайным
    // ключом и обновление поверх старой версии не устанавливается.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

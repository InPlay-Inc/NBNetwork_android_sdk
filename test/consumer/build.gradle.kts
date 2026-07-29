plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nanobeaconnetwork.consumer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.nanobeaconnetwork.consumer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":nbn"))
}

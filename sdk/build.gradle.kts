plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.nanobeaconnetwork"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://api.nanobeaconnetwork.com\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

// Publishing to Maven Central via the vanniktech plugin (handles the Central Portal upload,
// sources + javadoc jars, POM, and GPG signing). Credentials/keys are read from Gradle
// properties — put them in ~/.gradle/gradle.properties (never commit):
//   mavenCentralUsername / mavenCentralPassword       (Central Portal user token)
//   signingInMemoryKey / signingInMemoryKeyPassword   (ASCII-armored GPG key + passphrase)
//   signingInMemoryKeyId (optional, last 8 hex of the key id)
// Local testing: `./gradlew :sdk:publishToMavenLocal`. Release: `./gradlew :sdk:publishToMavenCentral`
// (then click Publish in the Central Portal; pass automaticRelease = true to skip that step).
mavenPublishing {
    publishToMavenCentral()
    // Sign only when a GPG key is configured, so `publishToMavenLocal` works for local testing
    // without keys. Maven Central requires signatures, so the key MUST be present for a real
    // release (Central rejects unsigned uploads at validation).
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates("com.nanobeaconnetwork", "nbn-sdk", "0.1.0")

    pom {
        name.set("NanoBeaconNetwork SDK")
        description.set("BLE beacon network SDK for Android (NanoBeaconNetwork).")
        url.set("https://nanobeaconnetwork.com")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("inplay")
                name.set("InPlay Tech")
                email.set("support@nanobeaconnetwork.com")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/nanobeaconnetwork/nbn-sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/nanobeaconnetwork/nbn-sdk-android.git")
            url.set("https://github.com/nanobeaconnetwork/nbn-sdk-android")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite)
    implementation(libs.security.crypto)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

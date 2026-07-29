plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.maven.publish)
}


val libVersion = providers.gradleProperty("VERSION_NAME").get()
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
        unitTests.isIncludeAndroidResources = true
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
// Local testing: `./gradlew :nbn:publishToMavenLocal`. Release: `./gradlew :nbn:publishToMavenCentral`
// (then click Publish in the Central Portal; pass automaticRelease = true to skip that step).
mavenPublishing {
    publishToMavenCentral()
    // Sign only when a GPG key is configured, so `publishToMavenLocal` works for local testing
    // without keys. Maven Central requires signatures, so the key MUST be present for a real
    // release (Central rejects unsigned uploads at validation).
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates("com.nanobeaconnetwork", "nanobeaconnetwork-android", libVersion)

    pom {
        name.set("NanoBeaconNetwork Android Library")
        description.set("BLE beacon network client library for Android (NanoBeaconNetwork).")
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
            connection.set("scm:git:https://github.com/InPlay-Inc/NBNetwork_android_sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/InPlay-Inc/NBNetwork_android_sdk.git")
            url.set("https://github.com/InPlay-Inc/NBNetwork_android_sdk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
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
    testImplementation(libs.robolectric)
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Root build file for the NanoBeaconNetwork Android library.
// Plugins are declared here (apply false) and applied per module
// (:sdk = library, :examples:app = sample application).
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.maven.publish) apply false
}

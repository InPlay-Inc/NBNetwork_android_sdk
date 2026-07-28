# NanoBeaconNetwork Android SDK

Android SDK for the **NanoBeaconNetwork** BLE beacon network. It parses NanoBeaconNetwork
beacon advertisements (service UUID `0xFC32`), deduplicates sightings, and uploads reports to
the NanoBeaconNetwork backend. Beacon payload decryption happens **server-side** — the SDK
never holds beacon identity keys.

[![Maven Central](https://img.shields.io/maven-central/v/com.nanobeaconnetwork/nbn-sdk.svg)](https://central.sonatype.com/artifact/com.nanobeaconnetwork/nbn-sdk)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## Requirements

- `minSdk` 29+
- Java 11 / Kotlin source & target compatibility

## Installation

Available on Maven Central:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.nanobeaconnetwork:nbn-sdk:0.1.0")
}
```

Transitive dependencies (Retrofit, OkHttp, Room, SQLCipher, security-crypto,
play-services-location) resolve automatically.

## Quick start

By default the SDK runs in **EXTERNAL** scan mode: your app owns BLE scanning and feeds
results to the SDK. This lets the SDK coexist with any BLE scanning you already do.

```kotlin
// 1. Initialize once, e.g. in Application.onCreate()
NbnSdk.init(
    context,
    NbnConfig.Builder()
        // Defaults to https://api.nanobeaconnetwork.com — override only for testing.
        .logLevel(NbnConfig.LogLevel.WARN)
        .build()
)

// 2. Feed scan results from your own BluetoothLeScanner callback.
//    Include the 0xFC32 service-data filter so NanoBeaconNetwork beacons are seen.
override fun onScanResult(callbackType: Int, result: ScanResult) {
    NbnSdk.submitScanResult(result)
}

// Or feed the raw 0xFC32 service-data block directly (framework-agnostic):
// NbnSdk.submitServiceData(serviceData, rssi)

// 3. Observe state (optional):
lifecycleScope.launch { NbnSdk.reportStats.collect { /* update UI */ } }

// 4. On shutdown:
NbnSdk.shutdown()
```

If you prefer the SDK to own scanning (a foreground service), build the config with
`.scanSource(NbnConfig.ScanSource.SDK_MANAGED)` and call `NbnSdk.startScan()` /
`NbnSdk.stopScan()`. See the integration guide for permissions and background scanning.

## Sample app

A runnable example lives in [`examples/app`](examples/app) — a small Compose app that
consumes the SDK from source (`implementation(project(":sdk"))`) and demonstrates
EXTERNAL-mode scanning, report stats, and settings.

```bash
./gradlew :examples:app:assembleDebug     # build the sample APK
./gradlew :examples:app:installDebug      # install on a connected device
```

## Documentation

- Full integration guide, permissions, and the 0xFC32 wire format: [INTEGRATION.md](INTEGRATION.md)
- Public API: `NbnSdk`, `NbnConfig`, `NbnError`, `NbnPermissions`

## Building from source

```bash
./gradlew :sdk:assembleRelease        # build the AAR
./gradlew :sdk:testDebugUnitTest      # run unit tests
./gradlew :sdk:publishToMavenLocal    # install to ~/.m2 for local testing
```

Releasing to Maven Central: see [RELEASING.md](RELEASING.md).

## License

```
Copyright 2026 InPlay Tech

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

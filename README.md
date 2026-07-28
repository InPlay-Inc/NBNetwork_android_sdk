# NanoBeaconNetwork Android SDK

Android SDK for the **NanoBeaconNetwork** BLE beacon network. It scans NanoBeaconNetwork beacon
advertisements (service UUID `0xFC32`), deduplicates sightings, and reports them **anonymously**
to the NanoBeaconNetwork backend. Beacon payload decryption happens **server-side** — the SDK
never holds beacon identity keys.

[![Maven Central](https://img.shields.io/maven-central/v/com.nanobeaconnetwork/nbn-sdk.svg)](https://central.sonatype.com/artifact/com.nanobeaconnetwork/nbn-sdk)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## Features

- **Two scan modes** — `SDK_SCAN` (the SDK runs its own foreground scan, default) or `HOST_SCAN`
  (you feed results from your existing scanner).
- **Background + reboot resilience** — foreground service with `START_STICKY`, and optional
  auto-resume after device reboot (`restartOnBoot`, default on).
- **Anonymous** — no login/account; the SDK fetches its own anonymous token. You manage nothing.
- **Offline-first** — reports are queued in an encrypted (SQLCipher) local DB and uploaded in
  batches; secrets live in Keystore-backed encrypted storage.

## Requirements

- `minSdk` 29+ · `compileSdk` 36
- Java 11 / Kotlin source & target compatibility

## Installation

Available on Maven Central (transitive deps — Retrofit, OkHttp, Room, SQLCipher,
security-crypto, play-services-location — resolve automatically):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.nanobeaconnetwork:nbn-sdk:0.1.0")
}
```

## Quick start (SDK_SCAN — default)

In the default **SDK_SCAN** mode the SDK owns its own BLE scan via a foreground service that keeps
running in the background and auto-restarts after a reboot. **Two calls are required:**
`init(...)` (always) and `startScan()` — `init()` alone does **not** begin scanning.

```kotlin
// 1. Initialize once, e.g. in Application.onCreate(). REQUIRED before any other SDK call.
NbnClient.init(
    context,
    NbnConfig.Builder()
        // Defaults to https://api.nanobeaconnetwork.com — override only for testing.
        .logLevel(NbnConfig.LogLevel.WARN)
        .build()   // scanSource defaults to SDK_SCAN; restartOnBoot defaults to true
)

// 2. Request the runtime permissions (BLE + location; + notifications on Android 13+).
if (!NbnPermissions.checkScanPermissions(context)) {
    requestPermissionsLauncher.launch(NbnPermissions.getScanPermissions())
}
// For background scanning, also request ACCESS_BACKGROUND_LOCATION *after* foreground
// location is granted (Android requires this two-step flow):
//   NbnPermissions.checkBackgroundLocationPermission(context)

// 3. Start scanning (REQUIRED to actually scan — init() does not auto-start).
NbnClient.startScan()      // starts the SDK's foreground scan service
// ...later...
NbnClient.stopScan()

// Optional: observe stats / lifecycle.
lifecycleScope.launch { NbnClient.reportStats.collect { /* update UI */ } }

// On teardown:
NbnClient.shutdown()
```

> **Do I still call `init` / `startScan` in SDK_SCAN?** Yes to both. `init(...)` is always
> required, and `startScan()` is what begins scanning. The **only** time scanning resumes without
> your code is **after a device reboot** — if you had called `startScan()` before and left
> `restartOnBoot = true`, a boot receiver re-starts the service automatically (it self-initializes
> the SDK). On a normal app launch you still call `init()` and `startScan()` yourself.

## Alternative: HOST_SCAN (you already scan BLE)

If your app already runs its own `BluetoothLeScanner`, use `HOST_SCAN` and feed results; the SDK
then never touches BLE and `startScan()` is a no-op:

```kotlin
NbnClient.init(context, NbnConfig.Builder().scanSource(NbnConfig.ScanSource.HOST_SCAN).build())

// From your own scan callback — the scan MUST include the 0xFC32 filter:
override fun onScanResult(callbackType: Int, result: ScanResult) {
    NbnClient.submitScanResult(result)
}
```

See [INTEGRATION.md](INTEGRATION.md) for permissions, background/Doze, OEM battery caveats, and
both modes in full detail.

> **Migrating from an earlier build?** The default `scanSource` changed from `HOST_SCAN` to
> `SDK_SCAN`. If your app fed results via `submitScanResult()`, you must now set
> `.scanSource(NbnConfig.ScanSource.HOST_SCAN)` explicitly, or those calls become no-ops.

## Permissions

The SDK **declares** the BLE / location / foreground-service permissions in its manifest (they
merge into your app) and provides the `NbnPermissions` helpers. But the **runtime permission
dialog must be triggered by your app** from an `Activity`/`Fragment` — a library has no Activity
and cannot (and shouldn't) pop the system prompt itself. The SDK's job is to tell you *which*
permissions to request and *whether* they're granted; requesting them is the host's job.

```kotlin
class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Start once granted (SDK_SCAN). In HOST_SCAN, start your own scanner here instead.
        if (NbnPermissions.checkScanPermissions(this)) NbnClient.startScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (NbnPermissions.checkScanPermissions(this)) {
            NbnClient.startScan()
        } else {
            requestPermissions.launch(NbnPermissions.getScanPermissions())  // shows the dialog
        }
    }
}
```

- `getScanPermissions()` returns the right set for the OS version (location; `BLUETOOTH_SCAN` on
  Android 12+; `POST_NOTIFICATIONS` on Android 13+ for the foreground-service notification).
- **Background scanning** needs `ACCESS_BACKGROUND_LOCATION`, which Android requires you to
  request **separately, *after* foreground location is granted** — check with
  `NbnPermissions.checkBackgroundLocationPermission(context)`.
- `NbnClient.init(...)` needs no permissions and can run anytime; permissions are only needed
  when `startScan()` actually scans.

## Sample app

A runnable example lives in [`examples/app`](examples/app) — a small Compose app that
consumes the SDK from source (`implementation(project(":sdk"))`) and demonstrates
HOST_SCAN-mode scanning, report stats, and settings.

```bash
./gradlew :examples:app:assembleDebug     # build the sample APK
./gradlew :examples:app:installDebug      # install on a connected device
```

## Documentation

- Full integration guide, permissions, and the 0xFC32 wire format: [INTEGRATION.md](INTEGRATION.md)
- Public API: `NbnClient`, `NbnConfig`, `NbnError`, `NbnPermissions`

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

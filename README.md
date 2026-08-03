# NanoBeaconNetwork for Android

Android library for the **NanoBeaconNetwork** BLE beacon network. It scans NanoBeaconNetwork beacon
advertisements (service UUID `0xFC32`), suppresses exact repeated broadcasts, and reports them using an
**account-free client token**. Beacon payload decryption happens **server-side** — the library
never holds beacon identity keys.

[![Maven Central](https://img.shields.io/maven-central/v/com.nanobeaconnetwork/nanobeaconnetwork-android.svg)](https://central.sonatype.com/artifact/com.nanobeaconnetwork/nanobeaconnetwork-android)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## Features

- **Two scan modes** — `LIBRARY_SCAN` (the library runs its own foreground scan, default) or `HOST_SCAN`
  (you feed results from your existing scanner).
- **Background resilience** — foreground service with `START_STICKY`; reboot recovery is on by
  default, and takes effect once your app grants `ACCESS_BACKGROUND_LOCATION`.
- **Account-free** — no user login; the library obtains a token tied to the app/device Android ID.
- **Latest-data-first** — each physical BLE source keeps only its newest pending observation;
  retry is bounded to six ordinary attempts or one hour, whichever comes first. Reports are queued
  in an encrypted (SQLCipher) local DB; secrets live in Keystore-backed encrypted storage.

## Requirements

- `minSdk` 29+ · `compileSdk` 36
- Java 11 / Kotlin source & target compatibility

## Installation

Available on Maven Central (transitive deps — Retrofit, OkHttp, Room, SQLCipher,
security-crypto, play-services-location — resolve automatically):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.nanobeaconnetwork:nanobeaconnetwork-android:0.2.0")
}
```

## Quick start (LIBRARY_SCAN — default)

The library scans in its own foreground service, which keeps running after the host app moves to the
background. Two calls:

**1. Initialize** in `Application.onCreate()` — needs no permissions:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NbnClient.init(this)      // all defaults
    }
}
```

**2. Start scanning** — any time after the runtime permissions are granted, e.g. from `onResume()` or
a "Start" button. `startScan()` is idempotent and does nothing while the permissions are missing:

```kotlin
override fun onResume() {
    super.onResume()
    NbnClient.startScan()
}
```

Your app has to request those permissions itself — see [Permissions](#permissions) below for the
code.


> **Already run your own BLE scanner?** An alternative **HOST_SCAN** mode lets you feed results to
> the library instead of having it scan. It has its own setup and requirements (your own foreground
> service, the `0xFC32` scan filter) — see [INTEGRATION.md](INTEGRATION.md) §5 for the full guide.

See [INTEGRATION.md](INTEGRATION.md) for permissions, background/Doze, OEM battery caveats, and
both scan modes in full detail.


## Permissions

The library declares everything it needs in its own manifest, which merges into your app — you don't add
`<uses-permission>` lines for it. What your app does have to do is **request these at runtime**:

| Permission | Why | When |
|---|---|---|
| `ACCESS_FINE_LOCATION` | Required for BLE scanning, and to tag reports with a position | Always |
| `ACCESS_COARSE_LOCATION` | Requested alongside fine location | Always |
| `BLUETOOTH_SCAN` | Scanning for beacons | Android 12+ |
| `ACCESS_BACKGROUND_LOCATION` | Only to resume scanning after a device reboot — and you must declare it yourself, the library deliberately doesn't | Optional |

`NbnPermissions.getScanPermissions()` returns the first three, already matched to the running OS
version.

### Requesting the runtime permissions

`NbnPermissions.getScanPermissions()` returns the right set for the running OS version, so you don't
have to version-check anything:

```kotlin
class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* nothing to do — startScan() checks for itself */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions.launch(NbnPermissions.getScanPermissions())
    }
}
```

No need to check first: if everything is already granted, `launch(...)` shows no dialog and returns
immediately.

### Optional: reboot recovery and `ACCESS_BACKGROUND_LOCATION`

A scan started while an Activity is visible keeps running when the Activity moves to the background,
because the library uses a location foreground service. Background location is **not** needed for that
normal flow — only for resuming after a **device reboot**, where the service starts with your app in
the background and Android then denies location access without it.

Nothing to configure in the library: `restartOnBoot` is already on by default, so
**`ACCESS_BACKGROUND_LOCATION` is effectively the switch.** Add it only if reboot recovery is a core
requirement of your app.

**1. Declare the permission in your app** (the library deliberately doesn't):

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

**2. Request it only after foreground location is granted.** On Android 10 this is a second runtime
request. On Android 11+ the user must select "Allow all the time" in Settings:

```kotlin
private val requestBackgroundLocation = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { /* denied is survivable; normal foreground-service scanning still works */ }

if (!NbnPermissions.checkBackgroundLocationPermission(this)) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    } else {
        requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}
```

Explain the feature and provide a decline option before requesting the permission or opening
Settings. After returning from Settings, re-check the grant in `onResume()`. On Android 11+,
`packageManager.backgroundPermissionOptionLabel` provides the localized option name.

If you never declare the permission — or the user revokes it — the library simply **does not resume after
a reboot**. It never resumes with degraded, position-less reports, and everything else (including
scanning while your app is backgrounded) is unaffected.

> **Google Play impact.** Because the library no longer declares `ACCESS_BACKGROUND_LOCATION`, the
> default integration does not add that permission to the host manifest. A host that opts in adds
> the permission itself and is responsible for the
> [Location permissions declaration](https://support.google.com/googleplay/android-developer/answer/9799150),
> prominent in-app disclosure, and demonstrating that background location is a core feature.

`NbnClient.init(...)` needs no permissions and can run anytime; permissions are only needed when
`startScan()` actually scans.

For the `NbnPermissions` helpers and the request code (including the two-step background-location
flow), see [INTEGRATION.md](INTEGRATION.md) §3.

## API

The whole host-facing surface, in `com.nanobeaconnetwork` (models in `….model`):

| Type | What it's for |
|---|---|
| `NbnClient` | The entry point: `init`, `startScan` / `stopScan`, `setScanMode`, `setRestartOnBoot`, `shutdown`, plus `submitScanResult` / `submitServiceData` for HOST_SCAN |
| `NbnConfig` + `NbnConfig.Builder` | Optional config: `scanSource`, `scanMode`, `restartOnBoot`, `logLevel` |
| `NbnPermissions` | `getScanPermissions()` to request, plus two `check…()` helpers |
| `ScanState`, `ScanEvent`, `ScanLogEntry`, `ReportStats` | Read-only state exposed as flows on `NbnClient` |

Full signatures, defaults, and per-mode caveats: [INTEGRATION.md](INTEGRATION.md) §14.

### Report delivery semantics

- A matching HTTP `202 accepted` confirms only that the complete batch entered the server's durable
  processing chain. It does not mean a beacon tag was valid or that every item will be verified.
- A new sighting replaces an older unsent sighting from the same BLE MAC. If an HTTP request is
  already in flight, it stays immutable while the new sighting occupies a separate latest slot.
- Each latest sighting gets at most six ordinary send attempts and expires after one hour. HTTP
  `429` and `503` obey `Retry-After` without consuming that attempt budget.
- The local queue is capped at 50,000 rows or an estimated 50 MiB. At capacity the new item reports
  `QueueFull`; the library never silently evicts another beacon's data.

## Sample app

A runnable example lives in [`examples/app`](examples/app) — a small Compose app that
consumes the library from source (`implementation(project(":nbn"))`) and demonstrates
HOST_SCAN-mode scanning, report stats, and settings.

```bash
./gradlew :examples:app:assembleDebug     # build the sample APK
./gradlew :examples:app:installDebug      # install on a connected device
```

## Documentation

- Full integration guide, permissions, and the 0xFC32 wire format: [INTEGRATION.md](INTEGRATION.md)
- Data collection, host disclosures, and backup exclusions: [PRIVACY.md](PRIVACY.md)
- Full API reference: [INTEGRATION.md](INTEGRATION.md) §14

## Building from source

```bash
./gradlew :nbn:assembleRelease        # build the AAR
./gradlew :nbn:testDebugUnitTest      # run unit tests
./gradlew :nbn:publishToMavenLocal    # install to ~/.m2 for local testing
```

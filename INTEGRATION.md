# NanoBeaconNetwork — Android Integration Guide

This guide walks you through integrating the **NanoBeaconNetwork Android library** into an existing Android app. The
library is a **data-collection library**: it scans BLE beacons (Eddystone-style, Service UUID `0xFC32`),
de-duplicates them, and reports them with an **account-free client token** to the NanoBeaconNetwork
server. It has **no user
login, no account system, and no device management** — you integrate it, and it just collects
and uploads.

- **Package:** `com.nanobeaconnetwork`
- **Min SDK:** 29 (Android 10) · **Compile/Target SDK:** 36
- **Language:** Kotlin (Java-callable for the core methods)
- **Artifact:** a single `.aar` (`:nbn`)

---

## 1. Two integration modes

Pick one. **`LIBRARY_SCAN` is the default** — the library runs its own scan and just works. Use
**`HOST_SCAN`** if your app already scans BLE and you want to feed results yourself.

| | `LIBRARY_SCAN` (default) | `HOST_SCAN` |
|---|---|---|
| Who owns the BLE scanner | The library | **Your app** |
| You call | `NbnClient.startScan()` / `stopScan()` | `NbnClient.submitScanResult(result)` from your scan callback |
| Foreground service | Library-provided (survives backgrounding; reboot recovery needs host-granted background location) | Yours (if you need background) |
| Coexistence with your BLE | Independent scanner; shares only the radio duty cycle | **None** — the library never touches `BluetoothLeScanner` |
| Best for | Apps that want library-managed foreground scanning, with optional reboot recovery | Apps that already scan BLE, or want full control over scanning/power |

> **Key rule:** a `ScanFilter`/scan setting only affects the scan that sets it, and multiple
> scanners in one app are independent. `LIBRARY_SCAN` runs the library's own scanner alongside yours
> without coupling their lifecycles; `HOST_SCAN` means the library does not scan at all. See §7.

---

## 2. Add the dependency

### Option A — Maven coordinate (recommended)

The library publishes as `com.nanobeaconnetwork:nanobeaconnetwork-android`. Transitive dependencies (Retrofit,
OkHttp, security-crypto, Room, SQLCipher, play-services-location) are resolved automatically.

```kotlin
// settings.gradle.kts — add the repository the library was published to
dependencyResolutionManagement {
    repositories {
        mavenLocal()                 // if published via ./gradlew :nbn:publishToMavenLocal
        // or a file repo you were given:
        // maven { url = uri("<path-or-url>/nanobeaconnetwork-android/repo") }
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.nanobeaconnetwork:nanobeaconnetwork-android:0.1.0")
}
```

### Option B — Local AAR

Copy `nanobeaconnetwork-android-0.1.0.aar` into your module (e.g. `app/libs/`) and add its transitive deps manually:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/nanobeaconnetwork-android-0.1.0.aar"))

    // The library depends on these at runtime — add them if your app doesn't already have them:
    implementation("com.squareup.retrofit2:retrofit:<ver>")
    implementation("com.squareup.retrofit2:converter-gson:<ver>")
    implementation("com.squareup.okhttp3:okhttp:<ver>")
    implementation("androidx.security:security-crypto:<ver>")   // encrypted token storage
    implementation("com.google.android.gms:play-services-location:<ver>") // GPS on reports
    // Room + SQLCipher are used internally for the offline queue.
}
```

Requires `minSdk >= 29` and Java 11 (or Kotlin) source/target compatibility.

> **Publishing the library (maintainers):** `./gradlew :nbn:publishToMavenLocal` publishes to
> `~/.m2` for local testing; releasing to Maven Central (credentials, GPG signing, and the
> publish commands) is documented in [RELEASING.md](RELEASING.md).

---

## 3. Permissions

The library manifest declares the default BLE, foreground-location, foreground-service, and
network permissions. It intentionally excludes `ACCESS_BACKGROUND_LOCATION`. The host app is
responsible for requesting runtime permissions and declaring optional permissions.

Permissions used:

| Permission | When needed |
|---|---|
| `BLUETOOTH_SCAN` (API 31+) | scanning |
| `ACCESS_FINE_LOCATION` | BLE scanning on API < 31, and to tag reports with GPS |
| `ACCESS_BACKGROUND_LOCATION` | optional: restoring the location foreground service after reboot; declared by the host |
| `FOREGROUND_SERVICE*` | background scanning via a foreground service |
| `INTERNET` | uploading reports (declared by the library) |

Request them with `NbnPermissions.getScanPermissions()`, which returns the right set for the running
OS version. `requestPermissionsLauncher` comes from `registerForActivityResult(...)`, so this must
live in an `Activity`/`Fragment` — an `Application` has no such API:

```kotlin
private val requestPermissionsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { /* nothing to do — startScan() checks the permissions itself */ }

// In onCreate() — no pre-check needed: if the permissions are already granted, launch() shows
// no dialog and invokes the callback immediately.
requestPermissionsLauncher.launch(NbnPermissions.getScanPermissions())
```

Starting the scan is a separate step: call `NbnClient.startScan()` any time after the permissions are
granted (see §6). It is idempotent and does nothing while they are missing, so `onResume()` or a
"Start" button both work.

**No notification permission.** `POST_NOTIFICATIONS` is intentionally neither declared nor requested
by the library: a foreground service does not need it, and its required ongoing notification is posted
regardless — on Android 13+ Android just keeps it out of the notification drawer while still listing
it in the Task Manager. If you would rather users see that notification, declare and request the
permission in your own app.

### Optional: background location for reboot recovery

The default integration does not declare or request `ACCESS_BACKGROUND_LOCATION`. A scan started while
an Activity is visible keeps running in a foreground service after the Activity moves to the
background; background location is not required for that.

`restartOnBoot` is already `true` by default, so there is nothing to enable in the library — the
permission is effectively the switch. If reboot recovery is a core requirement, declare it in the host
manifest (`ACCESS_BACKGROUND_LOCATION` is intentionally not contributed by the library AAR) and request it
as shown below:

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

Hosts that hold background location for their own features but do *not* want the library resuming after a
reboot can opt out:

```kotlin
val config = NbnConfig.Builder()
    .restartOnBoot(false)
    .build()
NbnClient.init(this, config)
```

`BootReceiver` re-checks the grant at boot: if `ACCESS_BACKGROUND_LOCATION` is missing or was revoked,
it logs a warning and **does not start the service at all**, rather than resuming a scan whose reports
would carry no position.

Background location is a separate, second request made only after foreground location is granted;
`NbnPermissions.getScanPermissions()` intentionally excludes it.

On Android 11+ the permission dialog does not offer "Allow all the time", so direct the user to the
app settings page. A normal runtime request is used on Android 10:

```kotlin
private val requestBackground = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { /* denied is survivable; normal foreground-service scanning still works */ }

// Only after checkScanPermissions() is true and after showing educational UI with a decline option:
if (!NbnPermissions.checkBackgroundLocationPermission(this)) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    } else {
        requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}
```

Justify the request before opening Settings and re-check it in `onResume()` after the user returns.
On API 30+, `packageManager.backgroundPermissionOptionLabel` returns the OS-localized permission
label so the educational UI can match the system wording.

> **Google Play impact.** The default integration does not add background location to the host
> manifest. A host that opts in is responsible for the
> [Location permissions declaration](https://support.google.com/googleplay/android-developer/answer/9799150),
> prominent in-app disclosure, and demonstrating that background location is a core feature.

---

## 4. Initialize the library

Call once in `Application.onCreate()`. The `config` argument is optional — omit it to accept every
default (`scanSource = LIBRARY_SCAN`, `restartOnBoot = true`, `scanMode = LOW_POWER`,
`logLevel = WARN`). The reporting endpoint is **not** a host setting: the library always reports to
`https://api.nanobeaconnetwork.com`. So the minimum is (then call `NbnClient.startScan()` once
permissions are granted — see §6):

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NbnClient.init(this)
    }
}
```

Java callers get the same one-argument overload (`@JvmOverloads`):
`NbnClient.INSTANCE.init(this);`

Calling `init()` again after a successful init is ignored (a warning is logged).

Full config:

```kotlin
val config = NbnConfig.Builder()
    .scanSource(NbnConfig.ScanSource.LIBRARY_SCAN)      // default; HOST_SCAN if your app already scans
    .scanMode(NbnConfig.ScanMode.LOW_POWER)         // LIBRARY_SCAN only (see §7)
    .restartOnBoot(true)                             // default; needs host-granted background location
    .logLevel(NbnConfig.LogLevel.WARN)              // DEBUG enables verbose HTTP logging
    .build()
NbnClient.init(this, config)
```

On init the library automatically fetches an **anonymous token** (bound to the device's ANDROID_ID,
used only for server-side rate limiting) and pulls the latest runtime config from
`GET /api/v1/config`. **You do not manage tokens.**

> In HOST_SCAN mode, `scanMode` is ignored because the library does not own the scanner.
> `logLevel(DEBUG)` turns on OkHttp request/response logging for troubleshooting.

---

## 5. HOST_SCAN mode (for apps that already scan BLE)

Your app runs its own scanner and forwards results to the library, which then never touches BLE
itself. Initialize with `scanSource = HOST_SCAN`:

```kotlin
// Once, e.g. in Application.onCreate() — required before submitScanResult()/submitServiceData().
NbnClient.init(
    context,
    NbnConfig.Builder()
        .scanSource(NbnConfig.ScanSource.HOST_SCAN)
        .build()
)
```

`NbnClient.startScan()` / `stopScan()` are **no-ops** in this mode, and `scanMode` is ignored —
you own the scanner, so you own its settings and its lifecycle.

> ### ⚠️ HOST_SCAN starts no service of its own — background scanning is on you
>
> In LIBRARY_SCAN the library runs its own foreground service, so collection survives backgrounding and can
> optionally resume after reboot. **HOST_SCAN provides none of that.** Scanning lives and dies with
> whatever component
> owns your scanner: start it from an `Activity`/`ViewModel` and it stops the moment your app is
> backgrounded or the process is killed — the library cannot keep it alive for you.
>
> To collect continuously you **must run your scanner inside your own foreground service** (with a
> `location` / `connectedDevice` `foregroundServiceType`, an ongoing notification, and
> `START_STICKY`), and — if you want it back after a reboot — your own `BOOT_COMPLETED` receiver.
> If you'd rather not build and maintain that, use **LIBRARY_SCAN**, which provides the service and optional reboot recovery
> (§6). Reference implementation:
> [`examples/app/src/main/java/com/nanobeaconnetwork/demo/ble/DemoScanService.kt`](examples/app/src/main/java/com/nanobeaconnetwork/demo/ble/DemoScanService.kt).

**You must also include the `0xFC32` `ScanFilter`** — both so the library sees beacons and because
Android 8.1+ delivers **no results at all for an *unfiltered* scan while the screen is off**
(it also cuts callback volume substantially, which saves battery). If your app already scans with
its own filters, **append** the `0xFC32` filter instead of replacing them — filters are
OR-combined, so you keep receiving your own advertisements too.

```kotlin
class MyScanController(private val context: Context) {

    // 0xFC32 = NanoBeaconNetwork beacon Service UUID. Required.
    private val nbnFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(UUID.fromString("0000FC32-0000-1000-8000-00805F9B34FB")))
        .build()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // ...your own business logic on the result, if any...
            NbnClient.submitScanResult(result)              // hand it to the library
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { NbnClient.submitScanResult(it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val scanner = context.getSystemService(BluetoothManager::class.java)
            .adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)  // your choice — see §7
            .setReportDelay(5000)                           // batch = power saving
            .build()

        // If you already scan with your own filters, APPEND nbnFilter — do NOT replace them.
        // Filters are OR-combined, so you keep receiving your own advertisements too.
        scanner.startScan(listOf(nbnFilter), settings, callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        context.getSystemService(BluetoothManager::class.java)
            .adapter.bluetoothLeScanner.stopScan(callback)
    }
}
```

Notes:
- Results that aren't NanoBeaconNetwork beacons (no `0xFC32` service data) are ignored by the library, so it's
  safe to forward everything.
- Alternative feed if you use a cross-platform BLE lib:
  `NbnClient.submitServiceData(serviceDataBytes, rssi, location)` (location may be `null`; the library
  will fetch one itself).
- For background/screen-off reliability (Doze, OEM battery killers), see §7 — it applies to your
  own foreground service exactly as it does to the library's.

---

## 6. LIBRARY_SCAN mode (default)

The library owns everything: scanner + foreground service + notification. This works whether or not
your app also scans BLE for its own purposes — the two scanners run independently (§7).

```kotlin
NbnClient.init(this, NbnConfig.Builder()
    .scanSource(NbnConfig.ScanSource.LIBRARY_SCAN)   // this is the default; shown for clarity
    .scanMode(NbnConfig.ScanMode.LOW_POWER)
    .build())

// After runtime permissions are granted:
NbnClient.startScan()   // starts the library's foreground scan service
// ...
NbnClient.stopScan()
```

The library provides the foreground service and an ongoing notification automatically.

**Reboot recovery is enabled by default (`restartOnBoot = true`), but gated on
`ACCESS_BACKGROUND_LOCATION`.** Normal scans started while an Activity is visible keep running after
the app moves to the background without that permission; only the post-reboot restart needs it,
because the service then starts with the app in the background. So to actually get reboot recovery the
host declares and obtains the permission as described in §3 — no library setting required. Recovery also
occurs only if scanning was active before shutdown, and like any `START_STICKY` foreground service it
is best-effort: a user "force stop" or aggressive OEM battery policies can still prevent it. When the
permission is absent the library skips recovery entirely (logging at `INFO` if the permission was never
declared, and at `WARN` if it was declared but not granted) rather than resuming without location.

---

## 7. Scan mode, background, and Doze — how to choose

**The scan mode does not decide whether you can scan in the background or with the screen off.**
It only trades battery for detection speed:

| `ScanSettings` mode | Radio duty cycle | Character |
|---|---|---|
| `SCAN_MODE_LOW_POWER` | ~10% | most battery-friendly; slower to detect, may miss brief ads |
| `SCAN_MODE_BALANCED` | ~25% | middle ground |
| `SCAN_MODE_LOW_LATENCY` | ~100% | fastest/most complete; heavy battery drain |

What actually enables **background + screen-off** scanning:

1. **A foreground service** to keep the process alive (yours in HOST_SCAN; library's in LIBRARY_SCAN).
2. **A `ScanFilter`** — on Android 8.1+, an unfiltered scan returns **nothing** while the screen
   is off. The `0xFC32` filter satisfies this.
3. **Doze / battery optimization** — in deep Doze the system defers BLE scans to maintenance
   windows regardless of scan mode. For continuous collection while the phone sleeps, guide the
   user to exempt your app from battery optimization
   (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), and be aware that some OEMs (Xiaomi, Huawei,
   Samsung, …) kill background apps more aggressively and need their own allow-listing.

So `LOW_POWER` is the **recommended default for long-running background collection** (battery),
**not a requirement**. Use `BALANCED`/`LOW_LATENCY` if you need faster detection and can accept
the battery cost.

---

## 8. Observing status (optional)

All of these are optional Kotlin `Flow`s — ignore them entirely if you just want silent
collection.

```kotlin
lifecycleScope.launch { NbnClient.scanEvents.collect { e -> Log.d("NanoBeaconNetwork", "${e.eidHex} ${e.rssi}") } }
lifecycleScope.launch { NbnClient.reportStats.collect { s -> updateUi(s.pendingCount, s.rateLimited) } }
```

| Flow | Type | Meaning |
|---|---|---|
| `scanState` | `StateFlow<ScanState>` | `isScanning`, `bleEnabled`, `gpsEnabled`, `hasPermissions` (driven by the library only in LIBRARY_SCAN mode) |
| `scanEvents` | `SharedFlow<ScanEvent>` | one per scanned beacon: `eidHex`, `rssi`, `timestamp`, `reported` (false = de-duped) |
| `reportStats` | `StateFlow<ReportStats>` | scan/report counts, pending rows, terminal failure/expiry/invalid/queue-full counters, `throttledCount` (deliberately skipped, not a failure), `successRate`, `rateLimited` |
| `scanLogs` | `StateFlow<List<ScanLogEntry>>` | last 100 log lines (time, EID prefix, rssi, status) |

### Report queue behavior

- With a BLE address, the library identifies the physical source with an install-keyed HMAC of the
  normalized MAC address. The digest stays local. `submitServiceData(..., bleAddress = null)` falls
  back to EID, so an EID rotation cannot be linked to the same physical beacon.
- Scan deduplication uses `(physical source, payload)`: an exact retransmission is suppressed within
  the configured window, but a changed payload from the same MAC always reaches the latest-data queue.
- Only the newest unsent observation is kept per source. An already-sent HTTP request remains an
  immutable `in_flight` row; a newer observation uses a separate `pending_latest` row.
- A matching `202 {"status":"accepted","batch_id":"..."}` means durable server admission only,
  not tag validity. It is the only response that removes the corresponding in-flight rows.
- Ordinary attempts occur immediately and approximately 1, 3, 7, 15, and 30 minutes after the
  observation was created, with up to ±20% jitter. Six ordinary failures or age one hour removes
  the observation. `429/503` do not consume an attempt but cannot extend the one-hour lifetime.
- Queue capacity is 50,000 rows or an estimated 50 MiB. A new source is rejected with `QueueFull`
  when full; existing rows are not silently evicted.
- **The queue does not survive a process restart.** Rows still queued when the process dies are
  discarded on the next launch instead of uploaded, and counted in `expiredCount`. This is
  deliberate: the scan log and the scan/report counters live in memory and reset with the process,
  so uploading rows the new process never scanned would report more observations than it scanned.
  The practical cost is bounded — an observation is only retained for five minutes anyway — but it
  does mean observations pending at the moment the app is killed never reach the server.
  Within a single process the queue behaves exactly as described above, retrying across network
  failures and app backgrounding.

---

## 9. Runtime configuration & lifecycle

- **Change scan mode:** `NbnClient.setScanMode(mode)` — applies the next time scanning starts, so
  `stopScan()` + `startScan()` to apply it to an active scan.
- **Change reboot resume:** `NbnClient.setRestartOnBoot(enable)`
- **Release resources:** `NbnClient.shutdown()`
- **Flow-control params** (dedup window, report interval, batch threshold) are pushed by the
  server (on the anonymous-token response and on `GET /api/v1/config`) — you don't set
  them. Sensible defaults apply until the first fetch.

### Privacy and backup

Review [PRIVACY.md](PRIVACY.md) before release and disclose the library's collection in your app's
privacy policy and store declarations. Because Android Keystore keys are not portable, exclude
`nbn_prefs.xml` and `nbn.db` from cloud backup and device transfer in the host app's
backup rules. The
sample app contains working exclusions.

---

## 10. ProGuard / R8

No custom keep rules are required. The release checks include a minified Java consumer app.


---

## 11. Java usage

The core methods are plain Java-callable:

```java
NbnClient.INSTANCE.init(this, new NbnConfig.Builder().build());
NbnClient.INSTANCE.submitScanResult(result);   // HOST_SCAN feed
```

(The observability `Flow`s are Kotlin-first; from Java, prefer polling `reportStats.getValue()`
or collecting via `kotlinx-coroutines-jdk`.)

---

## 12. Troubleshooting / FAQ

- **No beacons detected while the screen is off** → make sure your scan uses the `0xFC32`
  `ScanFilter` and runs inside a foreground service (§5, §7).
- **Detection stops after the phone sleeps a while** → Doze. Ask the user to disable battery
  optimization for your app; check OEM-specific autostart/background settings.
- **`startScan()` seems to do nothing** → expected in HOST_SCAN mode; feed via
  `submitScanResult()` instead (§5).
- **Reports not uploading** → check `reportStats.rateLimited` and network. A latest observation gets
  at most six ordinary send attempts and expires after one hour. HTTP `429/503` obey
  `Retry-After` without consuming an ordinary attempt. Inspect `failedCount`, `expiredCount`,
  `invalidCount`, and `queueFullCount` for terminal outcomes.
- **Duplicate-looking beacons not all reported** → by design: the same EID within the server's
  dedup window is reported once (`ScanEvent.reported = false` marks the de-duped ones).
- **A beacon is only reported once every few minutes** → by design, and this is usually what you
  are seeing rather than a bug. After a source is durably accepted, further sightings of that same
  BLE address (or EID, when no address is available) are dropped for
  `source_min_interval_seconds` (server-configured, default 300). They are counted in
  `throttledCount` — a deliberate skip, so keep it out of any error total you display. Note this is
  per BLE address, not per physical device: a beacon that rotates its address gets a fresh budget
  per address.

---

## 13. Minimal checklist

- [ ] Add the `.aar` (+ transitive deps) to `app/build.gradle.kts`.
- [ ] `NbnClient.init(this)` in `Application.onCreate()` (pass an `NbnConfig` only to change a default).
- [ ] Request scan permissions (`NbnPermissions.getScanPermissions()`), then call
      `NbnClient.startScan()` once they're granted.
- [ ] Optional reboot recovery: nothing to enable — `restartOnBoot` is on by default; just declare and
      request `ACCESS_BACKGROUND_LOCATION` if you want it (§3), or `.restartOnBoot(false)` to opt out.
- [ ] HOST_SCAN: scan with the `0xFC32` filter and call `NbnClient.submitScanResult(result)`;
      run it in your own foreground service for background use.
- [ ] For background-while-sleeping reliability, guide users to disable battery optimization.

---

## 14. API reference

Everything below is the complete host-facing surface. Package `com.nanobeaconnetwork`
(models in `com.nanobeaconnetwork.model`). `NbnClient` and `NbnPermissions` are Kotlin `object`s —
Java callers use `NbnClient.INSTANCE` / `NbnPermissions.INSTANCE`.

### NbnClient

| Member | Notes |
|---|---|
| `init(context: Context)` | Accept all defaults. Call once from `Application.onCreate()`; needs no permissions. |
| `init(context: Context, config: NbnConfig)` | Same, with an explicit config. A second `init()` is ignored (logs a warning). |
| `startScan()` | LIBRARY_SCAN only. Idempotent; does nothing if the scan permissions are missing. No-op in HOST_SCAN. |
| `stopScan()` | LIBRARY_SCAN only. Stops the foreground scan service. |
| `submitScanResult(result: ScanResult)` | **HOST_SCAN only.** Feed one result from your own scanner. |
| `submitServiceData(serviceData: ByteArray, rssi: Int, location: Location? = null, bleAddress: String? = null)` | **HOST_SCAN only.** Feed the raw `0xFC32` service data (for cross-platform BLE libraries). `location = null` lets the library fetch one. |
| `setScanMode(mode: NbnConfig.ScanMode)` | Applies the next time scanning starts — `stopScan()` + `startScan()` to apply it to an active scan. |
| `setRestartOnBoot(enable: Boolean)` | Opt out of (or back into) reboot recovery at runtime. |
| `shutdown()` | Stops scanning and releases resources. Does not erase queued reports, but anything still queued is discarded on the next process start rather than uploaded. |

Observable state (all cold-safe to collect at any time):

| Flow | Type |
|---|---|
| `scanState` | `StateFlow<ScanState>` |
| `scanEvents` | `SharedFlow<ScanEvent>` |
| `reportStats` | `StateFlow<ReportStats>` |
| `scanLogs` | `StateFlow<List<ScanLogEntry>>` |

### NbnConfig / NbnConfig.Builder

Build with `NbnConfig.Builder()…build()`. Read-only properties `scanSource`, `scanMode`,
`restartOnBoot`, `logLevel` are exposed on the built instance.

| Builder method | Default |
|---|---|
| `scanSource(source: ScanSource)` | `LIBRARY_SCAN` |
| `scanMode(mode: ScanMode)` | `LOW_POWER` (LIBRARY_SCAN only — see §7) |
| `restartOnBoot(enable: Boolean)` | `true`, but effective only with host-granted `ACCESS_BACKGROUND_LOCATION` (§3) |
| `logLevel(level: LogLevel)` | `WARN` (`DEBUG` enables OkHttp request/response logging) |
| `build()` | — |

Enums: `ScanSource { LIBRARY_SCAN, HOST_SCAN }` ·
`ScanMode { LOW_POWER, BALANCED, LOW_LATENCY }` ·
`LogLevel { NONE, ERROR, WARN, INFO, DEBUG }`

### NbnPermissions

| Member | Notes |
|---|---|
| `getScanPermissions(): Array<String>` | The set to request for the running OS version. Feed straight to `launch(...)`. |
| `checkScanPermissions(context): Boolean` | Whether scanning can run (`ACCESS_FINE_LOCATION`, plus `BLUETOOTH_SCAN` on API 31+). Optional — `startScan()` checks internally. |
| `checkBackgroundLocationPermission(context): Boolean` | Whether `ACCESS_BACKGROUND_LOCATION` is granted. Always `false` unless the host declares it (§3). |

### Models

| Data class | Fields |
|---|---|
| `ScanState` | `isScanning`, `bleEnabled`, `gpsEnabled`, `hasPermissions` |
| `ScanEvent` | `eidHex`, `rssi`, `timestamp`, `reported` (`false` = de-duplicated) |
| `ScanLogEntry` | `time`, `eidPrefix`, `rssi`, `status` (`Queued` / `Accepted` / `Skipped` / `Duplicate` / `QueueFull` / `Invalid`) |
| `ReportStats` | `todayScanCount`, `todayReportCount`, `pendingCount`, `failedCount`, `expiredCount`, `invalidCount`, `queueFullCount`, `throttledCount`, `successRate`, `rateLimited` |

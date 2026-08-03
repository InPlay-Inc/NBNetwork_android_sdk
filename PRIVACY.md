# Privacy and data disclosure

NanoBeaconNetwork is a data-collection library. The host application controls when scanning starts,
which server receives reports, and how users are informed or asked for consent.

## Data processed by the library

| Data | Destination and use |
| --- | --- |
| Android ID | Sent to the configured server to obtain and rate-limit an anonymous access token. |
| Beacon service data / EID | Sent to the configured server so the beacon can be resolved. |
| RSSI | Sent with each beacon observation. |
| Precise location | Sent as latitude and longitude with each observation when available; otherwise `0,0` is sent. |
| Observation time | Sent with each observation in UTC. |

The BLE device address may be used in memory as a local deduplication key. It is not placed in
the report payload and is not sent by the library.

## Purposes

- Android ID is used for anonymous token issuance and server-side rate limiting.
- Beacon observations are used to resolve registered beacons and provide network reports.

## Local storage and retention

- Anonymous token, runtime configuration, and the database passphrase use encrypted preferences.
- Pending observations use a SQLCipher-encrypted Room database.
- The local queue keeps only the newest unsent observation per physical BLE source. A record expires
  after one hour or six ordinary failed attempts. The queue is capped at 50,000 rows or an estimated
  50 MiB; when full, a new source returns `QueueFull` instead of evicting existing data.

`nbn_prefs.xml` contains Keystore-backed encrypted values, including the key for
`nbn.db`. Android Keystore keys are not
portable, so restoring that file onto another installation can make the values unreadable. Add
exclusions for both files in cloud-backup and device-transfer rules. The sample app shows
the required XML.

## Host application responsibilities

Before shipping the library, the host application should:

- Provide an accurate privacy notice and obtain consent where required.
- Request the runtime Bluetooth and foreground-location permissions in context.
- Note that the library does not declare or request `POST_NOTIFICATIONS`. The scan foreground service
  still posts its ongoing notification, but on Android 13+ it is not shown in the notification drawer
  unless the host declares and obtains that permission. Hosts that want users to see an in-progress
  indicator should add it.
- The default library manifest does not declare `ACCESS_BACKGROUND_LOCATION`, so the default integration
  collects no location in the background beyond the library foreground service, which can keep scanning
  after the UI is backgrounded.
- `restartOnBoot` is enabled by default, but resuming after a device reboot takes effect only if the
  host declares and obtains `ACCESS_BACKGROUND_LOCATION`. A host that adds that permission must also
  complete the Google Play Location permissions declaration and show a prominent in-app disclosure
  (see INTEGRATION.md §3). Hosts that hold the permission for other features but do not want the library
  resuming after a reboot should set `restartOnBoot(false)`.
- Complete the applicable app-store data-safety or privacy declarations.
- Ensure that the configured server's retention, access, and deletion policies are documented.

The library does not implement a consent UI because the host application owns the user experience and
the legal basis for collection.

## Network and logs

Production endpoints should use HTTPS. Debug HTTP logging redacts the `Authorization` header, but
debug builds and logs should still be treated as sensitive and excluded from production support bundles.

## Deletion

`NbnClient.shutdown()` stops library activity but does not erase stored records. To delete local library
data, clear the host application's data or uninstall it. Server-side deletion and retention are
deployment policies of the configured server and must be handled by the service operator.

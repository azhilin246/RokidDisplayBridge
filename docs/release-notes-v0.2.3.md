# Display Bridge v0.2.3

Android 16 foreground-service startup hotfix.

## Fixed

- Restored the normal `CHANGE_NETWORK_STATE` permission used by Android as a prerequisite for starting a `connectedDevice` foreground service before USB device or accessory permission exists.
- Added manifest regression coverage for the prerequisite.
- Verified on Pixel 10 user 0 that the release-signed companion cold-starts without the v0.2.2 fatal `SecurityException` and remains in the foreground.
- Installed and hash-verified the matching release-signed glasses APK on the target RG_glasses device.

The application does not use this permission to change routing, Wi-Fi, Bluetooth, or the user's audio route.

## Artifacts

| Artifact | SHA-256 |
|---|---|
| `display-bridge-companion-v0.2.3-release.apk` | `426E74465CA79651C320A91162B1390BC66742FA23D8CC56E9A4B2B829F712E5` |
| `display-bridge-glasses-v0.2.3-release.apk` | `305D3CC92AC8585A26D715961C68E39F2C2160AA9E19A7F63E67475982750C0A` |

Both APKs use the existing Display Bridge release certificate. Updating from v0.2.2 works in place.

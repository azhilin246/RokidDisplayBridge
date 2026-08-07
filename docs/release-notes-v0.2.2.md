# Display Bridge v0.2.2

> Superseded by v0.2.3: removing `CHANGE_NETWORK_STATE` caused Android 16 to reject the pre-permission `connectedDevice` foreground-service startup.

This release prepares the MVP repository for later public access while preserving the release-signing certificate introduced in v0.2.1.

## Changes

- Changed the AOA manufacturer to `Zhilin`.
- Changed the AOA URI to `https://github.com/azhilin246/RokidDisplayBridge`.
- Removed the unused `CHANGE_NETWORK_STATE` permission.
- Removed unused FileProviders and their path resources from both APKs.
- Added PolyForm Noncommercial 1.0.0 licensing, a required copyright notice, privacy/security documentation, contribution rules, and Dependabot configuration.
- Removed the five legacy project-name references from OpenSpec and repaired duplicate task numbering without changing completion status.

## Upgrade note

The AOA identity changed. Update the companion and glasses APKs together; a mixed v0.2.1/v0.2.2 pair will not match the same accessory manufacturer filter.

Both APKs retain the existing dedicated release certificate, so v0.2.2 can update a release-signed v0.2.1 installation in place.

## Artifacts

| Artifact | SHA-256 |
|---|---|
| `display-bridge-companion-v0.2.2-release.apk` | `BE44DE9152361C248EFDEA52BCFDD0353941F38A08393F04B63960A2F234D1A4` |
| `display-bridge-glasses-v0.2.2-release.apk` | `A4D4B7904F27289A8C4DE529884A0A69EEE91FC76592C6E2B7FFD1F63A531C08` |

Certificate SHA-256: `2C:AA:FA:50:01:48:27:FB:3B:A8:4C:E7:A4:0A:F4:2B:F7:5B:33:22:D8:B6:9E:97:BE:B1:29:49:F2:E5:F5:EC`.

## Verification

- 84 unit tests passed: protocol 13, companion 39, glasses 32.
- Both `lintRelease` tasks passed.
- Both signed `assembleRelease` tasks passed.
- APK Signature Scheme v2 and the dedicated release certificate were verified for both artifacts.
- Both OpenSpec changes pass strict validation.
- The release keystore and ignored signing properties were backed up and verified as Bitwarden Secure Note attachments through CLI.
- `git diff --check` passes.

Long-run, full reconnect/mode-switch, and complete physical gesture acceptance remain explicitly tracked as unfinished OpenSpec tasks.

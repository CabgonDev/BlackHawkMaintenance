# ADR 0003: Release signing recovery and compromise migration

- Status: Accepted for App v5.0.2
- Date: 2026-11-19

## Context

App v5.0.2 keeps the release certificate used by v5.0.1:

- SHA-256: `024db26301133f4ac627a26f89169d11d0000ca535e631e1d9965ee3c8ec1b28`
- SHA-1: `92f6f9250403786e4a84071ee43cecb574f41f67`

The APK is distributed by controlled sideload/OTA channels, so a replacement
private key cannot be assumed to update an existing installation. The signing
inputs are stored in GitHub Actions `protected-release` and in a Windows
DPAPI CurrentUser recovery backup outside the repository:

`C:\Users\t-he-\OneDrive\Desktop\BHM Codex\backups\final-v5.0.2-recovery-20261119-112052`

The backup contains only DPAPI ciphertext, metadata, checksums and a recovery
script. A same-user decrypt round-trip verified the original SHA-256 hashes:

- `keystore.properties`: `e8e17190ef38876f17634dad8398f793803a94d25f1ba99be02b433861ba8737`
- `bhm.jks`: `ba0c9eb959a74e9d21bbfb8efc87ce26f26825b1c641ef9929f0c341677439b3`

## Decision

No signing rotation is performed while the current key is not suspected of
compromise. If compromise is suspected:

1. Disable the release workflow and remove/rotate its protected secrets. Keep
   the last known-good APK and the current certificate available for rollback.
2. Preserve user data and stop distribution until the incident owner approves
   a migration build. Do not replace the key and assume Android will accept an
   update.
3. For Android 9/API 28 and newer, generate a signing certificate lineage
   with the Android SDK `apksigner rotate` command, using the old and new
   signers and explicitly setting `--set-installed-data true`. Sign the
   migration APK with that lineage and verify it with `apksigner` on API 28,
   the current API, and a representative 5.0.1 installation.
4. Publish the migration only after direct-update, clean-install, data
   preservation and rollback tests pass. If lineage support cannot be used for
   the affected install base, provide an explicit export/reinstall migration
   and keep the old-signed rollback APK available.
5. Revoke the compromised key only after the migration adoption gate is met;
   record the affected versions, APK hashes, certificate lineage and date.

Reference command shape:

```text
apksigner rotate --out lineage.bin --old-signer --ks old.jks --new-signer --ks new.jks --set-installed-data true
apksigner sign --ks new.jks --lineage lineage.bin --rotation-min-sdk-version 28 app-release.apk
apksigner verify --verbose --print-certs app-release.apk
```

Passwords and keystores are supplied through protected files or environment
inputs; they are never placed in command arguments, logs or the repository.

## Consequences

- Current installations retain certificate continuity for the normal v5.0.2
  release.
- A suspected compromise has a documented stop, recovery and lineage path.
- A new key is not activated until Android compatibility and data preservation
  are proven on the supported install paths.
- The DPAPI backup is recoverable only by the same Windows user; a second
  reviewer/escrow owner is still an operational control to add.

## Rollback

Disable the migration workflow, restore the last known-good protected secrets,
and distribute the APK signed with the current certificate. Do not delete the
current key or rewrite Git history automatically.

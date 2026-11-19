# ADR 0002: CI signing environment and historical Google services configuration

- Status: Accepted for App v5.0.2
- Date: 2026-11-18

## Context

The repository distributes signed APKs outside Google Play. Release signing
must use the existing certificate, while the signing keystore and properties
must stay outside the checkout. The Git history also contains older copies of
`app/google-services.json` from commits before the audit baseline.

## Decision

GitHub Actions uses a protected environment named `protected-release`. The
environment must restrict deployments to `master` and require the designated
release reviewers before secrets are made available. `CabgonDev` is the current
custodian and required reviewer; a second contingency reviewer is not yet
available and is tracked as an operational follow-up. The workflow receives
these environment secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON_BASE64`

The workflow is manual, checks out the fixed `master` ref, and materializes
the keystore and a temporary `keystore.properties` under `RUNNER_TEMP`, then
passes that file through `releaseSigningPropertiesFile`, verifies the public
certificate fingerprint, and removes the temporary directory in an `always()`
cleanup step. The protected Google services configuration is decoded into the
ignored `app/google-services.json` only for the build and is removed during the
same cleanup. It has read-only repository permissions.

The historical `app/google-services.json` objects remain in Git history. The
historical Android key was restricted to package `com.cabgon.blackhawk` and
the release SHA-1 fingerprint
`92f6f9250403786e4a84071ee43cecb574f41f67`. A new Android key resource
`projects/1071542595622/locations/global/keys/4ce05da4-a559-47ef-b95c-125ed7629702`
was created with the same restrictions, and the active ignored configuration
and protected CI secret now use it. The previous key remains restricted for
5.0.1 rollback until migration adoption is confirmed. History rewriting is
not automatic and is outside this delivery.

Signing recovery is documented in
`docs/adr/0003-signing-recovery-and-compromise-migration.md`. The DPAPI
recovery backup is outside the repository and contains no plaintext signing
files; its decrypt round-trip and SHA-256 checks are recorded in the v5.0.2
checklist.

## Consequences

- CI can build a release without placing signing material in the checkout.
- CI requires the protected Google services configuration because the Gradle
  Google Services plugin cannot build without it; the file is removed after the
  job.
- A release workflow run fails when any protected input is absent.
- The workflow does not publish an APK or print signing values.
- The previous restricted Android API key remains available for 5.0.1 rollback
  until the migration adoption gate is met; it must not be deleted solely
  because the new key is active.

## Rollback

Disable the workflow and restore the prior protected release procedure. Keep
the signing key and certificate unchanged. Do not rewrite `master` as an
automatic rollback.

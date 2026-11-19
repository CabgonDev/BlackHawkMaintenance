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
release reviewers before secrets are made available. The workflow receives
these environment secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The workflow is manual, checks out the fixed `master` ref, and materializes
the keystore and a temporary `keystore.properties` under `RUNNER_TEMP`, then
passes that file through `releaseSigningPropertiesFile`, verifies the public
certificate fingerprint, and removes the temporary directory in an `always()`
cleanup step. It has read-only repository permissions.

The historical `app/google-services.json` objects remain in Git history. The
mitigation is to preserve commit history, inspect the Firebase/Google Cloud
project, restrict the API key to the package and signing fingerprints, rotate
it when the cloud owner requires rotation, and record dated evidence. History
rewriting is not automatic and is outside this delivery.

## Consequences

- CI can build a release without placing signing material in the checkout.
- A release workflow run fails when any protected input is absent.
- The workflow does not publish an APK or print signing values.
- Cloud console access is required to complete API-key restriction/rotation and
  record the evidence for the remaining v5.0.2 checklist gates.

## Rollback

Disable the workflow and restore the prior protected release procedure. Keep
the signing key and certificate unchanged. Do not rewrite `master` as an
automatic rollback.

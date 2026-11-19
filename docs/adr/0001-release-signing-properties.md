# ADR 0001: Protected release signing properties

- Status: Accepted for App v5.0.2
- Date: 2026-11-18

## Context

BlackHawk Maintenance is distributed by sideloaded or cloud-controlled APK
channels. Android updates must continue to use the existing release
certificate. The current project keeps `keystore.properties` and the JKS file
outside Git, but the Gradle script previously allowed a release configuration
with missing signing properties.

## Decision

Release packaging requires all four signing properties:

- `storeFile`
- `storePassword`
- `keyAlias`
- `keyPassword`

The properties file is resolved from the protected Gradle property
`releaseSigningPropertiesFile`, then the `RELEASE_SIGNING_PROPERTIES_FILE`
environment variable, and finally the local ignored `keystore.properties`
fallback. The file and keystore must exist for release packaging tasks. The
validation reports only missing property names and never prints property
values.

Debug and non-packaging configuration tasks remain usable without release
secrets. CI must mount the properties file or provide an equivalent protected
secret and must not commit it or copy it into a tracked project path.

The release certificate continuity fingerprint recorded in the audit checklist
remains the required certificate for updates from version 5.0.1.

## Consequences

- A release build fails early when its protected signing input is absent or
  incomplete.
- Local debug work does not require release credentials.
- CI is responsible for supplying and protecting the external properties file
  and keystore.
- Certificate verification, installation, update preservation, and historical
  credential review remain separate v5.0.2 checklist gates.

## Rollback

Restore the previous Gradle signing configuration and keep the existing signing
key protected. Do not generate a replacement key or rewrite Git history as an
automatic rollback.

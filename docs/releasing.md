# Releasing the shared libraries

InfoProcess hosts shared library modules (for example `plugin-support`,
`jvm-process-report` and `plugin-test-fixtures`) that the cdsap Gradle plugins
(InfoGradleProcess, InfoKotlinProcess, GCReport, InfoTestProcess) consume from
Maven Central as `io.github.cdsap:<module-name>`.

The repository has two intentionally separate version identifiers:

| Identifier | Location | Meaning |
|---|---|---|
| `sharedLibsVersion` | `gradle.properties` | Maven Central version shared by every module that applies `io.github.cdsap.infoprocess.shared-library` |
| `infoProcessVersion` | Gradle property (defaults to `1.0.0-SNAPSHOT`) | Version of the unified `:plugin`; unrelated to the shared libraries |

`:plugin`, `:core` and the `collector-*` modules are not published to Maven
Central. Only modules that opt into the shared library convention get Central
publication tasks:

```kotlin
plugins { id("io.github.cdsap.infoprocess.shared-library") }
sharedLibrary { artifactId("plugin-support") }
```

The artifactId must equal the module name. The convention applies Kotlin JVM
with a Java 17 toolchain, `explicitApi()`, the lowest Kotlin language/API version
the compiler accepts (2.0), Kotlin ABI validation, and the vanniktech Maven
Central publication with signing and POM metadata. `check` runs
`checkKotlinAbi` and `verifyJavaTargetMetadata` (the published Gradle Module
Metadata must declare `org.gradle.jvm.version` 17).

Maven Central versions are immutable; publish a new version when a released
artifact needs correction. All shared library modules are released together
with the same version.

## build-observability-core 0.0.7

`jvm-process-report` depends on `io.github.cdsap:build-observability-core:0.0.7`,
the first core version with the `GbosDevelocity` publisher. Until 0.0.7 is on
Maven Central, the build cannot resolve it: every build that configures
`:jvm-process-report` dependencies (including `./gradlew check` locally and in
CI) fails with "Could not find io.github.cdsap:build-observability-core:0.0.7".

For local work, point the `gbosCoreBuild` Gradle property at a checkout of
[build-observability-schema](https://github.com/cdsap/build-observability-schema)
that contains the publisher. `settings.gradle.kts` then includes that build and
substitutes its `:core` project for the Maven Central module:

```bash
./gradlew check -PgbosCoreBuild=../build-observability-schema
```

To avoid passing it on every invocation, set it outside the repository, for
example `gbosCoreBuild=/path/to/build-observability-schema` in
`~/.gradle/gradle.properties`. Never commit the property to this repository's
`gradle.properties`: CI and releases must resolve the published core.

Release order:

1. Release build-observability-core 0.0.7 to Maven Central and wait until it
   resolves from Central (`--refresh-dependencies`, no `-PgbosCoreBuild`).
2. Only then can CI build without the property, and only then may the shared
   libraries be released: their POMs reference core 0.0.7, so a shared-library
   release before core 0.0.7 is on Central would publish unresolvable artifacts.
   Run the release-preparation `./gradlew check` without `-PgbosCoreBuild`.

## ABI dumps

Each shared library module commits its public ABI in `<module>/api/`. `check`
fails when the compiled ABI differs from the dump. After an intentional public
API change run:

```bash
./gradlew updateKotlinAbi
```

and commit the updated `api/` files with the change. Before a release, review
`git diff <previous-release-tag> -- '*/api/*'` to choose the version bump:
removed or changed declarations are breaking changes.

## Release process

1. Prepare a dedicated release-preparation change:
   - Confirm every external dependency of the libraries resolves from Maven
     Central, in particular build-observability-core 0.0.7 (see above). Do not
     set `gbosCoreBuild` for any release step.
   - Set `sharedLibsVersion` in `gradle.properties` to the release version, for
     example `0.1.0` (drop `-SNAPSHOT`).
   - Run `./gradlew updateKotlinAbi` and confirm no `api/` file changes; any
     change means the ABI was not recorded with the code change that caused it.
   - Run `./gradlew check`.
   - Run the combined-plugins suite on each Gradle lane, as CI does:
     `./gradlew :integration-tests:test -Dintegration.gradleVersion=<8.2|8.14.3|9.7.1>`.
     It publishes the libraries and the fixture plugins to `integration-tests/build/local-repo` first.
2. Merge the release-preparation change to `main` and release from a clean
   checkout of that commit.
3. Configure the Central Portal credentials and signing key locally (see
   below).
4. Publish and release directly through the Central Portal. Run from the root
   so every shared library module is published in the same invocation;
   `:plugin` and `:core` have no Central tasks and are not affected:

   ```bash
   set -a; . "${INFOPROCESS_RELEASE_ENV:-$HOME/.infoprocess-release/env}"; set +a
   ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache \
     -PmavenCentralUsername="$USER_NAME" \
     -PmavenCentralPassword="$central_password" \
     -Psigning.keyId="$key_id" \
     -Psigning.password="$signing_password" \
     -Psigning.secretKeyRingFile="${INFOPROCESS_SIGNING_KEY_FILE:-$HOME/.infoprocess-release/secring.gpg}"
   ```

5. Wait for the Central Portal deployment to reach `PUBLISHED` for every
   `io.github.cdsap:<module-name>` artifact. Do not tag before all of them are
   confirmed published.
6. Create and push an annotated tag. Use the `libs-v` prefix so shared library
   tags stay distinct from unified plugin releases:

   ```bash
   git tag -a libs-v0.1.0 -m "Release InfoProcess shared libraries 0.1.0"
   git push origin libs-v0.1.0
   ```

7. Bump `sharedLibsVersion` to the next `-SNAPSHOT` in a follow-up change.

## Preflight before a plugin release depends on a new version

Before any consuming plugin release (InfoGradleProcess, InfoKotlinProcess,
GCReport, InfoTestProcess) depends on a new shared library version, prove the
version resolves from Maven Central itself, not from a local cache:

1. In the consuming plugin repository, bump the version in
   `gradle/libs.versions.toml`.
2. Make sure no `mavenLocal()` repository is declared anywhere in the build
   (`settings.gradle.kts`, `build.gradle.kts`, init scripts in
   `~/.gradle/init.d`):

   ```bash
   rg -n "mavenLocal" --glob '*.gradle.kts' --glob '*.gradle' . ~/.gradle/init.d
   ```

3. Resolve with refreshed dependencies and confirm the exact version:

   ```bash
   ./gradlew :plugin:dependencyInsight --configuration runtimeClasspath \
     --dependency io.github.cdsap --refresh-dependencies
   ./gradlew check --refresh-dependencies
   ```

Only release the consuming plugin after this resolves the new version from
Maven Central.

## Local release credentials

The release command above reads `~/.infoprocess-release/env` by default. Set
`INFOPROCESS_RELEASE_ENV` to use another file. It must define these names,
matching build-observability-schema and ProjectGenerator:

- `USER_NAME`: Central Portal user-token username.
- `central_password`: Central Portal user-token password.
- `key_id`: signing key ID.
- `signing_password`: signing key passphrase.

The signing key is read from `~/.infoprocess-release/secring.gpg` by default.
Set `INFOPROCESS_SIGNING_KEY_FILE` to use another file. Keep both files outside
the repository and never commit their contents.

`publishToMavenLocal` remains available for consumer development; pass the same
signing properties if Gradle asks for a signatory. Remove `mavenLocal()` from
the consumer again before running the preflight above.

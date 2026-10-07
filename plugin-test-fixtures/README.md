# plugin-test-fixtures

`io.github.cdsap:plugin-test-fixtures` holds the TestKit helpers shared by the cdsap Gradle plugins' tests. Everything
is plain functions and classes that return files and `BuildResult`s, with no JUnit rules or extensions, so JUnit 4
and JUnit 5 suites can both use it.

- `SettingsScript` and `BuildScript` write Groovy `settings.gradle` and `build.gradle` files in the block order Gradle
  requires. `localMavenRepository(dir)` is the local-Maven-repository convention: the directory is searched first, then
  the Plugin Portal and Maven Central.
- `ConfigurationCache.storeAndReuse(runner, ...)` and `IsolatedProjects.storeAndReuse(runner, ...)` run a build twice
  and check that the first run stored a configuration cache entry and the second reused it.
- `FakeDevelocityPlugin` and `FakeGradleEnterprisePlugin` are offline fakes of the Develocity and legacy Gradle
  Enterprise plugins. They print `SCAN-VALUE <name>=<value>` and `SCAN-TAG <tag>` lines, run `buildFinished` actions
  at the end of the build (also on configuration cache reuse, and with Isolated Projects on Gradle 8.8 and later), and
  add their extension to the root project. `FakeDevelocity.scanValues(output)` and `scanTags(output)` read the lines
  back. Set the Gradle property `plugintest.fakeDevelocity.quiet=true` (`FakeDevelocity.QUIET_PROPERTY`) to drop
  their "applied" line from the output.
- `PluginUnderTest.classpath()` reads the plugin-under-test classpath that `withPluginClasspath()` uses.
  `PluginUnderTest.withFakeDevelocity(dir)` returns it with this library's jar and the Develocity API jar first and any
  real Develocity or Gradle Enterprise plugin jar removed, ready for `withPluginClasspath(...)`.
- `PluginSupportJar.replaceIn(classpath, version, dir)` and `reversioned(jar, version, dir)` write plugin-support
  copies that report another version, for testing a plugin's `PluginSupportVersion.requireCompatible` call.

## Dependencies

```kotlin
dependencies {
    testImplementation("io.github.cdsap:plugin-test-fixtures:<version>")
    testImplementation(gradleTestKit())
    // The fakes implement the real Develocity API interfaces. 3.19.2 is the last line that also ships the legacy
    // Gradle Enterprise API.
    testImplementation("com.gradle:develocity-gradle-plugin:3.19.2")
}
```

The library declares the Gradle API, TestKit and the Develocity plugin as `compileOnly`, so it never forces a Gradle
or Develocity version on the consumer. Add TestKit and the Develocity plugin to the test classpath yourself, as above.
The fakes run inside the TestKit build, which on Gradle 8.2 means Gradle's embedded Kotlin 1.8.20 standard library,
so they only use standard library APIs available in 1.8.

## Applying the fake Develocity plugin

Pass `FakeDevelocity.develocityApiJar(dir)` to one of these:

- **Plugins resolved from a Maven repository.** Publish this library to the same local repository, then call
  `SettingsScript.fakeDevelocity(version, apiJar)` and `plugin(FakeDevelocity.PLUGIN_ID)`. A `pluginManagement`
  resolution rule maps `com.gradle.develocity` and `com.gradle.enterprise` to this library, and the API jar goes on
  the settings classpath.
- **`GradleRunner.withPluginClasspath`.** Pass `PluginUnderTest.withFakeDevelocity(dir)`, then request
  `id 'com.gradle.develocity'` without a version.
- **Develocity injected by an init script**, which the plugins under test cannot see:
  `FakeDevelocity.writeInitScript(file, apiJar)`, then run with `--init-script`.

The fakes' plugin descriptors use the real plugin ids, because Gradle looks a plugin up under the requested id in the
module a resolution rule selects. The API jar is a copy of the real Develocity jar without its plugin descriptors, so
the fake is the only `com.gradle.develocity` plugin on the classpath.

The fake extensions are `java.lang.reflect.Proxy` instances, so Groovy closures do not delegate to them. Use the
closure parameter: `develocity.buildScan { it.value('k', 'v') }`.

Public ABI changes must be recorded with `./gradlew :plugin-test-fixtures:updateKotlinAbi`. For releases, see
[docs/releasing.md](../docs/releasing.md).

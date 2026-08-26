# AGENTS.md

## Project Overview

**Toolbox** (formerly "Plugin Manager", `ai.rever.boss.plugin.dynamic.pluginmanager`) is a dynamic plugin for the BOSS desktop application.

Toolbox - self-contained plugin store with install, uninstall, and update capabilities. Only the user-facing name is "Toolbox"; the plugin ID, panel ID (`plugin-manager`), package, repo, and JAR artifact names intentionally keep the old identifiers so existing installs and the host's store bootstrap keep working.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.pluginmanager`
- **Main Class**: `ai.rever.boss.plugin.dynamic.pluginmanager.PluginManagerDynamicPlugin`
- **API Version**: built against 1.0.85, but `minApiVersion` is **1.0.73** and
  `minBossVersion` **9.4.2** - deliberately, so one build runs on every host. `plugin.json`
  is the authority; keep this line reconciled with it (it had drifted to 1.0.57 once, and
  then sat at 1.0.73).

## Reporting downloads on a host that may not have a download center

Progress used to be a status-bar widget this plugin owned, which is why a download the
**host** started showed nothing. api 1.0.85 added `DownloadCenterProvider`, and this plugin
reports into it - but it must also load on hosts that predate it, so the adoption is shaped
by two host mechanisms rather than by taste:

- **`BinaryCompatibilityValidator` rejects the WHOLE plugin** if any class under
  `ai.rever.boss.plugin.*` in the jar names an api class or member the host cannot resolve.
  Not degrade - refuse. It skips classes outside that package, which is what makes an
  optional adapter possible at all.
- **`PluginContext` is host-compiled and served parent-first**, so on an older host
  `downloadCenterProvider` does not exist and reading it raises `NoSuchMethodError`. A `?:`
  cannot help; a `catch (LinkageError)` can.

So every reference to `DownloadCenterProvider`, `TransferHandle`, `TransferKind`,
`TransferPhase` and `TransferInfo` lives in **`com.risaboss.toolbox.downloadcenter`**, outside
the contract package, and everything in `ai.rever.boss.plugin.dynamic.pluginmanager` talks to
`TransferReporter`, which names no api type. Two implementations sit behind it: the host
center, and `LocalTransferReporter` feeding `DownloadStatusBarItem` - the bar older hosts
already had, with no dialog and no Cancel, because there is no host surface for them.

**Three rules if you touch this:**

1. Never name a 1.0.85 type from `ai.rever.boss.plugin.*`. **`./gradlew verifyNoApiLeak`
   enforces this**: it scans the constant pool of every contract class in the built jar, so it
   catches references the compiler synthesised, which a source grep misses. Verified by
   introducing a leak and watching it fail.

   It is wired into `check` **and named explicitly in `test.yml`**, because nothing in CI runs
   `check` - the workflow runs `test` and `buildPluginJar`, both narrower. Wiring it only into
   `check` left it green locally and never executed on a pull request. If you add a guard here,
   check which task CI actually invokes rather than which lifecycle task it hangs off.
2. The call into `HostDownloadCenter` is guarded at **both** ends. The inner catch covers the
   property read; the outer `runCatching` in `PluginManagerCore` covers resolving and
   verifying the method itself, whose descriptor names the api types - that error is thrown at
   the call site, where a catch inside the method can never see it.
3. Register `DownloadStatusBarItem` only when `HostDownloadCenter` returned null. Two bars for
   one download is the alternative.

**`apiVersion` in plugin.json is compared, and the fallback survives it only by convention.**
`DynamicPluginLoader.isApiVersionCompatible` checks the manifest's `apiVersion` against the api
jar's frozen `PluginManifestConstants.CURRENT_API_VERSION` (`1.0.18` - a manifest SCHEMA version,
not an api release), and it parses only (major, minor): `1.0.85` and `1.0.18` are both `(1, 0)`, so
the patch component is ignored and the check passes on every host. Declaring `apiVersion: 1.1.x`
would be refused everywhere - so if the api ever moves off `1.0.x`, this plugin's `apiVersion` line
cannot follow it without giving up the older hosts this whole arrangement exists for.

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `workspaceDataProvider`, `splitViewOperations`, `contextMenuProvider`, `activeTabsProvider`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.

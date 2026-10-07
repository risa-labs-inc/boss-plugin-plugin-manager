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

## The two version floors

A published plugin version declares two floors, they fail differently, and until now the Toolbox
only judged one of them.

| Floor | Source | Enforced by | Consequence of a miss |
|---|---|---|---|
| `minIpcVersion` | `plugin_versions.min_ipc_version` | `IpcCompat` (reads `boss.ipc.version`) | a plugin's out-of-process half cannot speak to this host |
| `minBossVersion` | `plugin_versions.min_boss_version` | `BossCompat` (reads `boss.app.version`) | `DynamicPluginLoader` **refuses the jar** - the plugin does not exist |

The second is the stricter one and was unchecked, because the host published `boss.api.version` and
`boss.ipc.version` but never its own app version - so there was nothing to compare `minBossVersion`
against and every published version looked installable. The failure was silent: Install downloaded
the jar, the host refused it, and the only trace was one ERROR line in the host log. fluck-browser
1.2.22 (`minBossVersion` 9.4.23) on a 9.4.22 host is the case that prompted this, and there the
missing plugin *is* the browser.

Four call sites now apply it, and all four matter separately:

- **`AvailablePluginCard`** - the store card. Judges the LATEST version, because that is what its
  Install button fetches. `PluginStoreItem.minBossVersion` is the view's `latest_min_boss_version`.
- **`VersionRow`** - the version sheet, per row, via `PluginVersionInfo.blockedReason()`. This one
  checks both floors; the card cannot, because the IPC floor is not projected into the store list.
- **`loadableUpdates`** - the Updates tab. The panel's list comes from `checkForUpdatesResult`,
  which filtered on "is it newer" and nothing else. Taking such an update is destructive rather
  than merely useless: the update path downloads *over* the installed jar, so a refused version
  removes a working plugin.
- **both download paths**, through one shared `bossFloorRefusal` - belt and braces, for a deep link
  or a stale list that reaches install without passing a UI filter.

**A hidden update still gets said out loud.** `loadableUpdates` returns what it held back as well as
what it kept, and the Updates tab renders "N updates need a newer BOSS" naming each one. Filtering
them out and saying nothing would have replaced one silence with another: a user on an out-of-date
host reading "All plugins are up to date" while updates they cannot have go unmentioned.

Two things about `PluginVersionInfo` that were wrong first and are worth not re-introducing:

- **`bossCompatibility` is derived, not stored.** As two independently defaulted constructor
  parameters, `minBossVersion` and its verdict had to be kept in step by every construction site,
  and the consumers disagreed about which was authoritative - so setting the floor and forgetting
  the verdict rendered a blocked version as installable.
- **`isLoadableHere()` reads the resolved `compatibility`, never `IpcCompat.isInstallable(minIpcVersion)`.**
  Those two are built from different inputs: `min_ipc_version` is nullable, and the entry coerces
  the string to `"1.0.0"` while resolving the status from the raw null. A version declaring no IPC
  floor resolves to UNKNOWN (no badge) while the coerced string reads as `MAJOR_MISMATCH` on any
  host whose IPC major is not 1 - a row with no badge and no action.

**Every one of them fails open on unknown.** A host that does not publish `boss.app.version` -
which is every BOSS up to 9.4.22 - and a version with no declared floor both stay installable. That
is not laziness: this plugin has to keep working on those hosts, and refusing every install there to
prevent the subset that would fail would be a worse bug than the one being fixed. The corollary is
that **the fix is inert until the host publishes the property**; it narrows what is offered on new
hosts and changes nothing on old ones.

The floor arrives as a **system property** rather than an API member on purpose: plugins load in
separate classloaders and cannot see `AppVersion`, and a property needs no `boss-plugin-api`
release, so this required no version-floor bump of its own.

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

### Automatic update prompt policy

BossConsole publishes optional JVM properties `boss.plugins.autoUpdate.enabled` and
`boss.plugins.autoUpdate.optOuts` (comma-separated plugin IDs). Missing properties
preserve manual prompts on older hosts. Automatic mode suppresses proactive prompts
except for explicit per-plugin opt-outs. Preferences can change during a session, so
recheck after store requests and before a toast action updates each plugin. Explicit
Updates-tab controls remain available. Failed checks preserve the current prompt;
retire mixed prompts only after a successful check can immediately re-offer opt-outs.
Replacing a mixed offer deliberately clears all its records, so disabling automatic
mode later can offer the formerly managed plugins again. Prompt replacement and
record changes share one mutex, preventing overlapping checks from clearing a
newer prompt's deduplication records.

Host and Toolbox share `.plugin-update-locks/<SHA-256 of UTF-8 plugin ID>.lock` for
all installer mutations, including uninstall. GitHub/file installs discover their
manifest identity before acquiring the lease; existing update fallbacks reuse the
held identity and reject mismatched manifests. Read installed state again inside
the lease. A busy lease is a neutral result, preserving toast deduplication records
and avoiding a false update-failure tally; competing installers report outcomes through their own UI.
Prompt checks use the real API's lossless compatible-update Result: failures in
either candidate or version-row lookups leave the visible prompt and records
untouched. The legacy public list API still collapses failures for callers that
only act on nonempty offers; never use it to retire prompts.
Targets handed to automatic mode by a stale toast clear matching records, so
switching back to manual mode can re-offer them. Explicit GitHub URL installs
and the version picker still permit same-version repair; only updater fallbacks
skip current versions. Both host and Toolbox companion releases
must ship for cross-installer protection. Keep lock files permanently: deleting one
allows another installer to lock a new inode while the old inode is still locked.

The process fence uses the platform MBeanServer's JDK-only RequiredModelMBean
`boss.plugins:type=UpdateLeaseRegistry,protocol=2`. Its managed resource is an
AtomicReference to a bootstrap ConcurrentHashMap of canonical lock paths to
java.lang.Object owner tokens, claimed before opening any lock descriptor. The
bean exposes only AtomicReference.get; map/token values never enter MBeanInfo.
Atomic registration chooses the shared winner across classloaders. Incompatible
metadata or map types fail closed; never replace or unregister the production bean.
No plugin-defined object may be retained in it, and system properties remain
string-compatible. Keep the protocol identical to BossConsole's
`composeApp/src/desktopMain/kotlin/ai/rever/boss/components/plugin/PluginUpdateProcessRegistry.kt`
and `PluginUpdateLease.kt`, including canonical paths and exact-token removal.
The lease intentionally spans download, state refresh and promotion; competing
host/Toolbox actions provide their own outcome UI. The host retries busy leases
without consuming its failure budget. Both revised releases must ship: an older
participant can still drop POSIX process locks by closing a contended descriptor.
Registry mutation by arbitrary in-process code is not a security boundary; plugins
already share JVM/file access. If abnormal teardown leaves an owner behind, restart
BOSS. Never probe a busy owner by opening/closing its lock file.

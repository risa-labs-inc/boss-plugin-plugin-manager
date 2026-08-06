# Toolbox

BOSS's plugin store client and plugin lifecycle manager, in a left sidebar panel.

Users see this as **Toolbox**. The plugin id, panel id, package, repository and jar all
deliberately keep the older `plugin-manager` name so existing installs and the host's store
bootstrap keep working. Expect both names when reading the code.

## What it does

- **Browse and install** from the plugin store, with search and a live progress bar in the
  status bar at the bottom of the window.
- **Manage installed plugins**: enable, disable, uninstall, inspect required permissions, view
  version history, and downgrade to an earlier version.
- **Updates arrive on their own**, even with the panel closed, as toasts. "Update all" applies
  them, and the panel tells you what applying actually needs: nothing, a reset of running
  instances, a full BOSS restart, or an API-layer hot swap.
- **Install from outside the store**: pick a local `.jar` with a file picker, or paste a GitHub
  release URL.
- **MCP tab**: toggle individual plugin-contributed MCP tools on and off, see which are
  permission-locked, start and stop the MCP server, and attach it to Claude Code, Codex, Gemini
  or OpenCode in one click.
- **Create tab**: publish your own plugin, or install Tool Creator if you do not have it. Gated
  on the `plugins.create` permission and hidden otherwise.

Tabs are Installed, Store, Updates, MCP and Create. Updates appears only when updates exist;
Create only when you may publish.

## MCP tools

| Tool | Purpose |
|---|---|
| `plugins_list` | List installed plugins with id, version, enabled and system flags |
| `plugin_enable` | Enable an installed plugin by id |
| `plugin_disable` | Disable an installed plugin by id |

`plugin_enable` and `plugin_disable` both require admin. `plugin_disable` additionally refuses
to disable `terminal-tab` (it hosts the MCP server, so disabling it would cut the channel the
call arrived on), itself, and any system or non-unloadable plugin.

## Permissions

The manifest declares none. Gating happens in `impl/PluginPermissions.kt`:

- `plugins.create` gates the Create tab. This is deliberately not `plugins.admin.publish`,
  whose RLS policy has no author scoping.
- `canInstallWith(...)` mirrors the server-side download gate so the UI never offers an install
  that would come back 403.
- Permission claims are read from the JWT **without verifying the signature**. That is for UI
  affordances only. Every gated action is re-checked server side, and an unreadable token
  denies.
- The admin "Delete from Store" action sits behind an additional build-time password hash. It
  is anti-fat-finger, not an authorization boundary.

## Requirements

- BOSS >= 9.2.33, boss-plugin-api >= 1.0.57
- **Supabase at `https://api.risaboss.com`** for the store catalog, the `plugin-store` edge
  function, and the Realtime channel that pushes store updates.
- Plugins install to `PluginLoaderDelegate.getPluginsDirectory()`, falling back to
  `~/.boss/plugins`, with an `installed.json` index and `<jar>.sig` signature sidecars.
- Optional and null-guarded: `McpToolRegistry`, `McpServerController` (from terminal-tab),
  `RoleManagementProvider`, `PanelEventProvider`, `ApplicationEventBus`.

## Build

```bash
./gradlew buildPluginJar
```

The build **fails hard** if `SUPABASE_ANON_KEY` is unset: `generateBuildConfig` needs it to
write `BuildConfig.kt`.

This is a bundled system plugin (`loadPriority: 5`, `canUnload: false`), so it ships with
BossConsole rather than being installed from the store. It also cannot hot-reload itself.

## Notes

- `PluginManagerCore` runs from `register()` to `dispose()`, independent of the panel. It owns
  the Supabase client and the Realtime connection and runs the update prompt service, which is
  why update toasts fire whether or not the panel is ever opened.
- Three plugins are treated as non-hot-reloadable when applying an update: Toolbox itself,
  `ai.rever.boss.plugin.api` (which triggers a process-wide API-layer swap), and
  `ai.rever.boss.microkernel.runtime`.

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.api.SplitViewOperations

/** The `supportsOpenPanelAsTab` getter, as the JVM names a Kotlin `val` on an interface. */
private const val SUPPORTS_GETTER = "getSupportsOpenPanelAsTab"

/**
 * Whether this host implements `SplitViewOperations.openPanelAsTab` (boss-plugin-api 1.0.77,
 * BossConsole#177) - the sanctioned, state-preserving way to open a sidebar panel in the main
 * area. False means fall back: [panelHostTabInfo]'s reflective bridge, then a sidebar reveal.
 *
 * ## Why this is reflective when the call itself is not
 *
 * Reading `ops.supportsOpenPanelAsTab` directly would be the same crash it exists to avoid.
 * `SplitViewOperations` is `@HostImplemented`: plugin-api-core filters it into the host and
 * serves it **parent-first**, so the host's PINNED copy is what this plugin resolves at
 * runtime, not the newer jar it compiled against. On a host below that pin the interface has
 * neither member, and touching either is a `NoSuchMethodError` rather than the defaulted
 * `false`. The probe therefore has to ask the class, not the value.
 *
 * For the same reason `BossApiRuntime.isAtLeast` is the wrong instrument here. It reports the
 * version of the api jar the host RESOLVED, which the hot-swappable api layer can advance
 * without the host's own compiled copy moving - exactly the case that would answer yes and
 * then crash. `getMethod` on the resolved interface is the only predicate that matches what
 * the call site will bind to.
 *
 * The manifest gate stays where it is on purpose: this plugin is a system plugin that has to
 * keep working on every host it installs on today, so it declares no new `minBossVersion` and
 * degrades instead.
 */
fun supportsOpenPanelAsTab(ops: SplitViewOperations): Boolean =
    runCatching {
        SplitViewOperations::class.java
            .getMethod(SUPPORTS_GETTER)
            .invoke(ops) as? Boolean
    }.getOrNull() ?: false

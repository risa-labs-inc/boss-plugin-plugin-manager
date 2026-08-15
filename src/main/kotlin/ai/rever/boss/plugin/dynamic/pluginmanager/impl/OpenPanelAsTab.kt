package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.api.SplitViewOperations

/** The `supportsOpenPanelAsTab` getter, as the JVM names a Kotlin `val` on an interface. */
private const val SUPPORTS_GETTER = "getSupportsOpenPanelAsTab"

/** The call the flag gates. */
private const val PROMOTE_METHOD = "openPanelAsTab"

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
 * then crash. Asking the resolved interface is the only predicate that matches what the call
 * site will bind to.
 *
 * ## It checks the call, not only the flag
 *
 * The flag and the method are two different questions, and only the second is what the call
 * site binds to. They ship together today, but a signature that later grew a parameter would
 * pass a flag-only check and then throw at the call. Matched on name + arity rather than the
 * exact parameter type: enough to catch that drift, and it keeps this testable against a
 * stand-in interface rather than a full [SplitViewOperations].
 *
 * The result is deliberately NOT cached. Whether the members exist is process-constant, but
 * the flag's value belongs to the instance, and [iface] is a seam - a `lazy` would freeze
 * both together for the sake of a lookup that costs nothing next to opening a tab.
 *
 * The manifest gate stays where it is on purpose: this plugin is a system plugin that has to
 * keep working on every host it installs on today, so it declares no new `minBossVersion` and
 * degrades instead.
 *
 * @param ops the host's provider, typed [Any] so a test can stand in without implementing
 *   eleven members of an interface it does not own.
 * @param iface the interface to interrogate. Defaults to the one the call site binds against,
 *   which is the only value production uses.
 */
internal fun supportsOpenPanelAsTab(
    ops: Any,
    iface: Class<*> = SplitViewOperations::class.java,
): Boolean =
    runCatching {
        val hasPromoteCall = iface.methods.any { it.name == PROMOTE_METHOD && it.parameterCount == 1 }
        hasPromoteCall && iface.getMethod(SUPPORTS_GETTER).invoke(ops) == true
    }.getOrDefault(false)

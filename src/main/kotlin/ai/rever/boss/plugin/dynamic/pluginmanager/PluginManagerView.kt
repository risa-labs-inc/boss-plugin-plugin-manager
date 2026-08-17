package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.dynamic.pluginmanager.api.DefinedPermissionData
import ai.rever.boss.plugin.dynamic.pluginmanager.api.ExtractedManifest
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstalledPluginState
import ai.rever.boss.plugin.dynamic.pluginmanager.api.IpcCompat
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginType
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginVersionInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import androidx.compose.foundation.layout.heightIn
import ai.rever.boss.plugin.ui.BossBadge
import ai.rever.boss.plugin.ui.BossCard
import ai.rever.boss.plugin.ui.BossEmptyState
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossSection
import ai.rever.boss.plugin.ui.BossTextArea
import ai.rever.boss.plugin.ui.BossTextField
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.api.InaccessiblePluginInfo
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.filterOrgSlugs
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.storeOrgSlugs
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.matchesOrgFilter
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.StoreProvenance
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.storeProvenanceByPluginId
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.SYSTEM_ORG_SLUG
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginPageUrl
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PublishTarget
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationCta
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationCtaDescription
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationCtaLabel
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.OrganisationCta
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.OrganisationPlugin
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationCtaEnabled
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationCtaNeedsCreateTab
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationDomainError
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationNameError
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationSlugError
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.organisationWebsiteError
import ai.rever.boss.plugin.ui.BossToggle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import ai.rever.boss.plugin.api.McpServerController
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.RegisteredMcpTool
import androidx.compose.material.icons.filled.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The organisation call to action.
 *
 * On the CREATE tab: creating an organisation belongs beside the other "make something new"
 * actions, not in the list of what is already installed.
 *
 * The Create tab is normally gated on `canPublish` (store admin or plugins.create), while the
 * CREATE branch here targets users who belong to no organisation - by definition the least likely
 * to hold plugins.create. So the tab is ALSO revealed for the two branches that have nowhere else
 * to live ([organisationCtaNeedsCreateTab]); without that, the people who most need the request
 * form are exactly the ones who cannot reach it.
 *
 * Renders nothing while membership is unknown: see organisationCta.
 */
// Every dialog in this file uses BossDialog, not Dialog. Under JxBrowser HARDWARE_ACCELERATED - the
// host default on every platform since BossConsole 9.4.1 - Chromium composites its own native window
// over the Compose scene, so a plain Compose Dialog in a plugin panel is drawn BEHIND the page.
// Reported live against the version/downgrade sheet, which was cropped at the browser's rendering
// area whenever the store sat beside a browser tab. BossDialog routes through the host's
// always-on-top overlay window and falls back to exactly this Dialog wherever the browser is
// off-screen.

@Composable
private fun OrganisationCtaCard(
    cta: OrganisationCta?,
    onAction: () -> Unit
) {
    if (cta == null) return

    BossSection(
        title = "Organisation",
        description = "Organisations own plugins, roles and shared secrets"
    ) {
        Text(
            text = organisationCtaDescription(cta),
            fontSize = 13.sp,
            color = BossThemeColors.TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        BossPrimaryButton(
            text = organisationCtaLabel(cta),
            onClick = onAction,
            enabled = organisationCtaEnabled(cta),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * Request a new organisation.
 *
 * In-app rather than a web page, and that is not a style preference:
 * `submit_organisation_request` is authenticated-only, and the handoff-token
 * mechanism that authenticates the other organisation web pages is org-scoped.
 * A user with no organisation has nothing to hand off for, so a web form could
 * not authenticate at all.
 *
 * Validation mirrors the database CHECK so a typo is a message under the field.
 * The reserved-slug and collision rules stay server-side - only the database
 * knows the full list, and `boss` deriving the existing global `boss_admin`
 * role is the reason that list exists.
 */
@Composable
private fun RequestOrganisationDialog(
    busy: Boolean,
    serverError: String?,
    onSubmit: (slug: String, name: String, description: String, justification: String,
               domain: String, website: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var justification by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var website by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }

    val nameError = if (touched) organisationNameError(name) else null
    val slugError = if (touched) organisationSlugError(slug) else null
    // Shown as soon as there is something to be wrong about, rather than waiting for
    // `touched`: both are optional, so a user who types one and leaves it malformed
    // would otherwise learn about it from a server round trip.
    val domainError = organisationDomainError(domain)
    val websiteError = organisationWebsiteError(website)
    val valid = organisationNameError(name) == null && organisationSlugError(slug) == null &&
        domainError == null && websiteError == null

    BossDialog(onDismissRequest = { if (!busy) onDismiss() }) {
        BossCard(modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "Request an organisation",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "A BOSS administrator reviews the request before the organisation is created.",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))

                BossTextField(
                    value = name,
                    onValueChange = { name = it; touched = true },
                    label = "Name",
                    placeholder = "Acme Inc"
                )
                nameError?.let { FieldError(it) }
                Spacer(Modifier.height(10.dp))

                BossTextField(
                    value = slug,
                    onValueChange = { slug = it.lowercase(); touched = true },
                    label = "Identifier",
                    placeholder = "acme"
                )
                slugError?.let { FieldError(it) }
                Spacer(Modifier.height(2.dp))
                Text(
                    // Stated up front because it is permanent: the roles derive
                    // from it and renaming would orphan them.
                    text = "Permanent. Roles are named ${slug.ifBlank { "acme" }}_admin and " +
                        "${slug.ifBlank { "acme" }}_user.",
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                BossTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = "Website (optional)",
                    placeholder = "https://acme.com"
                )
                websiteError?.let { FieldError(it) }
                Spacer(Modifier.height(10.dp))

                BossTextField(
                    value = domain,
                    onValueChange = { domain = it.lowercase() },
                    label = "Email domain (optional)",
                    placeholder = "acme.com"
                )
                domainError?.let { FieldError(it) }
                Spacer(Modifier.height(2.dp))
                Text(
                    // Worth saying plainly: this one is not decoration. Once verified it
                    // decides who may join without an invite, which is a different kind of
                    // field from the website beside it.
                    text = "Once you verify it with a DNS record, people with a matching " +
                        "address can find and join this organisation.",
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                BossTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "Description (optional)",
                    placeholder = "What this organisation is for"
                )
                Spacer(Modifier.height(10.dp))

                BossTextField(
                    value = justification,
                    onValueChange = { justification = it },
                    label = "Note for the reviewer (optional)",
                    placeholder = "Why you need it"
                )

                serverError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(text = it, color = BossThemeColors.ErrorColor, fontSize = 12.sp)
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BossSecondaryButton(text = "Cancel", onClick = onDismiss, enabled = !busy)
                    Spacer(Modifier.width(8.dp))
                    BossPrimaryButton(
                        text = if (busy) "Sending..." else "Send request",
                        onClick = {
                            touched = true
                            onSubmit(slug, name, description, justification, domain, website)
                        },
                        enabled = valid && !busy
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldError(message: String) {
    Text(
        text = message,
        color = BossThemeColors.ErrorColor,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/**
 * The organisation that owns a store plugin.
 *
 * Mono and slug-shaped (`@boss`), because that is what it IS - `organisations.slug` is validated
 * `^[a-z][a-z0-9_]{1,30}$` and the organisation's roles are named after it. Setting it in the same
 * face as the author's free-text name would suggest the two are the same kind of thing.
 *
 * A WASH BEHIND `TextSecondary`, never `AccentColor` as the text fill. Under the Blueprint theme
 * `AccentColor` is `#0F5BFF`, which clears the 3:1 floor for a UI component but not the 4.5:1 for
 * text - it is a fill colour, and the host corrected exactly this across ~90 call sites. A badge
 * is emphasis, so it gets a background, not a coloured glyph.
 *
 * Every plugin in the store today resolves to `@boss`, the system organisation every user is a
 * member of, so this currently reads the same on every card. That is not a bug to hide: it is the
 * honest state, and it is what makes it visible that organisation ownership is not yet doing any
 * work.
 */
/**
 * The organisation filter: a compact control that opens a searchable picker.
 *
 * WAS A ROW OF CHIPS, and that does not survive growth. One chip per organisation is fine at two
 * and unreadable at twenty: the row either wraps and eats the header or scrolls sideways, and
 * either way the organisation somebody wants is the one off-screen. A select degrades gracefully -
 * the control stays one width whatever the catalogue holds, and the list inside it is searchable.
 *
 * A DIALOG RATHER THAN A DROPDOWN MENU, and not by preference. Compose's `DropdownMenu` is a
 * Popup, and under JxBrowser HARDWARE_ACCELERATED - the host default on every platform since
 * BossConsole 9.4.1 - Chromium composites its own native window over the Compose scene, so a
 * Popup in a plugin panel draws BEHIND the page. That is the same bug that had the version sheet
 * cropped, and it is why every overlay in this file goes through `BossDialog`, which routes to the
 * host's always-on-top overlay window. There is no `BossPopup` at the declared api floor of
 * 1.0.73, so a dialog is the only overlay available that actually appears.
 */
@Composable
private fun OrgFilterControl(
    slugs: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    var picking by remember { mutableStateOf(false) }

    Text(
        text = selected?.let { "@$it" } ?: "All orgs",
        color = if (selected != null) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
        fontSize = 10.sp,
        // Mono only when it is showing a slug, which is an identifier. "All orgs" is prose.
        fontFamily = if (selected != null) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            // A wash when filtering, so an active filter is visible without reading the label -
            // otherwise a list narrowed to one organisation looks like a list with few plugins.
            .background(
                if (selected != null) {
                    BossThemeColors.AccentColor.copy(alpha = 0.16f)
                } else {
                    BossThemeColors.TextMuted.copy(alpha = 0.10f)
                }
            )
            .clickable { picking = true }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )

    if (picking) {
        OrgFilterDialog(
            slugs = slugs,
            selected = selected,
            onSelect = {
                onSelect(it)
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}

/**
 * The picker itself: a search field over the organisation list.
 *
 * The field is what makes this scale, so it is always present rather than appearing past some
 * threshold - a control that changes shape as data grows is one more thing to explain, and the
 * field costs one row.
 *
 * "All organisations" is a row in the list rather than a separate Clear button, so choosing and
 * un-choosing are the same gesture in the same place.
 */
@Composable
private fun OrgFilterDialog(
    slugs: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(slugs, query) { filterOrgSlugs(slugs, query) }

    BossDialog(onDismissRequest = onDismiss) {
        BossCard(modifier = Modifier.width(320.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "Filter by organisation",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                BossTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "",
                    placeholder = "Search organisations..."
                )
                Spacer(Modifier.height(8.dp))

                OrgFilterRow(
                    label = "All organisations",
                    mono = false,
                    isSelected = selected == null,
                    onClick = { onSelect(null) }
                )

                // Bounded, so a long list scrolls inside the dialog instead of growing it past
                // the window. LazyColumn rather than a Column in a scroll, because the whole
                // point of this change is that the list may be long.
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(shown, key = { it }) { slug ->
                        OrgFilterRow(
                            label = "@$slug",
                            mono = true,
                            isSelected = selected == slug,
                            onClick = { onSelect(slug) }
                        )
                    }
                }

                if (shown.isEmpty() && query.isNotBlank()) {
                    Text(
                        text = "No organisation matches \"$query\".",
                        color = BossThemeColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OrgFilterRow(
    label: String,
    mono: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (isSelected) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
        fontSize = 12.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            // A wash behind TextPrimary marks the selection, never AccentColor as the text fill:
            // under Blueprint that is #0F5BFF, which clears 3:1 for a UI component but not 4.5:1
            // for text.
            .background(
                if (isSelected) BossThemeColors.AccentColor.copy(alpha = 0.16f) else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/**
 * The organisation a plugin is about to be published under.
 *
 * A REAL `BossTextField`, disabled, with a transparent overlay taking the click. Every other input
 * in this section is one of these, and a hand-rolled box beside them reads as a different kind of
 * control - which is exactly what the first version did wrong. Borrowing the component means the
 * label, chrome, spacing and disabled colours cannot drift from the fields above and below it.
 *
 * The overlay rather than a `clickable` on a parent: a disabled text field's handling of pointer
 * events is its own business, and a Box drawn ON TOP receives the click whatever the field does
 * with it. Nothing here depends on knowing that.
 *
 * Disabled is honest - there is nothing to type. The value is an organisation id and the list is
 * the server's answer to "may I publish here", so the only sensible gesture is to pick.
 */
@Composable
private fun PublishOrgField(
    targets: List<PublishTarget>,
    selectedOrgId: String?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val selected = targets.firstOrNull { it.orgId == selectedOrgId }

    Box(modifier = Modifier.fillMaxWidth()) {
        BossTextField(
            // Name and slug together: the name is what a person recognises, the slug is what the
            // badge shows afterwards, so the choice made here and the badge seen later are
            // visibly the same thing.
            value = selected?.let { "${it.name}  @${it.slug}" } ?: "",
            onValueChange = {},
            label = "Organisation",
            placeholder = "Choose an organisation",
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        )
        // The affordance. A disabled field reads as "unavailable" on its own; the chevron is what
        // says "there is a list behind this". BossTextField has no trailing-icon slot - its extra
        // parameters are enabled, required and singleLine - so it is drawn over the field rather
        // than passed to it.
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Choose an organisation",
            tint = BossThemeColors.TextSecondary,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .size(18.dp)
        )
        // LAST, so it is on top of both. A disabled text field's handling of pointer events is its
        // own business, and a Box drawn over it receives the click whatever the field does.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { onClick() }
        )
    }
    Text(
        // Said here because it is not recoverable from this screen: a version publish never
        // re-derives ownership, so republishing does not move a plugin between organisations.
        text = "Decides which organisation's admins can update this plugin. Set once, when the plugin is created.",
        color = BossThemeColors.TextMuted,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 3.dp)
    )
}

/**
 * The picker for [PublishOrgField].
 *
 * A `BossDialog` for the same reason the filter picker is one: Compose's `DropdownMenu` is a Popup,
 * and under the host's hardware-accelerated browser a Popup in a plugin panel draws behind the
 * page. There is no `BossPopup` at the api floor of 1.0.73.
 *
 * No "let the server decide" row once a choice exists. Offering it would invite somebody to pick
 * the vaguest option on the one field here that cannot be changed afterwards.
 */
@Composable
private fun PublishOrgDialog(
    targets: List<PublishTarget>,
    selectedOrgId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(targets, query) {
        val needle = query.trim().removePrefix("@").lowercase()
        if (needle.isEmpty()) {
            targets
        } else {
            targets.filter {
                it.slug.lowercase().contains(needle) || it.name.lowercase().contains(needle)
            }
        }
    }

    BossDialog(onDismissRequest = onDismiss) {
        BossCard(modifier = Modifier.width(320.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "Publish for",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // The list is the server's answer, not a guess this side made, so an
                    // organisation somebody expects and cannot see is a permissions question
                    // rather than a bug in the picker.
                    text = "Only organisations you may publish for are listed.",
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))

                BossTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "",
                    placeholder = "Search organisations..."
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(shown, key = { it.orgId }) { target ->
                        OrgFilterRow(
                            label = "${target.name}  @${target.slug}",
                            mono = false,
                            isSelected = target.orgId == selectedOrgId,
                            onClick = { onSelect(target.orgId) }
                        )
                    }
                }

                if (shown.isEmpty()) {
                    Text(
                        text = if (query.isBlank()) {
                            "You cannot publish for any organisation yet."
                        } else {
                            "No organisation matches \"$query\"."
                        },
                        color = BossThemeColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Where a plugin came from, rendered identically on every card that shows one.
 *
 * ONE COMPOSABLE BECAUSE THE THREE TABS HAD DRIFTED. Available put the author under the
 * description and the organisation beside it; Installed put the organisation up on the title row
 * next to the version and showed no author at all; Updates put it on the title row after the name.
 * Same two facts, three positions, and one of them silently missing half of it - which is what
 * happens when each card is edited where it stands rather than through a shared piece.
 *
 * Placed under the description on every card, because that is the "about this plugin" region
 * rather than the "what state is it in" region: the title row carries version, update and health,
 * which change, while provenance does not.
 *
 * The two facts are not the same kind of thing and are deliberately styled differently. `author`
 * is free text whoever published typed; the organisation is a row the store resolved, and it is
 * what visibility and publish policy are actually keyed on. Hence prose against a mono badge.
 *
 * Renders NOTHING when both are absent - a sideloaded jar the store has never heard of - rather
 * than an empty row that shifts every card below it.
 */
@Composable
private fun ProvenanceLine(author: String, orgSlug: String) {
    if (author.isEmpty() && orgSlug.isEmpty()) return

    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (author.isNotEmpty()) {
            Text(
                text = "by $author",
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (orgSlug.isNotEmpty()) {
            if (author.isNotEmpty()) Spacer(Modifier.width(6.dp))
            OrgBadge(slug = orgSlug)
        }
    }
}

@Composable
private fun OrgBadge(slug: String) {
    Text(
        text = "@$slug",
        color = BossThemeColors.TextSecondary,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .background(
                BossThemeColors.TextMuted.copy(alpha = 0.14f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/**
 * Confirmation dialog for destructive actions.
 */
@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    BossDialog(onDismissRequest = onDismiss) {
        BossCard(
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = title,
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    color = BossThemeColors.TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BossSecondaryButton(
                        text = "Cancel",
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(8.dp))
                    BossPrimaryButton(
                        text = confirmText,
                        onClick = {
                            onConfirm()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Password-gated confirmation dialog for the admin "Delete from Store" action.
 *
 * Verifies the typed password against [DeletePasswordGate]; only invokes [onConfirmed] on a match.
 * A wrong password shows an inline error and keeps the dialog open.
 */
@Composable
private fun PasswordDialog(
    title: String,
    message: String,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    BossDialog(onDismissRequest = onDismiss) {
        BossCard(
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = title,
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    color = BossThemeColors.TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = false
                    },
                    singleLine = true,
                    label = { Text("Admin password") },
                    isError = error,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showPassword = !showPassword }
                                .padding(4.dp)
                                .size(20.dp),
                            tint = BossThemeColors.TextSecondary
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = BossThemeColors.TextPrimary,
                        cursorColor = BossThemeColors.AccentColor,
                        focusedBorderColor = BossThemeColors.AccentColor,
                        unfocusedBorderColor = BossThemeColors.TextSecondary,
                        focusedLabelColor = BossThemeColors.AccentColor,
                        unfocusedLabelColor = BossThemeColors.TextSecondary,
                        errorBorderColor = BossThemeColors.ErrorColor,
                        errorLabelColor = BossThemeColors.ErrorColor
                    )
                )
                if (error) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Incorrect password",
                        color = BossThemeColors.ErrorColor,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BossSecondaryButton(
                        text = "Cancel",
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(8.dp))
                    BossPrimaryButton(
                        text = "Delete",
                        enabled = password.isNotEmpty(),
                        onClick = {
                            if (DeletePasswordGate.verify(password)) {
                                onConfirmed()
                                onDismiss()
                            } else {
                                error = true
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Plugin Manager View - matching bundled plugin-panel-manager exactly.
 * Uses BossTheme and UI components from plugin-ui-core.
 */

/**
 * Version-history / downgrade sheet: lists every published version with its
 * host-IPC compatibility badge and an Update/Downgrade action. Incompatible
 * versions are shown but not installable.
 */
@Composable
private fun VersionSheetDialog(
    sheet: VersionSheetState,
    busy: Boolean,
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BossDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BossThemeColors.SurfaceColor)
                .padding(20.dp)
        ) {
            Text(
                text = "${sheet.displayName} — versions",
                color = BossThemeColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "This host speaks IPC ${sheet.hostIpcVersion ?: "unknown"} · " +
                    when {
                        // Null only when opened from the store for a plugin that isn't here.
                        sheet.installedVersion == null -> "not installed"
                        sheet.installedVersion.isBlank() -> "installed, version unknown"
                        else -> "installed v${sheet.installedVersion}"
                    },
                color = BossThemeColors.TextMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            when {
                sheet.isLoading -> Text("Loading versions…", color = BossThemeColors.TextSecondary, fontSize = 13.sp)
                sheet.error != null -> Text(sheet.error, color = BossThemeColors.ErrorColor, fontSize = 13.sp)
                sheet.versions.isEmpty() -> Text("No published versions in the store.", color = BossThemeColors.TextSecondary, fontSize = 13.sp)
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(sheet.versions, key = { it.version }) { v ->
                        VersionRow(
                            version = v,
                            installedVersion = sheet.installedVersion,
                            busy = busy,
                            onInstall = { onInstall(v.version) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BossSecondaryButton(text = "Close", onClick = onDismiss)
            }
        }
    }
}

/**
 * Per-plugin MCP tools dialog (opened from the Installed tab's MCP button —
 * mirrors [VersionSheetDialog]). Lists the tools this plugin contributes to the
 * `boss` MCP server with live enable toggles and permission info; the same
 * toggles as the MCP tab, scoped to one plugin.
 */
@Composable
private fun McpToolsDialog(
    displayName: String,
    tools: List<RegisteredMcpTool>,
    registry: McpToolRegistry,
    manifest: ExtractedManifest?,
    permissionDescriptions: Map<String, String>,
    onDismiss: () -> Unit
) {
    val exposed by registry.tools.collectAsState()
    val exposedNames = remember(exposed) { exposed.map { it.definition.name }.toSet() }
    val disabled by registry.disabledToolNames.collectAsState()
    val onCount = tools.count { it.definition.name in exposedNames }

    // Every permission in play: the plugin's own manifest gate + each tool's
    // requirements. Descriptions resolve from the plugin's definedPermissions
    // (self-documented), then the RBAC glossary, then a built-in fallback.
    val pluginPerms = manifest?.requiredPermissions.orEmpty()
    val allPerms = remember(tools, pluginPerms) {
        buildList {
            addAll(pluginPerms)
            tools.forEach { t ->
                if (t.definition.requiresAdmin) add("admin")
                addAll(t.definition.requiredPermissions)
            }
        }.distinct().sorted()
    }
    val definedByPlugin = remember(manifest) {
        manifest?.definedPermissions.orEmpty().associate { it.name to it.description }
    }
    fun describe(perm: String): String =
        definedByPlugin[perm]?.takeIf { it.isNotBlank() }
            ?: permissionDescriptions[perm]?.takeIf { it.isNotBlank() }
            ?: if (perm == "admin") "Administrator access — only admins can use this." else "No description available."

    BossDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BossThemeColors.SurfaceColor)
                .padding(20.dp)
        ) {
            Text(
                text = "$displayName — MCP tools",
                color = BossThemeColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "$onCount/${tools.size} exposed to agents as mcp__boss__* while this plugin is active",
                color = BossThemeColors.TextMuted,
                fontSize = 12.sp
            )
            Text(
                text = when {
                    manifest == null -> "Plugin permissions: reading manifest…"
                    pluginPerms.isEmpty() -> "Plugin requires no special permissions to load."
                    else -> "Plugin requires: ${pluginPerms.joinToString(", ")}"
                },
                color = BossThemeColors.TextMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            if (tools.isEmpty()) {
                Text(
                    text = "This plugin contributes no MCP tools.",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 13.sp
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(tools, key = { it.definition.name }) { tool ->
                        val def = tool.definition
                        val name = def.name
                        val on = name in exposedNames
                        val userDisabled = name in disabled
                        val permissionDenied = !on && !userDisabled
                        val perms = buildList {
                            if (def.requiresAdmin) add("admin")
                            addAll(def.requiredPermissions)
                        }
                        val desc = buildString {
                            append(def.description)
                            if (perms.isNotEmpty()) append("  ·  requires: ${perms.joinToString(", ")}")
                            if (permissionDenied) append("  ·  🔒 no permission")
                        }
                        BossToggle(
                            label = name,
                            checked = on,
                            onCheckedChange = { enable -> registry.setToolEnabled(name, enable) },
                            description = desc,
                            enabled = !permissionDenied
                        )
                    }
                }
            }

            // Permission glossary: what each permission referenced above grants.
            if (allPerms.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Permissions",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    allPerms.forEach { perm ->
                        Row {
                            Text(
                                text = perm,
                                color = BossThemeColors.AccentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(140.dp)
                            )
                            Text(
                                text = describe(perm),
                                color = BossThemeColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BossSecondaryButton(text = "Close", onClick = onDismiss)
            }
        }
    }
}

/**
 * Plugin permissions dialog (opened from the lock button on installed/store
 * cards). Shows the permissions a plugin requires to install/use — each with a
 * description — plus any permissions the plugin itself defines.
 *
 * [requiredPermissions] null means the manifest is still being read (installed
 * path); the store path passes the store item's list directly.
 */
@Composable
private fun PermissionsDialog(
    displayName: String,
    requiredPermissions: List<String>?,
    definedPermissions: List<DefinedPermissionData>,
    permissionDescriptions: Map<String, String>,
    /** Permissions used inside the plugin by its MCP tools (incl. "admin"). */
    toolPermissions: List<String> = emptyList(),
    onDismiss: () -> Unit
) {
    val definedByPlugin = remember(definedPermissions) {
        definedPermissions.associate { it.name to it.description }
    }
    fun describe(perm: String): String =
        definedByPlugin[perm]?.takeIf { it.isNotBlank() }
            ?: permissionDescriptions[perm]?.takeIf { it.isNotBlank() }
            ?: if (perm == "admin") "Administrator access — only admins can use this." else "No description available."

    BossDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BossThemeColors.SurfaceColor)
                .padding(20.dp)
        ) {
            Text(
                text = "$displayName — permissions",
                color = BossThemeColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Permissions gate who can install and use this plugin (admins bypass).",
                color = BossThemeColors.TextMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            when {
                requiredPermissions == null -> Text(
                    "Reading manifest…",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 13.sp
                )
                requiredPermissions.isEmpty() -> Text(
                    "This plugin requires no special permissions — any authenticated user can use it.",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 13.sp
                )
                else -> {
                    Text(
                        text = "Required permissions",
                        color = BossThemeColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        requiredPermissions.sorted().forEach { perm ->
                            Row {
                                Text(
                                    text = perm,
                                    color = BossThemeColors.AccentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(140.dp)
                                )
                                Text(
                                    text = describe(perm),
                                    color = BossThemeColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // Permissions used inside the plugin — required by its MCP tools
            // (beyond the plugin-level gate above).
            val extraToolPerms = toolPermissions.filterNot { it in requiredPermissions.orEmpty() }.sorted()
            if (extraToolPerms.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Used inside the plugin (MCP tools)",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    extraToolPerms.forEach { perm ->
                        Row {
                            Text(
                                text = perm,
                                color = BossThemeColors.AccentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(140.dp)
                            )
                            Text(
                                text = describe(perm),
                                color = BossThemeColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Permissions this plugin introduces to the system (from its manifest).
            if (definedPermissions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Defines permissions",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    definedPermissions.forEach { p ->
                        Row {
                            Text(
                                text = p.name,
                                color = BossThemeColors.AccentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(140.dp)
                            )
                            Text(
                                text = p.description.ifBlank { "No description available." },
                                color = BossThemeColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BossSecondaryButton(text = "Close", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: PluginVersionInfo,
    /** Null when the plugin is not installed — see [VersionSheetState.installedVersion]. */
    installedVersion: String?,
    busy: Boolean,
    onInstall: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "v${version.version}",
            color = BossThemeColors.TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.width(96.dp)
        )
        IpcBadge(version.compatibility)
        Spacer(Modifier.weight(1f))
        val isInstalled = installedVersion != null && version.version == installedVersion
        val installable = version.compatibility == IpcCompat.Status.COMPATIBLE ||
            version.compatibility == IpcCompat.Status.UNKNOWN
        when {
            isInstalled -> Text("Installed", color = BossThemeColors.TextMuted, fontSize = 12.sp)
            !installable -> Text("Needs newer BOSS", color = BossThemeColors.WarningColor, fontSize = 11.sp)
            busy -> Text("…", color = BossThemeColors.TextMuted, fontSize = 12.sp)
            else -> BossSecondaryButton(
                text = when {
                    // Opened from the store for a plugin that isn't installed: every version
                    // is a fresh install, not an up/downgrade of something already here.
                    installedVersion == null -> "Install"
                    ipcCompareSemver(version.version, installedVersion) > 0 -> "Update"
                    else -> "Downgrade"
                },
                onClick = onInstall
            )
        }
    }
}

@Composable
private fun IpcBadge(status: IpcCompat.Status) {
    val (color, label) = when (status) {
        IpcCompat.Status.COMPATIBLE -> BossThemeColors.SuccessColor to "Compatible"
        IpcCompat.Status.REQUIRES_HOST_UPDATE -> BossThemeColors.WarningColor to "Host update"
        IpcCompat.Status.MAJOR_MISMATCH -> BossThemeColors.ErrorColor to "Incompatible"
        IpcCompat.Status.UNKNOWN -> return
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/** Compare two `major.minor.patch` strings; non-numeric segments sort as 0. */
private fun ipcCompareSemver(a: String, b: String): Int {
    val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
    val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (d != 0) return d
    }
    return 0
}

@Composable
fun PluginManagerView(viewModel: PluginManagerViewModel) {
    val state by viewModel.state.collectAsState()

    val organisationPluginInstalled = state.installedPlugins.any {
        it.pluginId == OrganisationPlugin.PLUGIN_ID
    }
    // Read from the COLLECTED state, not viewModel._state: everything else here observes
    // `state`, and reading the flow's value directly from composition only recomposes
    // correctly by accident.
    val organisationCta = organisationCta(
        state.membership,
        organisationPluginInstalled,
        state.hasPendingOrgRequest
    )
    val createTabVisible = state.canPublish || organisationCtaNeedsCreateTab(organisationCta)

    // The tab BUTTON is derived state; currentTab is not. A non-publisher sitting on the Create
    // tab they reached only for the call to action would otherwise be stranded there the moment
    // an approval lands and the branch moves to OPEN - button gone, header showing no selection.
    LaunchedEffect(createTabVisible) {
        if (!createTabVisible && state.currentTab == PluginManagerTab.PUBLISH) {
            viewModel.selectTab(PluginManagerTab.INSTALLED)
        }
    }

    BossTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BossThemeColors.BackgroundColor)
        ) {
            // Header with tabs and search
            // Owning organisation per plugin id, built ONCE for every tab.
            //
            // The store catalogue is the only thing that knows this: an installed plugin is known
            // from installed.json and the jar on disk, and an update from a version comparison -
            // neither records an organisation. `availablePlugins` is only ever assigned the FULL
            // fetch, never a filtered subset, so it is safe to key off even while a search is
            // narrowing what the Available tab shows.
            //
            // Keyed on the list so it is not rebuilt per recomposition, and empty until the store
            // fetch returns - which renders no badge rather than a wrong one.
            val provenanceByPluginId = remember(state.availablePlugins) {
                storeProvenanceByPluginId(state.availablePlugins)
            }
            val orgSlugsInStore = remember(state.availablePlugins) {
                storeOrgSlugs(state.availablePlugins)
            }

            PluginManagerHeader(
                currentTab = state.currentTab,
                updateCount = state.updates.size,
                searchQuery = state.searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                orgSlugs = orgSlugsInStore,
                orgFilter = state.orgFilter,
                onOrgFilterChange = { viewModel.setOrgFilter(it) },
                onTabSelected = { viewModel.selectTab(it) },
                onRefresh = { viewModel.refresh() },
                isLoading = state.isLoading,
                // The Create tab also hosts the organisation call to action, so it is revealed
                // for the branches a non-publisher could otherwise never reach.
                canPublish = createTabVisible,
                realtimeConnected = state.realtimeConnected
            )

            // Error message
            if (state.error != null) {
                ErrorBanner(
                    message = state.error!!,
                    onDismiss = { viewModel.clearError() }
                )
            }

            // Content based on selected tab
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                when (state.currentTab) {
                    PluginManagerTab.INSTALLED -> InstalledPluginsTab(
                        plugins = filterPlugins(state.installedPlugins, state.searchQuery)
                            .filter {
                                matchesOrgFilter(
                                    provenanceByPluginId[it.pluginId]?.orgSlug,
                                    state.orgFilter
                                )
                            },
                        inaccessiblePlugins = state.inaccessiblePlugins,
                        updateIds = state.updates.map { it.pluginId }.toSet(),
                        onToggleEnabled = { id, enabled -> viewModel.togglePluginEnabled(id, enabled) },
                        onUninstall = { id -> viewModel.uninstallPlugin(id) },
                        onUpdate = { id -> viewModel.updatePlugin(id) },
                        onInstallFromFile = { viewModel.installFromFilePicker() },
                        onInstallFromGitHub = { url -> viewModel.installFromGitHub(url) },
                        onOpenHomepage = { url -> viewModel.openUrl(url) },
                        onOpenPlugin = { p -> viewModel.openPlugin(p.pluginId, p.url) },
                        openablePlugins = state.openablePlugins,
                        isLoading = state.isLoading,
                        busyPlugins = state.busyPlugins,
                        // Non-null: reaching this row means the plugin IS installed, whatever
                        // its version string says.
                        onShowVersions = { p -> viewModel.openVersions(p.pluginId, p.displayName, p.version) },
                        mcpToolRegistry = viewModel.mcpToolRegistry,
                        permissionDescriptions = state.permissionDescriptions,
                        provenanceByPluginId = provenanceByPluginId,
                        onExtractManifest = { jar, cb -> viewModel.extractManifest(jar, cb) }
                    )
                    PluginManagerTab.AVAILABLE -> AvailablePluginsTab(
                        // orgSlug off the item, not the map: on this tab the catalogue row IS
                        // the source, so going through the map would add a lookup that can only
                        // ever agree with it.
                        plugins = filterAvailablePlugins(state.availablePlugins, state.searchQuery)
                            .filter { matchesOrgFilter(it.orgSlug.ifEmpty { null }, state.orgFilter) },
                        installedIds = state.installedPlugins.map { it.pluginId }.toSet(),
                        updateIds = state.updates.map { it.pluginId }.toSet(),
                        onInstall = { pluginId -> viewModel.installFromRemote(pluginId) },
                        onUpdate = { pluginId -> viewModel.updatePlugin(pluginId) },
                        onDeleteFromStore = { pluginId -> viewModel.deleteFromStore(pluginId) },
                        onOpenHomepage = { url -> viewModel.openUrl(url) },
                        onShowVersions = { item ->
                            viewModel.openVersions(
                                item.pluginId,
                                item.displayName,
                                // Null when not installed — the sheet then offers "Install"
                                // per version instead of Update/Downgrade.
                                state.installedPlugins
                                    .find { it.pluginId == item.pluginId }?.version
                            )
                        },
                        canInstall = { item -> viewModel.canInstall(item) },
                        // No homepage fallback here: the store card's own title already opens
                        // the homepage, so an Open button that browsed there would duplicate it.
                        onOpenPlugin = { item -> viewModel.openPlugin(item.pluginId) },
                        openablePlugins = state.openablePlugins,
                        isStoreAdmin = state.isStoreAdmin,
                        isLoading = state.isLoading,
                        busyPlugins = state.busyPlugins,
                        permissionDescriptions = state.permissionDescriptions
                    )
                    PluginManagerTab.UPDATES -> UpdatesTab(
                        updates = state.updates
                            .filter {
                                matchesOrgFilter(
                                    provenanceByPluginId[it.pluginId]?.orgSlug,
                                    state.orgFilter
                                )
                            },
                        onUpdate = { id -> viewModel.updatePlugin(id) },
                        onUpdateAll = { viewModel.updateAllPlugins() },
                        isLoading = state.isLoading,
                        onOpenUrl = { url -> viewModel.openUrl(url) },
                        busyPlugins = state.busyPlugins,
                        provenanceByPluginId = provenanceByPluginId
                    )
                    PluginManagerTab.MCP -> McpToolsTab(viewModel)
                    PluginManagerTab.PUBLISH -> PublishTab(
                        canPublish = state.canPublish,
                        publishTargets = state.publishTargets,
                        organisationCta = organisationCta,
                        onOrganisationAction = { viewModel.onOrganisationCta() },
                        toolCreatorInstalled = state.installedPlugins.any {
                            it.pluginId == PluginManagerViewModel.TOOL_CREATOR_PLUGIN_ID
                        },
                        onOpenToolCreator = { viewModel.openToolCreator() },
                        onFetchFromGitHub = { url, onProgress, onStatus, onSuccess, onError ->
                            viewModel.fetchFromGitHubForPublish(url, onProgress, onStatus, onSuccess, onError)
                        },
                        onBrowseJar = { onResult -> viewModel.browseForPluginJar(onResult) },
                        onExtractManifest = { jarPath, onResult -> viewModel.extractManifest(jarPath, onResult) },
                        onPublish = { jarPath, pluginId, displayName, version, homepageUrl, authorName, description, changelog, tags, iconUrl, pluginType, apiVersion, minBossVersion, orgId, onProgress, onSuccess, onError ->
                            viewModel.publishPlugin(
                                jarPath = jarPath,
                                pluginId = pluginId,
                                displayName = displayName,
                                version = version,
                                homepageUrl = homepageUrl,
                                authorName = authorName,
                                description = description,
                                changelog = changelog,
                                tags = tags,
                                iconUrl = iconUrl,
                                pluginType = pluginType,
                                apiVersion = apiVersion,
                                minBossVersion = minBossVersion,
                                orgId = orgId,
                                onProgress = onProgress,
                                onSuccess = onSuccess,
                                onError = onError
                            )
                        },
                        isLoading = state.isLoading
                    )
                }
            }
        }

        // Version-history / downgrade sheet, at the root rather than inside a tab: it is
        // opened from BOTH the Installed and Store tabs, and its state lives in the shared
        // view state.
        state.versionSheet?.let { sheet ->
            VersionSheetDialog(
                sheet = sheet,
                busy = sheet.pluginId in state.busyPlugins,
                onInstall = { version -> viewModel.installVersion(sheet.pluginId, version) },
                onDismiss = { viewModel.closeVersions() }
            )
        }

        // After an update, prompt to reset running instances (or restart BOSS) so the
        // newly-installed version actually takes effect.
        state.postUpdatePrompt?.let { prompt ->
            when (prompt.kind) {
                PostUpdatePrompt.Kind.RESTART -> ConfirmationDialog(
                    title = "Update installed",
                    message = "${prompt.displayName} updated. Restart BOSS to apply the update?",
                    confirmText = "Restart BOSS",
                    onConfirm = { viewModel.confirmRestartApplication() },
                    onDismiss = { viewModel.dismissPostUpdatePrompt() }
                )
                PostUpdatePrompt.Kind.API_SWAP -> ConfirmationDialog(
                    title = "Update installed",
                    message = "${prompt.displayName} updated. Apply now? The API layer is " +
                        "hot-swapped — all plugins reload and their open tabs reset.",
                    confirmText = "Apply Now",
                    onConfirm = { viewModel.confirmApiSwap() },
                    onDismiss = { viewModel.dismissPostUpdatePrompt() }
                )
                PostUpdatePrompt.Kind.RESET -> {
                    val n = prompt.instanceCount
                    val plural = if (n == 1) "" else "s"
                    ConfirmationDialog(
                        title = "Update installed",
                        message = "${prompt.displayName} updated. Reset $n running instance$plural now to apply the update? Open tab$plural will close.",
                        confirmText = "Reset",
                        onConfirm = { viewModel.confirmResetInstances() },
                        onDismiss = { viewModel.dismissPostUpdatePrompt() }
                    )
                }
            }
        }

        // Rendered at the root, alongside the other dialogs, rather than inside
        // the Create tab: the tab unmounts when the user switches away, and a
        // dialog that disappears mid-typing because a click landed elsewhere is
        // worse than one that stays put.
        if (state.organisationRequestOpen) {
            RequestOrganisationDialog(
                busy = state.organisationRequestBusy,
                serverError = state.organisationRequestError,
                onSubmit = { slug, name, description, justification, domain, website ->
                    viewModel.submitOrganisationRequest(
                        slug, name, description, justification, domain, website,
                    )
                },
                onDismiss = { viewModel.dismissOrganisationRequest() }
            )
        }
    }
}

@Composable
private fun PluginManagerHeader(
    currentTab: PluginManagerTab,
    updateCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onTabSelected: (PluginManagerTab) -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    canPublish: Boolean,
    realtimeConnected: Boolean = false,
    /** Organisations present in the catalogue. Fewer than two renders no control. */
    orgSlugs: List<String> = emptyList(),
    /** The one currently selected, or null for all. */
    orgFilter: String? = null,
    onOrgFilterChange: (String?) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabButton(
            text = "Installed",
            selected = currentTab == PluginManagerTab.INSTALLED,
            onClick = { onTabSelected(PluginManagerTab.INSTALLED) }
        )
        TabButton(
            text = "Store",
            selected = currentTab == PluginManagerTab.AVAILABLE,
            onClick = { onTabSelected(PluginManagerTab.AVAILABLE) }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabButton(
                text = "Updates",
                selected = currentTab == PluginManagerTab.UPDATES,
                onClick = { onTabSelected(PluginManagerTab.UPDATES) }
            )
            if (updateCount > 0) {
                Spacer(Modifier.width(2.dp))
                BossBadge(count = updateCount)
            }
        }
        TabButton(
            text = "MCP",
            selected = currentTab == PluginManagerTab.MCP,
            onClick = { onTabSelected(PluginManagerTab.MCP) }
        )
        // Show Create tab to store admins and users with plugins.create.
        // Hosts creating (Tool Creator) + publishing to the store.
        if (canPublish) {
            TabButton(
                text = "Create",
                selected = currentTab == PluginManagerTab.PUBLISH,
                onClick = { onTabSelected(PluginManagerTab.PUBLISH) }
            )
        }

        Spacer(Modifier.width(8.dp))

        // Search bar
        BossSearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            placeholder = "Search...",
            modifier = Modifier.weight(1f)
        )

        // Organisation filter. Beside search because it is the same act - narrowing the list -
        // and it applies to whichever tab is showing, so it does not belong inside one of them.
        //
        // Hidden below two organisations: with everything published by one, every chip would
        // select the whole list and the control would be decoration that costs header width on a
        // panel that is often narrow.
        if (orgSlugs.size > 1) {
            Spacer(Modifier.width(6.dp))
            OrgFilterControl(
                slugs = orgSlugs,
                selected = orgFilter,
                onSelect = onOrgFilterChange
            )
        }

        Spacer(Modifier.width(4.dp))

        // Realtime status indicator
        if (realtimeConnected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(BossThemeColors.SuccessColor)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "Live",
                    color = BossThemeColors.SuccessColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Refresh button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = !isLoading) { onRefresh() }
                .padding(4.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = BossThemeColors.AccentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) BossThemeColors.AccentColor.copy(alpha = 0.15f)
                else BossThemeColors.BackgroundColor
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (selected) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.ErrorColor.copy(alpha = 0.15f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = BossThemeColors.ErrorColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                color = BossThemeColors.ErrorColor,
                fontSize = 13.sp
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onDismiss() }
                .padding(4.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = BossThemeColors.ErrorColor,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun InstalledPluginsTab(
    plugins: List<InstalledPluginState>,
    inaccessiblePlugins: List<InaccessiblePluginInfo> = emptyList(),
    updateIds: Set<String>,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onInstallFromFile: () -> Unit,
    onInstallFromGitHub: (String) -> Unit,
    onOpenHomepage: (String) -> Unit,
    /** Reveal the plugin's own panel/tab; falls back to its homepage when it has neither. */
    onOpenPlugin: (InstalledPluginState) -> Unit = {},
    /** Plugin ids with a panel or tab to open; the rest get no Open button. */
    openablePlugins: Set<String> = emptySet(),
    isLoading: Boolean,
    busyPlugins: Set<String> = emptySet(),
    onShowVersions: (InstalledPluginState) -> Unit = {},
    /**
     * Owning organisation slug per plugin id, from the store catalogue.
     *
     * Passed in rather than derived here so all three tabs read ONE map built once from
     * `availablePlugins`. A missing entry renders no badge, which is the honest answer for a
     * sideloaded jar and for the window before the store fetch returns.
     */
    provenanceByPluginId: Map<String, StoreProvenance> = emptyMap(),
    mcpToolRegistry: McpToolRegistry? = null,
    permissionDescriptions: Map<String, String> = emptyMap(),
    onExtractManifest: (String, (ExtractedManifest?) -> Unit) -> Unit = { _, cb -> cb(null) }
) {
    var showGitHubDialog by remember { mutableStateOf(false) }
    var gitHubUrl by remember { mutableStateOf("") }

    // Confirmation dialog state for uninstall
    var pluginToUninstall by remember { mutableStateOf<InstalledPluginState?>(null) }

    // Per-plugin MCP tools, for the card's MCP button + dialog.
    val allMcpTools = mcpToolRegistry?.allTools?.collectAsState()?.value ?: emptyList()
    val mcpToolsByPlugin = remember(allMcpTools) { allMcpTools.groupBy { it.providerId } }
    var mcpDialogPlugin by remember { mutableStateOf<InstalledPluginState?>(null) }

    // Manifest of the plugin whose MCP dialog is open — carries the plugin-level
    // requiredPermissions + plugin-defined permission descriptions.
    var mcpDialogManifest by remember { mutableStateOf<ExtractedManifest?>(null) }
    LaunchedEffect(mcpDialogPlugin) {
        mcpDialogManifest = null
        mcpDialogPlugin?.let { plugin ->
            onExtractManifest(plugin.jarPath) { manifest ->
                // The extraction runs on the VM scope and outlives this effect —
                // guard so a slow read for plugin A can't populate the dialog
                // after the user switched to plugin B (wrong permissions shown).
                if (mcpDialogPlugin?.pluginId == plugin.pluginId) mcpDialogManifest = manifest
            }
        }
    }

    // Permissions dialog (lock button on the card) — same manifest-read pattern.
    var permDialogPlugin by remember { mutableStateOf<InstalledPluginState?>(null) }
    var permDialogManifest by remember { mutableStateOf<ExtractedManifest?>(null) }
    LaunchedEffect(permDialogPlugin) {
        permDialogManifest = null
        permDialogPlugin?.let { plugin ->
            onExtractManifest(plugin.jarPath) { manifest ->
                if (permDialogPlugin?.pluginId == plugin.pluginId) permDialogManifest = manifest
            }
        }
    }
    permDialogPlugin?.let { plugin ->
        PermissionsDialog(
            displayName = plugin.displayName,
            requiredPermissions = permDialogManifest?.requiredPermissions,
            definedPermissions = permDialogManifest?.definedPermissions.orEmpty(),
            permissionDescriptions = permissionDescriptions,
            toolPermissions = mcpToolsByPlugin[plugin.pluginId].orEmpty().flatMap { t ->
                buildList {
                    if (t.definition.requiresAdmin) add("admin")
                    addAll(t.definition.requiredPermissions)
                }
            }.distinct(),
            onDismiss = { permDialogPlugin = null }
        )
    }

    // Per-plugin MCP tools dialog (mirrors the versions sheet).
    mcpDialogPlugin?.let { plugin ->
        if (mcpToolRegistry != null) {
            McpToolsDialog(
                displayName = plugin.displayName,
                tools = mcpToolsByPlugin[plugin.pluginId].orEmpty(),
                registry = mcpToolRegistry,
                manifest = mcpDialogManifest,
                permissionDescriptions = permissionDescriptions,
                onDismiss = { mcpDialogPlugin = null }
            )
        }
    }

    // Show confirmation dialog for uninstall
    pluginToUninstall?.let { plugin ->
        ConfirmationDialog(
            title = "Uninstall Plugin",
            message = "Are you sure you want to uninstall \"${plugin.displayName}\"? This action cannot be undone.",
            confirmText = "Uninstall",
            onConfirm = { onUninstall(plugin.pluginId) },
            onDismiss = { pluginToUninstall = null }
        )
    }

    // ONE scrollable surface for the whole tab. The banner, the install buttons and the GitHub
    // form used to sit in a fixed Column above a LazyColumn, so only the plugin list scrolled —
    // on a short panel that left the list a two-row window under an immovable header.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Banner: installed plugins hidden from this user for lack of permissions.
        if (inaccessiblePlugins.isNotEmpty()) {
            item(key = "inaccessible-banner") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            BossThemeColors.WarningColor.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${inaccessiblePlugins.size} installed plugin(s) hidden — you lack the required permission(s)",
                        color = BossThemeColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    inaccessiblePlugins.forEach { p ->
                        Text(
                            text = "• ${p.displayName} — ask an admin to grant: ${p.missingPermissions.joinToString(", ")}",
                            color = BossThemeColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Install section
        item(key = "install-actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BossSecondaryButton(
                    text = "From File",
                    onClick = onInstallFromFile,
                    enabled = !isLoading,
                    icon = Icons.Default.Download,
                    modifier = Modifier.weight(1f)
                )
                BossPrimaryButton(
                    text = "From GitHub",
                    onClick = { showGitHubDialog = true },
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // GitHub URL input
        if (showGitHubDialog) {
            item(key = "github-form") {
                BossCard {
                    BossTextField(
                        value = gitHubUrl,
                        onValueChange = { gitHubUrl = it },
                        label = "GitHub URL",
                        placeholder = "https://github.com/owner/repo",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        BossSecondaryButton(
                            text = "Cancel",
                            onClick = {
                                showGitHubDialog = false
                                gitHubUrl = ""
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        BossPrimaryButton(
                            text = "Install",
                            onClick = {
                                val trimmedUrl = gitHubUrl.trim()
                                if (trimmedUrl.isNotBlank()) {
                                    onInstallFromGitHub(trimmedUrl)
                                    showGitHubDialog = false
                                    gitHubUrl = ""
                                }
                            },
                            enabled = gitHubUrl.trim().isNotBlank()
                        )
                    }
                }
            }
        }

        // Installed plugins list
        if (plugins.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier.fillParentMaxWidth().fillParentMaxHeight(0.6f),
                    contentAlignment = Alignment.Center
                ) {
                    BossEmptyState(
                        icon = Icons.Default.Extension,
                        message = "No plugins installed",
                        description = "Install from file or GitHub"
                    )
                }
            }
        } else {
            items(plugins, key = { it.pluginId }) { plugin ->
                InstalledPluginCard(
                    plugin = plugin,
                    hasUpdate = plugin.pluginId in updateIds,
                    onToggleEnabled = { onToggleEnabled(plugin.pluginId, it) },
                    onUninstall = { pluginToUninstall = plugin },
                    onUpdate = { onUpdate(plugin.pluginId) },
                    onOpenHomepage = { plugin.url?.let { onOpenHomepage(it) } },
                    onOpenPlugin = { onOpenPlugin(plugin) },
                    onOpenPage = PluginPageUrl.forPlugin(
                        provenanceByPluginId[plugin.pluginId]?.orgSlug.orEmpty(),
                        plugin.pluginId,
                    )?.let { url -> { onOpenHomepage(url) } },
                    canOpen = plugin.pluginId in openablePlugins,
                    onShowVersions = { onShowVersions(plugin) },
                    isLoading = plugin.pluginId in busyPlugins,
                    author = provenanceByPluginId[plugin.pluginId]?.author.orEmpty(),
                    orgSlug = provenanceByPluginId[plugin.pluginId]?.orgSlug.orEmpty(),
                    mcpToolCount = mcpToolsByPlugin[plugin.pluginId]?.size ?: 0,
                    onShowMcp = { mcpDialogPlugin = plugin },
                    onShowPermissions = { permDialogPlugin = plugin }
                )
            }
        }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: InstalledPluginState,
    hasUpdate: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
    onOpenHomepage: () -> Unit,
    /** Reveal the plugin itself (its panel or tab). */
    onOpenPlugin: () -> Unit,
    /**
     * Open this plugin's page on the web. Null when it has none, which is the normal answer for a
     * sideloaded jar: the page lives under the organisation that owns the plugin, and nothing on
     * disk records one.
     */
    onOpenPage: (() -> Unit)? = null,
    /** True when the plugin has a panel or tab to open, which is what renders the Open button. */
    canOpen: Boolean = false,
    onShowVersions: () -> Unit,
    isLoading: Boolean,
    /**
     * Who published it, and the organisation that owns it. Empty when unknown.
     *
     * BOTH resolved by the caller from the store catalogue rather than read off [plugin]: an
     * installed plugin is known from `installed.json` and the jar on disk, and neither records an
     * author or an organisation. Empty is the normal answer for a sideloaded jar, and renders no
     * provenance line at all.
     */
    author: String = "",
    orgSlug: String = "",
    /** Number of MCP tools this plugin contributes; 0 hides the MCP button. */
    mcpToolCount: Int = 0,
    onShowMcp: () -> Unit = {},
    onShowPermissions: () -> Unit = {}
) {
    val hasHomepage = !plugin.url.isNullOrBlank()
    // The WHOLE card opens the plugin's page, padding included, because BossCard puts the click on
    // its own Surface. The buttons and the homepage icon inside consume their own clicks, so they
    // keep working; only the parts of the card that did nothing before are new.
    val openCard: () -> Unit = onOpenPage ?: onOpenPlugin

    BossCard(onClick = openCard) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // No click of its own any more: the card owns it, so the ripple covers the whole row
            // rather than stopping at this column's edge. Launching the plugin has not been lost -
            // that is the Open button, which renders whenever there is something to launch - and a
            // plugin with no page (a sideloaded jar) still launches from the card.
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.displayName,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (hasHomepage) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Open homepage",
                            // Generous padding on purpose: this sits INSIDE a now-clickable
                            // full-width row, so a near miss opens the plugin instead.
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onOpenHomepage() }
                                .padding(6.dp)
                                .size(12.dp),
                            tint = BossThemeColors.AccentColor
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "v${plugin.version ?: "?"}",
                        color = BossThemeColors.TextMuted,
                        fontSize = 11.sp
                    )
                    if (hasUpdate) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Upgrade,
                            contentDescription = "Update available",
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.AccentColor
                        )
                    }
                    if (plugin.isIncompatible) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Plugin incompatible",
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.ErrorColor
                        )
                    } else if (!plugin.healthy) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Plugin unhealthy",
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.WarningColor
                        )
                    }
                }
                if (plugin.isIncompatible) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (hasUpdate) "Incompatible with this version of BOSS. Update available."
                               else "Incompatible with this version of BOSS.",
                        color = BossThemeColors.ErrorColor,
                        fontSize = 12.sp
                    )
                } else if (plugin.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = plugin.description,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ProvenanceLine(author = author, orgSlug = orgSlug)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Clicking the row already opens the plugin, but that is an invisible
                // affordance — nothing about a card says it is a launcher. The button is the
                // one part of this card that names what the Toolbox is for.
                if (canOpen) {
                    BossSecondaryButton(
                        text = "Open",
                        onClick = onOpenPlugin,
                        // Disabled, not hidden, mid-install/update: the panel is about to be
                        // unregistered and re-registered, so opening now races the reload.
                        enabled = !isLoading && plugin.enabled && !plugin.isIncompatible,
                        icon = Icons.Default.Launch
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (hasUpdate) {
                    BossPrimaryButton(
                        text = "Update",
                        onClick = onUpdate,
                        enabled = !isLoading,
                        icon = Icons.Default.Upgrade
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isLoading) { onShowPermissions() }
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Permissions",
                        modifier = Modifier.size(15.dp),
                        tint = BossThemeColors.TextSecondary
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (mcpToolCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isLoading) { onShowMcp() }
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = "MCP tools",
                                modifier = Modifier.size(14.dp),
                                tint = BossThemeColors.AccentColor
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "$mcpToolCount",
                                color = BossThemeColors.AccentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isLoading) { onShowVersions() }
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.List,
                        contentDescription = "Versions & compatibility",
                        modifier = Modifier.size(16.dp),
                        tint = BossThemeColors.TextSecondary
                    )
                }
                Spacer(Modifier.width(8.dp))
                BossToggle(
                    label = "",
                    checked = plugin.enabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = !isLoading && !plugin.isIncompatible,
                    modifier = Modifier.width(60.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isLoading && plugin.canUnload) { onUninstall() }
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Uninstall",
                        modifier = Modifier.size(16.dp),
                        tint = if (plugin.canUnload)
                            BossThemeColors.ErrorColor
                        else
                            BossThemeColors.TextMuted.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailablePluginsTab(
    plugins: List<PluginStoreItem>,
    installedIds: Set<String>,
    updateIds: Set<String>,
    onInstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onDeleteFromStore: (String) -> Unit,
    onOpenHomepage: (String) -> Unit,
    /** Open the version-history sheet — install/update/downgrade to any published version. */
    onShowVersions: (PluginStoreItem) -> Unit = {},
    canInstall: (PluginStoreItem) -> Boolean = { true },
    /** Reveal an already-installed plugin's own panel/tab. */
    onOpenPlugin: (PluginStoreItem) -> Unit = {},
    /** Plugin ids with a panel or tab to open; the rest get no Open button. */
    openablePlugins: Set<String> = emptySet(),
    isStoreAdmin: Boolean,
    isLoading: Boolean,
    busyPlugins: Set<String> = emptySet(),
    permissionDescriptions: Map<String, String> = emptyMap()
) {
    // Confirmation dialog state for delete from store
    var pluginToDelete by remember { mutableStateOf<PluginStoreItem?>(null) }

    // Permissions dialog for a store item (its requiredPermissions ship with
    // the store listing — no manifest read needed).
    var permDialogItem by remember { mutableStateOf<PluginStoreItem?>(null) }
    permDialogItem?.let { item ->
        PermissionsDialog(
            displayName = item.displayName,
            requiredPermissions = item.requiredPermissions,
            definedPermissions = emptyList(),
            permissionDescriptions = permissionDescriptions,
            onDismiss = { permDialogItem = null }
        )
    }

    // Show delete-from-store dialog. When a delete password is baked into the build, require it;
    // otherwise fall back to a plain confirmation (e.g. dev builds with no hash configured).
    pluginToDelete?.let { plugin ->
        if (DeletePasswordGate.isConfigured) {
            PasswordDialog(
                title = "Delete from Store",
                message = "Enter the admin password to delete \"${plugin.displayName}\" from the plugin store. This action cannot be undone.",
                onConfirmed = { onDeleteFromStore(plugin.pluginId) },
                onDismiss = { pluginToDelete = null }
            )
        } else {
            ConfirmationDialog(
                title = "Delete from Store",
                message = "Are you sure you want to delete \"${plugin.displayName}\" from the plugin store? This action cannot be undone.",
                confirmText = "Delete",
                onConfirm = { onDeleteFromStore(plugin.pluginId) },
                onDismiss = { pluginToDelete = null }
            )
        }
    }

    if (plugins.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = BossThemeColors.AccentColor,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Loading plugins...",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                BossEmptyState(
                    icon = Icons.Default.Extension,
                    message = "No plugins available",
                    description = "Check back later"
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(plugins, key = { it.pluginId }) { plugin ->
                AvailablePluginCard(
                    plugin = plugin,
                    isInstalled = plugin.pluginId in installedIds,
                    hasUpdate = plugin.pluginId in updateIds,
                    onInstall = { onInstall(plugin.pluginId) },
                    onUpdate = { onUpdate(plugin.pluginId) },
                    onDeleteFromStore = { pluginToDelete = plugin },
                    onOpenHomepage = { if (plugin.url.isNotBlank()) onOpenHomepage(plugin.url) },
                    onShowVersions = { onShowVersions(plugin) },
                    canInstall = canInstall(plugin),
                    canOpen = plugin.pluginId in openablePlugins,
                    onOpenPlugin = { onOpenPlugin(plugin) },
                    onOpenPage = PluginPageUrl.forPlugin(plugin.orgSlug, plugin.pluginId)
                        ?.let { url -> { onOpenHomepage(url) } },
                    isStoreAdmin = isStoreAdmin,
                    isLoading = plugin.pluginId in busyPlugins,
                    onShowPermissions = { permDialogItem = plugin }
                )
            }
        }
    }
}

@Composable
private fun AvailablePluginCard(
    plugin: PluginStoreItem,
    isInstalled: Boolean,
    hasUpdate: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onDeleteFromStore: () -> Unit,
    onOpenHomepage: () -> Unit,
    onShowVersions: () -> Unit = {},
    canInstall: Boolean = true,
    /** True when the plugin is installed AND has a panel or tab to open. */
    canOpen: Boolean = false,
    onOpenPlugin: () -> Unit = {},
    /** Open this plugin's page on the web. Null when the catalogue row names no organisation. */
    onOpenPage: (() -> Unit)? = null,
    isStoreAdmin: Boolean,
    isLoading: Boolean,
    onShowPermissions: () -> Unit = {}
) {
    val hasHomepage = plugin.url.isNotBlank()
    // The whole card opens the plugin's page, falling back to the homepage for a catalogue row
    // that names no organisation and so has no page.
    val openCard: (() -> Unit)? = onOpenPage ?: onOpenHomepage.takeIf { hasHomepage }

    BossCard(onClick = openCard) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.displayName,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (hasHomepage) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Open homepage",
                            modifier = Modifier.size(12.dp),
                            tint = BossThemeColors.AccentColor
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "v${plugin.version ?: "?"}",
                        color = BossThemeColors.TextMuted,
                        fontSize = 11.sp
                    )
                    if (plugin.verified) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Verified",
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.SuccessColor
                        )
                    }
                }
                if (plugin.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = plugin.description,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ProvenanceLine(author = plugin.author, orgSlug = plugin.orgSlug)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Permissions button on every store card — the dialog explains
                // what's required (or that the plugin is open to all users).
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onShowPermissions() }
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Permissions",
                        modifier = Modifier.size(15.dp),
                        tint = if (plugin.requiredPermissions.isNotEmpty())
                            BossThemeColors.WarningColor
                        else
                            BossThemeColors.TextSecondary
                    )
                }
                Spacer(Modifier.width(4.dp))
                // System / library plugins (type=service, e.g. microkernel
                // runtime) ship through the plugin store but aren't user-
                // installable — the host's auto-installer fetches them on
                // launch when they're needed. Show a status badge instead
                // of an Install/Update button so the entry stays
                // discoverable but the broken click path is gone.
                val isSystemComponent = plugin.type.equals("service", ignoreCase = true)
                // Version picker — the same sheet the Installed tab opens, so a specific
                // version can be installed from the store rather than only the latest.
                // Hidden for system components, and for users who can neither install this
                // plugin nor already have it — deliberately broader than the Install button,
                // since an installed plugin stays re-versionable however it got here.
                if (!isSystemComponent && (canInstall || isInstalled)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isLoading) { onShowVersions() }
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = "Versions & compatibility",
                            modifier = Modifier.size(16.dp),
                            tint = BossThemeColors.TextSecondary
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // Installed and openable: launching the tool is the natural next step after
                // installing it, and otherwise means leaving the store to hunt for its sidebar
                // icon. Sits BEFORE the status below rather than replacing it — the button says
                // what you can do, the label still says what state the plugin is in.
                if (canOpen) {
                    BossSecondaryButton(
                        text = "Open",
                        onClick = onOpenPlugin,
                        enabled = !isLoading,
                        icon = Icons.Default.Launch
                    )
                    Spacer(Modifier.width(8.dp))
                }
                when {
                    isSystemComponent -> {
                        Text(
                            text = "System • Managed",
                            color = BossThemeColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    hasUpdate -> {
                        BossPrimaryButton(
                            text = "Update",
                            onClick = onUpdate,
                            enabled = !isLoading,
                            icon = Icons.Default.Upgrade
                        )
                    }
                    isInstalled -> {
                        Text(
                            text = "Installed",
                            color = BossThemeColors.SuccessColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    !canInstall -> {
                        // The user lacks the permission(s) this plugin requires to
                        // install/use. The server would reject the download (403),
                        // so we surface the reason instead of offering Install.
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = BossThemeColors.WarningColor
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Ask an admin",
                                    color = BossThemeColors.WarningColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (plugin.requiredPermissions.isNotEmpty()) {
                                Text(
                                    text = "Requires: ${plugin.requiredPermissions.joinToString(", ")}",
                                    color = BossThemeColors.TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        BossPrimaryButton(
                            text = "Install",
                            onClick = onInstall,
                            enabled = !isLoading,
                            icon = Icons.Default.Download
                        )
                    }
                }

                // Delete button for store admins
                if (isStoreAdmin) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isLoading) { onDeleteFromStore() }
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete from store",
                            modifier = Modifier.size(16.dp),
                            tint = BossThemeColors.ErrorColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatesTab(
    updates: List<UpdateInfo>,
    onUpdate: (String) -> Unit,
    onUpdateAll: () -> Unit,
    isLoading: Boolean,
    busyPlugins: Set<String> = emptySet(),
    /**
     * Owning organisation slug per plugin id, from the store catalogue.
     *
     * Passed in rather than derived here so all three tabs read ONE map built once from
     * `availablePlugins`. A missing entry renders no badge, which is the honest answer for a
     * sideloaded jar and for the window before the store fetch returns.
     */
    provenanceByPluginId: Map<String, StoreProvenance> = emptyMap(),
    /** Open a web address. Used for the plugin page each row links to. */
    onOpenUrl: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (updates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                BossEmptyState(
                    icon = Icons.Default.Check,
                    message = "All plugins are up to date",
                    description = "No updates available"
                )
            }
        } else {
            // Update All section
            BossSection(
                title = "Available Updates",
                description = "${updates.size} update${if (updates.size != 1) "s" else ""} available"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BossPrimaryButton(
                        text = "Update All (${updates.size})",
                        onClick = onUpdateAll,
                        enabled = busyPlugins.isEmpty() && !isLoading
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(updates, key = { it.pluginId }) { update ->
                    UpdateCard(
                        update = update,
                        onUpdate = { onUpdate(update.pluginId) },
                        isLoading = update.pluginId in busyPlugins,
                        author = provenanceByPluginId[update.pluginId]?.author.orEmpty(),
                        orgSlug = provenanceByPluginId[update.pluginId]?.orgSlug.orEmpty(),
                        onOpenPage = PluginPageUrl.forPlugin(
                            provenanceByPluginId[update.pluginId]?.orgSlug.orEmpty(),
                            update.pluginId,
                        )?.let { url -> { onOpenUrl(url) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    update: UpdateInfo,
    onUpdate: () -> Unit,
    isLoading: Boolean,
    /**
     * Who the NEW version comes from. Empty when unknown.
     *
     * Worth showing here more than anywhere else: this card is a button that replaces running code,
     * and this says who the replacement is coming from. Resolved from the store catalogue, which is
     * where the update itself was found.
     */
    author: String = "",
    orgSlug: String = "",
    /** Open the plugin's page on the web. Null when the store row names no organisation. */
    onOpenPage: (() -> Unit)? = null
) {
    // The whole card, like the other two tabs. Null when the store row names no organisation, and
    // BossCard then renders exactly as it did before rather than a card that looks pressable and
    // does nothing.
    BossCard(onClick = onOpenPage) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = update.displayName,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (update.critical) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(BossThemeColors.ErrorColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Critical",
                                color = BossThemeColors.ErrorColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${update.currentVersion} → ${update.newVersion}",
                    color = BossThemeColors.AccentColor,
                    fontSize = 12.sp
                )
                if (update.changelog.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = update.changelog,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ProvenanceLine(author = author, orgSlug = orgSlug)
            }

            BossPrimaryButton(
                text = "Update",
                onClick = onUpdate,
                enabled = !isLoading
            )
        }
    }
}

/**
 * MCP Tools tab — lists every MCP tool contributed by an active plugin, grouped
 * by plugin, each with an enable/disable switch. Disabling a tool removes it
 * from the live `boss` MCP server (persisted). Built-in terminal tools are
 * managed separately in Terminal settings.
 */
@Composable
private fun McpToolsTab(viewModel: PluginManagerViewModel) {
    val registry = viewModel.mcpToolRegistry
    if (registry == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BossEmptyState(
                icon = Icons.Default.Extension,
                message = "MCP tooling unavailable",
                description = "The host does not expose an MCP tool registry."
            )
        }
        return
    }

    val allTools by registry.allTools.collectAsState()
    val disabled by registry.disabledToolNames.collectAsState()
    val exposed by registry.tools.collectAsState()
    val exposedNames = remember(exposed) { exposed.map { it.definition.name }.toSet() }
    val state by viewModel.state.collectAsState()
    val nameById = remember(state.installedPlugins) {
        state.installedPlugins.associate { it.pluginId to it.displayName }
    }

    // Filter tools by the header search query (name, description, or plugin).
    val query = state.searchQuery.trim()
    val visibleTools = remember(allTools, query, nameById) {
        if (query.isEmpty()) allTools
        else allTools.filter { t ->
            t.definition.name.contains(query, ignoreCase = true) ||
                t.definition.description.contains(query, ignoreCase = true) ||
                t.providerId.contains(query, ignoreCase = true) ||
                (nameById[t.providerId]?.contains(query, ignoreCase = true) == true)
        }
    }
    val groups = remember(visibleTools) { visibleTools.groupBy { it.providerId }.entries.toList() }
    // Unfiltered per-plugin tools, so the "N/M on" section counts reflect the
    // plugin's FULL tool set even while a search filter narrows the rows shown.
    val allByProvider = remember(allTools) { allTools.groupBy { it.providerId } }

    // One scrollable surface for the whole tab — header, server controls, and
    // tool groups all scroll together.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "mcp-header") {
            Column {
                Text(
                    text = "Plugin MCP Tools",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tools contributed by active plugins, exposed to in-terminal agents as " +
                        "mcp__boss__*. Toggle a tool off to hide it from agents (built-in terminal " +
                        "tools are managed in Terminal settings). Use the search bar above to filter tools.",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Server on/off + CLI attach. Resolved per-render: terminal-tab (which
        // provides this API) loads after plugin-manager, so it starts null.
        item(key = "mcp-server") {
            McpServerSection(viewModel.mcpServerControllerProvider())
        }

        if (visibleTools.isEmpty()) {
            item(key = "mcp-empty") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (query.isEmpty()) {
                        BossEmptyState(
                            icon = Icons.Default.List,
                            message = "No plugin MCP tools",
                            description = "Tools appear here when a plugin that provides them is active."
                        )
                    } else {
                        BossEmptyState(
                            icon = Icons.Default.List,
                            message = "No tools match \"$query\"",
                            description = "Try a different search — tools match by name, description, or plugin."
                        )
                    }
                }
            }
        } else {
            items(groups, key = { it.key }) { (providerId, tools) ->
                    val display = nameById[providerId] ?: providerId.substringAfterLast('.')
                    val fullTools = allByProvider[providerId].orEmpty()
                    val onCount = fullTools.count { it.definition.name in exposedNames }
                    BossSection(
                        title = display,
                        description = "$onCount/${fullTools.size} on • $providerId"
                    ) {
                        tools.forEach { tool ->
                            val def = tool.definition
                            val name = def.name
                            val on = name in exposedNames
                            val userDisabled = name in disabled
                            val permissionDenied = !on && !userDisabled
                            val perms = buildList {
                                if (def.requiresAdmin) add("admin")
                                addAll(def.requiredPermissions)
                            }
                            val desc = buildString {
                                append(def.description)
                                if (perms.isNotEmpty()) append("  ·  requires: ${perms.joinToString(", ")}")
                                if (permissionDenied) append("  ·  🔒 no permission")
                            }
                            BossToggle(
                                label = name,
                                checked = on,
                                onCheckedChange = { enable -> registry.setToolEnabled(name, enable) },
                                description = desc,
                                enabled = !permissionDenied
                            )
                        }
                    }
            }
        }
    }
}

/**
 * MCP server controls: on/off toggle plus one-click attach of the `boss`
 * endpoint to AI CLIs (Claude Code, Codex, Gemini, OpenCode). Backed by the
 * terminal-tab plugin's [McpServerController]; renders a hint when that plugin
 * hasn't loaded (yet).
 */
@Composable
private fun McpServerSection(controller: McpServerController?) {
    if (controller == null) {
        BossSection(
            title = "MCP Server",
            description = "Server controls unavailable — the Terminal Tab plugin (which hosts the MCP server) is not loaded."
        ) {}
        return
    }

    val serverState by controller.state.collectAsState()
    val targets by controller.attachTargets.collectAsState()
    val scope = rememberCoroutineScope()
    var attachStatus by remember { mutableStateOf<String?>(null) }
    var attachingKey by remember { mutableStateOf<String?>(null) }

    BossSection(
        title = "MCP Server",
        description = if (serverState.running) {
            "${serverState.serverName} — running on 127.0.0.1:${serverState.port}"
        } else {
            "${serverState.serverName} — stopped"
        }
    ) {
        BossToggle(
            label = "Enable MCP server",
            checked = serverState.enabled,
            onCheckedChange = { controller.setEnabled(it) },
            description = "Serves mcp__${serverState.serverName}__* tools to AI agents over loopback."
        )

        Spacer(Modifier.height(10.dp))
        // Port editor. Seeded from the live state (bound port while running,
        // configured port otherwise) and re-seeded after an apply once the
        // server rebinds. setPort persists + reconciles server-side; attached
        // CLIs re-register on the new endpoint automatically.
        var portText by remember(serverState.port) {
            mutableStateOf(serverState.port?.toString() ?: "")
        }
        val parsedPort = portText.toIntOrNull()
        val portValid = parsedPort != null && parsedPort in 1024..65535
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BossTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = "Port",
                placeholder = "7677",
                modifier = Modifier.width(140.dp)
            )
            BossSecondaryButton(
                text = "Apply",
                onClick = {
                    if (parsedPort != null) {
                        attachStatus = try {
                            controller.setPort(parsedPort)
                            "Port set to $parsedPort — server restarting; attached CLIs re-register automatically."
                        } catch (e: LinkageError) {
                            // Host or terminal-tab predates McpServerController.setPort
                            // (NoSuchMethodError / AbstractMethodError) — degrade gracefully.
                            "Changing the port here needs updated BOSS and Terminal Tab versions."
                        }
                    }
                },
                enabled = portValid && parsedPort != serverState.port
            )
        }
        if (portText.isNotEmpty() && !portValid) {
            Text(
                text = "Port must be between 1024 and 65535.",
                color = BossThemeColors.TextSecondary,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Attach to AI CLIs",
            color = BossThemeColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        // Single inline row of attach buttons; scrolls horizontally if the
        // panel is too narrow to fit all four.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            targets.forEach { t ->
                BossSecondaryButton(
                    text = (if (t.attached) "✓ " else "") + t.displayName,
                    onClick = {
                        attachingKey = t.key
                        attachStatus = "Attaching ${t.displayName}…"
                        scope.launch {
                            val outcome = try {
                                controller.attach(t.key)
                            } catch (e: Exception) {
                                ai.rever.boss.plugin.api.McpAttachOutcome(false, "Attach failed: ${e.message}")
                            }
                            attachStatus = outcome.message
                            attachingKey = null
                        }
                    },
                    enabled = serverState.running && attachingKey == null
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        attachStatus?.let { status ->
            Text(
                text = status,
                color = BossThemeColors.TextSecondary,
                fontSize = 11.sp
            )
        }
        if (!serverState.running) {
            Text(
                text = "Turn the server on to attach CLIs.",
                color = BossThemeColors.TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Source selection for plugin JAR.
 */
private enum class JarSource {
    GITHUB,
    LOCAL_FILE
}

@Composable
private fun PublishTab(
    /**
     * False for a user who reached this tab only for the organisation call to action - the
     * tab is revealed for them (see [organisationCtaNeedsCreateTab]), the publishing surfaces
     * are not.
     */
    canPublish: Boolean,
    /**
     * Organisations this user may publish for, from the server's own `can_publish`.
     *
     * Empty renders no picker and sends no `orgId`, which leaves the server to derive it exactly
     * as it did before this control existed - so a user the server cannot answer for is no worse
     * off than they were.
     */
    publishTargets: List<PublishTarget>,
    organisationCta: OrganisationCta?,
    onOrganisationAction: () -> Unit,
    toolCreatorInstalled: Boolean,
    onOpenToolCreator: () -> Unit,
    onFetchFromGitHub: (
        url: String,
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit,
        onSuccess: (jarPath: String, manifest: ExtractedManifest) -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onBrowseJar: ((String?) -> Unit) -> Unit,
    onExtractManifest: (String, (ExtractedManifest?) -> Unit) -> Unit,
    onPublish: (
        jarPath: String,
        pluginId: String,
        displayName: String,
        version: String,
        homepageUrl: String,
        authorName: String,
        description: String?,
        changelog: String?,
        tags: List<String>,
        iconUrl: String?,
        pluginType: String,
        apiVersion: String,
        minBossVersion: String,
        orgId: String?,
        onProgress: (Float) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    isLoading: Boolean
) {
    // Nothing to publish with, so the tab is nothing but the organisation card. Returning
    // early keeps the publishing state below out of composition entirely.
    if (!canPublish) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OrganisationCtaCard(cta = organisationCta, onAction = onOrganisationAction)
        }
        return
    }

    // Defaults to BOSS when it is on offer, because that is where a plugin belongs unless
    // somebody decides otherwise - it is the platform's own store, and it is what every plugin
    // published before this control existed was attributed to. Falling back to the sole target
    // covers a user who cannot publish for boss but can for exactly one other organisation; with
    // several and no boss it starts unset and the server derives, as it did before.
    var selectedOrgId by remember(publishTargets) {
        mutableStateOf(
            publishTargets.firstOrNull { it.slug == SYSTEM_ORG_SLUG }?.orgId
                ?: publishTargets.singleOrNull()?.orgId
        )
    }
    var pickingOrg by remember { mutableStateOf(false) }

    var jarSource by remember { mutableStateOf(JarSource.GITHUB) }
    var gitHubUrl by remember { mutableStateOf("") }
    var fetchProgress by remember { mutableStateOf(0f) }
    var fetchStatus by remember { mutableStateOf<String?>(null) }
    var isFetching by remember { mutableStateOf(false) }

    var jarPath by remember { mutableStateOf("") }
    var pluginId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var homepageUrl by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var changelog by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var iconUrl by remember { mutableStateOf("") }
    var pluginType by remember { mutableStateOf(PluginType.PANEL) }
    var apiVersion by remember { mutableStateOf("1.0") }
    var minBossVersion by remember { mutableStateOf("1.0.0") }
    var publishProgress by remember { mutableStateOf(0f) }
    var publishStatus by remember { mutableStateOf<String?>(null) }
    var isPublishing by remember { mutableStateOf(false) }
    var showTypeDropdown by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        OrganisationCtaCard(cta = organisationCta, onAction = onOrganisationAction)

        BossSection(
            title = "Create a new plugin",
            description = "Scaffold a new BOSS plugin and build it with an AI coding agent"
        ) {
            Text(
                text = if (toolCreatorInstalled) {
                    "Opens Tool Creator: name your tool, pick permissions and a CLI (Claude Code, Codex, Gemini, OpenCode), and it scaffolds the repo + CI and starts building."
                } else {
                    "Tool Creator isn't installed yet. Install it from the store to scaffold new plugins from here."
                },
                fontSize = 13.sp,
                color = BossThemeColors.TextSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            BossPrimaryButton(
                text = if (toolCreatorInstalled) "Create a new plugin…" else "Install Tool Creator",
                onClick = onOpenToolCreator,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (pickingOrg) {
            PublishOrgDialog(
                targets = publishTargets,
                selectedOrgId = selectedOrgId,
                onSelect = {
                    selectedOrgId = it
                    pickingOrg = false
                },
                onDismiss = { pickingOrg = false }
            )
        }

        BossSection(
            title = "Publish Plugin",
            description = "Upload your plugin to the BOSS Plugin Store"
        ) {
            // WHO it will belong to, under this heading with the rest of the submission and
            // FIRST, because it is decided once and never again: a version publish does not
            // re-derive ownership, so unlike every field below it this one cannot be corrected by
            // republishing.
            //
            // Hidden when the server names no target. No orgId is then sent and the server
            // derives it exactly as before, so a user it cannot answer for is no worse off.
            if (publishTargets.isNotEmpty()) {
                PublishOrgField(
                    targets = publishTargets,
                    selectedOrgId = selectedOrgId,
                    enabled = !isPublishing && !isFetching,
                    onClick = { pickingOrg = true }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Source selection tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (jarSource == JarSource.GITHUB) BossThemeColors.AccentColor.copy(alpha = 0.15f)
                            else BossThemeColors.SurfaceColor
                        )
                        .clickable(enabled = !isLoading && !isPublishing && !isFetching) { jarSource = JarSource.GITHUB }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "From GitHub",
                        color = if (jarSource == JarSource.GITHUB) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (jarSource == JarSource.GITHUB) FontWeight.Medium else FontWeight.Normal
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (jarSource == JarSource.LOCAL_FILE) BossThemeColors.AccentColor.copy(alpha = 0.15f)
                            else BossThemeColors.SurfaceColor
                        )
                        .clickable(enabled = !isLoading && !isPublishing && !isFetching) { jarSource = JarSource.LOCAL_FILE }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "From Local File",
                        color = if (jarSource == JarSource.LOCAL_FILE) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (jarSource == JarSource.LOCAL_FILE) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // JAR source selection
            BossCard {
                when (jarSource) {
                    JarSource.GITHUB -> {
                        Column {
                            Text(
                                text = "GitHub Repository",
                                color = BossThemeColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            BossTextField(
                                value = gitHubUrl,
                                onValueChange = { gitHubUrl = it },
                                label = "",
                                placeholder = "https://github.com/owner/repo",
                                enabled = !isLoading && !isPublishing && !isFetching,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))

                            // Fetch progress
                            if (isFetching) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(
                                        progress = fetchProgress,
                                        color = BossThemeColors.AccentColor,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    if (fetchStatus != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = fetchStatus!!,
                                            color = BossThemeColors.TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (jarPath.isNotEmpty()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = BossThemeColors.SuccessColor
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = jarPath.substringAfterLast("/"),
                                                color = BossThemeColors.TextPrimary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                    BossPrimaryButton(
                                        text = "Fetch",
                                        onClick = {
                                            val trimmedUrl = gitHubUrl.trim()
                                            if (trimmedUrl.isNotBlank()) {
                                                isFetching = true
                                                fetchStatus = null
                                                fetchProgress = 0f
                                                onFetchFromGitHub(
                                                    trimmedUrl,
                                                    { progress -> fetchProgress = progress },
                                                    { status -> fetchStatus = status },
                                                    { path, manifest ->
                                                        isFetching = false
                                                        jarPath = path
                                                        pluginId = manifest.pluginId
                                                        displayName = manifest.displayName
                                                        version = manifest.version
                                                        description = manifest.description
                                                        authorName = manifest.author ?: ""
                                                        homepageUrl = manifest.url ?: trimmedUrl
                                                        apiVersion = manifest.apiVersion
                                                        minBossVersion = manifest.minBossVersion.ifEmpty { "1.0.0" }
                                                        pluginType = manifest.type
                                                        fetchStatus = null
                                                    },
                                                    { error ->
                                                        isFetching = false
                                                        fetchStatus = "Error: $error"
                                                    }
                                                )
                                            }
                                        },
                                        enabled = !isLoading && !isPublishing && !isFetching && gitHubUrl.trim().isNotBlank(),
                                        icon = Icons.Default.Download
                                    )
                                }
                            }
                        }
                    }
                    JarSource.LOCAL_FILE -> {
                        Column {
                            Text(
                                text = "Plugin JAR File",
                                color = BossThemeColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (jarPath.isNotEmpty()) jarPath.substringAfterLast("/") else "No file selected",
                                    color = if (jarPath.isNotEmpty()) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(8.dp))
                                BossSecondaryButton(
                                    text = "Browse",
                                    onClick = {
                                        onBrowseJar { selectedPath ->
                                            if (selectedPath != null) {
                                                jarPath = selectedPath
                                                // Auto-extract manifest
                                                onExtractManifest(selectedPath) { manifest ->
                                                    if (manifest != null) {
                                                        pluginId = manifest.pluginId
                                                        displayName = manifest.displayName
                                                        version = manifest.version
                                                        description = manifest.description
                                                        authorName = manifest.author ?: ""
                                                        homepageUrl = manifest.url ?: ""
                                                        apiVersion = manifest.apiVersion
                                                        minBossVersion = manifest.minBossVersion.ifEmpty { "1.0.0" }
                                                        pluginType = manifest.type
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isLoading && !isPublishing
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Plugin details form
            BossTextField(
                value = pluginId,
                onValueChange = { pluginId = it },
                label = "Plugin ID",
                placeholder = "ai.rever.boss.plugin.example",
                enabled = !isLoading && !isPublishing,
                required = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Display Name",
                placeholder = "My Awesome Plugin",
                enabled = !isLoading && !isPublishing,
                required = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BossTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = "Version",
                    placeholder = "1.0.0",
                    enabled = !isLoading && !isPublishing,
                    required = true,
                    modifier = Modifier.weight(1f)
                )
                BossTextField(
                    value = authorName,
                    onValueChange = { authorName = it },
                    label = "Author",
                    placeholder = "Your Name",
                    enabled = !isLoading && !isPublishing,
                    required = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Plugin Type dropdown
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Plugin Type",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BossThemeColors.SurfaceColor)
                            .clickable(enabled = !isLoading && !isPublishing) { showTypeDropdown = !showTypeDropdown }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pluginType.displayText,
                                color = BossThemeColors.TextPrimary,
                                fontSize = 13.sp
                            )
                            Icon(
                                if (showTypeDropdown) Icons.Default.Close else Icons.Default.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = BossThemeColors.TextSecondary
                            )
                        }
                    }
                    if (showTypeDropdown) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(BossThemeColors.SurfaceColor)
                        ) {
                            PluginType.entries.forEach { type ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pluginType = type
                                            showTypeDropdown = false
                                        }
                                        .background(
                                            if (type == pluginType) BossThemeColors.AccentColor.copy(alpha = 0.1f)
                                            else BossThemeColors.SurfaceColor
                                        )
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = type.displayText,
                                        color = if (type == pluginType) BossThemeColors.AccentColor else BossThemeColors.TextPrimary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BossTextField(
                    value = apiVersion,
                    onValueChange = { apiVersion = it },
                    label = "API Version",
                    placeholder = "1.0",
                    enabled = !isLoading && !isPublishing,
                    modifier = Modifier.weight(1f)
                )
                BossTextField(
                    value = minBossVersion,
                    onValueChange = { minBossVersion = it },
                    label = "Min BOSS Version",
                    placeholder = "1.0.0",
                    enabled = !isLoading && !isPublishing,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            BossTextField(
                value = homepageUrl,
                onValueChange = { homepageUrl = it },
                label = "Homepage URL",
                placeholder = "https://github.com/your/repo",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextField(
                value = iconUrl,
                onValueChange = { iconUrl = it },
                label = "Icon URL",
                placeholder = "https://example.com/icon.png",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextArea(
                value = description,
                onValueChange = { description = it },
                label = "Description",
                placeholder = "Describe what your plugin does...",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextArea(
                value = changelog,
                onValueChange = { changelog = it },
                label = "Changelog",
                placeholder = "What's new in this version...",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextField(
                value = tags,
                onValueChange = { tags = it },
                label = "Tags",
                placeholder = "ui, productivity, tools (comma-separated)",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            // Progress indicator
            if (isPublishing) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = publishProgress,
                        color = BossThemeColors.AccentColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Publishing... ${(publishProgress * 100).toInt()}%",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            // Status message
            if (publishStatus != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = publishStatus!!,
                    color = if (publishStatus!!.startsWith("Success")) BossThemeColors.SuccessColor else BossThemeColors.ErrorColor,
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // Publish button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                BossPrimaryButton(
                    text = "Publish",
                    onClick = {
                        if (jarPath.isNotBlank() && pluginId.isNotBlank() && displayName.isNotBlank() && version.isNotBlank() && authorName.isNotBlank()) {
                            isPublishing = true
                            publishStatus = null
                            onPublish(
                                jarPath,
                                pluginId,
                                displayName,
                                version,
                                homepageUrl,
                                authorName,
                                description.ifBlank { null },
                                changelog.ifBlank { null },
                                tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                iconUrl.ifBlank { null },
                                pluginType.value,
                                apiVersion.ifBlank { "1.0" },
                                minBossVersion.ifBlank { "1.0.0" },
                                selectedOrgId,
                                { progress -> publishProgress = progress },
                                { result ->
                                    isPublishing = false
                                    publishStatus = "Success! Plugin published with ID: $result"
                                    // Clear form
                                    jarPath = ""
                                    pluginId = ""
                                    displayName = ""
                                    version = ""
                                    homepageUrl = ""
                                    authorName = ""
                                    description = ""
                                    changelog = ""
                                    tags = ""
                                    iconUrl = ""
                                    pluginType = PluginType.PANEL
                                    apiVersion = "1.0"
                                    minBossVersion = "1.0.0"
                                },
                                { error ->
                                    isPublishing = false
                                    publishStatus = "Error: $error"
                                }
                            )
                        }
                    },
                    enabled = !isLoading && !isPublishing && jarPath.isNotBlank() && pluginId.isNotBlank() && displayName.isNotBlank() && version.isNotBlank() && authorName.isNotBlank(),
                    icon = Icons.Default.Upload
                )
            }
        }
    }
}

private fun filterPlugins(
    plugins: List<InstalledPluginState>,
    query: String
): List<InstalledPluginState> {
    if (query.isEmpty()) return plugins
    val lowerQuery = query.lowercase()
    return plugins.filter {
        it.displayName.lowercase().contains(lowerQuery) ||
        it.pluginId.lowercase().contains(lowerQuery) ||
        it.description.lowercase().contains(lowerQuery)
    }
}

private fun filterAvailablePlugins(
    plugins: List<PluginStoreItem>,
    query: String
): List<PluginStoreItem> {
    if (query.isEmpty()) return plugins
    val lowerQuery = query.lowercase()
    return plugins.filter {
        it.displayName.lowercase().contains(lowerQuery) ||
        it.pluginId.lowercase().contains(lowerQuery) ||
        it.description.lowercase().contains(lowerQuery)
    }
}

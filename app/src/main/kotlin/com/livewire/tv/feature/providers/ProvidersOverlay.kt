package com.livewire.tv.feature.providers

import com.livewire.tv.feature.providers.domain.ProviderConfig

/**
 * The overlay stack over the Providers list (mockup Option 2). At most one overlay is
 * open at a time. This is plain UI state — no Compose, no Android — so the focus-return
 * and Back-closing rules are unit-testable.
 *
 * Remote model: the list is one focus column (▲ ▼ between rows, OK opens the row's
 * action menu, OK on Add opens the form). Every action a row offers lives inside an
 * overlay reachable with only ▲ ▼ OK Back, so nothing is stranded to the right the way
 * the old Edit/Delete buttons were (they needed D-pad Right / Tab, unreachable on a
 * real remote — the bug this screen fixes).
 */
sealed interface ProvidersOverlay {
    /** No overlay: focus is on the list. */
    data object None : ProvidersOverlay

    /** The Set active / Edit / Delete menu for one provider. */
    data class Menu(val provider: ProviderConfig) : ProvidersOverlay

    /** The delete confirmation for one provider. Cancel is focused by default. */
    data class ConfirmDelete(val provider: ProviderConfig) : ProvidersOverlay

    /** The add/edit form. [editing] is null when adding a new provider. */
    data class Form(val editing: ProviderConfig?) : ProvidersOverlay
}

/**
 * The item a Back press should return focus to once every overlay is closed. Either a
 * provider row (by id) or the Add row. Held separately from [ProvidersOverlay] because
 * it must survive the whole overlay stack: opening Menu → ConfirmDelete → (cancel) must
 * still land back on the row the user started from.
 */
sealed interface FocusOrigin {
    data class Row(val providerId: String) : FocusOrigin
    data object Add : FocusOrigin
}

/**
 * Pure transitions for the overlay stack. The screen holds a [ProvidersOverlay] and a
 * [FocusOrigin]; these functions compute the next state so the rules are testable
 * without a UI.
 */
object ProvidersOverlayRules {

    /** OK on a provider row: open its action menu, remembering the row to return to. */
    fun openMenu(provider: ProviderConfig): Pair<ProvidersOverlay, FocusOrigin> =
        ProvidersOverlay.Menu(provider) to FocusOrigin.Row(provider.id)

    /** OK on the Add row: open the empty form, returning to Add on close. */
    fun openAdd(): Pair<ProvidersOverlay, FocusOrigin> =
        ProvidersOverlay.Form(editing = null) to FocusOrigin.Add

    /** Menu → Edit: swap the menu for the edit form (same origin row). */
    fun editFromMenu(provider: ProviderConfig): ProvidersOverlay =
        ProvidersOverlay.Form(editing = provider)

    /** Menu → Delete: swap the menu for the confirmation (Cancel focused by default). */
    fun confirmDeleteFromMenu(provider: ProviderConfig): ProvidersOverlay =
        ProvidersOverlay.ConfirmDelete(provider)

    /**
     * Back / Cancel from the current overlay. ConfirmDelete steps back to its Menu (so a
     * user who opened Delete by mistake returns to the action list, not all the way to the
     * row); every other overlay closes to the list. Returns [ProvidersOverlay.None] when
     * already closed.
     */
    fun back(current: ProvidersOverlay): ProvidersOverlay = when (current) {
        is ProvidersOverlay.ConfirmDelete -> ProvidersOverlay.Menu(current.provider)
        else -> ProvidersOverlay.None
    }

    /** After an action commits (set active, save, delete): close to the list. */
    fun close(): ProvidersOverlay = ProvidersOverlay.None
}

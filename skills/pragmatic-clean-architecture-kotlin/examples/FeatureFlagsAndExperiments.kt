package com.example.pragmatic.examples

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ============================================================================
// 1. DOMAIN: experiments described in product language. No flag keys, no
//    variant strings. Reads that log an A/B exposure are functions, not
//    properties: a property read in a log line, toString(), or a debugger watch
//    must not count as an exposure.
//    When an experiment ends, delete its member and follow the compiler errors.
// ============================================================================

interface RouteSummaryExperiments {
    /** Logs an exposure for the summary redesign. Call when the user opens the summary. */
    fun summaryLayout(): SummaryLayout

    /** Logs an exposure for the place-matching experiment. */
    fun usesPlaceMatchingV2(): Boolean
}

sealed interface SummaryLayout {
    data object Classic : SummaryLayout

    /**
     * Only users in the redesign get this instance, so nobody else can read the
     * sub-experiment. A plain class, not a data class: it wraps a deferred read, so
     * structural equality would be meaningless. Keep it in the ViewModel, not in UI state.
     */
    class Redesigned(private val readInlineSharePrompt: () -> Boolean) : SummaryLayout {
        /** Logs an exposure for the inline-share-prompt sub-experiment. Call at the decision point. */
        fun showsInlineSharePrompt(): Boolean = readInlineSharePrompt()
    }
}

// ============================================================================
// 2. DATA: the anti-corruption layer for raw flags. Keys, variant strings, and
//    control defaults never leave this layer.
// ============================================================================

enum class ExperimentFlag { SUMMARY_REDESIGN, INLINE_SHARE_PROMPT, PLACE_MATCHING_V2 }

interface AbTestRepository {
    /** Returns the assigned variant. With many SDKs, this read also records an exposure. */
    fun variantOf(flag: ExperimentFlag): String
}

private const val REDESIGNED_SUMMARY_VARIANT = "redesigned-summary"
private const val INLINE_SHARE_PROMPT_VARIANT = "inline-share-prompt"
private const val TREATMENT_VARIANT = "treatment"

class FlagBackedRouteSummaryExperiments(
    private val abTests: AbTestRepository,
) : RouteSummaryExperiments {

    override fun summaryLayout(): SummaryLayout =
        if (abTests.variantOf(ExperimentFlag.SUMMARY_REDESIGN) == REDESIGNED_SUMMARY_VARIANT) {
            SummaryLayout.Redesigned(
                readInlineSharePrompt = {
                    abTests.variantOf(ExperimentFlag.INLINE_SHARE_PROMPT) == INLINE_SHARE_PROMPT_VARIANT
                },
            )
        } else {
            SummaryLayout.Classic
        }

    override fun usesPlaceMatchingV2(): Boolean =
        abTests.variantOf(ExperimentFlag.PLACE_MATCHING_V2) == TREATMENT_VARIANT
}

// ============================================================================
// 3. VIEWMODEL: the parent flag is read once per screen session; the
//    sub-experiment only at the decision point, and never re-reads the parent.
// ============================================================================

class RouteSummaryViewModel(experiments: RouteSummaryExperiments) {

    // One exposure when the user reaches the screen, and a value that can't change
    // mid-session. Caching it per ViewModel gives screen-session consistency; `by lazy`
    // in a singleton would cache it for the whole process instead.
    private val layout: SummaryLayout = experiments.summaryLayout()

    private val _state = MutableStateFlow(State(isRedesigned = layout is SummaryLayout.Redesigned))
    val state: StateFlow<State> = _state.asStateFlow()

    fun onAction(action: Action) {
        when (action) {
            Action.ShareClicked -> onShareClicked()
            Action.ShareStepHandled -> _state.update { it.copy(shareStep = null) }
        }
    }

    private fun onShareClicked() {
        // An inline `when`, not a Strategy: this is a two-way UI branch.
        val showInlinePrompt = when (layout) {
            SummaryLayout.Classic -> false
            is SummaryLayout.Redesigned -> layout.showsInlineSharePrompt() // the only sub-experiment read
        }
        _state.update {
            it.copy(shareStep = if (showInlinePrompt) ShareStep.InlinePrompt else ShareStep.SystemShareSheet)
        }
    }

    sealed interface Action {
        data object ShareClicked : Action
        data object ShareStepHandled : Action
    }

    /** What the UI should present next. The UI sends ShareStepHandled once it has. */
    enum class ShareStep { InlinePrompt, SystemShareSheet }

    data class State(
        val isRedesigned: Boolean = false,
        val shareStep: ShareStep? = null,
    )
}

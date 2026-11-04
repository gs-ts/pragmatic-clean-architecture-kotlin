# Feature Flags & A/B Testing in Pragmatic Clean Architecture

Feature flags and A/B experiments sit where product language, analytics side effects, and short-lived code branches meet. Match the pattern to the flag's lifespan and blast radius; don't force heavy abstractions onto a simple toggle.

---

## 1. A domain-language contract (hide raw keys and variant strings)

Experimentation tools (Firebase Remote Config, LaunchDarkly, Optimizely, Statsig, in-house backends) speak in flag keys and arbitrary variant names (`"treatment"`, `"v2_test"`, `"redesigned-summary"`).

- Define a narrow contract in the **domain layer**, in product language: what the product does for the user, not what the variant is called.
- Implement it in the **data layer**, which owns the keys, variant strings, and control defaults.
- **Compiler-driven cleanup:** when an experiment ends, delete its member from the contract. The compiler then lists every dead branch.

```kotlin
// DOMAIN: product behavior, no keys or variant strings.
// Functions, not properties: each call can log an exposure.
interface RouteSummaryExperiments {
    fun summaryLayout(): SummaryLayout
    fun usesPlaceMatchingV2(): Boolean
}

// DATA: the anti-corruption layer for raw flags
private const val REDESIGNED_SUMMARY_VARIANT = "redesigned-summary"
private const val INLINE_SHARE_PROMPT_VARIANT = "inline-share-prompt"
private const val TREATMENT_VARIANT = "treatment"

internal class FlagBackedRouteSummaryExperiments(
    private val abTests: AbTestRepository,
) : RouteSummaryExperiments {

    override fun summaryLayout(): SummaryLayout =
        if (abTests.variantOf(ExperimentFlag.SUMMARY_REDESIGN) == REDESIGNED_SUMMARY_VARIANT) {
            // The sub-experiment is reachable only through the Redesigned instance (section 2).
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
```

### Escape hatch
If a legacy module already centralizes flag access through an existing `GetAbTestFlagUseCase`, follow the module's convention. Just don't add *new* pass-through use cases where a contract like this suffices.

---

## 2. Exposure tracking vs. session consistency

With many SDKs (LaunchDarkly, Statsig, Optimizely), reading a flag records an analytics exposure; Firebase Remote Config reads don't. Where reads log, treat each read as a side effect:

| Approach | Use when | Watch out for |
| :--- | :--- | :--- |
| **A function** (`fun summaryLayout()`): the default for exposure-logging reads | A read logs an exposure. The call site then shows the side effect. | Kotlin reserves properties for cheap, repeatable reads. A property read in a log line, `toString()`, or a debugger watch would count as an exposure. |
| **Read once, keep it in the ViewModel** | The flag governs the whole screen and must stay consistent for the screen session. | Read it when the user reaches the screen, not earlier (not, for example, when a singleton is created). |
| **`by lazy`** | A per-screen object needs to defer the read until first use. | It caches for the lifetime of its instance's DI scope; in a singleton, that's the whole process. Don't use it for sub-experiments unless the property is only touched at the decision point. |
| **`sealed interface` variants** | Variants are mutually exclusive or hierarchical (a sub-experiment exists only inside a parent variant). | Overkill for one independent boolean. |

### Hierarchical variants: reading in the wrong order shouldn't compile

```kotlin
sealed interface SummaryLayout {
    data object Classic : SummaryLayout

    // Only users in the redesign get this instance, so nobody else can read the sub-experiment.
    // A plain class, not a data class: it wraps a deferred read, so structural equality would be
    // meaningless (two instances holding lambdas are never equal). Keep it in the ViewModel, not in UI state.
    class Redesigned(private val readInlineSharePrompt: () -> Boolean) : SummaryLayout {
        fun showsInlineSharePrompt(): Boolean = readInlineSharePrompt() // logs the sub-experiment exposure
    }
}

class RouteSummaryViewModel(experiments: RouteSummaryExperiments) {
    private val layout = experiments.summaryLayout() // one parent exposure, consistent for the session

    private fun onShareClicked() {
        val showInlinePrompt = when (layout) {
            SummaryLayout.Classic -> false
            is SummaryLayout.Redesigned -> layout.showsInlineSharePrompt() // the only sub-experiment read
        }
        // ... update state
    }
}
```

**Why not `isRedesigned && variantOf(SUB) == ...` on every click?** It re-reads the parent each time. That logs the parent exposure again, and if remote config refreshed in between, it can disagree with the value cached when the screen opened.

---

## 3. Inline `if`/`when` vs. Strategy (branch by abstraction)

Don't force the Strategy pattern onto every flag, and don't litter complex domain logic with `if (flag)` checks. Match the pattern to the blast radius.

### Use inline branches when
- Toggling a dialog, bottom sheet, or navigation target in a ViewModel.
- Swapping a component in a composable (`if (state.isRedesigned) RedesignedSummary() else ClassicSummary()`).
- Making a two-line conditional call. A Strategy interface for a simple UI branch is overengineering.

### Use the Strategy pattern when
- A flag replaces a **multi-step workflow, calculation engine, or repository pipeline** (for example, a new place-matching algorithm).
- Inject `PlaceMatchingStrategy` into the use case or ViewModel, and let DI (Hilt, Koin, Metro) provide `PlaceMatchingV2` or `LegacyPlaceMatching` based on `experiments.usesPlaceMatchingV2()`. When the experiment ends, delete the losing class and the DI branch; the consumer doesn't change.
- **Mind when the provider runs.** If DI evaluates the flag when it builds the graph (at app start, say), the exposure is logged before the user reaches the workflow. Resolve the strategy when the workflow starts: a factory, or a provider scoped to that screen.

---

## 4. Preventing state clobbering when flags meet loaded content

When `State` holds both a flag read at screen start (`isRedesigned`) and content loaded asynchronously:

- **Event-driven style:** don't let a mapper build a fresh root `State()` that drops fields like `isRedesigned`. Update only the content fields with `_state.update { it.copy(...) }`, or nest loaded data in its own property (`val content: SummaryContent? = null`), so a refresh can't wipe screen-level flag state.
- **Hybrid style** avoids the problem by construction: the flag lives in a source (a ViewModel field or a private `MutableStateFlow`), and every emission rebuilds `State` from all sources.

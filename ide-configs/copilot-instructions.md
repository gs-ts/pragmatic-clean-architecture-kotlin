<!-- Generated from skills/pragmatic-clean-architecture-kotlin/SKILL.md by scripts/build-adapters.sh.
     Edit SKILL.md and re-run the script instead of editing this file. -->
# Kotlin / Android / KMP Architecture Rules

Pragmatic Clean Architecture for Kotlin client code: boundaries where they protect you, no ceremony where they don't. Each rule is a default with stated exceptions. The full guide, with references and examples, is the `pragmatic-clean-architecture-kotlin` skill.

## Ground rules

1. **Project conventions win.** Instructions in AGENTS.md or CLAUDE.md, architecture docs, and patterns the codebase applies consistently override these rules. When writing code, follow them. When reviewing, don't report a deliberate convention as a defect; suggest an improvement to it at most once, marked optional.
2. **Stay in scope.** Apply these rules to code you're writing or were asked to review. Suggest improvements to that code, but don't refactor beyond it, or apply review suggestions, unless asked.
3. **Check versions before using version-gated features.** `kotlin.time.Instant` and `Clock` are stable from Kotlin 2.3 (earlier versions need `@OptIn(ExperimentalTime::class)`). Explicit backing fields need Kotlin 2.4; otherwise use `private val _state = MutableStateFlow(...)` with `asStateFlow()`. Compose strong skipping is on by default from Kotlin 2.0.20.
4. **Explain deviations.** When you depart from a default here, say why in one sentence.

## Checklist (write and review against these)

- [ ] Follows the project's existing conventions; changes stay within the requested scope.
- [ ] Domain concepts are explicit and named in the product's language: IDs and constrained values are value objects, rules derived from a model's fields are methods on the model, and `sealed` types rule out impossible combinations.
- [ ] No DTO or SDK type from an API you don't control reaches domain or UI code.
- [ ] Boundary mappers parse instead of throwing; one bad record can't terminate a `Flow`.
- [ ] Any catch of `Exception`/`Throwable`, or `runCatching`, around suspend calls rethrows `CancellationException`.
- [ ] Each use case orchestrates 2+ collaborators, enforces a domain policy, or is shared; no pass-throughs and no use-case interfaces.
- [ ] Cheap intrinsic rules sit on the domain model, cross-stream values in the ViewModel, and nothing O(N) runs in a getter.
- [ ] Derived lists are computed once per relevant change and keep their instance when their inputs don't change; no `List` → `ImmutableList` conversion without a benchmark.
- [ ] UI state reuses domain models; cheap display values are getters or functions, and UI-specific types exist only for a different shape, an expensive derivation, or a module boundary.
- [ ] Locale-dependent text is formatted in the UI (or kept as `StringResource`/`UiText`), not in ViewModel-built strings.
- [ ] Time comes from an injected `Clock`/`TimeSource` or a `now` parameter.
- [ ] Each ViewModel uses one state style, receives input through a sealed `Action`, and never writes to derived state.
- [ ] No `else` branch in a `when` over a sealed type the project owns.
- [ ] Shared abstractions have no boolean mode flags; no new base class without 3+ proven, flag-free uses.
- [ ] Flag keys stay in the data layer; exposure-logging reads are functions called at the decision point; parent flags are read once per screen.
- [ ] Tests use fakes at the lowest I/O boundary, assert on state, and use virtual time (no `Thread.sleep`, no real delays).

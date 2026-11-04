---
name: pragmatic-clean-architecture-kotlin
description: Applies tactical Domain-Driven Design and pragmatic Clean Architecture to Kotlin client apps (Android, Jetpack Compose, Kotlin Multiplatform) where they pay off. Use when adding a screen or feature, or when creating or reviewing domain models, ViewModels, UI state, repositories, use cases, mappers, feature flags and A/B tests, or unit tests. When reviewing existing code, it suggests concrete improvements (value objects, rules on the domain model, boundary mapping, less ceremony) with their trade-offs instead of demanding rewrites. Triggers include "review this feature", "how can I improve this domain model", "where should this logic live", "do I need a use case", and "replace MockK with fakes". Defers to the project's existing conventions.
license: MIT (see LICENSE.txt)
---

# Pragmatic Clean Architecture for Kotlin Clients (Android · Compose · KMP)

Apply tactical DDD, Clean Architecture, AHA (Avoid Hasty Abstractions), Sandi Metz's POODR, and fakes-first testing to Kotlin client code, where they pay off. When writing code, pick the simplest structure that keeps it correct, testable, and easy to change. When reviewing, suggest concrete improvements and explain their trade-offs; don't demand rewrites. Each rule is a default with stated exceptions.

<!-- BEGIN GROUND-RULES: shared with ide-configs/ by scripts/build-adapters.sh -->
## Ground rules

1. **Project conventions win.** Instructions in AGENTS.md or CLAUDE.md, architecture docs, and patterns the codebase applies consistently override these rules. When writing code, follow them. When reviewing, don't report a deliberate convention as a defect; suggest an improvement to it at most once, marked optional.
2. **Stay in scope.** Apply these rules to code you're writing or were asked to review. Suggest improvements to that code, but don't refactor beyond it or apply review suggestions unless asked.
3. **Check versions before using version-gated features.** `kotlin.time.Instant` and `Clock` are stable from Kotlin 2.3 (earlier versions need `@OptIn(ExperimentalTime::class)`). Explicit backing fields need Kotlin 2.4; otherwise use `private val _state = MutableStateFlow(...)` with `asStateFlow()`. Compose strong skipping is on by default from Kotlin 2.0.20.
4. **Explain deviations.** When you depart from a default here, say why in one sentence.
<!-- END GROUND-RULES -->

## 1. Where derived values live

| The value depends on… | Compute it in… | Why |
| :--- | :--- | :--- |
| Only the model's own fields, cheaply (`isOngoing`, `hasAiSummary`) | **Domain model** getter | Each Flow emission is a new immutable instance, so the value is always current. |
| Only the model's own fields, expensively (loops, sorting, distance over thousands of points) | **Once per emission:** repository mapper, Flow `map` on `Dispatchers.Default`, or a `val` initialized in the constructor of a repository-emitted model | A getter re-runs on every read, on the main thread during composition. If it returns a list, each read is a new instance, so children that receive it can't skip. Don't put constructor-computed values on `State`: every `copy()` recomputes them. |
| The model plus UI state or another stream (filter, selection, service status, location) | **ViewModel:** `combine()` for O(N) work, `State` getters for O(1) flags | Domain models don't know about UI state or other streams. Derive each list only from the inputs it depends on, so unrelated changes reuse the same instance. |
| The current time | A **`now: Instant` parameter** (`isAbandonedAt(now)`); ticking UI from an injected `Clock` | Keeps domain code pure and testable. |
| Locale, string resources, `Context` | **Composable**, or keep `StringResource`/`UiText` in state | ViewModels survive locale changes, so strings formatted there go stale. Also keeps shared code platform-free. |

→ Details: `references/domain-vs-state-derivation.md`

## 2. ViewModel state and Compose

- **One state style per ViewModel.** Derived: `combine(...).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)` when state comes from flows. Event-driven: `MutableStateFlow` + `update { it.copy(...) }` when it comes from user actions. Hybrid: UI-local state in a private `MutableStateFlow`, combined with the data flows. Never write into derived state.
- **User input goes through `onAction(action: Action)`** with a `sealed interface Action`. Model navigation and other outcomes as state the UI consumes, unless the project already has an event pattern.
- **Preserve instances; don't convert collections.** With strong skipping, unstable parameters (`List`, classes from non-Compose modules) still skip when the same instance is passed. Compute lists once per relevant change and keep the instance when their inputs don't change. Don't switch to `ImmutableList`/`PersistentList`, add `@Immutable`, or add stability-config rules for collection-holding models by default: that forces element-by-element `equals()` on every recomposition. Reserve them for cases where you can't preserve references and a benchmark shows a win.

→ Details: `references/domain-vs-state-derivation.md`

## 3. Mapping between layers

- **Data → Domain.** Mandatory for network DTOs from APIs you don't control and for third-party SDK types (Places, Firebase, BLE): map them in the repository. For a local database schema you own that changes in lockstep with the model, a 1:1 mapper is a judgment call; map when you want storage annotations out of shared domain code or the schema stores data differently.
- **Parse, don't throw, at the boundary.** `init { require(...) }` on value objects catches programmer errors. Mappers use non-throwing factories (`PlaceId.parse(raw)`) and an explicit policy per field (drop and report, default, or typed error), so one bad record can't terminate a `Flow`.
- **Domain → UI.** Reuse domain models in `State` by default. Derive cheap display values (a title fallback, a badge flag) as getters or functions on `State`, or as UI-layer extension properties on the domain model; no mapper needed. Add a UI-specific type only when the UI needs a different shape (rows grouped under headers, rows merged from several sources), a derivation is too expensive for a getter, or module boundaries keep UI code off domain types; reuse domain objects inside it. For selection or expansion, keep a `Set<Id>` next to the reused list. Copying fields 1:1 and collection stability are not reasons.

→ Details: `references/smart-mapping-and-acl.md`

## 4. Abstractions (AHA and POODR)

- **Prefer duplication over the wrong abstraction.** When shared code grows boolean mode flags (`isHistoryView: Boolean`) to serve individual callers, inline it back into the callers and wait for 3+ stable uses to reveal a flag-free abstraction.
- **Base classes:** don't create `BaseViewModel`/`BaseRepository` up front. Once 3+ screens share a stable, flag-free shape (for example an MVI store contract), a small base class or, preferably, a composed helper is fine. If the codebase already standardizes one, use it.
- **Tell, don't ask** for domain rules (`route.canBeFinishedWith(name)`), not for UI state.
- **Interfaces where rates of change differ:** I/O boundaries and platform services, not 1:1 wrappers around stable classes.
- **Closed sets → `sealed interface` + exhaustive `when`** (UI state, actions, domain errors, variant hierarchies), with no `else` branch, so new variants break the build. **Open behavior → `interface`/`fun interface`** (repositories, strategies, decorators).

→ Details: `references/aha-and-poodr-kotlin.md`

## 5. Use cases

- Write one when it orchestrates 2+ repositories or services, enforces a domain policy across steps, is reused by several ViewModels, or (in KMP) is the shared home for logic that iOS would otherwise duplicate.
- Delete one-line pass-throughs and inject the repository instead, unless the codebase's convention requires them. Use cases don't need interfaces; fake their dependencies.

→ Details: `references/use-cases-and-testing.md`

## 6. Errors and coroutines

- Map technical failures (`HttpException`, `SQLException`, SDK exceptions) to domain errors at the repository boundary.
- **Never swallow cancellation.** When catching `Exception`/`Throwable` or using `runCatching` around suspend calls, rethrow `CancellationException`. Prefer catching specific exception types.
- Inject dispatchers, `Clock`, and `TimeSource`. Don't call `Clock.System.now()` or `System.currentTimeMillis()` in repositories, use cases, or domain code.

→ Details: `references/tactical-ddd-kotlin.md`

## 7. Testing with fakes

- **Fakes over mocks; sociable tests.** Use real use cases, mappers, and repositories. Fake the lowest I/O boundary you own (DAO, API client, SDK wrapper) when the repository has logic; fake the repository when it's thin.
- **Assert on state.** Count calls in a fake only when the count is the behavior (deduplication, caching, exposure logging).
- **Deterministic time:** `runTest`, `StandardTestDispatcher`, `testScheduler.timeSource` (shares the virtual clock with `delay`), and Turbine with `backgroundScope`.
- **Macrobenchmarks:** bind a fake data source in a variant-specific DI module. The production binding must not be compiled into that variant, or a compile-time container like Dagger/Hilt reports a duplicate binding.
- **Legacy code:** mocks are fine as temporary scaffolding around untestable classes; put new logic behind sprout or wrap seams.

→ Details: `references/use-cases-and-testing.md`, `examples/PragmaticTestingWithFakesTest.kt`

## 8. Feature flags and A/B tests

- **Product language in the domain, raw keys in the data layer.** A domain interface (`RouteSummaryExperiments`) speaks product behavior; the data layer owns flag keys, variant strings, and defaults. When an experiment ends, delete its member and follow the compiler errors.
- **Exposure safety.** Reads that log an exposure are functions, not properties. Read a parent flag once per screen and keep it in the ViewModel. Read sub-experiments only at the decision point, and only for users who can see them; a sealed variant that carries the sub-flag accessor enforces the order.
- **`by lazy` caches for its instance's DI scope**: in a singleton, that's the whole process.
- **Inline `if`/`when` for UI and navigation branches.** Use Strategy (branch by abstraction) only when a flag swaps a multi-step workflow.

→ Details: `references/feature-flags-and-ab-testing.md`

## 9. Tactical DDD and patterns

- **Value objects:** `@JvmInline value class` for IDs and constrained values. Validate real invariants (non-blank `PlaceId`, coordinate ranges), not storage details like "ID > 0".
- **Entities are immutable `data class` snapshots.** Structural equality is deliberate, because StateFlow and Compose rely on it. Use the `id` for identity (lazy-list keys, diffing).
- **Usually skip on clients:** aggregates with transactional boundaries, domain events, and domain-service layers.
- **Reflection:** don't write reflection-based mappers or factories. Codegen libraries and libraries that ship R8 rules (Retrofit, Moshi codegen, kotlinx.serialization) are fine.
- **DI:** any reflection-free container (Hilt/Dagger, Metro, kotlin-inject, Koin) or manual wiring. Hilt is Android-only.
- **GoF patterns only for a concrete problem:** Strategy as `fun interface`, Decorator via `by` delegation, Factory as companion functions or assisted injection.

→ Details: `references/tactical-ddd-kotlin.md`

## 10. Kotlin Multiplatform

- Keep domain, data, and presentation logic in `commonMain`; put platform services behind interfaces with platform implementations.
- If iOS uses native SwiftUI view models, put decisions in shared use cases or presenters, or Swift will reimplement them. Shared KMP ViewModels avoid the problem.
- The Swift boundary loses Kotlin types: value classes export as their underlying type or `id`, and sealed hierarchies aren't exhaustive in Swift without SKIE.
- Full reflection isn't available on Kotlin/Native or JS, and MockK is JVM-only, so fakes are the portable default.

→ Details: `references/kmp.md`

## Workflow: adding a feature

1. Read the project's conventions (AGENTS.md/CLAUDE.md, a neighboring feature) and its Kotlin version.
2. **Domain:** a model with intrinsic rules and value objects; a repository interface where you cross I/O.
3. **Data:** a repository implementation that maps at the boundary (parse, don't throw) and translates errors (rethrowing cancellation).
4. **Use case:** only if it passes section 5.
5. **ViewModel:** one state style, a `sealed interface Action`, derived lists built from their own inputs.
6. **UI:** reuse domain models in state, derive cheap display values with getters, and format locale-dependent values in composables.
7. **Tests:** sociable ViewModel or use-case tests with fakes at the lowest I/O boundary and virtual time.
8. **Summary:** mention any deviations from these defaults and why.

## Workflow: reviewing an existing feature

1. Read the project's conventions, then the feature end to end: domain models, repository, use cases, ViewModel, UI, and tests.
2. Walk the checklist below, and look for domain-modeling opportunities in particular:
   - primitives that should be value objects (IDs, amounts, coordinates);
   - rules that a ViewModel or composable computes from a model's fields, which belong on the model (Tell, Don't Ask);
   - boolean or nullable combinations that a `sealed` type would make impossible;
   - backend, DTO, or SDK vocabulary in domain or UI names, where the product's language fits better;
   - invariants checked in several places instead of once, at construction or at the boundary.
3. Report suggestions, most important first: correctness risks (swallowed cancellation, throwing mappers, double exposures), then domain-modeling improvements, then simplifications (pass-through use cases, 1:1 mappers), then tests.
4. For each suggestion, give the location, why it helps, a short before/after, and its cost or trade-off. Mark optional suggestions as optional, and say so when the current code is fine as it is.
5. Don't apply changes unless asked.

<!-- BEGIN CHECKLIST: shared with ide-configs/ by scripts/build-adapters.sh -->
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
<!-- END CHECKLIST -->

## Reference and example files

- `references/domain-vs-state-derivation.md`: derived values, ViewModel state styles, and Compose performance under strong skipping.
- `references/aha-and-poodr-kotlin.md`: the wrong-abstraction cure, base classes, Tell Don't Ask, and `sealed` vs. polymorphism.
- `references/smart-mapping-and-acl.md`: boundary mapping, parse-don't-throw policies, and when a UI-specific type earns its place.
- `references/feature-flags-and-ab-testing.md`: experiment contracts, exposure-safe reads, and inline branches vs. Strategy.
- `references/tactical-ddd-kotlin.md`: value objects, entities on clients, domain errors and cancellation, reflection, DI, and GoF patterns.
- `references/use-cases-and-testing.md`: the use-case litmus test, sociable tests, fakes, virtual time, Macrobenchmark wiring, and legacy seams.
- `references/kmp.md`: source sets, DI, where logic lives with native iOS UIs, and the Swift boundary.
- `examples/*.kt`: one running domain (a route tracker with place photos): domain and reactive state, boundary mapping, feature flags, and fakes with a test suite. They compile with Kotlin 2.3+ and kotlinx-coroutines; the test file also needs kotlin-test, kotlinx-coroutines-test, and Turbine.

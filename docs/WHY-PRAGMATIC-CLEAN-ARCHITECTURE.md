# Why Pragmatic Clean Architecture? (Benefits & Trade-Offs)

A concise summary for engineers and tech leads evaluating Pragmatic Clean Architecture & Tactical DDD for Kotlin, Android, and KMP projects.

---

## Core Benefits

### Domain and design
* **Domain rules you can find, test, and reuse**: Business rules live on the domain model (`route.canBeFinishedWith(name)`, `route.isAbandonedAt(now)`) instead of being re-derived in ViewModels and composables. They're plain Kotlin, unit-testable without Android, and shared by every screen (and, in KMP, every platform).
* **Invalid states are hard to represent**: Value objects validate at construction and turn ID mix-ups into compile errors, usually without allocation (values are boxed when nullable, generic, or used through an interface). `sealed` types rule out impossible combinations of booleans and nullables.
* **Ubiquitous language**: Domain models and experiment contracts speak product behavior (`FinishRouteResult.NameRejected`, `experiments.summaryLayout()`), not SDK types or variant strings, so the code is easier to read and to discuss with product and design.
* **AHA (Avoid Hasty Abstractions)**: No premature `BaseViewModel` hierarchies or flag-driven shared mappers, so control flow stays flat, local, and easy to trace.

### Change and maintenance
* **Less boilerplate**: No pass-through use cases, no use-case interfaces created only for mocking, and no 1:1 domain-to-UI mappers. How much code that removes depends on how much ceremony a codebase carries today.
* **Smaller, faster-to-review PRs**: Adding a repository method no longer forces a 5-file ripple (`Repository` → `UseCaseContract` → `UseCaseImpl` → DI module → `UiModelMapper`). Reviewers focus on business logic instead of plumbing.
* **Compiler-guided changes**: Exhaustive `when` over sealed types (with no `else` branch) and experiment contracts mean that adding a variant or ending an experiment breaks the build exactly where code needs attention.
* **External change stays at the edge**: Backend and SDK changes stop at the repository's anti-corruption layer, and a malformed record is dropped and reported instead of crashing a screen.

### Testing
* **Sociable tests**: Testing ViewModels with real use cases, repositories, and mappers over stateful fakes checks observable behavior instead of mock interactions (`verify { ... }`), so tests survive refactoring.
* **Deterministic, fast tests**: Virtual time drives delays and cache expiry, so time-based and concurrent behavior is tested in milliseconds, without flaky sleeps.
* **KMP-friendly**: Fakes run in `commonTest` on every target and sidestep mocking edge cases around value classes and `suspend` functions.

### Runtime quality
* **Smooth UI**: Computing derived lists once per change, and preserving their instances, keeps work off the main thread and lets Compose skip unchanged UI.
* **Trustworthy experiment data**: Exposure-safe flag reads log an exposure only when a user reaches the experiment, so A/B results aren't diluted by users who never saw the variant, or skewed by users who saw both.

### Team and reviews
* **A shared vocabulary for reviews**: The decision tables and checklist give human and AI reviewers the same reference, so reviews discuss trade-offs rather than taste. As an agent skill, it suggests improvements to existing features without forcing rewrites.

---

## Honest Disadvantages & Trade-Offs

* **Structure varies by need**: In a rigid layout, every feature gets the same set of files whether it needs them or not (DTO, entity, domain model, repository interface and implementation, use case, UI model, mappers), so features look uniform and are easy to check mechanically. Here, one screen may have no use case or UI model while the next has both. Each of those calls takes judgment and an understanding of the principles behind the rules; the decision tables and checklist keep the calls consistent across a team.
* **Fakes cost code up front and need upkeep**: A stateful fake takes more code on day one than `mockk<Repository>(relaxed = true)`, and it can drift from the real implementation. One fake per boundary is reused across many tests; for critical boundaries, run the same tests against the fake and the real implementation (for example, an in-memory database).
* **Value objects add friction at the edges**: Wrapping and unwrapping happens in mappers and navigation arguments, and Swift sees the underlying primitive instead of the Kotlin type.

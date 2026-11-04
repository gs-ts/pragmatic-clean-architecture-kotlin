# Why Pragmatic Clean Architecture? (Benefits & Trade-Offs)

A concise summary for engineers and tech leads evaluating Pragmatic Clean Architecture & Tactical DDD for Kotlin, Android, and KMP projects.

---

## Core Benefits

* **Less boilerplate**: No pass-through use cases, no use-case interfaces created only for mocking, and no 1:1 domain-to-UI mappers. How much code that removes depends on how much ceremony a codebase carries today.
* **Smaller, faster-to-review PRs**: Adding a repository method no longer forces a 5-file ripple (`Repository` → `UseCaseContract` → `UseCaseImpl` → DI module → `UiModelMapper`). Reviewers focus on business logic instead of plumbing.
* **Higher signal-to-noise ratio**:
  * **Ubiquitous language**: Domain models and experiment contracts speak product behavior (`route.canBeFinishedWith(name)`, `experiments.summaryLayout()`), not SDK types or variant strings.
  * **AHA (Avoid Hasty Abstractions)**: No premature `BaseViewModel` hierarchies or flag-driven shared mappers, so control flow stays flat, local, and easy to trace.
* **Refactor-resilient unit tests**:
  * **Sociable tests**: Testing ViewModels with real use cases, repositories, and mappers over stateful fakes checks observable behavior instead of mock interactions (`verify { ... }`).
  * **KMP-friendly**: Fakes run in `commonTest` on every target and sidestep mocking edge cases around value classes and `suspend` functions.
* **Fewer production and UI performance bugs**:
  * `@JvmInline value class` prevents primitive ID mix-ups at compile time, usually without allocation (values are boxed when nullable, generic, or used through an interface).
  * Computing derived lists once per change, and preserving their instances, keeps work off the main thread and lets Compose skip unchanged UI.

---

## Honest Disadvantages & Trade-Offs

* **Requires judgment over recipe-following**: Rigid Clean Architecture is easy to police because every feature follows the same 6-file template. Pragmatic architecture asks developers *"Does this use case or UI mapper earn its keep?"*, which takes more architectural maturity.
* **Initial alignment friction in code reviews**: Developers used to strict 1:1 layer mapping, or to mocking every class with MockK, may debate *when* a domain model belongs in `ViewModel.State` or *when* a pass-through use case should go. The decision tables and checklist in [`SKILL.md`](../skills/pragmatic-clean-architecture-kotlin/SKILL.md) give those reviews a shared reference point.
* **Evolution overhead when simple screens grow**: If a screen starts by reusing a domain model directly in `ViewModel.State` and later needs typed display values or merged sources, you'll introduce a UI model then. Deferring it until the requirement exists (YAGNI) usually still saves time.
* **Upfront cost of writing fakes**: A stateful fake takes more code on day one than `mockk<Repository>(relaxed = true)`. One fake is then reused across many ViewModel and use-case tests, with little per-test stubbing.

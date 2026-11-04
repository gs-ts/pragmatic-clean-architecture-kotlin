# AHA (Avoid Hasty Abstractions) & Sandi Metz's POODR in Kotlin

Clean Architecture often fails in practice not because layers are separated, but because unrelated features get coupled inside premature "reusable" abstractions. Combine **AHA (Avoid Hasty Abstractions)** and Sandi Metz's **Practical Object-Oriented Design (POODR)** with Kotlin's type system.

---

## 1. "Prefer duplication over the wrong abstraction" (Sandi Metz & AHA)

### How the wrong abstraction happens in Kotlin / Android / KMP
1. Two features look superficially similar (`ActiveRouteViewModel` and `PastRouteDetailViewModel`, or two mapper functions).
2. A developer extracts a shared `BaseViewModel`, `GenericRouteMapper`, or `CommonRouteItemUiState` to "keep it DRY."
3. Product introduces a requirement that applies to **one** of the screens.
4. Instead of splitting the abstraction, someone passes a control flag (`isHistoryScreen: Boolean = false`, `includeBadge: Boolean = true`) and adds an `if/else` inside the shared code.
5. Over time, the shared class accumulates flags and becomes brittle and hard to reason about.

### Detection and recovery
- **The control-flag smell:** if you're adding a boolean parameter (`isVariantA: Boolean`, `forOnboarding: Boolean`) or a nullable callback to a shared mapper, use case, or UI state model just to change its behavior for one caller, **you have the wrong abstraction**.
- **Sandi Metz's cure:** the fastest way forward is **back**. Inline the abstracted code into each caller, delete the branches each copy doesn't use, and accept the duplication until 3+ stable uses reveal the real, unconditional abstraction.
- **Base classes:** don't create `abstract class BaseViewModel<S, A>` or `BaseRepository` up front to share coroutine launching or state boilerplate. Start with top-level extension functions, a composed helper object, or interface delegation (`by`). Once 3+ screens share a stable shape with no mode flags (for example an MVI store contract: state, actions, reducer), extracting it is exactly what AHA allows. Prefer composition even then, and if the codebase already standardizes a base class, use it.

```kotlin
// BAD: the wrong abstraction, coupled through boolean mode flags
fun Route.toCommonUiModel(
    isHistoryView: Boolean,
    showShareButton: Boolean,
    overrideTitle: String? = null,
): CommonRouteUiModel {
    val title = if (isHistoryView) {
        displayName ?: "Past Route"
    } else {
        overrideTitle ?: "Tracking Now..."
    }
    // ... tangled conditional branches ...
}

// GOOD: focused models/mappers per screen.
// Share only genuinely identical, unconditional building blocks.
fun Route.toActiveTrackingUiState(): ActiveTrackingUiState = ...
fun Route.toPastRouteSummaryUiState(): PastRouteSummaryUiState = ...
```

---

## 2. "Tell, Don't Ask" & the Law of Demeter

Procedural code reaches into a domain model, pulls out raw fields, makes a decision, and acts from the outside. Object-oriented domain design **tells** the object what should happen, or asks it one cohesive domain question.

### Bad: interrogating internal structure in the ViewModel
```kotlin
// The ViewModel reaches into Route's fields to enforce a domain rule.
fun onConfirmRouteName(inputName: String) {
    val route = state.value.currentRoute ?: return
    if (route.finishedAt == null && inputName.trim().length >= 3 && route.distanceMeters > 0.0) {
        // ...
    }
}
```

### Good: encapsulating the decision
```kotlin
data class Route(
    val id: RouteId,
    val displayName: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val distanceMeters: Double,
) {
    val isOngoing: Boolean
        get() = finishedAt == null

    fun canBeFinishedWith(candidateName: String): Boolean =
        isOngoing && candidateName.trim().length >= MIN_NAME_LENGTH && distanceMeters > 0.0

    companion object {
        const val MIN_NAME_LENGTH = 3
    }
}
```
*Boundary:* use Tell, Don't Ask for **domain invariants and intrinsic rules**. Don't push UI-only state (whether a dialog or bottom sheet is open) into the domain model.

---

## 3. Depend on things that change less often (POODR's stability rule)

Sandi Metz judges design by **rate of change**:
- **High churn (volatile):** Compose/SwiftUI layouts, navigation arguments, third-party SDKs (Google Places, Firebase, BLE), network DTOs, and temporary A/B test variants.
- **Low churn (stable):** core domain concepts (`Route`, `PhotoMetadata`, `RouteId`) and core repository contracts.

**The rule:** dependencies point toward stability. Introduce an interface where you cross a volatility boundary (`RouteRepository` isolating volatile Room or network code from the ViewModel and domain). Don't wrap a stable internal class in a 1:1 interface when both change at the same rate. The same reasoning decides whether a mapper earns its place (see `smart-mapping-and-acl.md`).

---

## 4. Kotlin's twist on POODR: `sealed interface` (ADT) vs. polymorphism

In dynamically typed languages like Ruby, POODR favors replacing conditionals with polymorphic objects. **Don't apply that blindly in Kotlin.**

Kotlin has **algebraic data types** (`sealed interface` / `sealed class`) with compiler-checked exhaustive `when`. Choose with this matrix (the expression problem):

| Dimension | `sealed interface` + exhaustive `when` (Kotlin ADT) | Polymorphic `interface` / `fun interface` (POODR) |
| :--- | :--- | :--- |
| **Set of variants** | **Closed and fixed**: you control all variants in the same module and package. | **Open and extensible**: new implementations may live in other modules (`:data`, `:benchmark`, `:test`). |
| **Primary goal** | You **want** the compiler to flag every `when` across the app when a new state or event is added. | You **want** callers to be oblivious to which implementation runs (Open-Closed Principle). |
| **Ideal use cases** | UI `State`, MVI `Action`/`Event`, `DomainError` hierarchies, hierarchical A/B variants. | `Repository` implementations, `Strategy` algorithms, caching decorators, platform-specific KMP implementations. |

**Keep `when` exhaustive.** Don't add an `else` branch to a `when` over a sealed type or enum you own. It compiles today and silently absorbs the next variant, instead of breaking the build where that variant needs handling. `else` is fine for types you don't control.

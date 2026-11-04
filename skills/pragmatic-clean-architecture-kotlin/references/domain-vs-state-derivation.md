# Derived Values: Domain Model vs. Flow Pipeline vs. ViewModel State

Reactive Kotlin clients (Android or KMP, with `Flow` and Compose) fall into two traps: forcing every computed property into the domain model, or leaving the model anemic and pushing all logic into the ViewModel. Decide by **inputs**, **cost**, and **what triggers recalculation**.

---

## Rule 1: Intrinsic, cheap (O(1)) logic belongs in the domain model

If a value depends only on the model's own fields and is cheap to compute, put it on the model. When the UI observes `Flow<Route>` from Room, SQLDelight, or Ktor, every update emits a new immutable instance, so the value recalculates without any ViewModel code.

```kotlin
@JvmInline
value class RouteId(val value: Long)

data class Route(
    val id: RouteId,
    val displayName: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val distanceMeters: Double,
) {
    val isOngoing: Boolean
        get() = finishedAt == null

    val hasCustomName: Boolean
        get() = !displayName.isNullOrBlank()

    // A domain rule, asked as one question (Tell, Don't Ask)
    fun canBeFinishedWith(candidateName: String): Boolean =
        isOngoing && candidateName.trim().length >= MIN_NAME_LENGTH && distanceMeters > 0.0

    companion object {
        const val MIN_NAME_LENGTH = 3
    }
}
```

---

## Rule 2: Time-dependent rules take `now` as a parameter

A getter that reads the clock (`get() = Clock.System.now() - createdAt > 12.hours`) is impure: its value changes without a new emission, and tests can't control it. Pass the time in:

```kotlin
fun Route.isAbandonedAt(now: Instant): Boolean = isOngoing && now - createdAt > 12.hours
```

If the UI must update as time passes, drive it from a ticker built on an injected `Clock` and combine it in the ViewModel (Rule 3).

---

## Rule 3: Cross-stream logic belongs in the ViewModel

A domain model is a snapshot. It knows nothing about:
- ephemeral UI state (filter chips, search text, open dialogs, selected IDs);
- other streams (whether a foreground location service is running, live GPS, connectivity, a ticking timer).

Values that must recalculate when those change are computed in the ViewModel.

### Pattern A: O(1) flags → getters on `State`

```kotlin
data class State(
    private val isLocationServiceRunning: Boolean = false,
    val ongoingRouteId: RouteId? = null,
    val photoSection: PhotoSection = PhotoSection(),
) {
    // Depends on service status + route: it can't live on Route or PhotoMetadata.
    val isTracking: Boolean
        get() = isLocationServiceRunning && ongoingRouteId != null

    // Uses the unfiltered photos (Pattern B): a filter that hides every photo isn't "loading".
    val isStreamLoading: Boolean
        get() = isTracking && !photoSection.routeHasPhotos
}
```

### Pattern B: O(N) work → the Flow pipeline, computed from only the inputs it needs

**Anti-pattern:** an O(N) getter on a model or `State`.

```kotlin
data class State(val photos: List<PhotoMetadata>, val aiOnly: Boolean) {
    // Runs on every read, on the main thread during composition, and returns a new list
    // each time, so children that receive it can never skip by identity.
    val visiblePhotos: List<PhotoMetadata>
        get() = if (aiOnly) photos.filter { it.hasAiSummary } else photos
}
```

**Fix:** compute it in the pipeline, once per change of *its own* inputs. The filter depends on the route and the filter toggle only. The service status is combined afterwards, so a service toggle reuses the same `PhotoSection` instance:

```kotlin
private val onlyWithAiSummary = MutableStateFlow(false) // UI-local state (hybrid style)

private val photoSection: Flow<PhotoSection> = combine(
    routeRepository.observeRouteWithPhotos(routeId),
    onlyWithAiSummary,
) { routeWithPhotos, aiOnly ->
    PhotoSection(
        photos = if (aiOnly) routeWithPhotos.photos.filter { it.hasAiSummary } else routeWithPhotos.photos,
        isAiFilterEnabled = aiOnly,
        routeHasPhotos = routeWithPhotos.photos.isNotEmpty(), // before the filter
    )
}

val state: StateFlow<State> = combine(photoSection, isLocationServiceRunning) { section, serviceRunning ->
    State(isLocationServiceRunning = serviceRunning, ongoingRouteId = routeId, photoSection = section)
}
    .flowOn(Dispatchers.Default) // the filter runs off the main thread
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), State())
```

If the filter shared one `combine` with the service status, every service toggle would re-run it and emit a new list. With a plain `List`, that defeats skipping by identity; with `ImmutableList`, it costs an O(N) `equals()` per recomposition.

---

## Rule 4: Intrinsic but expensive → compute once per emission

If a value belongs to the model conceptually (bounding box, total elevation gain) but loops over a collection, don't use a getter. Compute it once: in the repository mapper, or in a property initialized in the constructor of a model the repository emits.

```kotlin
data class RouteTrack(
    val id: RouteId,
    val waypoints: List<Coordinates>,
) {
    // Computed once per instance, off the main thread where the repository maps rows.
    val totalDistanceMeters: Double = haversineTotal(waypoints)
}
```

**Don't do this on `State`.** `state.update { it.copy(isDialogShown = true) }` builds a new instance and re-runs every constructor-initialized property, on the main thread, for an unrelated change.

---

## Rule 5: Locale and resources → the UI

Formatting needs `Context`, string resources, or `Locale`. Do it in the composable, or keep a typed value in state (`StringResource` from Compose Multiplatform resources, or your own `UiText`) and resolve it in the UI. ViewModels survive configuration changes, including a locale change, so strings formatted in a ViewModel keep the old language until the next emission. This also keeps shared code free of platform types.

---

## Choosing a state style

| Style | Use when | Shape |
| :--- | :--- | :--- |
| **Derived** | State comes from repository or service flows | `combine(...).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)` |
| **Event-driven** | State comes mostly from user actions | `private val _state = MutableStateFlow(State())` + `_state.update { it.copy(...) }` |
| **Hybrid** | Both | UI-local state (filters, dialogs) in a private `MutableStateFlow`, combined with the data flows into a derived `state` |

Never write into derived state. Don't collect a data flow in `init` just to copy it into a `MutableStateFlow`: `stateIn(WhileSubscribed)` stops the upstream when nobody is collecting, and the copy loses that.

---

## Compose performance under strong skipping

Strong skipping has been the default since Kotlin 2.0.20 ([Android docs](https://developer.android.com/develop/ui/compose/performance/stability/fix)):

- Composables with unstable parameters (`List`, classes from modules not built with the Compose compiler) still skip; those parameters are compared by instance (`===`). Stable parameters are compared with `equals()`.
- So the main lever is **instance preservation**: never allocate in getters or during composition, and derive each list only from the inputs it depends on (Rule 3).
- `ImmutableList`/`PersistentList`, `@Immutable`/`@Stable`, and stability-config entries make a parameter stable, which switches it to element-by-element `equals()` on every recomposition. Use them only when you can't preserve references and a benchmark shows it beats recomposing.
- The stability configuration file is passed to the `composeCompiler { }` block of the modules that compile composables, and lists domain packages there. It helps most for small, flat models that the data layer re-allocates with identical values.

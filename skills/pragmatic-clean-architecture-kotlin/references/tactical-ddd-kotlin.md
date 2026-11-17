# Tactical DDD & Design Patterns in Modern Kotlin

Apply the tactical DDD patterns that pay off on clients, and Gang of Four patterns in idiomatic Kotlin, without Java-era ceremony.

---

## 1. Value objects with `@JvmInline value class`

Primitive obsession (`Long`, `String`, `Double` passed everywhere) causes mix-ups: a `routeId` passed where a `photoId` was expected, latitude and longitude swapped.

- Wrap identifiers and constrained primitives in `@JvmInline value class`.
- Validate **genuine domain invariants** in `init` (a blank place ID, an out-of-range latitude). Don't validate storage details: "ID > 0" is a database autoincrement convention, and it makes unsaved entities (Room's `id = 0` default before insert) impossible to represent.
- Give each validating type a **non-throwing factory** (`parse`) for boundary code, so mappers can apply a policy instead of crashing (see `smart-mapping-and-acl.md`).
- On the JVM there's usually no allocation; values are boxed when nullable (`RouteId?`), used as a generic type argument, or used through an interface.

```kotlin
@JvmInline
value class RouteId(val value: Long) // an opaque identifier: no storage rules

@JvmInline
value class PlaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlaceId must not be blank" }
    }

    companion object {
        fun parse(raw: String?): PlaceId? = raw?.takeIf { it.isNotBlank() }?.let(::PlaceId)
    }
}

data class Coordinates(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in LATITUDE_RANGE) { "Invalid latitude: $latitude" }
        require(longitude in LONGITUDE_RANGE) { "Invalid longitude: $longitude" }
    }

    companion object {
        private val LATITUDE_RANGE = -90.0..90.0
        private val LONGITUDE_RANGE = -180.0..180.0

        fun parse(latitude: Double?, longitude: Double?): Coordinates? {
            if (latitude == null || longitude == null) return null
            if (latitude !in LATITUDE_RANGE || longitude !in LONGITUDE_RANGE) return null
            return Coordinates(latitude, longitude)
        }
    }
}
```

When a value needs normalizing (trimmed, sorted), make the constructor **private** and build instances only through the factory, so that every instance is normalized. With a public constructor, `RouteName(" Walk ")` would skip the trim, and two names that look the same would not be equal. A data class also generates a public `copy()` that skips the factory, so add `@ConsistentCopyVisibility` to make `copy()` private too. Without it, Kotlin 2.3 and 2.4 only warn, and the warning says that this becomes an error in language version 2.5.

```kotlin
@JvmInline
value class RouteName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): RouteName? = raw.trim().takeIf { it.length >= Route.MIN_NAME_LENGTH }?.let(::RouteName)
    }
}

@ConsistentCopyVisibility // copy() is private too, so it cannot skip of()
data class ElevationRange private constructor(val lowestMeters: Double, val highestMeters: Double) {
    companion object {
        fun of(first: Double, second: Double) = ElevationRange(minOf(first, second), maxOf(first, second))
    }
}
```

---

## 2. Entities on a reactive client

DDD defines entities by identity. On a reactive client, model them as **immutable `data class` snapshots** anyway:
- Structural equality is deliberate. `StateFlow` drops emissions equal to the current value, and Compose skips stable parameters that are unchanged.
- Use the `id` when you mean identity: lazy-list keys (`items(photos, key = { it.id.value })`), diffing, "is this the same item?" checks.
- A change produces a new instance (`route.finish(name, at = now)` returns a copy).
- If only a factory may build an entity, make its constructor internal and add `@ConsistentCopyVisibility` (see section 1).

## 3. What usually doesn't pay off on clients

- **Aggregates with transactional consistency boundaries:** the backend owns cross-entity invariants; the client renders and edits snapshots.
- **Domain events and event buses:** reactive streams (`Flow`) already propagate change.
- **Domain-service layers:** a use case or a method on the model covers most cases.

Reach for them only when the client really owns the invariant (for example, offline-first editing with local conflict rules).

---

## 4. Domain errors, technical exceptions, and cancellation

Don't let raw `SQLException`, `HttpException`, or SDK `ApiException` escape the data layer. Map them at the repository boundary to domain exceptions or a `sealed interface` result hierarchy.

```kotlin
sealed class PhotoMetadataException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PlacesUnavailable(cause: Throwable? = null) : PhotoMetadataException("Nearby places unavailable", cause)
    class PhotoMetadataUnavailable : PhotoMetadataException("No new photos available for location")
    class PlaceIdInvalid(val placeId: PlaceId) : PhotoMetadataException("Place ID ${placeId.value} is no longer valid")
}
```

**Never swallow cancellation.** `CancellationException` is how coroutines stop. A broad `catch (e: Exception)` or `runCatching` around a suspend call catches it too, and turns a cancelled screen into an error state or a retry.

```kotlin
suspend fun nearbyPlaces(location: Coordinates): List<PlaceCandidate> =
    try {
        placesClient.searchNearby(location)
    } catch (e: CancellationException) {
        throw e // let cancellation propagate
    } catch (e: Exception) {
        throw PhotoMetadataException.PlacesUnavailable(e)
    }
```

Prefer catching specific exception types. If you must catch broadly or use `runCatching`, rethrow `CancellationException` first.

### Work that must finish after the user leaves

`viewModelScope` is cancelled when the ViewModel is cleared, for example when the user closes the screen, and so is every suspend call made from it. That is right for loading the screen's data, but not for a save in several steps. `FinishRouteUseCase` stops the location tracker and then saves the route, so a cancel between the two leaves a route that is neither tracked nor finished. The [Android guide](https://developer.android.com/topic/architecture/data-layer) sorts operations by how long they must live:

| Operation | Must live until | Run it in |
| :--- | :--- | :--- |
| UI-oriented (load the screen's data) | the user leaves the screen | `viewModelScope` |
| App-oriented (a save in several steps, a cache refresh) | the app process ends | an injected application scope |
| Business-oriented (an upload that must survive process death) | the work is done | WorkManager |

For app-oriented work, inject the scope into the use case or repository that owns the work, not into the ViewModel, so that every caller gets the guarantee. The ViewModel still calls it from `viewModelScope`.

```kotlin
// FinishRouteUseCase from the examples, with the scope added
class FinishRouteUseCase(
    private val routeRepository: RouteRepository,
    private val locationTracker: LocationTracker,
    private val clock: Clock,
    private val applicationScope: CoroutineScope, // a singleton: CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    // If the user leaves, await() is cancelled, but the work inside async continues.
    suspend operator fun invoke(routeId: RouteId, name: String): FinishRouteResult =
        applicationScope.async { finish(routeId, name) }.await()

    private suspend fun finish(routeId: RouteId, name: String): FinishRouteResult {
        val route = routeRepository.getRoute(routeId) ?: return FinishRouteResult.NotFound
        if (!route.canBeFinishedWith(name)) return FinishRouteResult.NameRejected
        locationTracker.stop()
        routeRepository.saveRoute(route.finish(name, at = clock.now()))
        return FinishRouteResult.Finished
    }
}
```

- `SupervisorJob` keeps one failed operation from cancelling the scope for the others.
- If the work fails after the user has left, nothing awaits it, and `async` ignores the scope's `CoroutineExceptionHandler`, so the error is lost. Log it inside `async` if you need it.
- In tests, pass the `TestScope` as the application scope, so that the work runs on virtual time.

---

## 5. Reflection and dependency injection

- **Reflection:** don't write reflection-based mappers, factories, or service locators. Codegen libraries (Moshi codegen, kotlinx.serialization, Room) and libraries that ship consumer R8 rules (Retrofit) are fine. In KMP, full reflection (`kotlin-reflect`) isn't available on Kotlin/Native or JS, so shared code can't rely on it at all.
- **DI:** use any reflection-free container (Hilt/Dagger, Metro, kotlin-inject, or Koin, which resolves at runtime without reflection) or wire by hand. Hilt is Android-only, so shared KMP code needs one of the others.
- **Value classes and Dagger:** Kotlin [mangles the name](https://kotlinlang.org/docs/inline-classes.html) of a function that takes a value class (`create-4DmrXe8`), and Dagger's generated Java code cannot use that name. So an assisted-injection parameter, such as the ID from a navigation key, takes the underlying type, and the ViewModel wraps it: `private val routeId = RouteId(id)`. This was checked with Dagger 2.60 and KSP.

---

## 6. Gang of Four patterns in idiomatic Kotlin

Don't introduce a GoF pattern preemptively. Apply one **only** when a concrete variation or structural problem exists.

### Strategy → functional interfaces (`fun interface`) or sealed policies
Use when an algorithm varies at runtime or across build or platform targets (location throttling for walking vs. driving, photo deduplication rules).

```kotlin
fun interface PlaceSelectionStrategy {
    suspend fun selectPlace(
        candidates: List<PlaceCandidate>,
        isAlreadySaved: suspend (PlaceId) -> Boolean,
    ): PlaceCandidate?
}

// Default strategy: the first candidate not yet saved for this route
val FirstUnsavedPlaceStrategy = PlaceSelectionStrategy { candidates, isAlreadySaved ->
    candidates.firstOrNull { candidate -> !isAlreadySaved(candidate.placeId) }
}
```

### Decorator → class delegation (`by`) plus a lock-guarded cache
Use for cross-cutting concerns (in-memory caching, logging, telemetry, retries) on a repository or client, without bloating the core class.

```kotlin
class CachingPlacesClient(
    private val delegate: PlacesClient,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val ttl: Duration = 5.minutes,
) : PlacesClient by delegate {

    private val lock = Mutex() // guards the map only; never held during network I/O
    private val cache = mutableMapOf<PlaceId, Entry>()

    private class Entry(val photoUri: String, val storedAt: TimeMark)

    override suspend fun fetchPhotoUri(placeId: PlaceId): String? {
        val cached = lock.withLock { cache[placeId] }
        if (cached != null && cached.storedAt.elapsedNow() < ttl) return cached.photoUri

        val fresh = delegate.fetchPhotoUri(placeId) ?: return null
        lock.withLock { cache[placeId] = Entry(fresh, timeSource.markNow()) }
        return fresh
    }
}
```
- Two concurrent misses for the same place both reach the network. If that matters, deduplicate per key (see `examples/PragmaticTestingWithFakes.kt`).
- `by delegate` forwards every member you don't override, including members added to the interface later, and those bypass the cache silently.

### Factory → companion functions or assisted injection
Avoid standalone `AbstractFactory` hierarchies unless multiple families of related objects are switched at runtime. Prefer:
- companion named constructors (`Coordinates.parse(...)`, `PlaceId.parse(...)`);
- assisted injection when a ViewModel combines runtime arguments (a navigation key) with injected dependencies: Hilt's `@AssistedFactory`, assisted injection in Metro or kotlin-inject, or Koin's `parametersOf(...)`.

### State & Command → MVI (`State` + `sealed interface Action`)
In Kotlin UI architecture, the Command and State patterns converge into unidirectional data flow (MVI):
- Commands are a `sealed interface Action`, handled exhaustively in one `when (action)` block.
- State transitions are atomic updates of a `StateFlow<State>`: `update { it.copy(...) }`, or a fresh derived emission (see `domain-vs-state-derivation.md`).

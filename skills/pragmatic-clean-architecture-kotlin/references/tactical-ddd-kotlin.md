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

---

## 2. Entities on a reactive client

DDD defines entities by identity. On a reactive client, model them as **immutable `data class` snapshots** anyway:
- Structural equality is deliberate. `StateFlow` drops emissions equal to the current value, and Compose skips stable parameters that are unchanged.
- Use the `id` when you mean identity: lazy-list keys (`items(photos, key = { it.id.value })`), diffing, "is this the same item?" checks.
- A change produces a new instance (`route.finish(name, at = now)` returns a copy).

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

---

## 5. Reflection and dependency injection

- **Reflection:** don't write reflection-based mappers, factories, or service locators. Codegen libraries (Moshi codegen, kotlinx.serialization, Room) and libraries that ship consumer R8 rules (Retrofit) are fine. In KMP, full reflection (`kotlin-reflect`) isn't available on Kotlin/Native or JS, so shared code can't rely on it at all.
- **DI:** use any reflection-free container (Hilt/Dagger, Metro, kotlin-inject, or Koin, which resolves at runtime without reflection) or wire by hand. Hilt is Android-only, so shared KMP code needs one of the others.

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

# Tactical DDD & Design Patterns in Modern Kotlin

Apply the tactical DDD patterns that pay off on clients, and Gang of Four patterns in idiomatic Kotlin, without Java-era ceremony.

---

## 1. Value objects with `@JvmInline value class`

Primitive obsession (`Long`, `String`, `Double` passed everywhere) causes mix-ups: a `routeId` passed where a `photoId` was expected, latitude and longitude swapped.

- Wrap identifiers, keys, and primitives whose format, range or unit other code relies on (a hash, a latitude, an amount) in `@JvmInline value class`. Display text stays a `String`; see "When a `String` is enough" below.
- Code relies on a format, range or unit when a wrong value would make it compute something wrong: a malformed hash never matches, an out-of-range latitude breaks a distance, and cents read as euros break a total. A value that code only passes along or shows stays a primitive. The examples keep `photoUri: String`, which goes straight to the image loader, and `distanceMeters: Double`, whose name gives the unit while the app uses only one.
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

**When a `String` is enough.** Text the user types and reads, such as a route name, a title or a note, has input limits (trimmed, not blank, a maximum length), but no other code depends on its content: nothing looks it up or matches it against other text. Breaking a limit only makes the text look wrong. Keep it a `String`, and keep the limits in one place: a function, or a rule on the model (`Route.canBeFinishedWith` in `examples/RichDomainVsReactiveState.kt`). Make it a value class once other code relies on it: the text becomes a key (unique names, tags) or gets a format (an email address, a phone number, a file name).

When a value object needs normalizing (trimmed, lowercased, sorted), make the constructor **private** and build instances only through the factory, so that every instance is normalized. A tag is text the user types too, but filters match tags, so it's a key. With a public constructor, `Tag(" Hiking")` would skip the normalizing, and a filter for `hiking` would miss that route. A data class also generates a public `copy()` that skips the factory, so add `@ConsistentCopyVisibility` (from Kotlin 2.0.20) to make `copy()` private too. Without it, Kotlin 2.3 and 2.4 only warn, and the warning says that this becomes an error in language version 2.5.

```kotlin
@JvmInline
value class Tag private constructor(val value: String) { // a key: filters match tags
    companion object {
        fun parse(raw: String): Tag? = raw.trim().lowercase().takeIf { it.isNotEmpty() }?.let(::Tag)
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

## 3. Work the platform runs, behind a port

Some work runs outside any screen, under the platform's control: a foreground service that tracks location, a BLE connection, media playback. Screens and use cases need its state, and the platform decides whether it may start. Give the domain one port for it, in product language: a `sealed` state and the commands. The platform component (the `Service`) is the adapter behind that port.

```kotlin
// From examples/RichDomainVsReactiveState.kt
sealed interface TrackingState {
    data object Idle : TrackingState
    data class Tracking(val routeId: RouteId) : TrackingState
}

interface LocationTracker {
    val state: StateFlow<TrackingState>
    fun start(routeId: RouteId)
    suspend fun stop()
}
```

- **One sealed state, with one owner.** A "service is running" flag plus a nullable route ID can say "tracking, but no route", and a screen can't tell whether the tracked route is its own: `ActiveRouteViewModel.State.isTracking` compares the IDs. Screens read the port's state instead of keeping their own copy of "the ongoing route", which drifts.
- **Report what the platform confirmed.** Starting a foreground service can fail. An app that targets Android 12 or higher gets a `ForegroundServiceStartNotAllowedException` when it starts one from the background ([docs](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)), and one that targets Android 14 or higher gets a `SecurityException` when a permission for the service type is missing ([docs](https://developer.android.com/develop/background-work/services/fgs/launch)). So `state` becomes `Tracking` once the service runs in the foreground, not when `start()` is called, and `Idle` whenever the service stops, whatever the reason.
- **The component hosts the work; a use case holds the rules.** What happens at each location (search nearby places, skip the ones already saved) goes in a use case that the service runs, so a JVM test reaches it without Android. The service keeps the platform parts: its lifecycle, the notification, and stopping when the work fails (see section 5).

## 4. What usually doesn't pay off on clients

- **Aggregates with transactional consistency boundaries:** the backend owns cross-entity invariants; the client renders and edits snapshots.
- **Domain events and event buses:** reactive streams (`Flow`) already propagate change.
- **Domain-service layers:** a use case or a method on the model covers most cases.

Reach for them only when the client really owns the invariant (for example, offline-first editing with local conflict rules).

When the client does own an invariant that two callers can race on, one conditional write usually enforces it, without an aggregate. `FinishRouteUseCase` (section 5) reads the route, checks it, and saves it, which keeps the example short. If two calls can overlap, such as from two screens, both can read the route as ongoing and both save it. Let one statement decide instead, such as `UPDATE routes SET finished_at = :at WHERE id = :id AND finished_at IS NULL`. A Room `@Query` that runs an `UPDATE` can return the number of rows it changed as an `Int`: 0 means that the other caller won.

---

## 5. Domain errors, technical exceptions, and cancellation

Don't let raw `SQLException`, `HttpException`, or SDK `ApiException` escape the data layer. Map them at the repository boundary to domain exceptions or a `sealed interface` result hierarchy.

```kotlin
// Failures only: finding nothing is an empty list, not an exception.
sealed class PhotoMetadataException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PlacesUnavailable(cause: Throwable? = null) : PhotoMetadataException("Nearby places unavailable", cause)
    class PlaceIdInvalid(val placeId: PlaceId) : PhotoMetadataException("Place ID ${placeId.value} is no longer valid")
}
```

**Never swallow cancellation.** `CancellationException` is how coroutines stop. A broad `catch (e: Exception)` or `runCatching` around a suspend call catches it too, and turns a cancelled screen into an error state or a retry.

```kotlin
// RemoteNearbyPlacesRepository from examples/AntiCorruptionAndSmartMapping.kt, with exceptions instead of NearbyPlacesResult
suspend fun nearbyPlaces(location: Coordinates): List<PlaceCandidate> {
    val response = try {
        api.searchNearby(location.latitude, location.longitude, radiusMeters)
    } catch (e: CancellationException) {
        throw e // let cancellation propagate
    } catch (e: Exception) {
        throw PhotoMetadataException.PlacesUnavailable(e)
    }
    return response.places.orEmpty().mapNotNull { it.toDomainOrNull() } // your own code: outside the try
}
```

Prefer catching specific exception types. If you must catch broadly or use `runCatching`, rethrow `CancellationException` first.

**Catch only what you expect, where it happens.** A broad catch hides bugs along with the failures it was written for.
- Put only the call you don't control inside the `try`, as above. A bug in your own mapping then fails loudly instead of looking like an unavailable API. Catch broadly only around such a call, for an SDK that throws types it doesn't document.
- Match failures by type or status code (`statusCode == 429`), never by message text.
- Report a failure as a failure: `catch (e: IOException) { emptyList() }` tells the user that nothing is nearby when the device is offline.
- Don't wrap the steps of a use case or a long-running loop in `catch (e: Exception)` to keep going. Expected failures already arrive as domain errors. Anything else is a bug that should end the work, so that its owner (a ViewModel, a service) stops and reports it, instead of failing quietly at every location.

**Streams fail the same way.** A `Flow` from a port ends with a domain exception when it can't go on, such as a `LocationUnavailableException` when location updates can't start, and the collector handles it with `catch` or a `try` around `collect`. A failure the stream goes on after, such as one search that couldn't reach the server, is a value of the emitted type. Collectors of `stateIn` or `shareIn` never see an upstream failure: it "terminates the sharing coroutine without affecting any of the subscribers" and goes to the scope ([`shareIn` docs](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/share-in.html)), which in `viewModelScope` crashes the app. Turn the failures you expect into state before sharing.

### Work that must finish after the user leaves

`viewModelScope` is cancelled when the ViewModel is cleared, for example when the user closes the screen, and so is every suspend call made from it. That is right for loading the screen's data, but not for a save in several steps. `FinishRouteUseCase` in the examples stops the location tracker and then saves the route. In `viewModelScope`, a cancel between the two would leave a route that is neither tracked nor finished. The [Android guide](https://developer.android.com/topic/architecture/data-layer) sorts operations by how long they must live:

| Operation | Must live until | Run it in |
| :--- | :--- | :--- |
| UI-oriented (load the screen's data) | the user leaves the screen | `viewModelScope` |
| App-oriented (a save in several steps, a cache refresh) | the app process ends | an injected application scope |
| Business-oriented (an upload that must survive process death) | the work is done | WorkManager |

For app-oriented work, inject the scope into the use case or repository that owns the work, not into the ViewModel, so that every caller gets the guarantee. The ViewModel still calls it from `viewModelScope`.

```kotlin
// FinishRouteUseCase from the examples
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
- In tests, pass the `TestScope` as the application scope, so that the work runs on virtual time. `finishingCompletesEvenIfTheCallerIsCancelled` in the examples cancels the caller halfway and checks that the route is still saved.

---

## 6. Reflection and dependency injection

- **Reflection:** don't write reflection-based mappers, factories, or service locators. Codegen libraries (Moshi codegen, kotlinx.serialization, Room) and libraries that ship consumer R8 rules (Retrofit) are fine. In KMP, full reflection (`kotlin-reflect`) isn't available on Kotlin/Native or JS, so shared code can't rely on it at all.
- **DI:** use any reflection-free container (Hilt/Dagger, Metro, kotlin-inject, or Koin, which resolves at runtime without reflection) or wire by hand. Hilt is Android-only, so shared KMP code needs one of the others.
- **Value classes and Dagger:** Kotlin [mangles the name](https://kotlinlang.org/docs/inline-classes.html) of a function that takes a value class (`create-4DmrXe8`), and Dagger's generated Java code cannot use that name. So an assisted-injection parameter, such as the ID from a navigation key, takes the underlying type, and the ViewModel wraps it: `private val routeId = RouteId(id)`. This was checked with Dagger 2.60 and KSP.

---

## 7. Gang of Four patterns in idiomatic Kotlin

Don't introduce a GoF pattern preemptively. Apply one **only** when a concrete variation or structural problem exists.

### Strategy → functional interfaces (`fun interface`) or sealed policies
Use when an algorithm varies at runtime or across build or platform targets (location throttling for walking vs. driving, photo deduplication rules).

```kotlin
fun interface PlaceMatchingStrategy {
    suspend fun matchPlace(
        candidates: List<PlaceCandidate>,
        isAlreadySaved: suspend (PlaceId) -> Boolean,
    ): PlaceCandidate?
}

// Default strategy: the first candidate not yet saved for this route
val FirstUnsavedPlaceMatching = PlaceMatchingStrategy { candidates, isAlreadySaved ->
    candidates.firstOrNull { candidate -> !isAlreadySaved(candidate.placeId) }
}
```

### Decorator → class delegation (`by`) plus a lock-guarded cache
Use for cross-cutting concerns (in-memory caching, logging, telemetry, retries) on a repository or client, without bloating the core class.

```kotlin
// PlacesRemoteClient is the interface in examples/PragmaticTestingWithFakes.kt.
class CachingPlacesRemoteClient(
    private val delegate: PlacesRemoteClient,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val ttl: Duration = 5.minutes,
) : PlacesRemoteClient by delegate {

    private val lock = Mutex() // guards the map only; never held during network I/O
    private val cache = mutableMapOf<PlaceId, Entry>()

    private class Entry(val photoUri: String, val storedAt: TimeMark)

    override suspend fun fetchFreshPhotoUri(placeId: PlaceId): String? {
        val cached = lock.withLock { cache[placeId] }
        if (cached != null && cached.storedAt.elapsedNow() < ttl) return cached.photoUri

        val fresh = delegate.fetchFreshPhotoUri(placeId) ?: return null
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
- Commands are a `sealed interface Action`, handled exhaustively in one `when (action)` block. Text input can be the exception: a field can edit a `TextFieldState` in the ViewModel directly (see `domain-vs-state-derivation.md`).
- State transitions are atomic updates of a `StateFlow<State>`: `update { it.copy(...) }`, or a fresh derived emission (see `domain-vs-state-derivation.md`).

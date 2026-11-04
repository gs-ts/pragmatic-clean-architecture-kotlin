package com.example.pragmatic.examples

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// Production code and fakes for examples/PragmaticTestingWithFakesTest.kt.

// ============================================================================
// 1. REPOSITORY WITH PER-KEY DEDUPLICATION AND A TESTABLE TTL
//    - The injected TimeSource lets tests drive the TTL with virtual time.
//    - One lock per place: concurrent refreshes of the SAME place hit the network
//      once, while different places refresh in parallel. A single repository-wide
//      Mutex would queue every refresh behind the slowest network call.
//    - Only the TTL decides whether to refetch. Comparing the caller's expired URI
//      with the stored one looks cheaper, but once the TTL has passed, the stored
//      URI may have expired too, and the caller would get a dead URI back.
//    - Within the TTL, callers get the cached URI without a network call, so a
//      place costs at most one successful refresh per TTL, however many screens ask.
// ============================================================================

class PhotoUriRefreshRepository(
    private val placesClient: PlacesRemoteClient,
    private val photoDao: PhotoUriDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val cacheTtl: Duration = 5.minutes,
) {
    private val stateLock = Mutex() // guards the two maps below; never held during I/O
    private val placeLocks = mutableMapOf<PlaceId, Mutex>() // one per place seen; bound it (LRU) if places are unbounded
    private val recentRefreshes = mutableMapOf<PlaceId, CachedUri>() // only entries within the TTL

    private class CachedUri(val uri: String, val refreshedAt: TimeMark)

    suspend fun refreshAndSavePhotoUri(placeId: PlaceId): String? =
        withContext(ioDispatcher) {
            val placeLock = stateLock.withLock { placeLocks.getOrPut(placeId) { Mutex() } }
            placeLock.withLock {
                // 1. A refresh within the TTL wins (another caller may have just made it).
                cachedUri(placeId)?.let { return@withLock it }

                // 2. Otherwise fetch, persist, and cache.
                val freshUri = placesClient.fetchFreshPhotoUri(placeId) ?: return@withLock null
                photoDao.updatePhotoUri(placeId, freshUri)
                cacheUri(placeId, freshUri)
                freshUri
            }
        }

    private suspend fun cachedUri(placeId: PlaceId): String? = stateLock.withLock {
        recentRefreshes.entries.removeAll { (_, cached) -> cached.refreshedAt.elapsedNow() >= cacheTtl }
        recentRefreshes[placeId]?.uri
    }

    private suspend fun cacheUri(placeId: PlaceId, uri: String) {
        stateLock.withLock { recentRefreshes[placeId] = CachedUri(uri, timeSource.markNow()) }
    }
}

interface PlacesRemoteClient {
    suspend fun fetchFreshPhotoUri(placeId: PlaceId): String?
}

interface PhotoUriDao {
    suspend fun updatePhotoUri(placeId: PlaceId, newUri: String)
}

// ============================================================================
// 2. A USE CASE THAT EARNS ITS KEEP
//    It orchestrates a repository and a platform service, enforces a domain rule,
//    and takes time from an injected Clock. A one-line repository call wouldn't qualify.
// ============================================================================

interface LocationTracker {
    suspend fun stop()
}

sealed interface FinishRouteResult {
    data object Finished : FinishRouteResult
    data object NotFound : FinishRouteResult
    data object NameRejected : FinishRouteResult
}

class FinishRouteUseCase(
    private val routeRepository: RouteRepository,
    private val locationTracker: LocationTracker,
    private val clock: Clock,
) {
    suspend operator fun invoke(routeId: RouteId, name: String): FinishRouteResult {
        val route = routeRepository.getRoute(routeId) ?: return FinishRouteResult.NotFound
        if (!route.canBeFinishedWith(name)) return FinishRouteResult.NameRejected
        locationTracker.stop()
        routeRepository.saveRoute(route.finish(name, at = clock.now()))
        return FinishRouteResult.Finished
    }
}

// ============================================================================
// 3. STATEFUL FAKES (no mocking framework; they also work in KMP commonTest)
//    Tests assert on the state these fakes hold. Counting calls is reserved for
//    cases where the count IS the behavior (deduplication, caching, exposure logging).
// ============================================================================

/** Fakes the DAO, the lowest boundary we own, so tests run the real repository and its mapping. */
class FakeRouteDao : RouteDao {
    private val routes = MutableStateFlow<Map<Long, RouteEntity>>(emptyMap())
    private val photos = MutableStateFlow<Map<Long, List<PhotoMetadataEntity>>>(emptyMap())

    fun seedPhotos(routeId: Long, rows: List<PhotoMetadataEntity>) {
        photos.update { it + (routeId to rows) }
    }

    override fun observeRouteWithPhotos(routeId: Long): Flow<RouteWithPhotosEntity?> =
        combine(routes, photos) { routesById, photosByRoute ->
            routesById[routeId]?.let { RouteWithPhotosEntity(it, photosByRoute[routeId].orEmpty()) }
        }

    override suspend fun getRoute(routeId: Long): RouteEntity? = routes.value[routeId]

    override suspend fun upsertRoute(route: RouteEntity) {
        routes.update { it + (route.id to route) }
    }
}

class FakeLocationTracker : LocationTracker {
    var isTracking: Boolean = true
        private set

    override suspend fun stop() {
        isTracking = false
    }
}

class FakePhotoUriDao : PhotoUriDao {
    private val storage = mutableMapOf<PlaceId, String>()

    /** For assertions: what the repository persisted. */
    fun uriOf(placeId: PlaceId): String? = storage[placeId]

    override suspend fun updatePhotoUri(placeId: PlaceId, newUri: String) {
        storage[placeId] = newUri
    }
}

class FakePlacesRemoteClient(
    private val simulatedLatency: Duration = Duration.ZERO,
) : PlacesRemoteClient {
    var networkCallCount: Int = 0
        private set

    override suspend fun fetchFreshPhotoUri(placeId: PlaceId): String? {
        networkCallCount++
        delay(simulatedLatency) // virtual time under runTest
        return "https://example.com/fresh_${placeId.value}.jpg"
    }
}

/** Fakes the remote API, the lowest boundary of RemoteNearbyPlacesRepository. Set [failure] to make calls throw. */
class FakeNearbySearchApi(
    private val places: List<PlaceDto>? = null,
) : NearbySearchApi {
    var failure: Throwable? = null

    override suspend fun searchNearby(latitude: Double, longitude: Double, radiusMeters: Double): NearbySearchResponseDto {
        failure?.let { throw it }
        return NearbySearchResponseDto(places = places)
    }
}

package com.example.pragmatic.examples

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

// ============================================================================
// 1. DATA LAYER: storage shapes (Room @Entity / SQLDelight rows in a real app).
//    They never leave the data layer.
// ============================================================================

data class RouteEntity(
    val id: Long,
    val displayName: String?,
    val createdAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val distanceMeters: Double,
)

data class PhotoMetadataEntity(
    val id: Long,
    val routeId: Long,
    val placeId: String,
    val photoUri: String,
    val displayName: String?,
    val generativeSummary: String?,
    val neighborhoodSummary: String?,
)

data class RouteWithPhotosEntity(
    val route: RouteEntity,
    val photos: List<PhotoMetadataEntity>,
)

/**
 * The lowest I/O boundary we own. LocalRouteRepository below maps rows and drops invalid ones,
 * so sociable tests fake this, not the repository.
 */
interface RouteDao {
    fun observeRouteWithPhotos(routeId: Long): Flow<RouteWithPhotosEntity?>
    suspend fun getRoute(routeId: Long): RouteEntity?
    suspend fun upsertRoute(route: RouteEntity)
}

// ============================================================================
// 2. DATABASE ROWS -> DOMAIN: for a schema you own, mapping is a judgment call.
//    It pays off here because the schema stores data differently (epoch millis)
//    and rows can break a domain invariant. Parse, don't throw: a row that breaks
//    an invariant is dropped and reported, so one bad row can't end the whole Flow.
// ============================================================================

fun RouteEntity.toDomain(): Route = Route(
    id = RouteId(id),
    displayName = displayName?.takeIf { it.isNotBlank() },
    createdAt = Instant.fromEpochMilliseconds(createdAtEpochMillis),
    finishedAt = finishedAtEpochMillis?.let(Instant::fromEpochMilliseconds),
    distanceMeters = distanceMeters,
)

fun Route.toEntity(): RouteEntity = RouteEntity(
    id = id.value,
    displayName = displayName,
    createdAtEpochMillis = createdAt.toEpochMilliseconds(),
    finishedAtEpochMillis = finishedAt?.toEpochMilliseconds(),
    distanceMeters = distanceMeters,
)

/** Returns null for rows that violate a domain invariant; the caller decides the policy. */
fun PhotoMetadataEntity.toDomainOrNull(): PhotoMetadata? {
    val placeId = PlaceId.parse(placeId) ?: return null
    return PhotoMetadata(
        id = PhotoId(id),
        placeId = placeId,
        photoUri = photoUri,
        displayName = displayName?.takeIf { it.isNotBlank() },
        generativeSummary = generativeSummary?.takeIf { it.isNotBlank() },
        neighborhoodSummary = neighborhoodSummary?.takeIf { it.isNotBlank() },
    )
}

class LocalRouteRepository(
    private val dao: RouteDao,
    private val onInvalidRow: (PhotoMetadataEntity) -> Unit = {},
) : RouteRepository {

    override fun observeRouteWithPhotos(routeId: RouteId): Flow<RouteWithPhotos> =
        dao.observeRouteWithPhotos(routeId.value)
            .filterNotNull()
            .map { row ->
                RouteWithPhotos(
                    route = row.route.toDomain(),
                    // Policy for invalid rows: drop and report.
                    photos = row.photos.mapNotNull { photo ->
                        photo.toDomainOrNull().also { if (it == null) onInvalidRow(photo) }
                    },
                )
            }
            .distinctUntilChanged() // unchanged rows don't produce new instances downstream

    override suspend fun getRoute(routeId: RouteId): Route? = dao.getRoute(routeId.value)?.toDomain()

    override suspend fun saveRoute(route: Route) = dao.upsertRoute(route.toEntity())
}

// ============================================================================
// 3. REMOTE API -> DOMAIN: the anti-corruption layer that is mandatory. The API
//    changes on someone else's schedule, so its DTOs never leave the data layer.
//    - Every DTO field is nullable with a null default, so a missing field skips
//      one place instead of failing the whole response. (kotlinx.serialization
//      needs the default: a nullable property without one is still required.)
//    - Technical failures become domain errors here; cancellation is rethrown.
// ============================================================================

// ---- Domain side: product language, no API types ----

/** A nearby place that could be added to the route. */
data class PlaceCandidate(
    val placeId: PlaceId,
    val displayName: String?,
    val photoUri: String,
)

/** Errors the UI can act on. A domain exception hierarchy works too (see references/tactical-ddd-kotlin.md). */
sealed interface NearbyPlacesResult {
    data class Found(val candidates: List<PlaceCandidate>) : NearbyPlacesResult
    data object Offline : NearbyPlacesResult
    data object QuotaExceeded : NearbyPlacesResult
    data class Unavailable(val cause: Throwable) : NearbyPlacesResult
}

interface NearbyPlacesRepository {
    suspend fun nearbyPlaces(location: Coordinates): NearbyPlacesResult
}

// ---- Data side: the API's shapes and failures stop here ----

/** The API's JSON shapes (@Serializable classes in a real app). APIs often omit empty arrays. */
data class NearbySearchResponseDto(val places: List<PlaceDto>? = null)

data class PlaceDto(
    val id: String? = null,
    val displayName: LocalizedTextDto? = null,
    val photos: List<PhotoDto>? = null,
)

data class LocalizedTextDto(val text: String? = null)

data class PhotoDto(val uri: String? = null)

/** Stand-ins for what your HTTP client or SDK throws (Ktor, OkHttp, or an SDK's ApiException). */
class HttpStatusException(val statusCode: Int) : Exception("HTTP $statusCode")

class NoConnectivityException : Exception("No connectivity")

interface NearbySearchApi {
    suspend fun searchNearby(latitude: Double, longitude: Double, radiusMeters: Double): NearbySearchResponseDto
}

/** Policy per field: no ID or no usable photo skips the place (useless here); a blank name becomes null. */
fun PlaceDto.toDomainOrNull(): PlaceCandidate? {
    val placeId = PlaceId.parse(id) ?: return null
    // Flatten: the domain needs one photo URI, not the API's nested photo list.
    val photoUri = photos?.firstNotNullOfOrNull { photo -> photo.uri?.takeIf { it.isNotBlank() } } ?: return null
    return PlaceCandidate(
        placeId = placeId,
        displayName = displayName?.text?.takeIf { it.isNotBlank() },
        photoUri = photoUri,
    )
}

class RemoteNearbyPlacesRepository(
    private val api: NearbySearchApi,
    private val radiusMeters: Double = 50.0,
) : NearbyPlacesRepository {

    override suspend fun nearbyPlaces(location: Coordinates): NearbyPlacesResult {
        // Only the call you don't control is inside the try. A bug in the mapping below
        // then fails loudly instead of being reported as an unavailable API.
        val response = try {
            api.searchNearby(location.latitude, location.longitude, radiusMeters)
        } catch (e: CancellationException) {
            throw e // first, before the broad catch below: cancellation must never become an error state
        } catch (e: NoConnectivityException) {
            return NearbyPlacesResult.Offline
        } catch (e: HttpStatusException) {
            // By status code, never by message text.
            return if (e.statusCode == HTTP_TOO_MANY_REQUESTS) NearbyPlacesResult.QuotaExceeded else NearbyPlacesResult.Unavailable(e)
        } catch (e: Exception) {
            // An SDK you don't control can throw types you didn't list. Without this catch, one would
            // escape to the caller's coroutine scope, and in a scope without a handler, crash the app.
            return NearbyPlacesResult.Unavailable(e)
        }
        return NearbyPlacesResult.Found(response.places.orEmpty().mapNotNull { it.toDomainOrNull() })
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

// ============================================================================
// 4. DOMAIN -> UI: the screen state reuses domain models. Cheap display values
//    are getters and functions on State, so there's no UI model and no mapper.
//    Fallback text comes from string resources, so the composable resolves it
//    and it follows locale changes, with no type in State:
//      Text(route.displayName ?: stringResource(R.string.untitled_route))
//    A UI-specific type earns its place only for a different shape (photos
//    grouped under place headers, for example) or a derivation too expensive
//    for a getter, and it would still hold PhotoMetadata, not a copy.
// ============================================================================

data class RouteDetailState(
    val route: Route, // reused as-is; the composable formats route.createdAt and resolves the fallback title
    val photos: List<PhotoMetadata>, // reused as-is: no PhotoUiModel
    val selectedPhotoIds: Set<PhotoId> = emptySet(), // per-item UI state without wrapping every photo
) {
    // A cheap (O(1)) display value, derived on read.
    fun isSelected(photo: PhotoMetadata): Boolean = photo.id in selectedPhotoIds
}

package com.example.pragmatic.examples

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

/** The lowest I/O boundary we own. Sociable tests fake this, not the repository. */
interface RouteDao {
    fun observeRouteWithPhotos(routeId: Long): Flow<RouteWithPhotosEntity?>
    suspend fun getRoute(routeId: Long): RouteEntity?
    suspend fun upsertRoute(route: RouteEntity)
}

// ============================================================================
// 2. ANTI-CORRUPTION LAYER: parse, don't throw. A row that breaks a domain
//    invariant is dropped and reported, so one bad row can't end the whole Flow.
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
// 3. DOMAIN -> UI: reuse domain models; add a UI model only for what the UI adds.
//    Here: it merges the route with screen-local selection state and gives the
//    title a type. The photos are reused domain objects, with no PhotoUiModel.
// ============================================================================

/** A typed title. The composable turns it into text, so it follows locale changes. */
sealed interface RouteTitle {
    data class Custom(val name: String) : RouteTitle
    data object Untitled : RouteTitle
}

data class RouteDetailUiState(
    val title: RouteTitle,
    val createdAt: Instant, // formatted in the composable, not here
    val photos: List<PhotoMetadata>, // domain objects reused as-is
    val selectedPhotoIds: Set<PhotoId>, // per-item UI state without wrapping every photo
)

fun RouteWithPhotos.toUiState(selectedPhotoIds: Set<PhotoId>): RouteDetailUiState = RouteDetailUiState(
    title = route.displayName?.let(RouteTitle::Custom) ?: RouteTitle.Untitled,
    createdAt = route.createdAt,
    photos = photos, // the same list instance as the domain emission
    selectedPhotoIds = selectedPhotoIds,
)

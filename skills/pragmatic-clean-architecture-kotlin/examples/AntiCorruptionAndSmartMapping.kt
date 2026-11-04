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
// 3. DOMAIN -> UI: the screen state reuses domain models. Cheap display values
//    are getters and functions on State, so there's no UI model and no mapper.
//    A UI-specific type earns its place only for a different shape (photos
//    grouped under place headers, for example) or a derivation too expensive
//    for a getter, and it would still hold PhotoMetadata, not a copy.
// ============================================================================

/** A typed title. The composable turns it into text, so it follows locale changes. */
sealed interface RouteTitle {
    data class Custom(val name: String) : RouteTitle
    data object Untitled : RouteTitle
}

data class RouteDetailState(
    val route: Route, // reused as-is; the composable formats route.createdAt
    val photos: List<PhotoMetadata>, // reused as-is: no PhotoUiModel
    val selectedPhotoIds: Set<PhotoId> = emptySet(), // per-item UI state without wrapping every photo
) {
    // Cheap (O(1)) display values, derived on read.
    val title: RouteTitle
        get() = route.displayName?.let(RouteTitle::Custom) ?: RouteTitle.Untitled

    fun isSelected(photo: PhotoMetadata): Boolean = photo.id in selectedPhotoIds
}

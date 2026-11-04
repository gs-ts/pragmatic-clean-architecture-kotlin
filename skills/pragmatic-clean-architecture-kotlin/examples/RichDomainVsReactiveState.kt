package com.example.pragmatic.examples

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// Requires Kotlin 2.3+ (stable kotlin.time.Instant) and kotlinx-coroutines.

// ============================================================================
// 1. VALUE OBJECTS: strong typing; validate genuine domain invariants only
// ============================================================================

// No `value > 0` check: "positive" is a database autoincrement detail, and it would
// make not-yet-saved routes impossible to represent.
@JvmInline
value class RouteId(val value: Long)

@JvmInline
value class PhotoId(val value: Long)

@JvmInline
value class PlaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlaceId must not be blank" }
    }

    companion object {
        /** Non-throwing factory for boundary code (mappers, deep links). */
        fun parse(raw: String?): PlaceId? = raw?.takeIf { it.isNotBlank() }?.let(::PlaceId)
    }
}

// A value object with several fields is a data class: a value class wraps a single value.
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

        /** Non-throwing factory for boundary code. */
        fun parse(latitude: Double?, longitude: Double?): Coordinates? {
            if (latitude == null || longitude == null) return null
            if (latitude !in LATITUDE_RANGE || longitude !in LONGITUDE_RANGE) return null
            return Coordinates(latitude, longitude)
        }
    }
}

// ============================================================================
// 2. DOMAIN MODELS: intrinsic, cheap (O(1)) rules live here.
//    Immutable data classes on purpose: structural equality lets StateFlow drop
//    duplicate emissions and Compose skip unchanged items. Identity is the `id`.
// ============================================================================

data class Route(
    val id: RouteId,
    val displayName: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val distanceMeters: Double,
) {
    val isOngoing: Boolean
        get() = finishedAt == null

    // Tell, don't ask: callers ask one domain question instead of inspecting fields.
    fun canBeFinishedWith(candidateName: String): Boolean =
        isOngoing && candidateName.trim().length >= MIN_NAME_LENGTH && distanceMeters > 0.0

    // Time-dependent rules take `now` as a parameter instead of reading a clock.
    fun isAbandonedAt(now: Instant): Boolean =
        isOngoing && now - createdAt > ABANDONED_AFTER

    fun finish(name: String, at: Instant): Route {
        require(canBeFinishedWith(name)) { "Route ${id.value} can't be finished with name '$name'" }
        return copy(displayName = name.trim(), finishedAt = at)
    }

    companion object {
        const val MIN_NAME_LENGTH = 3
        private val ABANDONED_AFTER = 12.hours
    }
}

data class PhotoMetadata(
    val id: PhotoId,
    val placeId: PlaceId,
    val photoUri: String,
    val displayName: String? = null,
    val generativeSummary: String? = null,
    val neighborhoodSummary: String? = null,
) {
    val hasAiSummary: Boolean
        get() = aiSummary != null

    val primaryDescription: String?
        get() = aiSummary ?: displayName?.takeIf { it.isNotBlank() }

    // One definition of "has a summary", so every getter treats a blank summary as missing.
    private val aiSummary: String?
        get() = generativeSummary?.takeIf { it.isNotBlank() } ?: neighborhoodSummary?.takeIf { it.isNotBlank() }
}

data class RouteWithPhotos(
    val route: Route,
    val photos: List<PhotoMetadata>,
)

interface RouteRepository {
    fun observeRouteWithPhotos(routeId: RouteId): Flow<RouteWithPhotos>
    suspend fun getRoute(routeId: RouteId): Route?
    suspend fun saveRoute(route: Route)
}

// ============================================================================
// 3. VIEWMODEL: cross-stream and UI-reactive derivations live here.
//    A plain class so the example compiles without androidx; in an app, extend
//    ViewModel and pass viewModelScope as `scope`.
// ============================================================================

class ActiveRouteViewModel(
    routeId: RouteId,
    routeRepository: RouteRepository,
    isLocationServiceRunning: Flow<Boolean>,
    scope: CoroutineScope,
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // UI-local state (hybrid style): a private source combined with the data flows.
    private val onlyWithAiSummary = MutableStateFlow(false)

    // Depends only on the route and the filter. A service toggle doesn't re-run the
    // O(N) filter, so the same PhotoSection instance flows through and Compose can
    // skip the photo list by identity.
    private val photoSection: Flow<PhotoSection> = combine(
        routeRepository.observeRouteWithPhotos(routeId),
        onlyWithAiSummary,
    ) { routeWithPhotos, aiOnly ->
        PhotoSection(
            photos = if (aiOnly) routeWithPhotos.photos.filter { it.hasAiSummary } else routeWithPhotos.photos,
            isAiFilterEnabled = aiOnly,
            routeHasPhotos = routeWithPhotos.photos.isNotEmpty(),
        )
    }

    val state: StateFlow<State> = combine(photoSection, isLocationServiceRunning) { section, serviceRunning ->
        State(
            isLocationServiceRunning = serviceRunning,
            ongoingRouteId = routeId,
            photoSection = section,
        )
    }
        .flowOn(defaultDispatcher) // the filter runs off the main thread
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = State(),
        )

    fun onAction(action: Action) {
        when (action) {
            is Action.AiFilterToggled -> onlyWithAiSummary.value = action.enabled
        }
    }

    sealed interface Action {
        data class AiFilterToggled(val enabled: Boolean) : Action
    }

    data class PhotoSection(
        val photos: List<PhotoMetadata> = emptyList(),
        val isAiFilterEnabled: Boolean = false,
        val routeHasPhotos: Boolean = false, // before the filter: see isStreamLoading
    )

    data class State(
        private val isLocationServiceRunning: Boolean = false,
        val ongoingRouteId: RouteId? = null,
        val photoSection: PhotoSection = PhotoSection(),
    ) {
        // O(1) cross-stream flags: service status + route + photos. They can't live on Route.
        val isTracking: Boolean
            get() = isLocationServiceRunning && ongoingRouteId != null

        // Uses the unfiltered photos: a filter that hides every photo isn't "loading".
        val isStreamLoading: Boolean
            get() = isTracking && !photoSection.routeHasPhotos
    }
}

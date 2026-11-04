# Smart Layer Mapping & the Anti-Corruption Layer (ACL)

Clean Architecture is notorious for three identical classes (`PhotoDto`, `Photo`, `PhotoUiModel`) joined by two mappers that copy fields 1:1. Map where a boundary protects you from change you don't control, and skip the mapping where it doesn't.

---

## 1. Data → Domain

### When the ACL is mandatory
**Network DTOs from APIs you don't control, and third-party SDK types** (Google Places, Maps, Firebase, BLE SDKs). Their shape changes on someone else's schedule, so map them in the repository and let those changes stop there. Don't import `retrofit2.*`, `com.google.android.libraries.places.*`, or similar into domain or UI code.

### When it's a judgment call
**A local database schema you own** that changes in lockstep with the domain model. A 1:1 entity-to-domain mapper then buys little: by the rate-of-change rule in `aha-and-poodr-kotlin.md`, mappers belong where things change at different speeds. Map anyway when:
- you want storage annotations (`@Entity`) out of shared domain code;
- the schema stores data differently (epoch millis, flattened columns, join tables);
- rows can violate domain invariants.

### Rules for the mapping
1. **Parse, don't throw.** `init { require(...) }` on a value object catches programmer errors. Data from disk or the network isn't a programmer error. Use non-throwing factories (`PlaceId.parse(raw)`) and decide a policy per field: drop and report, substitute a default, or surface a typed error. A throwing mapper inside `Flow.map` terminates the whole stream because of one bad row.
2. **Sanitize:** turn sentinels (`-1L`, `""`, nullable API junk) into proper types (`Instant`, `null`, value classes).
3. **Flatten:** if an SDK needs four nested calls to produce one photo URL, do that inside the data layer.

```kotlin
// DATA LAYER (Room entity)
@Entity(tableName = "photo_metadata")
data class PhotoMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val placeId: String,
    val photoUri: String,
    val displayName: String?,
)

// ACL MAPPER: returns null for rows that break a domain invariant
fun PhotoMetadataEntity.toDomainOrNull(): PhotoMetadata? {
    val placeId = PlaceId.parse(placeId) ?: return null
    return PhotoMetadata(
        id = PhotoId(id),
        placeId = placeId,
        photoUri = photoUri,
        displayName = displayName?.takeIf { it.isNotBlank() },
    )
}

// REPOSITORY: the policy for invalid rows is explicit (drop and report)
override fun observeRouteWithPhotos(routeId: RouteId): Flow<RouteWithPhotos> =
    dao.observeRouteWithPhotos(routeId.value)
        .filterNotNull()
        .map { row ->
            RouteWithPhotos(
                route = row.route.toDomain(),
                photos = row.photos.mapNotNull { photo ->
                    photo.toDomainOrNull().also { if (it == null) onInvalidRow(photo) }
                },
            )
        }
        .distinctUntilChanged() // unchanged rows don't produce new instances downstream
```

---

## 2. Domain → UI: earn your mapper

### Reuse the domain model in `State` when
1. **The UI shows what the domain model holds.**
2. **You don't need per-item wrappers:** either there's no per-item UI state, or a `Set<Id>` beside the list covers it.

Compose stability is **not** a criterion. Under strong skipping (the default since Kotlin 2.0.20), classes from non-Compose modules and plain `List`s still skip when the same instance is passed. Keep instances stable instead (see `domain-vs-state-derivation.md`).

```kotlin
// PhotoMetadata already matches what the feed renders.
// A PhotoMetadataUiModel would be a 1:1 copy.
data class State(
    val ongoingRouteId: RouteId? = null,
    val photos: List<PhotoMetadata> = emptyList(),
)
```

### Create a UI model when at least one of these holds
1. **Merging sources:** the screen combines a domain model with other data or screen-local state.
2. **Typed display values:** the UI needs a presentation decision the domain doesn't own (a title fallback, a section choice), expressed as a type the composable renders. Keep locale-dependent formatting in the composable.
3. **Per-item UI state:** for selection or expansion, prefer a `Set<Id>` next to the reused list. Wrapping every item (`PhotoItem(photo, isSelected)`) creates a new wrapper for every row on each toggle, so no row can skip by identity. Wrap when items need several derived UI fields.

Converting `List` to `PersistentList` is **not** a reason.

```kotlin
/** A typed title. The composable turns it into text, so it follows locale changes. */
sealed interface RouteTitle {
    data class Custom(val name: String) : RouteTitle
    data object Untitled : RouteTitle // e.g. stringResource(R.string.untitled_route) in the UI
}

data class RouteDetailUiState(
    val title: RouteTitle,
    val createdAt: Instant, // formatted in the composable, not here
    val photos: List<PhotoMetadata>, // domain objects reused as-is
    val selectedPhotoIds: Set<PhotoId>, // screen-local state, no per-item wrappers
)

fun RouteWithPhotos.toUiState(selectedPhotoIds: Set<PhotoId>): RouteDetailUiState = RouteDetailUiState(
    title = route.displayName?.let(RouteTitle::Custom) ?: RouteTitle.Untitled,
    createdAt = route.createdAt,
    photos = photos, // the same list instance as the domain emission
    selectedPhotoIds = selectedPhotoIds,
)
```
The parent gets a UI model because it merges the route with selection state and needs a typed title. Its children reuse `PhotoMetadata` directly instead of a pointless `PhotoUiModel`.

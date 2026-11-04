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

Every screen has a `State`. The question is whether it holds domain models directly, or mapped copies (`PhotoUiModel`) that a mapper produces.

### Default: reuse domain models, derive display values on read
1. Put domain models in `State` as they are, next to screen-local state.
2. Derive cheap display values (a title fallback, a badge flag, "is this row selected?") as getters or functions on `State`, or as UI-layer extension properties on the domain model. Keep them O(1) (see `domain-vs-state-derivation.md`). They need no mapper, and they can't drift from their source.
3. For selection or expansion, keep a `Set<Id>` next to the reused list. Wrapping every item (`PhotoItem(photo, isSelected)`) creates a new wrapper for every row on each toggle, so no row can skip by identity.

Compose stability is **not** a criterion. Under strong skipping (the default since Kotlin 2.0.20), classes from non-Compose modules and plain `List`s still skip when the same instance is passed. Keep instances stable instead (see `domain-vs-state-derivation.md`).

```kotlin
/** A typed title. The composable turns it into text, so it follows locale changes. */
sealed interface RouteTitle {
    data class Custom(val name: String) : RouteTitle
    data object Untitled : RouteTitle // e.g. stringResource(R.string.untitled_route) in the UI
}

data class RouteDetailState(
    val route: Route, // reused as-is; the composable formats route.createdAt
    val photos: List<PhotoMetadata>, // reused as-is: no PhotoUiModel
    val selectedPhotoIds: Set<PhotoId> = emptySet(), // per-item UI state without wrapping every photo
) {
    // Cheap (O(1)) display values, derived on read: no UI model, no mapper.
    val title: RouteTitle
        get() = route.displayName?.let(RouteTitle::Custom) ?: RouteTitle.Untitled

    fun isSelected(photo: PhotoMetadata): Boolean = photo.id in selectedPhotoIds
}
```

### When a UI-specific type earns its place
1. **A different shape:** the UI groups or merges data into something the domain doesn't model (photos under place headers, rows that combine two sources). Build it once per emission in the Flow pipeline, and keep domain objects inside it:
   ```kotlin
   sealed interface PhotoFeedRow {
       data class PlaceHeader(val placeId: PlaceId, val photoCount: Int) : PhotoFeedRow
       data class Photo(val photo: PhotoMetadata) : PhotoFeedRow // the domain object, not a copy
   }
   ```
2. **An expensive derivation:** a value too costly for a getter (O(N) over a large list) is computed once per emission and stored, in a `State` field or inside the UI type.
3. **A module boundary:** your module structure deliberately keeps UI code from depending on domain types.

Not reasons: copying fields 1:1, cheap display values (use getters), or converting `List` to `PersistentList`.

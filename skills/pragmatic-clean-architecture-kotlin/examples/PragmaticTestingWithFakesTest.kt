package com.example.pragmatic.examples

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// In a project this lives in src/commonTest (or src/test). kotlin-test,
// kotlinx-coroutines-test, and Turbine are all multiplatform.
@OptIn(ExperimentalCoroutinesApi::class)
class PragmaticTestingWithFakesTest {

    // ---- Repository: deduplication and TTL on virtual time ----------------------

    private fun TestScope.photoUriRepository(
        client: FakePlacesRemoteClient,
        dao: FakePhotoUriDao = FakePhotoUriDao(),
    ) = PhotoUriRefreshRepository(
        placesClient = client,
        photoDao = dao,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource, // the same virtual clock as delay()
    )

    @Test
    fun concurrentRefreshesOfOnePlaceHitTheNetworkOnce() = runTest {
        val client = FakePlacesRemoteClient(simulatedLatency = 1.seconds)
        val dao = FakePhotoUriDao().apply { seed(PlaceId("A"), "expired") }
        val repository = photoUriRepository(client, dao)

        val uris = List(5) { async { repository.refreshAndSavePhotoUri(PlaceId("A"), "expired") } }.awaitAll()

        assertEquals(1, client.networkCallCount) // here the count is the behavior under test
        assertEquals(setOf("https://example.com/fresh_A.jpg"), uris.toSet())
    }

    @Test
    fun differentPlacesRefreshInParallel() = runTest {
        val client = FakePlacesRemoteClient(simulatedLatency = 1.seconds)
        val repository = photoUriRepository(client)

        listOf("A", "B").map { id -> async { repository.refreshAndSavePhotoUri(PlaceId(id), "expired") } }.awaitAll()

        assertEquals(2, client.networkCallCount)
        assertEquals(1_000, currentTime) // not 2_000: refreshes don't queue behind each other
    }

    @Test
    fun cachedUriExpiresAfterTheTtl() = runTest {
        val client = FakePlacesRemoteClient()
        val dao = FakePhotoUriDao().apply { seed(PlaceId("A"), "expired") }
        val repository = photoUriRepository(client, dao)

        val fresh = repository.refreshAndSavePhotoUri(PlaceId("A"), "expired")!!
        repository.refreshAndSavePhotoUri(PlaceId("A"), fresh) // within the TTL: served from memory
        assertEquals(1, client.networkCallCount)

        advanceTimeBy(5.minutes + 1.seconds)
        repository.refreshAndSavePhotoUri(PlaceId("A"), fresh) // TTL elapsed, and the caller says it's stale
        assertEquals(2, client.networkCallCount)
    }

    // ---- Sociable tests: real use case, repository, and mappers; only the DAO is fake ----

    private val fixedNow = Instant.parse("2026-01-01T10:00:00Z")
    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    @Test
    fun finishingARouteStopsTrackingAndSavesTheTrimmedName() = runTest {
        val dao = FakeRouteDao().apply { upsertRoute(ongoingRoute(id = 1)) }
        val tracker = FakeLocationTracker()
        val finishRoute = FinishRouteUseCase(LocalRouteRepository(dao), tracker, fixedClock)

        val result = finishRoute(RouteId(1), name = "  Morning walk ")

        assertEquals(FinishRouteResult.Finished, result)
        assertFalse(tracker.isTracking)
        val saved = dao.getRoute(1)!!
        assertEquals("Morning walk", saved.displayName)
        assertEquals(fixedNow.toEpochMilliseconds(), saved.finishedAtEpochMillis)
    }

    @Test
    fun aTooShortNameIsRejectedAndNothingChanges() = runTest {
        val dao = FakeRouteDao().apply { upsertRoute(ongoingRoute(id = 1)) }
        val tracker = FakeLocationTracker()
        val finishRoute = FinishRouteUseCase(LocalRouteRepository(dao), tracker, fixedClock)

        assertEquals(FinishRouteResult.NameRejected, finishRoute(RouteId(1), name = "ab"))
        assertTrue(tracker.isTracking)
        assertNull(dao.getRoute(1)!!.finishedAtEpochMillis)
    }

    @Test
    fun viewModelFiltersPhotosAndInvalidRowsAreDroppedNotFatal() = runTest {
        val dao = FakeRouteDao().apply {
            upsertRoute(ongoingRoute(id = 1))
            seedPhotos(
                routeId = 1,
                rows = listOf(photoRow(1, summary = "Old town"), photoRow(2), photoRow(3, placeId = "")),
            )
        }
        val droppedRowIds = mutableListOf<Long>()
        val viewModel = ActiveRouteViewModel(
            routeId = RouteId(1),
            routeRepository = LocalRouteRepository(dao, onInvalidRow = { droppedRowIds += it.id }),
            isLocationServiceRunning = MutableStateFlow(true),
            scope = backgroundScope,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.state.test {
            assertEquals(ActiveRouteViewModel.State(), awaitItem())
            assertEquals(listOf(1L, 2L), awaitItem().photoSection.photos.map { it.id.value })
            assertEquals(listOf(3L), droppedRowIds) // the blank-placeId row was reported, not fatal

            viewModel.onAction(ActiveRouteViewModel.Action.AiFilterToggled(enabled = true))
            assertEquals(listOf(1L), awaitItem().photoSection.photos.map { it.id.value })
        }
    }

    @Test
    fun aServiceToggleKeepsThePhotoSectionInstance() = runTest {
        val dao = FakeRouteDao().apply {
            upsertRoute(ongoingRoute(id = 1))
            seedPhotos(routeId = 1, rows = listOf(photoRow(1, summary = "Old town")))
        }
        val serviceRunning = MutableStateFlow(true)
        val viewModel = ActiveRouteViewModel(
            routeId = RouteId(1),
            routeRepository = LocalRouteRepository(dao),
            isLocationServiceRunning = serviceRunning,
            scope = backgroundScope,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.state.test {
            skipItems(1) // initial State()
            val tracking = awaitItem()
            assertTrue(tracking.isTracking)

            serviceRunning.value = false
            val stopped = awaitItem()
            assertFalse(stopped.isTracking)
            assertSame(tracking.photoSection, stopped.photoSection) // same instance: Compose skips by identity
        }
    }

    // ---- Exposure safety: here the order and number of reads ARE the behavior ----

    private class RecordingAbTests(private val summaryVariant: String) : AbTestRepository {
        val reads = mutableListOf<ExperimentFlag>()

        override fun variantOf(flag: ExperimentFlag): String {
            reads += flag
            return when (flag) {
                ExperimentFlag.SUMMARY_REDESIGN -> summaryVariant
                ExperimentFlag.INLINE_SHARE_PROMPT -> "inline-share-prompt"
                ExperimentFlag.PLACE_MATCHING_V2 -> "control"
            }
        }
    }

    @Test
    fun theSubExperimentIsReadOnlyAtTheDecisionPoint() {
        val abTests = RecordingAbTests(summaryVariant = "redesigned-summary")
        val viewModel = RouteSummaryViewModel(FlagBackedRouteSummaryExperiments(abTests))
        assertEquals(listOf(ExperimentFlag.SUMMARY_REDESIGN), abTests.reads) // opening the screen

        viewModel.onAction(RouteSummaryViewModel.Action.ShareClicked)

        // One sub-experiment read, and no second read of the parent.
        assertEquals(listOf(ExperimentFlag.SUMMARY_REDESIGN, ExperimentFlag.INLINE_SHARE_PROMPT), abTests.reads)
        assertEquals(RouteSummaryViewModel.ShareStep.InlinePrompt, viewModel.state.value.shareStep)
    }

    @Test
    fun classicUsersAreNeverExposedToTheSubExperiment() {
        val abTests = RecordingAbTests(summaryVariant = "control")
        val viewModel = RouteSummaryViewModel(FlagBackedRouteSummaryExperiments(abTests))

        viewModel.onAction(RouteSummaryViewModel.Action.ShareClicked)

        assertEquals(listOf(ExperimentFlag.SUMMARY_REDESIGN), abTests.reads)
        assertEquals(RouteSummaryViewModel.ShareStep.SystemShareSheet, viewModel.state.value.shareStep)
    }

    // ---- Test data ----------------------------------------------------------------

    private fun ongoingRoute(id: Long) = RouteEntity(
        id = id,
        displayName = null,
        createdAtEpochMillis = 0,
        finishedAtEpochMillis = null,
        distanceMeters = 1_200.0,
    )

    private fun photoRow(id: Long, summary: String? = null, placeId: String = "place-$id") = PhotoMetadataEntity(
        id = id,
        routeId = 1,
        placeId = placeId,
        photoUri = "https://example.com/$id.jpg",
        displayName = null,
        generativeSummary = summary,
        neighborhoodSummary = null,
    )
}

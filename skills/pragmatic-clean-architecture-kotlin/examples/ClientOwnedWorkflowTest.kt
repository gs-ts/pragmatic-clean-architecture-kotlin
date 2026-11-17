package com.example.pragmatic.examples

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// In a project this lives in src/commonTest (or src/test), like PragmaticTestingWithFakesTest.kt.
@OptIn(ExperimentalCoroutinesApi::class)
class ClientOwnedWorkflowTest {

    // ---- Crashes: the process can die before or after any save ------------------

    @Test
    fun aCrashBeforeOrAfterAnySaveRepeatsAtMostOneCall() = runTest {
        for (crash in everyCrash) {
            val uploads = FakeRouteUploadRepository().apply { add(queuedUpload) }
            val api = FakeRouteUploadApi()
            val crashingAttempt = uploadAttempt(CrashingRouteUploadRepository(uploads, crash), api)
            val nextAttempt = uploadAttempt(uploads, api) // after the app restarts

            assertFailsWith<SimulatedCrash> { crashingAttempt(routeId) }
            nextAttempt(routeId)

            assertEquals(UploadStatus.Uploaded, uploads.get(routeId)?.status, "$crash")
            assertEquals(1, api.uploadedRoutes.size, "$crash") // the repeated call sent the same upload ID
            assertTrue(api.callCount <= 3, "$crash made ${api.callCount} calls") // two steps, and at most one repeat
        }
    }

    // ---- A race: the user cancels while a step runs ------------------------------

    @Test
    fun aCancelDuringAStepIsNotOverwrittenByTheAttempt() = runTest {
        val uploads = FakeRouteUploadRepository().apply { add(queuedUpload) }
        val api = FakeRouteUploadApi(latency = 1.seconds)
        val attempt = async { uploadAttempt(uploads, api)(routeId) }

        advanceTimeBy(500.milliseconds) // the photos are still uploading
        uploads.record(routeId, UploadStatus.Queued.cancel()) // the user taps Cancel
        attempt.await()

        // Without the check in record(), the attempt would overwrite Cancelled and then upload the route.
        assertEquals(UploadStatus.Cancelled, uploads.get(routeId)?.status)
        assertTrue(api.uploadedRoutes.isEmpty())
    }

    // ---- Temporary errors: the step waits for the next attempt -------------------

    @Test
    fun anUnavailableServerLeavesTheStepForTheNextAttempt() = runTest {
        val uploads = FakeRouteUploadRepository().apply { add(queuedUpload) }
        val api = FakeRouteUploadApi().apply { isReachable = false }

        assertEquals(UploadAttemptResult.RetryLater, uploadAttempt(uploads, api)(routeId))
        assertEquals(UploadStatus.Queued, uploads.get(routeId)?.status)

        api.isReachable = true
        assertEquals(UploadAttemptResult.Done, uploadAttempt(uploads, api)(routeId))
        assertEquals(UploadStatus.Uploaded, uploads.get(routeId)?.status)
    }

    // ---- Test data and the crashing repository -----------------------------------

    private val routeId = RouteId(1)
    private val queuedUpload = RouteUpload(routeId, UploadId("upload-1"), UploadStatus.Queued)
    private val finishedRoute = RouteWithPhotos(
        route = Route(
            id = routeId,
            displayName = "Morning walk",
            createdAt = Instant.parse("2026-01-01T08:00:00Z"),
            finishedAt = Instant.parse("2026-01-01T09:00:00Z"),
            distanceMeters = 1_200.0,
        ),
        photos = listOf(PhotoMetadata(PhotoId(1), PlaceId("museum"), photoUri = "https://example.com/museum.jpg")),
    )

    private fun uploadAttempt(uploads: RouteUploadRepository, api: RouteUploadApi) =
        ContinueRouteUploadUseCase(uploads, routes = { finishedRoute }, api = api)

    /** Where the process dies: at the first or the second save, before or after the write. */
    private data class Crash(val atSave: Int, val afterWriting: Boolean)

    private val everyCrash = listOf(
        Crash(atSave = 0, afterWriting = false),
        Crash(atSave = 0, afterWriting = true),
        Crash(atSave = 1, afterWriting = false),
        Crash(atSave = 1, afterWriting = true),
    )

    private class SimulatedCrash : Exception("The process died")

    /** Throws at the chosen save, before or after writing it, as if the process died there. */
    private class CrashingRouteUploadRepository(
        private val delegate: RouteUploadRepository,
        private val crash: Crash,
    ) : RouteUploadRepository by delegate {
        private var saves = 0

        override suspend fun record(routeId: RouteId, change: UploadStatusChange): RecordResult {
            val save = saves++
            if (save != crash.atSave) return delegate.record(routeId, change)
            if (crash.afterWriting) delegate.record(routeId, change)
            throw SimulatedCrash()
        }
    }
}

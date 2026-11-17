package com.example.pragmatic.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

// Production code and fakes for examples/ClientOwnedWorkflowTest.kt. In an app,
// background work (WorkManager on Android) runs ContinueRouteUploadUseCase, and
// the repository is backed by Room. references/client-owned-workflows.md shows
// both.

// ============================================================================
// 1. DOMAIN: the upload of a finished route, photos first. The app owns this
//    workflow until the server has the route, so the app enforces its rules.
//    Each status offers only its legal moves, so code that skips a step, or
//    that moves an upload that has ended, does not compile.
// ============================================================================

/** Sent with every call of an upload, so that the server can ignore a repeated call. */
@JvmInline
value class UploadId(val value: String)

sealed interface UploadStatus {

    /** The upload has work left, and the user can still cancel it. */
    sealed interface Pending : UploadStatus {
        fun cancel(): UploadStatusChange = UploadStatusChange(from = this, to = Cancelled)
    }

    data object Queued : Pending {
        fun photosUploaded(): UploadStatusChange = UploadStatusChange(from = this, to = PhotosUploaded)
    }

    data object PhotosUploaded : Pending {
        fun routeUploaded(): UploadStatusChange = UploadStatusChange(from = this, to = Uploaded)
    }

    data object Uploaded : UploadStatus

    data object Cancelled : UploadStatus
}

/**
 * A legal move from one status to the next. The constructor is internal, so
 * code outside the domain module can get a change only from a status.
 */
class UploadStatusChange internal constructor(
    val from: UploadStatus.Pending,
    val to: UploadStatus,
)

/**
 * The upload of one route. Its [uploadId] is created once, when the user queues
 * the upload, and saved with it, so that every attempt sends the same ID.
 */
data class RouteUpload(
    val routeId: RouteId,
    val uploadId: UploadId,
    val status: UploadStatus,
)

sealed interface RecordResult {
    data object Recorded : RecordResult

    /** The stored status is no longer the one that the change starts from, so nothing was saved. */
    data class Refused(val current: UploadStatus) : RecordResult

    data object NotFound : RecordResult
}

interface RouteUploadRepository {
    suspend fun get(routeId: RouteId): RouteUpload?

    suspend fun add(upload: RouteUpload)

    /**
     * Saves [change] only if the stored status is still the one that the change
     * starts from. The check and the write are one transaction, so two attempts
     * cannot both make the same move, and a change based on an old read cannot
     * overwrite a newer status, such as a cancel.
     */
    suspend fun record(routeId: RouteId, change: UploadStatusChange): RecordResult
}

/** Reads what an upload sends. In an app, the route repository offers this read. */
fun interface RouteWithPhotosReader {
    suspend fun read(routeId: RouteId): RouteWithPhotos?
}

/** The server's upload calls, after the data layer has translated their errors. */
interface RouteUploadApi {
    suspend fun uploadPhotos(uploadId: UploadId, photos: List<PhotoMetadata>): UploadCallResult

    suspend fun uploadRoute(uploadId: UploadId, route: Route): UploadCallResult
}

sealed interface UploadCallResult {
    data object Sent : UploadCallResult

    /**
     * The server could not be reached, or it was too busy, so a later attempt
     * can succeed. A real API would also return a result for data that the
     * server refuses. Any other error is not translated: it is most likely a
     * bug, so it fails the work instead of being retried.
     */
    data object Unavailable : UploadCallResult
}

// ============================================================================
// 2. A USE CASE THAT CONTINUES FROM THE SAVED STATUS
//    The background work runs it on every attempt. It makes one server call per
//    step and saves the step before the next one, so a crash repeats at most
//    one call. The server ignores the repeat, because it has the same upload ID.
// ============================================================================

sealed interface UploadAttemptResult {
    /** Nothing is left for this attempt: the upload has ended, or it no longer exists. */
    data object Done : UploadAttemptResult

    /** The server is unavailable. The status has not changed, so the next attempt repeats the step. */
    data object RetryLater : UploadAttemptResult
}

class ContinueRouteUploadUseCase(
    private val uploads: RouteUploadRepository,
    private val routes: RouteWithPhotosReader,
    private val api: RouteUploadApi,
) {
    suspend operator fun invoke(routeId: RouteId): UploadAttemptResult {
        val upload = uploads.get(routeId) ?: return UploadAttemptResult.Done
        val content = routes.read(routeId) ?: return UploadAttemptResult.Done
        var status = upload.status
        while (status is UploadStatus.Pending) {
            val change = sendNextPart(status, upload.uploadId, content) ?: return UploadAttemptResult.RetryLater
            status = when (val result = uploads.record(routeId, change)) {
                RecordResult.Recorded -> change.to
                // The user cancelled, or another attempt saved this step first.
                // Either way, the stored status says what is left to do.
                is RecordResult.Refused -> result.current
                RecordResult.NotFound -> return UploadAttemptResult.Done
            }
        }
        return UploadAttemptResult.Done
    }

    /**
     * Makes the one server call that [status] needs. Returns the change to
     * save, or null if the server is unavailable.
     */
    private suspend fun sendNextPart(
        status: UploadStatus.Pending,
        uploadId: UploadId,
        content: RouteWithPhotos,
    ): UploadStatusChange? = when (status) {
        is UploadStatus.Queued ->
            if (api.uploadPhotos(uploadId, content.photos) == UploadCallResult.Sent) status.photosUploaded() else null
        is UploadStatus.PhotosUploaded ->
            if (api.uploadRoute(uploadId, content.route) == UploadCallResult.Sent) status.routeUploaded() else null
    }
}

// ============================================================================
// 3. FAKES
//    The upload repository is faked as a whole, not through a fake DAO. Its
//    transaction is the behavior under test, and a fake DAO cannot reproduce
//    one. In an app, the real repository is tested on an in-memory database.
// ============================================================================

class FakeRouteUploadRepository : RouteUploadRepository {
    private val lock = Mutex() // makes the check and the write in record() one step
    private val uploads = mutableMapOf<RouteId, RouteUpload>()

    override suspend fun get(routeId: RouteId): RouteUpload? = lock.withLock { uploads[routeId] }

    override suspend fun add(upload: RouteUpload) {
        lock.withLock { uploads[upload.routeId] = upload }
    }

    override suspend fun record(routeId: RouteId, change: UploadStatusChange): RecordResult = lock.withLock {
        val upload = uploads[routeId] ?: return@withLock RecordResult.NotFound
        if (upload.status != change.from) return@withLock RecordResult.Refused(upload.status)
        uploads[routeId] = upload.copy(status = change.to)
        RecordResult.Recorded
    }
}

/** Fakes the server, which keeps the first route that it receives for each upload ID. */
class FakeRouteUploadApi(
    private val latency: Duration = Duration.ZERO,
) : RouteUploadApi {
    var isReachable: Boolean = true

    /** The calls that reached the server, repeats included. */
    var callCount: Int = 0
        private set

    private val routesByUploadId = mutableMapOf<UploadId, Route>()

    /** For assertions: the routes that the server holds. */
    val uploadedRoutes: Collection<Route>
        get() = routesByUploadId.values

    override suspend fun uploadPhotos(uploadId: UploadId, photos: List<PhotoMetadata>): UploadCallResult {
        delay(latency) // virtual time under runTest
        if (!isReachable) return UploadCallResult.Unavailable
        callCount++
        return UploadCallResult.Sent
    }

    override suspend fun uploadRoute(uploadId: UploadId, route: Route): UploadCallResult {
        delay(latency)
        if (!isReachable) return UploadCallResult.Unavailable
        callCount++
        routesByUploadId.getOrPut(uploadId) { route } // a repeated upload ID changes nothing
        return UploadCallResult.Sent
    }
}

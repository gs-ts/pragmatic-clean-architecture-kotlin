# Workflows the Client Owns

Most of a client's data is a copy of what the server holds, so the server enforces the rules and the client shows the result (see `tactical-ddd-kotlin.md`, section 3). Some workflows run on the device instead, and then the client must enforce their rules itself. `examples/ClientOwnedWorkflow.kt` applies the rules below to the upload of a finished route.

---

## 1. When the client owns a workflow

The client owns a workflow only while its data exists only on the device:
- before the server has accepted the data, as with an upload, or a form that is saved offline and sent later (the [Android guide](https://developer.android.com/topic/architecture/data-layer/offline-first) calls this a lazy write);
- or when the data never leaves the device.

Once the server has the data, the server runs the workflow, and the client shows its status. The rest of this file does not apply then.

---

## 2. Each status offers only its legal moves

Model the statuses as a `sealed interface`, and give each status a function for each move that it allows. A move returns a change, whose constructor is internal to the domain module, so a move is the only way to get one.

```kotlin
sealed interface UploadStatus {
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

class UploadStatusChange internal constructor(val from: UploadStatus.Pending, val to: UploadStatus)
```

- Code that skips a step (`Queued` has no `routeUploaded()`) or that moves an upload that has ended does not compile.
- This is not the State pattern. There, every state accepts every request and ignores the ones that it does not allow, so a wrong call compiles and does nothing.
- For a few statuses that rarely change, an enum is enough.

---

## 3. Check and write in one transaction

When a duplicate or an overwritten change would do harm, the repository saves a change only if the stored status is still the one that the change starts from. Otherwise it saves nothing and returns the current status, and the caller continues from that status or stops.

```kotlin
override suspend fun record(routeId: RouteId, change: UploadStatusChange): RecordResult =
    database.withWriteTransaction { // Room 3. In Room 2, use withTransaction.
        val current = dao.status(routeId.value)?.toUploadStatus()
        when {
            current == null -> RecordResult.NotFound
            current != change.from -> RecordResult.Refused(current)
            else -> {
                dao.updateStatus(routeId.value, change.to.toColumn())
                RecordResult.Recorded
            }
        }
    }
```

- Without the check, an attempt that read `Queued` before the user cancelled would overwrite `Cancelled` and upload the route anyway.
- A single `UPDATE ... WHERE status = :from`, which returns the number of changed rows, is atomic too.
- This is not conflict resolution for sync. With "last write wins", the server keeps the latest of several devices' writes to the same data, while this check guards the device's own moves.

---

## 4. Steps that survive a crash

An attempt can stop at any point: the process dies, or the system stops the work. The next attempt continues from the saved status.

- **Make one server call per step, and save the step before the next one.** A crash then repeats at most the call whose save it interrupted.
- **Make the repeated call harmless.** A call that changes server data sends an idempotency key, so that the server ignores a repeat. Create the key once, when the work is queued, and save it with the work. Do the same for any other input that must not change between attempts, such as a date that a decision depends on.
- **Classify the failures.** A temporary error (no connection, a busy server) becomes a result, and the work is retried with backoff. A refusal from the server, such as data that it will not accept, is a result of its own, and it moves the workflow to a final status. Anything else is most likely a bug, so let it fail the work, because a retry would only repeat it. This is stricter than on a screen, where `RemoteNearbyPlacesRepository` turns unexpected errors into an error state.
- **On Android, run each workflow as unique work with `ExistingWorkPolicy.KEEP`,** so that a second request for the same upload does nothing while the first is queued or running. `REPLACE` would cancel the running attempt, but cancellation takes effect only at its next suspension point, so two attempts could overlap.

```kotlin
workManager.enqueueUniqueWork("route-upload-${routeId.value}", ExistingWorkPolicy.KEEP, request)

// In the worker's doWork():
return when (continueRouteUpload(routeId)) {
    UploadAttemptResult.Done -> Result.success()
    UploadAttemptResult.RetryLater -> Result.retry()
}
```

---

## 5. A history, when you need an audit trail

Keep a history only when someone needs to see what happened and when, such as a support team or an auditor. Then:
- Each change inserts one entry, and the status is the latest entry, so the status and the history cannot disagree. A status column next to the history is a copy: write it in the same transaction, or leave it out.
- Order the entries by an autoincrement key, not by a timestamp, because the device clock can move backwards.
- The check works as in section 3: read the latest entry and insert the next one in one transaction.

```kotlin
override suspend fun record(routeId: RouteId, change: UploadStatusChange, at: Instant): RecordResult =
    database.withWriteTransaction {
        val current = historyDao.latestStatus(routeId.value)?.toUploadStatus() // ORDER BY id DESC LIMIT 1
        when {
            current == null -> RecordResult.NotFound
            current != change.from -> RecordResult.Refused(current)
            else -> {
                historyDao.insert(
                    UploadHistoryEntity(
                        routeId = routeId.value,
                        status = change.to.toColumn(),
                        atEpochMillis = at.toEpochMilliseconds(),
                    ),
                )
                RecordResult.Recorded
            }
        }
    }
```

---

## 6. Test crashes and races with fakes

- **Fake the repository itself.** Its transaction is the behavior under test, and a fake DAO cannot reproduce one. This is an exception to the "fake the DAO" default in `use-cases-and-testing.md`. Test the real repository on an in-memory Room database.
- **Crashes:** wrap the repository in a fake that throws at a chosen save, before or after writing it, as if the process died there. For each save, run an attempt until it throws, then run the next attempt on the same data. Check that the workflow finished, that the server holds no duplicate, and that at most one call was repeated.
- **Races:** give the fake server some latency on virtual time, and make a competing change while a step runs, such as the user's cancel. Check that the attempt did not overwrite it.

`examples/ClientOwnedWorkflowTest.kt` shows both.

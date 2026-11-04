# Pragmatic Use Cases & Testing Architecture

One of the biggest sources of bloat in Android and KMP Clean Architecture is the **pass-through use case**, paired with a **use-case interface** that exists only for mocking. Pragmatic Clean Architecture pairs a lean domain layer with fast, deterministic, state-based tests.

---

## 1. The use-case litmus test

Before creating `FooUseCase`, ask: **"If I delete this class and call the repository from the ViewModel, what breaks?"**

### Delete it (pass-through anti-pattern)
```kotlin
// ANTI-PATTERN: pure indirection, zero domain logic.
interface ObserveRouteWithPhotosContract {
    operator fun invoke(routeId: RouteId): Flow<RouteWithPhotos>
}

class ObserveRouteWithPhotosUseCase @Inject constructor(
    private val routeRepository: RouteRepository,
) : ObserveRouteWithPhotosContract {
    override operator fun invoke(routeId: RouteId): Flow<RouteWithPhotos> =
        routeRepository.observeRouteWithPhotos(routeId)
}
```
**Why it hurts:** every new repository method needs three edits (repository, contract, implementation) plus a DI binding, for zero business value.

### Keep it when it
- **orchestrates** 2+ repositories or services;
- **enforces a domain policy** across steps;
- is **reused** by several ViewModels; or
- in KMP, is the **shared home** for logic that native iOS view models would otherwise reimplement in Swift.

```kotlin
// Orchestrates a repository and a platform service, enforces a domain rule,
// and takes time from an injected Clock.
class FinishRouteUseCase(
    private val routeRepository: RouteRepository,
    private val locationTracker: LocationTracker,
    private val clock: Clock,
) {
    suspend operator fun invoke(routeId: RouteId, name: String): FinishRouteResult {
        val route = routeRepository.getRoute(routeId) ?: return FinishRouteResult.NotFound
        if (!route.canBeFinishedWith(name)) return FinishRouteResult.NameRejected
        locationTracker.stop()
        routeRepository.saveRoute(route.finish(name, at = clock.now()))
        return FinishRouteResult.Finished
    }
}
```
Even a justified use case **doesn't need an interface**: its dependencies are interfaces, so tests fake those.

---

## 2. Sociable unit tests (Martin Fowler)

Mocking every constructor parameter of a ViewModel (`mockk<StartRouteUseCase>()`, `mockk<RouteUiMapper>()`) couples tests to wiring instead of behavior.

- Use **real** use cases, domain models, mappers, and repositories.
- **Fake the lowest I/O boundary you own** (the DAO, API client, or SDK wrapper) when the repository has logic (mapping, caching, invalid-row policies), so that logic is tested too. Fake the repository itself when it's a thin pass-through.
- With SQLDelight, whose generated queries have no interface, wrap them in a small data-source interface or run an in-memory driver.
- The test covers the vertical slice (ViewModel + use case + domain rules + repository + mapper) at unit-test speed.

---

## 3. Stateful fakes over mocking frameworks (and when exceptions apply)

Prefer stateful in-memory fakes over MockK or Mockito:
- **Mocks test implementation details** (`verify(exactly = 1) { dao.insert(any()) }`), so tests break on refactors that keep behavior.
- **Fakes are portable.** MockK runs only on the JVM, so fakes are the default for KMP `commonTest`. (If you need mocks there, Mokkery supports `commonTest`.)
- **Fakes behave like the real thing.** A `FakeDao` backed by a `MutableStateFlow` emits updates like a real database.
- **Assert on state, not calls.** Count calls in a fake only when the count *is* the behavior: deduplication ("one network call"), caching, or exposure logging.

### Escape hatch
For a characterization test around a gnarly legacy class with many concrete dependencies that you can't safely refactor today, MockK or Mockito as temporary scaffolding is fine. New code and clean boundaries get fakes.

---

## 4. Deterministic concurrency, time, and Flow testing

1. **Virtual time for delays and TTLs.** Inside `runTest`, inject `testScheduler.timeSource` as the `TimeSource` and `StandardTestDispatcher(testScheduler)` as the dispatcher. `delay()` and TTL checks then share one virtual clock, and `advanceTimeBy(5.minutes)` moves both. (`TestTimeSource` works for code without coroutines.)
2. **Don't read the system clock in production code.** Inject `kotlin.time.Clock` or `TimeSource` instead of calling `System.currentTimeMillis()` or `Clock.System.now()`.
3. **Concurrency.** Launch callers with `async` and simulate latency in fakes to prove, for example, that five concurrent refreshes of one key make one network call while different keys run in parallel.
4. **StateFlow with Turbine.** A `stateIn(SharingStarted.WhileSubscribed(...))` flow runs only while collected, so reading `state.value` alone stays at the initial value. Collect with Turbine (`state.test { awaitItem() }`) and pass `backgroundScope` as the ViewModel's scope. For androidx ViewModels that use `viewModelScope`, call `Dispatchers.setMain(StandardTestDispatcher())` in setup.

`examples/PragmaticTestingWithFakesTest.kt` shows all four.

---

## 5. Macrobenchmarks without polluting the domain

### The problem
Macrobenchmarks drive a release-like build from a separate test process, so Hilt's `@TestInstallIn` doesn't apply. Teams then add `UseCaseContract` interfaces just so a benchmark module can bind a fake use case.

### The pragmatic solution
Swap the **lowest I/O boundary** (for example, the network data source) in a variant-specific DI module, so repository logic stays on the measured code path. Now in Android's `demo` flavor uses the same idea: a fake network data source behind the real repositories.

```kotlin
// src/benchmark/kotlin/.../BenchmarkDataModule.kt
@Module
@InstallIn(SingletonComponent::class)
abstract class BenchmarkDataModule {
    @Binds
    @Singleton
    abstract fun bindRouteRemoteDataSource(impl: FakeRouteRemoteDataSource): RouteRemoteDataSource
}
```

**Avoid duplicate bindings.** The production binding must not be compiled into the benchmark variant. A `benchmark` build type compiles `src/main` and `src/benchmark` only, so move the production module from `src/main` to `src/debug` and `src/release`, or use `demo`/`prod` product flavors. Otherwise Dagger reports a duplicate binding. With Koin or Metro, choose the module per variant the same way.

---

## 6. Legacy code seams: sprout & wrap (Michael Feathers)

When adding a feature to an untestable legacy class (a 1,500-line Activity, Service, or manager), **don't** force a risky multi-week rewrite to satisfy Clean Architecture:
- **Sprout class or function:** write the new logic in a new, pure Kotlin class or function with full unit-test coverage, and call it from the legacy class with a one-line insertion.
- **Wrap (decorator):** put the legacy dependency behind a narrow domain interface so new code stays clean and testable.

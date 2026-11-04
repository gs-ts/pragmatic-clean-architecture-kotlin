# Kotlin Multiplatform Notes

How the rules apply when code is shared across Android, iOS, desktop, or web.

---

## 1. Source sets and platform services

- Keep domain models, repositories, use cases, and (if shared) ViewModels or presenters in `commonMain`.
- Put platform services (location, notifications, secure storage) behind interfaces in `commonMain`, with implementations in `androidMain`/`iosMain` wired by DI. Keep `expect`/`actual` for small platform primitives such as a function or a type alias.
- Inject dispatchers. `Dispatchers.IO` exists on the JVM and Native but not on JS or Wasm; `Dispatchers.Default` exists everywhere.
- Use `kotlin.time.Clock` and `Instant` (stable from Kotlin 2.3) in shared code.

## 2. Dependency injection

- Hilt is Android-only and can't wire `commonMain` code. Use constructor injection everywhere, and wire it with Koin, Metro, kotlin-inject, or by hand.
- Keep DI framework types out of domain classes; a plain constructor is enough.

## 3. Where logic lives depends on the iOS UI

- **Shared ViewModels** (an androidx `ViewModel` in `commonMain`, or a shared presenter): "inject the repository into the ViewModel and derive state there" works on both platforms.
- **Native SwiftUI view models:** logic in Android ViewModels gets rewritten in Swift. Put decisions in shared use cases, domain models, or presenters, and keep platform view models thin. That's a legitimate reason for a use case that would otherwise be a pass-through.

## 4. The Swift boundary

Not every Kotlin type survives export to Objective-C and Swift ([Kotlin docs](https://kotlinlang.org/docs/native-objc-interop.html)):
- **Value classes** export as their underlying primitive type or `id`. Swift sees `Int64` or `Any`, not `RouteId`, so neither the type safety nor the `init` validation crosses the boundary.
- **Sealed hierarchies** export as class hierarchies. A Swift `switch` over them isn't exhaustive and needs a `default`; SKIE adds exhaustive `onEnum(of:)` switching.
- **Flows** need an adapter on the Swift side (SKIE or KMP-NativeCoroutines).

At the edge of the shared module, expose a thin, Swift-friendly API (plain types, explicit functions) rather than raw domain types.

## 5. Formatting and resources

Keep `StringResource` (Compose Multiplatform resources) or your own `UiText` in state, and resolve it in the UI on each platform at render time.

## 6. Testing

- Fakes compile in `commonTest`. kotlin-test, kotlinx-coroutines-test, and Turbine are multiplatform.
- MockK is JVM-only. Mokkery supports mocks in `commonTest` if you need them.
- Reflection-based test tooling won't run on Kotlin/Native, which is one more reason to use fakes.

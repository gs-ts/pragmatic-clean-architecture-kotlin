# Pragmatic Clean Architecture & Tactical DDD (Kotlin / KMP / Android)

An AI Agent Skill designed for **Android Studio**, **IntelliJ IDEA**, **Claude Code**, **Cursor**, and **GitHub Copilot**.

Instead of enforcing rigid, backend-heavy Domain-Driven Design (DDD) or generating 1:1 Clean Architecture boilerplate across every layer, this skill guides AI assistants to write **pragmatic, idiomatic Kotlin** for reactive Android, Jetpack Compose, and Kotlin Multiplatform (KMP) clients.

> **New to this approach?** [`docs/WHY-PRAGMATIC-CLEAN-ARCHITECTURE.md`](./docs/WHY-PRAGMATIC-CLEAN-ARCHITECTURE.md) gives a short breakdown of the benefits and the trade-offs.

---

## What the skill teaches

- **Project conventions first:** an existing codebase's conventions and explicit project instructions override the skill's defaults, and the agent stays within the requested scope.
- **Derived values in the right place:** domain model, Flow pipeline, ViewModel state, or composable, chosen by inputs and cost.
- **Compose performance without ceremony:** preserve instances under strong skipping instead of converting collections or adding UI models.
- **Mapping at real boundaries:** mandatory for APIs and SDKs you don't control; mappers parse instead of throwing; domain models are reused in UI state by default.
- **Use cases that earn their keep:** no pass-throughs and no use-case interfaces.
- **Fakes over mocks:** sociable tests that fake the lowest I/O boundary, with virtual time and no real delays.
- **Exposure-safe feature flags and A/B tests.**
- **Tactical DDD that pays off on clients:** value objects and immutable entity snapshots, plus notes for KMP shared code and the Swift boundary.

The full rules live in [`skills/pragmatic-clean-architecture-kotlin/SKILL.md`](./skills/pragmatic-clean-architecture-kotlin/SKILL.md).

---

## Repository structure

```text
pragmatic-clean-architecture-kotlin/
├── README.md                                   # This file
├── LICENSE
├── docs/
│   └── WHY-PRAGMATIC-CLEAN-ARCHITECTURE.md     # Benefits and trade-offs, for humans
├── skills/
│   └── pragmatic-clean-architecture-kotlin/    # The skill: copy this folder
│       ├── SKILL.md                            # Ground rules, decision guides, workflow, checklist
│       ├── LICENSE.txt
│       ├── references/
│       │   ├── domain-vs-state-derivation.md   # Derived values, state styles, Compose performance
│       │   ├── aha-and-poodr-kotlin.md         # Wrong abstractions, base classes, sealed vs. polymorphism
│       │   ├── smart-mapping-and-acl.md        # Boundary mapping and when a UI model earns its place
│       │   ├── feature-flags-and-ab-testing.md # Experiment contracts and exposure-safe reads
│       │   ├── tactical-ddd-kotlin.md          # Value objects, entities, errors, DI, GoF patterns
│       │   ├── use-cases-and-testing.md        # Use-case litmus test, fakes, virtual time, Macrobenchmark
│       │   └── kmp.md                          # Kotlin Multiplatform and the Swift boundary
│       └── examples/
│           ├── RichDomainVsReactiveState.kt    # Domain rules vs. reactive ViewModel state
│           ├── AntiCorruptionAndSmartMapping.kt# Boundary mapping and UI-state reuse
│           ├── FeatureFlagsAndExperiments.kt   # Experiment contracts and exposure-safe reads
│           ├── PragmaticTestingWithFakes.kt    # Per-key deduplication, a real use case, fakes
│           └── PragmaticTestingWithFakesTest.kt# Sociable tests with virtual time and Turbine
├── ide-configs/                                # Generated from SKILL.md; don't edit by hand
│   ├── copilot-instructions.md
│   └── AGENTS.md
└── scripts/
    └── build-adapters.sh                       # Regenerates ide-configs/ from SKILL.md
```

---

## How to use in your IDE

Clone this repository (or download it as a ZIP) somewhere outside your project:
```bash
git clone https://github.com/gs-ts/pragmatic-clean-architecture-kotlin.git
```
Run the commands below from **your project's root**, with `PCAK` pointing at the clone:
```bash
PCAK=path/to/pragmatic-clean-architecture-kotlin
```

### 1. Android Studio / JetBrains AI Assistant / Claude Code / Agent Skills
Copy the skill folder into your project's `.claude/skills/` or `.agents/skills/` directory:
```bash
mkdir -p .claude/skills
cp -R "$PCAK/skills/pragmatic-clean-architecture-kotlin" .claude/skills/
```

### 2. GitHub Copilot in Android Studio
Copy `ide-configs/copilot-instructions.md` to `.github/copilot-instructions.md` in your project:
```bash
mkdir -p .github
cp "$PCAK/ide-configs/copilot-instructions.md" .github/copilot-instructions.md
```

### 3. AGENTS.md
For tools that read `AGENTS.md`, copy `$PCAK/ide-configs/AGENTS.md` to your project root, or paste its contents into your existing `AGENTS.md`.

### 4. Cursor / Windsurf
Reference `skills/pragmatic-clean-architecture-kotlin/SKILL.md` and its `references/` directory (from your copy) in your `.cursor/rules/` or `.windsurfrules` configuration.

---

## Maintaining the instruction files

`SKILL.md` is the single source of truth. The files in `ide-configs/` contain its **Ground rules** and **Checklist** sections (between the `BEGIN`/`END` markers) and are regenerated by a script:

```bash
scripts/build-adapters.sh          # regenerate ide-configs/
scripts/build-adapters.sh --check  # exit 1 if ide-configs/ is out of date (for CI)
```

---

## Reference sources
- [Martin Fowler — Domain-Driven Design](https://martinfowler.com/tags/domain%20driven%20design.html)
- [Martin Fowler — Sociable vs. Solitary Unit Tests](https://martinfowler.com/bliki/UnitTest.html)
- [Sandi Metz — The Wrong Abstraction & POODR](https://sandimetz.com/blog/2016/1/20/the-wrong-abstraction)
- [Kent C. Dodds — AHA Programming](https://kentcdodds.com/blog/aha-programming)
- [Refactoring Guru — Design Patterns](https://refactoring.guru/design-patterns)
- [Official Kotlin Documentation](https://kotlinlang.org/docs/home.html)
- [Kotlin — Interoperability with Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html)
- [Android Developers — Architecture Guide](https://developer.android.com/topic/architecture)
- [Android Developers — Fix Compose stability issues](https://developer.android.com/develop/ui/compose/performance/stability/fix)

---

## License

MIT. See [LICENSE](./LICENSE).

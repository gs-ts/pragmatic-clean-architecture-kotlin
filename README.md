# Pragmatic Clean Architecture & Tactical DDD (Kotlin / KMP / Android)

An Agent Skill for **Gemini in Android Studio**, **Claude Code**, **Cursor**, **GitHub Copilot**, **Junie**, and other agents that support the open `SKILL.md` format.

This skill helps AI assistants apply **tactical Domain-Driven Design (DDD)** and **Clean Architecture** where they pay off in Kotlin client apps (Android, Jetpack Compose, Kotlin Multiplatform): rich domain models, value objects, a ubiquitous language, and clean boundaries. Use it while writing new code, or as a **reviewer** for an existing feature: it suggests concrete improvements and explains their trade-offs instead of demanding rewrites. It doesn't impose backend-style DDD ceremony or 1:1 layer boilerplate, and it respects your project's existing conventions.

> **New to this approach?** [`docs/WHY-PRAGMATIC-CLEAN-ARCHITECTURE.md`](./docs/WHY-PRAGMATIC-CLEAN-ARCHITECTURE.md) gives a short breakdown of the benefits and the trade-offs.

---

## What the skill teaches

- **Tactical DDD where it pays off:** domain rules on the model (Tell, Don't Ask), value objects for IDs and constrained values, `sealed` types that make impossible states unrepresentable, immutable entity snapshots, and names in the product's language.
- **Project conventions first:** an existing codebase's conventions and explicit project instructions override the skill's defaults. In reviews, the skill suggests improvements instead of flagging deliberate conventions as defects.
- **Derived values in the right place:** domain model, Flow pipeline, ViewModel state, or composable, chosen by inputs and cost.
- **Mapping at real boundaries:** mandatory for APIs and SDKs you don't control, with mappers that parse instead of throwing. UI state reuses domain models, with display values derived by getters.
- **Compose performance without ceremony:** preserve instances under strong skipping instead of converting collections or adding UI models.
- **Use cases that earn their keep:** no pass-throughs and no use-case interfaces.
- **Fakes over mocks:** sociable tests that fake the lowest I/O boundary, with virtual time and no real delays.
- **Exposure-safe feature flags and A/B tests.**
- **Kotlin Multiplatform:** shared domain code, DI without Hilt, and what survives the Swift boundary.

The full rules live in [`skills/pragmatic-clean-architecture-kotlin/SKILL.md`](./skills/pragmatic-clean-architecture-kotlin/SKILL.md).

---

## Use it as a reviewer

Point your agent at an existing feature, for example:

> Review the route-details feature with the pragmatic-clean-architecture-kotlin skill.

> Suggest DDD improvements for `RouteRepository` and its domain models.

The skill reads the feature end to end and reports suggestions in priority order: correctness risks first, then domain-modeling improvements, simplifications, and tests. Each suggestion comes with its location, why it helps, a short before/after, and the trade-off. Optional suggestions are marked as such, and nothing changes unless you ask.

---

## Repository structure

```text
pragmatic-clean-architecture-kotlin/
├── README.md                                     # This file
├── LICENSE
├── docs/
│   └── WHY-PRAGMATIC-CLEAN-ARCHITECTURE.md       # Benefits and trade-offs, for humans
├── skills/
│   └── pragmatic-clean-architecture-kotlin/      # The skill: copy this folder
│       ├── SKILL.md                              # Ground rules, decision guides, workflows, checklist
│       ├── LICENSE.txt
│       ├── references/
│       │   ├── domain-vs-state-derivation.md     # Derived values, state styles, Compose performance
│       │   ├── aha-and-poodr-kotlin.md           # Wrong abstractions, base classes, sealed vs. polymorphism
│       │   ├── smart-mapping-and-acl.md          # Boundary mapping and when a UI-specific type earns its place
│       │   ├── feature-flags-and-ab-testing.md   # Experiment contracts and exposure-safe reads
│       │   ├── tactical-ddd-kotlin.md            # Value objects, entities, errors, DI, GoF patterns
│       │   ├── use-cases-and-testing.md          # Use-case litmus test, fakes, virtual time, Macrobenchmark
│       │   └── kmp.md                            # Kotlin Multiplatform and the Swift boundary
│       └── examples/
│           ├── RichDomainVsReactiveState.kt      # Domain rules vs. reactive ViewModel state
│           ├── AntiCorruptionAndSmartMapping.kt  # Boundary mapping and UI-state reuse
│           ├── FeatureFlagsAndExperiments.kt     # Experiment contracts and exposure-safe reads
│           ├── PragmaticTestingWithFakes.kt      # Per-key deduplication, a real use case, fakes
│           └── PragmaticTestingWithFakesTest.kt  # Sociable tests with virtual time and Turbine
├── ide-configs/                                  # Generated from SKILL.md; don't edit by hand
│   ├── copilot-instructions.md
│   └── AGENTS.md
└── scripts/
    └── build-adapters.sh                         # Regenerates ide-configs/ from SKILL.md
```

---

## How to use in your IDE

Each agent looks for skills in its own folder:

| Agent | Project folder |
| :--- | :--- |
| Gemini in Android Studio (Agent Mode) | `.agents/skills/` |
| Claude Code | `.claude/skills/` |
| Cursor | `.agents/skills/` or `.cursor/skills/` |
| GitHub Copilot | `.agents/skills/`, `.github/skills/`, or `.claude/skills/` |
| Junie (JetBrains) | `.junie/skills/` |

The simplest setup is one copy in `.agents/skills/` and a link to it from `.claude/skills/`.

### 1. Install the skill

Clone this repository (or download it as a ZIP) somewhere outside your project:
```bash
git clone https://github.com/gs-ts/pragmatic-clean-architecture-kotlin.git
```

Then, from **your project's root**, with `PCAK` pointing at the clone:
```bash
PCAK=path/to/pragmatic-clean-architecture-kotlin
mkdir -p .agents/skills .claude/skills
cp -R "$PCAK/skills/pragmatic-clean-architecture-kotlin" .agents/skills/
ln -s ../../.agents/skills/pragmatic-clean-architecture-kotlin .claude/skills/
# Junie: mkdir -p .junie/skills && ln -s ../../.agents/skills/pragmatic-clean-architecture-kotlin .junie/skills/
```
- Commit `.agents/` and `.claude/` to share the skill with your team.
- To have it in every project, just for you, run the same commands from your home folder (`cd ~`) instead.
- On Windows, copy the folder into `.claude/skills/` instead of linking it.

Agents load the skill on their own when a request matches it. To call it explicitly, type `@pragmatic-clean-architecture-kotlin` in Gemini's Agent Mode, or `/pragmatic-clean-architecture-kotlin` in Claude Code or Cursor.

### 2. Optional: always-on rules

`ide-configs/` holds a short version of the rules (the ground rules and the checklist) for tools that read an instruction file on every request:
- **AGENTS.md** (read by Gemini in Android Studio, GitHub Copilot, Windsurf, and others): copy `$PCAK/ide-configs/AGENTS.md` to your project root, or paste its contents into your existing `AGENTS.md`.
- **GitHub Copilot:** copy `$PCAK/ide-configs/copilot-instructions.md` to `.github/copilot-instructions.md`.

These files are added to every prompt, so use them on top of the skill only if you want the checklist applied all the time.

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

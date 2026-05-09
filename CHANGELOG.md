# Changelog

All notable changes to re-frame2 are recorded in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses pre-release suffixes (`.beta`, `.alpha`, `.rc`) on its way to a stable v1.0.0 line. Once stable, releases follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Artefacts published per release (in lock-step — all 10 artefacts ship together at the same VERSION per [rf2-w05l](#) and [docs/release-process.md §Policy](docs/release-process.md#policy)):

| Artefact | Tier | Role |
|---|---|---|
| `day8/re-frame-2` | core | Registry, drain, fx, dispatch, subscribe, frame-provider, trace, the substrate-adapter contract, the headless plain-atom adapter |
| `day8/re-frame-2-schemas` | per-feature | Spec 010 — Malli-backed schema-attachment surface |
| `day8/re-frame-2-machines` | per-feature | Spec 005 — state machines |
| `day8/re-frame-2-routing` | per-feature | Spec 012 — routing |
| `day8/re-frame-2-flows` | per-feature | Spec 013 — flows |
| `day8/re-frame-2-http` | per-feature | Spec 014 — managed HTTP |
| `day8/re-frame-2-ssr` | per-feature | Spec 011 — SSR & hydration |
| `day8/re-frame-2-epoch` | per-feature | Tool-Pair §Time-travel — epoch / time-travel |
| `day8/re-frame-2-reagent` | per-substrate | Spec 006 — Reagent adapter (browser default) |
| `day8/re-frame-2-uix` | per-substrate | Spec 006 — UIx adapter ([rf2-3yij](#)) |

A future Helix adapter (`day8/re-frame-2-helix`, [rf2-2qit](#)) slots in alongside the existing per-substrate leaves when it ships.

Spec changes are tracked separately under `spec/` and referenced from each entry.

## [Unreleased]

### Added

### Changed

### Removed

### Fixed

### Spec

## [0.0.1.beta] — _unreleased_

First public pre-release. Mike fills in the release notes manually before tagging — this template lists the major themes that landed in the lead-up to the cut so the eventual notes have a starting point. None of these are commitments; the final list ships when the tag does.

### Added

- Reactive substrate machinery: `defn`-shape `reg-view`, frame-aware re-renders, and `frame-provider` for substrate-agnostic apps (Spec 006).
- `:rf.http/managed` effect family with retry / decode / abort semantics (Spec 014).
- Multi-instance frames and the `frame-provider` substrate boundary that lets adapters ship independently of the core (Spec 006 §Substrate-adapter shipping convention).
- Production-elision contract (Spec 009): dev-only diagnostics drop out of advanced-compile bundles; CI gates on the elision probe (rf2-11hn).
- Artefact split (rf2-0hxm + rf2-5vjj): `day8/re-frame-2` ships substrate-agnostic; the seven per-feature artefacts (`-schemas`, `-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-epoch`) ship as separate Maven coordinates so a consumer who omits a feature does not pay for it on the classpath; the per-substrate artefacts (`-reagent`, `-uix`) keep substrate code out of any app that has chosen the other substrate. All 10 artefacts ship in lockstep at the same VERSION per [rf2-w05l](#).
- `MIGRATION.md` (`spec/MIGRATION.md`) for agent-driven migration of v1 codebases to v2.

### Changed

- Public dependency coordinate moved from `re-frame/re-frame` to `day8/re-frame-2`. The `re-frame.core` namespace and its public surface are unchanged for the migration core path; see `spec/MIGRATION.md` for the full rule set.
- Alpha-namespace dissolution: features that lived under `re-frame.alpha.*` in late v1 development are either promoted into the public surface, retired, or moved into a substrate-adapter ns. See the relevant spec sections for each feature.

### Removed

- The `re-frame/re-frame` 1.x compatibility shim. v1 and v2 cannot coexist on a single classpath; the coordinate move makes the redesign visible to ops tooling. See `spec/MIGRATION.md` §M-0.

### Spec

- Spec 006 — Reactive Substrate (substrate-adapter contract, `frame-provider`, artefact-split shipping convention).
- Spec 009 — Production builds and the elision contract.
- Spec 014 — `:rf.http/managed` effect.
- `spec/Conventions.md` — published Maven coordinates and the per-substrate dependency shape.
- `spec/MIGRATION.md` — v1 → v2 migration rules.

[Unreleased]: https://github.com/day8/re-frame2/compare/v0.0.1.beta...HEAD
[0.0.1.beta]: https://github.com/day8/re-frame2/releases/tag/v0.0.1.beta

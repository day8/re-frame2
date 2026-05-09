# Changelog

All notable changes to re-frame2 are recorded in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses pre-release suffixes (`.beta`, `.alpha`, `.rc`) on its way to a stable v1.0.0 line. Once stable, releases follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Artefacts published per release (in lock-step):

- `day8/re-frame-2` — core (registry, drain, machines, flows, routing, fx, schemas, trace, headless plain-atom adapter)
- `day8/re-frame-2-reagent` — Reagent substrate adapter
- `day8/re-frame-2-uix` *(planned, [rf2-3yij](https://github.com/day8/re-frame2/issues))*
- `day8/re-frame-2-helix` *(planned, [rf2-2qit](https://github.com/day8/re-frame2/issues))*

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
- Artefact split (rf2-0hxm): `day8/re-frame-2` ships substrate-agnostic; `day8/re-frame-2-reagent` ships the Reagent adapter as a separate Maven coordinate so a UIx- or Helix-only app does not transitively pull in Reagent.
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

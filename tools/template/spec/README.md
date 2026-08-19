# Template (`day8/re-frame2-template`) — spec

## Files

- [000-Vision.md](000-Vision.md) — the v2 equivalent of v1's `day8/re-frame-template`: front-door scaffolding tool for new re-frame2 apps via deps-new + git-coord distribution.
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the substrate variants (`:reagent` default, `:uix`); top-level k/v invocation form; substrate coercion.
- [002-Generated-Shape.md](002-Generated-Shape.md) — file layout the template emits; deps-new resource tree (`root/` + `_shared/` + per-substrate); substitution variables.
- [003-DepsNew-Rebuild-Plan.md](003-DepsNew-Rebuild-Plan.md) — completed migration record for the deps-new rebuild; historical, not the current API contract.
- [004-SSR-Validation-Report.md](004-SSR-Validation-Report.md) — completed validation record for the shipped SSR variant.
- [005-Repo-Split.md](005-Repo-Split.md) — procedure for the remaining split into `github.com/day8/re-frame2-template`.
- [API.md](API.md) — consolidated public surface: every invocation form, every argument, every supported flag.
- [Principles.md](Principles.md) — template-specific design principles (build-time only, never on consumer classpath); why for the major decisions lives in DESIGN-RATIONALE.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — reasons behind the current design and its superseded alternatives.
- [findings/](findings/) — exploratory working substrate; audit lineage, not normative.

## How to use

Start with [`API.md`](API.md) for invocation and argument details. The current normative design is in 000–002 and [`Principles.md`](Principles.md); [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) explains the choices. Files 003–004 are completed migration/validation records, while 005 owns the pending repository split. `findings/` preserves audit lineage and is never normative.

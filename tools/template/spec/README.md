# Template (`day8/re-frame2-template`) — spec

## Files

- [000-Vision.md](000-Vision.md) — what the tool is for: one command, one small runnable counter SPA; lineage from v1's `day8/re-frame-template`; goals and non-goals.
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the one selector, `:substrate` (`:reagent` default, `:uix`); its coercion; what each value swaps; how a substrate is added.
- [002-Generated-Shape.md](002-Generated-Shape.md) — the twelve-file manifest, the resource tree it comes from, the substitution variables, the hot-reload contract.
- [005-Repo-Split.md](005-Repo-Split.md) — procedure for the remaining split into `github.com/day8/re-frame2-template`.
- [API.md](API.md) — the public surface: invocation, the arguments, the errors.
- [Principles.md](Principles.md) — the design principles.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — why, including the decisions this shape superseded.
- [findings/](findings/) — exploratory working substrate; audit lineage, not normative.

## How to use

Start with [`API.md`](API.md). The normative shape is 000–002 and [`Principles.md`](Principles.md); [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) explains the choices and records the superseded ones. 005 owns the pending repository split. The completed migration and validation records that sat at 003 and 004 now live in git history.

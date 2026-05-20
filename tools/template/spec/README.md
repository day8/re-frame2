# Template (`day8/re-frame2-template`) — Spec

## Files

- **[000-Vision.md](000-Vision.md)** — The v2 equivalent of v1's `day8/re-frame-template`: front-door scaffolding tool for new re-frame2 apps via deps-new + git-coord distribution.
- **[001-Substrate-Variants.md](001-Substrate-Variants.md)** — The three substrate variants (`:reagent` default, `:uix`, `:helix`); top-level k/v invocation form; substrate coercion.
- **[002-Generated-Shape.md](002-Generated-Shape.md)** — File layout the template emits; deps-new resource tree (`root/` + `_shared/` + per-substrate); substitution variables.
- **[003-DepsNew-Rebuild-Plan.md](003-DepsNew-Rebuild-Plan.md)** — Normative migration plan for the deps-new rebuild (rf2-dolpf): final shape, Stage 2-4 decomposition, cross-references to the v1 emit-spec locks.
- **[API.md](API.md)** — Consolidated public surface: every invocation form, every argument, every supported flag.
- **[Principles.md](Principles.md)** — Template-specific design principles (build-time only, never on consumer classpath); WHY for the major decisions lives in DESIGN-RATIONALE.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — WHY behind 000 / 001 / 002 / Principles / API; deps-new over clj-new + git-coord over Clojars; beads referenced as rf2-xxxx.
- **[findings/](findings/)** — Exploratory working substrate; audit lineage, not normative.

## How to use

This folder is complete enough to one-shot the tool. Read [`000-Vision.md`](000-Vision.md) first to anchor scope (deps-new template, build-time-only, alpha-channel `day8/re-frame2-*` coords, git-coord distribution); the capability docs (001–002) are normative — they own the substrate-variants matrix and the generated file shape. [`Principles.md`](Principles.md) and [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) capture the locks. [`API.md`](API.md) is the consolidated invocation reference. [`003-DepsNew-Rebuild-Plan.md`](003-DepsNew-Rebuild-Plan.md) is the migration record (§2-3 landed; §4 outstanding). `findings/` preserves the audit lineage and is never normative.

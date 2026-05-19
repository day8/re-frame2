# Template (`day8/clj-template.re-frame2`) — Spec

## Files

- **[000-Vision.md](000-Vision.md)** — The v2 equivalent of v1's `day8/re-frame-template`: front-door scaffolding tool for new re-frame2 apps via clj-new.
- **[001-Substrate-Variants.md](001-Substrate-Variants.md)** — The three substrate variants (`:reagent` default, `:uix`, `:helix`); invocation form; `:edn-args` plumbing rationale.
- **[002-Generated-Shape.md](002-Generated-Shape.md)** — File layout the template emits; resource tree it reads from; substitution variables threaded through.
- **[003-DepsNew-Rebuild-Plan.md](003-DepsNew-Rebuild-Plan.md)** — Normative migration plan for the deps-new rebuild (rf2-dolpf): final shape, Stage 2-4 decomposition into worker-scope commits, and cross-references to the v1 emit-spec locks.
- **[API.md](API.md)** — Consolidated public surface: every invocation form, every argument, every supported flag.
- **[Principles.md](Principles.md)** — Template-specific design principles (build-time only, never on consumer classpath); WHY for the major decisions lives in DESIGN-RATIONALE.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — WHY behind 000 / 001 / 002 / Principles / API; clj-new over deps-new; beads referenced as rf2-xxxx.
- **[findings/](findings/)** — Exploratory working substrate; audit lineage, not normative.

## How to use

This folder is complete enough to one-shot the tool. Read [`000-Vision.md`](000-Vision.md) first to anchor scope (clj-new template, build-time-only, alpha-channel `day8/re-frame2-*` coords); the capability docs (001–002) are normative — they own the substrate-variants matrix and the generated file shape. [`Principles.md`](Principles.md) and [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) capture the locks. [`API.md`](API.md) is the consolidated invocation reference. `findings/` preserves the audit lineage and is never normative.

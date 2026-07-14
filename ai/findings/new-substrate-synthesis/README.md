# re-frame2 UI — the unified substrate specification

**Status:** final · 2026-07-11

## The proposal in one paragraph

One first-party view library for re-frame2 — the only *taught* view layer — replacing
UIx and Helix outright, with **stock Reagent retained as a frozen compatibility tier**
(it enables the two-step v1 migration — dataflow first on existing Reagent/re-com views,
`ui` per subtree later). Views
are hiccup with **event vectors as handlers**; a compiler lowers them to one normalized
AST and emits direct React code for the browser and a structural render tree for the JVM
— no interpreter ships. A view's reads share **one** React bridge under a committed
push-ownership protocol (render probes; commit acquires; observation targets carry Story
overrides honestly), making abandoned renders retain nothing and concurrent rendering
tear-proof. Frames are created at **host preflight** (never from render) and are distinct
from **roots** (React hydration units) — a page is N roots referencing M frames, each
with precise failure isolation. Dev builds are a glass cockpit — per-commit cause
vectors, occurrence-level identity, static interaction surfaces, source coordinates,
loss-accounted histories — consumed by the Xray that already exists, and erased from
production by proven absence. Hot reload is a designed contract (stable shells, hook
signatures, no frame re-seeding). Controlled inputs get a designed synchrony law, not a
benchmark hope.

## Documents

| Doc | Contents |
|---|---|
| [01-goals-and-invariants.md](01-goals-and-invariants.md) | Ranked goals, non-goals, sixteen unconditional invariants, the decision rule |
| [02-programming-model.md](02-programming-model.md) | `defview`, templates, props ABI, the handler law, presence, `ui/html`, interop, REPL story |
| [03-reactivity-and-ownership.md](03-reactivity-and-ownership.md) | Observation targets, probe/acquire + commit algorithm, observed lifecycle (three runtime states; hide/unmount as qualified retroactive annotations), frames & preflight ENSURE, **HMR contract**, error taxonomy |
| [04-debugging.md](04-debugging.md) | Two evidence layers, cause vectors, occurrence identity, Xray integration (enrich-first), privacy, elision proof |
| [05-production.md](05-production.md) | Capability vocabulary, honest cost claims, absence roster, packaging, budgets |
| [06-ssr-islands.md](06-ssr-islands.md) | Roots vs frames, the root manifest, the JVM subset, hydration + failure isolation, static-root policy |
| [07-testing.md](07-testing.md) | The `ui.test` contract, fixture matrix, generative parity (scoped), gate roster G-1…G-14 |
| [08-delivery.md](08-delivery.md) | Rulings-first Stage 0, spikes, demand bar (pre-Stage-1), risks, **the decision record (§5)** |
| [09-review-disposition.md](09-review-disposition.md) | Decision log and provenance — history lives there |
| [10-migration-from-reagent.md](10-migration-from-reagent.md) | The Reagent path: ~80–90% mechanical, incremental by co-mounting, verdict per construct |
| [11-adoption-workstreams.md](11-adoption-workstreams.md) | Everything beyond the library — migrator, docs, tools, CI, deletion wave — as stage epics |
| [12-implementation-plan.md](12-implementation-plan.md) | Where the artifact lands, the **blessed** public API table + demand-bar audit, the stage→bead plan |
| [drafts/](drafts/) | Diff-ready spec amendment drafts (004 interim + rewrite, 006 observation port, `ui.test` selector grammar) |
| [guide/](guide/README.md) | The user tutorial (13 chapters: main track 01–07, depth 08–13) |
| [reviews/](reviews/) | The review archive |

## Naming

**`re-frame.ui`** (artifact `day8/re-frame2-ui`, alias `ui/`) — R-3.

## Standing-ruling conformance

Follows rf2-nyea0r (the frame-root/frame-provider split and the compiled substrate's
host-preflight ENSURE both landed — the split in the shipping adapters (#5691) and
ENSURE-at-host-preflight in `re-frame.ui` (#5711); the frozen legacy adapters keep
commit-owned two-pass ENSURE for their lifetime, and R-7 now carries only the spec
promotion of that already-live contract), rf2-y6dz8t
(capture-frame hold), frames-isolated doctrine (no
cross-frame spelling; carried-op misuse gets a dev diagnostic), rf2-5sjbg local-state
doctrine, Story-as-CLJS-unit-tests, and the one-catalogue rule (rf2-cs0kd1) for all new
trace/error vocabulary. Spec amendments R-1 (Spec 004 rewrite) and R-2 (Spec 006
observation port) land as diff-ready PRs in Stage 0–1; promotion of this suite to
`spec/` rides Stage 0–1 per R-1/R-2.

## Status and provenance

Final; the decisions were ratified 2026-07-11 by Mike (the 08 §5 decision record). The
decision log is [09-review-disposition.md](09-review-disposition.md); the review archive
is [reviews/](reviews/).

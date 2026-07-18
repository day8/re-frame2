# 08 — Delivery: stages, spikes, demand bar, risks, the decision record

**Status:** final · 2026-07-11. The stage plan settles rulings first, makes debugging an
early consumer, takes SSR later, and keeps resumability and the toy DOM emitter off the
critical path. Spec-amendment posture: **pre-alpha, the spec is ours to amend when the
benefit is strong — commit, don't tiptoe.**

## 1. Stage 0 — settle the load-bearing rulings and spike the physics

**The frozen rulings** (settled on paper, before code): the five-identity table (I-8) + root
manifest (06 §2) · frame ENSURE preflight (03 §8) · observation-target protocol (03 §3) ·
the observed lifecycle — three runtime states, hide/unmount as qualified retroactive
annotations (03 §4) · the JVM subset (06 §1) · event projections + handler
boundary law (02 §3) · error-boundary semantics (02 §6) · presence contract (02 §7) ·
capability vocabulary (05 §1) · `ui.test` contract (07 §2).

**Spikes** (throwaway, test-owned, stop conditions):

| # | Spike | Exit criterion |
|---|---|---|
| S-1 codegen | props/DOM/branches/keyed-list/event-vector `defview` → inspect advanced output | within G-1 parity |
| S-2 push falsification | 500 mounted reactive views, 1 sub delta/epoch | confirms committed push economics; failure **reopens 03** (no silent fork) |
| S-3 concurrency | ViewCell: conditional reads, abandoned mounts, commit-gap, Activity, **observation-target races, pass-scoped probe memo** | 10k abandoned renders retain zero; reconnect provable on public React |
| S-4 dual host | shared analyzer → CLJS + JVM tree → per-root hydration parity | structural agreement; failed-root isolation |
| S-5 input synchrony | controlled input under the sync door (02 §3) | caret/IME matrix green |

**Spike outcomes (2026-07-11; both reports in [spikes/](spikes/)):** S-1, S-2, S-3, and
S-5 are **feasibility PASS**. S-4 is a feasibility pass on **dual-host structural output
only** — root-manifest hydration, multi-root mounting, and failed-root isolation were
not run and remain named gates. Also remaining as named open gates despite the passes:
grafting the pure cold probe onto the **real sub-cache** (S-3 used a purpose-built
stand-in graph; the graft conformance runs in Stage 2), the **G-8 real-browser input
matrix** (S-5's evidence is jsdom-only — Chromium/WebKit IME, caret-on-restore, paint
timing), and a **G-1 rerun under the revised alternating-rounds/median-of-rounds
estimator** (the reported S-1 numbers used the earlier best-round/min estimator).
S-2's result is decisive (pull 4.0–6.5× worse than push, gap growing with scale): no
grounds to reopen 03. S-3's §5 shape proposal (target/evidence/lease) is now the sole
port-shape source per the codex2 F1 disposition (09) — superseding R-2's
"shapes provisional" framing; the controlled-input trigger predicate is **confirmed
sufficient** by S-5 (no longer provisional).

Deliberately not in Stage 0: a delegation spike (research-tier — it exists only to serve
the replay queue, itself research-tier) and a standing toy direct-DOM emitter (instead,
an **AST-shape gate** asserts the IR carries edit-list-sufficient information, plus one
archived spike — the option stays priced without a permanently maintained
implementation).

## 2. Stages 1–7

1. **Thinnest dual-host vertical slice** — `defview`, AST, literal props/DOM, internal
   views, branches, keyed lists, direct lowering, JVM structural output, root descriptor,
   escaping + `ui/html`, structural parity. No subs/effects/presence/SSR-payloads.
   *Demand-bar table produced before this stage builds surface.*
2. **re-frame2 ownership + HMR** — preflight ENSURE, ViewCell, observation targets,
   conditional subs, `rf=` stabilization, commit reconciliation, abandonment/disposal,
   frame scope/provider, drain-quiescence read/render batching, `flush!`, **the full HMR matrix (03 §10 —
   the guide sells hot reload on page one, so it ships with reactivity, not after
   SSR)**.
3. **Committed host behavior + debugging as first consumer** — event vectors + scalar
   projections + sync-input door, `local`, effects, `dispatch-fn`, foreign
   callbacks/components, error boundary, portals *(wave-2 in the blessed API table —
   the table wins on existence; this stage slot applies only if `ui/portal` is
   promoted)*, client-only, exact cleanup; **and the
   evidence schema: manifests, instance records, causes, integrated into existing Xray
   surfaces** (04 §5 — debuggability is a product goal, so Xray consumes before alpha,
   not after). Counter + dashboard must feel complete here, hot reload included.
   *[AMENDED 2026-07-16 — component-library readiness (directed): this stage also
   carries the P0 triad (atomic `local` updater · `ui/event` vector-outcome sync-door
   arm · internal render slots) plus the safe-spread policy, the native-library
   layout/ref blessing, and the docs/slot manifest projection — 12 §3 has the per-bead
   fold-in; `drafts/component-library-readiness.md` is the owning delta doc. Rationale:
   re-com is the first, most important consumer test; these amend S3-owned semantics
   before their enforcement hardens, per the stage-boundary absorption doctrine.]*
4. **Presence and web boundaries** — presence, custom elements, head policy, forms/IME
   guidance, high-confidence a11y diagnostics. Bounded; not an animation/form/a11y
   framework.
5. **SSR roots + hydration** — root manifests, idempotent frame payloads, digests,
   failure isolation, static-root explicit policy, `client-only` phase flip; rides
   existing Spec 011 contracts.
6. **Production specialization + consumer gates** — capability-specialized output,
   absence/equivalence gates (G-7/G-11/G-12), budgets (G-10/G-14), benchmark suite;
   **migrate one non-trivial real app plus Story and Xray** as the proof-of-consumer.
7. **Alpha** — every gate green, demand-bar prune, generated-JS examples + methodology
   published, guide-fixtures complete, spec amendments merged.

## 3. The demand bar

Produced **before Stage 1**: a `name → consumer | wave-2` table over every public
name. Named wave-2 rows with standing goals behind them: the **editor/kondo diagnostics
layer** (the AI-agent-ergonomics goal, 01 §Secondary) and `ui/view`-in-production
registry entries. Known wave-2 rows (per the blessed table): `ui/element`, `ui/view`, `ui/portal`,
`data/render`. (`ui/spread` and `->react` were promoted to v1 on 2026-07-12 — table
deltas #3/#2: `spread` is the conversion architecture's single dynamic-map path;
`->react` is the outward migration bridge, lands S6.) Not in v1: the resumability bootstrap/queue
(post-alpha research, 06 §4), the delegation mechanism (with it), `:memo false`,
`:on-mount`/`:on-unmount`, `:rf.ui/form-data`, `:rf.ui/event`. In v1, each with a named
consumer: `ui/presence` (toasts/modals in examples; Replicant-parity
capability), `ui/html` (docs/CMS/markdown rendering in the doc apps), `ui/error-boundary`
(every real app's route shell), `ui.test/*` (every Tier-1 test). Guide examples authored
here don't count as independent demand for *platform-scale* features — the rule stays
written down (it is what keeps resumability research-tier).

## 4. Risk register

| Risk | Stop condition / mitigation |
|---|---|
| Push economics falsified late | S-2/G-13 run at Stage 0/2; failure reopens 03 before hardening |
| Sync-input door leaks into general dispatch | the door is keyed to controlled-input sites only; a fixture asserts non-input dispatches still batch |
| Slice-scoped probe memo becomes a cache | it dies with the slice; a leak fixture pins it |
| Observation-target capture misses a resolution source | the target is the *only* resolution point; overrides/pins/context all resolve into it at render — fixture per source |
| Placeholder vocabulary creep | closed set (3 scalars); additions need demand + serializability proof |
| Two handler mechanisms blur the idiom | manifest `:dynamic`/non-serializable marks + dev nudges keep pressure toward vectors |
| Presence grows toward an animation system | the contract is the 02 §7 list; anything beyond enter/exit retention is out |
| Compiler grows a second Clojure | closed control-form grammar; explicit escapes; features need multiple real sites |
| Spec amendments stall | R-1/R-2 land as diff-ready PRs during Stage 0–1 (pre-alpha posture: amend when the benefit is strong) |
| HMR site identity drifts under edits | source anchor + path + generation; release/remount on ambiguity — correctness over preservation |

## 5. The decision record

| # | Ruling | Decision |
|---|---|---|
| R-1 | Spec 004 | **Staged merge — landed; the law as it now stands.** The portability *law*, live in `spec/004-Views.md` §The portability law and the template AST: a portable view has one deterministic, serialisable **template representation**, produced by the shared analyzer and consumed by that build's host emitter; emitted host values may be host-native and need not themselves be serialisable. Analysis is host-parameterized, so each build lowers its own AST and hands it to exactly one emitter — the hosts never meet as ASTs. Parity between the two emitter implementations is **normalized structural equivalence** (fingerprinted), which *detects* divergence rather than preventing it. The staging played out as ruled: a small broadening amendment carried the interim form first (`drafts/spec-004-interim-amendment.md`, now superseded), and the full normative rewrite (views/forms/lanes/positional-args + 002/009/Conventions ripples) then merged **atomically with the first conforming Stage-1 slice**. |
| R-2 | Spec 006 | **Semantics frozen; signatures after S-3.** The six invariants in 03 §3 are the contract; exact ObservationTarget/Probe/Lease shapes are provisional, and S-3's exit criteria include proposing them. The port is explicitly **outside** the closed public ten-fn adapter map. |
| R-3 | Name | **`re-frame.ui`**, alias `ui`, artifact `day8/re-frame2-ui`; supporting `re-frame.ui.test` / `.react` / (if earned) `.data`. "Facet" may live as internal codename only — never public vocabulary. |
| R-4 | Bare fns | **Narrow law:** bare fns legal only in **known native event properties** (`:on-click`, `:on-input`, custom-element native events) where invoker+phase are known — not refs, not arbitrary fn-valued props. Foreign boundaries explicit. Plus a day-one **strict lint** `{:re-frame.ui/bare-handlers :warn|:error}` — teams adopt explicit-everywhere as policy without a language change (flipping the language later would break source; the lint is the honest lever). Loop bare-fns keep their warning. |
| R-5 | Resumability | **Research-tier, decisively.** Graduation requires all of: independent application consumer · cross-engine event-order/default-action/IME/forms/portal conformance · CSP/payload-versioning/privacy/failure behavior · measured improvement over ordinary hydration · hard size+complexity budget. Serializability preserves the option; it creates no obligation. |
| R-6 | Packaging | **Separate artifact, lockstep release train initially** (core and UI protocols are co-evolving; no independently-moving versions yet). Do **not** casually revisit at alpha — coordinate changes stop being free exactly then; a future umbrella coordinate can give "one dependency" without collapsing the architecture. |
| R-7 | Frame chain | **The staged chain (recorded on the beads):** h1-foundation (`upsert-frame!` + registrar/source-store fencing) → nyea0r (frame-root/provider split; commit-owned two-pass ENSURE for current adapters, host/compiler preflight for the new substrate; no byte-identical clause) → h1 public removal (callers → public `make-frame`; teaching surfaces straight to `frame-root`) → y6dz8t. |
| — | Presence | **`ui/presence` wrapper, no reserved nodes.** `:timeout-ms` (unit-suffixed); completion on transition/animation end with the timeout as mandatory safety bound; keyed children required, fail-loud; `presence-phase` is the single phase read and returns `:present` outside a boundary (reusable children); Xray identity = presence-site + occurrence key. |
| — | Adapters | **`re-frame.ui` is the optimized/default/only taught view layer. Stock Reagent and UIx are frozen compatibility adapters; Helix + reagent-slim are removed** after the proof/default/soak gates: RealWorld-resources green · Story + Xray green · SSR/hydration + HMR matrices green · production-specialization + bundle-absence gates green · template/docs/examples default to `re-frame.ui` · zero repo-owned non-historical Helix/reagent-slim imports · **soak: two consecutive green nightlies + one week of repo work with no fallback**. **The compatibility adapters:** correct but frozen — a pinned contract suite + one browser smoke **for each** stay in CI; bugs are fixed to preserve the pinned contract; no new capabilities and no parity promise with `ui` features, performance, debugging, examples, or templates (presence, causes, static interaction surfaces are `ui`-only); `reg-view` freezes with stock Reagent; UIx remains governed primarily by Spec 006 plus its API/Conventions/Ownership rows; one minimum compatibility reference makes the two choices discoverable without teaching them. **Why (named consumers):** Reagent + re-com enable the two-step migration for existing v1 apps, including Mike's own re-com apps; existing UIx apps retain a correct re-frame2 boot choice without pulling UIx into the forward product surface. `ui` never depends on either compatibility adapter (G-12 holds at the artifact level). **The v1 runtime law is unchanged:** exactly one adapter is installed per process, chosen at boot from `ui/adapter`, `reagent-adapter/adapter`, or `uix-adapter/adapter`; there is no per-frame or within-frame adapter coexistence. `ui/raw` / `ui/->react` and ordinary foreign React-component interop do not select or install another adapter. Keep: the Reagent and UIx artifacts/public exports, Spec 006 contracts, plain-atom, their pinned compatibility suites and smokes, UIx classpath/classifier/release leaves, benchmark results + fixtures, a git tag of the removed Helix/slim surfaces, and slim's rationale docs. |
| — | Proof app | **RealWorld-resources** (leases, auth/classified data, machines, routing, mutations, async settlement). Don't distort it toward features it doesn't need — those stay conformance/testbed cases. **One vertical page migrates at Stage 3** to surface ergonomic problems early; the full app is the Stage-6 proof. RealWorld-http follows as ordinary migration, not a second gate. |
| — | Budget | **B-lite:** one worker on S-1 (codegen, **carrying S-4 dual-host as its second phase** — the codegen worker naturally builds both emitters), one on S-3 (observation/concurrency, carrying **S-2 push-falsification and S-5 input-synchrony as riders** on its harness). Other slots stay on correctness/ordinary work. No production Stage 1 until **all five spikes** pass (via those two workers) **and** the R-1/R-2 drafts are reconciled. Never saturate all six slots on the substrate pre-proof. Paper (spec drafts) proceeds immediately; hot-zone spec merges only with their conformance slice and a free surface. |

## 6. What this replaces

On alpha: **Helix and reagent-slim exit**; **Reagent and UIx stay as frozen
compatibility adapters** (one pinned contract suite + one smoke each, one minimum
compatibility reference, zero primary examples/templates/teaching). UIx therefore exits
the primary examples and guide path, but its artifact, public exports, classpath probe,
compatibility coverage, smoke, classifier arm, and release test/deploy leaf remain.
`re-frame.ui` is the only taught way and the only recipient of new capabilities,
performance work, debugging integration, examples, and templates. The headline external
story becomes the **two-step migration**: (1) a v1 Reagent/re-com app moves its dataflow
to re-frame2 keeping its views on the compat tier — gaining Xray/epochs/Story/schemas/
machines immediately; (2) views migrate to `ui` per subtree, later, with the migrator
([10-migration-from-reagent.md](10-migration-from-reagent.md)); re-com widgets are the
last movers (Reagent islands / foreign heads until the `ui`-native answer lands — that
answer is now a **directed program**: the re-com native-port epic `rf2-6ajm6z`, Wave 0
gated on the `ai/decisions/re-com-readiness.product-rulings.md` register; substrate
readiness rides S3 per `drafts/component-library-readiness.md`, 2026-07-16). **The repo's own migration** (examples, testbeds, Story/Xray
scenes, guides — all primary surfaces to `ui`) remains the budgeted Stage-6 workstream.
Every process still installs exactly one adapter; those rendering boundaries are React
interop, not per-subtree adapter selection.

# EP-0030: The Compiled-View Substrate Program (re-frame.ui)

Status: accepted
Type: standards-track

> **Graduating in stages.** The S1 and S2 slices have already landed in `spec/`:
> the full Spec 004 rewrite plus [`spec/004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md),
> [`spec/004C-Roots-and-Mount.md`](../../spec/004C-Roots-and-Mount.md), and
> [`spec/004D-UI-Test-Selectors.md`](../../spec/004D-UI-Test-Selectors.md) merged
> atomically with the Stage-1 conformance slice (ruling R-1), and the observation-port
> ABI is normative in [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md)
> §The internal observation port (ruling R-2). Per EP-0009 the spec governs wherever it
> and this EP differ; every later stage graduates the same way — spec edits merge
> atomically with that stage's conformance fixtures, never ahead of them.

> This is the umbrella EP of the compiled-view substrate program. It records **one
> decision surface**: add a first-party compiled view library, **`re-frame.ui`**, as a
> **new, experimental view substrate offered alongside the existing adapters**, delivered
> as staged conformance slices S1–S7 behind a demand-bar-gated public surface.
> `re-frame.ui` is an additional option, **not** a mandated replacement and **not** the
> only taught view layer: **Reagent, UIx, and reagent-slim live on as first-class,
> actively-supported adapters; only Helix is removed** (Resolved Decisions, 2026-07-17).
> The program-level decision, delivery plan, and operator rulings live here; the
> per-domain contracts live in the sibling EPs (EP-0031–EP-0035). Normative home: the
> Spec 004 family, Spec 006, and the Spec 008/009/011 rows each stage amends.

## Abstract

re-frame2 gets one first-party view layer. Views are hiccup with event vectors as
handlers; a compiler lowers them to one normalized AST and emits direct React code for
the browser and a structural render tree for the JVM — no interpreter ships. A view's
reads share one React bridge under a committed push-ownership protocol, frames are
created at host preflight (never from render), dev builds are a glass cockpit that
provably vanishes from production. `re-frame.ui` ships as a **new, experimental,
first-party view substrate** — an additional option, not a mandated replacement; stock
Reagent, UIx, and reagent-slim live on as first-class, actively-supported adapters; only
Helix is removed once the S7 soak gates pass.

## Motivation

The adapter trio was the exploration phase, and it worked — but it leaves the project
paying three taxes at once. **A parity tax:** every capability, test matrix, example,
and teaching page is built three ways or held to the weakest adapter. **An
interpretation tax:** hiccup walked at runtime, wrapper components, per-render
allocation — costs a compiler deletes wholesale. **A correctness ceiling:** modern React
(concurrent rendering, StrictMode, Activity, hydration, HMR) punishes render-time
ownership and speculative publication, a fight a first-party substrate ends by
construction. Meanwhile the things re-frame2 is actually about — frames carried
explicitly, events as the transition boundary, subscriptions as the one reactive
grammar, causal debuggability — deserve a view layer designed around them.

The unresolved alternatives (keep the trio; bless one existing adapter; build new) were
worked through a full synthesis dossier with spikes, adversarial reviews, and a ratified
decision record — a conversation that does not fit in a bead. This EP is its durable
record.

## Goals / Non-Goals

**Goals (ranked; the sibling EPs carry the full statements):**

1. Correct under modern React — concurrent rendering, StrictMode, hydration, HMR; no
   leaked owners, no render-time domain events.
2. re-frame2-native conceptual integrity — frames carried, events as the boundary,
   subscriptions as reads; no second state model.
3. Excellent ergonomics — one obvious spelling per job; no manual memoization, deps
   arrays, or frame-capture boilerplate.
4. Exceptional production efficiency — no interpreter, no dev machinery in production;
   claims are CI gates, not adjectives.
5. Causal debuggability — a committed repaint joins to its exact causes, emitted at the
   cause site, via public React behavior only.
6. Determinism and testability — headless-first; structural parity across CLJS and JVM.
7. Web-platform correctness as a design input — forms/IME, custom elements, trusted
   markup, focus are contracts, not polish.

**Non-Goals:** reproducing Reagent/UIx/Helix APIs inside `re-frame.ui`; a second state
model (signals, ratoms, cursors, form runtimes); Suspense-as-loading-state, RSC,
`startTransition` over app-db; pre-hydration event replay / resumability (research-tier
by ruling R-5); non-React emitters in v1 (priced by an AST-shape gate, not a maintained
implementation). This EP also does not decide the per-domain contracts — those are
EP-0031–EP-0034 — nor the component-library readiness package (EP-0035).

## Relationships

- **Sibling EPs (authored with this one; each a narrow decision surface):**
  **EP-0031** (programming model — `defview`, templates, the handler law, presence,
  interop), **EP-0032** (reactivity & ownership — observation targets, ViewCell,
  commit protocol, frames/preflight ENSURE, HMR), **EP-0033** (view evidence &
  debugging), **EP-0034** (production, SSR, and testing posture), **EP-0035**
  (component-library readiness — the directed S3 amendments and the re-com consumer).
- **EP-0014** names the derivation/process algebra; the substrate reads through that one
  reactive grammar and adds no second one.
- **EP-0029** is the format precedent for this EP family and keeps machines ordinary
  projections the view layer merely reads.
- **EP-0002 / EP-0024** own explicit frame resolution and unified frame identity — the
  substrate's carried-frame law and preflight ENSURE build directly on them.
- **Specs:** the Spec 004 family (`004-Views.md`, 004B/004C/004D),
  `spec/006-ReactiveSubstrate.md` (observation port; adapter contract), `spec/008`
  (the `ui.test` contract home), `spec/009-Instrumentation.md` (evidence and catalogue
  rows), `spec/011` (SSR roots and hydration).
- **Source dossier:** `ai/findings/new-substrate-synthesis/` (01–12, drafts, guide,
  spikes, reviews) — the design record this EP family distills; retired per the Bead Plan.

## Specification

### The decision

`re-frame.ui` (artifact `day8/re-frame2-ui`, alias `ui` — ruling R-3) is a **new,
experimental, first-party view substrate** of re-frame2 — an additional option, **not** a
mandated replacement and **not** the only taught view layer. It is the focus of the
program's new compiled-substrate capability, performance, and debugging-integration work.
**Stock Reagent, UIx, and reagent-slim live on as first-class, actively-supported
adapters** — they are not frozen and are not scheduled for removal, keeping their
contract suites plus one browser smoke each. **Only Helix is removed** after the
proof/soak gates (S7). The v1 runtime law is unchanged: exactly one adapter is installed
per process, chosen at boot; foreign React interop (`ui/raw`, `ui/->react`) never selects
a second adapter.

### The program decision record (ratified 2026-07-11)

The full record is `ai/findings/new-substrate-synthesis/08-delivery.md` §5; the rulings:

| # | Surface | Ruling |
|---|---|---|
| R-1 | Spec 004 | Staged merge: the portability law lands immediately; the full normative rewrite merges atomically with the first conforming Stage-1 slice. *(Fired — landed with S1.)* |
| R-2 | Spec 006 | Observation-port semantics frozen up front, exact shapes proposed by spike S-3; the port lives outside the closed ten-fn adapter map. *(Normative in Spec 006 since S2.)* |
| R-3 | Name | `re-frame.ui`, alias `ui`, artifact `day8/re-frame2-ui`; "facet" never public vocabulary. |
| R-4 | Bare fns | Legal only in known native event properties (invoker + phase known); day-one strict lint `{:re-frame.ui/bare-handlers :warn\|:error}`. |
| R-5 | Resumability | Research-tier, decisively; serializability preserves the option and creates no obligation. |
| R-6 | Packaging | Separate artifact on a lockstep release train; not casually revisited at alpha. |

Named companion rulings from the same record: the **presence** contract (`ui/presence`
wrapper, no reserved nodes, mandatory timeout safety bound); the **adapters** ruling
(the coexistence shape above — Reagent + re-com enable the two-step v1 migration; UIx
apps keep a correct boot choice; **the original "frozen compatibility adapters" /
"reagent-slim removed" shape is superseded on the adapter-disposition point by the
2026-07-17 reframing — see Resolved Decisions**); the **proof app** (RealWorld-resources
— one vertical page at S3, the full app at S6); the **budget** (B-lite: never saturate
all worker slots on the substrate pre-proof); and the **frame chain** (R-7 — the staged
frame-root/provider split, since landed).

### Stages S1–S7

Delivery is staged conformance slices; each stage wires its gates into CI in that stage
and merges its spec edits atomically with its fixtures. Stage 0 (rulings + five spikes:
codegen, push falsification, concurrency, dual host, input synchrony) completed with all
spikes feasibility-PASS before any production code.

1. **S1 — compiler slice:** `defview`, AST, dual emitters (CLJS + JVM), roots/mount,
   compile-error roster, `ui.test` Tier-1 core, parity corpus, G-1/G-14 gates.
2. **S2 — ownership + frames + HMR:** observation port, ViewCell + commit algorithm,
   preflight ENSURE, drain-quiescence batching, `flush!`, the full HMR matrix.
3. **S3 — committed host behavior + debugging as first consumer:** event vectors,
   sync-input door, `local`/`effect`/`dispatch-fn`, foreign boundaries, error boundary,
   evidence schema consumed by Xray, elision gates, one RealWorld vertical page. Carries
   the directed component-library readiness amendments (EP-0035).
4. **S4 — presence + web boundaries:** presence, custom elements, head policy, a11y
   diagnostics.
5. **S5 — SSR roots + hydration:** root manifests, idempotent frame payloads, failure
   isolation, `render-static`.
6. **S6 — production specialization + repo adoption:** capability-specialized output,
   absence/equivalence/budget gates, migrator, examples/docs/skills/template/CI
   rewrite; RealWorld-resources + Story + Xray green is the proof.
7. **S7 — alpha + Helix-removal wave:** every gate green, demand-bar prune, **only Helix
   removed** — strictly behind the soak gates (two consecutive green nightlies + one week
   of repo work with no fallback). Reagent, UIx, and reagent-slim live on as first-class
   adapters; `re-frame.ui` remains a new experimental substrate, not their replacement.

### The demand-bar-gated public surface

Every public name needed a named consumer **before Stage 1**, recorded in the blessed
API table — `ai/findings/new-substrate-synthesis/12-implementation-plan.md` §2, with the
§2b authoritative surface matrix (name → stage / owner / proof fixture / spec home).
This EP references those tables rather than duplicating them; **anything not in the
table is not part of `re-frame.ui`'s public surface** (the demand bar disciplines the new
substrate's own API — it says nothing about the retained adapters). The table was
**blessed as-is by Mike on 2026-07-12 00:39
AUSEST as the API freeze for v1**, with a delta protocol: findings that touch the table
return as row-level deltas for re-ruling; the freeze itself is not reopened. Five deltas
are ruled under it: **#1** `ui/custom-element` to v1 (2026-07-12), **#2** `ui/->react`
(the outward migration bridge, lands S6), **#3** `ui/spread` (the single dynamic-map
conversion path), **#4** `ui/slot` + internal `render-fn` widening (directed
2026-07-16), **#5** the literal safe-policy `spread` form (2026-07-16). Guide examples
authored by this project never count as independent demand for platform-scale features
— the rule that keeps resumability research-tier.

### Adoption workstreams

The library alone is not the program. Fifteen workstreams
(`11-adoption-workstreams.md`) decompose everything beyond it: the migrator tool (W1),
migration + authoring skills (W2/W6), the docs/guide rewrite (W3), examples migration
with the `substrates/` deletion (W4), the hot-zone spec-tree waves (W5), tool evidence
consumption into Xray/Story/Pair (W7a, S3 — debugging is the *first* consumer, not the
last), the tools' own UIs migrating as the dogfood proof (W7b), template collapse (W8),
the CI rewrite ending at three named causal suites (W9), SSR hosts (W10), one-time
benchmarks against the legacy adapters before Helix removal (W11), repo meta-docs (W12),
the Helix-removal wave (W13), conformance corpus (W14), and program management (W15).
None are optional; `ui.test`, the migrator, and the skills are critical path.

### Migration posture

The external story is a **two-step migration** (`10-migration-from-reagent.md`): step 1
moves a v1 Reagent/re-com app's *dataflow* to re-frame2 with views unchanged on the
coexisting Reagent adapter — gaining Xray, epochs, Story, schemas, machines immediately;
step 2 optionally migrates views to `ui` per subtree, on the app's schedule, with the
migrator (~80–90% mechanical). re-com widgets are the last movers; their `ui`-native answer is now a
**directed program** — epic `rf2-6ajm6z`, with substrate readiness riding S3 per EP-0035.

### Risks

The register is `08-delivery.md` §4; the headline stop conditions: push economics
falsified late (S-2/G-13 run early; failure reopens the ownership design, no silent
fork), the sync-input door leaking into general dispatch (keyed to controlled-input
sites; a fixture pins it), the compiler growing a second Clojure (closed control-form
grammar; explicit escapes), and HMR identity drift (release/remount on ambiguity —
correctness over preservation).

## Rationale

**One substrate, first-party** beats both alternatives. Keeping the trio preserves the
three taxes forever and makes every future capability a four-way negotiation. Blessing
one existing adapter inherits its runtime model — interpretation cost, render-time
ownership, foreign HMR semantics — and still leaves re-frame2's frame and evidence
contracts bolted on from outside. A compiler whose target *is* re-frame2 makes the
invariants (EP-0031/0032) hold by construction and makes production claims provable by
absence gates rather than discipline.

**Staged conformance slices** rather than a big-bang: each stage lands with its
fixtures, its gates in CI, and its spec edits atomic — so the repo is never knowingly
nonconformant and a fresh reviewer can say exactly what "Stage-N-conforming" asserts.

**The demand bar** exists because the failure mode of a green-field view layer is
surface sprawl. Freezing the table before Stage 1 — then admitting change only as
ruled row-level deltas — keeps the surface honest while staying amendable where real
consumers (re-com being the first) demonstrate need.

**Keep Reagent, UIx, and reagent-slim as first-class adapters, and offer `re-frame.ui`
alongside them** rather than as their replacement. All three have named consumers: the
two-step migration for existing v1/re-com apps, a correct boot choice for existing UIx
apps, and the slim-bundle option. They stay actively supported — `re-frame.ui` is the new
experimental substrate, not a mandate to leave them. **Only Helix is removed** once `ui`
ships; the others cover its niche.

## Backwards Compatibility

Pre-alpha: no compatibility shims. Within this repo, view surfaces gain `re-frame.ui` as
a new option over S6–S7 (examples, testbeds, tools, template, docs) while the retained
adapters keep working. For external apps the compatibility surface is deliberate: the
Reagent, UIx, and reagent-slim adapters stay first-class and actively supported, keeping
their contract suites and smokes in CI (`reg-view` stays with stock Reagent), and the
removal wave touches **only Helix**, behind the S7 soak gates. Benchmarks against the
legacy adapters run once before Helix removal; the results, fixtures, and a git tag of
the removed surface survive.

## Bead Plan / Reference Implementation

The program epic is **`rf2-vxgfnd`** (`EPIC: re-frame.ui program (S1–S7)`), with
per-stage child epics filed at the *prior* stage boundary so every brief cites
then-current contracts. Status at this EP's acceptance: **S1 and S2 are merged**
(S1's six children landed 2026-07-12; S2 core verified S3-ready under the
`rf2-vxgfnd.22` adversarial boundary review), and **S3 is in flight** as epic
`rf2-vxgfnd.95` with children `.95.1`–`.95.10` plus the directed readiness children
(`rf2-8k14ia`, `rf2-ri0k6n`, `rf2-isdqjv`). Trigger-gated spikes (`ui/tpl`, registered
`ui/view`, `ui/portal`, `defview-alias`, reset-key `local`) sit on the program epic,
not in stage scope, until their named triggers fire. The **re-com native port is a
separate directed epic, `rf2-6ajm6z`** — deliberately not a program stage, so component
migration never blocks S3–S7 (and vice versa).

**Source-folder retirement (directed by Mike 2026-07-16, in-session — the fold-into-EPs
direction; two steps, reconciled with the standing S7 cleanup bead
`rf2-vxgfnd.99.1`).** Step one, at the **S3→S4 boundary**: a tombstone README replaces
the dossier index, and a citation sweep repoints live references — bead briefs **and
this EP family's own into-folder citations** — to this EP family and the specs. Two
relocations gate that tombstone: (i) the **blessed 12 §2/§2b API tables** — a *living*
freeze authority with an active delta protocol — graduate to their durable home,
`spec/API.md` §`re-frame.ui` (the W5 manifest), carrying the delta protocol with them;
(ii) `09-review-disposition.md` — the binding codex2 per-finding rulings — relocates
under `reviews/` as retained historical evidence. Step two, at **S7 per
`rf2-vxgfnd.99.1`**: the tree itself is git-rm'd once every cited contract has its
spec home and no live brief dangles (that bead's own audit). Until step two the tree
remains force-tracked in place. Survival rules meanwhile: `drafts/*` survive until
their owning stage **consumes** them — spec merges under the Q61 atomic-landing rule,
or the W1/W3/W7a/W9/W11 workstreams for the tool/docs/CI-facing drafts; `prep/*`
survives until its PREP-ANCHORED beads consume it (`rf2-gria2b`, `rf2-nwgzha`,
`rf2-3339ri`, `rf2-nojiwy`); `guide/` moves to `docs/guide` at S6 (W3); `skill/` moves
to `skills/` at S6 (W6); `spikes/` and `reviews/` remain as historical evidence
referenced from this EP.

**Guide-impact assessment (EP-0009 rule 5):** the program adds `re-frame.ui` coverage
across the `docs/core` view surface (W3) — views, frames, the introduction counter,
forms — keeps the substrate-choice teaching (now presenting `re-frame.ui` as a new
experimental option alongside the retained Reagent/UIx/reagent-slim boot choices), and
rebuilds the playground. Sibling EPs name per-domain impacts.

## Resolved Decisions

- **Program ratification (2026-07-11, Mike).** The 08 §5 decision record — R-1…R-6 plus
  the presence, adapters, proof-app, budget, and frame-chain rulings — is the ruled
  decision surface this EP records. Its provenance log is
  `ai/findings/new-substrate-synthesis/09-review-disposition.md`. *(The adapters ruling's
  original "frozen compatibility adapters" / "reagent-slim removed" shape is superseded on
  the adapter-disposition point by the 2026-07-17 reframing below; all other rulings
  stand.)*
- **API freeze (2026-07-12 00:39 AUSEST, Mike).** The 12 §2 public-surface table is
  blessed as-is as the v1 API freeze, governed by the row-level delta protocol; deltas
  #1–#5 (custom-element, `->react`, `spread`, `slot`/internal `render-fn`, safe-policy
  `spread`) are ruled and recorded in the table.
- **Conditional S3 advance (2026-07-15, Mike; recorded in `rf2-vxgfnd.95` NOTES).** S3
  starts immediately in parallel with the bounded S2 correction tail, overriding the
  epic's original entry condition; S2's truth conditions stand — it is not declared
  conforming until its correction beads and adversarial proof are green, and S3 must
  not weaken or silently absorb those corrections. Hot-zone files stay single-owner
  behind the S2 owner. A throughput ruling, not a proof waiver.
- **Component-library readiness (2026-07-16, Mike, directed).** re-com is the first and
  most important consumer test; the substrate gets ready now. S3 carries the P0 triad
  (atomic `local` updater, the `ui/event` vector-outcome sync-door arm, internal render
  slots) plus the safe-spread policy, native-library layout/ref blessing, and the
  docs/slot manifest projection — full contract in **EP-0035**; the port itself is epic
  `rf2-6ajm6z`.
- **Adapter disposition reframed (2026-07-17, Mike).** `re-frame.ui` is a **new,
  experimental view substrate offered as an additional option** — not a mandated
  replacement of the adapter trio and not the only taught view layer. Stock **Reagent,
  UIx, and reagent-slim live on as first-class, actively-supported adapters** (not frozen,
  not scheduled for removal); **only Helix is removed** at S7, behind the soak gates. This
  supersedes the adapter-disposition point of the 2026-07-11 ratification (the "frozen
  compatibility adapters" and "reagent-slim removed" shape). The technical interop-boundary
  contract for legacy Reagent embedded in a `ui` host is **unchanged** — the coexistence
  mechanism (shared React context object, `frame-provider`, HMR-inward, sibling/inward/
  outward granularities) stays valid because Reagent still coexists with `re-frame.ui`;
  only the "frozen tier" *labeling* is retired in favour of "compatibility/interop tier."

## Recommendation

Keep EP-0030 `accepted` while the stages land; it moves to `final` when S7 completes —
every gate green, the demand-bar prune done, **Helix removed** behind the soak gates, and
the retained adapters' continued-support posture recorded in their spec homes. The
program's shape is already proving itself: two stages merged under adversarial boundary
reviews, the third in flight, and the first external consumer (re-com) driving real
contract work through the API freeze's own delta protocol rather than around it.

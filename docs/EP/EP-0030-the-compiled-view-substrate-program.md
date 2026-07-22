# EP-0030: The Compiled-View Substrate Program (re-frame.ui)

Status: accepted
Type: standards-track
Created: 2026-07-16
Resolution: accepted 2026-07-11 (program ratification); adapter disposition reframed 2026-07-17

## Abstract

This is the umbrella EP of the compiled-view substrate program. It records **one
decision surface**: add a first-party compiled view library, **`re-frame.ui`**, as
a **new, experimental view substrate offered alongside the existing adapters**,
delivered as staged conformance slices S1–S7 behind a demand-bar-gated public
surface.

Views are hiccup with event vectors as handlers; a shared analyzer lowers them to
a normalized AST, and each host build emits from its own — direct React code for
the browser, a structural render tree for the JVM — no interpreter ships. A
view's reads share one React bridge under a committed push-ownership protocol,
frames are created at host preflight (never from render), and dev builds are a
glass cockpit that provably vanishes from production.

`re-frame.ui` is an additional option, **not** a mandated replacement and **not**
the only taught view layer: stock **Reagent, UIx, and reagent-slim live on as
first-class, actively-supported adapters; only Helix is removed**, behind the S7
soak gates. The program-level decision, delivery plan, and operator rulings live
here; the per-domain contracts live in the sibling EPs (EP-0031–EP-0035). The
normative homes are the Spec 004 family, Spec 006, and the Spec 008/009/011 rows
each stage amends; per EP-0009 the spec governs wherever it and this EP differ,
and every stage graduates the same way — spec edits merge atomically with that
stage's conformance fixtures, never ahead of them.

## Motivation

The adapter trio was the exploration phase, and it worked — but it leaves the
project paying three taxes at once. **A parity tax:** every capability, test
matrix, example, and teaching page is built three ways or held to the weakest
adapter. **An interpretation tax:** hiccup walked at runtime, wrapper components,
per-render allocation — costs a compiler deletes wholesale. **A correctness
ceiling:** modern React (concurrent rendering, StrictMode, Activity, hydration,
HMR) punishes render-time ownership and speculative publication, a fight a
first-party substrate ends by construction. Meanwhile the things re-frame2 is
actually about — frames carried explicitly, events as the transition boundary,
subscriptions as the one reactive grammar, causal debuggability — deserve a view
layer designed around them.

The alternatives were genuine: keep the trio, bless one existing adapter, or
build new. They were worked through a full synthesis dossier with spikes,
adversarial reviews, and a ratified decision record — a conversation that does
not fit in a bead. This EP is that record's durable home.

## Specification

### Goals and non-goals

**Goals (ranked; the sibling EPs carry the full statements):**

1. Correct under modern React — concurrent rendering, StrictMode, hydration, HMR;
   no leaked owners, no render-time domain events.
2. re-frame2-native conceptual integrity — frames carried, events as the
   boundary, subscriptions as reads; no second state model.
3. Excellent ergonomics — one obvious spelling per job; no manual memoization,
   deps arrays, or frame-capture boilerplate.
4. Exceptional production efficiency — no interpreter, no dev machinery in
   production; claims are CI gates, not adjectives.
5. Causal debuggability — a committed repaint joins to its exact causes, emitted
   at the cause site, via public React behavior only.
6. Determinism and testability — headless-first; structural parity across CLJS
   and JVM.
7. Web-platform correctness as a design input — forms/IME, custom elements,
   trusted markup, focus are contracts, not polish.

**Non-goals:** reproducing Reagent/UIx/Helix APIs inside `re-frame.ui`; a second
state model (signals, ratoms, cursors, form runtimes);
Suspense-as-loading-state, RSC, `startTransition` over app-db; pre-hydration
event replay / resumability (research-tier by ruling R-5); non-React emitters in
v1 (priced by an AST-shape gate, not a maintained implementation). This EP also
does not decide the per-domain contracts — those are EP-0031–EP-0034 — nor the
component-library readiness package (EP-0035).

### The decision

`re-frame.ui` (artifact `day8/re-frame2-ui`, alias `ui` — ruling R-3) is a
**new, experimental, first-party view substrate** of re-frame2 — an additional
option, **not** a mandated replacement and **not** the only taught view layer. It
is the focus of the program's new compiled-substrate capability, performance, and
debugging-integration work. **Stock Reagent, UIx, and reagent-slim live on as
first-class, actively-supported adapters** — they are not frozen and are not
scheduled for removal, keeping their contract suites plus one browser smoke each.
**Only Helix is removed**, after the proof/soak gates (S7). The v1 runtime law is
unchanged: exactly one adapter is installed per process, chosen at boot; foreign
React interop (`ui/raw`, `ui/->react`) never selects a second adapter.

### The program decision record

The program was ratified as one decision record (2026-07-11). The rulings:

| # | Surface | Ruling |
|---|---|---|
| R-1 | Spec 004 | Staged merge: the portability law landed immediately; the full normative rewrite merged atomically with the first conforming Stage-1 slice. |
| R-2 | Spec 006 | Observation-port semantics frozen up front, exact shapes from the ownership spike; the port lives outside the closed ten-fn adapter map. Normative in Spec 006 since S2. |
| R-3 | Name | `re-frame.ui`, alias `ui`, artifact `day8/re-frame2-ui`; "facet" never public vocabulary. |
| R-4 | Bare fns | Legal only in known native event properties (invoker + phase known); the proposed day-one strict lint was withdrawn pre-alpha (style, not safety — rf2-b6pua ruling, 2026-07-19). |
| R-5 | Resumability | Research-tier, decisively; serializability preserves the option and creates no obligation. |
| R-6 | Packaging | Separate artifact on a lockstep release train; not casually revisited at alpha. |

Named companion rulings from the same record: the **presence** contract
(`ui/presence` wrapper, no reserved nodes, mandatory timeout safety bound); the
**adapters** ruling (the coexistence shape above — Reagent + re-com enable the
two-step v1 migration; UIx apps keep a correct boot choice — later reframed on
the adapter-disposition point; see Resolved Decisions); the **proof app**
(RealWorld-resources — one vertical page at S3, the full app at S6); the
**budget** (B-lite: never saturate all worker slots on the substrate pre-proof);
and the **frame chain** (R-7 — the staged frame-root/provider split, since
landed).

### Stages S1–S7

Delivery is staged conformance slices; each stage wires its gates into CI in that
stage and merges its spec edits atomically with its fixtures. Stage 0 (the
rulings plus five feasibility spikes: codegen, push falsification, concurrency,
dual host, input synchrony) completed with every spike a feasibility PASS before
any production code.

1. **S1 — compiler slice:** `defview`, AST, dual emitters (CLJS + JVM),
   roots/mount, compile-error roster, `ui.test` Tier-1 core, parity corpus,
   G-1/G-14 gates.
2. **S2 — ownership + frames + HMR:** observation port, ViewCell + commit
   algorithm, preflight ENSURE, host-checkpoint render batching, `flush!`, the
   full HMR matrix.
3. **S3 — committed host behavior + debugging as first consumer:** event
   vectors, sync-input door, `local`/`effect`/`dispatch-fn`, foreign boundaries,
   error boundary, evidence schema consumed by Xray, elision gates, one
   RealWorld vertical page. Carries the directed component-library readiness
   amendments (EP-0035).
4. **S4 — presence + web boundaries:** presence, custom elements, head policy,
   a11y diagnostics.
5. **S5 — SSR roots + hydration:** root manifests, idempotent frame payloads,
   failure isolation, `render-static`.
6. **S6 — production specialization + repo adoption:** capability-specialized
   output, absence/equivalence/budget gates, the migration skill,
   examples/docs/skills/template/CI rewrite; RealWorld-resources + Story + Xray
   green is the proof.
7. **S7 — alpha + Helix-removal wave:** every gate green, demand-bar prune,
   **only Helix removed** — strictly behind the soak gates (two consecutive
   green nightlies + one week of repo work with no fallback). Reagent, UIx, and
   reagent-slim live on as first-class adapters; `re-frame.ui` remains a new
   experimental substrate, not their replacement.

S1–S4 are complete and declared conforming, and S5's surfaces are shipped and
proven (the S3, S4, and S5 conformance profiles live under `spec/conformance/`;
the S5 profile's formal conforming declaration is pending); S6 is closed
administratively, and S7 — entered 2026-07-21 on the narrowed proof recorded in
the dated addendum under §Resolved Decisions — is the live stage. For a stage in
flight the program's tracker is the state, not this page. Trigger-gated spikes (`ui/tpl`,
registered `ui/view`, `ui/portal`, `defview-alias`, reset-key `local`) sit
outside stage scope until their named triggers fire; EP-0035 records the
rulings. The re-com native port is a separate directed program — deliberately
not a program stage, so component migration never blocks S3–S7 (and vice
versa).

### The demand-bar-gated public surface

Every public name needed a named consumer **before Stage 1**, recorded in the
blessed API table — `spec/API.md` §"re-frame.ui — blessed public-surface
freeze", with the §2b authoritative surface matrix (name → stage / owner / proof
fixture / spec home). This EP references those tables rather than duplicating
them; **anything not in the table is not part of `re-frame.ui`'s public
surface** (the demand bar disciplines the new substrate's own API — it says
nothing about the retained adapters). The table is blessed as the v1 API
freeze, with a delta protocol: findings that touch the table return as
row-level deltas for re-ruling; the freeze itself is not reopened. Six deltas
are ruled under it: **#1** `ui/custom-element`, **#2** `ui/->react` (the outward
migration bridge; lands S6), **#3** `ui/spread` (the single dynamic-map
conversion path), **#4** `ui/slot` plus the internal `render-fn` widening,
**#5** `ui/spread-safe` (the literal safe-policy sibling of `ui/spread`), and
**#6** `render-static` as a macro (the call-site literal-root-form guarantee
shared by every root entry point requires one). Guide
examples authored by this project never count as independent demand for
platform-scale features — the rule that keeps resumability research-tier.

### Adoption workstreams

The library alone is not the program. Fifteen workstreams decompose everything
beyond it: the Reagent→`re-frame.ui` migration skill (W1 — an AI skill,
`skills/reagent-migration`, not a codemod tool; the former W2 sibling migration
skill is absorbed into this single skill), the authoring skills (W6), the
additive `docs/core/re-frame.ui` guide (W3 — its wholesale docs rewrite
superseded by the shipped additive guide, later growth demand-driven), examples
gaining `re-frame.ui` variants while
`substrates/` is **retained** minus its Helix arm (W4), the hot-zone spec-tree
waves (W5), tool evidence consumption into Xray/Story/Pair (W7a, S3 — debugging
is the *first* consumer, not the last), the tools' own UIs migrating as the
dogfood proof (W7b), the template gaining a `re-frame.ui` scaffold and losing
its Helix variant (W8), the CI rewrite ending at **four** named causal suites
(W9), SSR hosts (W10), one-time benchmarks against the existing adapters before
Helix removal (W11), repo meta-docs (W12), the Helix-removal wave (W13), the
conformance corpus (W14), and program management (W15). None are optional;
`ui.test` and the skills are critical path.

Because the retained adapters keep their example coverage, their suites, and
their boot choices, none of these workstreams deletes a surface that belongs to
Reagent, UIx, or reagent-slim; each one *adds* the `re-frame.ui` option beside
them and subtracts only Helix. The template's post-Helix variant menu is a
ruled, supported external-alpha surface (see Resolved Decisions): W8 adds the
experimental `re-frame.ui` scaffold beside the retained `:reagent` (default) and
`:uix` variants, drops `:helix`, and leaves reagent-slim a reserved future
variant on its existing trigger. Whether the menu ever narrows later remains a
product choice reserved to Mike, and W8 does not presume one.

### Migration posture

The external story is a **two-step migration**. Step 1 moves a v1 Reagent/re-com
app's *dataflow* to re-frame2 with views unchanged on the coexisting Reagent
adapter — gaining Xray, epochs, Story, schemas, and machines immediately. Step 2
optionally migrates views to `ui` per subtree, on the app's schedule, with the
migration skill — it applies the M-tier mechanical rewrites and reasons through
the D-tier judgment cases with the author rather than guessing. re-com widgets
are the last movers; their
`ui`-native answer is a directed program of its own, with substrate readiness
delivered at S3 per EP-0035.

### The design-record retirement

The synthesis dossier this EP family distills is retired in two steps. The
first is in force: its index is a tombstone pointer map, the blessed API tables
live in their durable home (`spec/API.md` §`re-frame.ui`, carrying the
delta protocol with them), and the binding per-finding review dispositions are
archived in Git history at `41375ba940` under the tree's `reviews/` directory
(the tree is untracked as of S4; restore via `git show 41375ba940:<path>`). The tree
itself is disk-removed at S7, once every cited contract has its spec home and no live
brief dangles. Until then: `drafts/*` survive until their owning stage consumes
them (spec merges under the atomic-landing rule, or the W1/W3/W7a/W9/W11
workstreams for the skill/docs/CI-facing drafts); `guide/` is overtaken by the
shipped additive `docs/core/re-frame.ui` guide (W3), not relocated; `skill/`
moves to `skills/` at S6 (W6); `spikes/` and `reviews/`
remain historical evidence referenced from this EP family.

### Risks

The headline stop conditions: push economics falsified late (the falsification
benchmark runs early; failure reopens the ownership design, no silent fork), the
sync-input door leaking into general dispatch (keyed to controlled-input sites;
a fixture pins it), the compiler growing a second Clojure (closed control-form
grammar; explicit escapes), and HMR identity drift (release/remount on ambiguity
— correctness over preservation).

### Guide impact

The program's `re-frame.ui` teaching shipped as the **additive
`docs/core/re-frame.ui` guide** (W3; rf2-b1wv9/#6544) — a dedicated subtree
covering the mental model, building a view, state, events/handlers,
reactivity/ownership, presence, SSR, testing, and interop — rather than a
wholesale rewrite of the existing `docs/core` view chapters, which keep working
and keep the substrate-choice teaching (presenting `re-frame.ui` as a new
experimental option alongside the retained Reagent/UIx/reagent-slim boot
choices). Later guide growth — and any playground work — is demand-driven, not a
program-scale promise. Sibling EPs name per-domain impacts.

## Rationale

**One substrate, first-party** beats both alternatives. Keeping the trio
preserves the three taxes forever and makes every future capability a four-way
negotiation. Blessing one existing adapter inherits its runtime model —
interpretation cost, render-time ownership, foreign HMR semantics — and still
leaves re-frame2's frame and evidence contracts bolted on from outside. A
compiler whose target *is* re-frame2 makes the invariants (EP-0031/0032) hold by
construction and makes production claims provable by absence gates rather than
discipline.

**Staged conformance slices** rather than a big-bang: each stage lands with its
fixtures, its gates in CI, and its spec edits atomic — so the repo is never
knowingly nonconformant and a fresh reviewer can say exactly what
"Stage-N-conforming" asserts.

**The demand bar** exists because the failure mode of a green-field view layer
is surface sprawl. Freezing the table before Stage 1 — then admitting change
only as ruled row-level deltas — keeps the surface honest while staying
amendable where real consumers (re-com being the first) demonstrate need.

**Keep Reagent, UIx, and reagent-slim as first-class adapters, and offer
`re-frame.ui` alongside them** rather than as their replacement. All three have
named consumers: the two-step migration for existing v1/re-com apps, a correct
boot choice for existing UIx apps, and the slim-bundle option. They stay
actively supported — `re-frame.ui` is the new experimental substrate, not a
mandate to leave them. **Only Helix is removed** once `ui` ships; the others
cover its niche.

## Backwards Compatibility

Pre-alpha: no compatibility shims. Within this repo, view surfaces gain
`re-frame.ui` as a new option over S6–S7 (examples, testbeds, tools, template,
docs) while the retained adapters keep working. For external apps the
compatibility surface is deliberate: the Reagent, UIx, and reagent-slim adapters
stay first-class and actively supported, keeping their contract suites and
smokes in CI (`reg-view` stays with stock Reagent), and the removal wave touches
**only Helix**, behind the S7 soak gates. Benchmarks against the existing
adapters run **once**, before Helix removal; the results, fixtures, and a git
tag of the removed surface survive. They run once because keeping an adapter
first-class promises continued *support*, not ongoing performance parity — the
baseline is a recorded comparison, not a standing gate.

## Resolved Decisions

- **Program ratification (2026-07-11).** The decision record above — R-1…R-6
  plus the presence, adapters, proof-app, budget, and frame-chain rulings — is
  the ruled decision surface this EP records; the per-finding provenance log is
  retained under the synthesis tree's `reviews/` directory. The adapters ruling
  is superseded on the adapter-disposition point by the 2026-07-17 reframing
  below; all other rulings stand.
- **API freeze (2026-07-12).** The `re-frame.ui` public-surface table
  (`spec/API.md` §"re-frame.ui — blessed public-surface freeze") is blessed
  as-is as the v1 API freeze, governed by the row-level delta protocol; deltas
  #1–#6 (`custom-element`, `->react`, `spread`, `slot` + internal `render-fn`,
  `spread-safe`, `render-static` fn→macro) are ruled and recorded in the table.
- **Conditional S3 advance (2026-07-15).** S3 started in parallel with the
  bounded S2 correction tail, overriding the program's original entry
  condition; S2's truth conditions stood unchanged, and S3 was not permitted to
  weaken or silently absorb the corrections. A throughput ruling, not a proof
  waiver.
- **Component-library readiness (2026-07-16, directed).** re-com is the first
  and most important consumer test; the substrate got ready ahead of the port.
  S3 carried the P0 triad (atomic `local` updater, the `ui/event`
  vector-outcome sync-door arm, internal render slots) plus the safe-spread
  policy, native-library layout/ref blessing, and the docs/slot manifest
  projection — full contract in **EP-0035**.
- **Adapter disposition reframed (2026-07-17).** `re-frame.ui` is a **new,
  experimental view substrate offered as an additional option** — not a
  mandated replacement of the adapter trio and not the only taught view layer.
  Stock **Reagent, UIx, and reagent-slim live on as first-class,
  actively-supported adapters** (not frozen, not scheduled for removal); **only
  Helix is removed** at S7, behind the soak gates. This supersedes the
  ratification's original "frozen compatibility adapters" / "reagent-slim
  removed" shape. The technical interop-boundary contract for legacy Reagent
  embedded in a `ui` host is unchanged — the coexistence mechanism (shared
  React context object, `frame-provider`, HMR-inward, sibling/inward/outward
  granularities) stays valid because Reagent still coexists with `re-frame.ui`;
  only the "frozen tier" labeling is retired in favour of
  "compatibility/interop tier".
- **Template variant menu ruled a supported surface (2026-07-19, delegated).**
  The template's post-Helix variant menu is a supported external-alpha surface,
  not superseded by `re-frame.ui`: `:reagent` (default) and `:uix` stay
  supported variants, W8 adds the experimental `re-frame.ui` scaffold beside
  them (labelled experimental), `:helix` is removed behind the S7 soak gates,
  and reagent-slim remains a reserved future variant on its existing trigger —
  consistent with the 2026-07-17 adapter disposition above. The deferred
  external-alpha gate contract stands on its surviving basis (no emitted
  `day8/re-frame2*` coordinate resolves remotely yet). Whether the menu ever
  narrows later is expressly reserved as a future product decision on its own
  evidence.
- **W4 examples roster — curated five (2026-07-19, delegated).** The examples
  gaining `re-frame.ui` variants at S6 are `realworld_resources` (the full-app
  stage kill-gate) plus the trailing `realworld_http`, `login`, `todomvc`, and
  the routing capability example; no other example gains a `ui` variant at S6.
  Thereafter the standing demand rule applies: a further variant lands only for
  a named consumer (a guide page teaching that surface on `ui`, a
  migration-skill rule needing its worked proof, or an operator-reproduced bug
  needing the fixture) — repo-authored examples never count as independent
  demand. The retained adapters' example coverage is untouched. Recorded in the
  S6 epic's notes (`rf2-vxgfnd.98`).
- **W7b architecture of record (2026-07-19, delegated).** The tools' own UIs
  migrate as `ui`-compiled subtrees exported through `ui/->react` and mounted
  under the installed adapter's host-owned roots — no guest-root machinery;
  W7b sequences behind `->react` landing (S6). Recorded on `rf2-4rwtd` and in
  the S6 epic ruling.
- **Migration ships as an AI skill (2026-07-20).** W1 is the
  Reagent→`re-frame.ui` migration skill (`skills/reagent-migration`), not a
  codemod tool; the skill's rule table and fixture corpus carry the mechanical
  tier and reason through the judgment tiers with the author. W1 absorbed the
  former W2 into this single sibling skill of the existing v1 migration skill
  rather than shipping a second one.
- **ADDENDUM — `ui/->react` shipped ahead of S6 (2026-07-21, rf2-u53yy.2).**
  Delta #2 above records `ui/->react` as *lands S6*; the outward bridge in fact
  shipped early, as one of the ratified UIx-review features (epic `rf2-u53yy`).
  The prior clause is preserved (EP-0009 convention): the nominal stage is still
  S6, but the capability is **live now** — `ui/->react` exports a compiled view
  as a React component for a foreign parent, memoised per view identity, scoping
  a supplied frame without owning it, creating no root/manifest/preflight, under
  one shallow props-conversion rule (children + ref preserved). W7b's dependency
  on `->react` landing (2026-07-19 above) is now satisfied. The `spec/API.md`
  blessed-freeze row and the api-manifest flip planned→live with this ruling.
- **ADDENDUM — W1/W2/W3 adoption clauses trued to current truth (2026-07-20,
  rf2-3qb5w).** The pre-#6560 EP text carried the retired codemod-era adoption
  plan; #6560 corrected it in place. Per the EP-0009 convention (freeze meaning,
  not bytes — rf2-r7ahi) the replaced clauses are preserved here beside their
  current disposition. **Prior text:** §Adoption workstreams named "its sibling
  migration + authoring skills (W2/W6), the docs/guide rewrite (W3)"; §Migration
  posture said the migration skill's "rule table carries the mechanical rewrites
  and flags the judgment-tier call sites for review rather than guessing"; §The
  design-record retirement said "`guide/` moves to `docs/guide` at S6 (W3)"; and
  the 2026-07-20 "Migration ships as an AI skill" ruling said "the skill's rule
  table and fixture corpus carry the mechanical tier and flag the judgment tiers
  for review. W2 lands as a sibling skill of the existing v1 migration skill
  rather than an extension of it." **Current disposition (W numbers preserved,
  no renumber):** W2 is **absorbed into W1** — the Reagent→`re-frame.ui`
  migration ships as the single sibling AI skill `skills/reagent-migration`
  (rf2-3bjvs, #6543); the former W2 second skill (closed rf2-nwgzha) is
  superseded, not redispatched. W3's **wholesale docs rewrite and
  `guide/`→`docs/guide` move are superseded** by the additive
  `docs/core/re-frame.ui` guide already shipped (rf2-b1wv9, #6544); there is no
  `docs/guide` destination and later guide growth is demand-driven (closed
  rf2-3339ri records the plan as overtaken, not relocated). The W1 skill
  **applies the M-tier mechanical rewrites and reasons through the D-tier
  judgment cases with the author — it emits no review flags.**
- **ERRATUM — the whole-build `:build-digest` removed as dead machinery
  (2026-07-21, rf2-u53yy.1 C0).** The pre-C0 spec described a whole-build
  `:build-digest` — a hash over every view (and custom-element declaration) in
  the build, carried into the dev runtime by a `goog.DEBUG`-elided carrier and
  projected, at read, onto Root Descriptor v1 as its `:build-digest` field.
  **Prior clause:** spec/004-Views.md stated that a hook-signature change moves
  `build-digest` "so a stale SSR page's manifest fails digest validation and that
  root takes the loud client-fresh path"; spec/004C §2.1 split Root Descriptor v1
  into a "static core" and a "complete descriptor" assembled by a "read-time
  projection" of the digest on both hosts. **Current disposition:** the digest
  had **no load-bearing consumer** (Codex Finding 2, independently re-verified):
  SSR manifest validation never inspected `:build-digest`, `hydrate-root` read
  only `:root-id`/`:identifier-prefix`, no `=`/compare/stale/fresh/valid gate
  existed anywhere, and no tool gated on it — the "stale SSR manifest fails digest
  validation" clause was a promise with **no implementation**. C0 therefore
  **deletes** the digest carrier, its build-hook projection, the descriptor
  `:build-digest` stamping, and the digest-specific gate scripts and real-Shadow
  probe fixtures (a net deletion, no replacement). Root Descriptor v1 is now
  **per-root static facts only** — no whole-build aggregate, no read-time
  projection — and spec/004-Views.md, spec/004C §2/§2.1, and spec/004B are
  reconciled to that truth. The install/cache contract and its tax lines are
  unchanged here; they are the subject of the later programme slices (S1–S6).
- **ADDENDUM — the 2026-07-21 S7-entry/alpha ruling (recorded 2026-07-22,
  rf2-ae6n9).** The source of record is the DECISION note on the S7 stage epic
  (`rf2-vxgfnd.99`, 2026-07-21 17:20 AUSEST — Fable, delegated by Mike), as
  amended by the ENDORSEMENT disposition on the same note (2026-07-21 19:35
  AUSEST). This addendum records that ruling; it does not re-decide it. Per the
  EP-0009 convention the prior clauses — the §Stages S6/S7 rows and this EP's
  other "soak gates", "every gate green", and W11-timing phrases — are
  preserved verbatim and now read through five dispositions. **(1) The S6
  proof / S7 entry is narrowed.** Quoting the ruling: "This epic's ENTRY-line
  term 'S6 proof (RealWorld+Story+Xray green on re-frame.ui)' is REDEFINED as
  the S7-ENTRY PROOF, exactly: (P-1, kill-gate)
  examples/real-apps/realworld_resources ships a COMPLETE re-frame.ui full-app
  variant: the app boots a re-frame.ui root end-to-end (not the existing
  Reagent root in core.cljs/views.cljs), covering routing and meaningful
  edit/write paths. […] Bead: rf2-nn5s8. (P-2) Story and Xray green AGAINST
  that app as CONSUMERS (not tool-UI rewrites): re-frame.ui in Story's
  substrate roster with a realworld-ui deck playing through the existing shell
  (presence bridge […] exercised end-to-end), and Xray attached to the running
  ui app rendering true ViewCell evidence […]. Bead: rf2-vvdzx." The S6 row's
  "RealWorld-resources + Story + Xray green is the proof" is therefore
  satisfied by the complete `realworld_resources` `re-frame.ui` full-app
  variant (`rf2-nn5s8`) plus Story and Xray green against that app as
  consumers — the substrate-roster entry and the presence-bridge and
  ViewCell-evidence seams exercised end-to-end (`rf2-vvdzx`) — not by
  rewriting the tools' own UIs. **(2) W7b and the rest of the W4 roster move
  post-alpha, demand-driven.** The 2026-07-19 "curated five" bullet above
  narrows: `realworld_resources` is the only example gating the stage;
  `login`, `todomvc`, the routing capability example, and the trailing
  `realworld_http` fall under the standing demand rule (a named consumer earns
  the variant; repo-authored examples never count). W7b tool-shell migration
  is likewise demand-driven post-alpha, and the 2026-07-19 W7b architecture of
  record above (`rf2-4rwtd` — `ui`-compiled subtrees exported through
  `ui/->react` under host-owned adapter roots, no guest-root machinery) stands
  unchanged for whenever it runs. **(3) The alpha tag condition is an explicit
  exception contract.** Not the S7 row's "every gate green", and deliberately
  not the dynamic "every CI-wired gate green" — a wording under which an
  accidentally-unwired required check silently becomes optional and wiring
  changes silently rewrite the release contract. The contract: **G-2**
  (AOT-peer performance parity), **G-9** (keyed 1k-row list performance), and
  **G-10** (bundle-size budgets against the checked-in baseline EDN) — the
  [EP-0034 §4 roster](EP-0034-re-frame-ui-production-ssr-testing.md#4-the-gate-roster-one-line-per-gate)
  rows — are **deferred** until a future default/optimized claim returns; they
  are that claim's evidence bar (the claim itself was withdrawn by the
  2026-07-17 reframing above). W11's one-time trio comparison folds into
  G-2/G-10 whenever they run — no standalone benchmark harness now; the
  "before Helix removal" timing in §Adoption workstreams and §Backwards
  Compatibility lapses with it. **G-14**'s guide-fixtures arm is retired with
  its superseded subject (the wholesale guide-fixture programme gave way to
  the additive, demand-driven guide); its two wired arms — `defview` expansion
  p95 and the watch-rebuild delta — stand. **Every other alpha obligation
  named by the post-addendum contract must be green at tag time.** Stated
  plainly: the alpha carries **no absolute bundle-size (G-10) claim**, and
  `test:perf-bundle` is not size protection — it is a Performance-API sentinel
  elision/presence check invoked by no workflow; the workflow-wired bundle
  gates are `test:elision` and `test:bundle-isolation` (expensive-tests). This
  addendum is effectively pre-tag: EP-0030 tells the narrowed-entry truth
  before the tag ships. **(4) The soak-gate interpretation was pinned — and
  then waived.** As pinned: Leg A (objective) = two consecutive green
  **scheduled** runs of the expensive-tests workflow, both postdating the
  merge of the last of {P-1, P-2, rf2-u53yy.1} — soak the shape you ship; Leg
  B (judgment) = seven consecutive calendar days of ordinary repo work during
  which no merged PR reverts, disables, or migrates off a `re-frame.ui`
  programme surface and nothing reintroduces a Helix dependency, starting no
  earlier than the first green scheduled nightly after the nightly repair; the
  legs may run concurrently (with each other and with rf2-u53yy.1), and an
  unrelated red nightly does not reset Leg B. Subsequently **waived** (Mike,
  2026-07-21 21:23 AUSEST): "We don't need a soak clock." S7 entry therefore
  reduced to the two proofs merged; the tag remains Mike's one reserved manual
  act. **(5) Stage truth.** **Prior text:** §Stages read "S6 is the live
  stage." **Current disposition:** quoting the ruling, "rf2-vxgfnd.98 stays
  closed as an administrative fact […]; the operative gate moves" to the
  narrowed S7 entry above — and both entry proofs have since merged, P-1
  `rf2-nn5s8` (#6648) and P-2 `rf2-vvdzx` (#6651, 2026-07-21) — so S7 is the
  live stage. The §Stages sentence is corrected in place alongside this
  addendum.

## Open Issues

Graduation only: this EP stays `accepted` while the stages land and moves to
`final` when S7 completes — every gate green, the demand-bar prune done, Helix
removed behind the soak gates, and the retained adapters' continued-support
posture recorded in their spec homes.

## References

- **Sibling EPs (authored with this one; each a narrow decision surface):**
  [EP-0031](EP-0031-re-frame-ui-programming-model.md) — programming model
  (`defview`, templates, the handler law, interop); **presence is not
  EP-0031's** — its normative contract is
  [`spec/004-Views.md` §Presence](../../spec/004-Views.md#presence--declarative-enterexit),
  and the companion ruling (`ui/presence` wrapper, no reserved nodes, mandatory
  timeout safety bound) is recorded above in this EP;
  [EP-0032](EP-0032-re-frame-ui-reactivity-and-ownership.md) — reactivity and
  ownership (observation targets, ViewCell, commit protocol, frames/preflight
  ENSURE, HMR); [EP-0033](EP-0033-re-frame-ui-view-evidence.md) — view evidence
  and debugging; [EP-0034](EP-0034-re-frame-ui-production-ssr-testing.md) —
  production, SSR, and testing posture;
  [EP-0035](EP-0035-component-library-readiness.md) — component-library
  readiness (the directed S3 amendments and the re-com consumer).
- **[EP-0014](EP-0014-derivation-and-process-algebra.md)** names the
  derivation/process algebra; the substrate reads through that one reactive
  grammar and adds no second one.
- **[EP-0029](EP-0029-xstate-v6-machine-parity.md)** is the format precedent for
  this EP family and keeps machines ordinary projections the view layer merely
  reads.
- **[EP-0002](EP-0002-frame-target-resolution.md) /
  [EP-0024](EP-0024-unified-frame-identity-and-lifecycle.md)** own explicit
  frame resolution and unified frame identity — the substrate's carried-frame
  law and preflight ENSURE build directly on them.
- **Specs:** the Spec 004 family ([`spec/004-Views.md`](../../spec/004-Views.md),
  [`spec/004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md),
  [`spec/004C-Roots-and-Mount.md`](../../spec/004C-Roots-and-Mount.md)),
  [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md)
  (observation port; adapter contract),
  [`spec/008-Testing.md`](../../spec/008-Testing.md) (the `ui.test` contract
  home), [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md)
  (evidence and catalogue rows), [`spec/011-SSR.md`](../../spec/011-SSR.md)
  (SSR roots and hydration), and [`spec/API.md`](../../spec/API.md) (the blessed
  public-surface freeze); conformance profiles under `spec/conformance/`.
- **Design provenance:** the synthesis dossier at
  `ai/findings/new-substrate-synthesis/` (tombstoned + untracked at S4; see §The
  design-record retirement), with `reviews/` and `spikes/` archived in Git
  history at `41375ba940` (restore via `git show 41375ba940:<path>`).

# EP-0034: re-frame.ui Production, SSR, and Testing Posture

Status: accepted
Type: standards-track
Created: 2026-07-16
Resolution: accepted 2026-07-11; component-library gates added 2026-07-16

> **Addendum — 2026-07-21 (rf2-n7jtp).** The `ui.test` surface was minimized to
> six names — `render` / `attrs` / `text` on the JVM structural host, and
> `with-root` / `flush!` / `flush-presence!` on the CLJS mounted host.
> `ui.test/find`, `find-all`, `query`, `dispatch!`, render's `:frame`/`:props`
> options, and the **004D selector grammar** (`spec/004D-UI-Test-Selectors.md`,
> now retired) are deleted: traversal is ordinary Clojure
> (`(tree-seq map? :children tree)` + a `:tag`/`:view-id` predicate), state is
> driven with `rf/dispatch-sync` under `rf/with-new-frame` / `rf/with-frame`, and
> a mounted container is queried with native `.querySelector`. The `ui.test`
> contract's home is [`spec/008-Testing.md`](../../spec/008-Testing.md). Text
> below that names the retired verbs or 004D is historical.

## Abstract

This EP decides **how the `re-frame.ui` substrate proves itself**. The
substrate's product claims are not "faster React" — they are **absence,
capability specialization, proven debug erasure, one honest server tree, and a
testing surface that runs on the JVM in milliseconds**. The EP fixes the proof
discipline: budgets are hypotheses with kill-gates (a missed budget means
optimize or prune the feature — never weaken ownership or hydration semantics
to make a number); absence is verified by bundle scan, never assumed from
tree-shaking; SSR parity is normalized structural equivalence, never
byte-identical HTML; every claim is owned by exactly one named gate in the
G-1..G-18 roster.

The normative homes are the Spec 004 rewrite's production/capability sections,
[`spec/004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md)
(live), [`spec/008-Testing.md`](../../spec/008-Testing.md) (the `ui.test`
contract's eventual home), and [`spec/011-SSR.md`](../../spec/011-SSR.md) (root
manifests + hydration, S5). Each stage's spec edits merge **atomically with
that stage's conformance slice**; per EP-0009, where this EP and the spec
differ, the spec governs.

## Motivation

A compiled view substrate makes strong claims that are cheap to state and easy
to rot: "no hiccup interpreter ships", "the server renders what the browser
renders", "debug machinery is erased from production". Without a decision
record each degrades into faith — tree-shaking faith, jsdom faith,
best-run-benchmark faith. The unresolved alternatives were real: byte-identical
HTML vs structural equivalence; trusting `:advanced` elision vs scanning for
exact absence; a second server product vs one JVM tree feeding the existing SSR
artifact; a gesture-DSL browser story vs a headless-first pyramid; best-run
estimators vs noise-robust ones (the codegen spike hit that trap concretely).
One EP fixes these postures together because they share one enforcement
mechanism — the gate roster and its stage-wiring rule.

## Specification

**Scope.** This EP fixes the production posture (capability specialization, the
absence roster, packaging, budgets-as-kill-gates, the scheduling stance); the
SSR posture (one analyzer, one emitter per build; the honest JVM subset; roots
vs frames; root manifests, per-root hydration and failure isolation; explicit
static-root policy; the `client-only` phase flip); the testing posture (the
five-tier pyramid, the `ui.test` contract, Tier-3 fixtures, scoped generative
parity); and the gate roster G-1..G-18, each gate with one owner and a wiring
stage. It does not own the programming model, the reactivity/ownership
protocol, or the debugging evidence layers (sibling EPs — see References);
pre-hydration event replay/resumability stays research-tier post-alpha with
hard graduation criteria; RSC is out (the JVM SSR path is the canonical server
story); streaming sugar is out (the tree emitter is chunkable; policy stays
host-side per Spec 011); there is no dev-bundle size budget (the production
claim about debug machinery is *absence*); and there is no ongoing
performance-parity promise for the retained Reagent/UIx/reagent-slim adapters
(the comparison is a one-time S6 table, then an archive).

### 1. Production: specialization and the absence roster

Production components carry exactly the machinery their capability bits imply —
the closed vocabulary (`sub · local · effect · event-sites ·
frame-scope · presence · error-boundary · portal · client-only · trusted-html ·
custom-element · foreign-react · dynamic-view · ui-event/handler · debug-site`)
covers every feature that changes generated code, hydration, ownership, or
server behavior. The honest costs are stated: an event-only view still pays a
small commit step; "no handler allocation" is scoped to the compiled vector
path; "no wrapper components" is scoped to incidental wrappers; memo suppresses
*prop-driven* repaints only.

**The absence roster is bundle-scan-verified, never tree-shaking faith:** no
hiccup walker, tag parser, generic camelizer, sequence flattener, form
detection, second scheduler, per-sub hook scaffolding, compatibility stubs,
source-coord walkers; no manifests, cause vectors, histories, timings, warning
text, `data-rf2-*` strings, project paths; no schema engines when elided; **no
JVM renderer in any browser entry**; no proxy/signal/atom/query-cache runtimes.
Debug erasure is a proof: advanced builds are scanned for exact absence (G-11),
and dev/prod behavioral equivalence is gated in two layers (G-7): Layer 1 gates
the GENERATED STRUCTURAL output per generated shape (dev-CLJS == JVM-truth ==
advanced-CLJS, the parity corpus rendered in both regimes against one embedded
JVM truth), and Layer 2 gates per-production-wrapper MOUNTED behaviour through
causal fixtures — no powersets, no pairwise-capability generator. Ordinary view registration is **already `goog.DEBUG`-gated in the
emitter** (the registration branch of `compiler/emit_cljs.cljc` — the
production arm is a direct `React.memo` with no registry); G-18 is
fixture-first, and a new elision mode or packaging change is justified only if
that fixture fails structurally.

Packaging: the UI source artifact is `.cljc` (compiler + both emitters as
source); the browser build never reaches JVM-renderer namespaces (scan-gated);
`re-frame2-ssr` consumes the JVM emitter — no second server product.
Dependencies: re-frame2 core + patched React/React DOM 19.2.4+ peers; no
Reagent/UIx/Helix/slim anywhere in the graph (G-12, Maven/npm level). Budgets
are kill-gate hypotheses: kernel ≤ 4 KB gz over React; counter ≤ React + 6 KB
gz; relative targets vs UIx-adapter and reagent-slim baselines with
symbol-reachability evidence when chunk subtraction is noisy. Scheduling: no
`startTransition`, no per-sub priorities, no second render queue — exact work
reduction plus the one sanctioned synchronous door for controlled inputs;
`flush-render!` is for deterministic tooling, not applications.

### 2. SSR: one JVM structural tree

`defview` is `.cljc`: each host build runs the shared analyzer and hands that
build's own AST to exactly one emitter — CLJS → direct JSX; JVM → the canonical
serializable render tree consumed by `re-frame2-ssr`. The hosts never meet as
ASTs. One contextual conversion/escaping rule table serves both emitters, and
parity **detects** divergence between them rather than preventing it. **Parity
is normalized structural equivalence over semantic nodes** (tag/ns, attr
names+values, child order, escaping, keyed order, void/boolean, fragments,
fallbacks), fingerprinted and generatively tested — **byte-identical HTML is
not the contract**, consistent with Spec 011's canonical hydration-equivalence
rule. The JVM subset is honest and closed: structure, props, subs, branches,
lists, event intent, and `ui/html` carry full semantics; `local` contributes
its initial value (invoking the setter is a typed error); `effect` is recorded,
not run; refs are absent; `portal`/`client-only` render explicit deterministic
fallbacks; `error-boundary` is server failure policy per Spec 011; `presence`
renders `:present`.

**A React hydration root is not a re-frame2 frame** — roots (DOM/render units)
and frames (state worlds) are distinct, many-to-many identities; "island" is
not vocabulary. Each independently hydratable unit ships a root manifest (root
id, element locator, view id, props, frame-payload ids, render fingerprint,
identifier prefix, phase); mount position is never identity; duplicate root
ids are a build error; frame payloads install idempotently and
order-independently. Hydration per root: **preflight** — manifest
discovery/validation, then the idempotent payload-install decision (the
content digest exists to reject a conflicting second install,
`:rf.error/frame-payload-conflict`; no manifest-fingerprint verification
channel exists on this tier — the structural render-hash belongs to the hiccup
tier, and a compiled-tier fingerprint check is a deliberately deferred future
leaf) → `hydrate-root` adopts the server DOM against the root's
`:server`-phase render → one root **phase flip** swaps `client-only` fallbacks
in a single update → first connected commits acquire ownership. Hydration
verification is **React-native adoption**: a divergence React recovers from —
a text-content mismatch, or a missing, extra, or wrong-type element — surfaces
through the root's `onRecoverableError` as the `:rf.ssr/hydration-mismatch`
diagnostic while React patches the DOM. A mismatch never fails the root, the
compiled tier has no `:hard-error` escalation, and attribute-only mismatches
are by design not detected (Spec 011 owns the boundary). Root *failure* is the
distinct case: a root that throws while booting (preflight → install → hydrate
→ the host's mount) is contained by failed-root isolation — a bad payload
affects exactly its referencing roots, and sibling roots hydrate and stay
interactive. Static roots are explicit policy — compiler proof of no client
capability **and** a host declaration; "no subs, no handlers" never silently
strips a runtime. Event vectors are retained as data in the manifest and the
JVM tree; pre-hydration replay stays research-tier (see Scope).

### 3. Testing: the pyramid and `ui.test`

Five tiers: **1** headless view tests (structural tree + event-vector intent;
JVM and node; milliseconds, no DOM) · **2** pure dataflow (unchanged re-frame2)
· **3** mounted contract fixtures against real React · **4** Story variants
(CLJS-unit-test shape per repo ruling) · **5** the gates. One namespace,
`re-frame.ui.test`: `render` (real view against a real frame on the JVM →
structural tree; `{:sub-overrides …}` is the explicit JVM override door, not
pretended to be the CLJS context mechanism), `find`/`find-all` (Tier-1
structural queries under the **closed 004D grammar** — tag keyword, view-id/Var,
attr-map matched by `rf=`, predicate escape; no positional combinators; a
vector selector raises `:rf.error/ui-test-bad-selector` naming the
composed-`find` idiom), `query` (the Tier-3 live-DOM counterpart),
`text`/`attrs`, `dispatch!` (real dispatch + drain), `with-root` (CLJS Promise;
awaited mount, body, and total teardown), and `flush!` — the sole public test
flush: on CLJS a Promise running the optional thunk inside React 19 `act`,
letting the update and commit phases reach **drain quiescence**, then
alternating framework drains and React commits to a fixed point; on the JVM a
synchronous drain of the headless ViewCell registry. The open-drain guard
throws `:rf.error/flush-in-open-epoch`; a forgotten await fails loudly with
`:rf.error/ui-test-overlapping-act`; there is no production `ui/flush!`. There
is deliberately **no frame constructor in `ui.test`** (ruled): one
initialization grammar — `rf/make-frame` + `:initial-events` with `[:rf/set-db
…]`. Tier 1 requires the events/subs a view touches to be `.cljc` — a taught
authoring constraint. JVM semantics under test follow the SSR subset; setter or
effect use in Tier 1 is a typed error pointing at Tier 3. The Tier-3 fixture
matrix turns the ownership walkthrough into tests (abandoned mounts, commit-gap
correction, StrictMode/Activity, frame swap, hydration races, HMR, presence,
the sync door, the interop set, Promise-boundary semantics).

**Generative parity is scoped:** props schemas generate props — they cannot
generate an app-db satisfying arbitrary subs, so apps supply state
generators/fixtures (a one-liner around `rf/make-frame`). With inputs supplied:
JVM tree vs CLJS server-equivalent compare as normalized semantic nodes; memo
invariants (`rf=` props ⇒ no prop-driven re-render, identical output);
value-stabilization invariants (equal results ⇒ identical references).
Fingerprints pin build identity; the corpus doubles as the hydration-parity
suite, including multi-root failure isolation.

### 4. The gate roster (one line per gate)

This table is the roster of record — G-1..G-18 as ruled, including the
component-library additions (EP-0035). Where any earlier roster differs, this
table governs.

| Gate | Asserts |
|---|---|
| **G-1** direct-render parity | pure view within 10% of hand-written JSX CLJS (p50/p95) under the noise-robust estimator, plus an emitted-JS golden test pinning direct `jsx` calls (the IFn-dispatch trap) |
| **G-2** AOT peer | ≥ UIx-AOT parity on pure views; reactive one-read ≤ 15% update-p95 over raw correct `useSyncExternalStore` |
| **G-3** multi-read scaling | one store listener and one body invocation per ViewCell; independent lexical-site handles; at most one notification per dirty cell; queued writes settle before one read/render batch at the following host checkpoint |
| **G-4** equality no-op | `rf=` results ⇒ zero revisions, zero prop/sub-driven renders, stable references |
| **G-5** drain fan-in | eight queued update+commit epochs all execute in one run-to-completion drain and share exactly **one** read/render batch at the following host checkpoint — a drain is never split across batches; coalescing never drops writes; epoch count is never evidence of render/commit count |
| **G-6** abandonment/disposal | 10k headless abandoned renders/cold probes and bounded mounted StrictMode/Activity cycles return every ownership surface to exact baseline |
| **G-7** dev/prod equivalence | two layers: **(1)** generated structural output equivalent per generated shape — dev-CLJS / JVM-truth / advanced-CLJS agree, debug off; **(2)** per-production-wrapper mounted behaviour — committed DOM/events/owners/cleanup/hydration agree, debug off |
| **G-8** input correctness (hard) and latency evidence | **Hard gate:** in real Chromium **and** WebKit — pre-paint synchronous commit under the sync door, ordering, caret restoration, IME composition, exactly one attributable React commit per ordinary input and exactly one at the committed IME boundary, with the deliberate async-door regression as the tooth; the matrix runs through a **reusable event-prefix component** (`ui/event` vector-outcome door) — toy literal fixtures alone do not close it. **Evidence only:** event→commit p95 against an equivalent hand-written React control in the same warmed run — the ratio, the 10% reference-budget observation, the sample count and the noise policy are recorded and reported; no wall-clock number gates (G-13's posture), and an over-budget observation feeds performance follow-up, never a G-8 failure |
| **G-9** list updates | keyed 1k rows: one entity change renders its row + true dependents; stable handler identity; no retained lazy seqs |
| **G-10** bundle | kernel ≤ 4 KB gz; counter ≤ React + 6 KB gz; relative targets vs UIx-adapter/slim with symbol-reachability escalation (methodology: pinned three-module splits, per-module measurement, N=3 determinism, checked-in EDN baseline, ratio + floor + slope guard) |
| **G-11** elision | exact absence of the debug + absence rosters, including `re-frame.ui.test` and its React-`act` boundary, from advanced production bundles |
| **G-12** dependency isolation | no Reagent/UIx/Helix/slim at Maven/npm; no JVM renderer reachable from browser entries |
| **G-13** push falsification | C affected cells fixed while mounted V varies (100→500), plus a multi-write/single-drain arm: exactly C enroll/advances, C body renders, one commit batch per drain; failure **reopens the design**, never forks it |
| **G-14** compile budget | `defview` expansion p95; watch-loop rebuild delta on the dashboard fixture; guide-fixtures CI cost bounded |
| **G-15** atomic-local writer matrix | N same-turn host writers through `update!` all land (key+pointer, timer+listener, observer+handler arms); fn-value `set!` stores exactly; mixed `update!`+dispatch; StrictMode replay; JVM typed failure; writer composition + render count (not a `:local-state` cause row — that cause is specified for S6); HMR ride |
| **G-16** render-slot parity | slotted output normalized-structurally equivalent across both emitters; keyed reorder under slots; purity diagnostics fire inside slot bodies; manifest slot sites present |
| **G-17** safe-spread ownership | the policy form rejects owned keys (`:key` `:ref` `:value` `:checked` owned `:on-*`) in dev **and** advanced builds; `aria-*`/`data-*` pass; the policy form retains the sync door where general `spread` forfeits it |
| **G-18** library façade isolation | an advanced build importing one view from a multi-view library namespace retains no unused sibling views (fixture-first; see §1) |

Methodology, binding on every gate: identical fixtures, distributions not best
runs, pinned browsers, cold+warm, dev overhead separate, no precomputed-props
cheating. The component-library **proof pack**'s six library views (controlled
input, selection controller, slotted list cell, safe-attrs form control,
schema-described component, inline popover) are rendered and exercised by a
mounted DOM suite (`re-frame.ui.proof-pack.library-dom-cljs-test`) — their
host-bearing views have no JVM side and cannot be dual-emitter parity-corpus
rows. The separate single-view import consumer
(`re-frame.ui.proof-pack.single-view`) roots exactly one of those views for the
advanced reachability scan that proves G-18 sibling isolation; it is not part of
the mounted suite. The substrate takes no build dependency on re-com.

### 5. Gate wiring status

Every stage wires its own gates into CI in that stage, never later; a
feasibility PASS never silently closes a named-open gate. S1–S4 are complete
and S5's surfaces are shipped and proven (the formal S5 conforming declaration
is pending); the stages ahead follow
[EP-0030 §Stages S1–S7](EP-0030-the-compiled-view-substrate-program.md#stages-s1s7).

**Wired and green:**

- **G-1** (`npm run test:ui-g1`, under the noise-robust estimator, with the
  emitted-JS golden and the absence scan it carries).
- **Two of G-14's three arms**, in the JVM suite: `defview` expansion p95
  against a 50 ms pathology bar over three fixture sizes, and the watch-loop
  rebuild delta over an 11-view dashboard-shaped roster against a derived
  absolute bar (K × the per-view pathology bar). The rebuild arm proves the
  measured pass actually rebuilt the roster — K realisations, K **distinct**
  template fingerprints, all forced inside the timed span — before any duration
  is compared, so a shrunken, collapsed, or lazily-deferred pass cannot report
  "cheap". Its limit is stated rather than implied: at this roster size the
  absolute bar catches a runaway rebuild but does **not** separate a strictly
  quadratic pass, which needs a namespace-scale roster or a scaling ratio
  across two roster sizes (the suite carries the arithmetic).
- **The S2 family G-3/G-4/G-5/G-6/G-13**, landed with the S2 slice over the
  real sub-cache.
- **G-8**, including its widened event-prefix arm (`npm run test:ui-g8`, in
  real Chromium **and** WebKit).
- **G-11** (`npm run test:browser-prod-elision`, plus the absence scan G-1
  carries).
- **G-15**, the atomic-local writer matrix (`npm run test:browser`).
- **G-12** (`npm run test:ui-isolation`, which fails closed unless both arms
  run).
- **G-18** (`npm run test:ui-facade-isolation`, a standing required CI job);
  its roster of proven views is derived from the proof-pack library source, so
  a new library view either arms the gate or fails it.
- **G-16**, render-slot parity, certified with the S3 slice: cross-emitter
  structural parity, keyed reorder under slots, and purity diagnostics inside
  slot bodies all run, including the **manifest slot-site arm** — the
  analyzer indexes each slot site into the compiler manifest and the public
  `:render-slots` projection reports it, both pinned by focused acceptance.
- **G-7 Layer 1**, the generated-structural-output equivalence arm
  (`npm run test:browser-prod-elision`, the advanced parity-corpus prod-elision
  proof). The production-absence arm is green, and the corpus renders
  through the **advanced** CLJS emitter and compares — in the same
  normalized-semantic space the dev-lane parity corpus uses — against the JVM
  emitter's compile-time truth. Paired with the dev-lane corpus
  (`parity_corpus_cljs_test`) this closes the triangle: dev-CLJS == JVM-truth
  **and** advanced-CLJS == JVM-truth, so dev == prod per generated shape across
  all 43 corpus cases, with no golden files.
- **The S5 root-hydration family** — Root Manifest v1 with positional
  discovery, hydration preflight + idempotent payload adoption, failed-root
  isolation, the `emit-ui-tree` serialiser, the root-scoped `client-only`
  phase flip, and `render-static` — proven by the focused JVM/node/browser
  suites named in the S5 conformance profile
  (`spec/conformance/S5-view-conformance-profile.md`), riding the existing
  required CI jobs; the profile's formal conforming declaration is pending.

**Named open:**

- **G-7 Layer 2**, the shape-specialization arm: per-production-wrapper mounted
  causal fixtures (gaps: presence retention/removal, custom-element property
  application, the missing event/combination wrapper shapes) — owner
  **`rf2-55zsd`** (the S6 leaf under `rf2-vxgfnd.98`). Layer 1 above proves the
  generated structure equivalent per shape; Layer 2 proves the mounted
  behaviour each distinct production wrapper adds. Not a powerset, not a
  pairwise generator — one causal fixture per distinct wrapper shape.
- **G-14's remaining arm** — bounded guide-fixtures CI cost, which needs the
  guide-examples corpus and has no assertion anywhere in the repository, as the
  gate's own suite states.
- **G-2/G-9** — wire with the stages shipping their subjects.
- **G-10** and the remaining absence/equivalence/budget gates, plus the
  one-time W11 trio table (S6).

Per the 2026-07-21 S7-entry/alpha ruling recorded in
[EP-0030 §Resolved Decisions](EP-0030-the-compiled-view-substrate-program.md#resolved-decisions),
G-2, G-9, and G-10 do not gate the alpha: they are deferred as the evidence bar
for any future default/optimized claim, with W11's one-time trio comparison
folding into G-2/G-10 whenever they run.

### Guide impact

Guide 08 (SSR/hydration), 09 (testing/debugging), and 10 (performance) teach
these postures. The guide-fixtures corpus is itself a test surface — every
example compiles and runs as a fixture; one needing internals explained is an
API defect.

## Rationale

One roster, one owner per claim: conflating axes lets one excuse the other
(bundle bytes vs runtime speed; correctness vs latency), so G-10 refuses
runtime questions and W11 refuses correctness ones. Structural equivalence over
byte-identical HTML follows Spec 011's existing lock — serializers legitimately
differ; the contract is the computed view. Headless-first testing follows from
the design's three structural facts (views are pure AST→output functions;
handlers are data; ownership begins only at commit) — the browser is reserved
for what only the browser can prove. G-13's framing matters most: it exists to
*falsify* the committed push economics, and failure reopens the design rather
than toggling a fork — the roster is honesty, not marketing. Two disciplines
give the roster its value: budgets stay kill-gates (miss ⇒ optimize or prune,
never weaken semantics), and gate status stays explicit (a feasibility PASS
never silently closes a named-open gate).

## Backwards Compatibility

Pre-alpha; no back-compat shims. The legacy shared-adapter parity matrix
collapses into **four** named causal suites (W9): new-UI conformance + smoke,
and Reagent, UIx, and reagent-slim compatibility suites plus one smoke each;
only the Helix arm retires, at S7. The retained adapters keep a pinned
correctness contract — a distinct compiled substrate makes no automatic parity
promise. Spec 008's adapter-era testing surface (`flush-views!`,
`re-frame.test-helpers`) keeps governing the compatibility tier until the
`ui.test` contract graduates into 008.

## Resolved Decisions

- **Head ownership
  ([004D §The document head is host-owned](../../spec/004D-Freehand-Compiled-Grammar.md#the-document-head-is-host-owned)).**
  re-frame2's existing head model is the one owner of document-head output;
  substrate views never hoist metadata through React's head/resource
  mechanisms, and a foreign head is an explicit interop boundary. S4 hardened
  head policy; the ruling itself is settled here.
- **G-1 estimator (ruled).** The gate uses a noise-robust estimator —
  alternating interleaved rounds, median-of-rounds — because µs-scale p95 is
  environment-dominated (spike-measured). Companion ruling: an **emitted-JS
  golden test** pins direct `jsx` calls — a CLJS-var-bound jsx fn silently
  reintroduces IFn dispatch under `:advanced` (~5–8%, the spike's trap).
- **Byte-identical HTML is NOT the contract (ruled).** Dual-emitter parity is
  normalized structural equivalence over semantic nodes, fingerprinted and
  generatively tested — consistent with Spec 011's canonical
  hydration-equivalence rule.
- **Host-checkpoint render batching is the G-5/G-13 subject (ruled).** Every
  queued epoch executes; the batch closes at the host's next microtask
  checkpoint (or an explicit test flush), never at drain finalization — so a
  drain's epochs always share one read/render batch, and drains reaching the
  same checkpoint may share one. Epoch count alone is never evidence for
  render or commit count. G-13 falsifies the committed economics; failure
  reopens the design.
- **Component-library gates addition (2026-07-16, directed).** G-15..G-18, the
  widened G-8 arm, and the proof pack join the roster per the re-com readiness
  package (EP-0035); each wired with its S3 feature under the
  every-stage-wires-its-gates rule. G-18 is fixture-first — the registration
  branch is already `goog.DEBUG`-gated.

## Open Issues

The named-open gates in §5 are the open surface; each has one owner and a
wiring stage. Graduation: this EP stays `accepted` and graduates stage by stage
under the atomic spec-landing rule — `spec/011` receives its root-manifest and
hydration sections at S5, `spec/008` receives the `ui.test` contract with its
stage, and the remaining budget/absence gates complete at S6.

## References

- [EP-0030](EP-0030-the-compiled-view-substrate-program.md) — the program
  umbrella (stages, demand bar, adapter disposition); stage wiring and the
  one-time S6 adapter comparison follow its schedule.
- [EP-0031](EP-0031-re-frame-ui-programming-model.md) — the programming model
  (compiled `defview`, event-vector handlers, capability grammar): 0031
  promises, 0034 verifies.
- [EP-0032](EP-0032-re-frame-ui-reactivity-and-ownership.md) — reactivity and
  ownership (observation port, ViewCell, commit algorithm); G-3/4/5/6/13 gate
  its economics here.
- [EP-0033](EP-0033-re-frame-ui-view-evidence.md) — view evidence and
  debugging; its erasure obligation is enforced here (G-7/G-11 and the debug
  roster).
- [EP-0035](EP-0035-component-library-readiness.md) — component-library
  (re-com) readiness; G-15..G-18, the widened G-8 arm, and the proof pack
  originate there.
- Specs [`008-Testing.md`](../../spec/008-Testing.md),
  [`011-SSR.md`](../../spec/011-SSR.md), and
  [`004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md) —
  the graduated homes named in the Abstract; 004B is live now, 008 and 011
  receive their sections with their stages. (The 004D selector grammar named in
  the original Abstract was retired by rf2-n7jtp; see the Addendum above.)
- Methodology provenance: the synthesis drafts
  `g10-bundle-baseline-methodology.md` and
  `w11-runtime-benchmark-methodology.md` (under the tombstoned synthesis
  tree's `drafts/`) write down the G-10/W11 methodologies; their gates bind at
  S6.

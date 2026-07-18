# EP-0034: re-frame.ui Production, SSR, and Testing Posture

Status: accepted
Type: standards-track

> This EP decides **how the `re-frame.ui` substrate proves itself**: the production
> posture (capability specialization + a bundle-scan-verified absence roster), the SSR
> posture (one JVM structural tree consumed by the existing `re-frame2-ssr` artifact;
> roots/manifests/hydration at S5), the testing posture (the `ui.test` pyramid,
> `flush!` semantics, the 004D selector grammar, generative parity), and the gate
> roster **G-1..G-18** that makes every claim falsifiable. Normative homes on
> graduation: the Spec 004 rewrite's production/capability sections,
> [`spec/004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md)
> and [`spec/004D-UI-Test-Selectors.md`](../../spec/004D-UI-Test-Selectors.md) (both
> already live — promoted with Stage 1), [`spec/008-Testing.md`](../../spec/008-Testing.md)
> (the `ui.test` contract's eventual home), and [`spec/011-SSR.md`](../../spec/011-SSR.md)
> (root manifests + hydration, S5). Each stage's spec edits merge **atomically with
> that stage's conformance slice**. Ruled 2026-07-11; amended 2026-07-16 by the
> directed component-library gates addition (see Resolved Decisions and EP-0035).

## Abstract

The substrate's product claims are not "faster React" — they are **absence, capability
specialization, proven debug erasure, one honest server tree, and a testing surface
that runs on the JVM in milliseconds**. This EP fixes the proof discipline: budgets
are hypotheses with kill-gates (a missed budget means optimize or prune the feature —
never weaken ownership or hydration semantics to make a number); absence is verified
by bundle scan, never assumed from tree-shaking; SSR parity is normalized structural
equivalence, never byte-identical HTML; every claim is owned by exactly one named gate
in the G-1..G-18 roster.

## Motivation

A compiled view substrate makes strong claims that are cheap to state and easy to rot:
"no hiccup interpreter ships", "the server renders what the browser renders", "debug
machinery is erased from production". Without a decision record each degrades into
faith — tree-shaking faith, jsdom faith, best-run-benchmark faith. The unresolved
alternatives were real: byte-identical HTML vs structural equivalence; trusting
`:advanced` elision vs scanning for exact absence; a second server product vs one JVM
tree feeding the existing SSR artifact; a gesture-DSL browser story vs a headless-first
pyramid; best-run estimators vs noise-robust ones (S-1 hit that trap concretely). One
EP fixes these postures together because they share one enforcement mechanism — the
gate roster and its stage-wiring rule.

## Goals / Non-Goals

**Goals:**

- fix the production posture: capability specialization, the absence roster, packaging,
  budgets-as-kill-gates, the scheduling stance;
- fix the SSR posture: one analyzer, one emitter per build; the honest JVM subset;
  roots vs frames;
  root manifests, per-root hydration and failure isolation; explicit static-root
  policy; the `client-only` phase flip;
- fix the testing posture: the five-tier pyramid, the `ui.test` contract (`flush!`
  semantics, the 004D selector grammar), Tier-3 fixtures, scoped generative parity;
- fix the gate roster G-1..G-14 plus the 2026-07-16 gates G-15..G-18 and the widened
  G-8, each with one owner and a wiring stage; record which gates are wired/green
  today and which are named-open (stage honesty).

**Non-Goals:**

- the programming model, reactivity/ownership protocol, and debugging evidence layers
  (sibling EPs — see Relationships);
- pre-hydration event replay/resumability (research-tier post-alpha, hard graduation
  criteria), RSC (the JVM SSR path is the canonical server story), and streaming sugar
  (the tree emitter is chunkable; policy stays host-side per Spec 011);
- a dev-bundle size budget (the production claim about debug machinery is *absence*);
- any ongoing performance-parity promise for the coexisting Reagent/UIx compatibility
  adapters (the comparison is a one-time S6 table, then an archive).

## Relationships

- **EP-0030** — the program umbrella (stages, demand bar, adapter disposition);
  stage wiring and the one-time S6 adapter comparison follow its schedule.
- **EP-0031** — the programming model (compiled `defview`, event-vector handlers,
  capability grammar): 0031 promises, 0034 verifies.
- **EP-0032** — reactivity and ownership (observation port, ViewCell, commit
  algorithm); G-3/4/5/6/13 gate its economics here.
- **EP-0033** — view evidence and debugging; its erasure obligation is enforced
  here (G-7/G-11 and the debug roster).
- **EP-0035** — component-library (re-com) readiness, the directed 2026-07-16 package;
  G-15..G-18, the widened G-8, and the proof pack originate there.
- **Specs 008, 011, 004B, 004D** — the graduated homes named in the preamble; 004B/004D
  are live now, 008 and 011 receive their sections with their stages.
- **Methodology provenance** — the synthesis drafts `g10-bundle-baseline-methodology.md`
  and `w11-runtime-benchmark-methodology.md` write down the G-10/W11 methodologies and
  bind their gates at S6.

## Specification

### 1. Production: specialization and the absence roster

Production components carry exactly the machinery their capability bits imply — the
closed vocabulary (`sub · local · effect · event-sites · lease · frame-scope · presence
· error-boundary · portal · client-only · trusted-html · custom-element · foreign-react
· dynamic-view · ui-event/handler · debug-site`) covers every feature that changes
generated code, hydration, ownership, or server behavior. The honest costs are stated:
an event-only view still pays a small commit step; "no handler allocation" is scoped to
the compiled vector path; "no wrapper components" is scoped to incidental wrappers;
memo suppresses *prop-driven* repaints only.

**The absence roster is bundle-scan-verified, never tree-shaking faith:** no hiccup
walker, tag parser, generic camelizer, sequence flattener, form detection, second
scheduler, per-sub hook scaffolding, compatibility stubs, source-coord walkers; no
manifests, cause vectors, histories, timings, warning text, `data-rf2-*` strings,
project paths; no schema engines when elided; **no JVM renderer in any browser entry**;
no proxy/signal/atom/query-cache runtimes. Debug erasure is a proof: advanced builds
are scanned for exact absence (G-11), and dev/prod behavioral equivalence is gated per
generated shape + pairwise capabilities + high-risk triples (G-7) — full powersets are
not a practical gate. Ordinary view registration is **already `goog.DEBUG`-gated in the
emitter** (the registration branch of `compiler/emit_cljs.cljc` — the production arm
is a direct `React.memo` with no registry); G-18 is fixture-first, and a new elision
mode or packaging change is justified only if that fixture fails structurally.

Packaging: the UI source artifact is `.cljc` (compiler + both emitters as source); the
browser build never reaches JVM-renderer namespaces (scan-gated); `re-frame2-ssr`
consumes the JVM emitter — no second server product. Dependencies: re-frame2 core +
patched React/React DOM 19.2.4+ peers; no Reagent/UIx/Helix/slim anywhere in the graph
(G-12, Maven/npm level). Budgets are kill-gate hypotheses: kernel ≤ 4 KB gz over React;
counter ≤ React + 6 KB gz; relative targets vs UIx-adapter and reagent-slim baselines
with symbol-reachability evidence when chunk subtraction is noisy. Scheduling: no
`startTransition`, no per-sub priorities, no second render queue — exact work reduction
plus the one sanctioned synchronous door for controlled inputs; `flush-render!` is for
deterministic tooling, not applications.

### 2. SSR: one JVM structural tree

`defview` is `.cljc`: each host build runs the shared analyzer and hands that build's own
AST to exactly one emitter — CLJS → direct JSX; JVM → the canonical serializable render
tree consumed by `re-frame2-ssr`. The hosts never meet as ASTs. One contextual
conversion/escaping rule table serves both emitters, and parity **detects** divergence
between them rather than preventing it. **Parity is normalized structural
equivalence over semantic nodes** (tag/ns, attr names+values, child order, escaping,
keyed order, void/boolean, fragments, fallbacks), fingerprinted and generatively tested
— **byte-identical HTML is not the contract**, consistent with Spec 011's canonical
hydration-equivalence rule. The JVM subset is honest and closed: structure, props,
subs, branches, lists, event intent, and `ui/html` carry full semantics; `local`
contributes its initial value (invoking the setter is a typed error); `effect` is
recorded, not run; refs are absent; `portal`/`client-only` render explicit
deterministic fallbacks; `error-boundary` is server failure policy per Spec 011;
`presence` renders `:present`.

**A React hydration root is not a re-frame2 frame** — roots (DOM/render units) and
frames (state worlds) are distinct, many-to-many identities; "island" is not
vocabulary. Each independently hydratable unit ships a root manifest (root id, element
locator, view id, props, frame-payload ids, render fingerprint, build digest,
identifier prefix, phase); mount position is never identity; duplicate root ids are a
build error; frame payloads install idempotently and order-independently. Hydration
per root: validate digest + fingerprint → install payloads → `hydrate-root` → one root
**phase flip** swaps `client-only` fallbacks in a single update → first connected
commits acquire ownership. Failure scopes are precise (a mismatch fails that root; a
bad payload affects exactly its referencing roots); no `suppressHydrationWarning`-style
escape exists. Static roots are explicit policy — compiler proof of no client
capability **and** a host declaration; "no subs, no handlers" never silently strips a
runtime. Event vectors are retained as data in the manifest and the JVM tree;
pre-hydration replay stays research-tier (Non-Goals).

### 3. Testing: the pyramid and `ui.test`

Five tiers: **1** headless view tests (structural tree + event-vector intent; JVM and
node; milliseconds, no DOM) · **2** pure dataflow (unchanged re-frame2) · **3** mounted
contract fixtures against real React · **4** Story variants (CLJS-unit-test shape per
repo ruling) · **5** the gates. One namespace, `re-frame.ui.test`: `render` (real view
against a real frame on the JVM → structural tree; `{:sub-overrides …}` is the explicit
JVM override door, not pretended to be the CLJS context mechanism), `find`/`find-all`
(Tier-1 structural queries under the **closed 004D grammar** — tag keyword, view-id/Var,
attr-map matched by `rf=`, predicate escape; no positional combinators; a vector
selector raises `:rf.error/ui-test-bad-selector` naming the composed-`find` idiom),
`query` (the Tier-3 live-DOM counterpart), `text`/`attrs`, `dispatch!` (real dispatch +
drain), `with-root` (CLJS Promise; awaited mount, body, and total teardown), and
`flush!` — the sole public test flush: on CLJS a Promise running the optional thunk
inside React 19 `act`, letting the update and commit phases reach **drain quiescence**, then
alternating framework drains and React commits to a fixed point; on the JVM a
synchronous drain of the headless ViewCell registry. The open-drain guard throws
`:rf.error/flush-in-open-epoch`; a forgotten await fails loudly with
`:rf.error/ui-test-overlapping-act`; there is no production `ui/flush!`. There is
deliberately **no frame constructor in `ui.test`** (ruled 2026-07-14): one
initialization grammar — `rf/make-frame` + `:initial-events` with `[:rf/set-db …]`.
Tier 1 requires the events/subs a view touches to be `.cljc` — a taught authoring
constraint. JVM semantics under test follow the SSR subset; setter or effect use in
Tier 1 is a typed error pointing at Tier 3. The Tier-3 fixture matrix turns the
ownership walkthrough into tests (abandoned mounts, commit-gap correction,
StrictMode/Activity, frame swap, hydration races, HMR, presence, the sync door, the
interop set, Promise-boundary semantics).

**Generative parity is scoped:** props schemas generate props — they cannot generate
an app-db satisfying arbitrary subs, so apps supply state generators/fixtures (a
one-liner around `rf/make-frame`). With inputs supplied: JVM tree vs CLJS
server-equivalent compare as normalized semantic nodes; memo invariants (`rf=` props ⇒
no prop-driven re-render, identical output); value-stabilization invariants (equal
results ⇒ identical references). Fingerprints pin build identity; the corpus doubles
as the hydration-parity suite, including multi-root failure isolation.

### 4. The gate roster (07 §5 is the source; one line per gate)

| Gate | Asserts |
|---|---|
| **G-1** direct-render parity | pure view within 10% of hand-written JSX CLJS (p50/p95) under the noise-robust estimator, plus an emitted-JS golden test pinning direct `jsx` calls (S-1's IFn-dispatch trap) |
| **G-2** AOT peer | ≥ UIx-AOT parity on pure views; reactive one-read ≤ 15% update-p95 over raw correct `useSyncExternalStore` |
| **G-3** multi-read scaling | one store listener and one body invocation per ViewCell; independent lexical-site leases; at most one notification per dirty cell; queued writes settle before one post-quiescence batch |
| **G-4** equality no-op | `rf=` results ⇒ zero revisions, zero prop/sub-driven renders, stable references |
| **G-5** drain fan-in | eight queued update+commit epochs all execute in one run-to-completion drain, then exactly **one** read/render batch; coalescing never drops writes; epoch count is never evidence of render/commit count |
| **G-6** abandonment/disposal | 10k headless abandoned renders/cold probes and bounded mounted StrictMode/Activity cycles return every ownership surface to exact baseline |
| **G-7** dev/prod equivalence | per generated shape + pairwise capabilities + high-risk triples: committed DOM/events/owners/cleanup/hydration agree, debug off |
| **G-8** input correctness (hard) & latency evidence (widened 2026-07-16) | **Hard gate:** in real Chromium **and** WebKit — pre-paint synchronous commit under the sync door, ordering, caret restoration, IME composition, exactly one attributable React commit per ordinary input and exactly one at the committed IME boundary, with the deliberate async-door regression as the tooth; the matrix runs through a **reusable event-prefix component** (`ui/event` vector-outcome door) — toy literal fixtures alone do not close it. **Evidence only:** event→commit p95 against an equivalent hand-written React control in the same warmed run — the ratio, the 10% reference-budget observation, the sample count and the noise policy are recorded and reported; no wall-clock number gates (G-13's posture). An over-budget observation informs `rf2-dpwel`, it never fails G-8 |
| **G-9** list updates | keyed 1k rows: one entity change renders its row + true dependents; stable handler identity; no retained lazy seqs |
| **G-10** bundle | kernel ≤ 4 KB gz; counter ≤ React + 6 KB gz; relative targets vs UIx-adapter/slim with symbol-reachability escalation (methodology: pinned three-module splits, per-module measurement, N=3 determinism, checked-in EDN baseline, ratio + floor + slope guard) |
| **G-11** elision | exact absence of the debug + absence rosters, including `re-frame.ui.test` and its React-`act` boundary, from advanced production bundles |
| **G-12** dependency isolation | no Reagent/UIx/Helix/slim at Maven/npm; no JVM renderer reachable from browser entries |
| **G-13** push falsification | C affected cells fixed while mounted V varies (100→500), plus a multi-write/single-drain arm: exactly C enroll/advances, C body renders, one commit batch per drain; failure **reopens the design**, never forks it |
| **G-14** compile budget | `defview` expansion p95; watch-loop rebuild delta on the dashboard fixture; guide-fixtures CI cost bounded |
| **G-15** atomic-local writer matrix | N same-turn host writers through `update!` all land (key+pointer, timer+listener, observer+handler arms); fn-value `set!` stores exactly; mixed `update!`+dispatch; StrictMode replay; JVM typed failure; `:local-state` cause evidence; HMR ride |
| **G-16** render-slot parity | slotted output normalized-structurally equivalent across both emitters; keyed reorder under slots; purity diagnostics fire inside slot bodies; manifest slot sites present |
| **G-17** safe-spread ownership | the policy form rejects owned keys (`:key` `:ref` `:value` `:checked` owned `:on-*`) in dev **and** advanced builds; `aria-*`/`data-*` pass; the policy form retains the sync door where general `spread` forfeits it |
| **G-18** library façade isolation | an advanced build importing one view from a multi-view library namespace retains no unused sibling views, schemas, docs projections, or dev registration (fixture-first; see §1) |

Methodology, binding on every gate: identical fixtures, distributions not best runs,
pinned browsers, cold+warm, dev overhead separate, no precomputed-props cheating. The
component-library **proof pack** (controlled input, selection controller, slotted list
cell, safe-attrs form control, schema-described component, inline popover, single-view
import) lands in the conformance/parity corpus as the rolling consumer; the substrate
takes no build dependency on re-com.

### 5. Stage honesty — wired/green vs named-open

Every stage wires its own gates into CI in that stage, never later. As of 2026-07-18:
**wired and green** — G-1 (S1, complete; `npm run test:ui-g1` under the noise-robust
estimator, as CI job `cljs-ui-g1`); **two of G-14's three arms** —
`g14_compile_budget_jvm_test.clj` expands three fixture sizes against a 50 ms
pathology bar (`defview` expansion p95) **and** measures the watch-loop rebuild delta
over an 11-view dashboard-shaped roster against a derived absolute bar (K × the
per-view pathology bar), both in CI job `jvm-ui` alongside the REPL re-registration
story. The rebuild arm proves the measured pass actually rebuilt the roster — K
realisations, K **distinct** template fingerprints, all forced inside the timed span —
before any duration is compared, so a shrunken, collapsed, or lazily-deferred pass
cannot report "cheap". Its limit is stated rather than implied: at this roster size the
absolute bar catches a runaway rebuild but does **not** separate a strictly quadratic
pass, which needs a namespace-scale roster or a scaling ratio across two roster sizes
(the suite carries the arithmetic). G-14's third arm is named open below, so G-14 is
**not** complete at S1; the S2 family
G-3/G-4/G-5/G-6/G-13 (landed with the S2 slice; S2 core verified
S3-ready under the rf2-vxgfnd.22 boundary review, which absorbed the real-sub-cache
graft conformance the S-3 spike left open); the G-8 real-browser matrix **including**
its widened event-prefix arm — `npm run test:ui-g8` runs as CI job `cljs-ui-g8` on the
`ui_gates` surface in real Chromium **and** WebKit, superseding S-5's jsdom-only
evidence; G-11 (`npm run test:browser-prod-elision`, CI job
`cljs-browser-prod-elision`, plus the absence scan `npm run test:ui-g1` carries); the
G-15 atomic-local writer matrix (`npm run test:browser`); G-17 (its advanced arm in the
prod-elision build, its dev arms in the `:node-test` and JVM suites); and G-12 (`npm run
test:ui-isolation`, which fails closed unless both arms run). **Named open** — G-7
beyond its production-absence arm: the dev↔prod equivalence matrix over generated
shapes has no test yet; G-14's **remaining** arm — bounded guide-fixtures CI cost,
which needs the guide-examples corpus (its wall-clock budget is an S3 stage item) —
which has no assertion anywhere in the repository, as the gate's own suite states;
G-16's "manifest slot sites present" arm (its cross-emitter
parity and slot-arity arms do run); G-18, whose fixture and
`test:ui-facade-isolation` script exist but are self-declared RED and deliberately held
out of the required matrix until they go green; G-2/G-9 (wire with the stages shipping
their subjects); root-manifest hydration + failed-root isolation (S5 — the S-4 spike
passed dual-host structural output only); G-10 and the remaining
absence/equivalence/budget gates plus the one-time W11 trio table (S6). S3 closed
2026-07-18, and S4 (`rf2-vxgfnd.96`) closed and was declared conforming. The stages
after S4 ride their own epics — S5 `rf2-vxgfnd.97`, S6 `rf2-vxgfnd.98`, S7
`rf2-vxgfnd.99`; for a stage still in flight the epic is the state, not this page.

## Rationale

One roster, one owner per claim: conflating axes lets one excuse the other (bundle
bytes vs runtime speed; correctness vs latency), so G-10 refuses runtime questions and
W11 refuses correctness ones. Structural equivalence over byte-identical HTML follows
Spec 011's existing lock — serializers legitimately differ; the contract is the
computed view. Headless-first testing follows from the design's three structural facts
(views are pure AST→output functions; handlers are data; ownership begins only at
commit) — the browser is reserved for what only the browser can prove. G-13's framing
matters most: it exists to *falsify* the committed push economics, and failure reopens
the design rather than toggling a fork — the roster is honesty, not marketing.

## Backwards Compatibility

Pre-alpha; no back-compat shims. The legacy shared-adapter parity
matrix collapses into three causal owners (new-UI conformance + smoke; Reagent
and UIx compatibility suites + one smoke each); only the Helix arm retires, at
S7 (reagent-slim is kept). The coexisting adapters keep a pinned correctness contract —
a distinct compiled substrate makes no automatic parity promise.
Spec 008's adapter-era testing surface (`flush-views!`, `re-frame.test-helpers`) keeps
governing the compatibility tier until the `ui.test` contract graduates into 008.

## Bead Plan / Reference Implementation

Carried by the program epic's stage children (12 §3): **S1/S2 (done)** — `ui.test`
Tier-1 core, selector grammar (004D live), tree contract (004B live), G-1/G-14 then
G-3/4/5/6/13 wired. **S3** — elision gates G-7/G-11; G-8 including the widened arm;
G-15..G-18 + the proof pack. **S5** — root manifests, multi-root hydration, failure
isolation, `render-static`, the `client-only` phase flip; Spec 011 edits sequenced
behind any in-flight Spec-011 PR (hot zone). **S6** — G-10/G-12 and residual budget
gates; the one-time G-10/W11 comparison tables + archive tag before the S7 Helix-removal
wave. Guide-impact assessment: guide 08 (SSR/hydration), 09 (testing/debugging), and 10
(performance) teach these postures; the guide-fixtures corpus is itself a test surface
— every example compiles and runs as a fixture; one needing internals explained is an
API defect.

## Resolved Decisions


- **Head ownership (06 §5).** re-frame2's existing head model is the one owner of
  document-head output; substrate views never hoist metadata through React's
  head/resource mechanisms, and a foreign head is an explicit interop boundary. S4
  hardens head policy; the ruling itself is settled here.
- **G-1 estimator (ruled).** The gate uses a noise-robust estimator — alternating
  interleaved rounds, median-of-rounds — because µs-scale p95 is environment-dominated
  (S-1-measured). The S-1 PASS predated the ruling; the rerun under the revised
  estimator was executed and wired at S1f (`rf2-vxgfnd.6`, closed 2026-07-12 —
  `npm run test:ui-g1`, all components within the 1.10 budget, emitted-JS golden
  84 direct calls / 0 IFn traps). The source dossier's 07 §5 row predates that
  close and reads stale; this EP records the current state.
  Companion ruling: an **emitted-JS golden test** pins direct `jsx` calls — a
  CLJS-var-bound jsx fn silently reintroduces IFn dispatch under `:advanced` (~5–8%,
  S-1's trap).
- **Byte-identical HTML is NOT the contract (ruled).** Dual-emitter parity is
  normalized structural equivalence over semantic nodes, fingerprinted and generatively
  tested — consistent with Spec 011's canonical hydration-equivalence rule.
- **Drain-quiescence batching is the G-5/G-13 subject (ruled).** Every queued epoch
  executes; one read/render batch follows quiescence; epoch count alone is never
  evidence for render or commit count. G-13 falsifies the committed economics; failure
  reopens the design.
- **Component-library gates addition (directed, 2026-07-16).** G-15..G-18, the widened
  G-8 arm, and the proof pack join the roster per the re-com readiness package
  (EP-0035); they wire with their S3 features under the every-stage-wires-its-gates
  rule. G-18 is fixture-first — the registration branch is already `goog.DEBUG`-gated.

## Recommendation

Accept (ruled 2026-07-11; gates addition directed 2026-07-16) and graduate stage by
stage under the atomic spec-landing rule. Hold the two disciplines that give the
roster its value: budgets stay kill-gates (miss ⇒ optimize or prune, never weaken
semantics), and stage honesty stays explicit (a feasibility PASS never silently closes
a named-open gate).

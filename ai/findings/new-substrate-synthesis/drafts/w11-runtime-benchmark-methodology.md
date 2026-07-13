# DRAFT — W11 runtime-benchmark methodology: the client-side interactive story vs the trio

> **Status: DRAFT — not merged · 2026-07-12.** W11 is the S6 "benchmarks vs trio"
> workstream ⟨11 W11; 12 §3 S6 epic⟩: the RUNTIME-performance comparison of
> `re-frame.ui` against Reagent / UIx / Helix, run **before the S7 deletion wave**, with
> results + fixtures + a git tag as what survives ⟨11 W11; 08 §5 Adapters "Keep" list⟩.
> The G-10 draft explicitly carved runtime performance OUT of bundle measurement and
> pointed it here ⟨drafts/g10-bundle-baseline-methodology.md §9 "Runtime performance"⟩;
> this draft is the receiving end. It reuses G-10's house style deliberately: the
> fixture-family lineage and fixture rules (§3 there), the reproducibility-pin posture
> (§6 there), and the checked-in-EDN-plus-tag artifact shape (§7–8 there). The harness
> pattern is the shipped G-1 bench (`implementation/scripts/run-ui-bench.cjs` +
> `implementation/ui/bench/`) adapted to the browser. Where a detail can only be
> confirmed against the built S6 artefacts, it is marked **[S6-CONFIRM]**.
> House style: `⟨source⟩` provenance tags; British "serialisable".

## 1. What W11 measures — and the named exclusions

W11 answers exactly one question:

> **On identical interactive apps, sharing one re-frame2 dataflow layer, what does the
> new substrate's view/reactivity bridge cost or save at runtime, relative to each trio
> member — measured as user-visible latency and honest work counts, in a real
> browser?** ⟨11 W11; 05 §2/§3; 07 §5 Methodology line⟩

It is the **one-time-at-S6 comparative story** — the runtime sibling of G-10's §8.2
trio table — plus a recommendation (§7.3) on the single recurring subset, if any, worth
keeping after the trio is gone. Everything else runtime-shaped already has an owner, and
W11 refuses to re-own it:

| Not W11's | Owner |
|---|---|
| Bundle bytes, scaling slope, absolute size budgets | G-10 ⟨drafts/g10-bundle-baseline-methodology.md⟩ — the same one-time trio table on the bytes axis; W11 and G-10 share the S6 archive tag |
| Server-render throughput (per-render µs vs hand-written JSX CLJS) | G-1 — shipped and CI-gated (`npm run test:ui-g1`) ⟨implementation/scripts/run-ui-bench.cjs; 07 §5 G-1⟩ |
| The CI-gated push-economics falsification bench (500 views / 1 delta per epoch, own-substrate) | G-13, built by the S2 gate work as the S-2 spike's permanent successor ⟨07 §5 G-13; 08 §1 S-2⟩. W11 runs the *same shape comparatively* (§4 R-E) but the recurring gate is G-13's |
| Input **correctness** — caret stability, IME, one-commit-per-input, the real-browser Chromium/WebKit matrix | G-8 ⟨07 §5 G-8⟩. W11 measures input *latency* comparatively (§4 R-C) and defers every correctness assertion to G-8 |
| Update-parity micro-gates vs hand-written React (`useSyncExternalStore` baselines, multi-read scaling, equality no-ops, epoch fan-in, keyed-list work counts) | G-2 / G-3 / G-4 / G-5 / G-9 ⟨07 §5⟩ — parity/work gates against *hand-written React*, not the trio; W11's scenarios are macro-composites of these micro-claims |
| Memory / retention / disposal | G-6 ⟨07 §5⟩. W11 notes heap flatness as a cross-check only |
| Compile-time budgets | G-14 ⟨07 §5⟩ |
| Dev-mode overhead | out of scope entirely — production builds only (§6); dev/prod behavioural equivalence is G-7's |

The consequence of the split: **W11 carries no permanent gate obligation of its own.**
Its deliverable is the comparison table, the archive, and the §7.3 cadence ruling.

## 2. The comparator set and the shared-dataflow control

- **Comparators**: `re-frame.ui` vs **Reagent, UIx, Helix** (mandatory — the deletion
  wave's named survivors-in-archive ⟨08 §5 Adapters⟩). **reagent-slim** rides as an
  optional fifth row where its fixture builds without bespoke work, matching G-10's
  named-comparator treatment ⟨drafts/g10-bundle-baseline-methodology.md §8.2⟩
  **[S6-CONFIRM]** whether slim earns its rows.
- **The control that makes the comparison meaningful**: every variant of every fixture
  runs the **same re-frame2 core dataflow layer** — identical events, subs, app-db,
  handler registrations (substrate-independent by construction ⟨drafts/g10 §3 fixture
  rules; drafts/reagent-compat-boundary.md §1⟩). The trio adapters and `re-frame.ui`
  all sit on re-frame2 core, so W11 isolates exactly the **view + reactivity bridge**
  delta: element construction, prop conversion, handler identity, subscription
  notification, memo behaviour, commit cost ⟨05 §2 table⟩. W11 is *not* "re-frame vs
  something else" — the dataflow term cancels.
- **Timing**: the table is produced **at S6, before W13 deletes the trio** ⟨11 W11⟩.
  The trio rows freeze in the archive and are never rebuilt; any post-S7 recurring
  measurement (§7.3) is self-baseline only — the same one-time-vs-ongoing split G-10
  §1.3 pins.

## 3. The claims under honest measurement

The scenarios exist to test 05's committed performance claims — as hypotheses, with the
kill-gate posture ("if a budget misses, optimise or prune the feature; never weaken
ownership or hydration semantics to make a number" ⟨05 header⟩):

1. **Memo-by-default with the identity pipeline** — `rf=`-equal props ⇒ no prop-driven
   repaint; unchanged derivation output returns the prior reference and the cascade
   stops ⟨05 §1 "Memo scope"; 05 §2 identity pipeline⟩. → R-A, R-B.
2. **Event-only commit cost is small but not zero** — "an event-only view still pays a
   small commit step… 'no store hook' ≠ 'free'" ⟨05 §1 honest costs⟩. This is a
   *disclosed cost*, and W11 measures it rather than hiding it. → R-D's capability
   grading.
3. **Push economics** — every write epoch executes; after drain quiescence, one
   notification per dirty cell, O(changed graph) + O(C)
   notify, never ΣKᵥ component renders ⟨05 §3⟩; S-2 confirmed the design in jsdom
   (pull 4.0–6.5× worse, gap growing with scale ⟨spikes/s3-ownership-report.md §3⟩)
   and predicted the margin *widens* in a real browser ⟨s3 report §6 risk 6⟩. → R-E.
4. **The sync door costs latency only where applied** — controlled-input dispatches
   drain inline; non-input dispatches keep full batching ⟨02 §3; 05 §6; s3 report §4⟩.
   → R-C.
5. **No per-render handler allocation / stable handler identity on the compiled vector
   path** ⟨05 §1/§2⟩ — a constant-factor claim that shows up in R-B's hot-list ops.

## 4. The scenario matrix

Five scenarios, R-A…R-E. Each names: the claim, the fixture sketch, the
per-framework implementation rule, the environment, and the metric. Common metric
vocabulary: **p50/p95 per operation** under the §5.3 estimator; **component renders per
operation** (the honest work count — wall time can flatter a substrate that does more
work faster); **dropped-frame proxy** where the scenario is DOM/paint-bound (§5.4).

Fixtures follow G-10's fixture rules verbatim ⟨drafts/g10 §3⟩: one canonical dataflow
layer per fixture; one idiomatic view layer per substrate; gate-owned, never under
`examples/` (examples are test-free, locked ⟨CLAUDE.md; rf2-8cevm⟩); frozen once
baselined; the trio variants written once at S6 and archived.

### R-A — update fan-out (the memo-by-default claim)

- **Claim**: one sub delta → exactly the K affected views repaint, at a lower
  constant cost per notified view than reaction bookkeeping (one hook per view, one
  notification per dirty cell per drain, scalar snapshot ⟨05 §2⟩).
- **Fixture** `F-fanout`: N mounted views, each reading its own narrow sub
  (`[:cell/by-id i]`); one dispatch per operation updates K entities. Grid:
  N ∈ {100, 500}; K ∈ {1, 10, 100} at N = 500 and K ∈ {1, 10} at N = 100. Plus the
  **equality no-op arm**: a dispatch that touches the subs' inputs but leaves every
  output `rf=`-equal — expected zero renders on every substrate that suppresses
  correctly (the comparative G-4 shape).
- **Fairness note stated up front**: idiomatic Reagent (re-frame subscriptions →
  reactions) and idiomatic UIx/Helix (per-sub `use-subscribe` hooks) **also narrow to
  K** — this scenario is *not* an O(N)-vs-O(K) strawman. What it measures is the
  constant factor: per-epoch protocol overhead + per-notified-view bridge cost, at
  matched K. Any implementation that made a trio member O(N) here would violate §6.1.
- **Environment**: real Chromium primary (paint cost scales with K); a node/jsdom arm
  as cross-check for render counts and protocol-only wall (the S-2 harness shape).
- **Metric**: p50/p95 per measured one-event drain (dispatch → painted); component
  renders per drain (must equal K,
  every substrate — a fairness *precheck*, in the spirit of G-1's byte-equality
  precheck: if work counts differ, the wall-clock ratio is measuring different work
  and the run is invalid ⟨implementation/ui/bench/re_frame/ui/bench/main.cljs
  precheck rationale⟩).

### R-B — keyed-list churn (1k rows, js-framework-benchmark-aligned)

- **Claim**: G-9's shape at wall-clock level — one entity change renders its row +
  true dependents; stable handler identity means row memo holds under churn; direct
  element calls + compile-time prop conversion beat interpretation in hot lists
  ⟨05 §2; 07 §5 G-9⟩.
- **Fixture** `F-rows-1k`: a keyed 1,000-row list (id, label, selected-flag, two
  buttons with vector handlers). Operation set aligned to js-framework-benchmark
  naming for external comparability ⟨11 W11 "js-framework-benchmark fixtures"⟩:
  **create 1k · partial update (every 10th row) · select row · swap two rows ·
  remove row · clear**. Plus a sustained arm: 200 consecutive swap ops at animation
  cadence for the dropped-frame proxy.
- **Fairness**: each row implementation is that framework's documented hot-list idiom
  (Reagent: Form-2 row component keyed via metadata, narrow `by-id` subscription;
  UIx `defui` + `react/memo` where a careful practitioner would add it; Helix `defnc`
  + `:wrap [(helix.core/memo)]` likewise). Adding memoisation to trio rows is not
  charity — it is what §6.1 requires.
- **Environment**: real Chromium **only** — layout/paint dominated; jsdom underprices
  exactly this work ⟨s3 report §3 closing note⟩.
- **Metric**: per-op p50/p95 (dispatch → painted); renders per op; dropped-frame rate
  on the sustained-swap arm.

### R-C — input latency under the sync door (the controlled-input path)

- **Claim**: keystroke → committed → painted stays within touch-latency norms with the
  synchronous drain, at both nominal and throttled CPU; non-input dispatches in the
  same app keep full batching (the door does not leak ⟨08 §4 risk row; 02 §3⟩).
- **Fixture** `F-input`: a form page — one controlled text input bound
  `[:input {:value (sub …) :on-input [:form/typed :field :rf.ui/value]}]`, a derived
  validation view reading the same field, and ~50 unrelated mounted views (so the
  epoch has a realistic app around it). Driver: scripted 50-keystroke ASCII bursts
  through real key events (`page.keyboard`), plus a burst with 30 ms inter-key gaps
  (human-cadence arm).
- **Fairness**: the trio uses each framework's idiomatic *correct* controlled-input
  pattern — for Reagent/re-frame v1 idiom that is `:value` from the sub +
  `dispatch-sync` on input (the documented v1 controlled-input guidance; giving the
  trio the async-dispatch version would manufacture the caret pathology S-5
  documented ⟨s3 report §4 "async fallback" column⟩ and violate §6.1). Correctness
  itself (caret, IME, composition) is asserted by G-8, not here; R-C runs ASCII-only
  and measures latency.
- **Environment**: real Chromium only (jsdom has no paint — S-5's own honesty note
  ⟨s3 report §4 jsdom limits⟩). **CPU-throttled arms**: 1× and 4× via the CDP session
  (`Emulation.setCPUThrottlingRate` — Chromium-only; Playwright exposes CDP). 4× is
  where sync-drain cost would surface if it is going to. **[S6-CONFIRM]** adding a 6×
  arm if 4× does not separate the substrates.
- **Metric**: keystroke → painted p50/p95 per keystroke; commits per keystroke as the
  work precheck (must be 1 on the new substrate — G-8's fact, used here as validity
  check); epochs per keystroke on the trio (documenting what their idiom actually
  pays).

### R-D — mount/unmount storms (route-transition shaped)

- **Claim**: ownership acquire/release at commit, preflight ENSURE, and first-mount
  fan-out bounded by the slice-scoped memo table ⟨03 §3; s3 report §2 fixture 6⟩ keep
  transition cost competitive; and the **capability-graded** arm prices the honest
  "event-only views still pay a commit step" disclosure ⟨05 §1⟩ instead of hiding it.
- **Fixture** `F-routes`: two page subtrees swapped by one dispatch — a list page
  (~200 mounted views: keyed list + panels, mixed sub readers) and a detail page
  (~50 views); M = 50 alternating transitions per sample run. **Capability-graded
  variants**: the same page shapes built three ways — (i) pure-props views,
  (ii) event-only views, (iii) sub-reading views — so mount cost per capability class
  is a reported number (the specialisation claim made falsifiable: mount cost should
  be ordered (i) < (ii) < (iii), with (ii)−(i) being the priced commit step).
- **Fairness**: the trio builds the same three gradings with its own idioms; note the
  trio has no capability specialisation to grade — its three variants price what a
  uniform component model pays, which is exactly the comparison.
- **Environment**: real Chromium primary; node/jsdom cross-check for acquire/release
  counts.
- **Metric**: per-transition p50/p95 (dispatch → painted); heap-growth flatness across
  the M transitions noted as a cross-check only (retention gating is G-6's).

### R-E — sustained dispatch throughput (the G-13 shape, comparative)

- **Claim**: the committed push economics ⟨05 §3⟩ at exactly the scale G-13 names —
  500 mounted reactive views, 1 sub delta per epoch, 1,000 epochs, warmup 100
  ⟨07 §5 G-13; s3 report §3 parameters⟩ — hold against the trio's real notification
  machinery (Reagent's reaction graph; UIx/Helix per-sub `useSyncExternalStore`
  subscriptions), in a real browser, where real DOM commit costs were predicted to
  *widen* the push margin ⟨s3 report §6 risk 6⟩.
- **Fixture**: the S2 G-13 bench fixture itself, re-skinned per substrate — W11 does
  not invent a second 500-view fixture; it extends the G-13 harness with trio view
  layers. (The S-2 spike's pull arm is *not* rebuilt — pull was falsified as a design
  fork and closed ⟨08 §1 spike outcomes⟩; the comparison here is substrate vs trio,
  all push-shaped in their own idiom.)
- **Fairness**: each trio view layer is that framework's idiomatic narrow-subscription
  shape at 500 views — Reagent: one Form-2 component per view over its own narrow
  re-frame subscription (the reaction graph *is* its push machinery); UIx/Helix: one
  component per view with a per-sub `use-subscribe`-style hook over
  `useSyncExternalStore` — per the §6.1 idiom review; no variant hand-rolls a
  coarse-grained store-read that would manufacture O(N) work. The component-renders-per-
  drain
  work count (must be 1 on every substrate) is the R-A-style validity precheck; a run
  with differing counts is invalid until fixed or documented as idiom-inherent.
- **Environment**: real Chromium primary for the reported table; the node arm *is*
  the G-13 CI gate and stays own-substrate-only.
- **Metric**: µs per measured one-event drain p50/p95; component renders per drain (the
  S-2 table's work
  column ⟨s3 report §3⟩); dropped-frame rate over the sustained run.

## 5. Environment, harness, and estimator

### 5.1 Real Chromium via the repo's Playwright conventions

The browser harness follows the shipped house pattern, not a new stack: headless
Chromium via the pinned `playwright` dependency, a static server over release output,
driver modules in the `spec.cjs` shape (`{name, url, run(page)}` ⟨implementation/
adapters/reagent/testbed/spec.cjs⟩), a `serve-and-run-*` orchestrator, and the
**pageerror-fatal policy** (an uncaught browser exception fails the run regardless of a
green-looking summary ⟨implementation/scripts/run-browser-tests.cjs rf2-mwx08 note⟩).
Each fixture × substrate is its own `:advanced` release build with its own page —
bundle isolation between substrates is a *precondition* (G-12 posture), never one page
loading two frameworks.

**jsdom is banned for anything paint-adjacent.** The split, per scenario:

| Scenario | node/jsdom arm | Real-Chromium arm | Why |
|---|---|---|---|
| R-A fan-out | cross-check (render counts, protocol-only wall) | **primary** | paint cost scales with K |
| R-B list churn | none | **only** | layout/paint dominated; jsdom underprices it ⟨s3 §3⟩ |
| R-C input latency | none | **only**, + CPU throttle | no paint in jsdom ⟨s3 §4⟩ |
| R-D mount storms | cross-check (acquire/release counts) | **primary** | commit + paint |
| R-E throughput | the G-13 CI gate itself (own-substrate) | **primary** for the comparative table | browser widens the push margin ⟨s3 §6.6⟩ |

W11 numbers are **Chromium-only** (the CPU-throttle CDP call is Chromium-only anyway);
cross-engine input behaviour belongs to G-8's Chromium/WebKit matrix ⟨07 §5 G-8⟩.

### 5.2 CPU throttling

R-C runs at 1× and 4× via `Emulation.setCPUThrottlingRate` over a Playwright CDP
session. Throttled arms exist for the *latency* scenarios only — throughput and churn
scenarios report nominal-CPU numbers (throttling a 1,000-epoch run mostly measures the
throttle). **[S6-CONFIRM]** a 4× arm for R-B if nominal-CPU runs saturate below
frame budget on all substrates (a table of all-16ms rows discriminates nothing).

### 5.3 The estimator: alternating rounds, median-of-rounds — page-level

The same noise-robust estimator as G-1, adapted to the browser reality that substrates
live on different pages ⟨07 §5 G-1; implementation/ui/bench/re_frame/ui/bench/main.cljs⟩:

- **Round** = one visit to every variant page, visit order rotated each round (flip
  for two variants, rotation for four/five); 7 rounds (odd — the median stays a real
  observation).
- **Visit** = fresh browser context → load release page → in-page warmup ops
  (JIT/cache settle, the G-1 `warmup` analogue) → S timed samples of the scenario's
  operation(s), collected in-page via `performance.now()` marks and posted to the
  driver.
- Per round × variant: sample p50 + p95. Final estimate per variant: **median across
  rounds** of round-p50s and round-p95s.
- **Disclosure**: page-level alternation is coarser than G-1's per-sample in-process
  interleave — sub-second noise is handled by within-visit sample counts, and only
  slow drift (thermal, background load) is symmetrised by the rotation + median.
  Stated in the artifact, not hidden.
- Comparative results are reported as **ratios per scenario cell** (substrate/trio
  member) alongside absolute µs/ms — the same robust-to-absolute-machine-speed
  property the G-1 estimator was chosen for; gate numbers, if any recur (§7.3), come
  from the pinned CI runner class, local runs are investigation-only ⟨drafts/g10 §6.4⟩.

### 5.4 Paint-inclusive timing and the dropped-frame proxy

- **Op latency** = `performance.mark` at op start (immediately before the dispatch or
  key event) → end at the **post-paint proxy**: double-`requestAnimationFrame` after
  the expected DOM mutation is observed (first rAF runs pre-paint of the frame; the
  nested rAF timestamps the next frame, bounding paint). For R-C, the Event Timing API
  (`PerformanceObserver` type `event`) is the preferred primitive where its 
  granularity suffices, with the mark/double-rAF path as fallback.
  **[S6-CONFIRM]** the final choice after a calibration run comparing the two proxies
  on the same op stream — the calibration itself ships in the archive.
- **Dropped-frame proxy** (R-B sustained arm, R-E): the driver records rAF timestamps
  for the run's duration; a frame gap > 1.5 × 16.7 ms counts as dropped; report the
  dropped-frame rate. A proxy, named as such — not a claim about vsync.

### 5.5 Production-mode proof

Release `:advanced` builds only; `NODE_ENV` production **proven, not assumed** — the
dev-React sentinel-pair pattern (absence in every measured bundle + one positive-control
dev build where the sentinel must be present) exactly as G-10 §4.4 and
`check-perf-bundle.cjs` do it; the G-1 runner's hard refusal to run outside
`NODE_ENV=production` carries over ⟨implementation/ui/bench/main.cljs -main;
implementation/scripts/check-perf-bundle.cjs⟩. Debug/closure-defines off; no devtools
preloads (release ignores them); the same shared compiler-options block across all
variants of a fixture ⟨drafts/g10 §4.1⟩.

## 6. Fairness and honesty rules

### 6.1 No strawmen — idiomatic per framework, reviewed per framework

Every trio implementation is what that framework's documentation and community actually
teach for the shape in question — including their memoisation idioms (`react/memo` on
UIx/Helix hot rows, Reagent's Form-2 + narrow subscriptions), their controlled-input
idiom (`dispatch-sync`), and their keyed-list idioms. Concretely enforced, not
aspirational:

- Each trio fixture variant gets a **named per-framework idiom review** before any
  number is quoted — reviewer fluent in that framework, checklist = "would a competent
  {Reagent, UIx, Helix} practitioner write this for production?" — recorded in the
  artifact (`:idiom-review {:reagent "…" :uix "…" :helix "…"}`).
- The **work-count precheck** (§4 R-A) is the mechanical strawman detector: where the
  design says all substrates should do the same logical work (K renders for K changed
  entities), differing render counts invalidate the run until the fixture is fixed or
  the difference is documented as a real, idiom-inherent cost.
- "No precomputed-props cheating" and the rest of the 07 methodology line apply
  verbatim: identical fixtures, distributions not best runs, pinned browsers,
  cold + warm reported, dev overhead separate ⟨07 §5 Methodology⟩.

### 6.2 Pinned identical dependencies — and the one honest exception

All variants build from the same lockfile, same React/ReactDOM, same shadow-cljs +
Closure, same node — the G-10 §6 pins block, extended with the **browser identity**
(Playwright version + bundled Chromium build) and the CPU-throttle rates. The known
tension carries over unchanged from G-10 §4.3: the new substrate specifies patched
React 19.2.4+ peers ⟨05 §5⟩ while the trio builds against stock React. Same rule:
prefer all variants on the same (patched) peer if it is drop-in for the trio;
otherwise the variants differ in the React dependency **only**, the difference is a
named line item in the artifact, and it is never folded into substrate attribution.
**[S6-CONFIRM]** which branch applies — jointly with G-10, one ruling for both tables.

### 6.3 Publish losing numbers — the memo-hop precedent

The disclosure posture is already set by shipped work: PR #5703 published the G-1
todos-20 ratio at **1.08/1.08 and named the cause** — "the residual ~8% on todos-20 is
the memo hop per row on the server renderer (compiled views are memoized by design; the
hand baseline is not) — inside budget, honestly measured" ⟨PR #5703 §3, quoted verbatim⟩. W11 adopts
that posture wholesale:

- **Every scenario cell publishes**, wins and losses, in the same table — no
  scenario-shopping, no dropping a row because it embarrasses.
- A cell where the substrate **loses** gets a named causal explanation (profiled, not
  guessed) and exactly one of: an optimisation bead, or an **accepted-cost note**
  citing the design ruling that prices it (e.g. the event-only commit step ⟨05 §1⟩,
  the sync door's latency-for-correctness trade ⟨05 §6⟩). Losing numbers with named
  causes are the product's credibility; the budgets-are-hypotheses posture ⟨05 header⟩
  applies to comparative results exactly as to absolute ones.
- The full per-round raw data ships in the archive (the G-1 `out/ui-bench.json`
  convention: opts, estimator name, environment, per-round stats ⟨implementation/ui/
  bench/main.cljs output shape⟩), so any quoted ratio is re-derivable.

## 7. Artifact and cadence

### 7.1 The one-time S6 comparison table

A checked-in EDN artifact + a git tag, sharing the S6 archive tag with G-10's trio
table ⟨drafts/g10 §8.2; 11 W11⟩ **[S6-CONFIRM]** the single tag name. Shape
(illustrative; the schema freezes with the runner):

```clojure
{:w11/schema-version 1
 :pins {:node "…" :shadow-cljs "3.4.10" :closure "…" :react "…" :react-dom "…"
        :playwright "…" :chromium "…" :runner "…" :lockfile-sha "…" :git-sha "…"
        :fixture-set 1 :cpu-throttle-rates [1 4]}
 :estimator "page-level alternating rounds, median-of-rounds, 7 rounds"
 :idiom-review {:reagent "…" :uix "…" :helix "…"}
 :results
 {[:r-a {:n 500 :k 10}]
  {:ui      {:p50-ms 1.9 :p95-ms 3.1 :renders-per-op 10}
   :reagent {:p50-ms 2.6 :p95-ms 4.4 :renders-per-op 10}
   :uix     {…} :helix {…}
   :ratio-vs {:reagent {:p50 0.73 :p95 0.70} …}}
  [:r-b :swap-rows]      {… :dropped-frame-rate 0.00 …}
  [:r-c {:throttle 4}]   {…}
  [:r-d {:capability :event-only}] {…}
  [:r-e {:views 500}]    {…}}
 :losses [{:cell [:r-d {:capability :event-only}]
           :cause "commit-step publication per event-only view (05 §1 priced cost)"
           :disposition :accepted-cost}]}
```

Raw per-round JSON + traces go to CI artifact storage referenced by hash, not checked
in (the G-10 §7 rule). The fixtures themselves — including the trio view layers — are
part of the archive and survive the W13 deletion ⟨11 W11⟩.

### 7.2 When it runs

Once, at S6, after the repo migration has produced the idiomatic-parity fixture
variants and before the W13 deletion wave ⟨12 §3 S6 epic ordering: … → benchmarks vs
trio (W11) last⟩; rerun-at-will before the tag is cut if an S6 optimisation lands.
Landing note: the bench build ids and npm scripts touch the top-level
`implementation/shadow-cljs.edn` + `implementation/package.json` — hot-zone; one
dedicated wiring PR, sequenced per the standing rule ⟨CLAUDE.md hot-zone list;
drafts/g10 §8.3 landing note⟩.

### 7.3 The recurring-gate recommendation: none per-PR; R-B as a nightly self-baseline tripwire

Weighing CI cost per the G-14 remainder posture — wall-clock budgets in CI are
**pathology tripwires, not performance targets**, because timing gates flake and tight
ones flake constantly ⟨drafts/guide-fixture-pipeline.md §6 "Cost (the G-14
remainder)"⟩ — and given what already recurs:

- **No W11 scenario becomes a per-PR gate.** The recurring per-PR coverage of the
  claims already exists where it can be made deterministic: G-13 gates push economics
  (R-E's own-substrate arm) from S2; G-4/G-5/G-9 gate the work counts behind R-A/R-B;
  G-8 gates the input path's correctness and its 10%-vs-hand-written-React latency
  bound; G-1 gates the render path. A per-PR browser wall-clock comparison would
  re-gate those claims with strictly worse signal-to-noise.
- **The trio arms cannot recur at all** post-S7 — the trio no longer builds from the
  repo ⟨drafts/g10 §1.3; 11 W13⟩. Anything recurring is self-baseline by construction.
- **The single scenario recommended for recurring status: R-B keyed-list churn**, as a
  **nightly** (not per-PR) self-baseline tripwire — because R-B is the only scenario
  whose dominant cost (real-DOM reconcile + paint on a hot list) no existing gate
  measures as wall time in a real browser: G-9 asserts render counts and retained
  structures, G-13's gate arm is node-hosted. Form: new-substrate-only, candidate vs
  the checked-in baseline EDN, ratio + absolute floor in the G-10 §8.1 style but with
  deliberately **wide pathology thresholds** (starting point: fail only when a per-op
  p50 regresses ≥ 1.5× on two consecutive nightlies on the pinned runner class), and
  the §8.3-style refresh protocol (deliberate regressions refresh the baseline in-PR
  with justification; unexplained ones get a bead, never a baseline bump).
  **[S6-CONFIRM]** the thresholds after two weeks of observed nightly variance —
  if the variance makes even 1.5× flaky, R-B drops to release-time measurement and
  W11's recurring footprint is zero, which is an acceptable outcome (the tripwire is
  a nice-to-have; the archive table is the deliverable).
- Everything else (R-A, R-C, R-D, R-E-comparative) is **release-time measurement**:
  rerun against the archived baseline at release points and after any change that
  touches the render/notification/commit path, by hand or by a release checklist
  entry — not by the CI scheduler.

## 8. Prerequisites roster (what the S6 filing depends on)

In dependency order; the S6 W11 bead files against these. **Confirm ownership:** that
bead also resolves every `[S6-CONFIRM]` in this draft at filing/run time (item 8's
react-peer ruling jointly with the G-10 bead — one ruling, both tables); none has
another venue.

1. **S2 reactivity shipped** — ViewCell, `sub`, drain-quiescence batching, commit
   reconciliation ⟨12 §3 S2 epic⟩: prerequisite for R-A, R-D, R-E (and R-C's epoch
   half). Specifically the **S2 G-13 bench harness** (the S-2 spike's permanent
   successor, 500 views / 1 delta per epoch): R-E extends it with trio view layers
   rather than inventing a parallel fixture.
2. **S3 events shipped** — data handlers + placeholder splice + the sync-input door
   (S-5 predicate), `local`, `dispatch-fn` ⟨12 §3 S3 epic⟩: prerequisite for R-C
   entirely and for R-B's row handlers.
3. **S4 not required** — the scenarios deliberately use no presence/custom-element
   surface: the trio has no counterpart, so those capabilities are not comparable and
   would poison idiomatic parity (their costs are priced by the substrate's own gates
   instead).
4. **S6 migrated fixtures for idiomatic parity** — the per-substrate view layers,
   written once at S6 while the trio still builds ⟨11 W11⟩, reusing the G-10 fixture
   lineage where shapes permit (F-counter/F-todos/F-dashboard dataflow layers
   ⟨drafts/g10 §3⟩) and adding the W11-owned fixtures: `F-fanout`, `F-rows-1k`
   (js-framework-benchmark-aligned), `F-input`, `F-routes` — gate-owned, frozen once
   baselined.
5. **The idiom reviewers** (§6.1) — named per trio framework before numbers are
   quoted; the review text is an artifact field.
6. **Harness extensions** — all house-pattern-adjacent, none exotic: the page-level
   alternating-rounds driver (port of the G-1 estimator shapes
   ⟨implementation/ui/bench/main.cljs bench-round/median⟩ into a Playwright
   orchestrator in the `serve-and-run-*` + `spec.cjs` conventions with the
   pageerror-fatal policy ⟨implementation/scripts/run-browser-tests.cjs⟩); the CDP
   CPU-throttle helper; the paint-proxy timing helper + its calibration run (§5.4);
   the dev-React sentinel-pair check reused from the bundle gates (§5.5).
7. **Hot-zone wiring PR** — bench build ids in top-level `implementation/
   shadow-cljs.edn`, npm scripts in `implementation/package.json`; one dedicated
   sequential PR ⟨CLAUDE.md hot-zone list⟩.
8. **The G-10 §4.3 / §6.2 react-peer ruling** — taken once, jointly for both tables,
   before either builds its trio variants **[S6-CONFIRM]**.
9. **G-13 green in CI** (S2 onward) — R-E's comparative run presumes the own-substrate
   economics gate already holds; if G-13 is red, the design conversation happens there
   first (its failure reopens the design, not a benchmark table ⟨07 §5 G-13⟩).

⟨11 W11; 12 §3 S6 epic; 08 §2 Stage 6 + §4 risk register; 07 §5 gate roster +
methodology line; 05 §§1–3/5–6; spikes/s3-ownership-report.md §§3–4/6;
drafts/g10-bundle-baseline-methodology.md §§1/3/4/6–9; implementation/scripts/
run-ui-bench.cjs; implementation/ui/bench/re_frame/ui/bench/main.cljs;
implementation/scripts/run-browser-tests.cjs; implementation/adapters/*/testbed/
spec.cjs; PR #5703 §3⟩

# DRAFT — G-10 bundle-baseline methodology: reproducible shared-chunk attribution

> **Status: DRAFT — not merged · 2026-07-12.** Closes the 09 "Still open" item:
> *"G-10's relative bundle baselines need the reproducible shared-chunk methodology
> written down before they can gate."* ⟨09 §Still open⟩ Consumers: the G-10 gate row
> (07 §5), the 05 §5 budget paragraph, the 12 §2b residual budget gates (S6), and W11
> — the benchmark-vs-trio workstream that runs the one-time comparison table before
> the S7 freeze/removal wave ⟨11 W11⟩. The methodology aligns deliberately with the repo's shipped
> bundle-gate house style (`check-schemas-bundle.cjs`, `check-bundle-isolation.cjs`,
> `check-perf-bundle.cjs`, `scripts/lib/read-release-bundle.cjs`). Where a detail can
> only be confirmed against the built S6 artefacts, it is marked **[S6-CONFIRM]**.
> House style: `⟨source⟩` provenance tags; British "serialisable".

## 1. The question G-10 answers — and the one it refuses to answer

G-10's relative half answers exactly one question:

> **What does the compiled substrate add to a production `:advanced` bundle,
> relative to the equivalent app on the trio (Reagent / UIx / Helix), and how does
> that contribution grow as app surface scales?** ⟨07 §5 G-10 row; 05 §5⟩

Three consequences pin the design:

1. **Relative, not absolute.** An absolute "the counter is K kilobytes" number is a
   vanity metric: it is dominated by React and `cljs.core`, moves with every
   Closure/shadow-cljs release, and says nothing about what *this substrate* costs.
   The comparisons that matter are (i) same fixture, different substrate; (ii) same
   substrate, growing fixture (the scaling curve); (iii) same fixture + substrate,
   candidate vs last-accepted baseline (the regression gate). The two absolute
   budgets G-10 also carries — kernel ≤ 4 KB gz over React; counter ≤ React + 6 KB gz
   ⟨07 §5; 05 §1/§5⟩ — are *hypotheses with kill-gates* ⟨05 header⟩ and are measured
   as derived quantities of the same artefact (§5.4), not by a separate method.
2. **Marginal cost is the product claim.** 05's claims are absence, capability
   specialisation, and proven erasure ⟨05 §1⟩: a view pays for exactly the capability
   bits it uses. The observable form of that claim in bundle bytes is the **slope**
   — the marginal cost of each added view/feature — not the intercept.
3. **The trio is a Stage-6-only comparator.** At S7, Helix is deleted while Reagent and
   UIx freeze as compatibility adapters. Their survival is a correctness commitment,
   not an ongoing size/parity promise. Therefore the trio comparison is a **one-time S6
   table produced before the freeze/removal wave**; results + fixtures + a tag preserve
   the comparison and the removed Helix/slim provenance. The *ongoing* gate compares
   the new substrate against **its own last-accepted baseline artefact** and never
   turns Reagent/UIx compatibility into a recurring parity budget. ⟨11 W11/W13; 08 §5
   Adapters⟩

## 2. The shared-chunk problem, stated precisely

A naïve whole-bundle diff misattributes the two heaviest tenants:

- **React + ReactDOM** (~40–55 KB gz depending on version/entry) and **`cljs.core`**
  (tens of KB gz even after `:advanced` DCE) dominate every fixture at counter scale.
  A whole-bundle comparison of a 6 KB substrate delta rides on top of ~10× that in
  shared weight, so (a) real substrate regressions drown in shared-chunk noise, and
  (b) a shared-chunk change (React patch bump, Closure upgrade, `cljs.core` DCE
  behaviour shift) shows up as a phantom "substrate" regression.
- **Compression coupling.** gzip/brotli sizes are not additive: the compressed size
  of `app+react` concatenated is not `gz(app) + gz(react)`, and moving one string
  constant can shift the dictionary enough to move unrelated regions by hundreds of
  bytes. Any subtraction performed on *compressed whole-bundle* numbers attributes
  compression-coupling noise to whichever party is being measured.
- **DCE coupling.** Under `:advanced`, what survives of `cljs.core` (and of React,
  via `NODE_ENV` replacement) depends on what the app + substrate reach. A substrate
  that touches one more `cljs.core` var moves bytes that a whole-bundle diff files
  under "app grew".

**The fix is structural, not statistical:** isolate the shared tenants into pinned
`:modules` splits so each emitted module file has a single accountable tenant, then
measure module files individually. shadow-cljs assigns code to the nearest common
ancestor module of its consumers, so a pinned dependency chain makes the assignment
deterministic ⟨shadow-cljs `:browser` `:modules` semantics; house precedent: the
top-level `implementation/shadow-cljs.edn` example builds⟩. Chunk subtraction that
*still* looks noisy escalates to symbol-reachability evidence (§5.5), exactly as the
gate row anticipates ⟨07 §5 "symbol-reachability evidence"; 05 §5 "when chunk
subtraction is noisy"⟩.

## 3. The fixture family: three sizes, one lineage

Fixtures reuse the S-1 bench fixture family ⟨spikes/s1-codegen-report.md §fixtures:
`counter` (props + branches + capture-free and capturing handlers + keyed identical
rows), `todo-list`/`todo-row` (keyed `for` over an internal view call),
`status-panel` (cond multi-branch)⟩, extended to a dashboard shape shared with G-14:

| Fixture | Shape | What it measures |
|---|---|---|
| **F-counter** | the canonical no-feature counter app (same six-domino logic as `examples/counter`) — S-1's `counter` component shape | the intercept: kernel + minimal app. This is the **baseline fixture** for the per-substrate delta (§5.3) and the carrier of the two absolute budgets (§5.4) |
| **F-todos-N** | TodoMVC-shaped, parameterised by **N structurally distinct view definitions** derived from S-1's `todo-list`/`todo-row` + `status-panel` shapes; pinned instances **N ∈ {4, 16}** | the scaling curve: marginal bytes per added view. The N views must have **distinct bodies** (varied branches, varied event sites, varied sub reads) — N copies of one body would collapse under Closure and measure nothing |
| **F-dashboard** | the dashboard-shaped fixture — **the same fixture G-14 uses** for its watch-loop rebuild delta ⟨07 §5 G-14; 02 §8⟩: many views, mixed capabilities (subs, locals, effects, keyed lists, an error boundary, presence on one panel) | the capability-mix point: does specialisation hold when most capability bits are exercised somewhere, and does per-view cost stay near the F-todos slope |

Fixture rules:

- **One app source per fixture, per substrate variant.** Each fixture has one
  canonical dataflow layer (events/subs/app-db — substrate-independent by
  construction ⟨drafts/reagent-compat-boundary.md §1⟩) and one view layer per
  substrate: `re-frame.ui` `defview`s, Reagent Form-1/2, UIx `defui`, Helix
  `defnc` — idiomatic for each substrate, no adversarial or flattering styles, the
  same rule G-1 applies to hand-written comparisons ("no precomputed-props
  cheating" ⟨07 §5 Methodology line⟩).
- **Fixtures are gate-owned, not examples.** They live with the gate (under the
  bench/testbed tree, not `examples/` — examples are test-free, locked
  ⟨CLAUDE.md; rf2-8cevm⟩) and are frozen once baselined: a fixture edit is by
  definition a baseline-refresh event (§6.3).
- **The trio variants are written once, at S6, and remain an archive** after the wave
  (results + fixtures + git tag ⟨11 W11⟩). Reagent/UIx continue shipping, but these
  comparison variants do not become parity gates.

## 4. Build protocol: identical settings, pinned splits

### 4.1 One build id per fixture × substrate

Every fixture × substrate pair is its own shadow-cljs `:browser` **release** build
(`shadow-cljs release <id>`), compiled `:advanced`, exactly like the shipped example
builds ⟨implementation/shadow-cljs.edn `:examples/counter` family⟩. All pairs share
**identical compiler settings**: same `:compiler-options`, same closure-defines
(debug off — the production posture G-7/G-11 gate), same externs, same
`:js-options`, no `:module-hash-names` (stable file names for the differ), no
`:devtools` effect (release ignores preloads ⟨shadow-cljs docs; noted inline at
`:examples/counter`⟩). The **only** permitted differences between variants are the
view-layer source namespaces and the substrate dependency itself.

### 4.2 The pinned module split

Each build declares the same three-module shape:

```clojure
:modules
{:base  {:entries []}                          ;; cljs.core + goog land here (root)
 :react {:entries   [bench.shared.react-entry] ;; stub ns requiring ["react"] ["react-dom/client"]
         :depends-on #{:base}}
 :app   {:init-fn    bench.<fixture>.<substrate>/run
         :depends-on #{:react}}}
```

- `bench.shared.react-entry` is a stub namespace whose only job is to demand React
  and ReactDOM, pinning their JS into the `:react` module (nearest-common-ancestor
  assignment makes this deterministic — `:app` also demands React, through the
  substrate, and `:react` is its ancestor).
- `cljs.core`/`goog` land in `:base` (demanded by both CLJS modules; `:base` is the
  common root).
- Everything else — app views, the substrate (the `re-frame.ui` kernel, or
  Reagent/UIx/Helix), and re-frame2 core — lands in `:app`.

**re-frame2 core stays in `:app` deliberately.** It is byte-identical input across
all substrate variants of a fixture, so it cancels in every relative comparison;
pinning it into its own module would require a per-substrate stub (the adapters
reach core differently) and would *add* attribution ambiguity, not remove it. The
consequence is stated honestly: the `:app` module is "app + substrate + re-frame2
core", and all cross-substrate deltas are deltas of that composite with the core
term cancelling. **[S6-CONFIRM]** — verify with build-report evidence (§5.5) that
core's contribution is in fact byte-stable across variants; if an adapter drags a
different core surface, that difference is a *real* substrate cost and is correctly
attributed.

### 4.3 npm dependency parity — and the one honest exception

All variants build against the **same lockfile** (`implementation/package.json` +
lock), same React/ReactDOM version. One known tension: the new substrate specifies
**patched React/React DOM 19.2.4+ peers** ⟨05 §5⟩ while the trio today builds
against stock React ⟨implementation/package.json: react 19.2.0⟩. Rule:

- **Preferred:** build *all* variants of the S6 table against the same (patched)
  peer, if it is drop-in for the trio. Then dependency parity is exact.
- **Fallback:** if the trio cannot run the patched peer, the variants may differ in
  the `:react` module **only**. The report then carries each variant's `:react`
  module sizes explicitly, the trio-vs-new comparison is performed on `:app`
  modules only, and the react-peer size difference is reported as its own named
  line item (`:react-peer-delta`) — never folded into substrate attribution.
  **[S6-CONFIRM]** which branch applies.

### 4.4 Production-mode proof, not production-mode faith

`shadow-cljs release` performs the `NODE_ENV` replacement that selects production
React. The runner does not trust that: it asserts a known React-dev-only sentinel
string is **absent** from every variant's bundle, paired with a positive control (a
single dev-mode build of F-counter in which the same sentinel must be **present**)
so the absence check can never rot into vacuity — the exact sentinel-pair pattern
`check-perf-bundle.cjs` uses ⟨check-perf-bundle.cjs PERF_SENTINELS; the vacuity
rationale in its header⟩. The runner also rejects missing/empty bundle dirs via
`classifyReleaseBundle` — the non-vacuous floor ⟨scripts/lib/read-release-bundle.cjs,
rf2-utvst⟩.

## 5. Measurement: the three reported quantities

All byte counts are taken **per emitted top-level module file** (the release
artefact is top-level `*.js` only; stale `cljs-runtime/` dev output is excluded —
house rule ⟨read-release-bundle.cjs, rf2-qlk4w/rf2-z9a06⟩). For each file the runner
records raw bytes, `gzip` (zlib, level 9 — house convention
⟨check-schemas-bundle.cjs `gzippedSize`⟩), and `brotli` (node `zlib`
`brotliCompressSync`, quality 11; no new dependency). "Whole-bundle" compressed
size is the **sum of per-file compressed sizes** — the same summation
`check-schemas-bundle.cjs` uses — not compression of a concatenation.

Per fixture × substrate, the artefact reports:

### 5.1 (a) Whole-bundle bytes

`:base + :react + :app`, raw/gzip/brotli. This is the context number — what a
consumer actually ships — reported for honesty and used by the absolute budgets
(§5.4). It is **not** the regression-gate quantity (shared-chunk noise, §2).

### 5.2 (b) The app+substrate chunk

The `:app` module alone, raw/gzip/brotli, with `:base` and `:react` isolated by the
pinned split. This is the attribution quantity: cross-substrate comparisons and the
regression gate operate here.

### 5.3 (c) The delta vs the baseline fixture — the scaling curve

Per substrate:

- `Δ(F) = app-module(F) − app-module(F-counter)` for F-todos-4, F-todos-16,
  F-dashboard — the marginal cost of the fixture's added surface over the shared
  intercept.
- The **per-view marginal cost**:
  `(app-module(F-todos-16) − app-module(F-todos-4)) / 12` — the slope. The
  cross-substrate comparison of slopes is the headline scaling claim: the compiled
  substrate's per-view cost vs each trio member's, with kernels and React removed
  from the numerator entirely.
- Deltas are computed on **raw bytes primarily** (subtraction of compressed sizes
  compounds compression coupling; §2), with gzip deltas reported alongside as the
  shipped-weight view. Where the two disagree materially, the raw-byte delta is the
  attribution and the divergence is noted.

### 5.4 The absolute budgets, as derived quantities

- **counter ≤ React + 6 KB gz** ⟨07 §5; 05 §5⟩ is measured as: F-counter
  whole-bundle gzip **minus the `:react` module gzip** ≤ 6 KB — i.e. `:base`
  (`cljs.core` residue) + `:app` (kernel + counter). Everything-that-is-not-React.
- **kernel ≤ 4 KB gz over React** ⟨05 §1⟩ is measured on a dedicated
  kernel-only probe entry (mount + one static view; the "cell + event dispatcher +
  dynamic-prop converter + frame context" roster ⟨05 §1 Packaging⟩) as its `:app`
  module gzip. **[S6-CONFIRM]** the probe entry's exact contents against the
  shipped kernel roster.

Both remain kill-gate hypotheses ⟨05 header⟩: if a budget misses, optimise or prune
the feature — never weaken ownership or hydration semantics to make a number.

### 5.5 Symbol-reachability evidence — the escalation path

When a chunk delta is surprising or subtraction looks noisy, the runner attaches
**shadow-cljs build-report data** (per-namespace / per-npm-package optimised-byte
attribution per module) for candidate and baseline, and the report names which
namespaces grew. This is the "symbol-reachability evidence" the gate row reserves
⟨07 §5 G-10; 05 §5⟩. It is **evidence, not a gated quantity** — the gate compares
module numbers; the reachability data explains them and adjudicates disputes about
*which party* a regression belongs to (app fixture vs substrate vs core vs shared).

## 6. Reproducibility pins

The numbers are only diffable when the environment is pinned. Every report artefact
records, and the gate refuses to compare across mismatches of:

1. **Toolchain**: node version, npm version, `shadow-cljs` version (currently
   pinned 3.4.10 ⟨implementation/package.json⟩) and its bundled Closure compiler
   version, and the resolved `react`/`react-dom` versions from the lockfile.
   Compression is pinned transitively: zlib/brotli ship with the pinned node.
2. **Build settings**: `:advanced`; the shared `:compiler-options` block (one
   source of truth for all bench builds); debug/closure-defines off; the module
   split of §4.2; `NODE_ENV` production proven per §4.4.
3. **Determinism, asserted not assumed**: the runner builds each fixture × substrate
   **N = 3 times** from a clean output dir and asserts the emitted module files are
   **byte-identical** across repetitions (SHA-256 per module file, recorded in the
   artefact). If they are not, the gate does not silently average: the
   nondeterminism source is found and either eliminated (usual suspects: stale
   shadow-cljs server/cache state ⟨reference: the stale-server trap⟩, embedded
   build timestamps, gensym-derived strings surviving into output, module hash
   names — disabled here per §4.1) or, if genuinely irreducible, documented in the
   artefact as `:determinism {:status :documented-variance :source "…" :spread-bytes n}`
   with the gate tolerance widened by exactly that recorded spread — never by an
   unexplained fudge factor.
4. **Runner class**: the CI runner class (e.g. the GitHub-hosted image label) is
   recorded and pinned; baseline and candidate must come from the same class.
   Local runs are for investigation — **gate numbers come from CI** (the same
   posture as G-1's environment-dominated-p95 finding: environment identity is part
   of the measurement ⟨07 §5 G-1⟩).
5. **Repo identity**: git SHA, fixture-set version (a monotonic integer bumped on
   any fixture edit), and the lockfile hash.

## 7. The report artefact: checked-in EDN, diffed by the gate

**The gate diffs numbers, not logs.** The runner emits a machine-readable EDN
artefact; the checked-in copy of the last accepted run is the baseline; the gate is
a pure comparison of candidate EDN vs baseline EDN plus the pins check. Shape
(illustrative, not final — **[S6-CONFIRM]** the schema freezes with the runner):

```clojure
{:g10/schema-version 1
 :pins {:node "v22.x" :npm "10.x" :shadow-cljs "3.4.10" :closure "vYYYYMMDD"
        :react "19.2.4" :react-dom "19.2.4" :runner "ubuntu-24.04"
        :lockfile-sha "…" :git-sha "…" :fixture-set 1}
 :determinism {:status :byte-identical :repetitions 3}
 :results
 {[:f-counter :ui]
  {:modules {:base  {:raw 123456 :gzip 34567 :brotli 31567 :sha256 "…"}
             :react {:raw 187654 :gzip 52345 :brotli 47890 :sha256 "…"}
             :app   {:raw 21876  :gzip 5432  :brotli 5011  :sha256 "…"}}
   :whole   {:raw 332986 :gzip 92344 :brotli 84468}}
  [:f-todos-16 :ui] {…}
  …}
 :derived
 {:per-view-marginal-bytes {:ui {:raw 402 :gzip 118} :reagent {…} …}
  :delta-vs-counter        {[:f-dashboard :ui] {:raw 18234 :gzip 4102} …}
  :absolute-budgets        {:counter-minus-react-gzip 39877   ;; vs 6144 budget
                            :kernel-app-gzip 3891}}}          ;; vs 4096 budget
```

The artefact is small, textual, and reviewable in a PR diff — the point. Raw
build-report attachments (§5.5) go to CI artefact storage, referenced by URL/hash
from the EDN, not checked in.

## 8. The gate form

### 8.1 Ongoing: relative regression, candidate vs last-accepted baseline

For every fixture × substrate row present in the active baseline (post-S7 that is the
new substrate only; compatibility adapters are deliberately outside the recurring
parity budget), per fixture:

- **Gate quantity**: the `:app` module (raw and gzip).
- **Budget as ratio**: `candidate / baseline ≤ R` with an absolute floor —
  a row fails only when it exceeds **both** the ratio budget and the floor
  (`candidate − baseline > B_floor`), so byte-level jitter on a 5 KB module can't
  page anyone. Starting values `R = 1.01`, `B_floor = 512 bytes` per module —
  deliberately tight because §6.3 makes same-runner builds byte-identical;
  tighten/loosen only with a recorded reason, the same "bump in lockstep with the
  spec, with justification" discipline the existing gates use
  ⟨check-schemas-bundle.cjs FAIL text; check-bundle-isolation.cjs
  `expectedAllowListHits` note⟩. **[S6-CONFIRM]** the final values once three
  real baseline refreshes have calibrated observed drift.
- **Slope guard**: the per-view marginal cost (§5.3) gets its own ratio budget —
  a regression that adds a fixed cost to *every view* is worse than the same bytes
  once, and a whole-module ratio can hide it at small N.
- **Absolute budgets** (§5.4) assert alongside, unchanged from the G-10 row.
- **Pins mismatch = not comparable**, which is a *fail with a distinct message*
  ("refresh the baseline under the new pins via §8.3"), never a skip.

### 8.2 One-time: the S6 comparison table vs the trio (the W11 deliverable)

At S6, before the freeze/removal wave, the full fixture family builds for **all**
substrates — `re-frame.ui`, Reagent, UIx, Helix, plus reagent-slim as a named
comparator ⟨07 §5 G-10 "vs UIx-adapter / slim"; 05 §5⟩ — and the runner emits the
complete cross-substrate table: quantities (a)/(b)/(c), slopes, and build-report
evidence. That table, the fixtures, and a git tag are the archive that survives the
wave ⟨11 W11⟩; the trio comparison rows freeze there and are never rebuilt by G-10,
even though Reagent/UIx remain shipping compatibility adapters. The ongoing
gate (§8.1) carries forward only the new-substrate rows. This table is also where
the *product* claim is judged once: the compiled substrate's `:app` chunk and
per-view slope vs each trio member, on identical apps, with shared chunks pinned
out of the numerator.

### 8.3 Baseline refresh protocol

- A **deliberate** size change (feature lands, kernel grows for a ruled reason,
  toolchain pin bump, fixture-set bump) refreshes the baseline **in the same PR**:
  the PR regenerates the EDN artefact on CI, checks it in, and the PR body carries
  the per-fixture before/after table plus the justification. Reviewing the baseline
  diff *is* reviewing the size change.
- An **unexplained** regression never refreshes the baseline to go green; it gets a
  bead and the build-report evidence attached (§5.5).
- Pin changes and fixture edits always bump `:fixture-set`/`:pins` so history stays
  segment-comparable; the gate never compares across segments.
- Landing note: the bench build ids live in the top-level
  `implementation/shadow-cljs.edn` and the runner scripts in
  `implementation/package.json` — both **hot-zone**; the wiring PR sequences per
  the standing rule ⟨CLAUDE.md hot-zone list; 12 §1⟩. G-10 wires at **S6** with the
  other budget gates ⟨12 §2b residual gates; 08 §2 Stage 6⟩; the runner and fixture
  family can be built earlier as satellite work, but nothing gates before its
  stage ⟨12 §3 standing rules: every stage's gates wire in that stage⟩.

## 9. What G-10 does NOT measure

Named exclusions, each with its owner, so this gate never scope-creeps:

- **Runtime performance** — render/update latency, epoch fan-in, list updates:
  G-1/G-2/G-3/G-5/G-8/G-9, the G-13 push-falsification bench, and the W11
  js-framework-benchmark fixtures ⟨07 §5; 11 W11⟩. Bundle bytes and runtime speed
  are separate falsifiable claims; conflating them lets one excuse the other.
- **Dev-bundle size** — dev builds carry debug machinery by design; the production
  claim about that machinery is *absence*, owned by G-11 (exact absence + rosters)
  and G-7 (dev/prod equivalence) ⟨07 §5; 05 §4⟩, not a dev-weight budget.
- **SSR payload sizes** — root manifests, frame payloads, hydration digests are
  011's ledger (W10 / Spec 011) ⟨11 W10; 06⟩, measured on wire artefacts, not on
  browser JS modules.
- **Dependency isolation** — "the trio/tools never appear in the graph at all" is
  G-12 (Maven/npm level) and the existing sentinel-grep gates
  ⟨check-bundle-isolation.cjs⟩; G-10 assumes those hold and measures what remains.
- **Memory/retention** (G-6) and **compile-time budgets** (G-14 — which shares the
  F-dashboard fixture but measures seconds, not bytes) ⟨07 §5⟩.

## 10. Summary of what must exist before G-10 can gate

**Confirm ownership:** the S6 G-10 gate-wiring bead resolves every `[S6-CONFIRM]` in
this draft against the built S6 artefacts (the §4.3 react-peer branch is one joint
ruling with the W11 bead — ⟨drafts/w11-runtime-benchmark-methodology.md §8 item 8⟩);
none has another venue.

1. The fixture family (F-counter, F-todos-4/16, F-dashboard) per §3, gate-owned,
   with per-substrate view layers at S6.
2. The pinned three-module build shape (§4.2) as bench build ids (hot-zone wiring
   PR, §8.3).
3. The runner: N=3 determinism check, per-module raw/gzip/brotli measurement,
   dev-React sentinel pair, non-vacuous-floor rejection, EDN artefact emission
   (§§4–7) — house-style siblings of the existing `check-*-bundle` scripts.
4. The checked-in baseline EDN + the comparison gate with ratio/floor/slope budgets
   (§8.1).
5. The one-time S6 trio table + archive tag (§8.2), delivered by W11 before the
   compatibility-freeze / Helix-and-slim deletion wave.

⟨09 §Still open — this draft is the write-down that item required; 07 §5 G-10;
05 §1/§5; 11 W11; 12 §2b/§3; 08 §2 Stage 6⟩

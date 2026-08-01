# Hicasso — studio

The programme's measured record. Every number Hicasso publishes lands here and
on its bead; the operator-owned standard bead (`rf2-2rtt6.1`) carries the bar,
the budgets and the kill criteria, and this tree carries the evidence those
rulings are made against.

Minted by the first P0 worker per [HD-017](../decisions.md). **The Freehand
studio at `docs/design/freehand/studio/` is frozen and is never extended** — it
is the predecessor programme's record and it stays exactly as its own programme
left it.

## What a page in here owes its reader

The measurement discipline in [validation.md](../validation.md) is binding, and
these four obligations are the ones a page can silently fail:

- **The producing commit SHA and a reproduction command, per row.** Evidence
  must not outlive the code that produced it.
- **The runtime, beside every figure** — browser and version, build
  optimisation level, `goog.DEBUG`. All bar numbers are browser numbers
  (`:advanced`, real browser); JVM and Node figures are diagnostic-only and are
  never quotable against the bar.
- **Ranges across rounds, never a mean alone.** Overlapping ranges mean
  *indistinguishable*, and the page says so rather than quoting the mean as a
  winner.
- **`N unverified of M`** for any row that writes, and the positive control's
  **predicted vs measured**, on every run, passing or not.

A ratio stays attached to its exact witness, denominator, commit and build.
Never average across instruments.

## The lane

One build id serves the whole programme —
[`:hicasso-bench`](../../../../implementation/shadow-cljs.edn) — and one driver
rides it, so an arm needs no hot-zone edit of its own:

```bash
cd implementation

# the default entry: the P0 Reagent-on-subs baseline
npm run bench:hicasso

# any other arm, same build id, same driver
HICASSO_INIT_FN=re-frame.bench.hicasso.<arm>-app/-main \
HICASSO_OUT_DIR=out/hicasso-<arm> \
HICASSO_PORT=8132 \
  node freehand/test/re_frame/bench/hicasso/run.cjs

# an arm that needs more than one page load brings its own thin driver on
# the same build id — e.g. the converged clock table, one row per page
node freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs
```

Exit codes: `0` measured and clean, `1` the run failed, **`2` the arm-order
guard refused** — a figure that moves with its position in the plan is not a
figure, and the repair is the arm, never the tolerance.

**One build id also means one build cache, and each driver now clears it before
building** (`rf2-2rtt6.20`). shadow-cljs derives the cache directory from the
build id alone, and fixes it before any `--config-merge` data is applied — so
the arm is invisible to the cache key and every arm above shared one cache
entry. Running the commands in this section in sequence used to leave a cache
that compiled cleanly and then produced an `:advanced` bundle that **died on its
first execution**, `Cannot read properties of undefined (reading 'd')`, which
made a sound arm look broken at HEAD. The carrier was the `shadow-js/`
npm-conversion cache: its index is invalidated on shadow's own cache key, never
on the set of npm modules the build actually needs, so the UIx arm's
`react/jsx-runtime` was linked against whole-build state established for the
Reagent arms' fourteen modules. **Every reproduction command on these pages
therefore runs from a cold or a warm cache, in any order** — and emits the same
bundle either way, which a reproduction command needs and did not previously
have: two cleared-cache builds of an arm are byte-identical, where before the
fix the bundle depended on which arm had built last. The clear sits inside the
run-to-run noise, because the time is JVM start, classpath and the Closure
`:advanced` pass rather than the CLJS compile the cache holds.
`freehand/test/re_frame/freehand/bench/lane_cache.cjs` carries the isolation, the
measurement and the alternatives that were rejected.

The instrument is
`implementation/freehand/test/re_frame/bench/hicasso/lane.cljs`; it lives in the
bench/test measurement lane HD-017 carves out of the donor freeze, and it
requires nothing from any donor `src/` tree.

## Pages

| Page | Phase | What it settles |
|---|---|---|
| [P0 — Reagent-on-subs baseline (mount + bulk)](p0-reagent-on-subs-baseline.md) | P0 | The ship bar's **denominator**: mount and bulk view work for Reagent reading re-frame2 subscriptions. |
| [P0 — ratom-spine narrow write](p0-ratom-spine-narrow-write.md) | P0 | What a narrow write on the ratom spine costs, and the write-versus-flush split. |
| [P0 — the UIx-on-subs frontier arm](p0-uix-on-subs-frontier-arm.md) | P0 | The frontier against the denominator, on the arm's own witnesses. Its retained-heap absolutes are **shared-query fan-out evidence** (rf2-2rtt6.16), its ratios the cross-regime check; its clock rows are superseded by the converged page. |
| [P0 — the converged witness set, and the red-zones re-derived on it](p0-converged-witness-set.md) | P0 | The **clock red-zones** on the denominator's own witnesses, and how far the earlier per-arm witness sets moved the verdicts. |
| [P0 — the reads-per-boundary heap ladder](reads-per-boundary-heap-ladder.md) | P0 | Retained heap per subscribing boundary across the 1/3/7/20 reads ladder, both donors — the **operative** distinct-query (Q = E) red-zone family. [§6](reads-per-boundary-heap-ladder.md#6-the-hicasso-candidate-rung--one-hook-plus-a-shared-index) adds the **Hicasso candidate rung** on the P0 bench instrument with both donors re-taken beside it: one hook per boundary beats the UIx spine by 23.8% per read on the same substrate, and does **not** beat Reagent's 943 — it costs 1.44× of it. |
| [P0 — the heap fan-out sweep](heap-fan-out-sweep.md) | P0 | What sharing a subscription is worth: E/Q at 1, 2, 4 and 8 on one instrument, with B and reads/boundary pinned. Confirms the ruling's fan-out-1 prediction, reproduces the `.4` grid arm to 0.3%, and prices the shell and per-unique-key terms — **refusing** the Reagent per-edge one. |
| [HD-008 — the composed donor arm](hd8-composed-donor-arm.md) | donor gate | What the composed donor arm costs against both Reagent paths and against the frontier. |
| [HD-008's fourth donor arm — Freehand's codec, entered](hd8-freehand-codec-donor-arm.md) | donor gate | What the **other** runtime hiccup codec already in this tree costs, with the boundary, the spine, the dispatch, the handler and the page all held fixed and only the codec varying. Answers "should Hicasso consume Freehand's codec rather than author one?" — and reports against **raw UIx and the existing donor arms**, never against the suspended mount bar. |
| [The UIx spine's per-read allocation, decomposed](uix-spine-per-read-decomposition.md) | P0 | Where a UIx-on-subs read's bytes go. |
| [The cold-mount double build, priced against the mount red-zone](coldmount-double-build-priced.md) | P0 | What the double build **costs on the clock**, as a fraction of the same-run UIx-minus-Reagent mount excess — the rf2-2rtt6.14 decision input. |
| [The reagent-slim non-reactive arm — diagnosis](slim-non-reactive-arm-diagnosis.md) | P0 | Why HD-008's reagent-slim arm read `78 unverified of 78`: the **arm's composition**, not the adapter and not the mixed bundle. A single-substrate slim bundle re-renders on every write. |
| [Arm 1 — lean-React: the mechanism and the dogfood judgement](arm1-lean-react-dogfood-judgement.md) | P1 | How the ≤2-hook shell was reached, the collector against HD-002's four clauses, and the three-rendering ergonomics verdict. **Publishes no bar row** — mechanism, correctness witnesses and preference only. |
| [A controlled input has two implementations, and the bundle was picking](controlled-input-two-implementations.md) | P1 | Why a refused keystroke appeared to survive on React (rf2-n3dxw): UIx selects between plain React and a port of Reagent's workaround **on what else is on the classpath**. The full value/caret matrix for both, and the priced option that carries every row in one turn. **Publishes no bar row** — correctness only. |
| [The candidate's clock](the-candidates-clock.md) | P1 | The candidate's **first wall-clock rows** — mount at **1.21× Reagent-on-subs** (above the bar and above the red zone; above parity in all five runs, range straddling 1.0) and per-keystroke indistinguishable from both donors at one frame. Measured on a **frame-inclusive** instrument — Chrome's renderer counters over CDP, settled to the next frame, plus Event Timing — rather than an in-page span, and **refuses** the three bulk rows. Locates the mount deficit in the runtime hiccup **codec** rather than the spine, and records that an in-page `performance.now()` window mis-reads a substrate arm by 300–610%, by a different factor per arm. |
| [The clock behind the published rows](the-clock-behind-the-published-rows.md) | audit | **Which published clock rows were measured on an instrument that could see them** (rf2-8nqsl). Every row in the programme is an in-page `performance.now()` window closing when `flushSync` returns, established per row at file:line. Priced against a frame-inclusive clock on the same samples: substrate arms diverge **+268% to +704%**, pure-React controls under 13%. **`M1 mount 1.0150×` and coldmount's `1.0054×` are robust**; **`bulk broad 0.6291×` does not survive it** — re-taken by [rf2-yd52q](bulk-broad-re-taken.md), which also found that this page's clock was subtracting the operation's own script. |
| [Bulk broad, re-taken](bulk-broad-re-taken.md) | P1 | **The 37% win is withdrawn and the direction survives** (rf2-yd52q). On a clock that sees script *and* frame, `UIx / Reagent` on a broad commit reads **0.8602×** [0.7709 – 0.9058] against a published `0.6291×`; no magnitude replaces it, because the control refused on 7 of 8 runs and the 14% margin is inside the band on half. **The larger finding is the instrument**: `TaskDuration` less `DevToolsCommandDuration` removes the operation's own script, so the "frame-inclusive" clock was a frame-**only** one and near the in-page window's complement rather than its superset. The corrected clock reproduces `M1 mount 1.0150×` at **1.0011×** in the same runs. |
| [The candidate's rows, re-adjudicated on the corrected clock](rows-re-adjudicated-on-the-corrected-clock.md) | P1 | **The mount deficit is worse than published and the other rows stay refused** (`rf2-emvod`). `hicasso / reagent-subs` on `M1` reads **1.4896×** on the ensemble with standing, against a published `1.2107×`. **And the correction has a boundary**: `DevToolsCommandDuration` absorbs the operation's script only when the operation runs *inside* a protocol command, so the `keystroke` row and the whole outside cross-check — which drive through the **Input domain** — were never affected, and their two clocks agree to 0.3% and 0.8%. That makes `taskNet` a label meaning two things on two harnesses, which is what **reconciles the three mount numbers**: `1.2107×` is not a member of the comparison, and what remains is workload (~7–17%) plus an instrument-window 8.8%. `bulk100` changes sign (0.9859 → **1.1089×**), `narrow` reads **parity** and refutes this page's own pre-registered prediction, and `ctl-2x`'s undershoot is shown **additive** — it fails on the mount row too, so `rf2-7iqb5`'s repair is necessary and not sufficient. |
| [Cross-checked against an instrument nobody here wrote](cross-checked-against-an-outside-instrument.md) | P1 | The benchmark app of [krausest/js-framework-benchmark](https://github.com/krausest/js-framework-benchmark) implemented in **three** re-frame2 arms — Reagent-on-subs, UIx-on-subs and Hicasso Arm 1, one shared model, canonically identical DOM — and run under **its** driver as well as ours. The candidate's **mount is corroborated** (`1.1756×` theirs against our published `1.2107×`), and the contested **bulk-broad `UIx / Reagent` `0.6291×` is refused a third time** (`0.9740×` theirs, `1.1401×` ours, beside the audit's `1.0509×`). Fills the clock page's two refused rows: bulk is the candidate's **worst** row (1.43–1.62×), narrow is a **win** (0.72–0.76×). 6 of 10 rows agree within 15%; the largest gap has a named mechanism. **Positive control FAILED** and the page says so. |

**Clock red-zones live on the converged page.** Where it and the frontier arm
disagree, the converged row is the operative one: the bar's denominator is
Reagent-on-subs, so the denominator's witness set defines the comparison by
construction, and a threshold measured on a different witness is a threshold on
a different question.

**Retained-heap red-zones are regime-matched** (heap red-zone regime ruling,
delegated by Mike, 2026-07-31; authoritative text on rf2-2rtt6.16,
transcription on rf2-2rtt6.1). Cache cardinality is part of the witness, so
every heap red-zone row stamps **B** (boundaries), **E** (boundary-query edges)
and **Q** (unique live query keys). The
[heap ladder](reads-per-boundary-heap-ladder.md)'s distinct-query rows (Q = E)
are the mandatory worst-case witness and the operative upper-envelope red-zone
family; the frontier arm's absolute heap rows are fan-out 4 (amortised ×4)
shared-query witness evidence, not comparable to distinct-query rows, and its
ratios stay published as the cross-regime check — the 8% agreement across
shapes is *ratio* agreement, common-mode across both arms, so the ratio ports
and the quantity does not. **The ratio ports approximately**: the
[fan-out sweep](heap-fan-out-sweep.md) measured it drifting **9.3%** across
E/Q 1 → 8, against 1.6–1.9× for the absolute over the same range, so a
cross-regime check quoted to three decimals is quoting drift. A candidate heap
row is judged only against donor rows measured under the same regime on the
same witness, and reports both regime-matched gates: UIx **2,935 B/read**
[2,852–3,055] on the P0 bench instrument (worse is RED, operator waiver
required) and Reagent **943 B/read** (worse with no named paper path down is K3
territory; between the two is "UIx-rule cleared, K3 open", never plain green).

**Heap absolutes published before 2026-07-31 predate two spine landings, and
the UIx gate line has been restated on the post-landing tree.** The landing that
moves retained heap is `rf2-2rtt6.13` (`9df5094816`), which stopped retaining a
disposed render-phase reaction — **−769 B per unique query key** on the UIx
spine. `rf2-2rtt6.25` (`f784ab0adb`) landed the hook-scoped provisional hand-off
in the same window and is a **tree stamp rather than a heap cause**: its single
build holds under a forced synchronous commit, while the audit of PR #7305
measured 2N on the shipped bare `createRoot().render` path, so no retained-heap
delta from it is established and none is attributed. That is open on
`rf2-2rtt6.25`. Every UIx heap absolute on the ladder and on the frontier arm
was measured before both. The [fan-out sweep](heap-fan-out-sweep.md) is the
first heap page measured after them, and it prices `.13`'s difference: at
fan-out 1 the UIx/Reagent retained-heap ratio moves from ~2.44× to **~1.97×**.
On Mike's ruling of 2026-07-31 (option (a), `rf2-e3flf`) the UIx per-read gate
line was restated from **3,552 B/read** to **2,935 B/read** rather than stamped
historical — a line measured on a spine that no longer ships is looser than the
tree warrants, and loose is the unsafe direction for a gate. **That line rests
on a direct two-point contrast at Q = E, not on an independent triangulation**;
the arithmetic, and an explicit account of which readings are independent of
each other, are in
[the ladder's §5](reads-per-boundary-heap-ladder.md#5-what-this-hands-the-programme).
Reagent's 943 B/read is unmoved: neither landing goes near the ratom path.
Compare heap absolutes across that line only with the correction stated.

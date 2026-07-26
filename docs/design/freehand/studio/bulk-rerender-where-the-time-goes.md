# Where does Freehand's bulk re-render time go?

Seat: PROFILE SPIKE. Answering the one row
[`freehand-vs-reagent.md`](freehand-vs-reagent.md) identified as squarely
Freehand's own — **the broad update, ≈10× Reagent on repainting 300
boundaries** — and the operator's standard of 2026-07-27, verbatim:
*"Freehand can't be used if it is slower than Reagent."*

Measured 2026-07-27 against `main` at `2b02616db0`. Reagent **2.0.1**,
React **19.2.0**, headless Chromium 147 via Playwright, `:advanced` with
`goog.DEBUG false` — the artefact a consumer ships. Same host and the
same caveats as the report this extends.

**The compiled tier does not appear here.** It was ruled out on
2026-07-27 and is not measured, not quoted and not used as a baseline.
The arms are interpreted Freehand, Reagent, and the hand-built React
floor.

---

## The answer, first

**It is architectural, not a hot spot.** Three findings, and the second
is the one that settles it.

1. **There is no hot spot to fix.** In a capture of nothing but broad
   writes, the largest single self-time frame inside React's render phase
   is **4.69%** and inside React's commit phase **4.26%** — and neither is
   a Freehand function. Both phases are flat and are dominated by
   `cljs.core` collection primitives: `get`, `assoc`, `hash`,
   `inode-lookup`, `inode-assoc`, `array-index-of`, `-as-transient`,
   `into`, `reduce-kv`. That is allocation churn spread thin across a lot
   of bookkeeping. There is no function whose deletion moves the row.

2. **It is not per-boundary either, and an ablation proves it.** Holding
   the DOM, the reads, the lowering and the emitter constant and varying
   *only* how many boundaries the 300 reactive reads are spread across,
   300 boundaries → **1** boundary moves the write from **[3.9–4.5] ms**
   to **[3.0–3.2] ms**. Real, disjoint, and a minority term: about **25%**
   of the deficit. **With one boundary Freehand is still ≈9× Reagent.**

3. **What survives the ablation is the observation ledger, and it is
   O(reactive reads).** Recording 300 reads at render costs **0.77–0.86
   ms**; re-staging, re-reading and republishing the same 300 dependencies
   in the commit-phase layout effect costs **0.86–1.23 ms**. Together
   ≈2.0 ms — **≈55% of the gap** — and neither term scales with boundaries
   or with elements. It scales with *reads*.

That double accounting is not an accident and not a redundancy. It is the
atomic shell's contract, stated normatively in
`spec/006-ReactiveSubstrate.md` §The Freehand atomic shell and repeated in
`shell.cljs`: **"Render resolves and probes; the LAYOUT COMMIT owns."**
The commit-side re-read through `obs/read` is the invariant-5 tear check —
it compares node-key, version and frame/registry epoch between the render
that produced the element and the commit that publishes it. Reagent has no
counterpart to any of it: `deref-capture` collects reads into a flat array
during render and diffs the watch set once, with no commit-phase
transaction, no all-or-nothing bundle and no per-read map.

Measured on the same React, in the same bundle: **Reagent's entire
layout-effect commit phase is 0.36% of its capture — 1.4 µs per write —
against Freehand's 1.23 ms.**

**What this does not say.** It does not say the row cannot be improved.
It says the improvement is a redesign of the dependency ledger in
`cell.cljc`, not an optimisation findable in this profile, and §5 names
the three candidates and ranks them.

---

## Method, and what it refuses to claim

The clock is [`freehand-vs-reagent.md`](freehand-vs-reagent.md)'s,
unchanged. The **window** is
`re-frame.freehand.bench.b6-rows/timed-write!` called verbatim — the same
state install, the same single microtask yield, the same empty
`react-dom/flushSync` drain, and the same per-write read-back of the
written cell out of the DOM. Profiling a window that differed from the
published one would describe a measurement nobody took.

**One arm, alone.** The published suite interleaves four arms at the
sample level, which is right for the clock and useless for attribution:
the samples split four ways and most of the capture is scheduler idle.
Every capture here is one arm in a tight loop — 500 writes for Freehand,
2,000 for Reagent and the floor — with a 60-write warm-up **outside** the
capture. Measured idle across the four captures: **0.06%–0.5%**.

**`:pseudo-names`, not a development build.** The profiled twin is the
same `:advanced` pipeline — same inlining, same DCE, same collapsed
properties — with the renaming policy changed so symbols survive. React
and react-dom arrive as *already-minified* npm JavaScript, so nothing can
name their internals; they are identified by minified symbol and
`url:line`, and because every arm rides one bundle those symbols are
**the same frames across arms**, which is what makes the phase comparison
below legitimate.

### Instrument scepticism, discharged three ways

Two instrument faults on the predecessor bead each produced a plausible
wrong table. This one carries three checks and **all three fired**.

- **A positive control of known cost.** `window.B6_CONTROL_MS` spins a
  named function for a set time inside every write. Predicted share
  2/(2+4.055) = **33.0%**; measured **32.23%**. The profiler and the
  attribution tool are sound to within a point.
- **Every write verified at the DOM, inside its own window.** **0
  unverified writes of 8,500** across every capture and every repeat
  round in this page.
- **The ablation arm is gated on canonical DOM.** It had to be: the first
  version spelled its keys as `^{:key i}` metadata, rendered an **empty
  page**, and the gate refused it rather than reporting a very fast
  number for nothing at all. The published arm reports `parity=true`
  against the reference arm's 11,915-character canonical serialisation.

**And the profile agrees with the clock.** The capture's `flushSync`
share × wall ÷ writes is **2.97 ms**; the clock's independently measured
`force-ms` mean is **2.947 ms**. Two instruments, 1% apart.

**Four things this page refuses to claim.**

- **No absolute number here is comparable to the published table's.** A
  single warm arm in a tight loop does not pay the cache eviction three
  other substrates cause, so every arm is faster here than in the
  interleaved suite (Freehand 4.0 ms against the suite's 5.4–7.4 ms).
  **Ratios transfer; absolutes do not.** The ratio does transfer: **11×**
  here against the suite's **10.5×**.
- **Phase shares are single captures.** Only the wall-clock rows carry
  ranges across rounds. A share quoted to two decimals is one capture's,
  and should be read as ±1 point.
- **No claim about mount.** This is the update row only.
- **No memory claim.** The allocation reading the profile implies — that
  this is churn — is not a heap measurement and is not offered as one.

---

## 1. The wall clock, three rounds

p50 milliseconds per broad write; each round is 500 writes (Freehand) or
2,000 (Reagent, floor). Range across three rounds in brackets.

| arm | total | write | gap | force (React) |
|---|---:|---:|---:|---:|
| **Freehand interpreted** — 300 boundaries | **4.0** [3.9–4.5] | 0.7–0.8 | ~0.2 | 3.0–3.5 |
| **Freehand interpreted** — 1 boundary, *same DOM* | **3.1** [3.0–3.2] | 0.7 | ~0.0 | 2.3–2.4 |
| **Reagent** — 300 components | **0.35** [0.3–0.4] | 0.0 | 0.0 | 0.3–0.4 |
| floor — top-down React, no substrate | 0.1 [0.1–0.2] | 0.0 | 0.0 | 0.1 |

Freehand ÷ Reagent ≈ **11×**, disjoint ranges. The published interleaved
suite says 10.5×; the two agree.

---

## 2. Where the 4.05 ms goes

Shares of one 500-write capture, converted to milliseconds per write at
the capture's own wall ÷ writes. React's phase frames (`Kh` = render,
`Wk`/`Bl`/`Zb` = the commit-phase layout-effect loop) are the *same
minified symbols* in every arm, because every arm rides one bundle.

| leg | share | ms/write | what it is |
|---|---:|---:|---|
| **`flushSync` total** | 73.38% | **2.97** | React render + commit |
| — React **render** (`Kh`) | 37.70% | 1.53 | |
| —— `cell/observe!` → `record-read!` | 19.09% | **0.77** | recording 300 reactive reads on the candidate |
| —— `react/emit` | 11.62% | 0.47 | the interpreted markup walk |
| —— React hook machinery, other | ~7% | ~0.29 | 6 hooks × 300 boundaries |
| — React **commit** layout effects (`Wk`) | 30.48% | **1.23** | |
| —— `cell/commit!` | 29.43% | 1.19 | staging, re-reading and publishing 300 deps |
| — React DOM mutation and diff | ~5% | ~0.21 | |
| **outside `flushSync`** — the write leg | 18.38% | **0.74** | `frame/replace-app-db!` + the signal graph |
| **outside `flushSync`** — gap, DOM read-back | ~8% | ~0.34 | the microtask, and the instrument's own verification |

### The same three phases, across arms

| arm | `flushSync` | React render `Kh` | **commit layout effects `Wk`** |
|---|---:|---:|---:|
| Freehand interpreted | 73.38% → 2.97 ms | 37.70% → 1.53 ms | **30.48% → 1.23 ms** |
| Freehand, 1 boundary | 75.21% → 2.58 ms | 46.69% → 1.61 ms | **25.03% → 0.86 ms** |
| **Reagent** | 91.41% → 0.359 ms | 51.01% → 0.200 ms | **0.36% → 0.0014 ms** |
| floor | 86.25% → 0.153 ms | 6.87% → 0.012 ms | 0.49% → 0.0009 ms |

**Read the last column first.** Freehand spends 1.23 ms in React's
layout-effect commit loop. Reagent spends 1.4 **microseconds**. This is
not Freehand doing the same work slowly; it is Freehand doing work Reagent
does not do at all.

---

## 3. The ablation: is the cost per boundary?

`b6-witnesses-flat/u-grid-flat` declares the same 300 `v/sub` reads and
emits character-for-character the same DOM from **one** boundary instead
of 300. The element count, tags, attributes and text are identical — the
canonical-DOM gate proves it — so the pair varies exactly one thing.

| | 300 boundaries | 1 boundary | delta |
|---|---:|---:|---:|
| total ms/write | 4.0 [3.9–4.5] | 3.1 [3.0–3.2] | **−0.9** |
| React render | 1.53 | 1.61 | +0.08 |
| `cell/commit!` | 1.23 | 0.86 | −0.37 |
| write leg | 0.74 | 0.70 | −0.04 |

Two things fall out, and the second is the finding.

**The per-boundary constant is small.** 300 boundaries' worth of shell,
ViewCell, React component fiber and six hooks each costs ≈0.9 ms — about
3 µs a boundary, and ≈25% of the deficit.

**`cell/commit!` did not collapse — it only shrank by 30%.** Because its
cost is **O(dependencies), not O(boundaries)**: one boundary holding 300
dependencies stages 300 records, re-reads 300 nodes and allocates a
300-element `:observations` vector, exactly as 300 boundaries holding one
each do. Collapsing the boundaries moved the fixed per-commit overhead and
left the per-dependency work untouched.

**And the render leg did not move at all** (1.53 → 1.61). Whatever the
render costs, it is not the boundaries.

---

## 4. Why `cell/commit!` costs what it costs

Per committed render, for every dependency, `cell/commit!` (`cell.cljc`
§1107):

1. `stage!` reconciles the prior dependency set against this render's read
   order. On a broad write **every site is RETAINED** — same query, same
   target, same handle — and staging still allocates two maps per site to
   say so.
2. `readings` calls `obs/read` on every handle. This is a **second deref
   of every subscription**, after render already read it — and it is
   load-bearing, not waste: it is the invariant-5 tear check, comparing
   node-key, version and frame/registry epoch between the render that
   produced the element and the commit about to publish it.
3. The `bundle` allocates a 300-element `:observations` vector of five-key
   maps.
4. One `swap!` republishes `:deps`, `:dep-order`, `:frame`, `:lifecycle`
   and the evidence slot.

The dev-gated evidence plane (`commit-evidence`) is correctly absent from
the capture — `goog.DEBUG false` removes it, and it appears nowhere in the
profile.

So the commit phase's 1.23 ms is ~8 persistent allocations and one
subscription re-read per dependency, 300 times, serially, inside React's
commit. **Nothing in it is a mistake.** Every step buys a guarantee Spec
006 makes: all-or-nothing publication, rollback on a failed read,
abandoned-render safety, and tear detection.

That is what "architectural" means here — the cost is the guarantee.

---

## 5. What would have to change

Ranked. **None of these is a bounded optimisation, and all of them are
`cell.cljc`,** which PR #7133 currently holds — so this page changes no
code. Each is filed as its own bead.

1. **A retained-set fast path in `cell/commit!`** — the largest single
   lever, ≈1.2 ms of a ≈3.65 ms deficit. When staging acquires and
   releases nothing, the dependency set is structurally the prior one;
   the staged map could be republished by identity instead of rebuilt.
   The obstacle is step 2 above: the commit-side re-read is the tear
   check, so this is a change to *when the invariant is checked*, not a
   caching trick. It needs a spec conversation, not a patch.
2. **A mutable dependency ledger.** The ledger is persistent maps rebuilt
   per render; `array-index-of` alone is **10.65% self** of the whole
   capture, spread across a dozen callers with none above 2.76%, which is
   the signature of small persistent maps and sets being scanned
   everywhere. PR #7077 already showed the shape of the win — moving the
   memo stores to `js/Map` took `conversion/remembered` from 3.04% to
   0.75%.
3. **The per-boundary constant, ≈3 µs.** Six React hooks per boundary,
   two of them layout effects, one a `useSyncExternalStore`. Worth ≈25% of
   the deficit and the least tractable of the three, because each hook is
   named in the shell's contract.

   **Decision, 2026-07-27 (`rf2-dkcor`): the contract stands and this is
   not taken.** Every one of those six hooks is named normatively in
   `spec/006-ReactiveSubstrate.md` §The Freehand atomic shell and in
   `shell.cljs` — one `useSyncExternalStore` per view so N moving targets
   settle in one pass, the reconcile effect publishing each render's
   bundle, the lifecycle effect owning connect/disconnect. Removing one
   means weakening a stated guarantee, and the arithmetic does not
   justify it: §6's re-take puts the total at 4.8 ms against Reagent's
   ≈0.8, so the deficit is now ≈4.0 ms and the ablation's 0.9 ms is
   **≈22%** of it rather than 25% — *assuming the constant itself is
   unchanged, which has not been re-measured*. Twenty-two percent does
   not close a 10× gap, and it is the smallest of the three levers while
   being the only one that costs a contract. Recorded, not actioned.

Not on this list: the interpreted markup walk. `react/emit` is 0.47 ms of
4.05 — even a 30% cut is 4% of the deficit. #7077 already took 26% out of
that walk on the mount path; the update row is not where it lives.

### A known future delta, not measured here

**PR #7133 adds a second React commit per render batch** — a detached root
whose layout effect calls `cell/flush!`. Everything above is `main`
*without* it. The broad-update row is precisely where an extra commit pass
would show, because the commit phase is already 30% of this window. It
should be re-taken once #7133 lands.

---

## 6. The re-take, 2026-07-27 — the window moved (`rf2-huhno`)

#7133 has landed, and so have two changes that pull the other way:
`rf2-wxrrp` (`e5cf299fc2`, *fold the commit-side read, the tear check and
the bundle into one pass*) and `rf2-40kdm` (#7147, *split the render
candidate's ledger into two fields*). Re-taken against `main` at
`7c41225b4b`, which carries all three. Same instrument, same window, same
`--writes 500`, three rounds.

**The headline is not the totals. It is that the window RESTRUCTURED.**

| leg, p50 ms | §1 (pre-#7133) | re-take | |
|---|---:|---:|---|
| **total** | **4.0** [3.9–4.5] | **4.8** [4.6–4.9] | **+20%**, disjoint |
| write | 0.7–0.8 | 0.9–1.0 | +25% |
| **gap** (the microtask) | **~0.2** | **3.6–3.9** | **×18** |
| **force** (the empty `flushSync`) | **3.0–3.5** | **~0.0** | **collapsed** |

**Freehand's render has moved out of the measured `flushSync` and into
the microtask before it.** The empty `react-dom/flushSync` that used to
carry 3.0–3.5 ms now costs 0.0 — by the time it runs there is nothing
left to do, because #7133's flush already committed. Nothing has
disappeared; the same work is in the gap.

That matters beyond bookkeeping. §1's table and `b6-rows`' own commentary
both treat `:gap-ms` as a near-empty constant priced by the floor and
Reagent arms reporting `0.0`. **For Freehand that is no longer true**,
and a reader who takes the force leg as "Freehand's React time" will now
read 0.0 and conclude the substrate became free.

### The phase shares, same capture method

One 500-write capture, shares converted at the capture's own wall ÷
writes. Idle 0.07%, GC 1.92%.

| | §2 (pre-#7133) | re-take |
|---|---:|---:|
| capture wall ÷ writes | 4.055 ms | 5.148 ms |
| React **render** (`Kh`) | 37.70% → 1.53 ms | 39.44% → **2.03 ms** |
| React **commit** layout effects (`Wk`) | 30.48% → 1.23 ms | 26.77% → **1.38 ms** |

The commit phase's *share* fell (30.48% → 26.77%) while its *milliseconds*
rose (1.23 → 1.38). Both are true and the second is the one to quote: the
share fell only because the window around it grew. **#7133's extra commit
pass is visible and it costs about 0.15 ms**, which is roughly what an
extra pass over the tree should cost and is not the +0.8 ms the total
moved.

### The published interleaved row, and the ratio that did not move

`b6_prod_run.cjs`, six rounds, arms interleaved at the sample level.
**0 unverified writes.**

| broad update | published | re-take |
|---|---:|---:|
| Freehand interpreted, p50 | 5.4–7.4 ms | 6.9–9.0 ms |
| Reagent, p50 | — | 0.7–0.9 ms |
| floor, p50 | — | 0.2–0.3 ms |
| **Freehand ÷ Reagent** | **10.5×** | **9.9×** |

**The ratio is unchanged within this suite's noise — 10.5× against 9.9×
— and the absolute got worse.** The operator's standard is a comparison,
so the conclusion of this page stands exactly as written: the broad
update is ≈10× Reagent and it is architectural.

Note the `:ratio-to-floor` column is not quoted, and deliberately. The
broad floor's p50 is 0.2–0.3 ms — two or three of Chrome's 100 µs
quanta — so the published ratio steps ±33% between rounds and reads
33.44 [25.67–45.00] for a quantity the direct p50s put at ≈30. The
Freehand ÷ Reagent ratio above is taken from the p50s directly, which is
the sharp instrument at this scale.

### What the re-take does NOT settle

`rf2-huhno` also asked whether the row should be re-taken **without** the
microtask yield, on the reasoning that #7133 gives Freehand a synchronous
commit and the yield is now historical. **The measurement says the yield
is still load-bearing for this window, and dropping it is not the
tightening it sounds like:** the 3.6–3.9 ms now in the gap is Freehand's
notification and React's render and commit *running inside that
microtask*. Remove the yield and the work does not vanish — it either
moves into the `flushSync` or, if the notification has not yet fired,
lands after the measured window and the DOM read-back fails, which is the
exact fault `b6-rows` records from the first attempt at this row.

Deciding it needs the non-vacuity check `rf2-0fgth` added, run against a
yield-free window. Filed as `rf2-vxfjt`; it is a change to the published
window and does not belong in a re-take.

---

## Appendix: the W1 interpreted-mount discrepancy, resolved

Two numbers for "interpreted Freehand's W1 mount" were 57% apart and both
post-#7077, so one of them was misinforming a P0:

- **`rf2-xu6rx` / PR #7077** reports **1.904× floor** [1.755–2.020].
- **`rf2-dq20a`** reports **2.987× floor** [2.840–3.167], measured later.

**2.987 is the operative number. 1.904 is not comparable to it, and in
particular is not comparable to Reagent.**

**The direct evidence.** B6's instrument was re-run unmodified on `main`
on 2026-07-27: **W1 interpreted = 3.075 [2.947–3.286]**, Reagent = **1.518
[1.474–1.579]**. That is an independent reproduction of dq20a's
2.987/1.554 and is nowhere near 1.904.

**Why they differ — they are not measurements of the same thing.** Four
named differences, ranked by how likely they are to carry the gap. Which
one dominates is *not* established here; it is filed as its own bead.

1. **Different witness.** #7077's W1 is the `studio` fixture: **1,204**
   elements, a four-element skeleton (`section`/`h1`/`hr`/`ul#w1list`),
   and a row carrying `.w1row` sugar *plus* a vector `:class ["cell"
   "wide"]`, a **numeric** `:padding-left 4`, a `:title`, and a base64
   data-URI `src`. B6's W1 is **1,203** elements, a three-element
   skeleton, multi-class sugar only, `:padding-left "4px"`, no `:title`.
   Different attribute counts and different conversion paths.
2. **Different mount door.** The studio probe mounts every arm into ONE
   pre-existing shared frame (`:frame fid`). B6 passes no `:frame`, so
   `v/mount` creates a **fresh frame per sample, inside the timed
   `flushSync`** — substrate cost the floor never pays, on every B6 sample
   and on none of #7077's.
3. **Different floor.** #7077's floor arm measured **5.2 ms** [4.7–5.7]
   absolute for W1; B6's floor measures **2.4 ms** for a same-sized page.
   A ratio is only as meaningful as its denominator, and these denominators
   differ by ~2×.
4. **#7077's harness has no Reagent arm at all.** It compares interpreted
   Freehand to a hand-built React floor and to the compiled tier. 1.904
   was never a Freehand-versus-Reagent number.

**Neither bead did anything wrong.** #7077's claim is a *before/after on
one instrument* — "the six cuts took the interpreted walk from 2.568 to
1.904 on its own witness, alternated, ranges disjoint" — and nothing here
disturbs it. What is wrong is only the transfer: quoting 1.904 as where
interpreted mount stands relative to Reagent.

**So the P0 should carry: interpreted W1 mount ≈ 2.99–3.08× floor, ≈1.9–2.0×
Reagent.**

Difference 2 is worth its own measurement rather than a mention, because
if per-sample frame creation is material then B6's *mount* row overstates
the substrate's per-mount cost — and the mount row is also on the release
gate.

## Provenance

- Fixture: the published update grid — 300 reactive cells, 301 elements,
  one `v/sub` per cell; broad write = one `frame/replace-app-db!` every
  cell reads.
- Window: `b6-rows/timed-write!`, unmodified.
- Sampling: 60-write warm-up outside the capture; 500 writes (Freehand
  arms) or 2,000 (Reagent, floor) inside it; CPU profile at a 100 µs
  sampling interval over CDP.
- Wall-clock rows: three independent rounds per arm, ranges quoted.
- Gates green before any number was read: canonical-DOM parity of the
  ablation arm against the reference arm; per-write DOM read-back, **0
  unverified of 8,500**; positive control 32.23% against a predicted
  33.0%; profile/clock agreement within 1%.
- Build: `:advanced`, `goog.DEBUG false`, plus `:pseudo-names true` for
  the profiled twin, via `--config-merge` over the existing
  `:freehand-release` build id. `implementation/shadow-cljs.edn` is
  unchanged.
- Source, shipped rather than deleted so the next bead does not have to
  recover it from a deleted branch:
  `implementation/freehand/test/re_frame/freehand/bench/b6_profile_app.cljs`
  (the one-arm driver and the positive control),
  `b6_profile_run.cjs` (build, serve, CDP capture),
  `b6_profile_report.cjs` (offline self / inclusive / caller / callee
  attribution) and `b6_witnesses_flat.cljc` (the ablation witness).
- Reproduce:
  `node implementation/freehand/test/re_frame/freehand/bench/b6_profile_run.cjs --arm freehand-interpreted --writes 500`
  then
  `node implementation/freehand/test/re_frame/freehand/bench/b6_profile_report.cjs implementation/out/b6-profiles/freehand-interpreted.cpuprofile --under '^Kh$' --under '^Wk$'`.

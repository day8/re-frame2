# Does compiled Hiccup earn its permanent product cost?

Seat: EVIDENCE SPIKE. Bead `rf2-lnecd`, executing the measurement half of the
operator ruling of 2026-07-26.

Measured: 2026-07-26, against `a0be49a31d` (branch `worker/compiled-worth-it`).
Host: Windows 11, Chromium via Playwright 1.59.1, single developer workstation
with six other agents running concurrently. Nothing here is a release-worker
number, and the [method](#method-and-what-it-refuses-to-claim) says where that
matters.

This document is the direct successor to
[`er-01-architecture-comparison.md`](er-01-architecture-comparison.md), whose
verdict — `v/$` stays a non-goal — stands untouched. ER-01 named one avenue
that could reopen the performance half of the question: *"the React host is not
measured here… Production render cost lives in `emit-react`, and a large
asymmetry there would reopen the performance half."* This is that measurement.

---

## The recommendation, first

**KEEP — but stop describing it as a performance tier, and stop paying for it
on views that cannot use it.**

The compiled tier earns its cost on exactly one axis, and that axis is narrower
than the tier is. It is **not** faster Hiccup interpretation in any way that
matters to an ordinary application. It is **ViewCell elision**, and elision is
worth having, because it is the one thing in this system that no amount of
interpreter tuning can reach: eliding a reactive cell requires *proving* a body
has no reactive site, and proof requires analysis.

Four findings carry that, and the third is the one that would change most
readers' minds.

1. **On an ordinary form, a tuned interpreter is indistinguishable from
   compiled.** Across five interleaved rounds the interpreted/compiled ratio on
   the form witness is **1.054**, with a spread (0.846–1.222) that straddles
   1.0. Before the tuning it was 1.317. There is no performance argument for
   compiling a form.
2. **Where compiled wins, it wins by elision, not by compilation.** On 300
   sub-free boundaries compiled is **3.35×** the tuned interpreter; on 100
   *reactive* boundaries — where neither tier may elide — it is **1.22×**. Same
   substrate, same walk, same element counts. The whole difference between those
   two numbers is the ViewCell.
3. **The compiled tier makes the bundle bigger, not smaller, and it cannot
   remove the interpreter.** Promoting three views cost **+18,182 raw / +5,093
   gzipped bytes**, and every sentinel of the interpreted walk is still
   `PRESENT` in a fully-compiled bundle. There is no configuration in which a
   consumer ships the compiled tier *instead of* the interpreted one; the
   compiled tier is strictly additive to what already ships.
4. **The static-analysis products have almost no consumers, and the one clear
   defect the analyzer caught exclusively was closed by teaching the interpreter
   to catch it too.** Details in [requirement 6](#6-what-the-static-manifests-are-actually-worth).

Against that sits a real, verified, and frequently-overstated asset: **the
compiler itself does not ship.** Its 10,276 lines are macro-time, proven absent
from a production bundle against a positive control. The permanent cost is
therefore paid almost entirely in *source, tests, spec and feature-development
tax* — not in bytes a user downloads.

**What KEEP should not be read as saying.** The measured performance case for
compiled Hiccup, on the shapes an application actually contains, is thin enough
that it should stop being the reason the tier exists. If elision is the asset,
then elision is what the roadmap should be about, and the parts of the compiled
tier that are not elision should be priced separately the next time they ask for
budget. That is a scoping observation, not an instruction: **nothing here should
be removed or demoted on the strength of this report.**

---

## Method, and what it refuses to claim

Every number below was produced by an `:advanced` ClojureScript bundle with
`goog.DEBUG false` — the artefact a consumer ships — served over loopback and
driven in a real Chromium. The scaffolding that produced it was deleted in the
same PR that added this page; it is recoverable from git (see
[Provenance](#provenance)).

**What was measured, per mount.** `react-ms` brackets `react-dom/flushSync`
around the mount, so it contains the substrate's element construction *and*
React's render, commit and DOM mutation, with nothing scheduled out of the
window. `layout-ms` is a forced synchronous reflow taken immediately after.
`settle-ms` is their sum: the *work* a mount costs.

**Three things the harness refuses to do, each because a first pass got it
wrong.**

- **`frame-ms` is published but is not the settlement figure.** Time-to-next-
  animation-frame is quantised to the display's refresh interval, so it reports
  the vsync wait rather than the page: every arm, from the 51-element form to
  the 1,204-element table, returned ≈15 ms. A settlement claim built on it would
  have said all six arms cost the same.
- **The forced reflow had to be given a live consumer.** The first run reported
  `layout-ms` as exactly `0.000` in every arm of every sample, because Closure
  is free to drop a property read whose value nothing uses — so the layout it
  was taken to force never happened.
- **Parity is compared on canonical DOM, not `innerHTML`.** The first run
  reported two witnesses as producing *different pages*. They did not: the two
  front ends write props in different orders, and `innerHTML` preserves
  insertion order. Sorting attribute names compares the DOM instead of the
  serialiser, and all four witnesses then agree exactly.

**Determinism first.** Every witness gates on element count and canonical-DOM
equality across all of its arms before any timing is read. All four pass:
W1 1204/1204/1204, W2 301/301/301, W2r 101/101, W3 51/51, `agree? true`
throughout.

**Wall clock is quoted only where the spread supports it, and the calibration
is in-run.** This box is noisy: across five rounds the **floor arm — which
contains no Freehand code at all and which no change in this report can
touch — moved from 4.3 ms to 5.9 ms, a 37% drift.** That is larger than most of
the effects being measured. Absolute milliseconds are therefore *not* comparable
between rounds, and no cross-round absolute claim is made. Every comparative
claim is a **ratio to the floor measured in that same run**, which is the same
work in every run and so serves as an in-run calibrator. Where even the ratios
overlap, the report says so and declines the number — the refusal ER-01 made and
this report copies.

### The React floor

The single most useful instrument here is an arm that is not an arm: **the same
DOM, built by hand with `react/createElement` and no Freehand at all** — no
boundary, no ViewCell, no shell, no props map, no attribute walk, no conversion
table. It has no state, no events and no identity, so nobody could ship it. That
is the point: it is the irreducible cost of asking React to build this page, so
`interpreted − floor` and `compiled − floor` are the substrate's own overheads
rather than two numbers dominated by a reconciliation both arms pay identically.

The floor is gated on canonical-DOM equality with the interpreted arm, so a hand
transcription that drifted fails rather than flatters itself.

---

## 1. The witnesses, and what each mount costs

Fixture: W1 = 300 rows (1,204 elements, one boundary per row, rich attributes);
W2 = 300 sub-free leaf boundaries; W2r = 100 *reactive* leaf boundaries; W3 = a
12-field form with controlled inputs, per-field error lines and a derived submit
gate (51 elements). 5 warm-up + 30 samples per arm, arms interleaved, five
alternating rounds.

**Mount cost as a ratio to the in-run React floor** (mean of five rounds; range
across rounds in brackets):

| Witness | current interpreted | tuned interpreted | compiled |
|---|---:|---:|---:|
| **W1** large template, 1,204 el | 3.271 [2.907–3.426] | **2.747** [2.543–2.932] | **1.132** [1.096–1.186] |
| **W2** 300 elidable boundaries | 8.180 [7.400–9.250] | **7.320** [6.600–7.750] | **2.183** [1.800–2.500] |

W2r and W3 have no floor arm — a floor for a view that reads state would have to
read state, and would not be a floor — so they are reported as the ratio between
the two tiers directly:

| Witness | interpreted ÷ compiled, current | interpreted ÷ compiled, tuned |
|---|---:|---:|
| **W2r** 100 reactive boundaries | 1.211 [1.095–1.292] | 1.216 [1.143–1.280] |
| **W3** ordinary form, 51 el | 1.317 [1.167–1.417] | **1.054** [0.846–1.222] |

**Read W2 against W2r before anything else.** They are the same substrate, the
same walk, the same leaf shape and nearly the same code. The only difference is
that W2's leaves read no state and W2r's do. Compiled beats the tuned
interpreter by **3.35×** on the first and **1.22×** on the second. Compilation
did not get faster between those two rows; **elision stopped being available.**

**W3 is the shape most applications are made of**, and after one bounded tuning
the two tiers are inseparable there: 1.054 with a range that includes 1.0.

**Updates are a null result and worth stating as one.** A state change through
to a committed DOM measured 0.2–0.5 ms p50 on every arm of every witness, with
the two tiers indistinguishable at the timer's resolution. Whatever the compiled
tier buys, it does not buy re-render speed on these shapes.

### React render/commit, and total settlement

For W1 at the round-3 readings — the round whose floor sat mid-range — the
decomposition was:

| arm | react-ms p50 | layout-ms p50 | settle-ms p50 | frame-ms p50 |
|---|---:|---:|---:|---:|
| interpreted | 15.9 | 3.6 | 19.6 | 25.8 |
| compiled | 5.7 | 3.7 | 10.3 | 15.2 |
| floor | 5.6 | 3.7 | 9.3 | 13.9 |

Two things to take from the shape rather than the magnitudes. **Layout is
identical across all three arms** (3.6/3.7/3.7) — it is a function of the DOM,
and all three built the same DOM, which is what the parity gate proves. And
**the compiled arm sits within 2% of the floor on `react-ms`** while the
interpreted arm sits at 2.8× it. On this witness the compiled tier has
essentially eliminated the substrate from the mount.

### Allocation and GC indications

`performance.memory.usedJSHeapSize` under `--enable-precise-memory-info`, as a
delta across one mount. This is heap **occupancy**, not a counter: a collection
inside the window makes the delta smaller than the bytes really allocated and
can make it negative, and negative readings were observed. It is published as an
indication and gates nothing.

| arm | heap delta p50 | vs floor |
|---|---:|---:|
| W1 interpreted | 10.65 MB | 19.3× |
| W1 compiled | 1.09 MB | 2.0× |
| W1 floor | 0.55 MB | 1.00 |
| W2 interpreted | 4.34 MB | 22.4× |
| W2 compiled | 0.64 MB | 3.3× |
| W2 floor | 0.19 MB | 1.00 |

The direction agrees with ER-01's JVM allocation finding and with the CPU
profile, and the ratios are far larger than the timing ratios. The garbage
collector took 2.9–4.2% of samples in the interpreted profiles. No percentage
claim is made from occupancy sampling.

---

## 2. Shipped mode versus equal semantics

Requirement 3 exists because ER-01 was nearly misled by exactly this: raw `v/$`
measured 20% better than compiled, and the entire gap was the duplicate-key
proof `v/$` was skipping. Restoring it inverted the result to 1.011 in the
compiler's favour. So the question here is: **in a production browser, where
does the compiled tier do work the interpreted tier does not?**

**The answer inverts ER-01's, and it is the reason no correction is applied
above.** The compiled tier's browser duplicate-key proof is `goog.DEBUG`-gated:

```clojure
;; compiler/emit_react.cljc, emit-for
(when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
  (re-frame.freehand.compiled-react/check-key! ~seen ~row-key))
```

Verified in the artefact rather than assumed. Counting the shared refusal string
`"Duplicate key "` across bundles built from the same source:

| bundle | `goog.DEBUG` | occurrences |
|---|---|---:|
| production probe | `false` | **1** |
| dev probe | `true` | **2** |

The surviving occurrence in production is `node/keyed-run` — identified by the
adjacent `"compiled keyed run carries one key per row"` text, which is
`keyed-run`'s own and not `check-key!`'s. So in a production browser
`check-key!` is eliminated, and **neither tier proves key uniqueness**. The
production arms are already doing equal work on keys; the shipped-mode number
*is* the equal-semantics number, and applying an ER-01-style correction here
would be inventing one.

**In a development build the asymmetry is real and runs the other way.** With
`goog.DEBUG true` the compiled tier does strictly more:

| witness | production (tuned) | dev |
|---|---:|---:|
| W1 compiled ÷ floor | 1.132 | 1.318 |
| W2r interpreted ÷ compiled | 1.216 | 1.089 |

The compiled arm gets relatively worse in dev on both readings. **This is not
attributable to `check-key!` alone** and is not offered as such: `goog.DEBUG`
also turns on the Spec 009 instrumentation seam, schema validation and trace
emission. It is reported as what a developer experiences, not as a measurement
of the key proof.

The JVM structural tier is unaffected by any of this — `node/keyed-run` is
unconditional there — so ER-01's finding remains correct for the host it was
measured on. The two hosts genuinely differ.

---

## 3. Profiling the interpreted path, and the one bounded tuning

Requirement 4's order is binding — profile first, at most one optimisation, only
if obvious — and the operator's later sharpening made the reason explicit:
comparing against an un-optimised interpreter biases the answer toward KEEP by
construction.

### The instrument

A CDP CPU profile (100 µs sampling) over the production bundle, built a second
time with `:pseudo-names true` so `:advanced` output keeps readable function
names. The first attempt profiled the whole suite and was useless for
attribution: the arms interleave and the animation-frame waits between them put
**43% of samples in `(idle)`**. The profile below runs one arm in a tight
synchronous mount/unmount loop with no harness work in the window.

### Where the interpreted path's time goes

Samples bucketed by the frame's own name, W1, 150 iterations:

| bucket | interpreted | compiled | floor |
|---|---:|---:|---:|
| react + engine | 31.6% | 34.7% | 38.8% |
| **substrate: cljs.core / clojure.string** | **30.9%** | 7.0% | 1.4% |
| **substrate: freehand / re-frame** | **9.5%** | 4.1% | 1.2% |
| substrate: goog | 2.1% | 0.0% | 0.1% |
| host: DOM/native | 4.9% | 9.3% | 10.5% |
| garbage collector | 2.9% | 3.7% | 4.3% |

**Substrate total: interpreted 42.5%, compiled 11.1%, floor 2.6%** (the floor's
2.6% is attribution slop — it contains no substrate). So on this witness the
interpreted arm does roughly **thirteen times** the substrate work the compiled
arm does, and that is the honest size of the interpretation cost.

### Self time named the function; only caller attribution named the fix

The largest named frame in the interpreted profile was
`clojure.string/replace` at **5.08%** — the biggest single named cost in the
walk. The obvious reading was `react-style-name`, a `str/replace` over a regex
that `conversion.cljc` calls once per style entry per element, and which is the
*only* one of that namespace's four projections not memoised while its own
§Remembered projections comment argues at length for memoising the other three.

**That reading was wrong, and taking it first was a methodological error worth
recording.** Memoising `react-style-name` left `clojure.string/replace` at
5.89%. Caller attribution — walking the profile's parent chain rather than
reading self time — found the real path in one step:

```
5.82%   $re_frame$freehand$rules$react_prop_name$$
     <- $re_frame$freehand$rules$caller_key_slot$$
     <- (anon)  <- $cljs$core$some$$
```

`controlled/controlled-props?` is `(some #(controlled-slot? (prop-slot %))
attr-keys)`; `prop-slot` delegates to `rules/caller-key-slot`, which calls the
**uncached** `rules/react-prop-name`; and the interpreted walk asks
`controlled-props?` **twice per element** — once in `react-props` and once for
the `put-caller!` fold. An ordinary four-attribute element re-derived eight slot
names per render; a 300-row keyed run re-derived 2,400.

The speculative change was reverted and the profile-named one taken instead:
`caller-key-slot` now memoises through a bounded cache keyed on the author name,
with the nil answer kept outside the cache. It is the same argument, and the same
remedy, that `conversion.cljc` already makes for the `.class#id` parse and the
React prop and handler names.

**Effect, confirmed two ways.** `clojure.string/replace` disappears from the
profile's top frames entirely, and the `goog` bucket falls from 2.06% to 0.64%.
On the clock, floor-normalised across five alternating rounds:

| | current | tuned |
|---|---:|---:|
| W1 interpreted ÷ floor | 3.271 [2.907–3.426] | 2.747 [2.543–2.932] |
| W1 **compiled** ÷ floor | 1.166 [1.116–1.213] | 1.132 [1.096–1.186] |

The W1 interpreted ranges barely overlap (current min 2.907 vs tuned max 2.932),
which is as strong as this box supports. In substrate-overhead terms —
subtracting the floor — the interpreted arm's own cost fell from 2.27× to 1.75×
the floor, a **≈23% reduction**. The compiled row is the control: it moves
within its spread, as predicted, because W1's attribute slots are literal and
the compiler resolved them at build time.

The largest single beneficiary is W3, the form — 1.317 → 1.054 — which is
exactly where the controlled-input door runs.

### Headroom the one change did NOT capture

The cap of one optimisation is a scope control on this bead, not a claim that
one change exhausts the headroom. It does not. After the change, **substrate is
still 36.7% of the W1 interpreted mount.** Named specifically, from caller
attribution on the post-change profile:

- **The memo caches themselves — 1.71%.** The largest remaining single caller of
  `cljs.core/array-index-of` is `conversion/remembered`: the caches are
  persistent Clojure maps in atoms, so every hit is a `PersistentArrayMap`
  linear scan. A mutable `js/Map` on the ClojureScript side would remove most of
  it. The cache added by this report has the same shape and the same cost.
- **`top-layer/present?` via `contains?` — 0.42%**, on every element.
- **`react-style-name`, still uncached** — the candidate reverted above. Its
  measured contribution was under 1%, but it is genuinely uncached and its three
  siblings are not.

Those name maybe 3–4 points of the remaining 36.7. **The bulk is not addressable
by caching**: it is the walk itself — building a props object per element,
classifying every child, composing classes, and constructing a persistent
structure per node — which is intrinsic to deciding at runtime what a form
denotes. A reasonable estimate is that a determined optimisation campaign could
take the interpreted arm from 2.75× floor to perhaps 2.2–2.4× on W1, and would
not approach compiled's 1.13× **because the remaining gap on that witness is
substantially elision, not interpretation** — W1 has 300 elidable row
boundaries, and W2r shows what the gap looks like when elision is off the table.

That last point is the one that matters for the verdict: **the interpreter is
not near its ceiling, but closing the remaining distance would not close the
gap, because the gap is not all interpretation.**

---

## 4. Bundle reachability

Three bundles, string-literal sentinels, and a positive control — because both
naive oracles have been measured wrong in this repository (a debug flag survived
225 times inside prose; Closure inlines small functions, so a name grep can
never go red).

| sentinel | interpreted-only app | compiled-only app | control |
|---|---|---|---|
| interpreted walk — child classifier | PRESENT | **PRESENT** | PRESENT |
| interpreted walk — void-element refusal | PRESENT | **PRESENT** | PRESENT |
| interpreted walk — namespaced-tag refusal | PRESENT | **PRESENT** | PRESENT |
| compiler — analyzer refusal | **absent** | **absent** | **PRESENT** |

The control is a strict superset of the compiled app that additionally *reaches*
the analyzer at runtime. Its `PRESENT` is what gives the two `absent` cells
meaning: they are a DCE result, not a broken grep.

**Two findings.**

**The compiler does not ship.** 10,276 lines of analysis, grammar, emission and
diagnostics are macro-time and cost a user zero bytes. This is a genuine and
significant point in the tier's favour, and it is now proven rather than assumed.

**The compiled tier cannot remove the interpreter, and adds to it.** Every
interpreted-walk sentinel is present in a bundle whose every view is compiled —
`compiled_react.cljs` reaches back into `re-frame.freehand.react` for the
attribute writers, the child classifier and the boundary mount, and
`component-for` references both wrappers. And the matched pair, which differ
only in three `{:compiled true}` markers:

| bundle | raw | gzip -9 |
|---|---:|---:|
| interpreted-only | 723,691 | 215,630 |
| compiled-only | 741,873 | 220,723 |
| **delta for 3 promoted views** | **+18,182** | **+5,093** |

**+6,061 raw / +1,698 gzipped bytes per promoted view.** Compiling a view makes
the artefact larger. There is no bundle argument for the compiled tier; there is
a bundle argument against it, and it scales with adoption.

---

## 5. The product-cost ledger

Counted, not estimated. ER-01's 10,276-line figure was re-derived rather than
inherited, and it is **exactly right**.

| Item | Size | Note |
|---|---:|---|
| Compiler source (`compiler/` + `compiler.cljc`) | **10,276 lines** | 14 namespaces; macro-time, ships nothing |
| Compiled browser runtime (`compiled_react.cljs`) | 299 lines | ships |
| Compiler-facing tests | **12,974 lines** / 62 files | analyze, check, a11y, manifest, emit, parity, grammar, lowering |
| The separate grammar (`spec/004D`) | **2,474 lines** | its own normative document |
| Conformance fixtures | 14 of 97 | the `fh-diag-*` / `fh-struct-*` families |
| Documentation | 32 files | mention `{:compiled true}`, the tier, or 004D |
| Build integration | 1,856 lines | `build_hook.clj`, `build.cljc`, `harvest.clj` (within the 10,276) |
| Diagnostics | **83 distinct `:rf.ui.compile/*` ids** | ~4 a11y warnings, ~3 template-shape warnings, the rest hard build refusals |

For proportion: the whole of `implementation/freehand/src` is 35,480 lines, so
**the compiler is 29% of the substrate's source** — and it produces no runtime
behaviour a user's browser executes. Its tests are 20% of the freehand test tree
(12,974 of 66,360).

There is no single place the 83 diagnostic ids are enumerated. The nearest
surfaces are `grammar/id-recoveries` (21 ids with recovery ladders),
`a11y/a11y-warning-ids` (4), and prose tables in 004D covering the 7 warnings.

### The feature-development tax

This is the part a line count misses, and the D022 `v/defhost` slice that merged
today prices it exactly. Adding **one** view-language feature required, in one
commit (`c706fb01f2`):

```
compiler/analyze.cljc   +32     compiler/env.cljc   +22
node.cljc               +60     react.cljs         +109     tree.cljc  +62
```

— three test files renumbered alongside. And the compiled arm's answer was not a
lowering. It was a **new build refusal**, `:rf.ui.compile/host-crossing-
unsupported`:

> "compiled view mounts the declared host … — the compiled tier cannot yet lower
> a `v/defhost` crossing … accept the view and fail at render instead."

So the tax has a floor and a ceiling. The floor: **every new feature must be
classified by the analyzer and either lowered or explicitly refused, with a
stable diagnostic id and recoveries** — the compiler can never simply not know
about a feature. The ceiling: when lowering is genuinely hard, the feature ships
*worse* in the compiled tier than in the interpreted one, and the compiled tier
becomes a reason a user cannot use a feature they have paid for in every other
sense.

`rf2-drpa3.182.6` — "Publish trustworthy `v/check`" — is a P1 that remains
**open and paused**. The parity obligation is permanent and symmetric: every
behaviour needs a proof that both emitters agree, on both hosts, and the
conformance corpus is the shape of that obligation.

---

## 6. What the static manifests are actually worth

Requirement 6 asks for **real** consumers and defects caught **exclusively**, not
hypothetical benefits. This section is the one most likely to decide the
question, and its findings are the least comfortable.

### The consumers, one by one

| Candidate | Verdict |
|---|---|
| **`v/manifest`** — the public door | **REAL, but the consumer is the framework itself.** 9 non-test call sites in `src/`, 3 of them the bench harness. The load-bearing one is `react.cljs:637`, where `(not= :elided (:view-cell …))` chooses the wrapper — i.e. elision. 88 further call sites live across 25 **test** files. **Zero** reads in `examples/` or `testbeds/`. |
| **`tools/xray`** | **DOES NOT CONSUME THE FREEHAND TIER.** Xray's view-evidence panel is real and wired, but it requires `re-frame.ui.tool` — the *donor* `re-frame.ui` compiler, a different tier. `tools/xray/src/` contains no `re-frame.freehand` reference at all. |
| **`tools/story`** | **TEST-ONLY, and against the donor tier.** `tools/story/src/` contains zero manifest reads; the consumer lives under the `:test` alias and its own docstring says the shipped jar must not depend on it. |
| **`v/check`** | **DOES NOT EXIST as a public verb.** The real checker is `compiler/check.cljc`; every caller is a test. No `deps.edn` entry point, no CI job, no script, no MCP tool, no re-export. Making it trustworthy is `rf2-drpa3.182.6` — open and paused. |
| **conformance census** | **TEST-ONLY by design** — it is a conformance surface, which is the correct place for it. |
| **`FH-STRUCT-010` per-site facts** | **REAL, one production reader**: `tool.cljc`'s `read-view-event-sites`. Worth noting these facts were recorded by the analyzer and *silently dropped by the manifest projection* until `rf2-z0blg` — an event-site read could say where a view dispatches from but not what it dispatches. |
| **MCP / Tool-Pair** | **REAL — the only cross-process consumer.** `read-view-manifest` is a live, registered, shipped tool. **But it works for interpreted views too**, returning `:basis :opaque` with `{:reason :no-static-analysis}`, and there is a dedicated conformance fixture for exactly that arm. The tool exists for both tiers; only the payload is compiled-only. |

**No application in this repository declares a `{:compiled true}` view.** Every
occurrence under `implementation/*/src/` is the bench corpus or compiler
internals. `examples/` and `testbeds/` contain none —
`examples/ui/minimal-counter/shadow-cljs.edn` says so outright. There is
therefore **no in-repo evidence of the analyzer refusing a defect in shipping
application code**, because there is no shipping application code in the
compiled tier.

### Defects caught exclusively — and what happened to the best one

The strongest case, and it is a real one, is **`rf2-drpa3.163`**, found by the
F5h virtual-table pilot *on the first try*:

```clojure
(v/defview meta-keyed [{:keys [xs]}]
  [:ul (for [x xs] ^{:key x} [:li x])])
```

Interpreted, this rendered and **the key was silently gone** — no `:key`, no
`:keyed?`, no diagnostic; the only symptom a React console warning and a
reconciler reusing the wrong rows. Compiled, the identical source was a hard
build failure. For anyone arriving from Reagent, where `^{:key …}` *is* the
spelling, that is a migration trap with a delayed, misattributed symptom. The
compiled tier caught something the interpreted tier could not see at all.

**And then the fix closed the gap from the other side.** The interpreted walk now
refuses the same spelling — `node/refuse-metadata-key!`, called from
`react.cljs`'s child fold, raising *"the SHARED sentence the structural walk
raises"*. So the defect that was caught exclusively by compilation is no longer
caught exclusively by compilation. It is caught by both, because the remedy was
to teach the interpreter.

That pattern is the honest summary of this requirement. The other pilot-found
items (`rf2-drpa3.161`, `.164`, `.127`, `.126`, `.123`, `.122`, `.121`) are
defects **in** the compiled tier surfaced by exercising it, not defects it caught
in application code. And the a11y roster's one real outing on real source
produced *three findings and three suppressions with honest reasons* — commit
`e53d228ae1` — and zero accessibility bugs fixed; every `^{:rf.ui/suppress …}`
in the repository is in a test fixture or a docstring.

Set against that, one manifest cost is currently **open**: `rf2-12g5z` measured
the structural manifest payload shipping into `out/freehand-release/main.js`
under `:advanced` with `goog.DEBUG false` — `view-cell`, `crossings`,
`html-sites` and `source-coord` entries, including **eight occurrences of an
absolute build-machine path**. The analysis products are not merely
under-consumed; some of them are shipping.

**The finding, stated plainly.** The manifest's one indispensable consumer is
the runtime's own wrapper selection — that is, elision. Every other consumer is
a test, a paused bead, a tool pointed at a different compiler, or a tool that
degrades gracefully to `:opaque` for interpreted views. A benefit no consumer
uses is not a benefit, and on this evidence the *static-evidence* case for the
compiled tier is much weaker than the elision case it is usually bundled with.

---

## What would change the answer

1. **A real compiled application.** Every performance number here comes from
   purpose-built witnesses, because the repository contains no application that
   uses the tier. A large compiled application would measure adoption effects —
   cache behaviour across many declarations, bundle growth at scale, whether the
   refusals obstruct ordinary work — that six witnesses cannot.
2. **Elision on the interpreted tier, by some route other than proof.** The
   verdict rests on elision being unreachable without analysis. An opt-in
   author declaration (`{:reactive false}`, checked at runtime rather than
   proven) would take most of the compiled tier's measured advantage away at a
   tiny fraction of its cost. Whether that trade is acceptable is a design
   question this report does not answer and did not investigate — but it is the
   single change that would most undermine KEEP, and it should be considered
   before the tier gets more budget.
3. **A pinned release worker.** Every timing here is floor-normalised precisely
   because this box drifts 37% on an arm that cannot change. A quiet machine
   would turn several of the directional claims into numbers.
4. **Consumers actually arriving.** If Xray were pointed at
   `re-frame.freehand.tool`, if `v/check` shipped as a public verb, and if the
   per-site facts reached a tool an author uses daily, requirement 6 would read
   very differently. All three are plausible and none has happened.

## Provenance

- Fixtures: W1 300 rows / 1,204 elements; W2 300 sub-free boundaries; W2r 100
  reactive boundaries; W3 12-field form / 51 elements.
- Sampling: 5 warm-up + 30 samples per arm per run, arms interleaved; five
  alternating current/tuned rounds with a rebuild between each.
- Determinism gates: element count and canonical-DOM equality across every arm
  of every witness, green before any timing was read.
- Builds: `:advanced`, `:infer-externs :auto`, `goog.DEBUG false`, cleared build
  cache; a `:pseudo-names` twin for profiling and a `goog.DEBUG true` twin for
  the dev reading.
- Profiling: Chrome DevTools Protocol `Profiler`, 100 µs sampling, one arm per
  profile in a tight mount/unmount loop.
- Suites, on the scaffolding-free tree: see the PR's `## Quality gates`.
- Scaffolding: commits on `worker/compiled-worth-it`, reverted before merge —
  three witness namespaces, a hand-written React floor, a Playwright probe
  driver with CPU-profile and caller-attribution modes, an alternating A/B
  runner, a compiler-reachability control app, and five shadow-cljs build ids.
  Recoverable from git if this question is reopened.
- The one change that is NOT scaffolding and ships with this report: the
  `caller-key-slot` memoisation in
  `implementation/freehand/src/re_frame/freehand/rules.cljc`.

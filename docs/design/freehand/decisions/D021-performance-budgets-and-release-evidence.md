# D021 — Performance budgets and release evidence

Status: **Open**

Horizon: **Upcoming**

## Decision required

Decide whether Freehand’s timing and byte measurements ever become numerical
release gates, which deterministic properties do gate, which workloads and
baselines are published, and what evidence is required for alpha, donor deletion,
and beta.

The compiled tier is a settled product capability. This decision does not reopen
whether compilation exists, create an automatic promotion quota, or require
compiled code to beat interpreted code in every workload. It determines how the
project distinguishes measured capability from attractive estimates.

The absorption ruling is also fixed: `re-frame.ui` is a donor, its useful
analyzer/emitters/ViewCell/presence/manifest/diagnostic/test machinery moves into
Freehand, and the standalone artifact is deleted when the internal conformance
table is green and the component/library pilots pass. That is a gate, not a date.
Performance policy may make pilot evidence concrete; it must not turn deletion
into a calendar promise or re-create two competing products.

## The problem

Both designs name plausible performance regimes:

- a node-heavy template where interpretation/walking dominates;
- a mass mount of many ViewCells, especially sub-free library leaves where the
  compiled tier can elide the cell;
- a very wide keyed parent where generated prop comparators may matter;
- a controlled input whose synchronous frame flush couples keystroke latency to
  unrelated dirty work;
- bundle/parse overhead from shipping interpreted and compiled machinery in one
  artifact.

The Fable dossier contains estimates for several constants and correctly labels
them estimates. The Codex spine says release budgets should be set from a small,
repeatable harness before beta. Neither design supplies measured Freehand numbers,
because the substrate does not yet exist.

Choosing thresholds now would be folklore. Waiting until optimization is complete
would be equally weak: placement rules and architecture could solidify around
unmeasured assumptions. The policy needs an early harness and later calibrated
gates.

## Constraints already settled

- Optimize granularity, dependency fan-out, and windowing before compiling a
  remaining hot boundary.
- Compilation is manually selected with `v/defview {:compiled true}`; the
  framework does not auto-promote by percentage or benchmark threshold.
- The same workloads must compare equal structural/browser results, not merely
  timing.
- Performance evidence keeps stable view id/source/cause/node/read counters
  across demotion and promotion.
- Controlled-input correctness—no dropped characters, stable caret and IME—is
  not tradable for a favorable latency chart.
- React reconciliation or a foreign widget may dominate. The report must not
  attribute that cost to Hiccup interpretation.
- Debug/tool code is development-only and production-elided; measurement must
  include both instrumented and production builds where relevant.

## Required benchmark matrix

The two designs converge on five minimum workloads. Use stable identifiers so
every claim can cite a result rather than paraphrasing it.

| ID | Workload | Comparisons | Required measurements | Decision informed |
|---|---|---|---|---|
| B1 | one boundary renders a finite 10³–10⁴-node template | interpreted Freehand vs compiled Freehand | p50/p95 self time, per-node cost, allocation, equal tree/DOM | interpretation walk constant and useful promotion regime |
| B2 | cells-shaped mount storm, approximately 26×100 boundaries with three reads each; add a sub/event/presence/host-free arm | interpreted, compiled, and compiled-with-cell-elision | mount time, per-boundary decomposition, retained objects, omitted-cell count | ViewCell fixed cost and capability-elision value |
| B3 | 10,000 keyed rows with `rf=`-stable props; repeat with a window of about 40 under updates | generic vs generated comparator; interpreted vs compiled where meaningful | parent render time, skipped/committed rows, comparator time, end-to-end latency | whether comparator specialization matters after windowing |
| B4 | real-browser controlled input/selection/IME harness under an unrelated 20 Hz dirty sibling | interpreted and compiled input sites, production scheduling | p50/p95/p99 event-to-commit, coupled dirty work, dropped input, caret/composition correctness | synchronous-door latency and background-work coupling |
| B5 | representative interpreted, compiled, and mixed production bundles | one artifact with capabilities varied by reachability | gzip, parse/eval, initial mount, per-promoted-view delta, reachable runtime modules | shipped cost of the two-mode substrate |

The exact sizes are fixture parameters, not language constants. Include at least
one small realistic case beside each stress case so optimization is not tuned only
to synthetic extremes.

## Baselines

A useful result needs explicit comparators:

1. **Freehand interpreted versus Freehand compiled** isolates lowering cost under
   common semantics.
2. **Absorbed compiled tier versus the donor cut** detects regressions introduced
   while moving proven `re-frame.ui` machinery. Preserve a benchmark fixture and
   result from a named donor commit; do not retain the old artifact as a runtime
   dependency.
3. **Before versus after an implementation change** supports regression gates.
4. **End-to-end host time** prevents a substrate-local speedup from being sold as
   an application speedup when React or a foreign library dominates.

Every stored result must name the source revision, build mode, browser/runtime,
hardware class, warm-up/sample policy, fixture parameters, and whether dev
instrumentation was enabled. A median without its distribution and environment is
not release evidence.

## Options

### Option A — Fix absolute budgets now

Choose target milliseconds, microseconds, and bundle kilobytes from current
donor results or external UI library claims before Freehand is implemented.

Consequences:

- Teams have an immediate target.
- Measurement boundary differences, host versions, hardware, and the new common
  runtime make the numbers weakly comparable.
- An arbitrary threshold can drive architecture and micro-optimizations before
  the dominant costs are known.
- Passing a synthetic number may be mistaken for user-visible fitness.

This conflicts with the documents’ anti-folklore posture.

### Option B — Record measurements but never gate releases

Run B1–B5 and publish results; treat them only as advisory evidence.

Consequences:

- No flaky benchmark blocks delivery.
- Regressions can be explained away indefinitely, and “compiled for performance”
  has no minimum demonstrated fitness.
- Library-default compilation and cell-elision claims lack an acceptance bar.

Evidence without a decision rule is useful during exploration but insufficient
for beta.

### Option C — Calibrate first, then ratify workload-specific gates

Build B1–B5 alongside the first working two-mode slices. Record baselines and
variance before choosing numbers. Ratify numerical budgets before beta and gate
stable regressions from then on.

Consequences:

- Architecture receives early feedback without pretending estimates are facts.
- Thresholds are grounded in Freehand’s actual measurement boundary and hosts.
- There is a short period where the harness exists but results are diagnostic,
  which must be labeled clearly.
- The project needs a small ratification step after sufficient samples exist.
- Some full-browser benchmarks are too noisy for every PR and require a split
  between deterministic PR checks and scheduled/release runs.

### Option D — Leave budgets entirely to applications

Ship counters and let each app decide whether promotion helps.

Consequences:

- App-specific placement remains honest and necessary.
- The framework cannot substantiate its own compiled-tier, elision, controlled
  scheduling, or bundle-cost claims.
- Regressions become every consumer’s discovery.

Application measurement complements, but cannot replace, product evidence.

### Option E — Deterministic gates plus mandatory performance evidence

Build and publish B1–B5 from the first working slices. Gate releases on stable,
deterministic properties—semantic equality, no dropped input, exact attributable
commit counts, manifest/cell elision, row commit counts, and bundle reachability.
Treat wall-clock and byte distributions as mandatory evidence rather than pass/fail
thresholds. A stable adverse trend requires attribution work and an explicit
disposition; any public numerical claim must cite the supporting artifact.

Consequences:

- Correctness, claimed elision, and reachability cannot regress behind benchmark
  noise.
- Engine, hardware, timer granularity, and measurement-boundary drift cannot make
  an otherwise-correct release flap red.
- A release can ship with an explained adverse timing trend, so review discipline
  and transparent artifacts matter.
- A numerical product claim still has an acceptance bar: withdraw or qualify the
  claim when the cited evidence does not support it.

## Recommendation

Choose **Option E: early harness, deterministic gates, mandatory timing/byte
evidence**.

Use three evidence stages:

| Stage | Required evidence | Gate behavior |
|---|---|---|
| implementation/pre-alpha | B1–B5 fixtures exist as soon as each relevant slice works; estimates are labeled; output parity/correctness is asserted | diagnostic performance, hard correctness |
| donor deletion | internal conformance table green; named component/library pilots pass; no new `re-frame.ui` imports; donor worklist disposed; B1–B5 results published for the absorbed implementation | the settled deletion gate remains conformance + pilots, not a date; performance evidence is part of judging the pilots, not a new product rivalry |
| beta | B1–B5 distributions published with named baselines; deterministic count/correctness/elision/reachability checks stable | deterministic regressions block release; timing/byte trends require attribution and explicit disposition, not an automatic fail |

Do not require “compiled is N× faster” universally. Interpret each workload on its
own terms: B1 measures the direct-lowering regime; B2 must prove real elision; B3
may show that windowing dominates comparator choice; B4 first proves correctness
and then reports both commit and settlement/presentation latency; B5 proves
reachability/elision and reports shipped cost. A null result is design information
and may change placement guidance.

Run deterministic structural equality, no-dropped-input, manifest-elision, and
bundle-reachability checks on ordinary CI. Run noisy full B1–B5 timing on a pinned
scheduled/release worker, with a small smoke subset on pull requests if stable.
This avoids both flaky gates and an elaborate benchmarking service.

The evidence review should answer only:

1. What user or product claim does this budget protect?
2. Which fixture, build, host, and hardware class measures it?
3. What baseline distribution and variance were observed?
4. What change is distinguishable from noise, without turning that estimate into a
   pass/fail threshold?
5. Which correctness invariant must be green before timing is considered?
6. How is an adverse trend attributed, explained, and recorded?

## Release evidence artifact

A compact EDN result is sufficient; avoid a bespoke metrics platform:

```clojure
{:benchmark  :B4
 :revision   "<git-sha>"
 :fixture    {:background-hz 20 :input :ime-and-selection}
 :build      :advanced
 :host       {:browser "<name/version>" :hardware-class :release-worker}
 :samples    200
 :result     {:event-to-commit-ms {:p50 0.0 :p95 0.0 :p99 0.0}
              :dropped-characters 0
              :caret-correct? true
              :composition-correct? true}
 :baseline   {:revision "<baseline-sha>" :delta-p95 0.0}
 :status     :evidence}
```

The zeroes are placeholders, not proposed budgets.

## Dependencies and what this unlocks

Depends on:

- the common conformance corpus and structural/browser parity;
- the interpreted shell and absorbed compiler/emitters;
- controlled scheduling and selected-commit instrumentation;
- D011 for any schema-generated per-view corpus;
- component and library pilot definitions.

It unlocks:

- evidence-based placement guidance;
- an honest donor-absorption regression check;
- stable deterministic release gates plus transparent performance evidence;
- validation of cell elision and generated comparators;
- controlled-input latency claims under contention;
- bounded bundle and parse-cost claims for the one artifact.

## Sources

- [codex-design.md — “Optimization workflow”](../codex-design.md#optimization-workflow)
  and [“Measurement obligations”](../codex-design.md#measurement-obligations)
  define the workflow and five claims.
- [codex-design.md — “Release acceptance”](../codex-design.md#release-acceptance)
  states the two-mode and donor-retirement gates.
- [fable-design.md §3.5 — placement and measurement](../fable-design.md#35-placement-where-each-tier-applies)
  ties promotion to measured work rather than a quota.
- [fable-design.md §7.3–§7.4](../fable-design.md#73-uncertain-assumptions--constraints-now)
  labels uncertain constants and makes B1–B5 a day-one obligation.
- [fable-design.md Appendix B.5](../fable-design.md#appendix-b--matrices-and-budgets)
  provides the concrete benchmark shapes and metrics.

# The cold-mount double build, priced against the mount red-zone

**Bead** `rf2-2rtt6.15` · **epic** `rf2-2rtt6` (EP-0038) · decision input for the
**rf2-2rtt6.14** ruling
**Measured** 2026-07-31 AUSEST · **Runtime for every figure on this page:
HeadlessChrome 147.0.7727.15 (Chromium via Playwright), `:advanced`,
`goog.DEBUG false`, Windows 11 x64.**

> **The instrument is identified by content hash, not by commit SHA** — a rebase
> moves SHAs and leaves blobs alone, which is how two earlier pages' citations
> went stale. Authored at `0b482f385e` on `worker/coldmount-2rtt6-15`; if that
> does not resolve, these blobs are what to trust
> (`git rev-parse <candidate>:implementation/freehand/test/re_frame/bench/hicasso/<file>`):
>
> | file | blob |
> |---|---|
> | `coldmount_views.cljs` | `335a37bb09233121e83ca2dc9f6a0a9ef88e037c` |
> | `coldmount_app.cljs`   | `8cc9a9b5ec82dccc4a09321d2b08c440b580dc8f` |
> | `coldmount_run.cjs`    | `d37fd07688f6e9a47f18938117ba776cb8d098e9` |
>
> **[The hand-off landed](#the-hand-off-landed--the-same-instrument-re-run-against-shipped-code)**
> (`rf2-2rtt6.25`) re-ran this instrument against shipped code and added the
> `shipped` witness arm — **and that section's shipped-performance figures are
> RETRACTED (2026-08-01): the instrument mounts inside `flushSync`, and on the
> public `createRoot().render()` path the hand-off loses its race and the
> double build is still paid. See the banner at the head of that section.**
> Authored on `worker/handoff-2rtt6-25`; those figures
> are this second set of blobs:
>
> | file | blob |
> |---|---|
> | `coldmount_views.cljs` | `ad3556a641ce174e3575c9f91668330db2861f93` |
> | `coldmount_app.cljs`   | `46fcb53f43b2f87ef7d91a221456924c53bf9f3b` |
> | `coldmount_run.cjs`    | `d37fd07688f6e9a47f18938117ba776cb8d098e9` (unchanged) |
>
> Those three blobs pin **what produced the figures**, so they stay as they
> are. The instrument's working copy has since moved past them: the merged-PR
> audit of #7326 found that the retraction above had not reached the source,
> where the `shipped` arm still called itself this bead's acceptance witness
> and the emitted records still said the shipped cold read builds once. The
> arm is now named in the source and stamped in every emitted record as the
> **forced-synchronous mechanism arm**, with a `:schedule` key carrying the
> qualifier the prose here carries. Wording only — no measurement window was
> altered to repair provenance, so the rows below still say what they said.

Reproduce (each row is one page and one driver invocation; the plain command
runs all five over the same gates):

```bash
node implementation/freehand/test/re_frame/bench/hicasso/coldmount_run.cjs
COLDMOUNT_ONLY=M1L1 node implementation/freehand/test/re_frame/bench/hicasso/coldmount_run.cjs
```

The five published rows were taken one row per invocation — fresh build (the
lane cache cleared per rf2-2rtt6.20, so the bundle is byte-deterministic),
fresh browser per row — with the machine checked quiet first: the sibling
`test:cljs` runs live on this box were waited out before the first published
round. **Exit 0 on all five.**

---

## The question

rf2-2rtt6.12 proved by counting that every cold subscription read on the
React-hook spine constructs **two** reactions: `use-subscribe`'s render-phase
balanced round trip takes an empty-cache entry `0 → 1 → 0`, Spec 006's
no-grace-period rule makes `1 → 0` an in-tick dispose + evict, and the
commit-owned `subscribe-fn` then misses the cache and rebuilds
(`bodyRuns = 2.00N` against Reagent's `1.00N`, twice over disjoint cells —
[the decomposition page](uix-spine-per-read-decomposition.md)). That page
priced what the first, dead reaction *retains*. It deliberately did not price
what the second construction *costs on the clock*.

rf2-2rtt6.14 pre-registered the rule that consumes that clock:

> fraction < 20% of the mount red-zone, or unresolvable from round-to-round
> spread → rf2-2rtt6.14 closes as "take rf2-2rtt6.13 alone; Spec 006
> no-grace-period stands". fraction ≥ 20% on representative layer-1 AND
> layer-2/3 mounts → rf2-2rtt6.14 reopens for a design pass scoped to a
> hook-scoped provisional hand-off ONLY.

The denominator — the UIx-minus-Reagent mount-clock excess — is measured **in
the same runs**: M1's published red-zone magnitude is withdrawn (direction
only, ≈1.11–1.35×), so nothing here quotes it; every excess below comes from
this page's own rounds.

---

## The instrument — one variable, and the counts that pin it

Two transcriptions of the shipped hook run beside the shipped arm and the
converged Reagent denominator, on the converged witnesses, on the
`:hicasso-bench` lane. They differ in exactly one property — how many
reactions a cold read constructs:

| arm | render phase | commit phase | constructions | cache-API calls/read |
|---|---|---|---:|---:|
| `uix-xcript` | subscribe + unsubscribe (build #1, dispose #1) | subscribe — miss, **build #2** | **2** | 4 |
| `uix-handoff` | subscribe (+1 held provisionally) | subscribe — **hit** — then unsubscribe (2 → 1) | **1** | 4 |

Both make two `subs/subscribe` and two `subs/unsubscribe` calls per mounted
read; both install the same watch, the same refs, the same six hooks; the
canonical-DOM gate proves they build the identical page. `xcript − handoff` is
therefore the build/dispose/rebuild churn alone — the clock a commit-phase
adoption of the render-phase materialisation would recover, which is the
rf2-2rtt6.14 question in arm form. **`handoff` is a measurement arm, not a
patch**: its provisional +1 leaks if a render is abandoned before commit, and
the reaping policy a real hand-off needs is exactly what the ruling's design
pass would have to produce. Nothing here edits production code.

**The construction counts are adjudicated, not assumed.** Before any clock, a
counter-instrumented copy of each transcription reads a counter-instrumented
sub chain, predictions written in the driver first. Every call, every page,
came back exact — `failures []` on all five rows:

| variant, for N = 300 reads at page layer L | commits | rebuilt | bodyRuns per layer ≤ L |
|---|---:|---:|---:|
| `xcript` — predicted and measured | 300 | **300** | **600 (2N)** |
| `handoff` — predicted and measured | 300 | **0** | 300 (1N) |
| Reagent — predicted and measured | 0 | 0 | 300 (1N) |

`rebuilt = 300` on `xcript` re-confirms the double build **on this spine at
this commit**; `rebuilt = 0` on `handoff` proves the commit adopts the
render-phase reaction `identical?`-ly, which is what makes the subtraction
single-variable. At layers 2 and 3 the *whole chain* doubles (`body1` and
`body2` both 2N at L2; all three at L3) — the render-phase dispose cascades
down the `:<-` inputs and the commit rebuild reconstructs every node, which is
why materiality was expected to show at layer 2+ first.

The layered subs are identity chains over the converged witness's own sub
(`[:cm/l2 i] :<- [:p0/cell i]`, `[:cm/l3 i] :<- [:cm/l2 i]`, parametric
`input-fn` form), so the bodies stay near-zero and the ladder isolates the
per-hop `:<-` machinery rather than user compute.

Two deliberate departures from the converged mount rows, both stated: **four
rounds, even** — the converged page measured a systematic segment-order effect
and named an even round count as the arm-level repair; 2:2 makes the raw mean
and the order-balanced mean coincide by construction — and **a residue gate**
per segment-round (`lane/residue` back to baseline after a settle, equality,
no tolerance), because a `handoff` leak would silently convert later samples
into warm-cache mounts. It never fired.

---

## The answer

**The double build is not a minor term of the mount red-zone. It is most of
it — and at layer 1 it is all of it.** Per round, p50 milliseconds per mount;
the fraction is `(xcript − handoff) / (uix-subs − reagent-subs)` in the same
round, raw and seam-adjusted (UIx-segment terms divided by that round's
floor-vs-floor seam):

| row | double build, ms | red-zone excess, ms (raw) | **fraction, raw** | fraction, seam-adjusted | verdict vs 20% |
|---|---|---|---|---|---|
| **M1L1** — 901 el / 300 boundaries, layer 1 | 0.81 `[0.75–0.85]` | 0.63 `[0.35–0.85]` | **1.49 `[0.94–2.14]`** | 1.31 `[0.93–2.00]` | **≥ 20% in every round, both estimators** |
| **M1L2** — one `:<-` hop | 2.39 `[2.35–2.45]` | 3.61 `[3.15–3.90]` | **0.66 `[0.63–0.75]`** | 0.67 `[0.61–0.71]` | **≥ 20% in every round, both estimators** |
| **M1L3** — two `:<-` hops | 3.83 `[3.30–5.15]` | 7.50 `[6.35–9.95]` | **0.53 `[0.35–0.78]`** | 0.57 `[0.41–0.84]` | **≥ 20% in every round, both estimators** |
| M2L1 — 51-el form, layer 1 · *diagnostic* | 0.10 `[0.10–0.10]` | −0.04 `[−0.15–0.00]` | not resolved | not resolved | **unresolvable** — the excess sits on the clamp |
| M2L2 — the form, one hop · *diagnostic* | 0.13 `[0.10–0.20]` | 0.26 `[0.05–0.45]` | 0.82 `[0.22–2.00]` | not resolved | **unresolvable** — one seam-adjusted round ≤ 0 |

Per-round fractions, stated rather than smoothed:

| row | raw, per round | seam-adjusted, per round |
|---|---|---|
| M1L1 | 1.8889 · 1.0000 · 2.1429 · 0.9412 | 1.2911 · 1.0000 · 0.9302 · 2.0000 |
| M1L2 | 0.6528 · 0.6316 · 0.6282 · 0.7460 | 0.6960 · 0.7085 · 0.6655 · 0.6075 |
| M1L3 | 0.4718 · 0.5197 · 0.7803 · 0.3518 | 0.4487 · 0.5593 · 0.8423 · 0.4130 |

**One honesty note on M1L1's magnitude.** Its fraction's two segment-order
strata are disjoint (Reagent-first 1.89–2.14, UIx-first 0.94–1.00), so the
point value 1.49 is not a threshold — the same discipline that withdrew
`1.2301`. What the decision rule consumes survives the partition untouched:
**every round of every estimator in every stratum sits at 0.93 or above**,
more than four times the 20% boundary. M1L2's and M1L3's strata overlap and
their magnitudes stand as quoted.

The raw per-arm milliseconds behind the table, p50 per round:

| row | floor (R / U segment) | reagent-subs | uix-subs | uix-xcript | uix-handoff |
|---|---|---|---|---|---|
| M1L1 | 0.9·0.8·0.8·0.7 / 0.85·0.8·0.7·0.8 | 3.75·3.30·3.65·3.15 | 4.20·4.15·4.00·4.00 | 4.20·4.05·3.90·4.00 | **3.35·3.20·3.15·3.20** |
| M1L2 | 0.85·0.8·0.8·0.95 / 0.9·0.9·0.85·0.8 | 3.80·3.30·3.50·4.55 | 7.40·7.10·7.40·7.70 | 7.40·7.10·7.25·7.00 | 5.05·4.70·4.80·4.65 |
| M1L3 | 0.9·0.7·0.7·0.6 / 0.8·0.8·0.8·0.9 | 3.30·3.15·3.40·2.95 | 10.40·9.50·10.00·12.90 | 9.60·9.40·11.40·11.70 | 6.25·6.10·6.25·8.20 |
| M2L1 | 0.2·0.3·0.3·0.2 / 0.2·0.2·0.2·0.15 | 0.50·0.50·0.55·0.40 | 0.50·0.50·0.40·0.40 | 0.50·0.50·0.40·0.40 | 0.40·0.40·0.30·0.30 |
| M2L2 | 0.2·0.3·0.1·0.35 / 0.25·0.4·0.2·0.2 | 0.50·0.50·0.30·0.55 | 0.75·0.95·0.60·0.60 | 0.70·0.70·0.60·0.60 | 0.60·0.60·0.40·0.50 |

### The split by layer — where the second construction's clock goes

Per cold read (dividing each row's double-build delta by its 300 reads):

| depth | second construction, µs/read | increment |
|---|---|---|
| layer 1 — no `:<-` chain | **2.5–2.8** | the reaction allocation + dispose + cache round trip itself |
| layer 2 — one hop | 7.8–8.2 | **+5.2–5.5 µs for the first `:<-` hop** (input reaction rebuild + input re-deref + wiring) |
| layer 3 — two hops | 11.0–17.2 | +3.0–9.0 µs for the second hop |

The sub bodies here are near-zero (`nth` on a vector, identity chains), so the
second sub-body run's own compute is a negligible slice of these numbers — the
clock is dominated by the reaction allocation and, from layer 2, by rebuilding
and re-dereffing the input chain. Each `:<-` hop roughly *doubles-and-more* the
per-read churn, which is the shape the counting witness predicts: at depth L
the double build reconstructs L reactions, not one.

### Two observations beside the fraction, reported not ruled

* **The single-build hook mount lands at or below the Reagent denominator on
  M1L1.** `uix-handoff` read 3.15–3.35 ms against `reagent-subs`' 3.15–3.75 ms
  in the same rounds. On this page's witness, removing the double build takes
  the UIx mount out of the red zone entirely — an observation about the
  ceiling of the hand-off design, not a bar row.
* **The layered mounts widen the gap sharply** — `uix-subs / reagent-subs`
  reads 1.84–2.01× at layer 2 and 2.57–3.55× at layer 3 against layer 1's
  1.11–1.26× (which sits inside the converged page's ≈1.11–1.35 direction
  range, the external-consistency check this page owes). These layered ratios
  are **context, not red-zones** — no layered row exists in the converged set,
  and only the operator amends the standard. One method note applies to the
  denominator at depth: Reagent's released subscriptions dispose on a
  *macrotask* (measured in `lane/settle!`'s docstring), so within a
  synchronous sampling round its later mounts re-acquire live cache entries,
  while the hook spine's in-tick disposal makes every UIx mount cold. That
  asymmetry is shipped behaviour on both sides — it is the no-grace-period
  rule seen from the other end — and it is part of what these ratios measure.

---

## The decision rule, applied

The rule, verbatim from rf2-2rtt6.14 (pre-registered; applied, not
re-litigated):

> * fraction < 20% of the mount red-zone, or unresolvable from round-to-round
>   spread → rf2-2rtt6.14 closes as "take rf2-2rtt6.13 alone; Spec 006
>   no-grace-period stands".
> * fraction ≥ 20% on representative layer-1 AND layer-2/3 mounts →
>   rf2-2rtt6.14 reopens for a design pass scoped to a hook-scoped provisional
>   hand-off ONLY (never a general ref-count-0 cache tenancy), subject to the
>   correctness protocol recorded on rf2-2rtt6.14.

**The measurement satisfies the second branch.** On the representative M1
mount the fraction is ≥ 20% in every round of every estimator at layer 1
(lowest estimate 0.93) and at layers 2 and 3 (lowest estimates 0.61 and
0.35). The two diagnostic M2 rows are unresolvable at the clamp, exactly as
their bar-row counterpart's grading predicts, and the rule does not condition
on them.

**Implied verdict: rf2-2rtt6.14 reopens for a hook-scoped provisional
hand-off design pass only.** This page implies the verdict and stops; opening
and closing the ruling bead is the mayor's reconciliation, not this
instrument's.

> **Since ruled and implemented.** rf2-2rtt6.14 was RULED ADOPT (Mike,
> 2026-07-31) and rf2-2rtt6.25 landed the hand-off. Everything above this line
> is the evidence that fed the ruling and is left exactly as it was measured;
> [the hand-off landed](#the-hand-off-landed--the-same-instrument-re-run-against-shipped-code)
> at the foot of this page is the same instrument re-run against shipped code.

**Which spine was measured: the shipped one.** rf2-2rtt6.13's retention fix
is NOT landed at the measured commit — the render-phase `use-memo` still
returns the reaction handle — and the counting witness re-confirmed
`rebuilt = 1.00N` live. Per that bead's own sequencing note the fix removes
the *retention* of the dead reaction, not the rebuild, so these fractions
survive .13 unchanged in expectation; a post-.13 re-take would be a
confirmation, not a correction.

**That expectation has since been checked on the counting side.** rf2-2rtt6.13
landed on 2026-07-31, and the spine ablation's witness re-run against the fixed
spine reports `commits` 1.00N, `rebuilt` 1.00N, `bodyRuns` 2.00N on UIx against
0/0/1.00N on Reagent — the same exact integers, twice over disjoint cells
([the decomposition page](uix-spine-per-read-decomposition.md#the-fix-landed)).
The double build is untouched by .13, so the fractions on this page stand. Their
*clock* has not been re-taken post-.13, and this note does not claim it has.

---

## Instrument discipline

* **Fidelity, adjudicated.** The transcription must reproduce the shipped
  hook on the clock or every delta is void: per-round p50 ranges overlap or
  medians within the pre-declared 3% band. All five rows passed on the
  range-overlap clause; medians sat 1.2% (M1L1), 3.0% (M1L2), 2.9% (M1L3),
  6.3% / 4.2% (M2, quantised) apart. The shipped arm reads through the
  ambient 1-arg `use-subscribe` (the converged arm, unchanged, under
  `frame-provider`); the transcriptions pin the frame explicitly as the
  2-arg spine path does — one context read per boundary of difference, far
  below the quantum, and the control adjudicates it rather than a comment.
* **Positive control.** `:ctl-2x` at exactly twice the elements, predicted
  1.9989× (M1) / 1.9412× (M2) before the run, measured inside the interleave
  in both segments of every row — **ten controls, ten passes** under the
  overlap rule (`lane/control-verdict`, slack 25%), every mean below the
  prediction, the direction a fixed per-root term predicts.
* **Verification.** Every mount read back at the arm's own arithmetic —
  element count plus both ends of the page, the 2× control at its own far
  probe: **0 unverified of 640, on each of the five rows** (3,200 mounts
  total), and `unverified > 0` throws.
* **Arm-order guard.** `lane/slot-order` rotates and reflects; the self-test
  runs before anything is measured, plan sizes 2/3/5 asserted non-degenerate
  at boot. **Verdict: clean on all five rows** — `refuse? false`,
  `contaminated? false`, `unchecked? false`, tolerance 0.10 untouched.
* **Segment-order verdict** (`p0-converge-app/segment-order-verdict`,
  required, not copied) on every row's cross-seam ratio: no refusal anywhere;
  M1 rows publish magnitudes (strata overlap), M2 rows are direction-only —
  and the M1L1 *fraction*'s strata are disjoint, which is why its point value
  is not quoted as one (above).
* **Residue gate.** After every segment-round, one macrotask settle then
  `lane/residue` compared to the segment's baseline by equality —
  `sub-entries`, `sub-ref-count`, attached containers. Never fired: the
  provisional +1 balanced in all 3,200 mounts.
* **The report survives refusal.** Every record prints before any gate exits;
  a witness mismatch or fidelity failure exits 4 with the table already on
  stdout (the rf2-2rtt6.12 audit's fail-closed discipline).

---

## What this page does not claim

* It does not claim a new red-zone. The layered ratios are context; the bar
  and the red-zones are rf2-2rtt6.1's, and only the operator amends them.
* It does not claim `uix-handoff` is shippable. It is the hand-off *shape*
  with the reaping question deliberately unanswered — the design pass the
  verdict feeds is where that question lives, under the correctness protocol
  on rf2-2rtt6.14.
* It does not re-price retention. The 769 B / 23.0 obj term is
  [the decomposition page](uix-spine-per-read-decomposition.md)'s and is
  untouched; no red-zone value moves, so rf2-2rtt6.1 gets nothing from here.

---

## The hand-off landed — the same instrument, re-run against shipped code

> **RETRACTED, 2026-08-01 — every figure in this section measures a mount
> schedule no consumer mounts on** (`rf2-2rtt6.25`, merged-PR audit of #7305).
> `mount-once!` renders inside `react-dom/flushSync` (`coldmount_views.cljs`),
> which forces React's passive `useSyncExternalStore` subscribe to run before
> control returns — and that IS the ordering the hand-off needs to win. The
> shipping client mount path is `re-frame.substrate.adapter/render`, i.e. a bare
> `createRoot(…).render(…)` with nothing forcing the schedule, and **there the
> `setTimeout 0` reaper fires first: the escrowed reference is released, the
> commit misses, and the mount rebuilds.** Re-measured through the adapter
> render slot with no `act` and no `flushSync`, three trials at each size:
> `bodyRuns` **2.00N at N = 1 and at N = 300** — the double build this section
> reports as deleted is paid in full on every consumer mount.
>
> **So the `shipped` rows below are withdrawn as shipped-performance claims.**
> Read them as what the hand-off *mechanism* costs and saves when it runs to
> completion — the `commits` / `rebuilt` / `bodyRuns` witness, the `xcript`
> before/after clock, and the M1L1 red-zone closure all measured that. They are
> not a statement about any mount a consumer performs today. The counting
> witness, the fidelity gate, the residue gate and the segment-order control are
> all unaffected as instruments; what changed is which schedule they speak for.
>
> **Nothing is unsound.** Spec 006 §Render-phase provisional acquisition and
> commit adoption requires that correctness never depend on the reaper losing
> the race, and it does not: the lost race costs a construction and the steady
> state is identical. The horizon that would recover the saving —
> `setTimeout 4` and `setTimeout 32` both read 1.00N at N = 1 and N = 300,
> `requestAnimationFrame` reads 1.00N at 1 and 2.00N at 300, a `MessageChannel`
> post loses — is an operator-approved lifecycle contract (`rf2-2rtt6.14` ruled
> the primitive a macrotask), so it was **not** changed by the audit repair, and
> `rf2-2rtt6.25` stays OPEN on that decision. Standing witness:
> `assert-use-subscribe-public-mount-schedule-rebuilds`.

**Bead** `rf2-2rtt6.25` · **ruled ADOPT on `rf2-2rtt6.14`** (Mike, 2026-07-31) ·
**re-measured** 2026-07-31 13:4x AUSEST, same box, same runtime line as every
figure above.

The design pass this page's verdict fed is implemented. The render phase keeps
its `+1` in a one-shot escrow token instead of balancing it away, so the
commit-owned `subscribe-fn` HITS and adopts the reaction the render built; the
token is released `2 → 1` at adoption, or reaped by a host-macrotask drain
armed at acquisition if the commit never comes. The instrument gained a fourth
witness arm — **`shipped`**, the real hook — whose `commits` / `rebuilt` are
read off the sub-cache rather than from a counter cut into production code
(the render records which reaction the cache holds for its query the instant
the read returns; after the mount, `commits` counts the tenanted slots and
`rebuilt` counts the tenants that are not the one the render saw). Independent
measurement, same two integers, and on the pre-hand-off spine every render
observes nothing and every read counts as rebuilt — the same `rebuilt = N` the
`xcript` counter reports.

**The acceptance witness, exact, `failures []` on every row:**

| variant, N = 300 reads at page layer L | commits | rebuilt | bodyRuns per layer ≤ L |
|---|---:|---:|---:|
| `xcript` — the retired double build | 300 | **300** | **600 (2N)** |
| `handoff` — the transcription | 300 | 0 | 300 (1N) |
| **`shipped` — the real hook** | **300** | **0** | **300 (1N)** |
| Reagent | 0 | 0 | 300 (1N) |

Twice over disjoint cell ranges, at layers **1, 2 and 3**. The shipped hook
reads the `handoff` row: the double build is gone, and at depth the whole
`:<-` chain builds once rather than twice.

**And on the clock, from the same rounds.** `xcript` is the behaviour that was
removed, measured beside its replacement in the same page loads, so this is a
before/after and not a cross-run comparison — p50 ms per mount, four rounds:

| row | `shipped` (after) | `xcript` (before) | after ÷ before |
|---|---|---|---:|
| **M1L1** — 901 el / 300 boundaries, layer 1 | 3.50 · 3.25 · 3.25 · 3.10 | 4.15 · 3.80 · 3.90 · 3.95 | **0.83** |
| **M1L2** — one `:<-` hop | 5.15 · 4.70 · 4.85 · 4.60 | 7.00 · 6.80 · 6.60 · 6.20 | **0.71** |
| **M1L3** — two `:<-` hops | 6.55 · 6.50 · 6.40 · 7.40 | 11.00 · 9.30 · 9.70 · 10.15 | **0.66** |
| M1L3 — independent re-take | 6.50 · 6.00 · 5.95 · 6.65 | 10.05 · 8.65 · 10.20 · 9.20 | **0.65** |
| M2L1 · *diagnostic* | 0.45 · 0.50 · 0.40 · 0.40 | 0.50 · 0.50 · 0.40 · 0.40 | on the clamp |
| M2L2 · *diagnostic* | 0.60 · 0.60 · 0.40 · 0.50 | 0.70 · 0.70 · 0.60 · 0.65 | on the clamp |

**The mount red zone, re-derived from these runs' own rounds** (`uix-subs /
reagent-subs`, the cross-seam ratio, four rounds each):

| row | before (the rounds above this section) | after |
|---|---|---|
| M1L1 | 1.11–1.26× | **1.0054× `[0.917–1.143]`**, strata overlap, magnitude resolved — excess mean **0.00 ms** |
| M1L2 | 1.84–2.01× | 1.4223×, direction-only (strata disjoint) |
| M1L3 | 2.57–3.55× | 1.9282× `[1.697–2.242]`; re-take 1.9560× `[1.663–2.129]` |
| M2L1 | — | 0.9078× — the UIx mount reads *below* the Reagent denominator |
| M2L2 | — | **refused** (see below) |

The observation this page reported beside its fraction — *the single-build hook
mount lands at or below the Reagent denominator on M1L1* — held when the shape
became shipped code. At layer 1 the mount red zone is not reduced; it is
**closed**, on this witness. The layered rows keep a real gap (context, not a
red-zone: no layered row exists in the converged set), and it is roughly halved.

### Two gates fired, and neither is a regression

Reporting them is the point of having them.

* **`M1L3` fidelity, run A: `ok? false`** — shipped p50 6.525 ms against the
  `handoff` transcription's 6.25, ranges disjoint by 0.05 ms, medians 4.2%
  apart against a pre-declared 3% band; the independent re-take put the same
  pair at 6.25 vs 5.90, medians 5.2% apart but ranges overlapping, so the
  control passed on its first clause. The direction is stable across both
  runs and the cause is not noise: **the shipped hook is not the `handoff`
  arm.** It carries what the arm deliberately omits — the escrow token, the
  pending queue, one timer per burst, and an identity-guarded release instead
  of a plain `unsubscribe`. That is the hand-off's own machinery, ~4–5% of a
  layer-3 mount, and it is already inside every `shipped` number above. The
  band is not widened here: a control that reports a real difference is
  working, and the honest reading is that no transcription reproduces the
  post-hand-off hook to 3% because none of them contains it.
* **`M2L2` segment-order control: `refuse? true`** — the row's two order
  strata point opposite ways across 1.0 (Reagent-first 0.775×, UIx-first
  1.171×), so the row has no direction to publish. That is a direct
  consequence of the win rather than an instrument fault: on a 51-element form
  the UIx and Reagent mounts are now within the 100 µs clock quantum of each
  other, so which one reads faster is decided by the schedule. The M2 rows
  were graded DIAGNOSTIC and clamp-quantised before this change and the
  decision rule never conditioned on them.

Everything else held: canonical-DOM parity across both seams and all judged
arms; the counting witness exact on every row; the positive control ten of ten
under the overlap rule; 0 unverified of 640 writes per row; the arm-order guard
clean everywhere (`refuse? false`, tolerance untouched); the residue gate never
fired — the provisional `+1` balanced in every mount, which is the leak
detector for exactly the reference this change introduces.

### What this section does not claim

* It does not re-run the fraction. `xcript − handoff` is still measured and
  still large, but the question it was built to answer is answered and the
  rule that consumed it has fired.
* It does not amend a red zone. rf2-2rtt6.1 owns the bar; these are this
  instrument's own rounds, and only the operator moves a published figure.

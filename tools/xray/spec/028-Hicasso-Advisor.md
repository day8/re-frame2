# 028 — The hot-view advisor and the causal slice

**Status**: shipped (rf2-hic-037) · **Tab**: `:hicasso`, sub-views `Advisor` and `Causal`
**Producers**: `re-frame.hicasso.tool` (the four evidence reads) + Spec 009's retained ring
**Consumer**: `day8.re-frame2-xray.panels.hicasso-advisor` + `…panels.hicasso-causal`
**Normative upstream**: `docs/design/hicasso/product/specification.md` §10 ·
`docs/design/hicasso/product/lanes/hot-path-architecture.md` ·
`docs/design/hicasso/product/lanes/left-field-ideas.md` §Capability receipts

---

## What these two views are for

Spec SN §10 calls the advisor *the differentiating feature*: it *ranks
time/frequency/read churn/fan-out, then classifies computation, topology,
lowering, React, or layout pressure. It recommends native extraction only when
it addresses the measured owner.* The causal slice is the other half of the same
section — §10's chain, `event → subscriptions recomputed → values changed →
boundaries notified → bodies run → React commit → paint`, with the rule that
*each link needs an explicit evidence seam*.

They are **sub-views of the Hicasso tab**, not a tab of their own, and that is a
correctness choice rather than a layout one. Both are derivations of the same
one-turn read (`:rf.xray.hicasso/data`). A second tab would take a second turn,
and a mount landing between them would give a ranking about a census the slice no
longer agrees with — the same reason the four evidence envelopes are taken
together in the first place.

Adding them moved **no governance pin**. The tab, its L4 registration, the focus
mirror, the registry schema version, the palette counts and the feature-matrix
handoff are all unchanged; `hicasso-helpers/sub-modes` grew from four entries to
six, and `empty-copy` grew two matching entries. The `:rf.xray.hicasso/*` sub and
event ids are the same three.

---

## The instrument table, and the refusal that follows from it

| Pressure class | Instrument | Available here? |
|---|---|---|
| application computation | Spec 009 `:rf.sub/elapsed-ms` on the retained ring | **yes** |
| read topology | the cell table's fan-out, the entry cache's read orders, the ring's recompute counts | **yes** |
| Hiccup lowering | — | no |
| React reconciliation / commit | — | no |
| DOM layout / paint | — | no |

The three unavailable rows are not an oversight awaiting a later bead.

- **Boundary self time was KILLED as a decision** (`lanes/left-field-ideas.md`
  §Capability receipts, from the `rf2-hic-081` spike). Chrome clamps its timer to
  a 0.1 ms grain while the quantity is single-digit microseconds, so a
  per-attempt interval reads either zero or one whole tick and *a ranking built
  on it orders noise*; independently, two clock reads per attempt inflate a
  boundary in proportion to its attempt COUNT rather than its true cost, so two
  boundaries close in true self time and far apart in attempt count invert.
- **Commit, paint and attempt outcome are React's.** `re-frame.hicasso.tool`'s
  `host-projection` states all three `:host-opaque` on every envelope it emits.
- Hicasso emits no `:rf.view/render` trace, and its User-Timing `:render`
  measures ride `re-frame.performance/enabled?` — an independently gated,
  observer-first channel that is off by default.

Rungs 3, 4 and 5 of the performance ladder
(`lanes/hot-path-architecture.md` §The performance ladder) — a direct `n/$`
element, a named native island, a native screen — **all address a class in the
unavailable half**. So the honest consequence of §10's sentence is that

> **from this evidence the advisor never recommends a native route.**

Not once, not for the hottest boundary on the page. A tool that ranked
boundaries by the one clock it has and then pointed at the native ladder would be
recommending an expensive, semantics-changing, diagnostics-losing change on
evidence that cannot speak to whether it helps — which is exactly what a *sorted
list of render durations* invites, and the single most expensive mistake this
surface could make a developer make. **The refusal is the product.**

### The refusal is about the evidence, not about a missing arm

`hicasso-advisor/ladder` is a real table keyed on the measured OWNER, carrying
all five rungs with their working-loop steps. Hand it `:lowering` and it answers
rung 3; `:react-work`, rung 4; `:react-shaped`, rung 5. Those three owner keys are
**unreachable from `classify`** on this door, and that is the whole claim.

The suite asserts the pair, because either half alone is worthless:

| Row | Asserts |
|---|---|
| `the-advisor-never-recommends-a-native-route-from-this-evidence` | over every classification `classify` emits, driven from real windows rather than hand-built maps, `(:native? (recommend c))` is false — and the emitted owner set is exactly `#{:computation :read-topology :unattributed}` |
| `the-native-ladder-is-real-and-the-refusal-is-about-the-EVIDENCE` | the same table returns rungs 3, 4 and 5 with their steps for the three owners the door cannot measure |

The first row would be equally true of a `recommend` that answered
`:measure-first` unconditionally — which is a stub, not a finding. The second is
its non-vacuity control.

---

## The advisor

### Four axes, in four units, never a score

| Axis | Unit | Source |
|---|---|---|
| **time** | ms | `:rf.sub/elapsed-ms` summed over the boundary's read edges in the retained window |
| **frequency** | recomputes / dispatches / memo hits | the ring's `:rf.sub/run` and `:rf.sub/skip` events |
| **read-churn** | reads, read ORDERS | the census's `:reads` and `:read-orders` |
| **fan-out** | reader slots | the cell table's `:fan-out`, summed over the boundary's edges |

There is deliberately **no composite score**. Weighting milliseconds against
dispatch counts against read orders against reader slots would make the weights
the ranking — invented here, and unfalsifiable. Each axis is reported in its own
unit with its own loss, and `order-axis` states which one the roster was sorted
on: `:time` when any row has a timed recompute, `:frequency` otherwise, **and
the summary line says `NOT by time` in that case**. A list ordered by a fallback
axis while implying it was ordered by time is the quiet version of the mistake
this whole namespace is arranged against.

The `time` axis measures **subscription** time, not body time, and the tab never
lets that slide: an untimed read edge answers `:unknown` and never `0.0`. Spec
009 puts `:rf.sub/elapsed-ms` on the reactive recompute path and not on the pure
`compute-sub` form, so a fold that read a missing tag as zero would report a
subscription it never timed as instantaneous — and the reader would then rank it
LAST. Its loss is `:uncorrelated` rather than `:cap`, because the recomputes were
observed and it is the DURATION that joins to nothing; reporting a cap there
would send the reader to the retention knob, which is not the remedy.

The window is scoped **per frame**. Two frames are two applications, and summing
frame B's clock into frame A's ranking would make an idle boundary look hot
because something unrelated was busy next door — the defect `explain-render`
already scopes its leads against (audit #7789).

### Five classifications, and the trio that must not collapse

| `:owner` | `:basis` | `:observed` | Means |
|---|---|---|---|
| `:computation` | `:observation` | `:recomputes` | one read holds ≥ `dominance` of a total ≥ `computation-floor-ms` |
| `:read-topology` | `:derivation` | `:recomputes` / `:memo-hits-only` | the read set oscillates (`:read-orders` > 1), or it re-runs ≥ `repeat-floor` times for measured work below the floor |
| `:unattributed` | `:host-opaque` | `:recomputes` | the window was searched, recomputes happened, and the measured half does not explain them |
| `:unattributed` | `:host-opaque` | `:memo-hits-only` | the window retained this boundary's reads being CONSIDERED and the memo answered every one — nothing recomputed |
| `:unattributed` | `:cap` | `:nothing` | the window retained no activity for this boundary at all, so no search happened |

The last three are the trio a naive advisor collapses, and `:observed` is what
holds them apart **in data** rather than only in prose. `:cap` says *raise
`:rf.trace/events-retained` and reproduce* — free. The two `:host-opaque` rows
say *the answer is real and lives in another tool* — a change of instrument. One
sentence covering all three would send two thirds of its readers to the wrong
place, so they are three sentences under two loss chips, and exactly one of them
names the retention knob.

#### `:rf.sub/skip` means one thing, and both views say it

A memo hit is **not a recompute and not nothing**. Spec 009 emits `:rf.sub/skip`
only when the cell was considered — it is *distinct from the case where the sub
was not considered at all* — so a retained skip is positive evidence that the
window observed this read and the memo held.

That fact reached the two views differently, and they contradicted each other
about the same event (audit #8027, reproduced on `main` with one skip:
`{:memo-hits 1, :advisor-basis :cap, :causal-holds [:a], :causal-evidenced true}`):

- the advisor recorded the skip as a memo hit — correctly, and calls it the
  single most informative topology signal there is — but derived `searched?`
  from **recompute runs alone**, so a skip-only window fell through to `:cap`,
  said *no search happened*, and told the programmer to enlarge a window that
  had already retained the evidence;
- the causal slice's link 2 collected every `:subs` item carrying an
  `:rf.sub/id` **without filtering the operation**, so the same skip appeared in
  a roster labelled *subscriptions recomputed* under an `evidenced` chip. The
  trace projection's `:subs` slot also carries `:rf.sub/dispose`, which was
  landing in that roster too.

An advisor that sends a reader to the retention knob when the evidence was
already retained is worse than no advisor: it sends them to fix the instrument
instead of the code.

The repair is **one predicate**, `hicasso-helpers/sub-recompute?`
(`#{:rf.sub/run :rf.sub/create}`) with `sub-skip?` beside it, in the shared
algebra both derivations already consume — two definitions of *did work happen*
is what produced the disagreement, and a third would have been worse. The
advisor's private `sub-run?` is gone.

`classify` now asks two questions rather than one: `searched?` is about
recomputes and `considered?` is about activity of any kind. A skip-only window is
`:host-opaque` / `:memo-hits-only`, routed to **measure-first** and never to the
retention knob, and its sentence says what the window actually held — *N memo
hits and no recompute at all; the cells were considered and answered without
running; subscription computation owns none of this boundary's cost, because
none of it ran.* **`:runs` stays 0**: the skip is classified as observed activity
without being promoted to a run, because making the arithmetic work by
reclassifying the event would be the same lie one layer down.

Three thresholds, each with its reason attached rather than a taste:

| Constant | Value | Why |
|---|---|---|
| `computation-floor-ms` | 1.0 | the 0.1 ms timer grain means a smaller sum is fewer than ten ticks, and its ordering against another such sum is noise |
| `dominance` | 0.6 | below a comfortable majority the time is spread across the read SET, which is a topology finding; a two-read boundary at 55/45 must not be reported as having a hot subscription |
| `repeat-floor` | 2 | the smallest count that makes a re-run a pattern rather than an occurrence — demanding more would silently require a bigger ring to see a real repeat |

### The route is looked up on the OWNER, never on the rank

| Owner | Route | Rung |
|---|---|---|
| `:computation` | narrow or memoize the subscription | — (below the substrate) |
| `:read-topology` | tune topology without changing language | 2 |
| `:lowering` | return a direct React element (`n/$`) | 3 |
| `:react-work` | named native React island | 4 |
| `:react-shaped` | implement the screen natively | 5 |
| `:unattributed` | **measure first** — the refusal | — |

This is the mechanism behind *only when it addresses the measured owner*: the
hottest boundary on the page and the coldest one with the same owner get the same
rung. A `:computation` owner is routed BELOW the view substrate and told so in
those words — the subscription body runs beneath the boundary, so every native
route would move the markup and keep the cost.

Each recommendation carries the working-loop steps that apply to it, taken from
`lanes/hot-path-architecture.md` §Xray-guided workflow and numbered as they are
there. The refusal carries the three unmeasured classes with the instrument that
settles each — never Xray, which is asserted.

---

## The causal slice

Drawn for the boundary the advisor ranked **first** and the newest dispatch the
ring still holds, so the two views are one workflow rather than two lookups.

| # | Link | Seam | Basis |
|---|---|---|---|
| 1 | event | Spec 009's retained ring, the bundle for this dispatch | `:observation` |
| 2 | subscriptions recomputed | that bundle's `:subs` RECOMPUTE events — `hicasso-helpers/sub-recompute?`, keyed `:rf.sub/id` | `:observation` |
| 3 | values changed | the cell table's epoch stamps, at the boundary's peak | `:observation` |
| 4 | boundaries notified | the cells' reader arrays — the reverse edge `notify!` walks | `:derivation` |
| 5 | bodies run | — | `:host-opaque` |
| 6 | React commit | — | `:host-opaque` |
| 7 | paint | — | `:host-opaque` |

### A link's basis and its JOIN are different questions

Collapsing them is how a causal display starts lying, so each link renders both,
on separate rows, under separate testids.

Links 2 and 3 are both `:observation` — the sub really recomputed, the cell's
epoch really moved — and **the join between them is `:uncorrelated`**, because an
epoch stamp carries no dispatch id and Hicasso's commit seam records no cascade
id. Two solid facts, printed adjacent, joined by nothing: exactly the adjacency
`re-frame.hicasso.tool` refuses to call causality, surfaced as its own row rather
than implied away by the arrow between two green links. The 1→2 join, by
contrast, IS evidenced — the ring GROUPS by dispatch-id, so that join is the
storage rather than an inference.

Link 2's roster is **recomputes only**, and the memo hits ride a separate
`:skipped` field on the same link — reported rather than discarded, because a
skip is the informative half of a dispatch that recomputed nothing. An empty
`:holds` with no unnamed run is a genuine survey result (*this dispatch
recomputed nothing*); an empty `:holds` **with** unnamed runs states `:unknown`
instead, which is the one substitution the evidence schema exists to refuse.
Filtering by operation does not touch that rule: `:unknown` follows from an
unnamed RUN, never from an untagged skip.

Link 4 is derived even when green. The reader array is what `collector/notify!`
walks, so the notified set follows from it — but the notify CALL is not recorded,
and the array is read at ASK time rather than at commit time, so a boundary that
unmounted in between is absent from a set it was in. The link says so on the page.

Links 5, 6 and 7 are three different absences with three different authorities,
not three phrasings of one. React DevTools answers the first two; the browser's
own performance tools answer the third. A reader deciding what to open next needs
to know which one they are missing.

The slice's own envelope is `:complete? false` with `:loss {:reason
:uncorrelated}` **unconditionally**. A slice that reported itself complete
because its first four links came back green would be claiming the chain, having
evidenced its prefix.

### Every evidenced link is mutation-tested

A display that renders `evidenced` from a seam it is not actually reading is
worse than one that renders `unknown`, and on a healthy application the two are
indistinguishable from outside. Each row below breaks one seam and asserts the
link stops being evidenced — degrading to a stated loss, never to a confident
wrong answer, and never borrowing a neighbouring seam to keep its colour. **Each
sabotage asserts the link is green FIRST, on the same runtime**, because a
sabotage that reds a link which was never green proves the fixture was broken and
nothing else.

| Link | Sabotage | Must degrade to |
|---|---|---|
| 1 event | `clear-trace-buffer!` on the frame — the dispatch happened, the seam no longer holds it | `:cap`, `:holds :unknown`, "capped window" — never *a dispatch that did not happen*; link 2 follows it down |
| 2 subs recomputed | the runtime's own events with `:rf.sub/id` stripped | `:holds :unknown` with `{:reason :uncorrelated :dropped n}` — **never `[]`**, which would say this dispatch recomputed nothing |
| 3 values changed | a real read-free boundary (`:latest-reads :unknown`) | not evidenced, and says *not a gap in the instrument* — a boundary holding no read is a fact about the boundary |
| 4 boundaries notified | the attribution envelope suppressed | `:holds :unknown`, and the row asserts the mounted census was **available and not substituted** — it holds the forward edge, and answering a reverse-edge question from it would be a different fact under this one's name |

Two of the four act on the SEAM'S OWN DATA rather than on the runtime — the trace
tag and the attribution envelope — because those are the seams; the events they
carry are otherwise the runtime's own.

---

## Rendering contract

Every absence renders through the existing `hicasso-helpers/loss-chip`, so the
five states stay pairwise distinct in words and testid suffix on these two views
exactly as on the other four.

| Surface | testid |
|---|---|
| advisor root | `rf-xray-hicasso-advisor` |
| one advice row | `rf-xray-hicasso-advice-<slug>` |
| its classification + loss chip | `…-class`, `…-class-loss-<kind>` |
| its route or refusal | `…-route`, `…-refusal`, `…-refusal-<class>` |
| the three unmeasured classes | `rf-xray-hicasso-advisor-unmeasured[-<class>]` |
| causal root | `rf-xray-hicasso-causal` |
| one link | `rf-xray-hicasso-causal-link-<id>` |
| its basis, loss chip and JOIN | `…-basis`, `…-loss-<kind>`, `…-join` |
| the two new empties | `rf-xray-hicasso-empty-advisor`, `rf-xray-hicasso-empty-causal` |

Row slugs are the existing `boundary-slug`, so the advisor's rows share the
injective encoding `027-Hicasso-Evidence.md` §The key is INJECTIVE established,
and two frames' boundaries over one query cannot collide here either.

The three unmeasured classes render on **every** advisor render, rows or none —
they are the reason the top row is not a verdict, and a reader who saw them only
when the roster was empty would read a populated one as complete. The 200-row
panel budget applies through `common-helpers/cap-rows` as elsewhere.

---

## Read-only, dev-only, bundle-isolated

`hicasso-reads/trace-windows` is the only new live read. It calls
`re-frame.trace.tooling/trace-buffer` for the frames `explain-render`'s `:window
:frames` already names — the union of the frames the runtime dispatches through
and any frame a boundary reads from, computed by the producer for exactly the
reason a per-boundary window is scoped that way. Reading every frame in the
process instead would let an unrelated application's activity inflate this one's
ranking.

It is a **second producer**, not a fifth Hicasso read, and it is stamped as one:
`hicasso-advisor/sub-timing` carries `:day8.re-frame2-xray.hicasso-advisor.timing/v1`
and `:producer :re-frame/trace`, so a reader can see which producer vouched for
which number. The advice and slice envelopes likewise stamp Xray's own schemas —
stamping the producer's would tell a reader that Hicasso vouched for a ranking it
has never seen.

Nothing is pinned, dispatched or acquired; the read is `try`-guarded and degrades
to `{}`, which the advisor renders as a capped window rather than as a quiet
application. Every underlying read is `nil` or `[]` in a production build (the
Hicasso door and the trace ring both nil-gate on
`re-frame.interop/debug-enabled?`), and Xray never reaches a production bundle.
The dependency points one way only: `tools/xray` → `implementation/`.

Production erasure is rf2-hic-024's sentinel proof and is unchanged by this
bead: no new sentinel, no new evidence machinery, and no code under
`implementation/` was touched.

---

## Test coverage

| Suite | Tier | Proves |
|---|---|---|
| `…panels.hicasso-advisor-cljs-test` | node + JVM | the timing fold (untimed ≠ zero; a memo hit is not work; an unnamed run is `:uncorrelated`, never dropped; the per-frame scope); the top-3 against a HAND profile whose frequency order deliberately inverts its time order; the fallback axis says `NOT by time`; the five classifications, each driven from a real window; `:cap` and `:host-opaque` are two remedies in two sentences; **the native refusal as a property over the classifier's whole output**, with the ladder's non-vacuity control beside it; the refusal names a non-Xray authority per candidate |
| `…panels.hicasso-causal-cljs-test` | node (reactive substrate) | the seven links on a REAL interaction through the real commit seam and the real router; links 1–4 evidenced and 5–7 host-opaque with three distinct authorities; the 2→3 join `:uncorrelated` while the 1→2 join is `:evidenced`; **four mutation rows, each with its positive control**; the loss chips reach the page under distinct testids and change between two genuinely different window states; the advisor answers on the running app and still refuses the ladder; advice and slice come from ONE turn |
| `…panels.hicasso-skip-semantics-cljs-test` | node + JVM | **both public results, off ONE window** — a skip-only window is `:memo-hits-only` / `:host-opaque` with `:runs` 0, routed to measure-first and never to the retention knob, while the same window's slice holds `[]` recomputes and one `:skipped`; a bundle carrying all four `:rf.sub` operations gives the advisor's recompute COUNT and the slice's recompute ROSTER the same reading; the three unattributed states are pairwise distinct and exactly one names `:rf.trace/events-retained`; an untagged RUN beside a tagged skip still degrades to `:unknown` with a `:dropped` of 1 |

The pair-in-one-row shape of the third suite is the point: an advisor-only row
would go green against a causal slice that had drifted back, and a causal-only
row against an advisor that had. Its red demonstration is the pre-repair
predicate planted in both places — 17 failures across six of its rows, naming
`:cap` where the window held evidence and a five-element roster where two
recomputes ran.

All three suites run in the existing `:node-test` build (`tools/xray/test` is
already on its source paths) and the two `.cljc` ones additionally under
`tools/xray`'s `clojure -M:test`. No new build id, no new `:dev-http` port, no
`implementation/shadow-cljs.edn` change.

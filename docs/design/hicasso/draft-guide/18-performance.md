# Performance

"Is it fast enough?" is not a feeling. [Hicasso](glossary.md#hicasso) ships with budgets stated in
user-visible terms, and performance work is the method that meets them:
measure, attribute, change the smallest thing, then verify again. This
chapter gives you the budgets, the ladder, the loop, and one example with
every cost counted, so that you can compute a cost instead of guessing at
it.

> **Most apps never leave rung 1, and that is the intended result of the
> design.**

## The budgets

The budgets come first, because they are the only honest definition of
"slow". Each budget is a fact that a user can notice, measured at the
percentile that represents real hardware:

| Budget | The user-visible fact |
|---|---|
| Discrete interactions | Click, toggle, submit reach the next paint within **50 ms p95** (100 ms p99) |
| Keystroke echo | A [controlled field](glossary.md#controlled-field) is correct **in the same turn** and visibly echoed within **one 60 Hz frame** at p95 |
| Broad operations | Bulk replace, big filter flips complete within **100 ms p95**, unless explicitly classified as background work |
| Drag and animation | Stay inside the frame budget — normally by keeping high-rate mechanics host-local ([Ephemeral state](11-ephemeral-state.md)) |
| Narrow updates | Body work scales with **changed rows**, not mounted rows |
| Teardown | **Zero residue** after quiescence — long sessions do not accumulate |

Hold your own app to these numbers. The rest of this chapter matters only
when one of them is missed: a screen that meets its budgets is fast enough,
whatever score a synthetic benchmark reports.

!!! note "Measure under production conditions"
    Verify budgets on **production builds**, on mid-tier hardware, at p95
    across runs. A dev build carries diagnostics that production erases, and
    your machine is faster than your users' machines. Your best run is not a
    measurement.

The teardown row needs one expansion, because people forget that this budget
is user-visible. An SPA session is long, and leave-and-return cycles must
not accumulate residue. [Hicasso](glossary.md#hicasso) guarantees its own side: abandoned renders
own nothing, and unmount releases every read. Your side is the escapes. A
host or island that acquires an SDK, a listener, or a timer releases it on
teardown ([Interop](09-interop.md)), and the [test kit](glossary.md#test-kit)'s `assert-clean!`
proves the whole claim after quiescence ([Testing](14-testing.md)).

## Why most apps never leave rung 1

Rung 1 is ordinary [Hicasso](glossary.md#hicasso): write ordinary views, read at the point of use,
put [intents](glossary.md#intent) in the markup, and ship. Do not pre-optimise, and do not build a
second architecture for problems that you have not measured — the
interpreter is not the cost. Interpreted [lowering](glossary.md#lowering) is fast. When a screen
misses a budget, the cost almost always lives somewhere else: **where the
reads sit, how many [boundaries](glossary.md#boundary) exist, what React itself does, or what a host
does**. Per read, Hicasso's retained cost is low; the read *topology* is
what you chose. That is why the ladder's second rung is "move your reads",
not "abandon Hiccup".

A small share of view code turns out to be hot — often none, rarely more
than about two percent: a cell that renders constantly, a large collection
under broad writes, a vendor widget with its own behavior. For that code the
ladder continues downward, explicitly and locally. The share is an observed
outcome, never a quota. An escape is a local island, never a general style.

## The ladder

One table shows the whole gradient. Every rung is code that is visible in a
diff. There is no `:fast` flag, no compile mode, and no setting that changes
what Hiccup means:

| Rung | What you write | Reach for it when | Taught in |
|---|---|---|---|
| 1 — Ordinary [Hicasso](glossary.md#hicasso) | Hiccup, [`h/sub`](glossary.md#hsub) at point of use, [intents](glossary.md#intent) as data | Always. Start every feature here | [Views and reads](02-views-and-reads.md) |
| 2 — Tuned topology | The same language; [boundary](glossary.md#boundary) placement, keys, read shape | A named boundary or read pressure | [Lists and collections](06-lists-and-collections.md) |
| 3 — Direct React return | A [`defview`](glossary.md#defview) returns an [`n/$`](glossary.md#n-dollar) element; frame, reads, memo stay | Lowering itself is the measured owner | [The native tier](10-native-tier.md) |
| 4 — Named [native island](glossary.md#native-island) | [`n/defcomponent`](glossary.md#ndefcomponent) (or UIx) under the same root and frame | Hooks, vendor behavior, reconciliation, or high-rate local work dominate | [The native tier](10-native-tier.md) |
| 5 — Native screen | A React-first screen authored natively | The screen is React-shaped by nature | [The native tier](10-native-tier.md) |

The full lessons for rungs 3–5 are in [chapter 10](10-native-tier.md). This
chapter owns the decision: when you step down, and when an escape must come
back out.

## The working loop

Performance work that skips a step of this loop is guessing. The loop:

1. **Reproduce.** Name one slow interaction, and script it so that it runs
   the same way in every run. "The app is slow" is not actionable; "expedite
   on a 1,000-row table takes 180 ms" is actionable.
2. **Attribute.** Correlate the event with the work that ran — which
   subscriptions recomputed, which [boundaries](glossary.md#boundary) became invalid, how much body
   work, then commit and paint. Xray's [explain-render](glossary.md#explain-render) and read attribution
   answer the first half. Its [hot-view advisor](glossary.md#hot-view-advisor) classifies the pressure —
   computation, topology, [lowering](glossary.md#lowering), React, or layout — and recommends the
   smallest credible remedy ([Diagnostics](15-diagnostics.md)).
3. **Tune topology.** Adjust boundary placement, keys, and read shape —
   rung 2 ([Lists and collections](06-lists-and-collections.md)). Most hot
   paths are fixed here, and the loop ends.
4. **Compare direct output** if the measurement names lowering as the owner
   — rung 3.
5. **Isolate an island** if hooks, vendor behavior, reconciliation, or
   high-rate local work is the owner — rung 4. Choose the route by the
   component's needs, not by habit.
6. **Re-verify.** The change must preserve DOM and [intent](glossary.md#intent) behavior, focus
   and selection, frame routing, SSR/hydration, and cleanup — and the budget
   must flip from fail to pass. A faster screen that behaves wrongly is
   wrong.
7. **Keep or remove.** The escape stays only if it passes the benefit rule
   below.

Three instruments cover attribution, and all three erase from production:

| Instrument | The question it answers |
|---|---|
| **Xray** | Which reads changed, which boundaries ran and why, fan-out and read-set churn; the advisor's pressure classification |
| **React DevTools Profiler** | Which components committed — every boundary is a real React function component under its own display name |
| **Browser Performance panel** | `rf:*` User-Timing measures for events, subscriptions, effects and renders, on the Timings track next to paint |

A compile-time flag gates the `rf:*` measures, and the default is off. Build
with `:closure-defines {re-frame.performance/enabled? true}` to emit them; a
build without the flag carries no timing code at all. The runtime delivers
entries and does not retain them: a live `PerformanceObserver` and the
DevTools timeline see every measure, but a later `getEntriesByType` poll
finds nothing. A bail-out emits no measure — the absence records that no
work ran. A StrictMode double-invoke emits twice, because the body ran
twice.

Attribute *before* you change architecture. Unattributed "slow" is usually a
rung-2 problem, and an island built on a misattributed cause makes the code
worse without making it faster.

## The escape-benefit rule

An escape is a standing cost: a second semantics in that region, a body that
structural tests cannot see into, a diff that reviewers read more slowly.
The rule that justifies the cost: **an escape stays only if it recovers at
least 20%, saves at least 2 ms at p95, or converts a failed user-visible
budget into a pass.** Otherwise it comes out — simplified back to the rung
above it. The thresholds never widen to let an island stay, and "it might
matter someday" is not one of the three conditions. Re-run the comparison
when the surrounding code changes materially. An escape that no longer meets
a threshold is ordinary code that still pays escape costs.

## The cost of one keystroke: the editor and the grid

The budgets become concrete when you trace one keystroke through the
machine. Take the smallest complete example — a [controlled field](glossary.md#controlled-field) in a
four-field article editor:

```clojure
(ns app.editor
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-event :editor/set-title
  (fn [{:keys [db]} [_ typed]]
    {:db (assoc-in db [:editor :title] typed)}))

(rf/reg-sub :editor/title
  (fn [db _] (get-in db [:editor :title])))

(h/defview title-field [_]
  [:input {:type     :text
           :value    (h/sub [:editor/title])
           :on-input [:editor/set-title ::h/value]}])
```

One keystroke in the title field takes this path, entirely inside the
browser's own input event:

1. The DOM event fires. The [intent](glossary.md#intent) dispatches **synchronously** in the
   discrete event — no queue sits between the key and the handler.
2. One handler runs, and it writes **one address**.
3. The subscriptions that read that address recompute. Equality gates stop
   every subscription whose output did not change. **One** subscription's
   value changes.
4. The runtime notifies the [boundaries](glossary.md#boundary) whose reads changed. **One** body
   runs — the title field's body. The other three fields receive no
   notification.
5. React commits, and the controlled converge applies the value **and the
   caret** before the browser finishes the event
   ([Controlled inputs](04-controlled-inputs.md) owns that law).
6. The next frame paints the echo.

Read the counts from the walk: 1 write, 1 changed subscription, 1 body run,
and a commit scoped to one input. **Write amplification — the number of
boundary bodies that run per state write — is 1.** That is the mechanical
meaning of "narrow work scales with changed rows", and it is why the editor
stays far inside its frame budget.

Now scale the same shape to a 100-cell controlled grid:

```clojure
(h/defview grid-cell [{:keys [row col]}]
  [:input {:value    (h/sub [:grid/cell row col])
           :on-input [:grid/edit row col ::h/value]}])
```

Each cell reads its **own** address, so a keystroke in one cell is the same
walk: 1 write, 1 changed subscription, 1 body. The runtime never notifies
the other 99 cells — not "render and bail", but *never notified*. Typing
cost is constant in grid size. That is the narrow-update budget passing by
construction, and it came from a rung-2 choice: fine reads on a typing
surface.

Here is the same grid with the wrong shape, so that you can see the costs
move:

```clojure
;; Don't — a typing surface reading coarse
(h/defview grid [_]
  (let [cells (h/sub [:grid/all-cells])]   ;; every keystroke changes this value
    [:table
     [:tbody
      (for [cell cells]                    ;; cells render from props now
        [grid-cell {:key (:id cell) :cell cell}])]]))
```

Now every keystroke recomputes the whole-grid view-model, runs the parent
body, and runs a props compare over 100 cells. The bail-out keeps 99 bodies
from running — unless a cell prop carries a fresh closure, in which case
nothing bails and write amplification is 100. At this size the screen can
still pass the frame budget, narrowly. The *shape* is still wrong, because
it converts grid size into per-keystroke cost. Coarse reads are for cheap
mount and bulk replacement. They are the wrong topology for a surface that
changes 100 times a minute, one cell at a time.

The last cost item is **event volume**: controlled means one dispatch per
keystroke per cell. That is the cost of the contract, and the walk above
shows why the cost is affordable. When no consumer needs the intermediate
values (a search box that feeds a debounced query), do not pay the cost:
leave the input uncontrolled and commit on blur
([Controlled inputs](04-controlled-inputs.md)). When something must react to
the value more slowly, debounce the *consumers* of the committed value. A
debounced write path drops characters, so never debounce the write path
itself.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| "The app feels slow" and nothing else | No reproduction, so nothing is attributable | Script one named interaction; run the loop from step 1 |
| One keystroke or event runs hundreds of bodies | Read topology — a value read too high, or a coarse read on a narrow-update surface | Rung 2: [Lists and collections](06-lists-and-collections.md) |
| Characters drop under fast typing | Something async sits between keystroke and commit — a debounced write, a queued effect | Keep the controlled write synchronous; debounce consumers of the value |
| An island shipped and the interaction is no faster | The pressure owner was misattributed — usually reads or event volume, not [lowering](glossary.md#lowering) | Re-attribute with Xray; remove the island if it fails the benefit rule |
| Fast on your machine, budget missed in the field | Dev build, fast hardware, best-run numbers | Production build, mid-tier hardware, p95 across runs |
| Heap grows across leave-and-return cycles | An escape acquires without a paired release | Pair acquire/teardown at the host ([Interop](09-interop.md)); prove it with `assert-clean!` ([Testing](14-testing.md)) |
| An escape clears no threshold but "feels safer to keep" | The benefit rule read backwards — escapes are a cost held against a measured gain | Remove the escape; that is the rule working correctly |
| Still slow after going native | Wrong layer — the work moved but the owner did not | Return to step 2; the advisor classifies where the time goes |

## When not to optimise

- **Without a reproduction.** A feeling, or a fear of the interpreter, is
  not a named interaction. Check the budgets first; if none is missed, there
  is no work to do.
- **When the real issue is event volume.** A dense controlled surface is a
  dispatch-rate question with its own answers
  ([Controlled inputs](04-controlled-inputs.md)) — no rung fixes it.
- **When the plan is "hooks everywhere, for speed."** That plan is a rewrite
  presented as an optimisation. The ladder exists so that the hot 2% can be
  excellent while the other 98% pays nothing for it.

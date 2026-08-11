# Performance

Performance work starts with a user-visible budget, not a general feeling that
the framework might be slow.

Measure one interaction, identify the cost owner, change the smallest relevant
piece, and verify the result under the same conditions. Most applications stay
on ordinary Hicasso throughout that process.

## User-visible budgets

| Budget | Target |
| --- | --- |
| Discrete interaction | Click, toggle, or submit reaches the next paint within **50 ms p95** and **100 ms p99** |
| Controlled keystroke | State converges in the same browser turn and the visible echo arrives within **one 60 Hz frame at p95** |
| Broad operation | Bulk replacement or a large filter change completes within **100 ms p95**, unless explicitly classified as background work |
| Drag or animation | Remains inside the frame budget, usually by keeping high-rate mechanics inside a host |
| Narrow update | View-body work scales with changed rows or cells, not all mounted rows or cells |
| Teardown | Returns to **zero additional residue** after quiescence |

A screen that meets its user-visible budgets is fast enough even when a
synthetic benchmark says another implementation can execute an isolated
operation faster.

!!! note "Measure production behaviour"
    Use production builds, mid-tier hardware, and p95 across repeated runs.
    Development diagnostics add work that production removes. Your fastest
    machine and best run are not representative measurements.

Teardown is part of performance. Long-lived applications must not accumulate
subscriptions, timers, listeners, or SDK handles as users leave and revisit
screens. Hicasso releases its own committed reads. Hosts and native islands
must release what they acquire ([Interop](09-interop.md)). Prove the complete
claim with `hm/assert-clean!` ([Testing](14-testing.md)).

## Start with ordinary Hicasso

The normal implementation is:

- Hiccup;
- `h/sub` where the value is used;
- event intents as data;
- ordinary view boundaries.

Do not optimise for an unmeasured cost. When a page misses a budget, the
problem is often read placement, view topology, React work, host behaviour, or
browser layout—not the Hiccup walk itself.

The retained cost of one read is usually small. The number and placement of
reads are design choices, which is why topology is the first optimisation
step.

## The performance ladder

Each step is explicit in source. There is no `:fast` mode and no build setting
that changes what Hiccup means.

| Level | Implementation | Use it when | Details |
| --- | --- | --- | --- |
| 1. Ordinary Hicasso | Hiccup, point-of-use reads, data intents | Always start here | [Views and reads](02-views-and-reads.md) |
| 2. Tune topology | Same language; change boundaries, keys, and read shape | A measured interaction invalidates too much work | [Lists and collections](06-lists-and-collections.md) |
| 3. Direct React return | A `defview` returns `n/$` while keeping its Hicasso frame, reads, and memo | Hiccup lowering is the measured owner | [The native tier](10-native-tier.md) |
| 4. Native island | `n/defcomponent` or UIx under the same root and frame | Hooks, vendor behaviour, reconciliation, or high-rate local mechanics dominate | [The native tier](10-native-tier.md) |
| 5. Native screen | A React-first screen under the same state model | The screen is React-shaped by design | [The native tier](10-native-tier.md) |

This page decides whether a step is justified. The native-tier chapter teaches
the code.

## The measurement loop

Do not skip a step:

1. **Reproduce.** Script one named interaction. “The app is slow” is not a
   reproducible case. “Expedite on a 1,000-row table takes 180 ms” is.
2. **Attribute.** Identify changed subscriptions, notified views, body work,
   React commit, and browser paint. Use Xray to classify the pressure as
   computation, topology, lowering, React, or layout
   ([Diagnostics](15-diagnostics.md)).
3. **Tune topology.** Change read placement, keys, boundaries, or collection
   shape. Most cases end here.
4. **Compare a direct React return** only when lowering is the measured owner.
5. **Build a native island** only when the owner is hooks, vendor internals,
   reconciliation, or high-rate local work.
6. **Re-verify behaviour and performance.** Preserve DOM and intent behaviour,
   focus, selection, frame routing, SSR/hydration, cleanup, and the original
   budget.
7. **Keep or remove the escape.** Apply the benefit rule below.

Use the instruments for the questions they can answer:

| Instrument | Question |
| --- | --- |
| Xray | Which reads changed, which views ran, why they ran, fan-out, churn, and likely pressure class |
| React DevTools Profiler | Which real React components committed |
| Browser Performance panel | Event, subscription, effect, render, layout, and paint timing on one timeline |

### Optional `rf:*` User Timing

Runtime User Timing is disabled by default. Enable it at compile time:

```clojure
:closure-defines {re-frame.performance/enabled? true}
```

A build without the flag carries no timing code.

The runtime delivers entries to `PerformanceObserver` and browser DevTools but
does not retain them for later polling. A subsequent `getEntriesByType` call
may find nothing even though the live observer saw the entries.

A view that bails out emits no measure because its body did not run.
Development StrictMode emits twice when the body runs twice.

## Keep an escape only when it earns its cost

A native escape adds another local authoring model, hides structure from
semantic tests, and increases review cost. Keep it only when it satisfies at
least one of these conditions:

- recovers **20% or more** of the measured interaction;
- saves **2 ms or more at p95**;
- converts a failed user-visible budget into a pass.

Otherwise remove it and return to the previous level. “It may matter later” is
not a fourth condition. Re-run the comparison when the surrounding path
changes materially.

## Trace one controlled keystroke

```clojure
(ns app.editor
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-event :editor/set-title
  (fn [{:keys [db]} [_ typed]]
    {:db (assoc-in db [:editor :title] typed)}))

(rf/reg-sub :editor/title
  (fn [db _]
    (get-in db [:editor :title])))

(h/defview title-field [_]
  [:input
   {:type     :text
    :value    (h/sub [:editor/title])
    :on-input [:editor/set-title ::h/value]}])
```

One keystroke follows this path:

1. The DOM input event fires and the intent dispatches synchronously.
2. One handler writes one app-db address.
3. Subscriptions that depend on that address recompute; equality stops
   unchanged outputs.
4. The title subscription changes and notifies the title-field view.
5. One view body runs. Other fields are not notified.
6. React commits and Hicasso converges value and caret before the event turn
   ends.
7. The next frame paints the echo.

The path has one write, one changed subscription, and one view-body run.
**Write amplification** is the number of view bodies that run per state write;
here it is 1.

## Scale the same topology to a grid

```clojure
(h/defview grid-cell [{:keys [row col]}]
  [:input
   {:value    (h/sub [:grid/cell row col])
    :on-input [:grid/edit row col ::h/value]}])
```

Each cell reads its own address. Editing one cell still produces one write, one
changed subscription, and one cell-body run. The other cells are not rendered
and then bailed out; they are never notified. Typing cost therefore remains
constant as the grid grows.

A coarse read has a different cost:

```clojure
;; Don't use this shape for a narrow, high-frequency typing surface.
(h/defview grid [_]
  (let [cells (h/sub [:grid/all-cells])]
    [:table
     [:tbody
      (for [cell cells]
        [grid-cell
         {:key (:id cell)
          :cell cell}])]]))
```

Every keystroke now:

- recomputes the whole-grid view model;
- runs the parent body;
- compares props for every cell;
- may run every cell body when props contain fresh functions or other
  identity-based values.

Equal persistent props may let 99 of 100 cells skip their bodies, but the
whole-grid sweep remains proportional to grid size. Coarse reads are useful
for cheap mount or bulk replacement, not for one-cell-at-a-time editing.

## Event volume is a separate decision

A controlled field dispatches once per keystroke. That is the cost of making
intermediate text application-visible and keeping it correct.

When no consumer needs the intermediate text, use an uncontrolled input and
commit on blur. When a slower consumer exists, debounce the **consumer** of the
committed value. Do not debounce the controlled write itself; an asynchronous
write path can drop or reorder characters
([Controlled inputs](04-controlled-inputs.md)).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| “The app feels slow” is the only description | There is no repeatable interaction to attribute | Script one user action and start at measurement step 1 |
| One keystroke or event runs hundreds of view bodies | A read lives too high, or a coarse model is used for narrow updates | Change the topology as described in [Lists and collections](06-lists-and-collections.md) |
| Fast typing drops characters | A timeout, debounce, queue, or effect sits between input and app-db commit | Keep the controlled write synchronous and debounce downstream consumers |
| A native island shipped but the interaction did not improve | The original cost was misattributed | Re-run attribution and remove the island when it fails the benefit rule |
| The feature is fast locally but misses field budgets | Measurement used a development build, fast hardware, or best-run values | Test the production build on mid-tier hardware and report p95 |
| Heap or listeners grow after leave-and-return cycles | A host or island acquires without matching teardown | Pair attach and cleanup, then prove zero residue with `assert-clean!` |
| An escape clears no threshold but is kept “for safety” | The benefit rule was ignored | Remove it; an unearned escape is permanent complexity |
| The path remains slow after moving native | The measured owner was not construction | Return to attribution and fix the actual pressure class |

## When not to optimise

Do not optimise:

- without a scripted reproduction;
- when every user-visible budget already passes;
- by replacing the view layer when the issue is event volume or read topology;
- by introducing hooks everywhere “for speed.” That is a rewrite, not a local
  optimisation.

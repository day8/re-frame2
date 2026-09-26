# Performance

Most Fresco screens are fast enough written the ordinary way: Hiccup, `h/sub`
where the value is used, and event vectors for handlers. Performance work
starts when a specific interaction misses a user-visible budget. Measure that
interaction, find what costs the time, change the smallest relevant piece, and
measure again.

## Trace one controlled keystroke

```clojure
(ns app.todos
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]))

(rf/reg-event :todo.ui/set-draft
  (fn [{:keys [db]} [_ typed]]
    {:db (assoc db :todo.ui/draft typed)}))

(rf/reg-sub :todo.ui/draft
  (fn [db _]
    (:todo.ui/draft db)))

(h/defview new-todo-field [_]
  [:input
   {:type     :text
    :value    (h/sub [:todo.ui/draft])
    :on-input [:todo.ui/set-draft ::h/value]}])
```

One keystroke follows this path:

1. The DOM input event fires and the event dispatches synchronously.
2. One handler writes one app-db key.
3. Subscriptions that depend on app-db recompute. Those whose output is equal
   to the previous value stop there.
4. `:todo.ui/draft` changes and notifies `new-todo-field`.
5. That one view body runs. Other views are not notified.
6. React commits, and Fresco restores the value and caret before the event
   turn ends.
7. The next frame paints the typed character.

That is one write, one changed subscription, and one view body.

## Keep reads narrow in lists

The same shape scales to a list of editable titles when each row reads its
own todo:

```clojure
(h/defview todo-title-field [{:keys [id]}]
  [:input
   {:value    (:title (h/sub [:todo/by-id id]))
    :on-input [:todo/rename id ::h/value]}])
```

Editing one title is still one write and one row body. The other rows'
subscriptions return equal values, so those rows are never notified. Typing
cost stays constant as the list grows.

A read placed higher up costs more:

```clojure
;; Don't do this for a list you type into.
(h/defview todo-list [_]
  [:ul
   (for [todo (h/sub [:todo/all])]
     [todo-row {:key (:id todo) :todo todo}])])
```

Every keystroke now recomputes `:todo/all`, runs the `todo-list` body, and
compares props for every row. Rows whose props are equal skip their bodies,
but the sweep is still proportional to list size. A read like this is fine for
mounting or bulk replacement. It is the wrong shape for editing one row at a
time. [Lists and collections](06-lists-and-collections.md) covers read
placement in detail.

## Event volume is a separate decision

A controlled field dispatches once per keystroke. That is the cost of making
the text visible to the application while it is typed.

When nothing needs the intermediate text, use an uncontrolled input and commit
on blur. When a slow consumer needs it, debounce the consumer of the committed
value. Do not debounce the controlled write itself: an asynchronous write path
can drop or reorder characters ([Controlled inputs](04-controlled-inputs.md)).

## Measure against a budget

| Budget | Target |
| --- | --- |
| Discrete interaction | Click, toggle, or submit reaches the next paint within 50 ms p95 and 100 ms p99 |
| Controlled keystroke | The typed character is painted within one 60 Hz frame at p95 |
| Broad operation | Bulk replacement or a large filter change completes within 100 ms p95, unless it is background work |
| Drag or animation | Stays inside the frame budget, usually by keeping high-rate work inside a host |
| Narrow update | View bodies run and rows of markup built both scale with the changed rows, not all mounted rows |
| Teardown | No leftover subscriptions, timers, or listeners after the screen is left |

A screen that meets these budgets is fast enough, even if a benchmark shows
another implementation doing an isolated operation faster.

The narrow-update row needs both counters. A parent that rebuilds every row
for a one-row change does that work inside one body, so counting view bodies
alone reports a pass. Count bodies in a mounted test with
`(hm/bodies-run #(hm/dispatch-and-settle! m [:todo/rename 7 "x"]))`, which
returns how many view bodies ran. Settle inside the function, or the count is
short.

!!! note "Measure production behaviour"
    Use production builds, mid-tier hardware, and p95 across repeated runs.
    Development diagnostics add work that production removes.

Teardown matters in long-lived applications, which must not accumulate
subscriptions, timers, listeners, or SDK handles as users leave and revisit
screens. Fresco releases its own reads; hosts and islands must release what
they acquire ([Interop](09-interop.md)). Check it with `hm/assert-clean!` from
`re-frame.fresco.test.mounted` ([Testing](15-testing.md)).

## The measurement loop

1. **Reproduce.** Script one named interaction. "The app is slow" cannot be
   reproduced; "toggling a todo in a 1,000-item list takes 180 ms" can.
2. **Attribute.** Find which subscriptions changed, which views ran, and how
   long React commit and browser paint took. Xray attributes the cost to
   subscription computation or read topology; when neither explains it, Xray
   names Hiccup conversion, React and layout as candidates it cannot separate
   ([Diagnostics](16-diagnostics.md)). Measure those with the `rf:render` User
   Timing, the React Profiler and the browser Performance panel.
3. **Tune topology.** Change read placement, keys, view boundaries, or
   collection shape. Most cases end here.
4. **Return a React element directly** only when Hiccup conversion is the
   measured cost.
5. **Build a React island** only when the cost is hooks, vendor internals,
   reconciliation, or high-rate local work.
6. **Re-verify.** Check DOM and event behaviour, focus, selection, frame
   routing, SSR and hydration, cleanup, and the original budget.
7. **Keep or remove the change** using the rule below.

| Instrument | Answers |
| --- | --- |
| Xray | Which reads changed, which views ran and why, fan-out, churn, and likely cost class |
| React DevTools Profiler | Which React components committed |
| Browser Performance panel | Event, subscription, effect, render, layout, and paint timing on one timeline |

## The performance ladder

The five rungs, and what each costs in tests, tools and server rendering, are
in [The escape ladder](escape-ladder.md#the-performance-ladder).

### Keep an escape only when it earns its cost

Rungs 3 to 5 add another authoring model, hide structure from semantic tests,
and make review harder. Keep one only when it:

- recovers 20% or more of the measured interaction,
- saves 2 ms or more at p95, or
- turns a failed budget into a pass.

Otherwise remove it and return to the previous rung. Re-run the comparison
when the surrounding code changes materially.

This rule applies to an escape taken for speed. An escape taken because there
is no ordinary way to write the thing at all, such as a foreign React library
or an SDK that owns its own DOM node, has nothing to be compared against
([The escape ladder](escape-ladder.md#the-rule-an-interoperability-escape-is-not-judged-by)).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| "The app feels slow" is the only description | There is no repeatable interaction to measure | Script one user action and start at step 1 of the measurement loop |
| One keystroke or event runs hundreds of view bodies | A read sits too high, or a coarse read serves a narrow update | Move the read down, as in [Lists and collections](06-lists-and-collections.md) |
| Fast typing drops characters | A timeout, debounce, queue, or effect sits between the input and the app-db write | Keep the controlled write synchronous and debounce downstream consumers |
| A React island shipped but the interaction did not improve | The cost was misattributed | Re-run attribution, and remove the island if it fails the rule above |
| Fast locally but misses budgets in the field | Measured on a development build, fast hardware, or best runs | Test the production build on mid-tier hardware and report p95 |
| Heap or listeners grow after leaving and revisiting a screen | A host or island acquires something it never releases | Pair each attach with a cleanup, then check with `hm/assert-clean!` |

## When not to optimise

Do not optimise:

- without a scripted reproduction;
- when every user-visible budget already passes;
- by replacing the view layer when the problem is event volume or read
  placement;
- by introducing hooks everywhere for speed. That is a rewrite, not an
  optimisation.

## Advanced

### `rf:*` User Timing

Runtime User Timing is off by default. Enable it at compile time:

```clojure
:closure-defines {re-frame.performance/enabled? true}
```

A build without the flag contains no timing code.

Each `h/defview` then emits a `rf:render:<view-id>` measure, where the id is
the declaration's `"<namespace>/<name>"`, for example
`rf:render:app.todos/todo-row`. In development builds React DevTools shows the
same string as the component's `displayName`.

- Only `defview`s are measured. A plain function called from a body has no
  entry; its cost is inside the enclosing view's entry.
- A view that bails out emits nothing, because its body did not run.
- StrictMode emits twice when the body runs twice. When the runtime re-runs a
  body internally to settle its reads, the view still emits one entry.
- A body that throws still emits, so an entry shows a render was attempted,
  not that it completed.

Entries go to `PerformanceObserver` and browser DevTools but are not retained.
A later `getEntriesByType` call may find nothing even though a live observer
saw them.

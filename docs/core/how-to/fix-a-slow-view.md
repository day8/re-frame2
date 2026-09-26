# Find and fix a slow view

A click hitches or typing stutters because some [view](../glossary.md#view) is doing too much work. This recipe finds that view and fixes it without adding memoisation by hand.

Views read [subscriptions](../glossary.md#subscription), which chain into a [derivation graph](../glossary.md#the-derivation-graph), and the framework already caches every node: a subscription recomputes only when an input it reads produces a new value, compared with `=`. So a slow view nearly always has expensive work on the wrong side of that `=` check. The fix is to move the work, not to add caching.

Work through the steps in order; most hunts end at step 2.

??? info "For JavaScript developers"

    This page covers the ground of `memo`, `useMemo`, and `useCallback`. Here the framework owns memoisation, and your job is placement: which subscription reads `app-db`, and which derives from another subscription. Placement can be checked in code review, not only in a flame graph.

## 1. Observe: name the shape of the slow

The React DevTools profiler still works, but a flame graph shows which components rendered, not why their data changed. Every re-render here traces back through a subscription to an [event](../glossary.md#event), and [Xray](../glossary.md#xray) shows that chain.

Attach Xray ([Debug with Xray](../../xray/index.md)), reproduce the slow interaction once, select the newest event row, and open the **Views** tab. It lists every view that re-rendered in that [pipeline run](../glossary.md#run) with its render time, and nests under each view the subscriptions it read; each sub can be drilled into to see why it re-ran, back to the causing event. Mounted, re-rendered, and unmounted views are grouped separately, and a re-rendered row names its cause: `← :sub-id` when a subscription's value changed, `← props` when the parent passed different arguments.

Look for one of two shapes:

- **One wide row.** A single view, or one subscription under it, accounts for the time. The work is misplaced: go to step 2.
- **A cloud of rows.** Dozens or hundreds of views re-rendered for a change that concerned one of them. That is a re-render storm: go to step 3.

If the dev build feels fine and only production is slow, go to step 4.

The per-view milliseconds are wall-clock reads around each registered view's render, inflated by the dev build's tracing. Use them to rank rows within one capture; for numbers that match what users feel, use step 4.

## 2. Move the work behind the equality check

As [Subscriptions](../subscriptions.md) explains, a **layer-1** sub (an extractor) reads [app-db](../glossary.md#app-db) directly and pulls out a slice, and a **layer-2** sub reads other subscriptions and is where derived work such as sorting, filtering, and formatting belongs. When app-db changes, every extractor re-runs to re-check its slice; if the result is `=` to last time, [nothing downstream recomputes](../subscriptions.md#the-equality-gate). Expensive work belongs on the layer-2 side of that check.

So first ask whether any layer-1 sub computes something. An extractor runs on every app-db change, so work inside one runs on every keystroke in every unrelated form:

```clojure
;; Don't do this: an extractor re-runs on every app-db change, so this
;; sorts every todo on every keystroke anywhere in the app.
(rf/reg-sub :todo/sorted-ids
  (fn [db _]
    (->> (vals (:todos db))                          ;; {id -> todo}
         (sort-by :title)
         (mapv :id))))
```

Split it. A small extractor decides whether anything changed, and a layer-2 sub does the work only when it did:

```clojure
;; Same code, after the equality check.
(rf/reg-sub :todo/todos
  (fn [db _] (:todos db)))

(rf/reg-sub :todo/sorted-ids {:inputs [[:todo/todos]]}
  (fn [[todos] _]
    (->> (vals todos)
         (sort-by :title)
         (mapv :id))))
```

`:todo/sorted-ids` now derives from the value `:todo/todos` extracted rather than from `db`. When some other key in app-db changes, `:todo/todos` re-runs, returns an `=` map, and `:todo/sorted-ids` doesn't recompute, so the sort doesn't run.

!!! warning "Gotcha: a mistyped `:inputs` id makes the result wrong, not slow"

    The new `:inputs` edge names another sub by id. If the id is wrong (a typo, or a sub not yet registered), nothing throws: the runtime emits a `:rf.error/no-such-sub` [error record](../glossary.md#error-record) (recovery `:replaced-with-default`) to your error listeners and feeds `nil` for that input. The list renders empty, or a downstream `nil` throws somewhere unrelated. If a sub you just split returns nothing, check its `:inputs` ids against your `reg-sub` names. A malformed `:inputs` literal is rejected at registration with `:rf.error/reg-sub-bad-args`. An `:inputs` producer function that throws raises `:rf.error/sub-input-fn-exception`, and one that returns something other than a vector of query vectors raises `:rf.error/sub-input-fn-bad-return`.

The same mistake happens one level up. Computation in a view body runs on every render of that view, including renders caused by its ancestors. Move sorting, filtering, and formatting into a layer-2 sub, where it runs once per input change and every consumer shares the result; the view only walks data and returns hiccup ([Views](../views.md)).

To confirm the fix, dispatch the same event with the Views tab open: the sub's drill shows it returning its cached value, and unrelated typing no longer re-runs the sort.

!!! note "One derivation, read in many places?"

    A subscription is a cache for views. When a handler needs the same derived value, don't read the sub with `subscribe-once` in the handler body: that read is one the handler never declared, and it recomputes from scratch whenever no mounted view holds the query. Make the value a [flow](../flows.md) instead: it is computed once per app-db write, [stored in app-db](../glossary.md#flow), and every reader, view or handler, reads that one result.

!!! note "When placement isn't enough"

    Some work is expensive wherever it runs: parsing megabytes, a simulation step, diffing two trees. While it runs, the browser's single thread does nothing else, so no repaints, clicks, or spinner. Split it into chunks driven by a [machine](../../machines/concepts.md), or move it to a Web Worker. Do this only after confirming the work is correctly placed and still slow.

## 3. Break up the re-render storm

A cloud of rows in the Views tab almost always means a parent passes each child more state than it needs:

```clojure
;; Don't do this: every row receives its whole todo map.
(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todo/all])]           ;; a vector of full todo maps
     ^{:key (:id todo)} [todo-item todo])])
```

Toggle one todo in a 200-item list and `:todo/all` is a new vector, because one map inside it changed. `todo-list` re-renders and builds hiccup for all 200 rows. The 199 untouched rows pass their `=` prop checks and keep their DOM, but those checks still run on full todo maps on every click. That hiccup and those comparisons are the hitch.

Pass each row an id, and let the row subscribe to its own slice:

```clojure
;; Rows get an id; each subscribes to exactly what it renders.
(rf/reg-view todo-list []
  [:ul
   (for [id @(subscribe [:todo/sorted-ids])]
     ^{:key id} [todo-item id])])

(rf/reg-sub :todo/by-id
  (fn [db [_ id]] (get-in db [:todos id])))

(rf/reg-view todo-item [id]
  (let [{:keys [title done?]} @(subscribe [:todo/by-id id])]
    [:li
     [:input {:type "checkbox" :checked done?
              :on-change #(dispatch [:todo/toggle id])}]
     title]))
```

On a toggle, one todo's map changes, so `:todo/sorted-ids` recomputes, but it returns an `=` id vector and `todo-list` doesn't re-render. `[:todo/by-id id]` changes for one id, so one row re-renders. The Views tab shows one row where the cloud was.

`^{:key id}` gives each row a stable identity, so inserting or removing a todo is diffed by identity instead of position; without it, one deletion at the top re-renders every row below it. The inline `#(dispatch …)` on the checkbox is fine as written, because replacing a listener on a DOM element is cheap (see [Stable callbacks](#stable-callbacks-only-with-a-measurement) for when it isn't).

!!! warning "Gotcha: key by the id, not by the value that changes"

    Keying a row by `(str id done?)` would remount the row every time it is toggled. Don't key by index either: deleting row 0 then looks like every row's data changed, which recreates the storm. A random key remounts every row on every render. Use the todo's id.

??? info "For JavaScript developers"

    This is the same move as a per-item `useSelector(s => s.todos[id])`: push the selector down to the leaf so a change to one item can't invalidate the list. You don't wrap anything in `memo` or worry about selector identity; the per-id sub is cached by its whole [query vector](../glossary.md#query-vector).

!!! note "Won't 200 subscriptions leak?"

    No. `[:todo/by-id 1]` and `[:todo/by-id 2]` are separate cache entries, and readers of the same query vector share one entry. When the last reader of an entry goes away (a row unmounts, the list shrinks), the entry is disposed in the same tick and a `:rf.sub/dispose` trace fires, so in a virtualised list the per-id subs come and go with the rows ([Subscriptions → Lifecycle](../subscriptions.md#lifecycle-a-sub-exists-only-while-something-watches)).

## 4. Only slow in production: the `rf:` timing channel

Xray reads the dev [trace stream](../glossary.md#trace-stream), which is [elided](../glossary.md#elide) from production builds, so it can't see slowness that only happens there. For that, the runtime can wrap four hot paths in the browser's User Timing API (`performance.measure`), with entries named `rf:<bucket>:<id>`:

| Bucket | Fires on | Entry name |
|---|---|---|
| `event` | an event handler ran (the full interceptor chain for one event) | `rf:event:todo/toggle` |
| `sub` | a subscription recomputed its body | `rf:sub:todo/sorted-ids` |
| `fx` | one effect executed (including reserved fx like `:dispatch` and managed HTTP) | `rf:fx:rf.http/managed` |
| `render` | a `reg-view` (or a Fresco `h/defview` boundary) rendered | `rf:render:my.app/todo-item` |

The id keeps its namespace, so split on the second `:` to group by bucket. Only registered views appear under `rf:render:`; a plain `defn` view has no id to measure. Fresco's `h/defview` counts as registered, so a Fresco screen needs no extra step ([Performance](../fresco/19-performance.md) in the Fresco guide explains its numbers).

!!! warning "Gotcha: a path that throws still records a measure"

    Each measurement is wrapped in `try/finally`, so when a handler or render throws, its `measure` entry still lands before the exception propagates. The slowest interactions are often the ones that then fail, so their timing is kept. A `rf:event:…` or `rf:render:…` bar is therefore not proof the path completed; check your error sinks too.

Profile a release build (`:advanced`, with this flag on), never the dev build: the dev build carries the whole trace surface, so its profile includes the cost of tracing and shows costs that vanish in production.

The channel is off by default and has its own compile-time flag, independent of `goog.DEBUG`:

```edn
;; shadow-cljs.edn — the build you want to measure
{:builds
 {:app {:target           :browser
        :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

A build without the flag carries no User Timing code at all, because dead-code elimination removes every measurement. (This repository's `npm run test:perf-bundle` builds one example both ways and checks that the flag-off bundle contains no `performance.measure`, `clearMeasures`, or `rf:` string.) Turning the channel on in production is cheap ([Configure dev and production builds](configure-dev-and-prod.md)).

To read it, open the Chrome DevTools **Performance** panel, where the `rf:` measures appear as named bars beside React renders, paint, and layout.

Each measure is delivered to any live `PerformanceObserver` and then cleared from the browser's buffer, so a long-running page doesn't accumulate entries. `performance.getEntriesByType('measure')` therefore returns `[]` in a normal build. For continuous telemetry, attach a `PerformanceObserver` and forward `rf:` measures to your APM:

```javascript
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      sendToAPM(e);                 // { name, startTime, duration }
    }
  }
}).observe({ type: 'measure', buffered: true });   // buffered: replay entries from before this observer attached
```

Read the result as in step 1: one wide `rf:render:` or `rf:sub:` bar is misplaced work (step 2), and a cloud of narrow `rf:render:` bars per interaction is a storm (step 3).

For a one-off console snapshot instead of an observer, build with `:closure-defines {re-frame.performance/retain-entries? true}`, which keeps entries in the buffer:

```javascript
// only populated when retain-entries? is on
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'))
  .sort((a, b) => b.duration - a.duration)
  .slice(0, 20);
```

Leave `retain-entries?` off in production: the browser's [User Timing buffer is unbounded](https://developer.mozilla.org/en-US/docs/Web/API/Performance/measure) for measure entries, so keeping every entry across a multi-hour session leaks memory.

??? info "For JavaScript developers"

    `rf:` measures are ordinary [User Timing API](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing) `measure` entries, the same kind React emits and APMs such as Datadog RUM, Sentry, and New Relic already ingest. Nothing re-frame2-specific is needed on the production side.

!!! note "JVM and SSR"

    The `rf:` channel is browser-only: on the JVM, `enabled?` is a constant `false` and the measurements compile away. To profile SSR or a headless render, use a JVM profiler such as clj-async-profiler or JFR.

## Advanced

### Stable callbacks, only with a measurement

Each render that writes `#(dispatch [:todo/toggle id])` creates a new function object, and `=` between two anonymous functions is `false`. A *view* that receives it as a prop sees a change and re-renders for nothing. On a cheap child this is invisible. It matters only when the Views tab shows an expensive child re-rendering although its data didn't change.

Hoisting the function into an outer `let` captures the mount-time `id` and goes stale if the row is later given a different one. What you need is one function object whose identity never changes but which acts on the current render's arguments. Build that callback once per row, and on each render write the render's arguments into an atom the callback reads when it fires:

```clojure
(defn callback-factory-factory
  "Returns a factory that always hands back the SAME callback object;
   the callback reads its dynamic args from an atom the factory refreshes.
   Stable identity across renders + current args = no false invalidation."
  [the-real-callback]
  (let [*args1        (atom nil)
        same-callback (fn [& args2]
                        (apply the-real-callback (concat @*args1 args2)))]
    (fn callback-factory [& args1]
      (reset! *args1 args1)
      same-callback)))

(rf/reg-view todo-item [_]
  ;; Form-2: the outer body runs once per mounted row, so the factory is
  ;; built once; the inner fn is the per-render render fn.
  (let [on-toggle (fn [id _event] (dispatch [:todo/toggle id]))
        toggle-cb (callback-factory-factory on-toggle)]
    (fn [id]
      (let [{:keys [title done?]} @(subscribe [:todo/by-id id])]
        [:li
         [:input {:type "checkbox" :checked done?
                  :on-change (toggle-cb id)}]
         title]))))
```

`(toggle-cb id)` returns the same object on every render, so the prop is `=` and the receiving view skips its render. On this plain checkbox that gains nothing; the wiring is the same when the prop feeds a chart, an editor, or a deep row, which is where it pays.

The factory has to be built once per row, not once per render, which is why `todo-item` is a Form-2 view: the outer function runs once at mount, and the inner function is the render function ([Views](../views.md) explains the view shapes).

??? info "For JavaScript developers"

    This does the job of `useCallback(fn, [id])` without a dependency array to get wrong: the callback object is stable and always sees current arguments, because they live in an atom rather than a captured closure.

Use this pattern only with a measurement in hand. It costs some readability, and it pays off only when the re-render it saves is expensive. Most lists never need it.

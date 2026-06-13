# Find and fix a slow view

A click hitches. Typing stutters. Some view is doing too much work. You want to find it and stop it, without sprinkling memoisation everywhere and hoping.

Your usual first move still works here: open the React DevTools profiler, record, do the slow thing, read the flame graph. But it tells you *which components rendered*, not *why the data changed*. The why is what you need. Every re-render traces back through a subscription to an event, and Xray shows that chain directly. The fixes are also structural, not per-component. The [equality gate](../concepts/subscriptions.md) already memoises every node of the derivation graph. Nearly every slow view is the same mistake in different clothes: expensive work on the wrong side of that gate.

> **Put expensive work after the circuit breaker, not before it.**

> **Coming from React?** This page replaces the `memo` / `useMemo` / `useCallback` genre: the framework owns memoisation, and your job is *placement* — which is checkable in review, not just in a profiler.

The hunt is a ladder. Climb it in cost order. Most hunts end on the first two rungs.

## 1 — Observe: name the shape of the slow

Attach Xray with one line ([Debug with Xray](debug-with-xray.md)). Reproduce the slow interaction once, select the newest event row, and open the **Views** tab. It lists every view that re-rendered in that cascade, with its render time. Under each view it nests the subscriptions it read. Each sub has a drill that answers "why did this sub re-run", back to the event that caused it.

You are looking for one of two shapes:

- **One wide row.** A single view, or one subscription under it, accounts for the time. The work itself is misplaced: rung 2.
- **A cloud of rows.** Dozens or hundreds of views re-rendered for a change that concerned one of them. A re-render storm: rung 3.

If the dev build feels fine and only production is slow, jump to rung 4.

## 2 — Move the work behind the equality gate

Here is the mechanism in three sentences. When `app-db` changes, every layer-1 extractor re-runs to check its slice, and the new result is compared with the old by `=`. If the slice didn't change, propagation stops: downstream subs keep their cached values, and views don't re-render. That `=` check is the circuit breaker, and it can only save you work that sits *behind* it.

So the first question is always: **is there computation in a layer-1 sub?** An extractor runs on every `app-db` change. That means every keystroke in every unrelated form, because running is how it checks its gate:

```clojure
;; Slow — the sort sits BEFORE the gate. Extractors re-run on every
;; app-db change, so this sorts the whole feed on every keystroke
;; anywhere in the app.
(rf/reg-sub :feed/slugs
  (fn [db _]
    (->> (vals (:articles db))                       ;; {slug -> article}
         (sort-by :created-at #(compare %2 %1))
         (mapv :slug))))
```

Split it. A tiny extractor decides *whether* anything changed. A layer-2 sub does the thinking only when it did.

```clojure
;; Fast — same code, other side of the gate.
(rf/reg-sub :articles/all
  (fn [db _] (:articles db)))

(rf/reg-sub :feed/slugs
  :<- [:articles/all]
  (fn [articles _]
    (->> (vals articles)
         (sort-by :created-at #(compare %2 %1))
         (mapv :slug))))
```

The same misplacement happens one level up. Computation in a **view body** runs on every render of that view, including renders caused by ancestors. Sorting, filtering, and formatting belong in a layer-2 sub. There they run once per input change and are shared by every consumer. Views walk data and emit hiccup ([Views: pure functions of data](../concepts/views.md)).

Now observe the fix. Dispatch the same event with the Views tab open. The sub's drill shows it returning its cached value: the gate closed, and the sort never ran. Unrelated typing no longer wakes the feed at all.

Some work is genuinely huge even when ideally placed, like parsing megabytes or a simulation step. No placement saves you there. Chunk it through a [state machine](../concepts/machines.md) or move it to a Web Worker.

## 3 — Break up the re-render storm

A cloud of rows in the Views tab almost always means a parent handed each child more state than it needs:

```clojure
;; Storm — every row receives its whole article map.
(rf/reg-view feed []
  [:div
   (for [article @(subscribe [:feed/articles])]   ;; the sorted full maps
     ^{:key (:slug article)} [article-row article])])
```

Favorite one article in a 200-row feed and `:feed/articles` is a new vector, because one map inside it changed. So `feed` re-renders and all 200 `article-row`s are re-invoked. The 199 untouched rows pass their deep `=` prop checks and keep their DOM. But the checks still run, on full maps, on every click. That's the hitch.

The cure: **hand each row an id, and let the row subscribe to its own slice.**

```clojure
;; Calm — rows get a slug; each fetches exactly what it renders.
(rf/reg-view feed []
  [:div
   (for [slug @(subscribe [:feed/slugs])]
     ^{:key slug} [article-row slug])])

(rf/reg-sub :article/by-slug
  (fn [db [_ slug]] (get-in db [:articles slug])))

(rf/reg-view article-row [slug]
  (let [{:keys [title favorited? favorites-count]} @(subscribe [:article/by-slug slug])]
    [:div
     [:h3 title]
     [:button {:on-click #(dispatch [:article/toggle-favorite slug])}
      (if favorited? "Unfavorite" "Favorite") " (" favorites-count ")"]]))
```

Trace a favorite click through it. One article's map changes, so `:feed/slugs` recomputes. But it yields an `=` slug vector, so the gate closes and `feed` doesn't re-render at all. `[:article/by-slug slug]` changes for exactly one slug, so exactly one row re-renders. Views tab: one row where the cloud was.

Two details are load-bearing. First, `^{:key slug}` gives each row a *stable identity*. Inserting or removing an article then diffs by identity instead of position. Without it, one deletion at the top re-renders every row beneath it. (Never use an index or a random value as a key.) Second, the inline `#(dispatch …)` on the button is correct as written. On a DOM element, swapping a listener is cheap. That brings us to the rung people climb too eagerly.

### Stable callbacks — only with a measurement

Every render that writes `#(dispatch [:article/toggle-favorite slug])` mints a fresh function object. It's behaviourally identical to last render's, but `=` between two anonymous fns is `false`. So a *component* receiving it as a prop sees a change and re-renders for nothing. This is invisible on a cheap child. It matters only when the Views tab shows an **expensive** child re-rendering whose data didn't change: the callback prop is the churn.

The naive fix is to hoist the fn into an outer `let`. That captures the mount-time `slug` forever and goes stale if the instance is ever handed a different one. The robust shape keeps one stable function object and feeds it fresh args each render:

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

(rf/reg-view article-row [_]
  ;; Form-2: the outer body runs once per mounted row, so the factory is
  ;; built once; the inner fn is the per-render render fn.
  (let [on-favorite (fn [slug _event] (dispatch [:article/toggle-favorite slug]))
        favorite-cb (callback-factory-factory on-favorite)]
    (fn [slug]
      (let [{:keys [title favorited? favorites-count]} @(subscribe [:article/by-slug slug])]
        [:div
         [:h3 title]
         [:button {:on-click (favorite-cb slug)}
          (if favorited? "Unfavorite" "Favorite") " (" favorites-count ")"]]))))
```

`(favorite-cb slug)` returns the same object every render, so the prop is `=` and the receiver skips. On this plain button it buys nothing. The payoff comes when the prop feeds a chart, an editor, or a row with real depth; the wiring is identical. Reach for this rung last, with a measurement in hand, not "on spec" across every list in the app.

> **Coming from re-frame v1?** This is v1's callback-factory-factory, unchanged — the prop-equality contract underneath didn't move.

## 4 — Only slow in production: the `rf:` timing channel

Xray rides the dev trace, which is compiled out of production builds. So it can't see a slowness that only happens live. For that there is a second, narrower channel built for production. The runtime brackets its four hot paths with the browser's User Timing API (`performance.mark` / `performance.measure`). Entries are named `rf:<bucket>:<id>` — `rf:event:article/toggle-favorite` (a handler ran), `rf:sub:feed/slugs` (a sub recomputed), `rf:fx:rf.http/managed` (an effect executed), `rf:render:my.app/article-row` (a `reg-view` rendered).

The channel is off by default. It is gated on its own compile-time flag, independent of `goog.DEBUG`:

```edn
;; shadow-cljs.edn — the build you want to measure
{:builds
 {:app {:target           :browser
        :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

A build that doesn't flip the flag carries **zero** User-Timing bytes. Dead-code elimination removes every bracket. So keeping it on in production is a deliberate, cheap choice ([Configure dev and production builds](configure-dev-and-prod.md)).

To read it, open the Chrome DevTools **Performance** panel. The `rf:` measures appear as named bars beside React renders, paint, and layout. Or ask the console for the worst offenders:

```javascript
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'))
  .sort((a, b) => b.duration - a.duration)
  .slice(0, 20);
```

The diagnosis reads the same as rung 1. One wide `rf:render:` or `rf:sub:` bar is misplaced work (rung 2). A cloud of identical narrow `rf:render:` bars per interaction is a storm (rung 3). For continuous telemetry, attach a `PerformanceObserver` and forward `rf:`-prefixed measures to your APM. Entry shapes, observer code, and the elision guarantees are in [spec 009 — Instrumentation](../../../spec/009-Instrumentation.md).

One warning saves an afternoon: never profile the dev build. It carries the whole trace surface, so the profile measures the measurement apparatus. Build `:advanced` with the perf flag on, serve that, and profile that.

---

**You can now:**

- read Xray's Views tab and name the shape of a slowdown — one wide row, or a cloud
- spot work in front of the equality gate and move it behind — extractors tiny, thinking in layer 2, computation out of view bodies
- reshape a list so one change re-renders one row, with thin props and stable keys
- say when stable callbacks are worth the factory pattern — and when they're over-engineering
- time a production build with the `rf:` User-Timing channel, without shipping the dev trace

**Next:** the machinery behind the gate — [Subscriptions: the derivation graph](../concepts/subscriptions.md) · the full inspection workflow — [Debug with Xray](debug-with-xray.md)

# Find and fix a slow view

A click hitches. Typing stutters. Some view — a function that turns your data into the on-screen elements — is doing too much work, and you want to find it and stop it, without sprinkling memoisation everywhere and hoping.

Your usual first move still works here: open the React DevTools profiler, record, do the slow thing, read the flame graph. But it tells you *which components rendered*, not *why the data changed*, and the why is what you need. Every re-render traces back through a subscription — a cached, derived view of your state — to an event, the record of something that happened. Xray, the dev inspector, shows that chain directly. The fixes are structural rather than per-component, too. The [equality gate](../concepts/subscriptions.md) already memoises every node of the derivation graph, so nearly every slow view is the same mistake in different clothes: expensive work on the wrong side of that gate.

> **Put expensive work after the circuit breaker, not before it.**

> **Coming from React?** This page replaces the `memo` / `useMemo` / `useCallback` genre: the framework owns memoisation, and your job is *placement* — which is checkable in review, not just in a profiler.

The hunt is a ladder, and you climb it in cost order. Most hunts end on the first two rungs, so don't brace for all four.

## 1 — Observe: name the shape of the slow

Attach Xray with one line ([Debug with Xray](debug-with-xray.md)). Reproduce the slow interaction once, select the newest event row, and open the **Views** tab. It lists every view that re-rendered in that cascade, with its render time, and under each view it nests the subscriptions it read. Each sub has a drill that answers "why did this sub re-run", tracing back to the event that caused it. Mounted, re-rendered, and unmounted views appear in their own groups, and a row that re-rendered carries a one-line *mount-reason* attribution ("parent conj'd this child", "own data changed", "callback prop churned").

> **About those render times.** The dev Views tab borrows its per-view millisecond numbers from React's own profiler, so they're *best-effort*: present when React profiling data is available, absent otherwise, and — like any dev-build number — inflated by the trace surface itself. Use them to rank rows within one capture, not as ground truth. The numbers that match what your users feel come from rung 4's production channel.

You are looking for one of two shapes:

- **One wide row.** A single view, or one subscription under it, accounts for the time. The work itself is misplaced: rung 2.
- **A cloud of rows.** Dozens or hundreds of views re-rendered for a change that concerned one of them. A re-render storm: rung 3.

If the dev build feels fine and only production is slow, jump to rung 4.

## 2 — Move the work behind the equality gate

Here is the mechanism in three sentences. When `app-db` — your app's single state map — changes, every layer-1 extractor re-runs to check its slice, and the new result is compared with the old by `=`. If the slice didn't change, propagation stops: downstream subs keep their cached values, and views don't re-render. That `=` check is the circuit breaker, and it can only save you work that sits *behind* it.

So the first question is always: **is there computation in a layer-1 sub?** An extractor — a plain layer-1 sub that just pulls a slice out of `app-db` — runs on every `app-db` change. That means every keystroke in every unrelated form, because running is how it checks its gate:

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

Split it. A tiny extractor decides *whether* anything changed, and a layer-2 sub — one that derives from other subs rather than from `app-db` directly — does the thinking only when it did.

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

The `:<-` arrow reads as "this sub's input comes from"; `:feed/slugs` now derives from the *value* `:articles/all` extracted, not from `db`. When some other key in `app-db` changes, `:articles/all` re-runs, returns an `=` map, the gate closes, and `:feed/slugs` never wakes — the sort doesn't run. [Subscriptions](../concepts/subscriptions.md) has the full layering grammar.

The same misplacement happens one level up, and this trips people up. Computation in a **view body** runs on every render of that view, including renders caused by ancestors. Sorting, filtering, and formatting belong in a layer-2 sub; there they run once per input change and are shared by every consumer. Views just walk data and emit hiccup ([Views: pure functions of data](../concepts/views.md)).

Now observe the fix. Dispatch the same event with the Views tab open, and the sub's drill shows it returning its cached value: the gate closed, and the sort never ran. Unrelated typing no longer wakes the feed at all.

> **One derivation, read in many places?** If the same expensive value is read by *both* views and event handlers (or schemas, or other derivations), a layer-2 sub recomputes it for the render side only — handlers can't read the sub cache. Promote it to a [flow](../concepts/flows.md) and it's computed exactly once per app-db write, materialised into app-db, and every reader — view and handler alike — shares that one result. A flow is the equality gate applied to *state* instead of to a view-facing cache.

> **When placement isn't enough.** Some work is genuinely huge even when ideally placed — parsing megabytes, running a simulation step, diffing two trees. No side of the gate saves you there, because the cost is in the computation itself, not in how often it fires. Chunk it through a [state machine](../concepts/machines.md) or move it to a Web Worker. But this is rare: reach for it only after you've confirmed the work is correctly placed and *still* slow.

## 3 — Break up the re-render storm

A cloud of rows in the Views tab almost always means a parent handed each child more state than it needs:

```clojure
;; Storm — every row receives its whole article map.
(rf/reg-view feed []
  [:div
   (for [article @(subscribe [:feed/articles])]   ;; the sorted full maps
     ^{:key (:slug article)} [article-row article])])
```

Favorite one article in a 200-row feed and `:feed/articles` is a new vector, because one map inside it changed. So `feed` re-renders and all 200 `article-row`s are re-invoked. The 199 untouched rows pass their deep `=` prop checks and keep their DOM — but the checks still run, on full maps, on every click. That's the hitch.

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

Trace a favorite click through it. One article's map changes, so `:feed/slugs` recomputes — but it yields an `=` slug vector, so the gate closes and `feed` doesn't re-render at all. `[:article/by-slug slug]` changes for exactly one slug, so exactly one row re-renders. Views tab: one row where the cloud was.

> **"But now I have 200 subscriptions — won't they leak?"** No. A subscription's cache key is its whole query vector, so `[:article/by-slug "abc"]` and `[:article/by-slug "xyz"]` are distinct cached entries, and equal subscriptions from multiple readers share one entry. Each entry is ref-counted: when the last reader of a query vector goes away — a row unmounts, the feed shrinks — the entry is evicted *in the same tick*, its reaction disposed, and a `:rf.sub/dispose` trace fires. Scroll a virtualised feed and the per-slug subs come and go with the rows; nothing accumulates. (Mechanics in [spec 006 §Reference counting and disposal](../../../spec/006-ReactiveSubstrate.md).)

Two details are load-bearing here. First, `^{:key slug}` gives each row a *stable identity*, so inserting or removing an article diffs by identity instead of position. Without it, one deletion at the top re-renders every row beneath it. (Never use an index or a random value as a key — an index makes "delete row 0" look like "every row's data changed", which re-creates the storm you just fixed; a fresh random key remounts every row on every render.) Second, the inline `#(dispatch …)` on the button is correct as written: on a DOM element, swapping a listener is cheap. That brings us to the rung people climb too eagerly.

> **Key by the id, not the value you're watching.** The key must be *stable for that row's identity*, not derived from the bit that changes. Keying a feed row by `favorites-count` would remount the row every time someone favorites it — the opposite of what you want. The slug is durable; the favorite count is not.

### Stable callbacks — only with a measurement

Every render that writes `#(dispatch [:article/toggle-favorite slug])` mints a fresh function object. It's behaviourally identical to last render's, but `=` between two anonymous fns is `false`, so a *component* receiving it as a prop sees a change and re-renders for nothing. This is invisible on a cheap child. It matters only when the Views tab shows an **expensive** child re-rendering whose data didn't change: the callback prop is the churn.

The naive fix is to hoist the fn into an outer `let`, but that captures the mount-time `slug` forever and goes stale if the instance is ever handed a different one. The robust shape keeps one stable function object and feeds it fresh args each render:

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

`(favorite-cb slug)` returns the same object every render, so the prop is `=` and the receiver skips. On this plain button it buys nothing; the payoff comes when the prop feeds a chart, an editor, or a row with real depth, and the wiring is identical.

> **Why a Form-2 view?** The factory must be built *once per row*, not once per render — otherwise you've minted a fresh "stable" object each render and bought nothing. A Form-2 view (a render body that returns another fn) gives you exactly that seam: the outer fn runs once at mount and is where the factory lives; the inner fn is the render fn. ([Views §Form-1, Form-2, Form-3](../concepts/views.md) explains the three shapes.)

> **Reach for this rung last.** Use the factory pattern with a measurement in hand, not "on spec" across every list in the app. It trades a little reading clarity for a saved re-render, and that trade only pays off when the saved re-render is expensive. Most lists never need it; a button is not a chart.

> **Coming from re-frame v1?** This is v1's callback-factory-factory, unchanged — the prop-equality contract underneath didn't move.

## 4 — Only slow in production: the `rf:` timing channel

Xray rides the dev trace, which is compiled out of production builds, so it can't see a slowness that only happens live. For that there is a second, narrower channel built for production. The runtime brackets its **four** hot paths with the browser's User Timing API (`performance.mark` / `performance.measure`), and entries are named `rf:<bucket>:<id>`:

| Bucket | Fires on | Entry name |
|---|---|---|
| `event` | an event handler ran (the full interceptor chain for one event) | `rf:event:article/toggle-favorite` |
| `sub` | a subscription recomputed its body | `rf:sub:feed/slugs` |
| `fx` | one effect executed (including reserved fx like `:dispatch` and managed http) | `rf:fx:rf.http/managed` |
| `render` | a `reg-view` rendered | `rf:render:my.app/article-row` |

The id keeps its full namespace, so you can split on the second `:` for a per-bucket view. (An effect, by the way, is a described side-effect the framework carries out for you; a handler is the function that runs in response to an event.) Note that only `reg-view` views show up under `rf:render:` — a plain `defn` view has no registered id to bracket, which is one more reason to register the views you care about measuring.

The channel is off by default. It is gated on its own compile-time flag, independent of `goog.DEBUG`:

```edn
;; shadow-cljs.edn — the build you want to measure
{:builds
 {:app {:target           :browser
        :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

A build that doesn't flip the flag carries **zero** User-Timing bytes, because dead-code elimination removes every bracket. CI proves this both ways: `npm run test:perf-bundle` builds the same example twice — flag off and flag on — and asserts the off bundle contains no `performance.mark`, `performance.measure`, or `rf:` fragment while the on bundle contains all three. So keeping the channel on in production is a deliberate, cheap choice ([Configure dev and production builds](configure-dev-and-prod.md)).

To read it, open the Chrome DevTools **Performance** panel, where the `rf:` measures appear as named bars beside React renders, paint, and layout. Or ask the console for the worst offenders:

```javascript
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'))
  .sort((a, b) => b.duration - a.duration)
  .slice(0, 20);
```

The diagnosis reads the same as rung 1. One wide `rf:render:` or `rf:sub:` bar is misplaced work (rung 2); a cloud of identical narrow `rf:render:` bars per interaction is a storm (rung 3).

For continuous telemetry, don't lean on the entry buffer — the host bounds it (Chrome keeps only the last 10,000 `measure` entries), so a long-running page silently drops the oldest. Instead attach a `PerformanceObserver`, which fires per entry as it lands, and forward `rf:`-prefixed measures to your APM:

```javascript
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      sendToAPM(e);                 // { name, startTime, duration }
    }
  }
}).observe({ type: 'measure', buffered: true });   // buffered: replay entries from before this observer attached
```

Entry shapes, the observer contract, the bounded-buffer caveat, and the elision guarantees are in [spec 009 — Instrumentation](../../../spec/009-Instrumentation.md).

> **Never profile the dev build.** The dev build carries the whole trace surface, so the profile ends up measuring the measurement apparatus — you'll chase phantom costs that vanish in production. Build `:advanced` with the perf flag on, serve *that*, and profile that. The numbers you get are the numbers your users get.

> **JVM and SSR.** The `rf:` channel is browser-only — on the JVM the `enabled?` flag is a `^:const false` and the brackets compile to pure pass-through. If you're profiling SSR or a headless render, reach for a JVM profiler (clj-async-profiler, JFR) instead; the User-Timing path isn't there to read.

## The ladder, in one breath

Almost every slow view is the same mistake wearing a different costume: expensive work on the wrong side of the equality gate. The ladder just sorts the costumes by how cheaply you can spot them.

1. **Observe.** Xray's Views tab names the shape — one wide row, or a cloud.
2. **Move the work behind the gate.** Keep extractors tiny; do the thinking in a layer-2 sub; keep computation out of view bodies; promote a shared derivation to a flow when handlers need it too. This ends most hunts.
3. **Break up the storm.** Hand each row an id and let it subscribe to its own slice — one change re-renders one row, on thin props with stable keys, and the per-row subs ref-count themselves away when rows unmount.
4. **Reach for stable callbacks only with a measurement** — the factory pattern earns its keep when the receiving child is expensive, and not before.

And when the dev build feels fine but production doesn't, the `rf:` User-Timing channel measures the real binary without shipping a byte of the dev trace. The framework owns the memoisation; your job is placement — and placement is the rare performance problem you can catch in code review instead of a profiler.

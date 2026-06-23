# Find and fix a slow view

A click hitches. Typing stutters. Some view — a function that turns your data into the on-screen elements — is doing too much work, and you want to find it and stop it, without sprinkling memoisation everywhere and hoping.

Here is the good news up front, and it's the whole reason this page is short. In re-frame2 your views read from *subscriptions* — cached, derived views of your state — and those subscriptions chain into each other, each one feeding the next. That chain is your **derivation graph**, and the [equality gate](../concepts/subscriptions.md) already memoises every node of it for you. So nearly every slow view is the *same* mistake wearing a different costume: expensive work on the wrong side of that gate. You don't add caching — you move work to the side of the cache that already exists.

> **Put expensive work after the circuit breaker, not before it.**

The hunt is a ladder, and you climb it in order of *how cheaply you can spot the problem* — easiest-to-see first, not most-impactful first. Most hunts end on the first two rungs, so don't brace for all four:

1. **Observe** the shape of the slow.
2. **Move the work behind the gate.** Ends most hunts.
3. **Break up the re-render storm.**
4. **Measure production** with the `rf:` timing channel.

We'll take them one at a time.

> **For JavaScript developers.** This page replaces the `memo` / `useMemo` / `useCallback` genre. In React you reach for those hooks per-component, in a profiler, when something feels slow. Here the framework owns memoisation, and your job is *placement* — which key reads `app-db`, which derives from another sub. Placement is checkable in code review, not just in a flame graph. The rest of this page is mostly "where does this line of code belong".

---

## 1 — Observe: name the shape of the slow

Your usual first move still works: open the React DevTools profiler, record, do the slow thing, read the flame graph. But it tells you *which components rendered*, not *why the data changed* — and the why is what you need. Every re-render in re-frame2 traces back through a subscription (a cached, derived view of your state) to an event (the record of something that happened). Xray, the dev inspector, shows that chain directly.

Attach Xray with one line ([Debug with Xray](debug-with-xray.md)). Reproduce the slow interaction once, select the newest event row, and open the **Views** tab. It lists every view that re-rendered in that cascade, with its render time, and nests under each view the subscriptions it read. Each sub has a drill that answers "why did this sub re-run", tracing back to the event that caused it. Mounted, re-rendered, and unmounted views appear in their own groups, and a re-rendered row carries a one-line *mount-reason* attribution ("parent conj'd this child", "own data changed", "callback prop churned").

You are looking for one of two shapes:

- **One wide row.** A single view, or one subscription under it, accounts for the time. The work is misplaced: that's rung 2.
- **A cloud of rows.** Dozens or hundreds of views re-rendered for a change that concerned one of them. A re-render storm: that's rung 3.

If the dev build feels fine and only production is slow, jump straight to rung 4.

> **Gotcha — those render times are best-effort.** The dev Views tab borrows its per-view millisecond numbers from React's own profiler: present when React profiling data is available, absent otherwise, and — like any dev-build number — inflated by the trace surface itself. Use them to *rank rows within one capture*, not as ground truth. The numbers that match what your users feel come from rung 4's production channel.

---

## 2 — Move the work behind the equality gate

First, the two words this whole section turns on. Subscriptions come in *layers*. A **layer-1** sub — also called an **extractor** — reads `app-db` directly and just pulls out a slice; it does no real computing. A **layer-2** sub reads from *other subscriptions* rather than from `app-db`, and it's where derived work (sorting, filtering, formatting) belongs. The equality gate sits between the two, and the whole game is putting expensive work on the layer-2 side of it. ([Subscriptions](../concepts/subscriptions.md) has the full layering grammar.)

With those words in hand, here is the mechanism in three sentences. When `app-db` — your app's single state map — changes, every layer-1 extractor re-runs to check its slice, and the new result is compared with the old by `=`. If the slice didn't change, propagation stops: downstream subs keep their cached values, and views don't re-render. That `=` check is the circuit breaker, and it can only save you work that sits *behind* it.

So the first question is always: **is there computation in a layer-1 sub?** Remember, an extractor runs on *every* `app-db` change — running is how it checks its gate. So any computing you put in one runs on every keystroke in every unrelated form:

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

Split it. A tiny extractor decides *whether* anything changed, and a layer-2 sub does the thinking only when it did.

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

The `:<-` arrow reads as "this sub's input comes from"; `:feed/slugs` now derives from the *value* `:articles/all` extracted, not from `db`. When some other key in `app-db` changes, `:articles/all` re-runs, returns an `=` map, the gate closes, and `:feed/slugs` never wakes — the sort doesn't run.

The same misplacement happens one level up, and it trips people up. Computation in a **view body** runs on every render of that view, including renders caused by ancestors. Sorting, filtering, and formatting belong in a layer-2 sub; there they run once per input change and are shared by every consumer. Views just walk data and emit hiccup — the `[:div ...]` vector form re-frame2 uses to describe markup ([Views: pure functions of data](../concepts/views.md)).

Now observe the fix. Dispatch the same event with the Views tab open, and the sub's drill shows it returning its cached value: the gate closed, and the sort never ran. Unrelated typing no longer wakes the feed at all.

> **Going deeper.** The split-extractor trick is *function memoisation* applied compositionally. Layer 1 is a pure projection `app-db → slice`; the gate is `=` on its output, so the layer-1 sub is a memoised function whose cache invalidates exactly when its observable input changes. Stacking a layer-2 sub on top composes two memoised projections: `app-db → slice → derived`, and the composite recomputes the second stage only when the first stage's `=` check opens. The whole subscription graph is a DAG of memoised pure functions, each gated independently — which is why "move the work behind the gate" is the *only* lever you ever need: you're choosing which arrow in the composition carries the cost, and the framework caches every arrow for free.

> **From re-frame v1.** This is the same layered-subscription model you already know — `reg-sub` with `:<-` inputs, the `=`-based propagation cut. Nothing in the gate moved. What's new is the diagnostic surface: Xray's Views tab shows the cached-vs-recomputed verdict per sub per event, so "is this extractor doing work?" is now a thing you *read* rather than reason about. The fix is identical; the observability is better.

> **One derivation, read in many places?** If the same expensive value is read by *both* views and event handlers (or schemas, or other derivations), a layer-2 sub recomputes it for the render side only — handlers can't read the sub cache. Promote it to a [flow](../concepts/flows.md) and it's computed exactly once per `app-db` write, materialised into `app-db`, and every reader — view and handler alike — shares that one result. A flow is the equality gate applied to *state* instead of to a view-facing cache.

> **When placement isn't enough.** Some work is genuinely huge even when ideally placed — parsing megabytes, running a simulation step, diffing two trees. No side of the gate saves you there, because the cost is in the computation itself, not in how often it fires. Chunk it through a [state machine](../concepts/machines.md) or move it to a Web Worker. But this is rare: reach for it only after you've confirmed the work is correctly placed and *still* slow.

---

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

Two details are load-bearing here.

First, `^{:key slug}` gives each row a *stable identity*, so inserting or removing an article diffs by identity instead of position. Without it, one deletion at the top re-renders every row beneath it.

Second, the inline `#(dispatch …)` on the button is correct as written: on a DOM element, swapping a listener is cheap. (That's the rung people climb too eagerly — rung 4 below.)

> **Gotcha — key by the id, not the value you're watching.** The key must be *stable for that row's identity*, not derived from the bit that changes. Keying a feed row by `favorites-count` would remount the row every time someone favorites it — the opposite of what you want. And never use an index or a random value as a key: an index makes "delete row 0" look like "every row's data changed", which re-creates the storm you just fixed; a fresh random key remounts every row on every render. The slug is durable; the favorite count is not.

> **For JavaScript developers.** "Hand each row an id and let it subscribe to its own slice" is the same instinct as a Redux `connect`-per-row or a per-item `useSelector(s => s.articles[id])` — push the selector down to the leaf so a single-item change can't invalidate the list. The difference: you don't wrap anything in `memo`, and you don't worry about selector identity. The per-slug sub *is* the memo boundary, and it's keyed by the whole query vector for free.

> **"But now I have 200 subscriptions — won't they leak?"** No. A subscription's cache key is its whole query vector, so `[:article/by-slug "abc"]` and `[:article/by-slug "xyz"]` are distinct cached entries, and equal subscriptions from multiple readers share one entry. Each entry is ref-counted: when the last reader of a query vector goes away — a row unmounts, the feed shrinks — the entry is evicted *in the same tick*, its reaction disposed, and a `:rf.sub/dispose` trace fires. Scroll a virtualised feed and the per-slug subs come and go with the rows; nothing accumulates. (Mechanics in [spec 006 §Reference counting and disposal](../../../spec/006-ReactiveSubstrate.md).)

### Stable callbacks — only with a measurement

Every render that writes `#(dispatch [:article/toggle-favorite slug])` mints a fresh function object. It's behaviourally identical to last render's, but `=` between two anonymous fns is `false`, so a *component* receiving it as a prop sees a change and re-renders for nothing. This is invisible on a cheap child. It matters only when the Views tab shows an **expensive** child re-rendering whose data didn't change: the callback prop is the churn.

The naive fix is to hoist the fn into an outer `let`, but that captures the mount-time `slug` forever and goes stale if the instance is ever handed a different one. We need both things at once: *one* function object that never changes identity, yet always acts on the *current* render's args.

The trick is to split those two concerns. Build, once per row, a single long-lived callback object — that's the stable identity the child compares against. Each render, instead of making a *new* function, just write this render's args into an atom that the stable callback reads from when it eventually fires. Stable object on the outside, fresh args on the inside. Because that's a function (the stable callback) built by a function (the per-render setter) built by a function (the one-per-row setup), the helper is named `callback-factory-factory` — a factory that makes factories:

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

> **For JavaScript developers — this is `useCallback`, made honest.** `useCallback(fn, [slug])` exists to hand a child a stable function identity so it can `memo` past a no-op prop change. The factory-factory does the same job, but it sidesteps the stale-closure trap that bites `useCallback` when the dependency array is wrong: the callback object is stable *and* always sees current args, because the args live in an atom the factory refreshes, not in a captured closure. Same payoff (skip the expensive child's re-render), no dependency array to get wrong.

> **From re-frame v1.** This is v1's `callback-factory-factory`, unchanged — the prop-equality contract underneath didn't move. If you wrote this pattern in a v1 app, copy it across verbatim.

> **Reach for this rung last.** Use the factory pattern with a measurement in hand, not "on spec" across every list in the app. It trades a little reading clarity for a saved re-render, and that trade only pays off when the saved re-render is expensive. Most lists never need it; a button is not a chart.

---

## 4 — Only slow in production: the `rf:` timing channel

Xray rides the dev trace, which is compiled out of production builds, so it can't see a slowness that only happens live. For that there is a second, narrower channel built for production. The runtime brackets its **four** hot paths with the browser's User Timing API (`performance.mark` / `performance.measure`), and entries are named `rf:<bucket>:<id>`. Two of the four buckets name things worth a quick gloss: an *effect* is a described side-effect the framework carries out for you, and a *handler* is the function that runs in response to an event.

| Bucket | Fires on | Entry name |
|---|---|---|
| `event` | an event handler ran (the full interceptor chain for one event) | `rf:event:article/toggle-favorite` |
| `sub` | a subscription recomputed its body | `rf:sub:feed/slugs` |
| `fx` | one effect executed (including reserved fx like `:dispatch` and managed http) | `rf:fx:rf.http/managed` |
| `render` | a `reg-view` rendered | `rf:render:my.app/article-row` |

The id keeps its full namespace, so you can split on the second `:` for a per-bucket view. Note that only `reg-view` views show up under `rf:render:` — a plain `defn` view has no registered id to bracket, which is one more reason to register the views you care about measuring.

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

> **For JavaScript developers.** `rf:` measures are plain [User Timing API](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing) `measure` entries — the same ones React emits, the same ones your APM (Datadog RUM, Sentry, New Relic) already ingests. There's no re-frame2-specific tooling to install on the production side: it's `performance.getEntriesByType('measure')` and a `PerformanceObserver`, exactly as you'd instrument any web app. The framework just gives every event, sub, fx, and render a stable, namespaced entry name for free.

> **Gotcha — never profile the dev build.** The dev build carries the whole trace surface, so the profile ends up measuring the measurement apparatus — you'll chase phantom costs that vanish in production. Build `:advanced` with the perf flag on, serve *that*, and profile that. The numbers you get are the numbers your users get.

> **JVM and SSR.** The `rf:` channel is browser-only — on the JVM the `enabled?` flag is a `^:const false` and the brackets compile to pure pass-through. If you're profiling SSR or a headless render, reach for a JVM profiler (clj-async-profiler, JFR) instead; the User-Timing path isn't there to read.

---

## The ladder, in one breath

Almost every slow view is the same mistake wearing a different costume: expensive work on the wrong side of the equality gate. The ladder just sorts the costumes by how cheaply you can spot them.

1. **Observe.** Xray's Views tab names the shape — one wide row, or a cloud.
2. **Move the work behind the gate.** Keep extractors tiny; do the thinking in a layer-2 sub; keep computation out of view bodies; promote a shared derivation to a flow when handlers need it too. This ends most hunts.
3. **Break up the storm.** Hand each row an id and let it subscribe to its own slice — one change re-renders one row, on thin props with stable keys, and the per-row subs ref-count themselves away when rows unmount.
4. **Reach for stable callbacks only with a measurement** — the factory pattern earns its keep when the receiving child is expensive, and not before.

And when the dev build feels fine but production doesn't, the `rf:` User-Timing channel measures the real binary without shipping a byte of the dev trace. The framework owns the memoisation; your job is placement — and placement is the rare performance problem you can catch in code review instead of a profiler.

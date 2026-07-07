# Find and fix a slow view

A click hitches. Typing stutters. Some [view](../glossary.md#view) — the pure function that turns your data into [hiccup](../glossary.md#hiccup) — is doing too much work, and you want to find it and stop it. Without sprinkling memoisation everywhere and hoping.

Here is the good news up front, and it's the whole reason this page is short. Your views read from [subscriptions](../glossary.md#subscription) — named, cached, read-only derivations of state — and those subscriptions chain into each other, each one feeding the next. That chain is the [derivation graph](../glossary.md#the-derivation-graph), and the framework already memoises every node of it for you: a subscription recomputes only when an input it actually reads produces a *new* value, compared by `=`.

Which means nearly every slow view is the *same* mistake wearing a different costume: expensive work on the wrong side of that `=` check. You don't add caching. You move the work to the side of the cache that already exists.

> **Put expensive work after the circuit breaker, not before it.**

That sentence is the whole page. The four rungs below just find the work and pick the side.

The hunt is a ladder, and you climb it in order of *how cheaply you can spot the problem* — easiest-to-see first, not most-impactful first. Most hunts end on the first two rungs, so don't brace for all four:

1. **Observe** the shape of the slow.
2. **Move the work behind the gate.** Ends most hunts.
3. **Break up the re-render storm.**
4. **Measure production** with the `rf:` timing channel.

We'll take them one at a time.

??? info "For JavaScript developers"

    This page replaces the `memo` / `useMemo` / `useCallback` genre. In React you reach for those hooks per-component, in a profiler, when something feels slow. Here the framework owns memoisation, and your job is *placement* — which subscription reads `app-db`, which derives from another subscription. Placement is checkable in code review, not just in a flame graph. The rest of this page is mostly "where does this line of code belong".

---

## 1 — Observe: name the shape of the slow

Your usual first move still works: open the React DevTools profiler, record, do the slow thing, read the flame graph. But a flame graph tells you *which components rendered*, not *why the data changed* — and the why is what you need. Every re-render here traces back through a subscription to an [event](../glossary.md#event), the inert data vector recording that something happened. That chain is exactly what [Xray](../glossary.md#xray), the dev inspector, shows you. Use the trace, Luke.

Attach Xray with one line ([Debug with Xray](../../xray/index.md)). Reproduce the slow interaction once, select the newest event row, and open the **Views** tab. It lists every view that re-rendered in that [pipeline run](../glossary.md#run), with its render time, and nests under each view the subscriptions it read. Each sub carries a drill that answers "why did this sub re-run", tracing back to the event that caused it. Mounted, re-rendered, and unmounted views appear in their own groups, and a re-rendered row names its cause — `← :sub-id` when a subscription's value moved, `← props` when the parent handed it different arguments. You may be surprised by what you see.

You are looking for one of two shapes:

- **One wide row.** A single view, or one subscription under it, accounts for the time. The work is misplaced: that's rung 2.
- **A cloud of rows.** Dozens or hundreds of views re-rendered for a change that concerned one of them. A re-render storm: that's rung 3.

If the dev build feels fine and only production is slow, jump straight to rung 4.

One caution before you lean on those per-view milliseconds: they are best-effort. Each number is the framework's own wall-clock read around a registered view's render function — and, like any dev-build number, it's inflated by the trace surface itself. Use them to *rank rows within one capture*, not as ground truth. The numbers that match what your users feel come from rung 4's production channel.

---

## 2 — Move the work behind the equality gate

Two words this section turns on, recapped from [Subscriptions](../subscriptions.md): a **layer-1** sub (an **extractor**) reads [app-db](../glossary.md#app-db) directly and pulls out a slice; a **layer-2** sub reads from *other subscriptions* and is where derived work — sorting, filtering, formatting — belongs. The `=` check between them is the [circuit breaker](../subscriptions.md#the-equality-gate): when app-db changes, every extractor re-runs to re-check its slice, and an `=` result shuts the gate so nothing downstream recomputes. The whole game is putting expensive work on the layer-2 side of that gate.

So the first question is always: **is there computation in a layer-1 sub?** An extractor runs on *every* app-db change — running is how it checks its gate — so any computing you put in one runs on every keystroke in every unrelated form:

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

The `:<-` arrow reads as "this sub's input comes from": `:feed/slugs` now derives from the *value* `:articles/all` extracted, not from `db`. When some other key in app-db changes, `:articles/all` re-runs, returns an `=` map, the gate closes, and `:feed/slugs` never wakes. The sort doesn't run. You didn't make anything faster — you stopped doing it.

!!! warning "Gotcha — a mistyped `:<-` input fails quiet, not loud"

    When you split a sub, the new `:<-` edge points at another sub *by id*. Get that id wrong — a typo, a sub you haven't registered yet — and re-frame2 doesn't throw. It [fails loud as data](../glossary.md#error-record): it emits a `:rf.error/no-such-sub` [error record](../glossary.md#error-record) (recovery `:replaced-with-default`) to your always-on error listeners and feeds your derivation `nil` for that input. So a bad split doesn't look *slow* — it looks *wrong* (the feed renders empty, or a downstream `nil` throws somewhere unrelated). If a sub you just refactored returns nothing, check its `:<-` ids against your `reg-sub` names before you suspect the gate. The parametric input-function form has its own two named modes: an input fn that throws surfaces `:rf.error/sub-input-fn-exception`, and one that returns something other than a query vector (or vector of them) surfaces `:rf.error/sub-input-fn-bad-return`.

The same misplacement happens one level up, and it trips people constantly. Computation in a **view body** runs on every render of that view, including renders caused by ancestors. Sorting, filtering, and formatting belong in a layer-2 sub; there they run once per input change and are shared by every consumer. Views just walk data and emit hiccup — the `[:div ...]` vector form re-frame2 uses to describe markup ([Views](../views.md)).

Now watch the fix land. Dispatch the same event with the Views tab open, and the sub's drill shows it returning its cached value: gate closed, sort never ran. Unrelated typing no longer wakes the feed at all.

??? note "Going deeper"

    The split-extractor trick is *function memoisation* applied compositionally. Layer 1 is a pure projection `app-db → slice`; the gate is `=` on its output, so the layer-1 sub is a memoised function whose cache invalidates exactly when its observable input changes. Stacking a layer-2 sub on top composes two memoised projections: `app-db → slice → derived`, and the composite recomputes the second stage only when the first stage's `=` check opens. The whole graph is a DAG of memoised pure functions, each gated independently — which is why "move the work behind the gate" is the *only* lever you ever need: you're choosing which arrow in the composition carries the cost, and the framework caches every arrow for free.

!!! note "One derivation, read in many places?"

    A subscription is a *view-facing* reactive cache — a handler can take a one-shot `subscribe-once` read of its current value, and when a mounted view already holds that same query live, the read is a cache hit. But when nothing reactive holds the sub, each `subscribe-once` recomputes from scratch. So an expensive value read by handlers on paths no view keeps warm gets computed repeatedly. Promote it to a [flow](../flows.md) and it's computed exactly once per app-db write, [materialised into app-db](../glossary.md#flow), and every reader — view and handler alike — shares that one result as plain state. A flow is the equality gate applied to *state* instead of to a view-facing cache.

!!! note "When placement isn't enough"

    Some work is genuinely huge even when ideally placed — parsing megabytes, running a simulation step, diffing two trees. No side of the gate saves you there, because the cost is in the computation itself, not in how often it fires. And the browser gives you exactly one thread: while that work runs, nothing else does — no repaints, no clicks, no spinner. Chunk it through a [machine](../../machines/concepts.md) or move it to a Web Worker. But this is rare: reach for it only after you've confirmed the work is correctly placed and *still* slow.

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

Favorite one article in a 200-row feed and `:feed/articles` is a new vector, because one map inside it changed. So `feed` re-renders and all 200 `article-row`s are re-invoked. The 199 untouched rows pass their deep `=` prop checks and keep their DOM — but the checks still run, on full maps, on every click. Lots of hiccup, `=` checks on big props: that's the anatomy of every storm, and that's your hitch.

The cure is to not do the unnecessary work. (Duh.) Concretely: **hand each row an id, and let the row subscribe to its own slice.**

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

Second, the inline `#(dispatch …)` on the button is correct as written: on a DOM element, swapping a listener is cheap. (That's the rung people climb too eagerly — `### Stable callbacks` below.)

!!! warning "Gotcha — key by the id, not the value you're watching"

    The key must be *stable for that row's identity*, not derived from the bit that changes. Keying a feed row by `favorites-count` would remount the row every time someone favorites it — the opposite of what you want. And never use an index or a random value as a key: an index makes "delete row 0" look like "every row's data changed", which re-creates the storm you just fixed; a fresh random key remounts every row on every render. The slug is durable; the favorite count is not.

??? info "For JavaScript developers"

    "Hand each row an id and let it subscribe to its own slice" is the same instinct as a Redux `connect`-per-row or a per-item `useSelector(s => s.articles[id])` — push the selector down to the leaf so a single-item change can't invalidate the list. The difference: you don't wrap anything in `memo`, and you don't worry about selector identity. The per-slug sub *is* the memo boundary, and it's keyed by the whole [query vector](../glossary.md#query-vector) for free.

!!! note "'But now I have 200 subscriptions — won't they leak?'"

    No. A subscription's cache key is its whole query vector, so `[:article/by-slug "abc"]` and `[:article/by-slug "xyz"]` are distinct cached entries, and equal subscriptions from multiple readers share one entry. Each entry is ref-counted: when the last reader of a query vector goes away — a row unmounts, the feed shrinks — the entry is evicted *in the same tick*, its reaction disposed, and a `:rf.sub/dispose` trace fires. Scroll a virtualised feed and the per-slug subs come and go with the rows; nothing accumulates. (The mechanics are in [Subscriptions → Lifecycle](../subscriptions.md#lifecycle-a-sub-exists-only-while-something-watches).)

### Stable callbacks — only with a measurement

Every render that writes `#(dispatch [:article/toggle-favorite slug])` mints a fresh function object. It's behaviourally identical to last render's, but `=` between two anonymous fns is `false`, so a *view* receiving it as a prop sees a change and re-renders for nothing. This is invisible on a cheap child. It matters only when the Views tab shows an **expensive** child re-rendering whose data didn't change: the callback prop is the churn.

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

!!! note "Why a Form-2 view?"

    The factory must be built *once per row*, not once per render — otherwise you've minted a fresh "stable" object each render and bought nothing. A Form-2 view (a render body that returns another fn) gives you exactly that seam: the outer fn runs once at mount and is where the factory lives; the inner fn is the render fn. ([Views](../views.md) explains the three view shapes.)

??? info "For JavaScript developers"

    `useCallback(fn, [slug])` exists to hand a child a stable function identity so it can `memo` past a no-op prop change. The factory-factory does the same job, but it sidesteps the stale-closure trap that bites `useCallback` when the dependency array is wrong: the callback object is stable *and* always sees current args, because the args live in an atom the factory refreshes, not in a captured closure. Same payoff (skip the expensive child's re-render), no dependency array to get wrong.

And don't be too paranoid about any of this. Use the factory pattern with a measurement in hand, not "on spec" across every list in the app: it trades a little reading clarity for a saved re-render, and that trade only pays off when the saved re-render is expensive. Most lists never need it. A button is not a chart.

---

## 4 — Only slow in production: the `rf:` timing channel

Xray rides the dev [trace stream](../glossary.md#trace-stream), which is [elided](../glossary.md#elide) from production builds, so it can't see a slowness that only happens live. For that there is a second, narrower channel built for production. The runtime brackets its **four** hot paths with the browser's User Timing API (`performance.mark` / `performance.measure`), and entries are named `rf:<bucket>:<id>`. Two of the four buckets name things worth a quick gloss: an [effect](../glossary.md#effect) is a described side-effect the runtime carries out for you, and an [event handler](../glossary.md#event-handler) is the pure function that runs in response to an event.

| Bucket | Fires on | Entry name |
|---|---|---|
| `event` | an event handler ran (the full interceptor chain for one event) | `rf:event:article/toggle-favorite` |
| `sub` | a subscription recomputed its body | `rf:sub:feed/slugs` |
| `fx` | one effect executed (including reserved fx like `:dispatch` and managed HTTP) | `rf:fx:rf.http/managed` |
| `render` | a `reg-view` rendered | `rf:render:my.app/article-row` |

The id keeps its full namespace, so you can split on the second `:` for a per-bucket view. Note that only `reg-view` views show up under `rf:render:` — a plain `defn` view has no registered id to bracket, which is one more reason to register the views you care about measuring.

!!! warning "Gotcha — a throwing path still leaves a measure"

    Each bracket is a `try/finally`, so if the body it wraps throws — a handler blows up, a render dies — the `:end` mark fires and the `measure` entry still lands before the exception propagates. That's deliberate: the slowest interactions are often the ones that then error, and you don't want their timing to vanish exactly when you need it. So a `rf:event:…` or `rf:render:…` bar in the profile is *not* proof that path completed cleanly — cross-check against your error listeners.

The channel is off by default. It is gated on its own compile-time flag, independent of `goog.DEBUG`:

```edn
;; shadow-cljs.edn — the build you want to measure
{:builds
 {:app {:target           :browser
        :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

A build that doesn't flip the flag carries **zero** User-Timing bytes, because dead-code elimination elides every bracket. CI proves this both ways: `npm run test:perf-bundle` builds the same example twice — flag off and flag on — and asserts the off bundle contains no `performance.measure`, `clearMeasures`, or `rf:` fragment while the on bundle contains all three. (The bracket allocates no `performance.mark` at all — it uses the options-bag `measure` form — so that string is absent from both bundles.) So keeping the channel on in production is a deliberate, cheap choice ([Configure dev and production builds](configure-dev-and-prod.md)).

To read it, open the Chrome DevTools **Performance** panel, where the `rf:` measures appear as named bars beside React renders, paint, and layout (the panel captures entries as they are emitted, so it sees them regardless of the buffer-retention flag below).

The channel is **observer-first**: each bracket emits its measure, hands it to any live `PerformanceObserver`, then clears it from the retained buffer so a long-running page doesn't accumulate entries forever. That means `performance.getEntriesByType('measure')` returns `[]` in a normal build — the entries were delivered, not retained. For continuous telemetry, attach a `PerformanceObserver`, which fires per entry as it lands, and forward `rf:`-prefixed measures to your APM:

```javascript
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      sendToAPM(e);                 // { name, startTime, duration }
    }
  }
}).observe({ type: 'measure', buffered: true });   // buffered: replay entries from before this observer attached
```

The diagnosis reads the same as rung 1. One wide `rf:render:` or `rf:sub:` bar (in the panel, or in an observer's stream) is misplaced work (rung 2); a cloud of identical narrow `rf:render:` bars per interaction is a storm (rung 3).

For a quick one-shot console snapshot instead of an observer, build with `:closure-defines {re-frame.performance/retain-entries? true}` — that skips the per-emit clear so entries persist in the buffer for `getEntriesByType`:

```javascript
// only populated when retain-entries? is on
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'))
  .sort((a, b) => b.duration - a.duration)
  .slice(0, 20);
```

Leave `retain-entries?` off in production: the [W3C User-Timing buffer is unbounded](https://developer.mozilla.org/en-US/docs/Web/API/Performance/measure) (`maxBufferSize` is Infinite for measure entries), so retaining every entry across a multi-hour session leaks memory. re-frame2 clears after emit precisely so it doesn't.

Once more, because it decides what ships: the channel is off by default, and it rides its own compile-time flag, distinct from the dev-trace gate. [Observability](../observability.md#production-the-wire-disappears--errors-dont) covers what ships and what doesn't.

??? info "For JavaScript developers"

    `rf:` measures are plain [User Timing API](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing) `measure` entries — the same ones React emits, the same ones your APM (Datadog RUM, Sentry, New Relic) already ingests. There's no re-frame2-specific tooling to install on the production side: it's `performance.getEntriesByType('measure')` and a `PerformanceObserver`, exactly as you'd instrument any web app. The framework just gives every event, sub, fx, and render a stable, namespaced entry name for free.

One warning, and it undoes everything above if you skip it: **never profile the dev build.** The dev build carries the whole trace surface, so the profile ends up measuring the measurement apparatus — you'll chase phantom costs that vanish in production. Build `:advanced` with the perf flag on, serve *that*, and profile that. The numbers you get are the numbers your users get.

!!! note "JVM and SSR"

    The `rf:` channel is browser-only — on the JVM the `enabled?` flag is a `^:const false` and the brackets compile to pure pass-through. If you're profiling SSR or a headless render, reach for a JVM profiler (clj-async-profiler, JFR) instead; the User-Timing path isn't there to read.

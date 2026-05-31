# 15 - Performance

Your app got slow and you have no idea why. That's the situation this chapter is for, and the good news is that the why is almost always one of four specific shapes, the framework has a clean answer for each, and the tooling will tell you which one you did. The better news is that you probably won't need any of it for a long while — re-frame2 is fast by default, and the default holds for a counter, a form, and an HTTP fetch without you thinking about performance once.

## Fast by default, and why that's not a boast

Let me defend the "fast by default" claim before I teach you how to break it, because the claim is the foundation and the breakage is the exception.

re-frame2 trims work everywhere the architecture lets it, and it lets it in a lot of places *because* of the shape we set up in the earlier chapters. Subscriptions are caches, keyed per-frame — a sub body runs only when its inputs change, and otherwise hands back the value it computed last time, for free ([chapter 05](05-subscriptions.md)). The drain coalesces: a cascade that touches `app-db` five times doesn't paint five times, it settles and paints once. And views can run pure-equality checks to skip work because the inputs are *data* — immutable values that compare by structure, so "did this actually change?" is a question the runtime can answer cheaply instead of re-rendering on faith.

None of that is something you turn on. It's the standing consequence of state being one immutable value, derivations being cached pure functions, and views being boring derivative render functions. You got the performance the moment you adopted the architecture. So this chapter is a *deep-dive*, and the honest framing is: don't read it as a checklist to apply to a healthy app. Read it the first time a profiler tells you there's a problem, and not before. Premature optimisation here is the same vice it always is — you'll contort views chasing a re-render storm that isn't happening.

One scoping note before we start: the whole chapter is a *browser* story. The dynamic model is identical on the JVM, but rendering performance is something that only exists in front of a DOM. JVM-side timing — SSR, headless jobs — belongs to host profilers (clj-async-profiler, JFR), not to re-frame2's surfaces, which are browser-only by construction.

## The four shapes of slow

Almost every "this page feels slow" report reduces to one of four shapes. They all share a root cause — *unnecessary work* — but the *kind* of unnecessary work differs, and so does the fix, so it's worth being able to name them on sight.

**Shape 1 — big-prop re-render storms.** A parent renders a long list of children, and hands each child a chunk of state bigger than it needs. Something mutates deep in the data, the parent re-renders, and Reagent walks *every* child running an `=` check on a deep tree, only to conclude the visible output didn't change. Multiply by 361 grid cells or 200 todo rows and the page hitches every time anything in the underlying data so much as twitches. This is the original re-frame v1 case, and it survived intact into v2 because Reagent's prop-equality contract didn't change. The cure is one sentence: **don't hand a child more state than it needs to render itself.**

**Shape 2 — deep `=` checks on hiccup-heavy trees.** A view computes a large hiccup tree — a table, a grid, a tree-of-trees. On each re-render the substrate walks that tree and compares it node-by-node against the previous one. Most nodes are equal, but the equality is *structural* — it recurses the whole shape — and on a big tree the cost adds up even when nothing changed. The cure overlaps with shape 1 but isn't identical: even with thin individual props, *very deep* hiccup forces deep comparisons, so you push the depth-checking earlier — into subs that already cache, into child components with flat props, into React `:key` boundaries that let the substrate say "keys match, skip" instead of "let me walk this entire subtree to be sure."

**Shape 3 — inline anonymous callbacks.** Every render of a parent that writes `:on-click (fn [e] (dispatch [:something id]))` mints a *fresh function object*. It's structurally identical to last render's, but `=` on two anonymous fns is false, so React's reconciler sees the prop changed, swaps the event handler, and — depending on what sits underneath — may re-render the child for no reason at all. For most components this is invisible. For a child that's *expensive to re-render* — a complex form field, a chart, a row in a long list — replacing its `:on-click` every render defeats whatever memoisation it would otherwise enjoy.

**Shape 4 — subs that compute too much.** A subscription is a cache, and its promise is "run my body only when my inputs change." When the body does real work — a `sort-by` over thousands of items, a graph walk, a JSON parse, an HTTP-response decode — that cache earns its keep on every hit. But it has a cache's failure mode too: if the input *changes too often* — say the sub reads the whole `app-db` instead of a slice — the cache misses constantly and the body runs every render. A sub that takes 30 ms to settle and runs three times per drain has cost the user a frame. The cure is the inverse of shape 1: **subs should compute, views shouldn't.** A view re-renders whenever any deref'd input changes and its body fires every time; a sub re-runs only when its inputs change. Push the work upstream.

The four interact, which is why a slow page often shows two or three at once. A view that computes (4) is also a view that's expensive to re-render, which makes inline callbacks (3) hurt more. A child handed a big prop (1) is usually also a child with a deep tree under it (2). Treat the taxonomy as a diagnostic vocabulary, not four separate problems — the cures compose, and you'll often apply two together.

## The framework's answers

Five rules cover almost the whole surface. A few of them you've met earlier in the guide; this chapter brings them under one roof and attaches them to the shapes above.

### Give views thin props, not deep state

A child should receive *the minimum data it needs to render itself*. Not the whole `app-db`. Not the whole `:items` vector. Not the entire row map when only the id and the toggle-state matter.

```clojure
;; Bad — pass the whole row map down. Every change to any cell of any row
;; makes Reagent walk every TodoItem's `=` check on the full row map.
(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos/all])]
     ^{:key (:id todo)} [todo-item todo])])

;; Better — pass just the id. The child fetches what it needs through a
;; parameterised sub that the framework caches per-id.
(rf/reg-view todo-list []
  [:ul
   (for [id @(subscribe [:todos/ids])]
     ^{:key id} [todo-item id])])

(rf/reg-view todo-item [id]
  (let [todo @(subscribe [:todo/by-id id])]
    [:li [:input {:type "checkbox" :checked (:done? todo)}]
         [:span (:text todo)]]))
```

The reshape is small and the win is real. A click on row 47 fires `:todo/toggle 47`, the slice at `[:todos 47]` changes, `:todo/by-id 47` invalidates, and *only* `todo-item` for id 47 re-renders. The other 199 rows' `:todo/by-id` subs return cached values, and Reagent skips re-rendering them because their props — the ids — didn't change. This is the canonical fix for shape 1, and it's the first refactor you reach for in the worked example below.

### React `:key` metadata matters

When you render a collection with `for`, attach `^{:key …}` pointing at a *stable* identifier:

```clojure
;; Right — stable key per row.
(for [id @(subscribe [:todos/ids])]
  ^{:key id} [todo-item id])

;; Wrong — no key. React falls back to position-based reconciliation;
;; inserting at the front rebuilds every row beneath the insertion.
(for [id @(subscribe [:todos/ids])]
  [todo-item id])

;; Also wrong — `(rand)` as a key. Every render gets a new key, so every
;; child is "different" and remounts. Worse than no key at all.
(for [id @(subscribe [:todos/ids])]
  ^{:key (rand)} [todo-item id])
```

The key tells the reconciler "this node is the same node as last render's node with this same key, even if it moved." Without it the substrate falls back to position, so a delete at the top of a 200-row list shifts every row's identity by one and the substrate dutifully re-renders all 199. With a key, it sees the same DOM nodes are still there, just one row shorter. This is the single cheapest performance fix in any list-shaped UI — if you take exactly one rule from this chapter, take this one.

### The stable-callback pattern

For most components, an inline `:on-click` is fine — forget about it. For a child that's re-rendered *many* times — a row in a long list, a cell in a grid, a field in a 30-field form — replacing the callback every render defeats whatever memoisation the child could benefit from. v1 named the fix the **callback-factory-factory**, and v2 keeps the shape.

The naïve attempt — hoist the callback into a `let` — falls over when the callback needs to close over an argument that changes between renders. Hoist `(fn [] (dispatch [:something id]))` into the outer `let` and it captures the *first* `id` forever; later renders see a stale closure. The fix is to make the callback take its dynamic args explicitly, and use a factory that always returns the same callback object:

```clojure
(defn callback-factory-factory
  "Returns a fn that always returns the same callback object every time it
   is called. The callback closes over an atom; the factory updates the atom
   with the dynamic args; the callback applies them at call time.

   Stable identity across renders + dynamic args = no false invalidation."
  [the-real-callback]
  (let [*args1        (atom nil)
        same-callback (fn [& args2]
                        (apply the-real-callback (concat @*args1 args2)))]
    (fn callback-factory [& args1]
      (reset! *args1 args1)
      same-callback)))

(rf/reg-view todo-item [_]
  ;; Form-2 — the outer let runs once per component instance; the inner fn
  ;; is the per-render render fn.
  (let [on-toggle         (fn [id _dom-event] (dispatch [:todo/toggle id]))
        on-toggle-factory (callback-factory-factory on-toggle)]
    (fn render [id]
      (let [todo @(subscribe [:todo/by-id id])]
        [:li [:input {:type     "checkbox"
                      :checked  (:done? todo)
                      :on-change (on-toggle-factory id)}]
             [:span (:text todo)]]))))
```

Every render passes a fresh `id` into the factory; the factory updates the atom and returns the *same* `same-callback` reference. React sees the prop is `=` to last render's, declines to swap the handler, and the DOM listener stays put. The inner closure reads the current `id` off the atom when the click actually fires.

The form-2 wrapper — that inner `(fn render …)` — is doing real work, not decoration: it's what makes the outer `let` run *once per component instance* rather than once per render. Without it, the factory itself would be reconstructed every time and the whole point would evaporate.

And the discipline that matters more than the mechanism: **reach for this only when you need it.** The trigger is a profiler pointing at "rendering" as the hot spot, in a component repeated many times, with an inline callback in its props. For a one-off button, an inline `(fn [_] (dispatch [...]))` is correct and this pattern is over-engineering.

### Compute in subs, not views

[Chapter 06 makes the case in full](06-views.md); the performance version is one sentence longer: **everything you compute in a view runs on every re-render of that view; everything you compute in a sub runs only when the sub's inputs change.**

```clojure
;; Bad — sort runs on every re-render of every ancestor.
(rf/reg-view product-list []
  [:ul
   (for [p (sort-by :price @(subscribe [:products]))]
     ^{:key (:id p)} [:li (:name p) " — $" (.toFixed (:price p) 2)])])

;; Good — sort runs once per change to :products, cached, shared.
(rf/reg-sub :products/by-price
  :<- [:products]
  (fn [products _]
    (->> products
         (map #(update % :price (fn [n] (.toFixed n 2))))
         (sort-by :price))))

(rf/reg-view product-list []
  [:ul
   (for [p @(subscribe [:products/by-price])]
     ^{:key (:id p)} [:li (:name p) " — $" (:price p)])])
```

The before/after looks small and the win compounds: every view wanting the sorted-and-formatted list shares the one cached value, and the body fires *only* when `:products` itself changes, not when some unrelated slice does.

The cousin rule lives in the same place: **keep sub bodies cheap.** A 50 ms sub body runs on every cache miss, and if your inputs change every drain — because the sub reads `(:everything db)` instead of a slice — you've turned the cache into a tax that runs every frame. The cure is to *narrow the inputs*: write a thin sub that projects exactly the slice you need, then layer the expensive sub on top of *that*, so the cache hits the common case and the heavy body fires rarely.

### When to reach for the chunked-work machine

Some work is genuinely expensive — iterating a million-element vector, parsing a large blob, running a simulation step — and none of the rules above will save you, because the work *itself* is the bottleneck, not the wiring around it. The naïve "just do it inline" answer freezes the UI for the duration: the browser raises a "page unresponsive" warning, clicks queue up, the cancel button is unclickable.

There are two real answers, and which one fits depends on whether the work can leave the main thread:

1. **Offload to a Web Worker.** The main thread stays responsive, the work runs at full speed on another core, and progress flows back as events. The right answer when the work is serialisable across the worker boundary.
2. **Chunk and yield on the main thread.** When the work *has* to run on the main thread — DOM access, framework state, data that's awkward to serialise — split it into small batches and hand the thread back to the browser between them. That's a state machine.

The chunked machine has a canonical shape covered in detail at [chapter 12 §Pattern-LongRunningWork](12-machines.md). The summary: a five-state machine — `:idle`, `:processing`, `:checking-done`, `:yielding`, `:complete` — with `:after 0` in `:yielding` to yield the thread to the browser between batches. Progress is a snapshot field; cancellation is a transition, not a flag. The shape is a state machine precisely *because* "where are we in this long job, and can I still cancel it?" is exactly the question state machines exist to answer.

One historical note so you don't go looking for the old idiom: v1 handled this with `^:flush-dom` event metadata and self-redispatching `{:dispatch [...]}` tail-call loops. Both are gone. The chunked machine is the v2 substitute, and the rule of thumb is to reach for it whenever you're tempted to write a `for` loop that holds the thread for more than ~16 ms — one frame's budget.

## A worked example — the laggy checklist

Theory's cheap; here's the journey. A small app: 200 todo items, each with a checkbox, click to toggle. One careless implementation hitches on every click; three small refactors make the same UI feel instant. The example walks three of the four shapes and shows the cure for each, so you can see how the diagnosis turns into the fix.

The model is trivial and stays trivial — that's the point, the trouble is all in the view. `app-db` holds 200 todos and one event toggles by id:

```clojure
{:todos [{:id 0 :text "Item 0" :done? false}
         {:id 1 :text "Item 1" :done? false}
         ;; ... 198 more
         {:id 199 :text "Item 199" :done? false}]}

(rf/reg-event-db :todo/toggle
  (fn [db [_ id]]
    (update-in db [:todos id :done?] not)))
```

### Round 1 — naïve

The first cut hands the whole `:todos` vector down and skips the key:

```clojure
(rf/reg-sub :todos
  (fn [db _] (:todos db)))

(rf/reg-view todo-item [todo]
  [:li
   [:input {:type     "checkbox"
            :checked  (:done? todo)
            :on-change (fn [_] (dispatch [:todo/toggle (:id todo)]))}]
   [:span (:text todo)]])

(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos])]
     [todo-item todo])])           ;; no :key
```

Read this through the taxonomy and the bugs name themselves:

- **Shape 1 — big-prop storm.** `todo-item` gets the whole row map. Any toggle re-fires the `:todos` sub (it depends on the whole vector), so every `todo-item` is re-invoked. The 199 untouched rows' maps are `=` to before, so React skips their DOM — but the `=` work *still runs 200 times*.
- **Shape 2 — no `:key`.** Position-based reconciliation. Fine while nothing's inserted or removed; the instant you sort, every row remounts.
- **Shape 3 — inline callback.** Every render of `todo-item` rebuilds `:on-change`, replacing the DOM listener. Invisible at this size — but load-bearing in round 3.

Profile it with the `rf:` channel on (below) and a single toggle shows one wide `rf:event:todo/toggle` bar, one wide `rf:sub:todos` bar, then a *cloud of 200 narrow* `rf:render:my-app/todo-item` bars. Each is only ~0.3 ms, but 200 of them is 60 ms — four frames of jank from one click.

### Round 2 — thin props plus keys

Split the sub, narrow the prop, add the key:

```clojure
(rf/reg-sub :todos/ids
  :<- [:todos]
  (fn [todos _] (mapv :id todos)))

(rf/reg-sub :todo/by-id
  :<- [:todos]
  (fn [todos [_ id]] (nth todos id)))

(rf/reg-view todo-item [id]
  (let [todo @(subscribe [:todo/by-id id])]
    [:li
     [:input {:type     "checkbox"
              :checked  (:done? todo)
              :on-change (fn [_] (dispatch [:todo/toggle id]))}]
     [:span (:text todo)]]))

(rf/reg-view todo-list []
  [:ul
   (for [id @(subscribe [:todos/ids])]
     ^{:key id} [todo-item id])])
```

- **Shape 1 gone.** `todo-list` now subscribes to `:todos/ids` — a vector of integers. Toggle one item and the *ids vector* is `=` to before, so the sub returns its cached value and `todo-list` doesn't re-render *at all*. Each `todo-item` subscribes to `:todo/by-id` for its own id; only the toggled item's sub invalidates; only that item re-renders.
- **Shape 2 gone.** `^{:key id}` is present, so reorder/insert/delete diffs against stable identity instead of position.

Profile again: the toggle now shows `rf:event:todo/toggle`, `rf:sub:todos` (still invalidates — the vector changed), `rf:sub:todos/ids` (returns cached, *no body fires*), `rf:sub:todo/by-id` for id 47 (fires), and **one** `rf:render:my-app/todo-item` for id 47. 60 ms became 2 ms. The hitch is gone.

This is *almost always* where the journey ends. The 200-row UI feels instant; you ship.

### Round 3 — stable callbacks (only when it pays)

You go further only with a measurement in hand. Suppose the row is no longer a thin checkbox-and-text but a *complex* row — a chart, a contextual menu, a status indicator reading from its own derived sub. Now the inline `:on-change` is a real cost: every re-render of the row's parent (a toolbar updating somewhere up the tree) replaces the row's handler and makes React reconsider whether the whole expensive row needs rebuilding. Apply the stable-callback pattern:

```clojure
(defn callback-factory-factory [the-real-callback]
  (let [*args1        (atom nil)
        same-callback (fn [& args2]
                        (apply the-real-callback (concat @*args1 args2)))]
    (fn callback-factory [& args1]
      (reset! *args1 args1)
      same-callback)))

(rf/reg-view todo-item [_]
  ;; Form-2 again — the factory and the real callback are constructed once
  ;; per component instance in the outer let; the inner render fn re-runs.
  (let [on-toggle        (fn [id _dom-event] (dispatch [:todo/toggle id]))
        on-toggle-factory (callback-factory-factory on-toggle)]
    (fn render [id]
      (let [todo @(subscribe [:todo/by-id id])]
        [:li
         [:input {:type     "checkbox"
                  :checked  (:done? todo)
                  :on-change (on-toggle-factory id)}]
         [:span (:text todo)]]))))
```

The outer fn runs once per `todo-item` instance, so factory and real callback are built once, not per render; the inner `render` re-runs, calls `(on-toggle-factory id)`, and always gets the same `same-callback` back. The atom holds the latest `id`; the closure applies it when the click fires. When `:on-change` is `=` to last render's, React keeps the DOM listener and skips a noticeable chunk of substrate-internal work — for a complex row that's a real saving, for a checkbox-and-text it's nothing.

The right time: the profiler says renders are *still* the hot spot after you've split props, and the children are complex enough to feel it. The wrong time: every list-shaped view in the app, on spec, because the pattern looks clever. Round 2 is the destination for almost everything.

### What the ladder teaches

Three refactors, in increasing order of cleverness, and you climb them in order:

1. **Cheap and universal** — thin props plus React keys. Apply everywhere; it costs nothing and pays off the first time a list gets long.
2. **Cheap and conditional** — compute in subs, not views. Apply whenever a view does more than walk-and-emit hiccup.
3. **Expensive and rare** — stable callbacks. Apply only when a profiler proves renders are the bottleneck *and* the children are complex enough to feel it.

Most apps stop on rung one.

## The `rf:` Performance API surface

The trace bus from [chapter 16 — Observability](16-observability.md) is dev-only — it carries causal detail and disappears in production. But there's a second, narrower observation channel built for *production* timing: APM dashboards, in-house perf overlays, "is this page slower in prod than in dev?" investigations. It rides the browser's [User Timing API](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing).

When it's on, the runtime brackets four hot-path call sites with `performance.mark` / `performance.measure` entries, all stably named under the `rf:` prefix:

| Bucket | Where | Entry name |
|---|---|---|
| `:event`  | Event handler invocation (the interceptor chain) | `rf:event:<event-id>` |
| `:sub`    | Subscription recompute | `rf:sub:<sub-id>` |
| `:fx`     | Per-fx walk-step (one entry per fx that fires) | `rf:fx:<fx-id>` |
| `:render` | Per-`reg-view` render | `rf:render:<view-id>` |

Keyword namespaces survive onto the wire, so the entry names read like the ids you wrote:

```
rf:event:auth/login
rf:sub:cart/total
rf:fx:rf.http/managed
rf:render:my.app/page-header
```

### Turning it on

The channel is gated on a `goog-define`d boolean — `re-frame.performance/enabled?` — defaulting to `false`. A production build that doesn't ask for timing carries *zero* User-Timing bytes: Closure DCE elides every emit site, every entry-name string, every bracket call. You don't pay for what you don't turn on.

To flip it on for a build, set the define in shadow-cljs:

```edn
{:builds
 {:app
  {:target           :browser
   :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

Under `:advanced`, the flag constant-folds at compile time, the gated branch survives, and the brackets become live measurements. Crucially the flag is *independent* of `goog.DEBUG`, and the two compose: a dev build can run trace-on / perf-off (the default), a production build can run trace-off / perf-on, and the four combinations give you four sensibly-sized bundles. That independence is the whole reason the perf channel exists separately — so production telemetry never drags in the heavyweight dev trace surface.

### Reading the entries

Three consumers, in increasing order of effort:

**The Chrome DevTools Performance panel.** Open it, record, drive the app, stop. The `rf:` measures render as named bars right alongside React renders, network, paint, and layout — bar width is duration, bar name is the entry id. No custom UI; you get a profiler view of "what re-frame2 did and how long it took" for free.

**Ad-hoc in the console:**

```javascript
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'))
  .sort((a, b) => b.duration - a.duration)
  .slice(0, 20);
```

That's the twenty slowest re-frame2 measurements from the latest profile. Each entry is a standard `PerformanceMeasure` — `{name, startTime, duration, ...}` — so you can group by bucket (split on the second `:`) to see which of event/sub/fx/render is hottest.

**Programmatic and streaming** — attach a `PerformanceObserver` and forward to your APM:

```javascript
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      sendToAPM(e);   // { name, startTime, duration, ... }
    }
  }
}).observe({ type: 'measure', buffered: true });
```

`buffered: true` delivers entries that fired *before* the observer attached — important for SPAs where the observer mounts after the first cascade has already run.

### A workflow that doesn't lie to you

1. **Don't profile dev builds.** Dev runs with `goog.DEBUG=true`, which keeps the trace surface live, and the trace work shows up in the profile and is *not* representative of production. Build `:advanced` with the perf flag on, serve that, profile that. Profiling the dev build is measuring the measurement apparatus.
2. **Record a representative interaction.** DevTools Performance panel, record, do the slow thing, stop. The `rf:` measures appear on their own track.
3. **Find the wide bars first.** The slowest single measurement is usually where the problem lives. A wide `rf:render:<view-id>` means an expensive view — check its props (shape 1), its sub usage (shape 4), its callbacks (shape 3). A wide `rf:sub:<sub-id>` means a heavy sub body — either the cache is missing or the work itself should chunk.
4. **Count the narrow bars second.** One fast bar is fine; a *cloud* of fast bars repeated 200 times is a re-render storm. Filter by id and count — if `rf:render:my.app/todo-item` fires 200 times per drain, every drain, that's shape 1, and you're back at round 2 of the worked example.

One operational caveat: the Chrome User-Timing buffer is bounded (default ~10000 entries), so long-running pages that want every entry should attach the observer and offload to durable storage rather than rely on `getEntriesByType` after the fact.

### What the channel is, and isn't

The `rf:` channel is a *timing* channel: it tells you how long each call took. It does **not** tell you *why* — that's the trace bus's job. The two compose. In dev you run both, and cross-reference a wide `rf:render:foo` bar against the trace event for the dispatch that triggered it. In prod you run just `rf:` and let your APM aggregate "render of `foo` is at p99 = 80 ms across the last hour." And the channel is **CLJS-only**: JVM artefacts emit no User-Timing entries, because the API is browser-only. JVM profiling uses host profilers (clj-async-profiler, JFR), full stop.

## What this chapter deliberately skips

A few performance topics that *aren't* re-frame2 stories, named so you don't go hunting for them here:

- **Bundle size** is a Closure-compiler story. The trace surface and the `rf:` channel both DCE under `goog.DEBUG=false`; keeping *your* code small is an `:advanced`-compiler concern, not a framework one.
- **Animation jank** is usually DOM/CSS. Reagent renders into React renders into the DOM; smooth `transform`/`opacity` lives one layer deeper, with the browser's `will-change` and compositing tricks. The chunked-work machine helps only when you've blocked the main thread.
- **SSR performance** is JVM-side, the `rf:` channel is browser-only, and profiling it uses host tools. The shape of [chapter 20](20-server-side.md) governs server-side cost, not this chapter.
- **Sub-graph topology** — `(rf/sub-topology)`, surfaced in [Xray](../xray/index.md) (the tooling tour is [chapter 17](17-tooling.md)) — is the lever for finding dead subs and visualising dependencies. The performance angle is "every redundant edge is a potential cache miss"; the tooling covers the rest.

The throughline, if you take nothing else: four shapes account for nearly every slowdown, the cures compose and stack in order of cost, and most apps never climb past thin-props-and-keys. The framework hands you fast by default; this chapter is just the map for the day you accidentally gave it back.

Performance is the first chapter where the raw runtime evidence starts to feel indispensable. You can reason about keys and thin props in prose, but the moment a real app gets weird you want the trace, the epoch, the timing mark, and the panel that puts them together. That is observability.

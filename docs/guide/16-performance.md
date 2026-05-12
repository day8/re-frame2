# 16 — Performance

re-frame2 is a fast pattern, but it isn't a magic one. The runtime trims work where the architecture lets it — subs are cached per-frame, drains coalesce renders, views can run pure-equal checks on data because *data is data* — and the rest is up to you. When your app gets slow, the cause is almost always one of four shapes, and the framework has an answer for each.

This chapter is a deep-dive. The core path doesn't need it — a counter, a form, an HTTP fetch all run at interactive speed without you doing anything special. Pick it up the first time the profiler says you have a problem.

What you'll come away with:

- A short, named taxonomy of where slowness comes from in a re-frame2 app.
- The framework's answer to each shape — how to write views, subs, and callbacks so the pipeline stays cheap.
- When to reach for the chunked-work state machine from [chapter 08](08-state-machines.md#pattern-longrunningwork--cpu-bound-work-as-a-chunked-machine), and when to offload to a worker instead.
- The `rf:` Performance API surface from [chapter 15](15-devtools-and-pair-tools.md#performance-the-prod-friendly-channel) and how to read it in Chrome DevTools.
- One worked example: a list-with-checkboxes that goes from "noticeably laggy" to "feels instant" through three small refactors.

The whole chapter is CLJS-flavoured — the dynamic model is the same on the JVM, but rendering performance is a browser story. JVM-side timing belongs to host profilers, not to re-frame2's surfaces.

## Where slowness comes from

Four shapes account for almost every "this page feels slow" report. Each one has the same underlying cause — *unnecessary work* — but the *kind* of unnecessary work differs, and so does the fix.

### 1. Big-prop re-render storms

A parent component renders a long list of children. Each child is handed a chunk of state that's bigger than it needs. The state mutates somewhere deep, the parent re-renders, and Reagent walks every child checking `=` on a deep tree only to conclude that the visible output didn't change. Multiply that by a list of 361 grid cells, or 200 todo rows, and the page hitches every time anything in the underlying data touches.

This is the original re-frame v1 case ([Performance-Problems.md §1](https://github.com/day8/re-frame/blob/master/docs/Performance-Problems.md)) and it survives intact in v2 because Reagent's prop-equality contract hasn't changed. The cure is the same: **don't hand a child more state than it needs to render itself**.

### 2. Deep `=` checks on hiccup-heavy trees

A view computes a large hiccup tree (a table, a grid, a tree-of-trees). On each re-render the framework substrate walks that tree and compares it against the previous one. Most nodes are `=`, but the equality is structural — it has to recurse the whole shape — and the cost adds up when the tree is large.

The fix overlaps with the first one but isn't identical: even when individual props are thin, *very deep* hiccup output forces deep comparisons. The cure is to push the depth-checking earlier — into subs that already cache, into child components whose props are flat, into React `:key` boundaries that let the substrate decide "the keys match, the work was identical, skip" rather than "let me walk this whole tree to confirm."

### 3. Inline anonymous callbacks

Every render of a parent that writes `:on-click (fn [e] (dispatch [:something id]))` creates a *fresh function object*. The function is structurally identical to last render's, but `=` on two anonymous fns is false. React's reconciler sees the prop changed, replaces the event handler, and (depending on which component sits underneath) may force a re-render of the child for no reason at all.

For most components this is invisible. For a child that's *expensive to re-render* — a complex form field, a chart, a row in a long list — replacing its `:on-click` every time the parent renders is the same as having no memoisation at all.

### 4. Subs that compute too much

A subscription is a cache. The promise is "run my body only when my inputs change." When the body is doing real work — a `sort-by` over thousands of items, a transitive walk of a graph, a JSON parse, an HTTP-response decode — the cache earns its keep on every cache hit.

But the failure mode is the same as for any cache: if the input *changes too often* — for example, the sub reads the whole `app-db` instead of a slice — the cache misses constantly and the body runs every render. A sub that takes 30 ms to settle and runs three times per drain costs the user a frame.

The fix here is the inverse of #1: **subs should compute, views shouldn't**. A view re-renders any time *any* deref'd input changes; the body of the view fires every time. A sub re-runs only when its inputs change. Push the work upstream.

---

These four shapes interact. A view that computes (#4) is also a view that's expensive to re-render, which makes inline callbacks (#3) hurt more. A child handed a big prop (#1) is also a child with a deep tree under it (#2). The taxonomy is for diagnosis; the cures compose.

## The framework's answers

Five rules cover most of the surface. Three of them appear earlier in the guide; this chapter brings them under one heading.

### Give views thin props, not deep state

A child component should receive *the minimum data it needs to render itself*. Not the whole `app-db`. Not the whole `:items` vector. Not the entire row map when only the id and the toggle-state matter.

```clojure
;; Bad — pass the whole row map down. Every change to any cell of any
;; row makes Reagent walk every TodoItem's `=` check on the full row.
(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos/all])]
     ^{:key (:id todo)} [todo-item todo])])

;; Better — pass just the id. The child fetches what it needs through
;; a parameterised sub that the framework caches per-id.
(rf/reg-view todo-list []
  [:ul
   (for [id @(subscribe [:todos/ids])]
     ^{:key id} [todo-item id])])

(rf/reg-view todo-item [id]
  (let [todo @(subscribe [:todo/by-id id])]
    [:li [:input {:type "checkbox" :checked (:done? todo)}]
         [:span (:text todo)]]))
```

The reshape is small. The win is real: a click on row 47 fires `:todo/toggle 47`, the slice at `[:todos 47]` changes, `:todo/by-id 47` invalidates, only `todo-item` for id `47` re-renders. The other 199 rows' `:todo/by-id` subs return cached values, and Reagent skips re-rendering them because the props (the ids) didn't change.

This is the canonical fix for shape #1 above. It's also the *first* refactor you reach for in the worked example below.

### React `:key` metadata matters

When you render a collection with `for`, attach `^{:key ...}` metadata pointing at a stable identifier:

```clojure
;; Right — stable key per row.
(for [id @(subscribe [:todos/ids])]
  ^{:key id} [todo-item id])

;; Wrong — no key. React falls back to position-based reconciliation;
;; inserting at the front rebuilds every row beneath the insertion.
(for [id @(subscribe [:todos/ids])]
  [todo-item id])

;; Also wrong — `(rand)` as a key. Every render gets a new key, so
;; every child is "different" and remounts. The fix is worse than
;; no key at all.
(for [id @(subscribe [:todos/ids])]
  ^{:key (rand)} [todo-item id])
```

The key tells React's reconciler "this hiccup node is the same node as last render's node with this same key, even if it moved." Without it, the substrate falls back to position; a delete at the top of a 200-row list shifts every row's identity by one, and the substrate dutifully re-renders all 199. With a key, the substrate sees that the same DOM nodes are still there, just one row shorter.

This is the single cheapest performance fix in any list-shaped UI. If you take one rule from this chapter, take this one.

### The stable-callback pattern

For most components, an inline `:on-click` is fine. For a *child that's re-rendered many times* — a row in a long list, a cell in a grid, a form field in a 30-field form — replacing the callback every render defeats whatever memoisation the child might benefit from. The fix v1 named the **callback-factory-factory** pattern; v2 keeps the shape.

The naïve attempt — hoist the callback into a `let` — falls over when the callback needs to close over an argument that changes between renders. Hoisting `(fn [] (dispatch [:something id]))` into the outer `let` captures the first `id` value forever; subsequent renders see a stale closure.

The fix is to make the callback *take its dynamic arguments explicitly* and to use a factory that always returns the same callback fn:

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
  ;; Form-2 — the outer let runs once per component instance; the
  ;; inner fn is the per-render render fn.
  (let [on-toggle         (fn [id _dom-event] (dispatch [:todo/toggle id]))
        on-toggle-factory (callback-factory-factory on-toggle)]
    (fn render [id]
      (let [todo @(subscribe [:todo/by-id id])]
        [:li [:input {:type     "checkbox"
                      :checked  (:done? todo)
                      :on-change (on-toggle-factory id)}]
             [:span (:text todo)]]))))
```

Every render passes a fresh `id` into the factory; the factory updates the atom and returns the *same* `same-callback` reference. React sees the prop is `=` to last render's, declines to replace the handler, and the DOM event subscription stays put. The inner closure picks up the current `id` from the atom when the click fires.

The form-2 wrapper (`(fn render ...)`) is doing real work here — it's what makes the `let` run *once* per component instance, not once per render. Without it the factory itself would be recreated every time.

**Reach for this only when you need it.** A profiler that points at "rendering" as the hot spot, in a component that's repeated many times, with an inline callback in its props, is the trigger. For a one-off button, an inline `(fn [_] (dispatch [...]))` is fine.

### Compute in subs, not views

Chapter 06 makes the case in detail ([§Views compute hiccup only](06-views-and-frames.md#views-compute-hiccup-only)); the performance version is one sentence longer: **everything you compute in a view runs on every re-render of that view; everything you compute in a sub runs only when the sub's inputs change**.

```clojure
;; Bad — sort runs on every re-render of every ancestor.
(rf/reg-view product-list []
  [:ul
   (for [p (sort-by :price @(subscribe [:products]))]
     ^{:key (:id p)} [:li (:name p) " — $" (.toFixed (:price p) 2)])])

;; Good — sort runs once per change to `:products`, cached, shared.
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

The before/after looks small. The win compounds: every view that wants the sorted-and-formatted list shares the cached value, and the body fires *only* when `:products` itself changes — not when an unrelated slice does.

The cousin rule: **keep sub bodies cheap**. A sub that does a 50 ms computation runs on every cache miss; if your inputs change every drain (because the sub reads `(:everything db)` instead of a slice), you've turned the cache into a tax. The cure is to *narrow the inputs* — write a thin sub that projects exactly the slice you need, layer the expensive sub on top of that — so the cache hits the common case.

### When to reach for the chunked-work machine

Some work is genuinely expensive — iterating a million-element vector, parsing a large blob, running a simulation step. None of the rules above will save you, because the work itself is the bottleneck. The naïve "just do it inline" answer freezes the UI for the duration; the browser raises a "page unresponsive" warning; clicks queue up; the cancel button is unclickable.

There are two real answers:

1. **Offload to a Web Worker.** The main thread stays responsive; the work runs at full thread speed on another core; progress reports flow back as events. This is the right answer when the work is serialisable across the worker boundary. See [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md) for the shape.
2. **Chunk and yield on the main thread.** When the work has to run on the main thread — DOM access, framework state, awkward-to-serialise data — split it into small batches and yield between batches. That's a state machine.

The chunked machine has a canonical shape covered in detail in [chapter 08 §Pattern-LongRunningWork](08-state-machines.md#pattern-longrunningwork--cpu-bound-work-as-a-chunked-machine). The summary: a five-state machine (`:idle`, `:processing`, `:checking-done`, `:yielding`, `:complete`) with `:after 0` in `:yielding` to hand the thread back to the browser between batches. Progress is a snapshot field; cancellation is a transition, not a flag. The full worked example is `:counter/scan` in chapter 08.

The v1 idiom for this work — `^:flush-dom` event metadata, self-redispatching `{:dispatch [...]}` tail-call loops — is gone. The chunked machine is the v2 substitute. Reach for it whenever you're tempted to write a `for` loop that holds the thread for more than ~16 ms.

## The `rf:` Performance API surface

The trace bus from [chapter 15](15-devtools-and-pair-tools.md#what-you-get-for-free) is dev-only. For production timing — APM dashboards, in-house perf overlays, "is this page slower in production than in dev?" investigations — re-frame2 ships a second observation channel through the browser's [User Timing API](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing).

When the channel is on, the runtime brackets four hot-path call sites with `performance.mark` / `performance.measure` entries, all stably named under the `rf:` prefix:

| Bucket | Where | Entry name |
|---|---|---|
| `:event`  | Event handler invocation (the interceptor chain) | `rf:event:<event-id>` |
| `:sub`    | Subscription recompute | `rf:sub:<sub-id>` |
| `:fx`     | Per-fx walk-step (one entry per registered fx that fires) | `rf:fx:<fx-id>` |
| `:render` | Per-`reg-view` render | `rf:render:<view-id>` |

Keyword namespaces are preserved on the wire:

```
rf:event:auth/login
rf:sub:cart/total
rf:fx:rf.http/managed
rf:render:my.app/page-header
```

### Turning it on

The channel is gated on a `goog-define`d boolean — `re-frame.performance/enabled?` — that defaults to `false`. Production builds that don't ask for timing carry zero User-Timing bytes; Closure DCE elides every emit site, every entry-name string, every bracket call.

To flip it on for a build, set the goog-define in your shadow-cljs:

```edn
{:builds
 {:app
  {:target           :browser
   :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

`:advanced` constant-folds the flag at compile time, the gated branch survives, and the brackets become live measurements.

The flag is *independent* of `goog.DEBUG`. The two compose: a dev build can run with trace on (`goog.DEBUG=true`) and perf off (default), a production build can run with trace off (`goog.DEBUG=false`) and perf on, and the four combinations correspond to four sensibly-sized bundles. The perf flag exists precisely so production telemetry doesn't drag in the dev trace surface.

### Reading the entries

Three consumers, in increasing order of how much work you put in:

**Chrome DevTools Performance panel.** Open it, hit record, drive the app, stop. The `rf:` measures render as named bars alongside React renders, network, paint, and layout. The bar's width is its duration; the bar's name is the entry id. No custom UI required — you get a profiler view of "what re-frame2 did and how long it took" for free.

**Ad-hoc inspection in DevTools console.**

```javascript
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'))
  .sort((a, b) => b.duration - a.duration)
  .slice(0, 20);
```

That returns the twenty slowest re-frame2 measurements from the most recent profile. The shape of each entry is `{name, startTime, duration, ...}` — standard `PerformanceMeasure` records. Group by `:event` / `:sub` / `:fx` / `:render` (split on the second `:`) to see which buckets are hottest.

**Programmatic, streaming.** Attach a `PerformanceObserver` and forward to your APM:

```javascript
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      sendToAPM(e);   // { name, startTime, duration, ... }
    }
  }
}).observe({ type: 'measure', buffered: true });
```

The `buffered: true` flag delivers entries that fired before the observer was attached — useful for SPAs where the observer mounts after the first cascade has already happened.

### A practical workflow

1. **Don't profile dev builds.** Dev builds run with `goog.DEBUG=true`, which keeps the trace surface live; the trace work itself shows up in the profile and is *not* representative of production. Build with `:advanced` plus the perf flag on, serve the result, profile that.
2. **Record a representative interaction.** Open DevTools' Performance panel, click record, do the thing that feels slow, stop. The `rf:` measures appear under their own track.
3. **Find the wide bars first.** The slowest individual measurement is usually where the problem lives. If it's a `rf:render:<view-id>`, the view is too expensive — look at its props (shape #1 above), its sub usage (shape #4), its callbacks (shape #3). If it's a `rf:sub:<sub-id>`, the sub body is doing real work and either its cache is missing or the work itself should chunk.
4. **Count the narrow bars second.** A single fast bar is fine; a *cloud* of fast bars repeated 200 times is a re-render storm. Filter by event id and count occurrences — if `rf:render:my.app/todo-item` fires 200 times per drain, every drain, that's shape #1.

The Chrome User-Timing entry buffer is bounded (default ~10000 entries). Long-running pages that want every entry should attach the observer and offload to durable storage rather than rely on `getEntriesByType` after the fact.

### What the surface is, and isn't

The `rf:` channel is a *timing* channel. It tells you how long each call took. It does *not* tell you why — the trace bus does that. The two compose: in dev, you run with both on, and you cross-reference a wide `rf:render:foo` bar against the trace event for the dispatch that triggered it. In prod, you run with just `rf:` on, and you let your APM aggregate "render of `foo` is at p99 = 80 ms across last hour."

The perf channel is **CLJS-only**. JVM artefacts (SSR, headless tests, server-side jobs using re-frame2 for state) emit no User-Timing entries — the API is browser-only. JVM profiling uses host profilers (clj-async-profiler, JFR).

## A worked example — the laggy checklist

A small app: a list of 200 todo items, each with a checkbox. Click a checkbox, the item toggles. With one careless implementation, every click hitches; with three small refactors, the same UI feels instant. The story walks the three shapes from the taxonomy above and shows the cure for each.

### Setup

`app-db` holds 200 todos:

```clojure
{:todos [{:id 0 :text "Item 0" :done? false}
         {:id 1 :text "Item 1" :done? false}
         ;; ... 198 more
         {:id 199 :text "Item 199" :done? false}]}
```

One event toggles by id:

```clojure
(rf/reg-event-db :todo/toggle
  (fn [db [_ id]]
    (update-in db [:todos id :done?] not)))
```

That's all the model needs. The trouble lives in the view.

### Round 1 — naïve

The first cut hands the whole `:todos` vector down:

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

What goes wrong, by shape:

- **Shape #1 — big-prop re-render storm.** `todo-item` receives the *whole row map*. When any item toggles, the `:todos` sub re-fires (it depends on the whole vector), every `todo-item` is re-invoked with its row map. The previous row maps are `=` to the new ones for the 199 untouched rows, so React skips the DOM — but the `=` work still runs 200 times.
- **Shape #2 — no `:key`.** Reagent falls back to position-based reconciliation. As long as nothing is inserted or removed it's fine; the moment you sort, every row gets remounted.
- **Shape #3 — inline anonymous callback.** Every render of `todo-item` rebuilds `:on-change`. React replaces the DOM event listener every time. Not visible on this size, but it's load-bearing in the round-3 fix below.
- **Shape #4 — implicit.** The view does no work yet, but if you wanted to render "5 of 200 done" you'd be tempted to compute it inline. Don't.

Profile with `rf:` on: a single toggle shows one wide `rf:event:todo/toggle` bar (the handler runs), one wide `rf:sub:todos` bar (the sub recomputes), then *200 narrow* `rf:render:my-app/todo-item` bars in a cloud. The narrow bars are each only ~0.3 ms, but 200 of them is 60 ms, which is four frames worth of jank.

### Round 2 — thin props + keys

Two changes: split the sub, narrow the prop.

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

What changed:

- **Shape #1 gone.** `todo-list` subscribes to `:todos/ids` — a vector of integers. When a single item toggles, the *ids vector* is `=` to the previous one, so the sub returns the cached value and `todo-list` doesn't re-render at all. Each `todo-item` subscribes to `:todo/by-id` for its own id; only the toggled item's sub invalidates; only that item re-renders.
- **Shape #2 gone.** `^{:key id}` is present. Reorder, insert, delete — Reagent walks the diff against the stable identity, not against position.

Profile again: the toggle now shows `rf:event:todo/toggle`, `rf:sub:todos` (still invalidates because the vector changed), `rf:sub:todos/ids` (returns cached — no body fires!), `rf:sub:todo/by-id` for id 47 (fires), and **one** `rf:render:my-app/todo-item` for id 47. 60 ms became 2 ms. The hitch is gone.

This is *almost always* where the optimisation journey ends. The 200-row UI now feels instant; you can ship.

### Round 3 — stable callbacks (only when it matters)

A measurement-driven reason to keep going: imagine the row is no longer a thin checkbox-and-text but a *complex* row with a chart, a contextual menu, a status indicator that itself reads from a derived sub. Now the inline `:on-change` is a real cost — every re-render of the row's parent (a toolbar updating somewhere up the tree) replaces the row's event handler and forces React to consider whether the row needs rebuilding.

Apply the stable-callback pattern:

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

Two things to notice. First, the outer fn runs once per `todo-item` instance — the factory and the real callback are constructed *once*, not per render. Second, the inner `render` fn is what re-runs; it calls `(on-toggle-factory id)` every time and always gets the same `same-callback` reference back. The atom holds the latest `id`, the closure applies it when the click fires.

When `:on-change` is `=` to last render's, React keeps the DOM listener as-is. For a complex row this skips a noticeable amount of substrate-internal work; for a simple checkbox-and-text it doesn't.

The right time to reach for this: the profiler says renders are still the hot spot *after* you've split props. The wrong time: every list-shaped view in the app. Most of the time, round 2 is the destination.

### What the journey teaches

Three refactors, in increasing order of how clever they are:

1. **Cheap and universal.** Thin props plus React keys. Apply this everywhere; it costs nothing and pays off the first time the list gets long.
2. **Cheap and conditional.** Compute in subs, not views. Apply this whenever the view is doing more than walking and emitting hiccup.
3. **Expensive and rare.** Stable callbacks. Apply this only when a profiler proves the renders themselves are the bottleneck and the children are complex enough to feel it.

Climb the ladder in order. Most apps stop on rung one.

## What's not here

A few things the chapter deliberately does not cover.

- **Bundle size.** Production bundle size is a Closure-compiler story, not a re-frame2 one. The DCE story for the trace surface and the `rf:` channel is in [Spec 009](../../spec/009-Instrumentation.md#production-elision-verification); how to keep *your* code small is a `:advanced` story.
- **Animation performance.** Reagent renders into React renders into the DOM. Animation jank is usually a DOM / CSS story, not a re-frame2 one. The chunked-work machine helps when you've blocked the main thread; for `transform` / `opacity` smoothness, the browser's `will-change` and `transform: translateZ(0)` tricks live one layer deeper.
- **Server-side rendering performance.** SSR is JVM-side; the `rf:` channel is browser-only; profiling SSR uses host tools. The shape of [chapter 11](11-server-side.md) is what governs server-side cost, not this chapter.
- **Sub-graph topology analysis.** The static sub-graph (`(rf/sub-topology)`, [chapter 15 §Reference](15-devtools-and-pair-tools.md#reference-the-static-sub-graph--sub-topology)) is the right lever when you want to find dead subs or visualise dependencies. The performance angle on it is "every redundant edge is a potential cache miss"; the tooling angle covers the rest.

## What we covered

- Four shapes account for almost every performance problem: big-prop re-renders, deep `=` checks, inline callbacks, expensive subs.
- The framework's answers compose: thin props, React keys, stable callbacks, compute-in-subs, chunked-work machines. Apply them in order of cost; most apps stop after the first two.
- The `rf:` Performance API surface is a prod-friendly timing channel gated on `re-frame.performance/enabled?`. Default off; flip the flag in your build to consume measures through Chrome DevTools or a `PerformanceObserver`.
- The worked checklist example shows the three rounds end-to-end: laggy → thin-props-and-keys → stable-callbacks-when-they-pay.

## Next

- [08 — State machines §Pattern-LongRunningWork](08-state-machines.md#pattern-longrunningwork--cpu-bound-work-as-a-chunked-machine) — the chunked-work machine in full.
- [15 — Tooling](15-devtools-and-pair-tools.md) — the trace bus, the epoch records, the source-coord story.
- [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md) — the Web Worker offload alternative for genuinely heavy CPU work.
- [Spec 009 §Performance instrumentation](../../spec/009-Instrumentation.md#performance-instrumentation) — the normative contract for the `rf:` surface (entry names, gating, bundle isolation).

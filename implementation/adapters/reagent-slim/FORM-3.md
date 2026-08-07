# Form-3 in `reagent-slim` — the 7-key cap with worked examples

> Bead rf2-pe4u. Companion to `DESIGN-RATIONALE.md` §4 and `IMPL-SPEC.md` §6.
> Pre-release. Audience: adopters writing Form-3 components against `day8/reagent-slim`.

`reagent2.core/create-class` accepts exactly seven lifecycle keys. This document
enumerates them, explains why the cap is what it is, gives migration recipes for
the keys it excludes, and walks through the canonical Form-3 use case
(wrapping an imperative JS library that needs a DOM element + lifecycle hooks).

---

## §1 The 7-key cap

`reagent2.core/create-class` accepts exactly these keys in its spec map:

| Key | One-sentence semantic |
|---|---|
| `:reagent-render` | The render function. Returns hiccup. Called on every render. |
| `:component-did-mount` | Fires once, after the first commit. Use to attach DOM-dependent state (refs read, JS libraries instantiated). |
| `:component-did-update` | Fires after every re-render commit (not the first). Receives `(this prev-props prev-state snapshot)`. The third arg is the value returned by `:get-snapshot-before-update` if set, else `nil`. |
| `:component-will-unmount` | Fires once, just before unmount. The canonical disposal seam — release timers, listeners, JS-library instances. |
| `:get-snapshot-before-update` | Fires just before commit, with `(this prev-props prev-state)`. Returns any value; that value is passed as the 3rd arg to `:component-did-update`. Use to capture pre-commit DOM measurements (e.g. scroll position) for restoration after the commit. |
| `:component-did-catch` | Error-boundary callback. Fires with `(this error info)` when a descendant throws during render. Logging-only — re-frame2 ships only the `componentDidCatch` half of React's error-boundary contract. Apps that want stateful fallback rendering pair this with a local `(reagent2.core/atom)` flipped from inside the callback. |
| `:display-name` | A string used by React DevTools and error messages. Compile-time only — zero runtime cost. |

**Any other key throws.** The throw fires at `create-class` call time (registration
time, not render time — fail fast) with the canonical discriminator
`:rf.error/id :rf.error/create-class-key-unsupported` (per Spec 009),
the offending key(s) in `:keys`, and the supported set in `:supported-keys`. The
`:reason` slot names the supported keys and points the user at the migration paths
in §3 below.

The cap is checked once per `create-class` call site. Components passing the
check incur zero per-render validation cost — the validation is a registration-time
concern only.

---

## §2 Why these seven keys

The cap is what four production Day8 codebases use, plus nothing else. Audits
of re-com, re-frame-10x, Dash8, and rf8 established this empirically in May
2026; the relevant results are summarized below so this document is
self-contained.

### What the audits found

| Codebase | `create-class` sites | Keys used |
|---|---|---|
| re-com | 10 | `:display-name`, `:component-did-mount`, `:component-did-update`, `:component-will-unmount`, `:reagent-render` (five keys) |
| re-frame-10x | 3 | The re-com five **plus** `:get-snapshot-before-update` (one site — the `code` component's scroll-restoration path in `panels/event/views.cljs:54`) |
| Dash8 | 8 | The re-com five **plus** `:component-did-catch` (two sites — `day8.shared.components.reagent-error-boundary` and the top-level boundary in `main.cljs:81`) |
| rf8 | 6 (across 5 files) | Same shape as Dash8 — five re-com keys **plus** `:component-did-catch` (two sites — same shared boundary file and `main.cljs:71`) |

Combined: **27 `create-class` sites across the four codebases use exactly seven
distinct lifecycle keys**. The cap is the union of those seven, plus zero.

### What the audits did NOT find

Across all four codebases combined, **zero** sites use:

- `:component-will-receive-props` / `UNSAFE_componentWillReceiveProps`
- `:component-will-mount` / `UNSAFE_componentWillMount`
- `:component-will-update` / `UNSAFE_componentWillUpdate`
- `:should-component-update`
- `:get-derived-state-from-props`
- `:get-initial-state`
- `:component-function` / legacy `:render`

Every legacy lifecycle React 16.3 deprecated is unused. The legacy "will-"
lifecycles were superseded for safety reasons (they fire before commit and
must be pure) and for concurrent-rendering reasons (React 18 / 19 may invoke
them multiple times per logical update). The four-codebase ecosystem migrated
off them before this cap was even contemplated — the cap simply doesn't ship
support for surfaces nobody uses.

### What this means for the cap

The cap is empirical, not aspirational. It's not "we are deciding what users
are allowed to do." It's "this is what users actually do." A future codebase
that needs a banned key files a bead and the cap extends — the discipline is
empirical, not ideological. See `DESIGN-RATIONALE.md` §4 for the broader framing.

For the four audited codebases: **zero changes**. Their existing `create-class`
calls work as-is under reagent-slim.

---

## §3 Migration from out-of-cap keys

If your codebase uses a key not in the cap, here are the migration recipes for
the common cases. Each recipe ends in either (a) a supported pattern inside
the cap or (b) a re-frame2 idiom that replaces the lifecycle hook entirely.

### `:component-will-receive-props` → derived state in `:component-did-update`

The deprecated `componentWillReceiveProps` fired *before* a re-render in response
to new props. The React-blessed replacement is to derive state from props in
render (pure) and capture any pre-commit measurement via
`:get-snapshot-before-update` for use in `:component-did-update`:

```clojure
;; Old (forbidden under the cap)
:component-will-receive-props
(fn [this new-argv]
  (when (props-changed? this new-argv)
    (do-side-effect!)))

;; New
:component-did-update
(fn [this prev-props _prev-state _snapshot]
  (let [[_ new-props] (reagent2.core/argv this)]
    (when (not= prev-props new-props)
      (do-side-effect!))))
```

If the side effect needs a pre-commit DOM measurement (e.g. scroll position),
capture it in `:get-snapshot-before-update` and consume it in
`:component-did-update`. See §4 for a worked example.

### `:component-will-mount` → `:component-did-mount`

`componentWillMount` fired before the first commit; its side effects ran against
the un-mounted DOM. React 16.3+ rendered it unsafe (multiple invocations under
concurrent rendering). The replacement is `:component-did-mount`, which runs
*after* the first commit — the DOM is attached, refs are populated, side effects
are safe:

```clojure
;; Old (forbidden under the cap)
:component-will-mount
(fn [_this]
  (init-some-resource!))

;; New
:component-did-mount
(fn [_this]
  (init-some-resource!))
```

If the resource truly must be ready *before* the first render (rare —
typically only constants and computed props), compute it at outer-fn time
in a Form-2 component:

```clojure
(defn my-view [initial-props]
  (let [precomputed (compute-once initial-props)]
    (fn render [props]
      [:div precomputed (str props)])))
```

### `:should-component-update` → the framework installs one for you

`shouldComponentUpdate` returned `false` to skip a re-render. reagent-slim does
**not** accept `:should-component-update` as a user cap key — and you almost
never need it, because the framework installs its own.

**The framework default.** Every reagent-slim class carries an internal,
framework-owned `shouldComponentUpdate` that compares the component's hiccup
argv with `=`. A child whose argv is `=` to its previous argv is **not**
re-rendered just because its parent re-rendered — the fine-grained-re-render
property, matching stock Reagent. This is a framework invariant, not a user
surface: you cannot override it through the cap (a `:should-component-update`
key is still rejected at registration time, per §1), and you do not need to —
it is always on.

**What still triggers a render.** The argv gate skips *only* equal-argv parent
propagation. A view still re-renders when:

- its argv **changes** — a parent passed a value that is `not=` the previous one;
- a **subscription it deref's is invalidated** — the reactive substrate targets
  that view directly (the update drains as a `forceUpdate`, which React
  specifies bypasses `shouldComponentUpdate`), so a view's *own* data changes
  always commit;
- its **local reactive state** (a Form-2 `reagent2.core/atom`) changes;
- an ancestor **React context** it consumes changes;
- something calls **force-update** on it.

So the accurate contract is two-sided: the reactive substrate gates a view's
*subscription-driven* re-renders to the moment its subscribed values change, and
the argv gate additionally spares it from its *parent's* unrelated re-renders.
It does **not** mean "a view only ever re-renders when a subscribed value
changes" — the causes above still apply. If a component re-renders more than you
expect, the usual diagnosis is "your sub is firing too often" or "the value you
pass down is `not=` every render" (an inline `(fn …)` closure — never `=` to
another — or a freshly-built collection whose contents differ), not "this
component needs a manual gate."

### `:get-initial-state` → outer-fn Form-2 closure, or `:initial-events`

`getInitialState` (a Reagent shim over React's pre-hooks pattern) returned the
initial value of `this`'s state atom. Two replacements:

```clojure
;; Form-2 closure — per-mount initial state, ergonomic for view-local needs
(defn counter [_initial]
  (let [count (reagent2.core/atom 0)]
    (fn render [_]
      [:button {:on-click #(swap! count inc)} @count])))

;; :initial-events on the surrounding frame — for app-state init
(rf/make-frame {:id :feature/counter :initial-events [[:counter/initialise]]})
```

The frame `:initial-events` path is preferred when the state belongs to app-db (the
event is named, registered, queryable, and visible in the trace stream). The
Form-2 closure path is appropriate when the state is genuinely view-local
(component instance lifetime, never read elsewhere).

### `UNSAFE_*` keys → drop entirely

The three `UNSAFE_` prefixed lifecycles (`UNSAFE_componentWillMount`,
`UNSAFE_componentWillReceiveProps`, `UNSAFE_componentWillUpdate`) are the
React-16.3 renamed forms of the legacy `:component-will-*` keys. The cap
rejects both forms — under React 19's concurrent-rendering strictness, any
remaining use will surface a regression in production. Drop the lifecycle and
migrate to the recipes above.

### `:get-derived-state-from-props` → render-time derivation

`getDerivedStateFromProps` is a React 16.3+ lifecycle for the narrow case where
state genuinely depends on props. The reagent-slim cap doesn't ship it because
none of the four audited codebases use it — the same intent is achieved by
deriving the value in render (pure, no state needed):

```clojure
;; Old (forbidden under the cap)
:get-derived-state-from-props
(fn [new-props _prev-state]
  {:derived (compute new-props)})

;; New — compute in render
:reagent-render
(fn [props]
  (let [derived (compute props)]
    [:div derived]))
```

If memoisation is needed, wrap `compute` in `memoize`, or move the derivation
into a `reg-sub` and deref that in render. `reagent2.core/track` is **not**
shipped — a cached derived value is what a sub already is.

---

## §4 Canonical use case — imperative DOM library integration

This is THE canonical Form-3 pattern: wrapping an imperative JavaScript
library that needs a DOM element, lifecycle integration, and explicit
cleanup. The four audited codebases use Form-3 for exactly this — Vega-Embed
(Dash8, rf8), SpreadJS designer (Dash8), v-table (re-com), popover positioning
(re-com), nested-grid (re-com), Reagent error boundaries (Dash8, rf8, the
`reagent-error-boundary` shared component).

### Worked example — Google Maps wrapper

```clojure
(ns my-app.maps
  (:require [reagent2.core :as r]))

(defn google-map [_initial-props]
  (let [el-ref       (atom nil)
        map-instance (atom nil)]
    (r/create-class
      {:display-name "google-map"

       :component-did-mount
       (fn [this]
         (let [[_ props] (r/argv this)
               el        @el-ref]
           (reset! map-instance
                   (js/google.maps.Map.
                     el
                     #js {:center #js {:lat (-> props :center :lat)
                                       :lng (-> props :center :lng)}
                          :zoom   (:zoom props)}))))

       :component-did-update
       (fn [this _prev-props _prev-state _snapshot]
         (let [[_ new-props] (r/argv this)]
           (when-let [m @map-instance]
             (.setCenter m #js {:lat (-> new-props :center :lat)
                                :lng (-> new-props :center :lng)})
             (.setZoom m (:zoom new-props)))))

       :component-will-unmount
       (fn [_this]
         (some-> @map-instance .dispose)
         (reset! map-instance nil)
         (reset! el-ref nil))

       :reagent-render
       (fn [_props]
         [:div {:ref   (fn [el] (reset! el-ref el))
                :style {:width "100%" :height "400px"}}])})))
```

### Five moving parts

1. **Atom closures (`el-ref`, `map-instance`)** — per-component-instance state,
   captured by all four lifecycle callbacks via lexical closure. Each mount of
   `[google-map ...]` gets its own pair of atoms. Don't use `defonce` or top-level
   `def` here — those leak across mounts.

2. **`:reagent-render`** returns just the container `<div>` with a `:ref`
   callback. The hiccup is intentionally minimal — the JS library will fill
   the div with whatever it wants. The `:ref` callback fires after mount with
   the DOM element, before `:component-did-mount` runs.

3. **`:component-did-mount`** instantiates the JS library against the now-attached
   DOM element. `r/argv` returns `[component-fn & user-args]` — destructure
   to get the user's props. The JS instance is stashed in the closure atom so
   later lifecycle phases can manipulate it.

4. **`:component-did-update`** reacts to prop changes imperatively. The lifecycle
   callback receives `(this prev-props prev-state snapshot)`; we read the *new*
   args via `r/argv` and tell the JS library to update. (The reagent-slim
   contract: `:component-did-update` is the only cap path for "props changed,
   update the imperative thing." Compare with how Form-1 would react to data
   changes — it'd re-render on subscription change. Form-3 is the right tool
   precisely because the JS library does not re-render on data change; it
   mutates in place.)

5. **`:component-will-unmount`** disposes the JS instance. Critical for memory
   leaks — without this, every navigation away from a page containing a map
   leaks a Google Maps instance, its tile cache, its event listeners, and its
   internal request queue. Set the closure atoms back to `nil` so the JS
   instance becomes garbage-collectable.

### Why this shape applies to every imperative JS library

The same five-part shape applies to **SpreadJS, ag-grid, CodeMirror, Mapbox,
AmCharts, Vega-Embed** — anything imperative that needs a DOM element and a
lifecycle. The differences are surface details (which library method to call,
what props to pass), not structural. The `create-class` cap was sized
specifically to support this shape.

Three variations worth knowing:

- **Async init.** If `js/google.maps.Map.` doesn't exist yet at mount time
  (deferred-loaded API), wrap the `:component-did-mount` body in an
  async-load callback. The atoms are still the right state container — the
  callback just runs later.
- **Render in response to subs — route it through `reg-view*` + a captured
  frame.** The architecture is sound: let the inner `:reagent-render` deref a
  subscription so the view re-renders when the data changes, which fires
  `:component-did-update`, which lets the imperative library see the new data.
  But a **bare `@(subscribe [...])` in a plain-`defn` Form-3 throws
  `:rf.error/no-frame-context` at first render** under any `frame-provider`
  (EP-0002 — frame identity is carried, not found). The 7-key cap (§1) forbids
  `:context-type`, and a plain-`defn` `create-class` carries none, so the class
  can't read its provider's frame from React context and the ambient
  `subscribe` resolves no scope. Register the class through
  `re-frame.core/reg-view*` and capture the frame **once in the outer callable**
  (`(rf/capture-frame)` — the one live-resolver site in a Form-3); deref the
  captured `:subscribe` inside `:reagent-render`, never in a lifecycle callback
  (reactive reads have no owner there, and the scope has already unwound). The
  one disciplined exception is a rare imperative widget re-fed as a sub's value
  changes from a hook rather than React's render commit. It captures the frame's
  `:subscribe` in the outer callable, acquires the reaction in
  `:component-did-mount`, and — the step that is easy to miss — **owns** it there
  with a per-mount reactive owner, because a subscription is not live merely for
  having been handed to you. On this adapter a subscription *is* a bare
  `reagent2.ratom/Reaction`, built deliberately without `:auto-run`, and a
  Reaction learns its sources only through `deref-capture`; a deref taken in a
  lifecycle hook runs the body raw and leaves `watching` nil, so the node ends up
  in no watcher set. An `add-watch` on it is therefore a **trap** rather than a
  shortcut — the watch is registered against a node that can never fire, so the
  widget is fed once at mount and is deaf for the rest of its life, silently and
  with no error. The owner is what supplies the missing capture, and reagent-slim
  spells it with its own reactive primitives:
  `(reagent2.ratom/activate! (reagent2.ratom/make-reaction (fn [] (feed! @!widget @reaction))))`.
  `activate!` runs that body once through `deref-capture`, so the first run is
  both the seed and the subscription to the sources, and every later change
  re-runs it on the ordinary batched drain (`reagent2.ratom/flush!`, which the
  commit boundary performs) rather than inline inside the `app-db` write. Write
  the widget as though the feed lands on the following commit; the adapter's
  `:flush-render!` happens to perform that drain itself, so under `dispatch-sync`
  the widget is already current when the call returns, but do not build on it.
  Hold the owner per mount and tear down in that order at unmount —
  `reagent2.ratom/dispose!` the owner **first**, then balance the acquire with
  frame-first `(rf/unsubscribe frame query-v)` — so the owner is gone before the
  cache slot is released and no feed can run against a destroyed widget. Equal
  `(frame, query-v)` subscriptions share **one** cached reaction, but each mount
  holds its own owner, so two instances are independent by construction: there is
  nothing to key and no sibling watch to clobber. That is the *only* lifecycle
  use of the captured `:subscribe`; see
  [`guided-handlers-state.md`](../../../skills/re-frame-migration/references/guided-handlers-state.md)
  §M-11 for the fully worked form — take its **structure**, not its ops. M-11 is
  written for the stock-Reagent adapter and reaches for `reagent.core/track!`,
  which `reagent2.core` deliberately does not ship (see
  [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)); `activate!` + `make-reaction` +
  `dispose!` is the reagent-slim spelling of the same ownership. It does not
  weaken the default: ordinary reactive reads still belong in `:reagent-render`,
  not a hook.

  **Capture-once is a locked handle — safe only when the mount's frame is
  invariant.** `(rf/capture-frame)` locks to the **one** frame that is live when
  the outer callable runs (once per mount) and never re-resolves — every op it
  hands you closes over that frame permanently. So capture-once is correct only
  while the instance stays under that frame for its whole life. It goes **stale**
  if a *surviving* instance is **retargeted from provider A to provider B** — the
  subtree re-parented under a different `frame-provider`, or a provider whose
  `:frame` prop changes without unmounting the child. React keeps the instance
  mounted, so the outer callable does **not** re-run, and the locked handle keeps
  sending render/lifecycle actions to the stale **A**, never **B**. Two
  frame-safe remedies (no mutable-capture machinery, which is not warranted here):
  **force a remount** with a frame-derived React `key`
  (`^{:key (str frame-id)} [chart series]`) so a frame change remounts the
  component and the capture re-locks to **B**; or **use the registered `reg-view`
  child** (route 1), which reads its frame from React context on **every** render
  and so follows A→B with no remount and no re-capture. See
  `guided-handlers-state.md` §M-11 Form-3 routes 1 & 2 for the full recipe.
- **DOM measurement during commit.** If you need to capture a measurement
  *before* React commits a re-render (typical for scroll preservation),
  use `:get-snapshot-before-update`. See §5 below.

### Worked example — Vega-Embed (closely mirrors Dash8 `graphic.cljs`)

```clojure
(defn vega-chart [_initial-spec]
  (let [el-ref        (atom nil)
        vega-instance (atom nil)]
    (r/create-class
      {:display-name "vega-chart"

       :component-did-mount
       (fn [this]
         (let [[_ spec] (r/argv this)]
           (.then (js/vegaEmbed @el-ref (clj->js spec))
                  (fn [result]
                    (reset! vega-instance (.-view result))))))

       :component-did-update
       (fn [this _prev-props _prev-state _snapshot]
         (let [[_ new-spec] (r/argv this)]
           (.then (js/vegaEmbed @el-ref (clj->js new-spec))
                  (fn [result]
                    (some-> @vega-instance .finalize)
                    (reset! vega-instance (.-view result))))))

       :component-will-unmount
       (fn [_this]
         (some-> @vega-instance .finalize)
         (reset! vega-instance nil)
         (reset! el-ref nil))

       :reagent-render
       (fn [_spec]
         [:div {:ref (fn [el] (reset! el-ref el))}])})))
```

Same five parts. The library-specific bits: `vegaEmbed` returns a Promise
resolving to an object containing a `view` (the imperative handle);
`view.finalize()` is the disposal seam.

### Worked example — error boundary (closely mirrors Dash8/rf8 `reagent-error-boundary`)

```clojure
(defn error-boundary [_]
  (let [error (r/atom nil)]
    (r/create-class
      {:display-name "error-boundary"

       :component-did-catch
       (fn [_this err info]
         (js/console.error "Caught render error:" err info)
         (reset! error err))

       :reagent-render
       (fn [child]
         (if @error
           [:div.fallback "Something went wrong."]
           child))})))
```

`:component-did-catch` is the one cap key for which there is no Form-1
substitute. React's error-boundary contract requires a class component;
function components cannot implement it. The reagent-slim cap permits
`:component-did-catch` precisely because of this — error boundaries are a
genuine load-bearing use case that no alternative covers (see
`DESIGN-RATIONALE.md` §4 and rf2-kfpf §6).

reagent-slim ships logging-only error boundaries (the React 19 contract
has two halves — `getDerivedStateFromError` for state, `componentDidCatch`
for logging — and the four audited apps use only the second). Apps that
want stateful fallback rendering pair `:component-did-catch` with a local
`r/atom` as shown above.

---

## §5 `:get-snapshot-before-update` — scroll restoration

The one Form-3 pattern that needs `:get-snapshot-before-update` is preserving
a DOM measurement across a commit. The canonical case is **scroll position
restoration**: a re-render is about to replace the children of a scrollable
element, and you want the scrollTop to be the same after the new children
are committed.

```clojure
(defn scroll-preserving-log []
  (let [container-ref (atom nil)]
    (r/create-class
      {:display-name "scroll-preserving-log"

       :get-snapshot-before-update
       (fn [_this _prev-props _prev-state]
         ;; Runs against the PREVIOUS DOM, just before commit.
         ;; Capture whatever measurement we need to restore.
         (when-let [el @container-ref]
           {:scroll-top (.-scrollTop el)}))

       :component-did-update
       (fn [_this _prev-props _prev-state snapshot]
         ;; Runs AFTER commit, with the snapshot as the 4th arg.
         (when-let [el @container-ref]
           (when-let [target (:scroll-top snapshot)]
             (set! (.-scrollTop el) target))))

       :reagent-render
       (fn [log-entries]
         [:div.log {:ref (fn [el] (reset! container-ref el))}
          (for [entry log-entries]
            ^{:key (:id entry)}
            [:div (:text entry)])])})))
```

This mirrors the re-frame-10x `code` component's scroll-restoration path
(`panels/event/views.cljs:54-64` per rf2-cgcv §3). Without
`:get-snapshot-before-update`, capturing the pre-commit scroll position
race-condition-free is not possible — React 16.3 added this lifecycle
specifically to close that gap.

---

## §6 When NOT to use Form-3

Form-3 is the right tool for **imperative DOM library integration**. For
everything else, prefer the re-frame2 idioms — they are simpler, named,
and visible in the trace stream.

### Use frame `:initial-events` / `:on-destroy`, not Form-3, for app-state side effects

If a "mount-time" effect updates app-db (load data, register a listener that
dispatches events, etc.), put it on the surrounding frame, not in a Form-3
`:component-did-mount`:

```clojure
;; Wrong — and it doesn't merely read worse: a bare (rf/dispatch …) in a
;; plain-defn Form-3's :component-did-mount THROWS :rf.error/no-frame-context
;; under any frame-provider (EP-0002 — the class carries no frame context).
(r/create-class
  {:component-did-mount (fn [_] (rf/dispatch [:counter/initialise]))
   :reagent-render      ...})

;; Right — frame-level init event
(rf/make-frame {:id :counter-frame :initial-events [[:counter/initialise]]})
```

The frame event is named, registered, queryable, traceable in re-frame-10x,
and runs deterministically once per frame mount — and it is the pattern to
reach for regardless. The Form-3 version isn't just a worse idiom: under
EP-0002 that bare `(rf/dispatch …)` **throws `:rf.error/no-frame-context`**,
because a plain-`defn` `create-class` carries no `:contextType` (the 7-key cap
forbids `:context-type`) and so can't resolve its provider's frame. A Form-3
that genuinely must dispatch from a lifecycle hook has to go through
`re-frame.core/reg-view*`, capturing the frame's `:dispatch` **once in the
outer callable** and closing that handle over the hook — never a fresh
`(rf/capture-frame)` inside the hook, which fires after the scope unwinds and
re-throws. See `guided-handlers-state.md` §M-11 Form-3 route 2. For plain
mount-time app-state init, prefer `:initial-events`.

### Use `:ref` callbacks, not Form-3, for DOM access

If all you need is a DOM element reference (for focus management, measurement,
imperative method call on a single event), a `:ref` callback on a Form-1
component is enough:

```clojure
(rf/reg-view focusable-input []
  (let [el-ref (atom nil)]
    (fn render [_]
      [:input {:ref (fn [el] (reset! el-ref el))
               :on-blur #(some-> @el-ref (.classList.add "blurred"))}])))
```

Form-3 is for when DOM access has to coordinate with React's commit lifecycle
(mount/update/unmount). For "I just need a handle to the element," a `:ref`
in Form-1 is enough.

### Use subscriptions, not Form-3, for prop-change reactions

If a Form-3 `:component-did-update` exists only to read changing data and
re-render, the data should be a subscription and the component should be
Form-1:

```clojure
;; Wrong — Form-3 to react to data
(r/create-class
  {:component-did-update
   (fn [this _ _ _]
     (let [[_ data] (r/argv this)]
       (do-something data)))
   :reagent-render
   (fn [data] [:div data])})

;; Right — subscribe inside Form-1
(rf/reg-view data-display []
  [:div @(subscribe [:current-data])])
```

The wrong version re-renders the component, then runs `:component-did-update`,
then does the side effect. The right version skips the lifecycle entirely —
the subscription triggers the re-render, and the side effect lives in an
event handler triggered by whatever changes the data, not in the view at all.

### Summary

| Need | Tool |
|---|---|
| Wrap an imperative JS library | **Form-3** with the 5-part shape from §4 |
| Mount-time app-state side effect | Frame `:initial-events` |
| DOM element handle for an event handler | `:ref` callback on Form-1 |
| React to data changes | Subscription inside Form-1 |
| Error boundary | **`ui/error-boundary`** when error-only reporting fits; otherwise **Form-3** with `:component-did-catch` (see the MIG-17 table below) |
| Pre-commit DOM measurement | **Form-3** with `:get-snapshot-before-update` |

Form-3 is the right tool for imperative DOM library integration and for the
pre-commit snapshot protocol (`:get-snapshot-before-update`), which has no
native door. Error boundaries do have one: route them to `ui/error-boundary`
when its contract fits, and keep `:component-did-catch` only when it does not
(the MIG-17 table draws that line). For everything else, prefer the re-frame2
idioms.

### The lifecycle decision table — every Form-3 role to its native target (MIG-17)

reagent-slim keeps Form-3, so on the slim adapter these components run as-is —
the table above is for teams staying on the class shape. But when a team
migrates a Reagent Form-3 *off* the class shape to **native re-frame.ui**
(`ui/defview`), the generic "host work becomes an `effect`" advice does not
cover every lifecycle role safely: an update hook needs explicit dependency
semantics, the snapshot protocol is a paired pre-/post-commit dance with no
passive-effect translation, and an error boundary has its own shipped form.
This is the MIG-17 decision (skill: `reagent-migration`,
[`catalog-judgment.md`](../../../skills/reagent-migration/references/catalog-judgment.md)
§MIG-17). It routes each role, and holds honestly the two that have **no**
native equivalent and stay on reagent-slim Form-3 — which is exactly why the
7-key cap keeps their keys.

| Reagent Form-3 lifecycle | Phase & frequency | Native re-frame.ui target (MIG-17) |
|---|---|---|
| `:reagent-render` | every render | The `defview` render body — extract it as the view, and the other migration rules apply to that body. |
| `:component-did-mount` | after the first commit, before paint (dev StrictMode may replay mount → unmount → mount) | **Deferrable host work** (wire a listener, focus a node, attach a self-sizing chart) → `(effect :connect …)`. **Measure or mutate the DOM before paint** (read geometry to place a popover, size a viewport) → `ui/ref` + `re-frame.ui.react/use-layout-effect` — the measure-before-paint door; a passive `effect` fires *after* paint and would measure a frame late and flicker. **Domain work** ("mark viewed", "load on mount") → a route or domain **event** through the dataflow — there is deliberately no `:on-mount` primitive. |
| `:component-did-update` | after every commit but the first, per update | Route by **intent** — the native effect does not skip the first commit the way this hook does. **Re-feed an imperative library on a prop change** (finalize the old view, embed the new) → one dependency-keyed `(effect [deps…] …)` with a matching cleanup, folding the mount + update + unmount work together: it runs after commit on the **initial** mount too, then whenever a dep changes (compared by `rf=` — keep deps narrow), which is what a library wrapper wants anyway. **Measure or mutate the DOM before paint on an update** (re-place a popover after its anchor moves) → the dependency-keyed `use-layout-effect`, not the passive `effect`. If the body genuinely **must** skip the first commit, guard it with a first-render ref or keep Form-3 — don't fake it by dropping the mount case. If the hook exists **only** to read changed data and re-render, the data is a **subscription** and the view is Form-1 (§6.3) — the lifecycle disappears. |
| `:get-snapshot-before-update` + `:component-did-update` (paired) | measure the previous DOM pre-commit → restore post-commit | **No native passive-effect or hook equivalent — stays on reagent-slim Form-3.** `use-layout-effect` runs *after* React has mutated the DOM, so it cannot read the pre-mutation geometry; the pre-commit half of the protocol has no native door. This paired protocol (scroll restoration, §5) is exactly why the cap keeps `:get-snapshot-before-update`. |
| `:component-will-unmount` | just before unmount (dev StrictMode replays connect → disconnect → connect) | **Host teardown** (dispose the chart, remove the exact listener you added) → the `(effect :connect …)` **cleanup** — the effect body's trailing `(fn [] …)`, which must be **symmetric and replay-safe**: it releases the exact resource its matching setup acquired, so a balanced pair (add-then-remove a listener, increment-then-decrement a counter, push-then-pop) stays correct as React replays setup → cleanup → setup at each disconnect. What corrupts state is an *unpaired* teardown, not a balanced one — blanket idempotency is not required. **Domain work** (release an owned resource, mark a draft abandoned) **re-homes OUT of the view**: the causal *end* events that end the resource's life release it. There is **no dispatch-at-unmount** — a committed dispatcher fails loud from a disconnecting view (the **leaked-listener law**, `:rf.error/dispatch-disconnected`), and an `effect` cleanup is not a place to smuggle it in. |
| `:component-did-catch` | on a descendant render/lifecycle throw | The shipped **`ui/error-boundary`** `{:fallback :reset-key :on-error}` when **error-only reporting** is enough: `:fallback` renders with `:error` (the stateful fallback), `:on-error` dispatches a domain event **after** the failing commit, and changing `:reset-key` (compared `rf=`) clears the caught error (retry). The one thing it drops is React's second callback arg — the `info`/`componentStack`; reagent-slim Form-3's `:component-did-catch (this error info)` still hands you that. So if you need the component stack (grouped crash logging, error fingerprinting), or the native contract otherwise does not fit, the view **stays on reagent-slim Form-3** (`:component-did-catch` + a local `r/atom`, §4) or is redesigned. React catches only render/lifecycle throws below the boundary — not event-handler or async errors, which keep their typed paths. |

Every row preserves the phase, frequency, and dependency semantics of the hook
it replaces. The two "stays on reagent-slim Form-3" rows are the honest holds:
the snapshot protocol and (when `ui/error-boundary` does not fit) the error
boundary have no faithful native passive-effect translation, so the class shape
is the answer, not a lossy rewrite.

---

## §7 Cross-references

- **[IMPL-SPEC](IMPL-SPEC.md)** §6 — Form-3 implementation: validation throw
  shape, React-class wrapper, and lifecycle key → method mapping.
- **[DESIGN-RATIONALE](DESIGN-RATIONALE.md)** §4 — the seven-key Form-3 cap: design
  rationale and re-frame2-fit framing.
- **[Views spec](../../../spec/004-Views.md)** §"Form-3 (class — out of scope for the
  macro)" — Form-3 is intentionally not supported by the `reg-view` macro; use
  `re-frame.core/reg-view*` (the plain-fn surface) for Form-3 components.
- **[Migration recipe](../../../skills/re-frame-migration/references/guided-handlers-state.md)**
  §M-11 Form-3 routes 1 & 2 — the full recipe for a **frame-scoped** Form-3 (one
  that subscribes or dispatches app state): extract the subscribing part into a
  `reg-view` child, or register the outer fn through `reg-view*` and capture the
  frame once in the outer callable. A bare `@(subscribe)` / `(rf/dispatch)` in a
  plain-`defn` Form-3 under a `frame-provider` throws `:rf.error/no-frame-context`
  (EP-0002). The worked examples in §4–§5 above are frame-independent and need
  none of this.

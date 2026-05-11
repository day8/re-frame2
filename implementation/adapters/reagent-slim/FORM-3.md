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
time, not render time — fail fast) with `:type :rf.error/create-class-key-unsupported`,
the offending key(s) in `:keys`, and the supported set in `:supported-keys`. The
error message names the supported keys and points the user at the migration paths
in §3 below.

The cap is checked once per `create-class` call site. Components passing the
check incur zero per-render validation cost — the validation is a registration-time
concern only.

---

## §2 Why these seven keys

The cap is what the four production Day8 codebases use, plus nothing else. Two
audits established this empirically (2026-05-10):

- **`findings/recom-react19-readiness-audit.md`** (bead rf2-cgcv) — surveyed
  re-com (master) and re-frame-10x (master).
- **`findings/dash8-rf8-react19-readiness-audit.md`** (bead rf2-kfpf) — surveyed
  Dash8 and rf8 (Day8 internal apps).

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

### `:should-component-update` → React.memo at the user's call site, or accept the re-render

`shouldComponentUpdate` returned `false` to skip a re-render. The React-blessed
replacement is `React.memo` (for function components) or `React.PureComponent`
(for class components). reagent-slim ships neither as a Form-3 cap key, but
users can apply `React.memo` at the call site by reactifying their component:

```clojure
(def memoised-thing
  (js/React.memo (reagent2.core/reactify-component thing)))
```

In re-frame2's idiom, the more common answer is **accept the re-render**:
re-frame2's reactive substrate already gates render at the subscription level
(views only re-render when their subscribed values change), so most
`:should-component-update` use cases are subsumed by sub graph design. If a
component is re-rendering too often, the diagnosis is usually "your sub is
firing too often," not "this component needs a manual gate."

### `:get-initial-state` → outer-fn Form-2 closure, or `:on-create` event

`getInitialState` (a Reagent shim over React's pre-hooks pattern) returned the
initial value of `this`'s state atom. Two replacements:

```clojure
;; Form-2 closure — per-mount initial state, ergonomic for view-local needs
(defn counter [_initial]
  (let [count (reagent2.core/atom 0)]
    (fn render [_]
      [:button {:on-click #(swap! count inc)} @count])))

;; :on-create on the surrounding frame — for app-state init
(rf/reg-frame :feature/counter {:on-create [:counter/initialise]})
```

The frame `:on-create` path is preferred when the state belongs to app-db (the
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

If memoisation is needed, wrap `compute` in `memoize` or a
`(reagent2.core/track compute props)` track.

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
- **Render in response to subs.** Combine Form-3 with subscription: the inner
  `:reagent-render` can `@(subscribe [...])` to trigger re-renders, which
  causes `:component-did-update` to fire, which lets the imperative library
  see new data. Don't `subscribe` in the lifecycle callbacks themselves
  (reactive context is undefined there) — `subscribe` only in the render.
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

### Use frame `:on-create` / `:on-destroy`, not Form-3, for app-state side effects

If a "mount-time" effect updates app-db (load data, register a listener that
dispatches events, etc.), put it on the surrounding frame, not in a Form-3
`:component-did-mount`:

```clojure
;; Wrong — Form-3 for app-state init
(r/create-class
  {:component-did-mount (fn [_] (rf/dispatch [:counter/initialise]))
   :reagent-render      ...})

;; Right — frame-level init event
(rf/reg-frame :counter-frame {:on-create [:counter/initialise]})
```

The frame event is named, registered, queryable, traceable in re-frame-10x,
and runs deterministically once per frame mount. The Form-3 version hides the
dispatch behind a lifecycle callback and couples the side effect to a
particular view.

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
| Mount-time app-state side effect | Frame `:on-create` event |
| DOM element handle for an event handler | `:ref` callback on Form-1 |
| React to data changes | Subscription inside Form-1 |
| Error boundary | **Form-3** with `:component-did-catch` (no substitute exists) |
| Pre-commit DOM measurement | **Form-3** with `:get-snapshot-before-update` |

Form-3 is the right tool for two narrow cases: imperative DOM library
integration and the two React-class-only lifecycles (`:component-did-catch`,
`:get-snapshot-before-update`). For everything else, prefer the re-frame2
idioms.

---

## §7 Cross-references

- **Stage 1 findings doc** (rf2-ui6g) — `findings/re-frame2-reagent-stage1-api-surface.md`,
  §6 DECISION-3 (the cap decision rationale) and §1.10 (Form-1/2/3 conventions).
- **re-com + re-frame-10x audit** (rf2-cgcv) —
  `findings/recom-react19-readiness-audit.md`, §4 (six-key cap recommendation
  including `:get-snapshot-before-update`) and §3 (10x per-site key sets).
- **Dash8 + rf8 audit** (rf2-kfpf) —
  `findings/dash8-rf8-react19-readiness-audit.md`, §6 (seven-key cap recommendation
  including `:component-did-catch`) and §4 (per-site key sets).
- **IMPL-SPEC** — `IMPL-SPEC.md` §6 (Form-3 implementation: validation throw
  shape, React-class wrapper, lifecycle key → method mapping).
- **DESIGN-RATIONALE** — `DESIGN-RATIONALE.md` §4 (the 7-key Form-3 cap — design
  rationale and re-frame2-fit framing).
- **Views spec** — `spec/004-Views.md` §"Form-3 (class — out of scope for the
  macro)" — Form-3 is intentionally not supported by the `reg-view` macro; use
  `re-frame.core/reg-view*` (the plain-fn surface) for Form-3 components.

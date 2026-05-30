(ns re-frame.story.sub-overrides
  "View-state subscription overrides — the render-path resolver
  (rf2-5x1wt.13).

  Per `tools/story/spec/017-Testing-Story.md` §View-state subscription
  overrides, a variant whose goal is rendering / design exploration MAY
  author `:sub-overrides` — a map of exact subscription query vectors to
  the data values the renderer should surface for them. This namespace is
  the RENDER-PATH read side: given the plan's resolved override map and a
  query vector a view is subscribing to, return either the override value
  or a miss sentinel.

  ## What this layer is — and is NOT

  `:sub-overrides` is the THIRD, deliberately lower-fidelity rung of the
  fidelity ladder (real setup events → schema-checked app-db seed →
  subscription overrides). It exists so a designer can pin a view into an
  `:error` / `:loading` / `:empty` state without authoring the full
  event sequence that would naturally produce it.

  The override feeds the RENDER PATH ONLY. It never writes into the
  variant frame's app-db and never reaches `re-frame.subs/compute-sub`.
  This is the load-bearing boundary that keeps `:rf.assert/sub-equals`
  honest: that assertion evaluates a sub through `compute-sub` against the
  frame's app-db snapshot (see `re-frame.story.assertions/
  evaluate-sub-equals`), which this namespace deliberately does not touch.
  A `:sub-overrides` value therefore does NOT satisfy a subscription
  assertion — subscription correctness is proven by real setup events, a
  schema-checked app-db seed, or `compute-sub`, not by an override.

  ## Resolution — exact query-vector match

  Override keys are EXACT subscription query vectors `[sub-id & args]`
  AFTER `[:arg key]` substitution (the plan compiler resolves the
  placeholders; this layer matches resolved vectors). A view subscribing
  to `[:login/state]` is overridden by an override keyed `[:login/state]`;
  a view subscribing to `[:item 7]` is overridden only by `[:item 7]`,
  NOT by `[:item]` or `[:item 8]`. Match is plain `=` on the whole vector
  — no prefix / sub-id-only fuzzing, so an override can never leak into a
  sibling query the author did not name.

  ## Purity / elision

  The pure resolver fns (`resolve` / `read` / `overridden?` / `miss?`)
  are data → data, so they are JVM-runnable and the test suite needs no
  host. The CLJS-only render-path carriage (the override-context
  Provider + the `:subs/resolve-sub-override` hook publication below)
  reads the plan's `[:world :render :sub-overrides]` map; production
  bundles elide the whole runtime, and the core consult is gated on
  `interop/debug-enabled?`, so nothing surfaces an override in
  production.

  ## STATUS — the subscribe-seam is WIRED via React context (rf2-7pgiz)

  An exact-query-vector `:sub-overrides` value now SURFACES at render. A
  normally-authored view's `@(rf/subscribe [:q])` is intercepted in
  `re-frame.subs/subscribe` (CLJS, dev-only) via the
  `:subs/resolve-sub-override` late-bind hook this namespace publishes;
  on a HIT `subscribe` returns a constant reaction holding the pinned
  value, so the view paints the overridden state.

  Two design facts shaped the seam:

    1. The carriage is a React CONTEXT, not the dynamic var. A
       `binding`-bound dynamic var does NOT survive into a view's
       DEFERRED React render — the canvas binds it during the
       override-scope component's own render, but the view's
       `@(rf/subscribe)` runs later, in its own reaction, with the
       binding already unwound (empirically confirmed under
       react-dom/server — a parent-render binding reads `:unbound` in a
       child component's render). React mutates a context's
       `_currentValue` as Provider boundaries are entered/exited during
       render, so a descendant read sees the closest enclosing
       Provider's value — exactly how `re-frame.adapter.context`
       propagates the frame-id. The render path wraps the variant view
       in `re-frame.adapter.sub-override-context`'s Provider; the
       `*overrides*` dynamic var is retained ONLY as a JVM / pure-test
       convenience (the resolver tests bind it directly).
    2. The consult is SUBSTITUTIVE but dev-only + honesty-bounded. It
       sits inside `subscribe`'s `interop/debug-enabled?` gate (DCEs
       under `:advanced` + `goog.DEBUG=false`), and the override feeds
       ONLY the constant reaction the view derefs — never app-db, never
       `compute-sub`. So `:rf.assert/sub-equals` (which evaluates a sub
       through `compute-sub`) STILL cannot be satisfied by an override.

  Mike RULED (A) on rf2-7pgiz (2026-05-31): build the core
  subscribe-override seam — React-context carriage + the debug-gated
  `:subs/resolve-sub-override` hook — and schema-validate a HIT against
  the sub's declared `:schema` (the FOLD-IN; core-side, mirroring Spec
  010 §`:sub-return`)."
  (:refer-clojure :exclude [resolve read])
  #?(:cljs (:require [re-frame.adapter.sub-override-context :as ovr-ctx]
                     [re-frame.late-bind :as late-bind])))

;; ============================================================================
;; Render-path override binding
;; ============================================================================
;;
;; The renderer binds the active variant's resolved override map for the
;; dynamic extent of its render so a `read`-through subscribed value can
;; consult it. Unbound (the default, and always in production) every
;; lookup misses and the view reads its real subscription.

(def ^:dynamic *overrides*
  "The active variant's resolved `:sub-overrides` map for the dynamic
  extent of a Story render — `{query-vector value}`, exact query vectors
  after `[:arg key]` substitution. Unbound (`nil`) outside a Story render
  and in production; every lookup then misses."
  nil)

(def ^:private miss
  "Sentinel returned by `resolve` when a query vector has no override. A
  distinct sentinel (not `nil`) so an override whose VALUE is `nil` is
  still honoured as a hit."
  ::miss)

(defn miss?
  "True iff `x` is the no-override sentinel `resolve` returns on a miss."
  [x]
  (= miss x))

(defn resolve
  "Return the override value for `query-v` from `overrides`, or the
  `miss` sentinel when no exact-match override is registered.

  Match is `=` on the whole query vector — `:sub-overrides` keys are
  exact `[sub-id & args]` vectors after `[:arg key]` substitution, so an
  override for `[:item 7]` matches ONLY a subscription to `[:item 7]`.

  `nil` / empty `overrides` always misses. An override whose value is
  `nil` is a HIT (the sentinel is distinct from `nil`), so a view can be
  pinned to a nil subscription value."
  ([query-v] (resolve *overrides* query-v))
  ([overrides query-v]
   (if (and (map? overrides) (contains? overrides query-v))
     (get overrides query-v)
     miss)))

(defn overridden?
  "True iff `query-v` has an exact-match override in `overrides` (or the
  bound `*overrides*` in the 1-arity form). Distinct from a `nil`-valued
  override, which is a genuine hit."
  ([query-v] (overridden? *overrides* query-v))
  ([overrides query-v]
   (and (map? overrides) (contains? overrides query-v))))

(defn read
  "Render-path read for a subscribed `query-v` against the dynamic-var
  `*overrides*`: return the override value when one is registered for the
  active extent, otherwise call `real-read` (a 0-arg thunk that performs
  the genuine subscription) and return its value.

  This is the resolve-or-fall-through helper for callers that already
  hold an explicit thunk and a dynamic-var binding (JVM / pure tests).
  The LIVE render path does NOT route through `read` — it routes through
  the React-context carriage + the `:subs/resolve-sub-override` core hook
  (see the ns docstring §STATUS and `resolve-sub-override-hit` below),
  because the dynamic var does not survive into a view's deferred render.

  `real-read` is invoked ONLY on a miss, so subscribing to a non-
  overridden query is exactly as it would be without overrides — no extra
  work, no behavioural change."
  [query-v real-read]
  (let [v (resolve *overrides* query-v)]
    (if (miss? v) (real-read) v)))

(defn with-overrides*
  "Run `thunk` with `*overrides*` bound to `overrides` (a
  `{query-vector value}` map, typically the plan's
  `[:world :render :sub-overrides]`). Returns `thunk`'s value. The
  function form behind the `with-overrides` macro — callers that already
  have a thunk (or are on a host where the macro is awkward) use this
  directly."
  [overrides thunk]
  (binding [*overrides* overrides]
    (thunk)))

#?(:clj
   (defmacro with-overrides
     "Evaluate `body` with `*overrides*` bound to `overrides` for its
     dynamic extent. Sugar over `with-overrides*`. The Story render path
     wraps the variant's view render in this; outside the binding (and in
     production) `*overrides*` is nil and `resolve` always misses.

     NOTE (rf2-7pgiz): this dynamic-var binding is the JVM / pure-test
     convenience form. The LIVE render path surfaces an override via the
     React-context carriage (`re-frame.adapter.sub-override-context`) +
     the `:subs/resolve-sub-override` core hook, NOT this binding — the
     dynamic var does not survive into a view's deferred render. See the
     ns docstring §STATUS."
     [overrides & body]
     `(binding [*overrides* ~overrides]
        ~@body)))

;; ============================================================================
;; Live render-path carriage — React context + the core subscribe hook
;; (rf2-7pgiz; CLJS only)
;; ============================================================================
;;
;; The dynamic var above cannot reach a view's DEFERRED render (the
;; binding unwinds first). The carriage that DOES survive is the React
;; context in `re-frame.adapter.sub-override-context` (mirroring how
;; `re-frame.adapter.context` propagates the frame-id). The Story render
;; path wraps the variant view in that context's Provider; the resolver
;; below reads the closest enclosing Provider's override map and is
;; published to core via the `:subs/resolve-sub-override` late-bind hook.
;; `re-frame.subs/subscribe` consults the hook ONLY inside its
;; `interop/debug-enabled?` gate, so the whole seam DCEs in production and
;; core never `:require`s this tools ns (bundle-isolation holds).

#?(:cljs
   (defn resolve-sub-override-hit
     "Core hook impl (published under `:subs/resolve-sub-override`). Read
     the closest enclosing override-context Provider's `{query-vector
     value}` map and return `[value]` on an exact-query-vector HIT — a
     ONE-ELEMENT vector so a `nil`-valued override is honoured as a hit —
     or nil on a miss / no Provider in scope.

     The one-element-vector / nil contract is what lets core distinguish
     'override pins nil' from 'no override' without importing this ns's
     `miss` sentinel; core stays tools-free."
     [query-v]
     (let [overrides (ovr-ctx/current-overrides)
           v         (resolve overrides query-v)]
       (when-not (miss? v)
         [v]))))

#?(:cljs
   (defn override-provider
     "Reagent render-path carriage (CLJS-only): wrap `child` (a hiccup
     form) in the override-context Provider with `overrides` (a
     `{query-vector value}` map, or nil) as its value, so a DESCENDANT
     view's `@(rf/subscribe)` reads the override at deref time via the
     `:subs/resolve-sub-override` core hook.

     Uses Reagent's `:r>` interop head (NOT `:>`) so the CLJS map value
     passes through unconverted — `:>` would mangle the map's keyword
     keys. Mirrors `re-frame.views.provider/frame-provider-component`'s
     `:r>` Provider mount. When `overrides` is nil/empty this is render-
     transparent (the descendant consult misses and the view reads its
     real subscription)."
     [overrides child]
     [:r> (.-Provider ovr-ctx/override-context) #js {:value overrides} child]))

;; Publish the hook at ns-load time (CLJS only). Core consults it lazily
;; via `late-bind/get-fn` inside the dev gate; an unpublished hook (JVM,
;; or a build that never loads Story) simply makes the consult a no-op.
#?(:cljs
   (late-bind/set-fn! :subs/resolve-sub-override resolve-sub-override-hit))

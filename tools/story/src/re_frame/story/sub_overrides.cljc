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

  Every fn here is pure data → data, so the resolver is JVM-runnable and
  the test suite needs no host. The render path (`re-frame.story.ui.
  canvas`, CLJS) reads the plan's `[:world :render :sub-overrides]` map
  and binds it through `with-overrides`; production bundles elide the
  whole runtime, so the binding is never set there.

  ## STATUS — the consuming subscribe-seam is NOT yet wired (rf2-7pgiz)

  This namespace is the read-SIDE resolver, but as of rf2-7pgiz NO
  subscribe / `compute-sub` / view-render seam consults it: a
  normally-authored view's `@(rf/subscribe [:q])` hits the real
  framework subscribe and the bound `*overrides*` map is never read, so
  a `:sub-overrides` value does NOT actually surface at render. Two
  facts compound here:

    1. There is no consumer — `subscribe` does not check `*overrides*`.
    2. Even if it did, a `binding`-bound dynamic var does NOT survive
       into a view's DEFERRED React render: the canvas binds `*overrides*`
       during the override-scope component's own render, but the view's
       `@(rf/subscribe)` runs later, in its own reaction, with the binding
       already unwound (empirically confirmed under react-dom/server —
       a parent-render binding reads `:unbound` in a child component's
       render). The robust survive-into-deferred-render mechanism is
       React context, which is exactly what `re-frame.adapter.context`
       uses to propagate the frame-id.

  Mike RULED (a) on rf2-7pgiz: build the frame-scoped subscribe shim so
  the override surfaces at render (zero-cost when unbound; stays a
  labelled low-fidelity rung; honesty guarantee preserved). That seam is
  a NEW core subscribe hook — high-stakes — so it is pinned + surfaced
  for review under rf2-7pgiz rather than guessed at here."
  (:refer-clojure :exclude [resolve read]))

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
  "Render-path read for a subscribed `query-v`: return the override value
  when one is registered for the active extent, otherwise call `real-read`
  (a 0-arg thunk that performs the genuine subscription) and return its
  value. This is the resolve-or-fall-through helper the eventual
  subscribe-seam will route through so an override surfaces at render
  WITHOUT ever touching app-db or `compute-sub`.

  NOTE (rf2-7pgiz): as of this commit NO subscribe / view-render seam
  calls `read` — a normally-authored view's `@(rf/subscribe)` does not
  route through here, so the override does not yet surface at render. See
  the ns docstring §STATUS. `read` is exercised by the resolver tests
  only until the core subscribe shim lands.

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

     NOTE (rf2-7pgiz): binding `*overrides*` here is currently INERT for a
     normally-authored view — no subscribe seam consults it, and the
     dynamic binding does not survive into the view's deferred React
     render. See the ns docstring §STATUS."
     [overrides & body]
     `(binding [*overrides* ~overrides]
        ~@body)))

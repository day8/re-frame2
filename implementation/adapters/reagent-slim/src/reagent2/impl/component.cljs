(ns reagent2.impl.component
  "Component-shape detection (Form-1/2/3) + Form-3 7-key cap for the
  day8/reagent-slim artefact (rf2-6hyy Stage 4-C).

  Per IMPL-SPEC §5 and §6.

  Public surface (consumed by other ns'es in the artefact):

    *current-component*  ;; dynamic, mirrors stock Reagent
    current-component    ;; reader fn (used by reagent2.core)
    cap-keys             ;; the canonical 7-key set
    create-class*        ;; spec map -> React class (validates against the cap)
    wrap-render          ;; runtime Form-1/2/3 detection
    fn-to-class          ;; one-arg-fn -> reagent-slim React class
    reagent-class?       ;; predicate: was this class made by create-class*?
    react-class?         ;; predicate: is this a generic React class?
    get-argv             ;; Form-3 accessor: full hiccup-style arg vector
    get-props            ;; Form-3 accessor: first arg if it's a map
    get-children         ;; Form-3 accessor: rest after props
    state-atom           ;; Form-3 state cell

  Compile-time fold (rf2-yfbx): the runtime detection in `wrap-render`
  is the load-bearing correctness mechanism. Per IMPL-SPEC §14.1 the
  recommended fold is for `re-frame.core/reg-view`'s expansion to add
  a compile-time form-tag (via the `reagent2.impl.component/-form-tag`
  meta on the wrapped fn), letting `wrap-render` skip the runtime
  classification on the hot path. The runtime path stays load-bearing
  for plain `(reg-view* :id (fn ...))` callers and for paths where the
  fold isn't applied — i.e. correctness without the macro is preserved.

  No separate `defview` macro is shipped (per the rf2-yfbx decision).
  Users who want compile-time dispatch use `reg-view`; that is the
  single canonical view-registration surface.

  Form-1: `(fn [args] hiccup)` — pure render fn.
  Form-2: `(fn [args] (fn [args] hiccup))` — outer fn is setup, inner
          fn is the live render closure that captures local state.
  Form-3: `(create-class spec-map)` — explicit class with lifecycle
          methods. Detection happens at `create-class*` call time
          (registration time, fail-fast).

  React floor: 19 (per rf2-5djt + Stage 1 commitment). Class components
  remain the React-blessed shape for error boundaries
  (`getDerivedStateFromError` + `componentDidCatch`); function components
  do NOT support `componentDidCatch`. The cap's
  `:component-did-catch` key is the single irreplaceable Form-3 surface."
  (:require [reagent2.impl.batching :as batching]
            [reagent2.ratom :as ratom]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Hiccup → React element seam
;;
;; `wrap-render` returns raw hiccup (per IMPL-SPEC §5.1); React's
;; render() MUST return a React element. The conversion lives in
;; `reagent2.impl.template/as-element` — that ns already requires this
;; one, so we can't static :require template here without a cycle.
;; Same pattern as `re-frame.late-bind`: template registers its
;; `as-element` at ns-load via `set-as-element-fn!`.
;; ---------------------------------------------------------------------------

(defonce ^:private as-element-fn (atom nil))

(defn set-as-element-fn!
  "Register the hiccup → React-element conversion fn. Called by
  `reagent2.impl.template` at ns-load time. Idempotent."
  [f]
  (reset! as-element-fn f))

(defn- ->react-element
  "Convert wrap-render's hiccup output to a React element via the
  registered `as-element` fn. Unregistered → throw
  `:rf.error/as-element-fn-unregistered` — honest failure over silent
  pass-through (the pre-rf2-08t0 shape that reached React with a raw
  CLJS vector and surfaced as 'Objects are not valid as a React
  child'). The unregistered fallback is reachable only via a
  hand-rolled bundle that requires component without template;
  production load via reagent2.core pulls both."
  [hiccup]
  (if-let [f @as-element-fn]
    (f hiccup)
    (throw (ex-info ":rf.error/as-element-fn-unregistered"
             {:type     :rf.error/as-element-fn-unregistered
              :hiccup   hiccup
              :reason   (str "reagent2.impl.template/as-element was not"
                             " registered before render. Require"
                             " reagent2.impl.template (or reagent2.core)"
                             " so its ns-load wires the as-element seam.")
              :recovery :no-recovery}))))

;; ---------------------------------------------------------------------------
;; Dynamic var: in-flight component instance
;;
;; Per IMPL-SPEC §2.8: mirrors stock Reagent's
;; `reagent.impl.component/*current-component*`. `reagent2.core/current-
;; component` reads through this. The render path binds it for the
;; duration of each render so user code (and re-frame's render-trace
;; emission) can identify "the component we are inside of".
;; ---------------------------------------------------------------------------

(def ^:dynamic *current-component* nil)

(defn current-component
  "Return the component instance currently rendering, or nil outside a
  render. Reads the dynamic *current-component* binding installed by
  the render path."
  []
  *current-component*)

;; ---------------------------------------------------------------------------
;; The 7-key cap (per IMPL-SPEC §6.1 + Stage 1 DECISION-3)
;;
;; Exactly these keys are accepted in a `create-class` spec map. Any
;; other key throws at registration time so users see the error at
;; startup, not on first render.
;; ---------------------------------------------------------------------------

(def cap-keys
  "Canonical 7 keys accepted by `create-class*`. Per IMPL-SPEC §6.1."
  #{:component-did-mount
    :component-will-unmount
    :component-did-update
    :reagent-render
    :display-name
    :get-snapshot-before-update
    :component-did-catch})

(defn- validate-spec!
  "Throw `:rf.error/create-class-key-unsupported` if `spec` carries any
  out-of-cap keys. Per IMPL-SPEC §6.2 + R-002 mitigation. Returns
  `spec` unchanged on success so the validator composes inline."
  [spec]
  (let [unsupported (vec (remove cap-keys (keys spec)))]
    (when (seq unsupported)
      (throw
        (ex-info ":rf.error/create-class-key-unsupported"
          {:type           :rf.error/create-class-key-unsupported
           :keys           unsupported
           :supported-keys cap-keys
           :reason         (str "create-class accepts a 7-key cap. "
                                "Unsupported: " (pr-str unsupported) ". "
                                "Migrate to the supported keys, restructure "
                                "via :on-create / :on-destroy events, or "
                                "switch to day8/reagent-classic.")
           :recovery       :no-recovery}))))
  spec)

;; ---------------------------------------------------------------------------
;; Form-3 accessors
;;
;; A reagent-slim class component receives `props` from React. The
;; user's hiccup-style arg vector is stashed under .-cljsArgv on the
;; component instance. `get-argv` / `get-props` / `get-children`
;; surface the conventional Reagent shape.
;; ---------------------------------------------------------------------------

(defn get-argv
  "Return the full hiccup-style arg vector that mounted `c`. Mirrors
  stock Reagent's `r/argv`. Includes the user-fn head as the first
  element."
  [^js c]
  (.-cljsArgv c))

(defn get-props
  "Return the first arg of `c`'s argv if it is a map, else nil. The
  Reagent props convention: `[my-view {:k v} ...]` — the props map
  is the second element of the argv (index 1). When absent, returns
  nil."
  [^js c]
  (let [argv (.-cljsArgv c)
        head (when argv (nth argv 1 nil))]
    (when (map? head) head)))

(defn get-children
  "Return the children seq from `c`'s argv. If argv[1] is a props map,
  children start at index 2; otherwise at index 1."
  [^js c]
  (let [argv (.-cljsArgv c)]
    (when argv
      (let [head (nth argv 1 nil)]
        (drop (if (map? head) 2 1) argv)))))

(defn state-atom
  "Return (creating on first call) a per-component reagent2 RAtom for
  Form-3 state. Mirrors stock Reagent's `r/state-atom`."
  [^js c]
  (or (.-cljsState c)
      (let [a (ratom/atom nil)]
        (set! (.-cljsState c) a)
        a)))

;; ---------------------------------------------------------------------------
;; Type predicates
;;
;; Per IMPL-SPEC §2.8: kept (re-com / 10x type-check). `reagent-class?`
;; is true only for classes built by `create-class*` (or `fn-to-class`).
;; `react-class?` is the broader "is this a React component class?"
;; predicate.
;; ---------------------------------------------------------------------------

(defn reagent-class?
  "True if `x` is a React class produced by `create-class*` /
  `fn-to-class`. Tagged via `.-cljsReagentClass`."
  [x]
  (and (some? x)
       (true? (some-> ^js x .-cljsReagentClass))))

(defn react-class?
  "True if `x` looks like a React component class (has a `render`
  method on its prototype)."
  [x]
  (and (some? x)
       (some? (.-prototype ^js x))
       (some? (.-render ^js (.-prototype ^js x)))))

;; ---------------------------------------------------------------------------
;; Runtime Form-1/Form-2 detection (per IMPL-SPEC §5.1)
;;
;; Form-3 doesn't reach `wrap-render` — it's classified at
;; `create-class*` call time and the user supplies an explicit
;; `:reagent-render` key. `wrap-render` handles Form-1 vs Form-2:
;;
;;   - Form-1: render-fn returns hiccup directly. Re-call on each
;;             render.
;;   - Form-2: render-fn returns a fn on its first call (the "setup"
;;             fn ran once and produced the live render closure).
;;             We cache the inner fn on `.-cljsRenderFn` and recall it
;;             with the current argv on subsequent renders.
;;
;; The compile-time fold (rf2-yfbx) lets `reg-view`'s expansion stamp
;; a `-form-tag` property on the user fn — when present, `wrap-render`
;; skips the classification cond and dispatches directly. The runtime
;; cond stays load-bearing for plain `(reg-view* :id (fn ...))` calls.
;; ---------------------------------------------------------------------------

(defn- argv-args
  "Slice the user-fn invocation args from the component's argv. The
  argv is `[head & user-args]`; we drop the head. Returns nil when the
  argv is absent (called outside a mounted instance)."
  [^js c]
  (when-some [argv (.-cljsArgv c)]
    (rest argv)))

(defn- form-tag
  "Read the compile-time form-tag stamped onto `render-fn` by
  `reg-view`'s expansion (per rf2-yfbx). Returns `:reagent2/form-1`,
  `:reagent2/form-2`, or nil when the fn carries no tag (plain
  `(reg-view* :id (fn ...))` callers, or a non-folding macro path)."
  [render-fn]
  (some-> render-fn meta :reagent2/form))

(defn wrap-render
  "Runtime Form-1/Form-2 detection on the user render fn `render-fn`,
  invoked on `c` (the React component instance). Returns the resulting
  hiccup.

  Per IMPL-SPEC §5.1: Form-1's render-fn returns hiccup; Form-2's
  returns a fn the first time, and the cached inner fn is recalled
  with the current argv on each subsequent render. The check is a
  single `fn?` test on the first-call return value — same shape as
  stock Reagent's `reagent.impl.component:wrap-render`.

  Compile-time fold (rf2-yfbx): when `render-fn` carries the
  `:reagent2/form` meta stamped by `reg-view`'s expansion, we
  short-circuit the classification cond and dispatch directly. The
  runtime cond stays load-bearing for plain `(reg-view* :id (fn ...))`
  callers and other paths where the fold isn't applied — correctness
  without the macro is preserved.

  Form-3 dispatches via the `:reagent-render` key in the spec map and
  reaches this fn directly with the user's render fn (the spec's
  `:reagent-render` value)."
  [^js c render-fn]
  (let [args   (argv-args c)
        cached (.-cljsRenderFn c)]
    (cond
      ;; Form-2 hot path: inner fn already cached. Recall with current args.
      (some? cached)
      (apply cached args)

      ;; Compile-time-tagged Form-2: skip the classification cond.
      (= :reagent2/form-2 (form-tag render-fn))
      (let [inner (apply render-fn args)]
        (set! (.-cljsRenderFn c) inner)
        (apply inner args))

      ;; Compile-time-tagged Form-1: skip the classification cond.
      (= :reagent2/form-1 (form-tag render-fn))
      (apply render-fn args)

      :else
      (let [out (apply render-fn args)]
        (cond
          ;; Form-1: hiccup returned directly. (Vector / nil / string /
          ;; number / seq are all valid render outputs.)
          (vector? out) out
          (or (string? out) (number? out) (nil? out)) out

          ;; Form-2: render-fn returned a fn. Cache it and recall with
          ;; the current args so this render produces actual hiccup.
          (fn? out)
          (do
            (set! (.-cljsRenderFn c) out)
            (apply out args))

          ;; Seq-as-fragment shape — coerce to a vector for React's
          ;; children-of-a-fragment handling.
          (seq? out) (vec out)

          ;; Anything else (a React element, a primitive) flows
          ;; through unchanged.
          :else out)))))

;; ---------------------------------------------------------------------------
;; Lifecycle plumbing (per IMPL-SPEC §6.4)
;;
;; The cap's 7 keys map to React lifecycle methods:
;;
;;   :component-did-mount         -> componentDidMount(this)
;;   :component-will-unmount      -> componentWillUnmount(this)
;;   :component-did-update        -> componentDidUpdate(this, prev-argv, snapshot)
;;   :reagent-render              -> render(this) — wrapped via wrap-render
;;   :display-name                -> displayName (static class field)
;;   :get-snapshot-before-update  -> getSnapshotBeforeUpdate(this, prev-argv)
;;   :component-did-catch         -> componentDidCatch(this, error, info)
;;
;; The user fns are called with `this` as the first arg to mirror
;; stock Reagent's convention (`(fn [this] ...)`). `componentDidUpdate`
;; receives the previous argv (not React's prevProps) plus the snapshot
;; from `getSnapshotBeforeUpdate`. Per IMPL-SPEC §6.6.
;;
;; All user-fn calls are wrapped in a `binding [*current-component* this]`
;; so re-frame's render-trace + `current-component` reads work.
;; ---------------------------------------------------------------------------

(defn- prev-argv-from
  "Extract the previous argv stashed on React's prevProps. The argv
  travels through React's props as `__rfArgv` (see fn-to-class)."
  [^js prev-props]
  (when prev-props (.-__rfArgv prev-props)))

(defn- bind-and-call
  "Run `f` (a user lifecycle fn) with `*current-component*` bound to
  `this`. Returns whatever `f` returns. Always installs the binding
  so user-side calls to `current-component` resolve correctly during
  the lifecycle method body."
  [this f]
  (binding [*current-component* this]
    (f)))

(defn- copy-argv-from-props!
  "Read the argv off React's `props` and stash it on `c` as
  `cljsArgv`. Called from the React constructor and from render entry.
  The argv lives on the instance so wrap-render reads a single source
  of truth."
  [^js c ^js props]
  (when props
    (set! (.-cljsArgv c) (.-__rfArgv props))))

(defn- install-lifecycle!
  "Attach the cap's lifecycle methods (those present in `spec`) to the
  React class's prototype. The user fn for each key is invoked with
  `this` (and any extra React args) inside a *current-component*
  binding."
  [^js klass spec]
  (let [proto (.-prototype klass)]
    (when-let [f (:component-did-mount spec)]
      (set! (.-componentDidMount proto)
            (fn []
              (this-as this
                (bind-and-call this #(f this))))))

    ;; componentWillUnmount: always installed (even without
    ;; :component-will-unmount) so the per-instance render Reaction
    ;; can dispose its watch graph and unblock GC. Per IMPL-SPEC §3.4.
    (let [user-fn (:component-will-unmount spec)]
      (set! (.-componentWillUnmount proto)
            (fn []
              (this-as ^js this
                (when-some [rea (.-cljsRenderRea this)]
                  (ratom/dispose! rea)
                  (set! (.-cljsRenderRea this) nil))
                (when user-fn
                  (bind-and-call this #(user-fn this)))))))

    (when-let [f (:component-did-update spec)]
      ;; React calls componentDidUpdate(prevProps, prevState, snapshot).
      ;; User fn signature: (this prev-argv snapshot) per IMPL-SPEC §6.6.
      (set! (.-componentDidUpdate proto)
            (fn [prev-props _prev-state snapshot]
              (this-as this
                (bind-and-call this
                  #(f this (prev-argv-from prev-props) snapshot))))))

    (when-let [f (:get-snapshot-before-update spec)]
      ;; User fn: (this prev-argv) → snapshot; React then threads
      ;; snapshot as the third arg to componentDidUpdate.
      (set! (.-getSnapshotBeforeUpdate proto)
            (fn [prev-props _prev-state]
              (this-as this
                (bind-and-call this
                  #(f this (prev-argv-from prev-props)))))))

    (when-let [f (:component-did-catch spec)]
      ;; Error-boundary logging half — user fn: (this error info).
      ;; Per IMPL-SPEC §6.4. Stateful fallback is the user's
      ;; responsibility (reset! a state-atom from inside the callback).
      (set! (.-componentDidCatch proto)
            (fn [error info]
              (this-as this
                (bind-and-call this
                  #(f this error info))))))

    ;; getDerivedStateFromError: React-19 requires it (paired with
    ;; componentDidCatch) for the boundary to catch at all. Default
    ;; flips a :cljsHasError marker user :reagent-render can check.
    ;; Per IMPL-SPEC §6.5.
    (when (:component-did-catch spec)
      (set! (.-getDerivedStateFromError klass)
            (fn [_error]
              #js {:cljsHasError true})))

    (when-let [name-str (:display-name spec)]
      (set! (.-displayName klass) name-str))

    klass))

;; ---------------------------------------------------------------------------
;; React class construction (per IMPL-SPEC §6.3 + §4.4)
;;
;; The class extends React.Component via prototype chain. The
;; constructor stashes the initial argv on the instance; the render
;; method delegates to wrap-render and runs inside a per-component
;; Reaction (reactive-subscription wiring, IMPL-SPEC §4.4 path 1).
;; ---------------------------------------------------------------------------

(defn- make-render-method
  "Build the React `render` method. Runs the user's render fn inside a
  per-component Reaction so deref'd RAtoms / Reactions register as
  dependencies; on dep change the auto-run queues a React re-render
  via `batching/queue-render!`. The Reaction is created lazily on
  first render and cached on the instance as `.-cljsRenderRea`.

  Per IMPL-SPEC §4.4 path 1 + §5.1. See DESIGN-RATIONALE for the
  `(._run rea false)`-on-subsequent-renders shape (custom auto-run
  doesn't mark dirty? so a plain @rea would return the cached prior
  state)."
  [render-fn]
  (fn []
    (this-as ^js this
      ;; Re-stash argv from current props on every render entry so
      ;; Form-2's cached render-fn sees fresh args. Static
      ;; getDerivedStateFromProps cannot side-effect on the instance,
      ;; so the copy happens here.
      (copy-argv-from-props! this (.-props this))
      (binding [*current-component* this]
        (batching/mark-rendered this)
        (let [^js rea (.-cljsRenderRea this)
              hiccup (if (nil? rea)
                       (let [^js r (ratom/make-reaction
                                     #(wrap-render this render-fn)
                                     :auto-run
                                     (fn [_r]
                                       (batching/queue-render! this)))]
                         (set! (.-cljsRenderRea this) r)
                         @r)
                       ;; ._run re-captures deps AND produces fresh hiccup.
                       (._run rea false))]
          (->react-element hiccup))))))

(defn create-class*
  "Build a React component class from a Form-3 spec map. Validates
  the spec against the 7-key cap (per IMPL-SPEC §6.1) — any
  out-of-cap key throws `:rf.error/create-class-key-unsupported` at
  registration time (fail-fast, not render-time).

  Returned class extends React.Component (synthesised via prototype
  chain — CLJS can't `(class Foo extends ...)` directly), carries the
  cap's lifecycle methods (IMPL-SPEC §6.4), and is tagged
  `cljsReagentClass = true` for `reagent-class?`."
  [spec]
  (validate-spec! spec)
  (let [render-fn (or (:reagent-render spec)
                      (throw (ex-info "create-class spec missing :reagent-render"
                               {:type :rf.error/create-class-missing-render
                                :spec spec})))
        ;; The constructor: extends React.Component via prototype chain.
        ^js klass (fn [props]
                    (this-as this
                      ;; Call React.Component's constructor.
                      (.call (.-Component react) this props)
                      ;; React fields React expects.
                      (set! (.-state this) #js {})
                      ;; Stash the initial argv from props.__rfArgv so render
                      ;; can read it without a fresh prop-walk.
                      (copy-argv-from-props! this props)
                      this))]
    ;; Prototype chain: klass -> React.Component.prototype -> ...
    (set! (.-prototype klass)
          (.create js/Object (.-prototype (.-Component react))))
    (set! (.. klass -prototype -constructor) klass)
    (set! (.-cljsReagentClass klass) true)
    ;; Stash the user's `:reagent-render` on the class so out-of-band
    ;; introspection (e.g. `re-frame.test-helpers/expand-tree`) can
    ;; invoke it without going through React. Mirrors stock Reagent's
    ;; convention of exposing the user fn alongside the class tag.
    (set! (.-cljsReagentRender klass) render-fn)

    ;; render delegates to wrap-render over the user's :reagent-render.
    ;; make-render-method's body re-stashes argv from props at render
    ;; entry, so a single install is sufficient.
    (set! (.. klass -prototype -render) (make-render-method render-fn))

    (install-lifecycle! klass spec)
    klass))

;; ---------------------------------------------------------------------------
;; fn-to-class — lift a plain CLJS fn into a reagent-slim React class
;;
;; Per IMPL-SPEC §2.8 / §7.1: most reg-view'd render fns reach the
;; renderer as plain CLJS fns; the renderer wraps them in a class so
;; lifecycle (including the *current-component* binding + the
;; dependency-tracking deref-capture in 4-D) has somewhere to live.
;; This is the canonical path for Form-1 and Form-2 components that
;; arrive without an explicit create-class call.
;;
;; Deduplication: each plain fn is wrapped at most once; the wrapped
;; class is cached on the fn as `.-cljsReagentClass-fn`. Subsequent
;; calls return the cached class.
;; ---------------------------------------------------------------------------

(defn fn-to-class
  "Wrap a plain CLJS render fn `f` in a reagent-slim React class so the
  renderer has lifecycle plumbing (and so re-com / 10x's reagent-class?
  type checks return true). Caches the wrapped class on `f` so repeated
  calls return the same class.

  `f` is treated as the `:reagent-render` of an implicit Form-1/Form-2
  spec; runtime detection between Form-1 and Form-2 happens inside
  `wrap-render`."
  [f]
  (or (.-cljsReagentClass-fn ^js f)
      (let [klass (create-class*
                    {:reagent-render f
                     :display-name   (or (some-> f .-displayName)
                                         (some-> f .-name)
                                         "")})]
        (set! (.-cljsReagentClass-fn ^js f) klass)
        klass)))

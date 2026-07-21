(ns re-frame.ui.hooks
  "S3 host-hook lowering targets — the compiler-owned runtime a defview's
  `local` / `effect` / `dispatch-fn` sites lower to (the analyzer rewrites the
  authored forms to these calls; both emitters emit them verbatim, so one
  `.cljc` symbol resolves per host).

  CLJS realises real React hooks + the committed EventOwner:

    local-state    `react/useState` behind the `[value set! update!]`
                   three-tuple (P0-1): `set!` stores its argument EXACTLY (a
                   stored fn is a value, never an updater); `update!` applies
                   `(f current & args)` to the LATEST host state so same-turn
                   writers compose. Host-only — a set!/update! during render
                   fails loud (dev).
    effect-value   `react/useEffect` with `rf=` value-deps (a stable token
                   changes identity only on an `rf=` dep change, so React owns
                   cleanup on dep-change / disconnect / unmount and StrictMode
                   replay is idempotent-safe by contract).
    effect-connect `react/useEffect` with empty deps — runs at each connect,
                   cleanup at each disconnect (Activity reveal re-runs it).
    dispatch-fn    the per-view stable committed-frame dispatcher (owned by
                   `re-frame.ui.events`).

  JVM realises the structural subset (Spec 004 §The JVM structural subset):
  `local` exposes its INITIAL value and raises `:rf.error/jvm-host-op` on any
  set!/update!; effects DO NOT run (metadata only); a `dispatch-fn` invocation
  raises the same typed host-op error."
  (:refer-clojure :exclude [dispatch-fn])
  #?(:cljs (:require ["react" :as react]
                     [re-frame.error :as error]
                     [re-frame.ui.eq :as eq]
                     [re-frame.ui.events :as events]
                     [re-frame.ui.reactive :as reactive])
     :clj  (:require [re-frame.ui.tree :as tree])))

#?(:cljs
   (do

;; ---------------------------------------------------------------------------
;; local — the [value set! update!] three-tuple (P0-1)
;; ---------------------------------------------------------------------------

;; DEV render-phase guard. `with-local-render-guard` wraps a local-bearing
;; view's body so a set!/update! invoked DURING render (renders are
;; speculative) fails loud. Production emits the body directly (no flag, no
;; check — the whole guard is goog.DEBUG-gated at both ends).
(def ^:private render-active #js {:v false})

(defn with-local-render-guard
  "DEV: run `thunk` with the local render-phase flag set. A `local` set!/update!
  invoked while the flag is set fails loud."
  [thunk]
  (let [prev (.-v render-active)]
    (set! (.-v render-active) true)
    (try (thunk) (finally (set! (.-v render-active) prev)))))

(defn- fail-render-phase! []
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/local
   (str "a (local …) set!/update! ran during a render pass — local mutation is "
        "host-only and renders are speculative. Call the setter/updater from a "
        "committed handler or an (effect …), never in the render body")
   {:extra {:reason :render-phase-local-mutation}}))

(defn local-state
  "React `useState`-backed `[value set! update!]`. `set!` stores its argument
  exactly (a stored fn is a value, never an updater); `update!` applies
  `(f current & args)` to the LATEST host state so several same-turn writers
  compose (React's functional updater queues them against the live state).

  DEV-only view-evidence bridge (Ruling 2 :local-state): a host-only write is the
  cause of the re-render it triggers, so the ViewCell's connected commit attributes
  its :rf.view/causes to the local write — but ONLY when React ACTUALLY commits the
  change (rf2-bvqu0). The state updater is PURE (it never touches the evidence
  stash); the attribution instead rides a DEBUG `useLayoutEffect` keyed on the
  COMMITTED value. Because a layout effect runs ONLY in the commit phase, a no-op
  setter and a same-batch net-zero `0->1->0` — which React bails WITHOUT committing
  — never reach it and never stash a marker (the stale-marker contamination the old
  per-updater Object.is note left behind, rf2-qkq2k/rf2-bvqu0). A committed-value
  ref skips the mount run (that commit is :mount, not :local-state) and makes a
  StrictMode effect replay idempotent. The effect runs BEFORE the ViewCell's own
  commit effect (same fiber, earlier hook slot), so `note-local-state!`'s flag is
  set in time for that commit's record. React — not the ViewCell scheduler — owns
  the re-render, so the bridge only sets the flag, never marks the cell dirty. The
  whole bridge is `goog.DEBUG`-gated at both ends (and `goog.DEBUG` is
  build-constant, so the hook order is stable within a build) and elides in
  production."
  [init]
  (let [pair      (react/useState init)
        value     (aget pair 0)
        react-set (aget pair 1)
        ops       (react/useRef nil)]
    ;; DEBUG-only committed-change attribution (rf2-bvqu0). The whole block — its
    ;; useRef + useLayoutEffect included — DCEs in production; goog.DEBUG is
    ;; build-constant, so the hook order is stable for every render of a build.
    (when ^boolean js/goog.DEBUG
      (let [cell      (reactive/ambient-cell)   ;; the owning cell (read during render)
            committed (react/useRef init)]      ;; the last COMMITTED value observed
        (react/useLayoutEffect
         (fn note-committed-local-change []
           ;; Runs only in a real commit phase. `#js [value]` limits it to a
           ;; committed value CHANGE (+ the mount run, which the ref then skips).
           (when-not ^boolean (js/Object.is (.-current committed) value)
             (set! (.-current committed) value)
             (reactive/note-local-state! cell))
           js/undefined)
         #js [value])))
    ;; The React setter identity is stable across renders, so set!/update! are
    ;; minted once and kept stable (attach them as handlers/listeners freely).
    (when (nil? (.-current ops))
      (let [setter  (fn local-set!
                      [v]
                      (when ^boolean js/goog.DEBUG
                        (when (.-v render-active) (fail-render-phase!)))
                      ;; ALWAYS the functional form: a stored fn is returned
                      ;; verbatim, never invoked as a React updater. The updater is
                      ;; PURE — :local-state attribution rides the committed-value
                      ;; layout effect above, not this updater (rf2-bvqu0).
                      (react-set (fn [_] v))
                      nil)
            updater (fn local-update!
                      [f & args]
                      (when ^boolean js/goog.DEBUG
                        (when (.-v render-active) (fail-render-phase!)))
                      (react-set (fn [cur] (apply f cur args)))
                      nil)]
        (set! (.-current ops) #js [setter updater])))
    [value (aget (.-current ops) 0) (aget (.-current ops) 1)]))

;; ---------------------------------------------------------------------------
;; effect — rf= value-deps / :connect
;; ---------------------------------------------------------------------------

(def ^:private empty-deps #js [])

(defn- run-effect
  "Invoke an effect body; coerce a non-fn return to `undefined` so React only
  ever receives a cleanup fn or nothing."
  [body-fn]
  (let [c (body-fn)] (if (fn? c) c js/undefined)))

(defn- deps-token
  "WIP/concurrency-safe identity token for an authored deps vector, held in
  ordinary React hook STATE (`useState`) — never a mutable `useRef` cell written
  during render. Returns a token (a monotone counter) whose value changes iff the
  authored deps stop being PER-SLOT `rf=` (`eq/deps-rf=?`) against the last
  COMMITTED deps — a distinct-but-`rf=`-equal deps value keeps the SAME token, so
  React's own cleanup→setup does not fire. Because the comparison is
  kernel-internal (a held previous-deps slot, not React's native deps array) the
  authored deps arity may vary between renders behind a fixed one-element React
  deps array.

  Why hook state, not a render-time ref (rf2-u53yy.6 obl-2): React shares ONE
  mutable ref object across a fiber's current AND work-in-progress renders, but
  DOUBLE-BUFFERS hook state per WIP fiber. A ref-held token is poisoned by a
  concurrent render that is later ABANDONED — committed A holds token 0; a
  suspended transition render B writes deps-B/token-1 into the shared ref; B is
  abandoned; then an unchanged A re-renders, reads B's leftover deps, and
  spuriously mints a new token → React cleanup/setups an effect whose COMMITTED
  deps never changed. Holding (deps, token) in `useState` isolates B's write to
  B's WIP fiber, discarded on abandon; the committed fiber keeps its own deps +
  token, so the unchanged-A re-render skips the effect.

  On an `rf=` dep change the token is advanced via a set-state-DURING-render (the
  React-supported \"adjust state while rendering\" pattern: React re-renders with
  the new state before committing, so the effect registers the advanced token).
  The set NEVER fires on `rf=`-equal deps, so a StrictMode double-render — or any
  re-render with `rf=`-equal deps — is idempotent; the token is a plain number,
  Object.is-stable by value, so React's native deps array carries it faithfully.

  This is re-frame.ui's ONE effect-dependency equality doctrine — the same `rf=`
  the memo/prop comparator and the native `effect` tier use, walked PER SLOT so a
  stable `##NaN` slot (which whole-vector `rf=` would falsely report changed) or
  a rebuilt-but-equal CLJS collection slot does not re-run the effect. Shared
  verbatim by the react-interop wrappers, never a second per-tier regime."
  [deps]
  (let [pair      (react/useState (fn init-deps-state [] #js {:deps deps :token 0}))
        state     (aget pair 0)
        set-state (aget pair 1)]
    (if ^boolean (eq/deps-rf=? (.-deps ^js state) deps)
      (.-token ^js state)
      (let [t (inc (.-token ^js state))]
        (set-state #js {:deps deps :token t})
        t))))

(defn effect-value
  "Passive host effect with `rf=` VALUE deps. A stable per-effect token changes
  identity only when the deps stop being `rf=`, so React's own `useEffect`
  drives cleanup-then-setup on dep change, on disconnect/unmount, and under
  StrictMode replay — no hand-rolled scheduling. The token is WIP/concurrency-safe
  (held in React hook state, not a shared render-time ref — see `deps-token`)."
  [body-fn deps]
  (react/useEffect #(run-effect body-fn) #js [(deps-token deps)])
  js/undefined)

(defn effect-connect
  "Passive host effect that runs at each CONNECT with cleanup at each disconnect
  — `react/useEffect` with empty deps (React re-runs it on Activity reveal)."
  [body-fn]
  (react/useEffect #(run-effect body-fn) empty-deps)
  js/undefined)

;; ---------------------------------------------------------------------------
;; dispatch-fn — the stable committed-frame dispatcher
;; ---------------------------------------------------------------------------

(defn dispatch-fn
  "The per-view stable committed-frame dispatcher (owned by
  `re-frame.ui.events`)."
  []
  (events/dispatch-fn))

;; ---------------------------------------------------------------------------
;; Host-hook CLJS lowering targets — the six re-frame.ui.react interop wrappers
;; plus the substrate `re-frame.ui/ref` (lowered to the `use-ref` fn below,
;; unchanged — rf2-u53yy.9). The analyzer rewrites the authored sites to these;
;; the position law keeps them straight-line and once-per-render, so React's hook
;; order stays static. Every React-property access is `^js`-hinted so the
;; advanced build carries zero :infer-warning.
;; ---------------------------------------------------------------------------

(defn use-ref
  "`re-frame.ui/ref` (`ui/ref`) lowering target — `useRef`. Returns the host ref
  object; read/write `(.-current ref)`; assignment never re-renders."
  ([] (react/useRef nil))
  ([initial] (react/useRef initial)))

(defn use-effect
  "react/use-effect lowering target — `useEffect` (passive, after paint). Both
  arities allocate the same fixed hook shape (a held deps-state cell + the
  effect), so editing deps presence is a same-signature edit. No-deps ⇒ a fresh
  token every render (runs after every commit); deps ⇒ `rf=` VALUE deps via the
  shared WIP-safe `deps-token` — a distinct-but-`rf=`-equal CLJS deps value does
  NOT rerun (the one equality doctrine shared with `effect` and memo, never a
  second per-tier regime)."
  ([setup]
   (react/useState nil) ;; shape-match the deps arity's `deps-token` hook state
   (react/useEffect #(run-effect setup) #js [#js {}])
   js/undefined)
  ([setup deps]
   (react/useEffect #(run-effect setup) #js [(deps-token deps)])
   js/undefined))

(defn use-layout-effect
  "react/use-layout-effect lowering target — `useLayoutEffect` (after DOM
  mutation, before paint): the measure-before-paint door. Same fixed hook shape
  and `rf=` value-deps contract as `use-effect` (the deps token is WIP-safe —
  held in React hook state, not a shared render-time ref — see `deps-token`)."
  ([setup]
   (react/useState nil) ;; shape-match the deps arity's `deps-token` hook state
   (react/useLayoutEffect #(run-effect setup) #js [#js {}])
   js/undefined)
  ([setup deps]
   (react/useLayoutEffect #(run-effect setup) #js [(deps-token deps)])
   js/undefined))

(defn use-effect-event
  "react/use-effect-event lowering target — React 19.2 `useEffectEvent`, native.
  The returned fn sees the latest render's values; its identity is NOT stable
  (React allocates a fresh function each render) and it throws if called during
  render. No shim — the host primitive's deliberate misuse signal is preserved."
  [f]
  (react/useEffectEvent f))

(defn use-context
  "react/use-context lowering target — `useContext`; hands the foreign context
  value through uncoerced."
  [ctx]
  (react/useContext ctx))

(defn use-id
  "react/use-id lowering target — `useId`; a host tree-positional token prefixed
  by the root's identifierPrefix."
  []
  (react/useId))

(defn lazy-component
  "Def-level React.lazy wrapper (re-frame.ui.react/lazy). `load-thunk` is a
  zero-arg fn returning a Promise of a foreign component; `fallback-thunk` is a
  zero-arg fn returning the compiled fallback React element (rendered inside a
  Suspense boundary) or nil (the author supplies a Suspense ancestor). Returns a
  foreign component — legal exactly where foreign heads are."
  [load-thunk fallback-thunk]
  (let [lazy-inner (react/lazy load-thunk)]
    (fn lazy-boundary [props]
      (let [el (react/createElement lazy-inner props)]
        (if fallback-thunk
          (react/createElement react/Suspense #js {:fallback (fallback-thunk)} el)
          el)))))

)

   :clj
   (do

;; The JVM structural subset. `local` exposes its initial value; every host
;; mutation and `dispatch-fn` invocation raises the typed host-op error;
;; effects do not run (recorded as capability metadata only — 06 §1 / 07 §2).

(defn local-state
  "JVM `local`: `[init set! update!]` where the initial value is real and both
  mutators raise `:rf.error/jvm-host-op` when invoked."
  [init]
  [init
   (fn jvm-local-set! [& _]
     (tree/jvm-host-op! :ui/local-set!
                        "(local …) set! is a host-only client operation"))
   (fn jvm-local-update! [& _]
     (tree/jvm-host-op! :ui/local-update!
                        "(local …) update! is a host-only client operation"))])

(defn with-local-render-guard [thunk] (thunk))

(defn effect-value [_body-fn _deps] nil)

(defn effect-connect [_body-fn] nil)

(defn dispatch-fn []
  (fn jvm-dispatch-fn [& _]
    (tree/jvm-host-op! :ui/dispatch-fn
                       "(ui/dispatch-fn) dispatch is a host-only client operation")))

;; ---------------------------------------------------------------------------
;; Host-hook JVM structural subset (Spec 004 §Host behaviour) — the substrate
;; `ui/ref` and the re-frame.ui.react interop hooks. Refs are inert; passive/
;; layout effects do not run; effect-event is metadata-only and its returned fn
;; fails loud; use-context resolves a JVM-provided test value or fails loud;
;; use-id is a deterministic inert string.
;; ---------------------------------------------------------------------------

(defrecord InertRef [current])

(defn use-ref
  "JVM `re-frame.ui/ref` (lowering target `use-ref`): an inert ref (`current`
  nil, stays nil) — refs never appear in the JVM structural tree."
  ([] (->InertRef nil))
  ([_initial] (->InertRef nil)))

(defn use-effect
  "JVM react/use-effect: does not run (recorded as capability metadata only)."
  ([_setup] nil)
  ([_setup _deps] nil))

(defn use-layout-effect
  "JVM react/use-layout-effect: does not run (capability metadata only)."
  ([_setup] nil)
  ([_setup _deps] nil))

(defn use-effect-event
  "JVM react/use-effect-event: metadata-only; the returned fn raises
  `:rf.error/jvm-host-op` if invoked."
  [_f]
  (fn jvm-use-effect-event [& _]
    (tree/jvm-host-op! :ui/use-effect-event
                       "(react/use-effect-event …) is a host-only client operation")))

(def ^:dynamic *jvm-react-context-values*
  "JVM/SSR structural-render lookup for `(react/use-context ctx)`: a map keyed
  by the JVM-side context argument (typically a reader-conditional token). Bound
  by a fixture / the ui.test/render `:react-context-values` option; an absent
  key fails loud — foreign React context never silently resolves nil."
  nil)

(defn use-context
  "JVM react/use-context: the JVM-provided test value, else fails loud."
  [ctx]
  (if (and (map? *jvm-react-context-values*)
           (contains? *jvm-react-context-values* ctx))
    (get *jvm-react-context-values* ctx)
    (tree/jvm-host-op!
     :ui/use-context
     (str "(react/use-context " (pr-str ctx) ") has no JVM-provided value — "
          "bind re-frame.ui.hooks/*jvm-react-context-values* (or the "
          "ui.test/render :react-context-values option) keyed by the JVM-side "
          "context token; foreign React context never resolves on the JVM"))))

(def ^:dynamic *jvm-use-id-prefix*
  "JVM `(react/use-id)` identifier prefix (the resolved root prefix stand-in)."
  "rf2-")

(def ^:dynamic *jvm-use-id-counter*
  "Optional per-render occurrence counter (a `volatile!`) making repeated
  `(react/use-id)` calls deterministic and distinct; nil ⇒ a single `-0` id."
  nil)

(defn use-id
  "JVM react/use-id: a deterministic inert string (prefix + occurrence counter).
  Does NOT reproduce React's tree-positional algorithm — safe for static roots;
  a hydrating root is a mismatch risk."
  []
  (let [n (if *jvm-use-id-counter*
            (let [v @*jvm-use-id-counter*] (vswap! *jvm-use-id-counter* inc) v)
            0)]
    (str *jvm-use-id-prefix* "uid-" n)))

;; A re-frame.ui.react/lazy component on the JVM: an INVOKABLE structural
;; component (renders the declared fallback / nothing, never touching the load
;; thunk — which is not even emitted on the JVM) that also carries the
;; `:rf.ui/lazy` marker the analyzer's `classify-head` detects via var deref, so
;; the JVM emitter calls it instead of raising the foreign host-op.
(defrecord LazyJvmComponent [render-fallback]
  clojure.lang.IFn
  (invoke [_ _props] (when render-fallback (render-fallback))))

(defn lazy-jvm
  "JVM structural render value of a react/lazy component (see LazyJvmComponent)."
  [fallback-thunk]
  (assoc (->LazyJvmComponent fallback-thunk) :rf.ui/lazy true))

))

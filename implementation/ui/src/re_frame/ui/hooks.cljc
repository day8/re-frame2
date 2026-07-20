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

(defn- note-committed-local-change!
  "DEBUG-only :local-state attribution gate (Ruling 2 + rf2-qkq2k fix). Records
  `:local-state` on `cell` ONLY when the write is an ACTUALLY-COMMITTED value
  change — `cur` (the LIVE host state React itself compares) differs from `nv` by
  `Object.is`, React's own bail law. React 19.2 Object.is-BAILS a no-op setter
  WITHOUT rendering, so noting on invocation left a stale marker that contaminated
  a LATER unrelated commit; aligning on the same `Object.is` React uses makes the
  note fire EXACTLY when a re-render (hence a connected commit) will. Runs inside
  the react-set functional updater (the one place the live `cur` is authoritative,
  correct even for chained same-turn writes); `note-local-state!` is an idempotent
  stash that triggers no re-render, so a StrictMode double-invoke is harmless.
  Returns `nv` (so the updater's value is unchanged)."
  [cell cur nv]
  (when-not ^boolean (js/Object.is cur nv)
    (reactive/note-local-state! cell))
  nv)

(defn local-state
  "React `useState`-backed `[value set! update!]`. `set!` stores its argument
  exactly (a stored fn is a value, never an updater); `update!` applies
  `(f current & args)` to the LATEST host state so several same-turn writers
  compose (React's functional updater queues them against the live state).

  DEV-only view-evidence bridge (Ruling 2 :local-state): a host-only
  `set!`/`update!` records `:local-state` as the cause of the re-render it
  triggers, so the ViewCell's NEXT connected commit attributes its
  :rf.view/causes to the local write — but ONLY when the write is an
  actually-committed value change (`note-committed-local-change!`): React 19.2
  Object.is-bails a no-op setter without rendering, so noting on every invocation
  contaminated a later unrelated commit (rf2-qkq2k). The owning cell is captured
  ONCE at setter-mint time (the first render, which runs inside the ambient
  `with-capture`); React — not the ViewCell scheduler — owns the re-render, so the
  bridge only STASHES the cause (`reactive/note-local-state!`), never marks the
  cell dirty or advances a revision. The whole bridge is `goog.DEBUG`-gated at
  both ends and elides in production."
  [init]
  (let [pair      (react/useState init)
        value     (aget pair 0)
        react-set (aget pair 1)
        ops       (react/useRef nil)]
    ;; The React setter identity is stable across renders, so set!/update! are
    ;; minted once and kept stable (attach them as handlers/listeners freely).
    (when (nil? (.-current ops))
      (let [cell    (when ^boolean js/goog.DEBUG (reactive/ambient-cell))
            setter  (fn local-set!
                      [v]
                      (when ^boolean js/goog.DEBUG
                        (when (.-v render-active) (fail-render-phase!)))
                      ;; ALWAYS the functional form: a stored fn is returned
                      ;; verbatim, never invoked as a React updater. The DEBUG
                      ;; :local-state note rides INSIDE the updater so it sees the
                      ;; live `cur` React compares — fired ONLY on a real change
                      ;; (rf2-qkq2k); production DCEs to `(fn [_] v)`.
                      (react-set (fn [cur]
                                   (if ^boolean js/goog.DEBUG
                                     (note-committed-local-change! cell cur v)
                                     v)))
                      nil)
            updater (fn local-update!
                      [f & args]
                      (when ^boolean js/goog.DEBUG
                        (when (.-v render-active) (fail-render-phase!)))
                      (react-set (fn [cur]
                                   (let [nv (apply f cur args)]
                                     (if ^boolean js/goog.DEBUG
                                       (note-committed-local-change! cell cur nv)
                                       nv))))
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

(defn effect-value
  "Passive host effect with `rf=` VALUE deps. A stable per-effect token changes
  identity only when the deps stop being `rf=`, so React's own `useEffect`
  drives cleanup-then-setup on dep change, on disconnect/unmount, and under
  StrictMode replay — no hand-rolled scheduling."
  [body-fn deps]
  (let [ref   (react/useRef nil)
        prev  (.-current ref)
        ;; Deriving the token during render is a pure function of (prev, deps):
        ;; equal deps yield the same token, so a StrictMode double-render (or any
        ;; re-render with rf=-equal deps) is idempotent.
        token (if (and (some? prev) (eq/rf= (.-deps prev) deps))
                (.-token prev)
                (let [t #js {}]
                  (set! (.-current ref) #js {:deps deps :token t})
                  t))]
    (react/useEffect #(run-effect body-fn) #js [token])
    js/undefined))

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
;; re-frame.ui.react interop tier — the frozen seven wrappers' CLJS lowering
;; targets. The analyzer rewrites (react/use-* …) authored sites to these; the
;; position law keeps them straight-line and once-per-render, so React's hook
;; order stays static. Every React-property access is `^js`-hinted so the
;; advanced build carries zero :infer-warning.
;; ---------------------------------------------------------------------------

(defn use-ref
  "react/use-ref lowering target — `useRef`. Returns the host ref object;
  read/write `(.-current ref)`; assignment never re-renders."
  ([] (react/useRef nil))
  ([initial] (react/useRef initial)))

(defn- deps-object-is-equal?
  "Per-slot `Object.is` comparison of two authored deps vectors — React's
  effect-dependency equality (a synchronisation input, NOT a repaint value: a
  distinct value-equal host object IS a change here). An arity change is never
  equal. `rf=` stays the memo/prop comparator; effects use this."
  [prev nxt]
  (let [n (count prev)]
    (and (== n (count nxt))
         (loop [i 0]
           (or (== i n)
               (and ^boolean (js/Object.is (nth prev i) (nth nxt i))
                    (recur (inc i))))))))

(defn- effect-deps-token
  "Render-time pure token for a react effect's authored deps held in the useRef
  cell `ref`: one internal token whose identity changes iff any slot changed by
  `Object.is` or the arity changed — so React owns cleanup→setup and the authored
  deps arity stays compiler-managed behind a fixed one-element React deps array."
  [^js ref deps]
  (let [prev ^js (.-current ref)]
    (if (and (some? prev) (deps-object-is-equal? (.-deps prev) deps))
      (.-token prev)
      (let [t #js {}]
        (set! (.-current ref) #js {:deps deps :token t})
        t))))

(defn use-effect
  "react/use-effect lowering target — `useEffect` (passive, after paint). Both
  arities allocate the same fixed hook shape (a held deps cell + the effect), so
  editing deps presence is a same-signature edit. No-deps ⇒ a fresh token every
  render (runs after every commit); deps ⇒ per-slot `Object.is`."
  ([setup]
   (let [_ref (react/useRef nil)]
     (react/useEffect #(run-effect setup) #js [#js {}]))
   js/undefined)
  ([setup deps]
   (let [ref (react/useRef nil)]
     (react/useEffect #(run-effect setup) #js [(effect-deps-token ref deps)]))
   js/undefined))

(defn use-layout-effect
  "react/use-layout-effect lowering target — `useLayoutEffect` (after DOM
  mutation, before paint): the measure-before-paint door. Same fixed hook shape
  and `Object.is` deps contract as `use-effect`."
  ([setup]
   (let [_ref (react/useRef nil)]
     (react/useLayoutEffect #(run-effect setup) #js [#js {}]))
   js/undefined)
  ([setup deps]
   (let [ref (react/useRef nil)]
     (react/useLayoutEffect #(run-effect setup) #js [(effect-deps-token ref deps)]))
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
;; re-frame.ui.react interop tier — the JVM structural subset (Spec 004 §Host
;; behaviour). Refs are inert; passive/layout effects do not run; effect-event
;; is metadata-only and its returned fn fails loud; use-context resolves a
;; JVM-provided test value or fails loud; use-id is a deterministic inert string.
;; ---------------------------------------------------------------------------

(defrecord InertRef [current])

(defn use-ref
  "JVM react/use-ref: an inert ref (`current` nil, stays nil) — refs never
  appear in the JVM structural tree."
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

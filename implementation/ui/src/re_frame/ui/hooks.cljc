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
                     [re-frame.ui.events :as events])
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
  compose (React's functional updater queues them against the live state)."
  [init]
  (let [pair      (react/useState init)
        value     (aget pair 0)
        react-set (aget pair 1)
        ops       (react/useRef nil)]
    ;; The React setter identity is stable across renders, so set!/update! are
    ;; minted once and kept stable (attach them as handlers/listeners freely).
    (when (nil? (.-current ops))
      (let [setter  (fn local-set!
                      [v]
                      (when ^boolean js/goog.DEBUG
                        (when (.-v render-active) (fail-render-phase!)))
                      ;; ALWAYS the functional form: a stored fn is returned
                      ;; verbatim, never invoked as a React updater.
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

))

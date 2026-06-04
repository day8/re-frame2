(ns re-frame.interop
  "JVM host primitives. Used for the plain-atom (headless / SSR / test) adapter.

  Mirrors the .cljs counterpart's surface; some operations are no-ops or
  simplified because the JVM has no DOM, no Reagent, no animation frame.
  See Spec 011 §JVM-runnable view rendering and Spec 008 §JVM-runnable
  test suites."
  (:require [clojure.string :as str])
  (:import [java.util.concurrent Executor Executors ScheduledExecutorService TimeUnit ScheduledFuture]))

(set! *warn-on-reflection* true)

;; ---- next-tick scheduling -------------------------------------------------

(defonce ^:private executor (Executors/newSingleThreadExecutor))

(defn next-tick
  "Schedule f for execution on the runtime's executor. Returns nil."
  [f]
  (let [bound-f (bound-fn [& args] (apply f args))]
    (.execute ^Executor executor bound-f))
  nil)

;; ---- after-render hook ----------------------------------------------------
;; No animation frame on the JVM; degrade to next-tick.
(def after-render next-tick)

;; ---- mutable cells --------------------------------------------------------

(defn ratom [v]
  (atom v))

(defn ratom? [x]
  (instance? clojure.lang.IAtom x))

;; ---- reactions ------------------------------------------------------------
;;
;; On the JVM, "reaction" semantics are limited: the runtime does not maintain
;; a tracking graph the way Reagent does. make-reaction returns a thunk that
;; recomputes f on every deref. For the headless / SSR use cases this is fine
;; — they read once or twice per request, not in a hot reactive loop.
;;
;; Per rf2-tnnln: on-dispose callbacks live ON the reaction object (a mutable
;; field on the `Reaction` deftype), NOT in a process-wide identity-keyed
;; registry. This mirrors the CLJS plain-atom adapter, whose derived-value
;; reify closes over an `(atom [])` of callbacks (see
;; `re-frame.substrate.plain-atom/make-derived-value`): the callbacks' lifetime
;; is tied to the reaction's lifetime, so an orphaned reaction (one that is
;; dropped WITHOUT an explicit `dispose!` — a partial-teardown bug, an
;; exception between `add-on-dispose!` and `dispose!`) is reclaimed by GC along
;; with its callbacks rather than pinned forever. This is the correct
;; resource-lifecycle posture for the long-lived JVM SSR profile (Spec 011's
;; frame-per-request), where the prior global strong-ref `atom`-of-map was a
;; leak surface: it held strong references to every reaction (and its whole
;; layer-2+ input chain + compute-fn) until `dispose!` was called with that
;; exact object, defeating GC for any reaction that escaped its dispose path.
;;
;; `dispose!` semantics are unchanged: callbacks are cleared from the reaction
;; first (re-entrancy / idempotency — a second `dispose!` is a no-op) then
;; fired in registration order.
;;
;; A weak-keyed fallback registry handles objects that are NOT `Reaction`s
;; (bare `reify clojure.lang.IDeref` test doubles, plain atoms) so the
;; `add-on-dispose!` / `dispose!` surface still works uniformly for any
;; deref-able. The fallback is weak-KEYED (a `WeakHashMap`) so even a
;; non-`Reaction` orphan is reclaimable: the only path on which a fallback
;; value could strong-ref its key is when a registered callback closes over
;; the keyed object, and the production sub-cache disposal closure
;; (`re-frame.subs`) closes over the reaction — which on the JVM is a
;; `Reaction`, so it takes the on-object storage path, not the fallback.

(defprotocol IDisposeRegistry
  "JVM on-dispose callback storage carried ON the reaction object. The
  CLJS counterpart is `re-frame.disposable/IDisposable`; this is the
  JVM-local equivalent for the plain-atom / SSR / headless host. Per
  rf2-tnnln — keeping callbacks on the object (not a global registry)
  is what makes an un-disposed reaction GC-reclaimable."
  (-add-on-dispose [this f]
    "Append a 0-arg on-dispose callback. Registration order is preserved.")
  (-dispose [this]
    "Clear the callbacks (idempotent — a second call finds none) then
     fire them in registration order. Throwing callbacks are NOT
     swallowed here; the caller owns per-reaction throw containment."))

(deftype Reaction [f ^:unsynchronized-mutable callbacks]
  clojure.lang.IDeref
  (deref [_] (f))
  IDisposeRegistry
  (-add-on-dispose [_ cb]
    (set! callbacks (conj callbacks cb))
    nil)
  (-dispose [_]
    (let [cbs callbacks]
      ;; Clear FIRST so a re-entrant dispose (or a callback that triggers
      ;; another dispose of this same reaction) is a no-op, matching the
      ;; prior dissoc-before-fire discipline.
      (set! callbacks [])
      (doseq [cb cbs] (cb)))))

;; Weak-keyed fallback for deref-ables that are not `Reaction`s (bare
;; reify test doubles, plain atoms). Identity is the natural key; a
;; `WeakHashMap` keys by `.equals`/`.hashCode`, which for these objects
;; is identity. Synchronized for the concurrent SSR / hot-reload paths.
(defonce ^:private ^java.util.Map fallback-on-dispose-callbacks
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn make-reaction
  "On the JVM, return a deref-able that recomputes f on every deref and
  carries its own on-dispose callback storage (per rf2-tnnln)."
  [f]
  (->Reaction f []))

(defn add-on-dispose! [a-ratom f]
  (if (instance? Reaction a-ratom)
    (-add-on-dispose a-ratom f)
    (locking fallback-on-dispose-callbacks
      (let [cbs (.get fallback-on-dispose-callbacks a-ratom)]
        (.put fallback-on-dispose-callbacks a-ratom (conj (or cbs []) f)))))
  nil)

(defn dispose! [a-ratom]
  (if (instance? Reaction a-ratom)
    (-dispose a-ratom)
    (let [callbacks (locking fallback-on-dispose-callbacks
                      (let [cbs (.get fallback-on-dispose-callbacks a-ratom)]
                        (.remove fallback-on-dispose-callbacks a-ratom)
                        cbs))]
      (doseq [f callbacks] (f)))))

(defn reactive?
  "Always true on the JVM (no reagent reactive context to detect)."
  []
  true)

;; ---- timers ---------------------------------------------------------------
;;
;; The JVM uses a real ScheduledExecutorService so that ms-valued delays
;; behave like js/setTimeout — used by `:dispatch-later`, the state-
;; machine `:after` clock, HTTP retry, and the spine dispose drain.
;; When ms = 0 the schedule still goes through the executor but fires
;; effectively immediately.

(defonce ^:private ^ScheduledExecutorService scheduled-executor
  (Executors/newSingleThreadScheduledExecutor))

(defn set-timeout!
  "Schedule f to run after ms milliseconds. Returns an opaque handle
  (a ScheduledFuture on the JVM)."
  [f ms]
  (let [bound-f (bound-fn [] (f))]
    (.schedule scheduled-executor ^Runnable bound-f
               (long (or ms 0)) TimeUnit/MILLISECONDS)))

(defn clear-timeout!
  "Cancel a previously-scheduled timer."
  [handle]
  (when (instance? ScheduledFuture handle)
    (.cancel ^ScheduledFuture handle false))
  nil)

;; Per Spec 005 §Clock abstraction (005:1684-1690): the state-machine
;; `:after` clock surface is named `schedule-after!` / `cancel-scheduled!`
;; / `now-ms` in `re-frame.interop`. The JVM realisation rides the same
;; ScheduledExecutorService the generic `set-timeout!` / `clear-timeout!`
;; use; the distinct names give the machines timer layer the spec-
;; canonical surface without disturbing the generic host-timeout
;; consumers across core.
(def schedule-after!  set-timeout!)
(def cancel-scheduled! clear-timeout!)

;; ---- clock ----------------------------------------------------------------

(defn now-ms []
  (System/currentTimeMillis))

;; ---- queue primitives -----------------------------------------------------

(def empty-queue clojure.lang.PersistentQueue/EMPTY)

;; ---- platform marker ------------------------------------------------------
;;
;; Per Spec 011 §Effect handling on the server, the runtime tracks the
;; active platform (`:server` or `:client`) so `reg-fx`/`reg-cofx`
;; `:platforms` metadata can gate execution. JVM hosts default to
;; `:server`; the public setter `re-frame.core/init-platform` swaps it
;; at boot for the rare reverse case (CLJS-on-Node SSR runtime that wants
;; `:server` semantics, or JVM-runnable tests that simulate `:client`).
;;
;; Per-frame `:config :platform` (set by the `:ssr-server` preset, or any
;; user-supplied frame config) still wins over this host-wide marker —
;; see `re-frame.cofx/active-platform-for-frame` and
;; `re-frame.router/run-fx-effects!`.

(defonce ^:private platform-state (atom :server))

(defn active-platform
  "Return the active platform marker (`:server` on JVM, `:client` on
  CLJS by default). Override at boot with
  `(re-frame.core/init-platform :server)` / `(... :client)` per
  Spec 011 §Effect handling on the server."
  []
  @platform-state)

(defn set-platform!
  "Internal setter. Public callers should use
  `(re-frame.core/init-platform p)` which validates the argument."
  [p]
  (reset! platform-state p))

;; ---- compile-time constants -----------------------------------------------
;;
;; JVM/SSR/headless deployments need an explicit production gate, the
;; JVM-side counterpart to CLJS `goog.DEBUG=false`. Without it the
;; always-dev posture keeps the trace
;; surface live in SSR (per-frame `:trace-cb` listeners, the 200-entry
;; retain-N ring buffer, registry trace emits, source-coord metadata) AND
;; keeps the epoch-history dev surfaces (`restore-epoch`, `reset-frame-db!`,
;; the per-frame ring buffer of `:db-before`/`:db-after`/`:trace-events`)
;; reachable from any in-process code. Those carry secrets that should not
;; live in a production server's heap, crash dumps, or log files.
;;
;; The gate is read ONCE at namespace-load time from two sources, with
;; explicit-off precedence (set `:require`-time, not per-call, so the
;; downstream `when interop/debug-enabled?` checks stay branchless on the
;; hot path):
;;
;;   1. Java system property `re-frame.debug` — set with
;;      `-Dre-frame.debug=false` on the JVM command line. The lowercase
;;      dotted form matches the conventional JVM system-property style.
;;
;;   2. Environment variable `RE_FRAME_DEBUG` — set in the process
;;      environment, conventional uppercase + underscores. Same semantic
;;      as the system property; the system property wins on conflict.
;;
;; Both sources accept the conventional false-y vocabulary:
;;
;;   "false" / "0" / "no" / "off" / "" (empty)
;;
;; case-insensitively. Anything else — including absent / unset — leaves
;; the flag at its default (`true`, dev-on, matching the historical
;; behaviour). Setting the flag to a recognised false-y value disables
;; trace emission, trace-buffer retention, epoch-history capture,
;; `restore-epoch`, `reset-frame-db!`, and the source-coord trace
;; enrichment — the same surfaces that Closure DCE elides in CLJS
;; `:advanced` + `goog.DEBUG=false` builds.
;;
;; This is the SSR-mode production switch. Local dev / tests leave it
;; alone (the default is `true`).

(defn- read-debug-flag
  "Read the JVM debug gate. System property `re-frame.debug` wins; falls
  back to env var `RE_FRAME_DEBUG`. A nil / absent reading returns
  `true` (dev default). A reading from the conventional false-y
  vocabulary returns `false`; any other value returns `true`."
  []
  (let [raw (or (System/getProperty "re-frame.debug")
                (System/getenv "RE_FRAME_DEBUG"))]
    (if (nil? raw)
      true
      (let [v (-> raw str str/trim str/lower-case)]
        (not (contains? #{"false" "0" "no" "off" ""} v))))))

(def debug-enabled?
  "JVM-side dev/prod gate. True by default; explicitly disabled by
  `-Dre-frame.debug=false` or `RE_FRAME_DEBUG=false` (and the
  conventional false-y vocabulary: `0`, `no`, `off`, empty string).
  The JVM counterpart to CLJS `goog.DEBUG`. Read once at namespace
  load — set the property / env var BEFORE `re-frame.interop` loads."
  (read-debug-flag))

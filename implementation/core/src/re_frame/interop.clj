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

(defonce ^:private on-dispose-callbacks (atom {}))

(defn make-reaction
  "On the JVM, return a deref-able that recomputes f on every deref."
  [f]
  (reify clojure.lang.IDeref
    (deref [_] (f))))

(defn add-on-dispose! [a-ratom f]
  (swap! on-dispose-callbacks update a-ratom (fnil conj []) f)
  nil)

(defn dispose! [a-ratom]
  (let [callbacks (get @on-dispose-callbacks a-ratom)]
    (swap! on-dispose-callbacks dissoc a-ratom)
    (doseq [f callbacks] (f))))

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

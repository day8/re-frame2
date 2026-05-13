(ns re-frame.interop
  "JVM host primitives. Used for the plain-atom (headless / SSR / test) adapter.

  Mirrors the .cljs counterpart's surface; some operations are no-ops or
  simplified because the JVM has no DOM, no Reagent, no animation frame.
  See Spec 011 §JVM-runnable view rendering and Spec 008 §JVM-runnable
  test suites."
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
;; behave like js/setTimeout — required by the per-frame sub-cache's
;; grace-period disposal (per Spec 006 §Reference counting and disposal,
;; rf2-s9dn). When ms = 0 the schedule still goes through the executor
;; but fires effectively immediately.

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

;; ---- clock ----------------------------------------------------------------

(defn now-ms []
  (System/currentTimeMillis))

;; ---- queue primitives -----------------------------------------------------

(def empty-queue clojure.lang.PersistentQueue/EMPTY)

;; ---- platform marker ------------------------------------------------------

(def platform :server)

;; ---- compile-time constants -----------------------------------------------

(def debug-enabled? true)

(ns re-frame.interop
  "CLJS host primitives. Everything platform-specific lives here.
  See Spec 002 §Interop layer and Spec 005 §Interop layer.

  Reactive-substrate surfaces (`ratom`, `ratom?`, `make-reaction`,
  `add-on-dispose!`, `dispose!`, `reactive?`, `after-render`) dispatch
  through the late-bind hook table so adapter swap-in works without
  hard-coupling this ns to any substrate's protocols. Each adapter
  publishes its impls at ns-load time under the `:adapter/*` hook keys.
  Hook installation routes through `(substrate-adapter/current-adapter)`
  so test bundles loading multiple adapters stay honest about which
  substrate is active."
  (:require [goog.async.nextTick]
            [re-frame.late-bind :as late-bind]))

;; ---- next-tick scheduling -------------------------------------------------

(def next-tick goog.async.nextTick)

;; ---- adapter-published reactive hooks -------------------------------------
;;
;; The seven `:adapter/*` reactive hooks below are published once per
;; loaded React-shaped adapter at ns-load time (see Spec 006 §Substrate
;; adapter contract and rf2-jicu2). They are read on every render /
;; subscribe / reaction tear-down — sticky-cache via
;; `late-bind/get-fn-cached` (rf2-f72pd) so the resolution is one
;; per-key atom slot rather than a `@hooks` map walk per call.

;; ---- after-render hook ----------------------------------------------------

(defn after-render
  "Schedule f to run after the next render. Returns nil when no adapter
  has registered the `:adapter/after-render` hook."
  [f]
  (when-let [hook (late-bind/get-fn-cached :adapter/after-render)]
    (hook f)))

;; ---- mutable cells (used by the runtime, opaque to user code) -------------

(defn ratom
  "Construct a reactive atom seeded with v."
  [v]
  (when-let [hook (late-bind/get-fn-cached :adapter/ratom)]
    (hook v)))

(defn ratom?
  "True if x is a reactive atom (per the active adapter's substrate).
  Returns false when no adapter has registered the hook."
  [x]
  (if-let [hook (late-bind/get-fn-cached :adapter/ratom?)]
    (hook x)
    false))

;; ---- reactions ------------------------------------------------------------

(defn make-reaction
  "Build a reaction that recomputes f when its dependencies change."
  [f]
  (when-let [hook (late-bind/get-fn-cached :adapter/make-reaction)]
    (hook f)))

(defn add-on-dispose!
  "Register a teardown callback on a-ratom."
  [a-ratom f]
  (when-let [hook (late-bind/get-fn-cached :adapter/add-on-dispose!)]
    (hook a-ratom f)))

(defn dispose!
  "Tear down a reactive atom / reaction."
  [a-ratom]
  (when-let [hook (late-bind/get-fn-cached :adapter/dispose!)]
    (hook a-ratom)))

(defn reactive?
  "True when called from inside a reactive context (e.g. a render).
  Returns false when no adapter has registered the hook."
  []
  (if-let [hook (late-bind/get-fn-cached :adapter/reactive?)]
    (hook)
    false))

;; ---- timers ---------------------------------------------------------------
;; Timers are a platform primitive, not substrate-reactive — call
;; `js/setTimeout` / `js/clearTimeout` directly rather than routing
;; through the late-bind hook table like the reactive surfaces above.

(defn set-timeout!
  "Schedule f to run after ms milliseconds. Returns an opaque handle."
  [f ms]
  (js/setTimeout f ms))

(defn clear-timeout!
  "Cancel a previously-scheduled timer."
  [handle]
  (js/clearTimeout handle))

;; Per Spec 005 §Clock abstraction (005:1684-1690): the state-machine
;; `:after` clock surface is named `schedule-after!` / `cancel-scheduled!`
;; / `now-ms` in `re-frame.interop`. These are the spec-canonical names
;; the machines timer layer (`re-frame.machines.timer`) reaches for; the
;; generic `set-timeout!` / `clear-timeout!` above stay the host-timeout
;; primitive every other core surface (`:dispatch-later`, the sub-cache
;; grace timer, the spine dispose timer, HTTP retry) uses. Same
;; setTimeout / clearTimeout realisation; distinct, intention-named
;; surfaces so an AI re-implementing the machines clock from Spec 005
;; finds the names the spec promises.
(def schedule-after!  set-timeout!)
(def cancel-scheduled! clear-timeout!)

;; ---- clock ----------------------------------------------------------------

(defn now-ms []
  (if (and (exists? js/performance) (exists? js/performance.now))
    (js/performance.now)
    (js/Date.now)))

;; ---- queue primitives -----------------------------------------------------

(def empty-queue #queue [])

;; ---- platform marker ------------------------------------------------------
;;
;; Per Spec 011 §Effect handling on the server, the runtime tracks the
;; active platform (`:server` or `:client`) so `reg-fx`/`reg-cofx`
;; `:platforms` metadata can gate execution. CLJS hosts default to
;; `:client`; the public setter `re-frame.core/init-platform` swaps it
;; at boot for the CLJS-on-Node SSR case (a CLJS bundle whose `:target`
;; isn't `:browser` and that wants `:server` fx semantics).
;;
;; Per-frame `:config :platform` (set by the `:ssr-server` preset, or any
;; user-supplied frame config) still wins over this host-wide marker.

(defonce ^:private platform-state (atom :client))

(defn active-platform
  "Return the active platform marker (`:client` on CLJS by default).
  Override at boot with `(re-frame.core/init-platform :server)` per
  Spec 011 §Effect handling on the server."
  []
  @platform-state)

(defn set-platform!
  "Internal setter. Public callers should use
  `(re-frame.core/init-platform p)` which validates the argument."
  [p]
  (reset! platform-state p))

;; ---- compile-time constants -----------------------------------------------

(def ^boolean debug-enabled? "@define {boolean}" ^boolean goog/DEBUG)

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
            [re-frame.late-bind :as rf.late-bind]))

;; ---- next-tick scheduling -------------------------------------------------
;;
;; The router schedules its drain through `next-tick`. On CLJS this is
;; `goog.async.nextTick`, a **macrotask** (a next-turn TASK), NOT a microtask.
;; It fires after the current synchronous stack unwinds AND after the host has
;; drained its microtask checkpoint, then yields to the event loop so rendering
;; can interleave between drains. That not-a-microtask boundary is the whole
;; guarantee, and it is deliberate: the UI-render tear-correction flush is the
;; one that rides a true microtask (`js/queueMicrotask`) — see Spec 006 §Render
;; batching and Spec 002 §Drain scheduling. Keep the two boundaries distinct.
;;
;; NOT guaranteed: which task mechanism runs the callback. Closure prefers a
;; native `setImmediate` where one exists (Node), else a `MessageChannel`/
;; `postMessage` emulation, and falls back to `setTimeout(cb, 0)` where neither
;; is available (see goog/async/nexttick.js). So the drain usually escapes the
;; nested-`setTimeout` ≥4ms clamp, but that is a property of the host's
;; available mechanisms, not a promise — assume no lower bound on drain latency.

(def next-tick
  "Schedule f as a next-turn TASK (macrotask) via `goog.async.nextTick`.
  Fires after the current synchronous stack AND the host microtask checkpoint,
  yielding to the event loop between drains — deliberately NOT a microtask.
  The underlying mechanism (`setImmediate` / `MessageChannel` / a `setTimeout`
  fallback) is host-dependent and promises no latency bound.
  See Spec 002 §Drain scheduling."
  goog.async.nextTick)

;; ---- adapter-published reactive hooks -------------------------------------
;;
;; The seven `:adapter/*` reactive hooks below are published once per
;; loaded React-shaped adapter at ns-load time (see Spec 006 §Substrate
;; adapter contract and rf2-jicu2). They are read on every render /
;; subscribe / reaction tear-down — sticky-cache via
;; `rf.late-bind/get-fn-cached` (rf2-f72pd) so the resolution is one
;; per-key atom slot rather than a `@hooks` map walk per call.

;; ---- after-render hook ----------------------------------------------------

(defn after-render
  "Schedule f to run after the next render. Returns nil when no adapter
  has registered the `:adapter/after-render` hook."
  [f]
  (when-let [hook (rf.late-bind/get-fn-cached :adapter/after-render)]
    (hook f)))

;; ---- mutable cells (used by the runtime, opaque to user code) -------------

(defn ratom
  "Construct a reactive atom seeded with v."
  [v]
  (when-let [hook (rf.late-bind/get-fn-cached :adapter/ratom)]
    (hook v)))

(defn ratom?
  "True if x is a reactive atom (per the active adapter's substrate).
  Returns false when no adapter has registered the hook."
  [x]
  (if-let [hook (rf.late-bind/get-fn-cached :adapter/ratom?)]
    (hook x)
    false))

;; ---- reactions ------------------------------------------------------------

(defn make-reaction
  "Build a reaction that recomputes f when its dependencies change."
  [f]
  (when-let [hook (rf.late-bind/get-fn-cached :adapter/make-reaction)]
    (hook f)))

(defn activate-derived-value!
  "Put a `make-derived-value` result on the substrate's PUSH path — i.e.
  make it actually notify its watches when a source moves (rf2-8cnxg /
  rf2-jt8vz).

  Spec 006 §`make-derived-value` says the derived container \"updates
  automatically when any source's value changes\" and that
  `subscribe-container` works on it as on a base container. On the
  React-hook spine that is true from birth: `make-derived-value-fn` wires one
  watch per source AT CONSTRUCTION. The ratom family is DEMAND-driven
  instead: `reagent.ratom/Reaction` captures its sources only through
  `deref-capture`, and a plain `deref` taken OUTSIDE `*ratom-context*` (with
  no `auto-run`) runs the body raw and leaves `watching` nil — a reaction
  that is watchable, and watched, and notifies nobody. A Reagent COMPONENT
  never notices because its render IS the capture context; a compiled
  ViewCell is not a component, so nothing supplies it.

  This op is that missing capture, and it is the ONLY thing the ratom family
  needs to satisfy the spec's push clause: once the sources are captured, a
  write reaches `_handle-change` → the reaction enqueues itself → the
  substrate's ordinary batched flush recomputes and notifies. Nothing here
  makes a subscription EAGER: activation happens when an observer attaches,
  not at construction, so a sub nothing observes is untouched, and
  the change path stays batched (`:auto-run true` — the one-line \"fix\" —
  would instead recompute every Reagent-hosted subscription synchronously
  inside the app-db `reset!`).

  Idempotent and total: adapters whose derived values are already push-based
  (the React-hook spine: UIx) publish no hook
  and the call is a no-op; the ratom impls no-op on an already-activated
  reaction and on any container that is not one of their derived values."
  [derived]
  (when-let [hook (rf.late-bind/get-fn-cached :adapter/activate-derived-value!)]
    (hook derived))
  nil)

(defn add-on-dispose!
  "Register a teardown callback on a-ratom."
  [a-ratom f]
  (when-let [hook (rf.late-bind/get-fn-cached :adapter/add-on-dispose!)]
    (hook a-ratom f)))

(defn dispose!
  "Tear down a reactive atom / reaction."
  [a-ratom]
  (when-let [hook (rf.late-bind/get-fn-cached :adapter/dispose!)]
    (hook a-ratom)))

(defn reactive?
  "True when called from inside a reactive context (e.g. a render).
  Returns false when no adapter has registered the hook."
  []
  (if-let [hook (rf.late-bind/get-fn-cached :adapter/reactive?)]
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
;; primitive every other core surface (`:dispatch-later`, HTTP retry)
;; uses. Same setTimeout / clearTimeout realisation; distinct,
;; intention-named surfaces so an AI re-implementing the machines clock
;; from Spec 005 finds the names the spec promises.
(def schedule-after!  set-timeout!)
(def cancel-scheduled! clear-timeout!)

;; ---- clock ----------------------------------------------------------------

(defn now-ms
  "Monotonic-ish high-resolution clock for ELAPSED measurement (trace / perf
  timing spans, machines timer relative delays). On CLJS this is
  `performance.now()` when available — a high-resolution timestamp relative
  to the page/worker origin, NOT epoch wall-clock. Do NOT use it for durable
  wall-clock facts; use `epoch-now-ms` (rf2-n1rh0f / EP-0010 §Time)."
  []
  (if (and (exists? js/performance) (exists? js/performance.now))
    (js/performance.now)
    (js/Date.now)))

(defn epoch-now-ms
  "Wall-clock epoch milliseconds. The canonical source for EP-0010 durable
  causal time (`:rf.cofx` `:rf/time-ms`, EP-0010 §Time — \"wall-clock
  epoch milliseconds\"). DISTINCT from `now-ms`: that is `performance.now()`
  (origin-relative, for elapsed measurement) on CLJS, which is NOT comparable
  with `js/Date`-based freshness checks. A durable timestamp folded from this
  value is wall-clock-meaningful and serializable across hosts."
  []
  (js/Date.now))

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

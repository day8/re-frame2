(ns day8.re-frame2-xray.install
  "Load-inert install primitives shared by manual and preload startup.

  Requiring this namespace performs no installation. `core/init!` calls
  these functions explicitly; `preload` calls them from its dev-only boot
  block. This separation lets a manual host require and configure Xray
  before any listeners, browser globals, keybindings, or mounts exist.

  The helpers register the trace and epoch collectors and expose the
  browser launch API. Each operation is idempotent; the browser export is
  a no-op without `js/window`, and the preload owns the debug-build gate."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; Compile-time anchor. Both collectors attach through the
            ;; `rf/register-listener!` facade, so no home-namespace alias
            ;; is needed; this bare require exists to LOAD the epoch
            ;; PRODUCER on every startup path. The facade reaches that
            ;; producer through a late-bind hook an unloaded namespace
            ;; never populates, so manual `core/init!` startup (which
            ;; requires this namespace but not `preload`) would otherwise
            ;; register a silent no-op. `preload.cljs` uses the same
            ;; bare-require shape.
            [re-frame.epoch]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- registrations -------------------------------------------------------

(defonce ^:private trace-cb-registered?
  ;; Idempotency sentinel for the trace-callback registration. Cf.
  ;; `rf/register-listener!`: passing the same id twice
  ;; replaces the callback. The replacement is harmless but emits a
  ;; warning trace on every reload that pollutes the dev console;
  ;; this sentinel suppresses re-registration on `:after-load`.
  (atom false))

(defonce ^:private epoch-cb-registered?
  ;; The Time Travel panel needs one per-settle pump into Xray's app-db.
  (atom false))

(defn register-trace-collector!
  "Register Xray's trace-collector callback under
  `:rf.xray/trace-collector`. Idempotent via the
  `trace-cb-registered?` sentinel — a second call is a silent no-op
  (no warning trace, no replacement). Public so tests can drive
  it directly without `#'`-piercing into private vars."
  []
  (when (compare-and-set! trace-cb-registered? false true)
    (rf/register-listener! :trace :rf.xray/trace-collector
                           trace-collector/collect-trace!))
  nil)

;; ---- task-coalesced epoch pump (rf2-chs7) --------------------------------
;;
;; The framework records an epoch per event run-to-completion, on EVERY
;; frame. Pre-rf2-chs7 each one cost a full dispatch round-trip into
;; `:rf/xray`, and the handler it reaches is a cheap conditional re-read
;; — so for every frame that is not the current target the round-trip
;; computed the same db value it already had.
;;
;; Under load that is not merely wasteful, it is destructive. A host
;; burst produces epochs faster than `:rf/xray`'s own drain settles, so
;; the queue passes the depth-100 cap and the router DROPS events with
;; `:recovery :no-recovery`. The events it drops are whatever happens to
;; be behind the flood: measured on the Story feature-load gate, an
;; unrelated Xray chrome event (`:rf.xray.edn-inspector/clear-width`)
;; was the casualty. The flood costs Xray its OWN UI events.
;;
;; The remedy is the one already in the sibling stream:
;; `trace-collector/request-mirror-sync!` (rf2-wq6gx) coalesces the
;; trace mirror onto a single `next-tick` task "so the sub fires on
;; every push without one dispatch per trace event". `epoch-recorded`
;; never got the same treatment; it does now, with the same primitive
;; and the same two-atom shape.
;;
;; What coalescing preserves: the pending set is keyed by FRAME-ID, and
;; the drain dispatches one `:rf.xray/epoch-recorded` per DISTINCT
;; frame. So the event's arg keeps its meaning ("this frame recorded"),
;; the handler keeps its target comparison, and a burst of N epochs on
;; one frame collapses to one dispatch instead of N. Frame count is
;; small and bounded where epoch count is neither.
;;
;; `rf.interop/next-tick` runs the drain asynchronously AS A TASK —
;; never a microtask, never inline. Coalescing is correct on every task
;; mechanism Closure may pick: a later boundary merges MORE records,
;; never fewer.

(defonce ^:private pending-epoch-frames
  ;; Frame-ids that have recorded an epoch since the last drain. A SET,
  ;; which is the whole coalescing mechanism.
  (atom #{}))

(defonce ^:private epoch-sync-scheduled?
  ;; `compare-and-set!` sentinel — `true` while a drain task is queued
  ;; and not yet run; reset to `false` immediately before the task reads
  ;; the pending set, so a record arriving after the read enqueues a
  ;; fresh task rather than merging silently into one already in flight.
  ;; Mirrors `trace-collector/mirror-sync-scheduled?`.
  (atom false))

(defn drain-epoch-frames!
  "Dispatch one `:rf.xray/epoch-recorded` per DISTINCT frame-id that has
  recorded an epoch since the last drain, then clear the pending set.

  PRE-MOUNT / POST-TEARDOWN NO-OP: when the `:rf/xray` frame is not
  registered the pending set is cleared and nothing is dispatched —
  first mount seeds history from the framework ring, so records produced
  during that window are still visible. The liveness check is taken HERE,
  at the instant of dispatch, rather than when the record arrived: the
  frame can be seated or torn down in between, and the dispatch is what
  cares. Resolved against the framework's frame registry (NOT a
  Xray-side flag) so a teardown / re-register cycle stays correctly
  tracked without our needing extra state.

  Returns the set of frame-ids dispatched (empty on a no-op). Public so
  tests can drive the drain deterministically without waiting on
  `next-tick` — the same posture `trace-collector/refresh-trace-rings!`
  keeps for the trace mirror."
  []
  (let [frames (first (reset-vals! pending-epoch-frames #{}))]
    (if (rf.frame/frame defaults/default-frame-id)
      (do
        ;; Wrap the dispatch in the Xray shell frame so the registry's
        ;; handler writes to Xray's app-db, not the host's.
        (rf/with-frame defaults/default-frame-id
          (doseq [frame-id frames]
            (rf/dispatch [:rf.xray/epoch-recorded frame-id])))
        frames)
      #{})))

(defn note-epoch-recorded!
  "Record that `frame-id` produced an epoch, and schedule the coalesced
  drain if one is not already queued. The epoch-listener body, lifted to
  a named fn so tests drive the coalescing seam directly rather than
  reaching into the framework's epoch-listener registry.

  Returns nothing."
  [frame-id]
  (swap! pending-epoch-frames conj frame-id)
  (when (compare-and-set! epoch-sync-scheduled? false true)
    (rf.interop/next-tick
      (fn []
        (reset! epoch-sync-scheduled? false)
        (drain-epoch-frames!))))
  nil)

(defn register-epoch-collector!
  "Register Xray's epoch-settle pump under `:rf.xray/epoch-collector`.

  On every dequeued event's settle the framework's epoch artefact fires
  this callback with the assembled `:rf/epoch-record` (one record per
  event's run-to-completion, not per drain-settle). The cb notes the
  record's frame and requests a task-coalesced drain, which dispatches
  `:rf.xray/epoch-recorded` into the `:rf/xray` frame so the registry's
  event handler re-reads `rf/epoch-history` and pumps the fresh snapshot
  into Xray's app-db. The scrubber's `:rf.xray/epoch-history` sub then
  re-fires off the standard app-db-write reactive path.

  ONE DISPATCH PER FRAME PER TASK, not one per epoch (rf2-chs7) — see
  the §task-coalesced epoch pump block above for why the un-coalesced
  form overflowed `:rf/xray`'s own queue.

  Idempotent via the `epoch-cb-registered?` sentinel."
  []
  (when (compare-and-set! epoch-cb-registered? false true)
    (rf/register-listener! :epoch :rf.xray/epoch-collector
      (fn [record]
        (note-epoch-recorded! (:frame record)))))
  nil)

(defn reset-for-test!
  "Reset the install helpers' idempotency sentinels + the epoch
  coalescer's pending state so test fixtures can drive multiple load
  cycles. Test-only — never call from production code."
  []
  (reset! trace-cb-registered? false)
  (reset! epoch-cb-registered? false)
  (reset! pending-epoch-frames #{})
  (reset! epoch-sync-scheduled? false)
  nil)

;; ---- public browser API exports -----------------------------------------

(defn- ensure-js-object!
  [parent key]
  (or (gobj/get parent key)
      (let [obj #js {}]
        (gobj/set parent key obj)
        obj)))

(defn- install-api-on!
  [obj]
  (gobj/set obj "open_BANG_" mount/open!)
  (gobj/set obj "open_overlay_BANG_" mount/open-overlay!)
  (gobj/set obj "close_BANG_" mount/close!)
  (gobj/set obj "toggle_BANG_" mount/toggle!)
  (gobj/set obj "popout_BANG_" mount/popout!)
  (gobj/set obj "status" mount/status)
  nil)

(defn install-browser-api-exports!
  "Expose the dev-only Xray launch API on the browser global object.

  The preload is the namespace shadow-cljs actually loads into host
  dev bundles; the facade namespace (`day8.re-frame2-xray.core`) may
  be absent from apps that only install Xray via `:devtools/preloads`.
  Export on `window.day8.re_frame2_xray` for preload-only bundles, and
  augment `window.day8.re_frame2_xray.core` only when Closure has
  already created that real namespace object. Never pre-create `core`:
  doing so races `goog.provide` in browser-test and fails with
  \"Namespace already declared\"."
  []
  (when (exists? js/window)
    (let [day8  (ensure-js-object! js/window "day8")
          xray (ensure-js-object! day8 "re_frame2_xray")]
      (install-api-on! xray)
      (when-let [core (gobj/get xray "core")]
        (install-api-on! core))))
  nil)

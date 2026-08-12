(ns day8.re-frame2-xray.panels.machine-after-rings-tick-loop-cljs-test
  "Off-render `tick-loop!` regression coverage (rf2-nms77h).

  Sibling to `machine_after_rings_cljs_test.cljs`. Lives in a SEPARATE ns
  so the `use-fixtures` map shape `cljs.test/async` requires does not
  conflict with the fn-form `test-support/make-reset-runtime-fixture`
  the existing after-rings tests use — mirrors the split
  `settings/popup_dispatch_routing_cljs_test.cljs` uses for the same
  reason.

  ## The bug this test defends against

  `tick-loop!` is the rAF/next-tick callback that drives the countdown
  rings' sweep. It runs OFF-render — by the time it actually fires, any
  `with-frame` / Provider scope active when `kick-tick!` armed it has
  already unwound (both rAF and `next-tick` resume on a LATER TASK —
  neither is a microtask and neither runs inline — so the callback
  always fires after the synchronous call that scheduled it returns). Before the fix its two internal reads (the active-timers sub
  + the scrubber sub) used the ambient 1-arity `rf/subscribe`, which has
  NO `:rf/default` floor and raises `:rf.error/no-frame-context` with no
  scope established — the FIRST expression in the loop's body, so it
  threw before `:rf.xray/timer-tick` was ever dispatched. The loop's
  defensive `catch` swallowed that error every iteration and set
  `:running? false`, so `:rings/now-ms` never advanced and the ring
  painted once but never swept — silently, no console error. The fix
  reads via the explicit-frame `(rf/subscribe query-v {:frame frame})`
  opts form, targeting the frame captured in `tick-state` at
  `kick-tick!` time.

  ## Why this test invokes the private `tick-loop!` directly

  Calling it outside `rf/with-frame` reproduces the EXACT off-render
  condition deterministically (no waiting on the real rAF/next-tick
  queue to invoke the callback itself). Its OWN `:rf.xray/timer-tick`
  dispatch is still the real async `rf/dispatch` (not `dispatch-sync`),
  so the test awaits the router drain via `test-support/poll-until`
  under `cljs.test/async`, matching this codebase's standard pattern for
  asserting on a queued (non-sync) dispatch."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]))

;; `make-xray-runtime-fixture` (rf2-vj80u8) composes core
;; `make-reset-runtime-fixture` (snapshot/restore + frames-reset + adapter
;; dispose/install) with Xray's own reset tier. `:tier :all` folds the sentinel +
;; trace-collector rings reset; `:async? true` is the map-form cljs.test/async
;; requires; `:post-reset` re-registers Xray's :rf.xray/* handlers + the panel
;; test-override seams + the :rf/xray frame (rolled back with the per-test
;; registrar snapshot). A composed fixture brackets `after-rings/stop-tick!`
;; before AND after each test — the off-render `tick-loop!` is an rAF loop the
;; core runtime reset does not cancel, so a stale tick from one test must not
;; survive into the next.
(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :all
     :async?     true
     :post-reset (fn []
                   (registry/register-xray-handlers!)
                   (xray-test-support/install-test-overrides!)
                   (rf/make-frame {:id :rf/xray}))})
  {:before (fn [] (after-rings/stop-tick!))
   :after  (fn [] (after-rings/stop-tick!))})

;; ---- fixture data (mirrors machine_after_rings_cljs_test.cljs) --------

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on    {:start :authing}
                       :after {5000 :timeout}}
             :authing {:on {:ok :done}}
             :timeout {:on {:retry :idle}}
             :done    {:final? true}}})

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.xray/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.xray/set-machine-definitions-override-for-test definitions]))

(defn- push-scheduled!
  [id machine-id state delay epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id machine-id
            :state state
            :delay delay
            :delay-source :literal
            :epoch epoch}}))

(defn- pin-now-ms! [ms]
  (rf/dispatch-sync [:rf.xray/set-now-ms-override-for-test ms]))

;; ---- rf2-nms77h — off-render tick-loop! dispatches with no ambient frame --

(deftest tick-loop-runs-off-render-without-ambient-frame-context
  (async done
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (push-scheduled! 1000 :auth/login :idle 5000 0)
      ;; `kick-tick!` captures `:rf/xray` as the surrounding instance
      ;; frame (mirrors the overlay reg-view's `rf/current-frame-id`
      ;; capture at render time) into `tick-state`.
      (after-rings/kick-tick! :rf/xray))
    (is (nil? (:rings/now-ms (frame/frame-app-db-value :rf/xray)))
        "sanity: nothing has bumped now-ms yet")
    ;; Runs OUTSIDE `rf/with-frame` — the off-render condition the bug
    ;; hits.
    (#'day8.re-frame2-xray.panels.machine-after-rings/tick-loop!)
    (-> (test-support/poll-until
          #(pos? (or (:rings/now-ms (frame/frame-app-db-value :rf/xray)) 0))
          {:label "tick-loop! dispatch drained onto :rf/xray"})
        (.then (fn [_]
                 (is (pos? (:rings/now-ms (frame/frame-app-db-value :rf/xray)))
                     "the loop's :rf.xray/timer-tick dispatch landed on the
                      captured :rf/xray frame — now-ms was bumped, proving
                      the two internal reads did NOT hit
                      :rf.error/no-frame-context (pre-fix this slot stayed
                      nil forever: the throw happened before dispatch, and
                      the catch swallowed it silently every iteration)")))
        (.catch (fn [e]
                  (is false
                      (str "tick-loop! never dispatched :rf.xray/timer-tick "
                           "onto :rf/xray — " (.-message e)))
                  nil))
        (.then (fn [_] (done))))))

;; ---- rf2-e64drj — the rAF loop is MOUNT-gated, not only DATA-gated --------
;;
;; Pre-fix `tick-loop!` re-scheduled itself whenever `needs-ticking?` was true
;; (an armed `:after` timer + a `:present` scrubber), regardless of whether the
;; `AfterRingsOverlay` was still mounted — so switching to another L3 tab left
;; the loop dispatching `:rf.xray/timer-tick` ~60×/s in the background,
;; outliving its panel. The overlay now owns a `:mounted?` liveness flag
;; (`kick-tick!` sets it, the `overlay-ref!` React ref callback clears it on
;; unmount); `tick-loop!` requires it before dispatching / re-scheduling.

(deftest tick-loop-mount-gate-stops-loop-after-unmount
  ;; Stub `js/requestAnimationFrame` to a no-op capture so a reschedule is a
  ;; deterministic drop (no async next-tick racing the synchronous
  ;; assertions); we assert on `tick-state`, not the real rAF queue.
  (let [had? (exists? js/requestAnimationFrame)
        orig (when had? js/requestAnimationFrame)
        ts   @#'day8.re-frame2-xray.panels.machine-after-rings/tick-state]
    (set! js/requestAnimationFrame (fn [_f] 0))
    (try
      (rf/with-frame :rf/xray
        (override-machines!    [:auth/login])
        (override-definitions! {:auth/login fixture-definition})
        (pin-now-ms! 2000)                          ; now (2000) < fires-at (6000) ⇒ armed
        (push-scheduled! 1000 :auth/login :idle 5000 0)
        (rf/dispatch-sync [:rf.xray/set-scrubber-position :present])
        ;; the mounted overlay's render arms the loop.
        (after-rings/kick-tick! :rf/xray))
      (is (true? (:running? @ts)) "sanity: kick-tick! armed the loop")
      (is (true? (:mounted? @ts)) "sanity: kick-tick! stamped the overlay live")
      ;; `needs-ticking?` is TRUE here (armed timer + :present scrubber), so
      ;; PRE-FIX `tick-loop!` would re-arm regardless of mount. Simulate the
      ;; overlay UNMOUNTING — React fires the ref with nil.
      (#'day8.re-frame2-xray.panels.machine-after-rings/overlay-ref! nil)
      (is (false? (:mounted? @ts)) "the ref-callback recorded the unmount")
      (#'day8.re-frame2-xray.panels.machine-after-rings/tick-loop!)
      (is (false? (:running? @ts))
          "rf2-e64drj: unmounting AfterRingsOverlay stops the rAF loop within
           one frame even though the :after timer is still armed + the scrubber
           sits at :present (pre-fix the data-gated loop kept re-arming)")
      (is (nil? (:rings/now-ms (frame/frame-app-db-value :rf/xray)))
          "the unmounted iteration dispatched NO :rf.xray/timer-tick")
      ;; A remount (the overlay's next render) re-arms the loop.
      (rf/with-frame :rf/xray (after-rings/kick-tick! :rf/xray))
      (is (true? (:mounted? @ts)) "remount re-stamps the overlay live")
      (is (true? (:running? @ts)) "remount re-arms the loop via kick-tick!")
      (finally
        (set! js/requestAnimationFrame (if had? orig js/undefined))
        (after-rings/stop-tick!)
        (swap! ts assoc :mounted? false)))))

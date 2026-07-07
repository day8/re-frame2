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
  already unwound (a rAF/next-tick callback always resumes on a LATER
  macro/microtask, after the synchronous call that scheduled it
  returns). Before the fix its two internal reads (the active-timers sub
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
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]))

;; ---- fixture (map shape per cljs.test/async requirement) ---------------

(def ^:private fixture-snap (atom nil))

(defn- setup-runtime! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!)
  (after-rings/stop-tick!)
  ;; Snapshot the framework registrar so we can restore it post-test,
  ;; matching the body of `test-support/make-reset-runtime-fixture`.
  (reset! fixture-snap (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (frame/reg-frame :rf/xray {}))

(defn- teardown-runtime! []
  (after-rings/stop-tick!)
  (when-let [snap @fixture-snap]
    (test-support/restore-registrar! snap)
    (reset! fixture-snap nil))
  (reset! frame/frames {}))

(use-fixtures :each
  {:before setup-runtime!
   :after  teardown-runtime!})

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
                      the catch swallowed it silently every iteration)")
                 (done)))
        (.catch (fn [e]
                  (is false
                      (str "tick-loop! never dispatched :rf.xray/timer-tick "
                           "onto :rf/xray — " (.-message e)))
                  (done))))))

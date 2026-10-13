(ns day8.re-frame2-xray.epoch-pump-coalescing-cljs-test
  "Xray's epoch pump is TASK-COALESCED: one
  `:rf.xray/epoch-recorded` dispatch per distinct frame per drain, not one
  per settled epoch.

  ## The defect this pins

  The framework records an epoch per event run-to-completion, on every frame.
  Dispatching per record would cost a full round-trip into `:rf/xray` each
  time, and the handler it reaches is a cheap conditional re-read — so for
  every frame that is not the current target the round-trip would compute
  the same db value it already had.

  Under load that waste scales with epoch count. Each pump dispatch roots a
  family of its own on `:rf/xray`'s external lane, so `:drain-depth` never
  halts a burst of them, and the cost is one `:rf/xray` event per epoch.

  The sibling stream follows the same pattern:
  `trace-collector/request-mirror-sync!` coalesces the trace mirror
  onto one `next-tick` task.

  ## Why the assertions read the ROUTER QUEUE

  The dispatches are the cost, so they are what these deftests measure. `rf/dispatch`
  enqueues and schedules an async drain, so the queue read taken immediately
  after a drain call sees exactly what the pump contributed and nothing else.
  A test that only asserted the drain's return value would be pinning the coalescer's
  bookkeeping rather than the behaviour at risk. A non-zero queue read is also
  what shows the burst really travelled the seam rather than going nowhere.

  Node-test (`npm run test:cljs`) — no DOM, no browser."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.install :as install]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier :runtime :post-reset mount/teardown!}))

(def ^:private burst-size 40)

(defn- seat-xray! []
  (registry/register-xray-handlers!)
  (config/set-auto-open! false)
  (mount/boot-on-runtime-ready!))

(defn- xray-queue-depth
  "How many envelopes are sitting undrained in `:rf/xray`'s external lane
  (the router's `:queue`), which is where every pump dispatch lands."
  []
  (count (:queue @(:router (rf.frame/frame :rf/xray)))))

;; ---- the regression ------------------------------------------------------

(deftest the-drain-dispatches-once-per-distinct-frame
  (testing "40 epochs per frame recorded between two drains enqueue ONE
            `:rf.xray/epoch-recorded` per frame, not one per record.
            Coalescing is keyed by FRAME-ID, so the event's arg means `this
            frame recorded` and the handler's target comparison needs no
            change. Frame count is small and bounded where epoch count is
            neither, so this is the axis it is safe to fan out on. The drain
            takes-and-clears: a leaked pending set would turn the coalescer
            into a permanent one-dispatch-per-tick-per-frame-ever-seen pump,
            which is the same defect at a lower constant."
    (seat-xray!)
    (dotimes [_ burst-size]
      (install/note-epoch-recorded! :app/main)
      (install/note-epoch-recorded! :app/sidebar))
    (is (zero? (xray-queue-depth))
        "noting a record does not itself enqueue — the dispatch is deferred
         to the coalesced drain")
    (is (= #{:app/main :app/sidebar} (install/drain-epoch-frames!))
        "both frames are represented exactly once")
    (is (= 2 (xray-queue-depth))
        (str (* 2 burst-size) " records across two frames cost TWO queue "
             "slots, one per frame"))
    (is (= #{} (install/drain-epoch-frames!))
        "a second drain with nothing pending dispatches nothing")
    (is (= 2 (xray-queue-depth))
        "and enqueues nothing further")))

;; ---- the pre-mount case --------------------------------------------------

(deftest an-unseated-xray-frame-drains-to-nothing
  (testing "the liveness check is taken at the instant of DISPATCH
            rather than when the record arrived, because the frame can be
            seated or torn down in between and the dispatch is what cares.
            With `:rf/xray` absent the pending set is cleared and nothing is
            dispatched — first mount seeds history from the framework ring, so
            records produced in that window are still visible."
    (registry/register-xray-handlers!)
    (is (nil? (rf.frame/frame :rf/xray))
        "precondition: Xray's frame is unseated")
    (dotimes [_ burst-size]
      (install/note-epoch-recorded! :app/main))
    (is (= #{} (install/drain-epoch-frames!))
        "the drain is a no-op while the frame is absent")
    (seat-xray!)
    (install/drain-epoch-frames!)
    (is (zero? (xray-queue-depth))
        "and the pre-mount records were cleared, not replayed on the seat")))

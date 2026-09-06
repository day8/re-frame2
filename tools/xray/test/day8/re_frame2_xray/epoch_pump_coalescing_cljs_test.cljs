(ns day8.re-frame2-xray.epoch-pump-coalescing-cljs-test
  "rf2-chs7 — Xray's epoch pump is TASK-COALESCED: one
  `:rf.xray/epoch-recorded` dispatch per distinct frame per drain, not one
  per settled epoch.

  ## The defect this pins

  The framework records an epoch per event run-to-completion, on every frame.
  Pre-fix each one cost a full dispatch round-trip into `:rf/xray`, and the
  handler it reaches is a cheap conditional re-read — so for every frame that
  is not the current target the round-trip computed the same db value it
  already had.

  Under load that is not merely wasteful. Measured on
  `npm run test:story-feature-load`, a 20-event host burst produced epochs
  faster than `:rf/xray`'s own drain settled, the queue passed the router's
  depth-100 cap, and the router discarded events with `:recovery
  :no-recovery`. One captured record shows 101 queued `:rf.xray/epoch-recorded`;
  a second shows the casualty being an unrelated Xray CHROME event
  (`:rf.xray.edn-inspector/clear-width`) sitting behind the flood. The pump was
  costing the inspector its own UI events.

  The remedy follows the precedent already in the sibling stream:
  `trace-collector/request-mirror-sync!` (rf2-wq6gx) coalesces the trace mirror
  onto one `next-tick` task.

  ## Why the assertions read the ROUTER QUEUE

  Queue depth is the defect, so it is what these deftests measure. `rf/dispatch`
  enqueues and schedules an async drain, so the queue read taken immediately
  after a drain call sees exactly what the pump contributed and nothing else —
  which is the number the router's depth cap was counting. A test that only
  asserted the drain's return value would be pinning the fix's bookkeeping
  rather than the behaviour that broke.

  `control-…` is what makes the rest non-vacuous: it drives the SAME 40 records
  through the same seam on a frame that is not the target, and shows the queue
  still holding exactly one dispatch. Without coalescing that read would be 40
  on both, so a green here means the mechanism fired rather than that the
  events went nowhere.

  Node-test (`npm run test:cljs`) — no DOM, no browser."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
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
  "How many envelopes are sitting in `:rf/xray`'s router queue, undrained.
  This is the quantity the router's depth cap counts and the quantity the
  un-coalesced pump drove past 100."
  []
  (count (:queue @(:router (rf.frame/frame :rf/xray)))))

;; ---- the regression ------------------------------------------------------

(deftest a-same-tick-epoch-burst-collapses-to-one-dispatch
  (testing "rf2-chs7 — 40 epochs recorded on ONE frame between two drains
            enqueue ONE `:rf.xray/epoch-recorded`, not 40. Pre-fix the
            listener dispatched per record and this read 40 — four such
            bursts is what carried `:rf/xray`'s own queue past the router's
            depth-100 cap."
    (seat-xray!)
    (is (zero? (xray-queue-depth))
        "precondition: nothing else is queued on Xray's frame")
    (dotimes [_ burst-size]
      (install/note-epoch-recorded! :app/main))
    (is (zero? (xray-queue-depth))
        "noting a record does not itself enqueue — the dispatch is deferred
         to the coalesced drain")
    (is (= #{:app/main} (install/drain-epoch-frames!))
        "the drain reports the one distinct frame it dispatched for")
    (is (= 1 (xray-queue-depth))
        (str burst-size " epoch records on one frame cost ONE queue slot"))))

(deftest the-drain-dispatches-once-per-distinct-frame
  (testing "rf2-chs7 — coalescing is keyed by FRAME-ID, so the event's arg
            keeps its meaning (`this frame recorded`) and the handler keeps
            its unchanged target comparison. Frame count is small and bounded
            where epoch count is neither, so this is the axis it is safe to
            fan out on."
    (seat-xray!)
    (dotimes [_ burst-size]
      (install/note-epoch-recorded! :app/main)
      (install/note-epoch-recorded! :app/sidebar))
    (is (= #{:app/main :app/sidebar} (install/drain-epoch-frames!))
        "both frames are represented exactly once")
    (is (= 2 (xray-queue-depth))
        (str (* 2 burst-size) " records across two frames cost TWO queue "
             "slots, one per frame"))))

;; ---- the control — the burst really did travel this seam -----------------

(deftest control-a-non-target-frames-burst-also-collapses
  (testing "rf2-chs7 — the same 40 records on a frame that is NOT the target
            also enqueue one dispatch. This is the control for the regression
            above: it drives the identical burst through the identical seam,
            so a queue depth of 1 there cannot be explained by the records
            having gone nowhere. It is also the wasteful case the coalescer
            most obviously pays for — the handler's re-read is a no-op for a
            non-target frame, so pre-fix these were 40 round-trips computing
            the db value Xray already had."
    (seat-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :app/main]))
    (dotimes [_ burst-size]
      (install/note-epoch-recorded! :app/somewhere-else))
    (is (= #{:app/somewhere-else} (install/drain-epoch-frames!)))
    (is (= 1 (xray-queue-depth))
        "one queue slot for a burst the handler will decline")))

;; ---- the pending set is cleared, and the pre-mount case ------------------

(deftest a-drain-clears-the-pending-set

  (testing "rf2-chs7 — the drain takes-and-clears, so a frame that recorded
            once is not re-dispatched on every later drain. A leaked pending
            set would turn the coalescer into a permanent one-dispatch-per-
            tick-per-frame-ever-seen pump, which is the same defect at a
            lower constant."
    (seat-xray!)
    (install/note-epoch-recorded! :app/main)
    (is (= #{:app/main} (install/drain-epoch-frames!)))
    (is (= #{} (install/drain-epoch-frames!))
        "a second drain with nothing pending dispatches nothing")
    (is (= 1 (xray-queue-depth))
        "and enqueues nothing further")))

(deftest an-unseated-xray-frame-drains-to-nothing
  (testing "rf2-chs7 — the liveness check is taken at the instant of DISPATCH
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
    (is (= #{} (install/drain-epoch-frames!))
        "and the pre-mount records were cleared, not replayed on the seat")
    (is (zero? (xray-queue-depth))
        "so nothing reaches the queue")))

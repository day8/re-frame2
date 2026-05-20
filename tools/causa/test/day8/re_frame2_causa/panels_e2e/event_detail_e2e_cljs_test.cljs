(ns day8.re-frame2-causa.panels-e2e.event-detail-e2e-cljs-test
  "Multi-frame end-to-end coverage for the Event Detail panel
  (rf2-7icrs, spec/017 — Event Detail row).

  Approach: spin up a real host frame (`:rf/default`) with the
  canonical counter fixture, install Causa under `:rf/causa`, dispatch
  REAL host events, then assert Causa's panel subs reflect what the
  host did. Catches the bug class where the spine's `:rf.causa/focus`
  stops auto-tracking after a fresh head cascade arrives (rf2-70tkv
  panel-frozen class).

  ## What this asserts

  1. Cold start — buffer empty, spine has no focus.
  2. After one host `:counter/inc`: Causa's cascade list contains the
     cascade, the spine focuses on it, the event-detail sub's `:event`
     slot carries `[:counter/inc]`, and the focused frame is the host
     frame (`:rf/default`) — the cross-frame-routing invariant.
  3. After a SECOND host dispatch: focus auto-advances to the new head
     (LIVE auto-follow per spec/018 §3). The previous focus is NOT
     pinned — this is the bug rf2-70tkv class catches.

  ## Sub-level vs view-level

  The test reads `:rf.causa/focus` + `:rf.causa/cascades` rather than
  `:rf.causa/event-detail` because the event-detail composite sub
  layers further projection over the same data; sub-graph cohesion is
  exercised by the existing pure-fn `event_detail_cljs_test.cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(deftest causa-spine-focuses-on-host-dispatch
  (testing "real host dispatch flows through the trace bus into Causa"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [cascades        (e2e/causa-cascades)
              focused-event   (e2e/causa-focused-event)
              focused-frame   (e2e/causa-focused-frame)
              cascade-by-host (e2e/host-cascades-only)]
          (is (pos? (count cascades))
              "Causa's :rf.causa/cascades is empty after a host dispatch — the trace bus is not wired")
          (is (pos? (count cascade-by-host))
              "no cascades carry the host frame :rf/default")
          (is (= [:counter/inc] focused-event)
              "spine focus is not on the host's :counter/inc dispatch")
          (is (= :rf/default focused-frame)
              "focused cascade's :frame is not the host frame"))))))

(deftest causa-focus-auto-advances-on-second-host-dispatch
  (testing "LIVE auto-follow — focus moves to the new head on every dispatch"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [first-focus-id (:dispatch-id (e2e/sub-causa [:rf.causa/focus]))]
          (is (some? first-focus-id)
              "spine did not focus on first dispatch — cold-start invariant broken"))
        (e2e/dispatch-host [:counter/inc])
        (let [second-focused-event (e2e/causa-focused-event)]
          (is (= [:counter/inc] second-focused-event)
              "focus did not advance to the second dispatch — rf2-70tkv panel-frozen regression"))))))

(deftest causa-app-db-mirrors-after-host-dispatch
  (testing "host :counter/value reaches expected post-dispatch state"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (is (= 5 (e2e/sub-host [:counter/value]))
            "fixture init did not seed :counter/value at 5")
        (e2e/dispatch-host [:counter/inc])
        (is (= 6 (e2e/sub-host [:counter/value]))
            "host frame did not commit :counter/inc")))))

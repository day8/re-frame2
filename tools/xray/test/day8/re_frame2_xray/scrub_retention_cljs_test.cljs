(ns day8.re-frame2-xray.scrub-retention-cljs-test
  "rf2-kuky.54 — Xray's user-facing clear is a DATA clear, not a fixture reset.

  ## The defect this pins

  `trace-collector/retroactive-scrub!` backs three user-reachable paths —
  the Settings popup's \"Clear buffer now\", the palette's
  `:clear-trace-buffer` command, and the automatic reveal → redact privacy
  narrowing. Its first act used to be
  `re-frame.trace.tooling/clear-trace-rings!`, the FIXTURE-grade reset,
  which does three things: drops every frame's ring, resets the
  process-default `:events-retained` back to the built-in 50, and clears the
  hot-reload registration dedup table.

  The middle one is the bug. The Buffer tab writes that very knob
  (`(rf/configure! {:trace-buffer {:events-retained N}})`), so pressing the
  button beside it silently reverted the user's own setting — and the ring's
  retention has no reader, so nothing showed them it had happened.

  The framework had the right verb all along: `clear-trace-buffer!` empties a
  ring at its own effective cap and preserves the `:override?` flag
  (rf2-va65k). rf2-kuky.54 gave it a 0-arity all-frames case and pointed the
  scrub at it.

  ## Why these assertions

  Retention is observable only through a ring's behaviour, so the test drives
  the cap: configure 3, scrub, dispatch 5, count 3. Pre-fix that read 5, the
  built-in default of 50 having been restored under the user.

  `scrub-still-empties-the-rings` is what keeps the first test honest. A
  scrub that had quietly become a no-op would satisfy \"retention survives\"
  vacuously while breaking the Spec 009 §Privacy §Retroactive-scrub contract
  the scrub exists to serve, so the privacy half is pinned alongside it.

  Node-test (`npm run test:cljs`) — no DOM, no browser."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

(def ^:private probe-frame :xray-scrub-test/probe)

(defn- seed-frame! []
  (rf/make-frame {:id probe-frame :doc "retention probe"})
  (rf/reg-event :xray-scrub-test/ping (fn [{:keys [db]} _] {:db db})))

(defn- ping! [n]
  (dotimes [_ n] (rf/dispatch-sync [:xray-scrub-test/ping] {:frame probe-frame})))

;; ---- the regression ------------------------------------------------------

(deftest scrub-preserves-configured-trace-retention
  (testing "retroactive-scrub! clears retained events without reverting the
            user's configured retention (rf2-kuky.54)"
    (rf/configure! {:trace-buffer {:events-retained 3}})
    (seed-frame!)
    (ping! 5)
    (is (= 3 (count (rf/trace-buffer probe-frame)))
        "precondition: the configured cap of 3 is in force")

    (trace-collector/retroactive-scrub!)

    (ping! 5)
    (is (= 3 (count (rf/trace-buffer probe-frame)))
        "the configured :events-retained 3 survived the scrub — pre-fix this
         read 5, the built-in default of 50 having been restored")))

(deftest scrub-preserves-a-per-frame-override
  (testing "a frame's explicit :rf.trace/events-retained override also survives"
    (rf/reg-event :xray-scrub-test/ping (fn [{:keys [db]} _] {:db db}))
    (rf/make-frame {:id :xray-scrub-test/pinned :rf.trace/events-retained 2
                    :doc "explicit per-frame override"})
    (dotimes [_ 5] (rf/dispatch-sync [:xray-scrub-test/ping]
                                     {:frame :xray-scrub-test/pinned}))
    (is (= 2 (count (rf/trace-buffer :xray-scrub-test/pinned)))
        "precondition: the override caps at 2")

    (trace-collector/retroactive-scrub!)

    (dotimes [_ 5] (rf/dispatch-sync [:xray-scrub-test/ping]
                                     {:frame :xray-scrub-test/pinned}))
    (is (= 2 (count (rf/trace-buffer :xray-scrub-test/pinned)))
        "the per-frame override survived the scrub")))

;; ---- the control: the scrub still does its actual job --------------------

(deftest scrub-still-empties-the-rings
  (testing "the privacy contract is unchanged — every place trace data lives
            is still emptied (Spec 009 §Privacy §Retroactive-scrub)"
    (rf/configure! {:trace-buffer {:events-retained 3}})
    (seed-frame!)
    (ping! 2)
    (is (seq (rf/trace-buffer probe-frame))
        "precondition: the framework ring holds events")

    (trace-collector/retroactive-scrub!)

    (is (= [] (rf/trace-buffer probe-frame))
        "the framework's per-frame ring is emptied")
    (is (zero? (count (trace-collector/buffer-for-test)))
        "and so is the snapshot every Xray consumer reads")))

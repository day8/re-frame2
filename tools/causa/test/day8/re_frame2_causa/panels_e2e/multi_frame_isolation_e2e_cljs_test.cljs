(ns day8.re-frame2-causa.panels-e2e.multi-frame-isolation-e2e-cljs-test
  "Multi-frame e2e port of the Causa feature-matrix Playwright scenario
  `multi-frame isolation substrate` (rf2-rviu8). The Playwright original
  (`scenarios.cjs::runMultiFrame`) opened the multi-frame testbed in a
  browser, clicked `inc-A`, `inc-B`, and `cross-bump`, then asserted:

    1. Frame isolation — `inc-A` only bumps `:counter/a`'s `:n`,
       `inc-B` only bumps `:counter/b`'s `:n`, and `:log/entries`
       stays empty.
    2. Cross-frame fan-out — `cross-bump` (dispatched against
       `:counter/a`) lands a `::inc` in `:counter/b` AND a
       `::log-append` in `:log` via a fx-driven cross-frame dispatch.
    3. Causa surfaces all three frames in `:rf.causa/cascades` with
       distinct `:frame` tags.
    4. Selecting a `:counter/b`-tagged cascade by dispatch-id flips
       Causa's focused cascade off the head and onto the chosen row.

  At the data layer this is pure ClojureScript — three frames in one
  Node CLJS process, real `rf/dispatch` (not synthetic injection),
  trace bus + epoch capture as in production. The browser carried no
  extra signal — the DOM was a single counter per frame and a log
  list; the assertions all read off epoch-history + cascade subs.

  ## Bugs this catches

  - rf2-83d4x / rf2-dodq2 — cross-frame dispatch missing `:frame` opt
    drops the cascade onto the wrong frame.
  - rf2-hwuki — `:frame` tag missing on emitted trace events filters
    out of epoch capture; Causa sees no transitions.
  - rf2-ulpp8 — `:focus :frame` filter slot mismatch lets phantom
    cascades from other frames bleed into the L2 list."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.multi-frame
             :as mf]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(deftest direct-A-and-B-stay-isolated
  (testing "inc-A bumps A only; inc-B bumps B only; log stays empty"
    (e2e/with-host-and-causa-frames
      {:install-host mf/install-and-init!}
      (fn []
        (e2e/dispatch-host [::mf/inc] {:frame mf/frame-a})
        (e2e/dispatch-host [::mf/inc] {:frame mf/frame-b})
        (let [a-n     (e2e/sub-host [::mf/n] {:frame mf/frame-a})
              b-n     (e2e/sub-host [::mf/n] {:frame mf/frame-b})
              entries (e2e/sub-host [::mf/entries] {:frame mf/frame-log})]
          (is (= 1 a-n)
              ":counter/a got the wrong number of ::inc dispatches")
          (is (= 1 b-n)
              ":counter/b got the wrong number of ::inc dispatches")
          (is (= [] entries)
              ":log saw entries before any cross-bump — isolation broke"))))))

(deftest cross-bump-fans-out-into-B-and-log
  (testing "cross-bump from A bumps B and appends to log via fx-driven dispatch"
    (e2e/with-host-and-causa-frames
      {:install-host mf/install-and-init!}
      (fn []
        (e2e/dispatch-host [::mf/cross-bump] {:frame mf/frame-a})
        (let [a-n     (e2e/sub-host [::mf/n] {:frame mf/frame-a})
              b-n     (e2e/sub-host [::mf/n] {:frame mf/frame-b})
              entries (e2e/sub-host [::mf/entries] {:frame mf/frame-log})]
          (is (= 1 a-n) ":counter/a should have bumped once (the cross-bump's :db effect)")
          (is (= 1 b-n) ":counter/b did not receive the cross-frame ::inc — :frame opt was dropped")
          (is (= 1 (count entries))
              ":log did not receive the fan-out ::log-append — cross-frame dispatch lost")
          (is (= mf/frame-a (:from (first entries)))
              "log entry's :from frame is wrong — bridge fx mutated the payload"))))))

(deftest causa-cascades-surface-three-distinct-frames
  (testing "Causa sees one cascade per frame after cross-bump (A + B + log)"
    (e2e/with-host-and-causa-frames
      {:install-host mf/install-and-init!}
      (fn []
        (e2e/dispatch-host [::mf/cross-bump] {:frame mf/frame-a})
        (let [cascades  (e2e/causa-cascades)
              frame-ids (set (map :frame cascades))]
          (is (contains? frame-ids mf/frame-a)
              ":counter/a cascade missing from :rf.causa/cascades")
          (is (contains? frame-ids mf/frame-b)
              ":counter/b cascade missing — cross-frame dispatch lost its :frame tag (rf2-83d4x class)")
          (is (contains? frame-ids mf/frame-log)
              ":log cascade missing — fan-out bridge dropped the third frame"))))))

(deftest causa-focused-frame-tracks-host-dispatch-frame
  (testing "focused cascade :frame matches the host dispatch target after re-targeting Causa"
    (e2e/with-host-and-causa-frames
      {:install-host mf/install-and-init!}
      (fn []
        ;; Dispatch into :counter/b directly so a :counter/b cascade
        ;; lands on the bus.
        (e2e/dispatch-host [::mf/inc] {:frame mf/frame-b})
        ;; Re-target Causa onto :counter/b (the analogue of the user
        ;; flipping the frame-picker). The helper's default-install
        ;; seeds :target-frame to :rf/default; picking :counter/b is
        ;; the canonical multi-frame test gesture (see
        ;; parallel_frames_e2e_cljs_test for the same pattern).
        (e2e/dispatch-causa [:rf.causa/set-target-frame mf/frame-b])
        (is (= mf/frame-b (e2e/causa-focused-frame))
            "focused-cascade :frame is not :counter/b after re-targeting Causa onto :counter/b")))))

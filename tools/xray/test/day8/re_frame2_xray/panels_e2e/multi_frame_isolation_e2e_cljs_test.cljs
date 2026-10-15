(ns day8.re-frame2-xray.panels-e2e.multi-frame-isolation-e2e-cljs-test
  "Cross-frame host dispatches retain their frame identity in Xray."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.multi-frame
             :as mf]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest xray-cascades-surface-three-distinct-frames
  (testing "Xray sees one cascade per frame after cross-bump (A + B + log)"
    (e2e/with-host-and-xray-frames
      {:install-host mf/install-and-init!}
      (fn []
        (e2e/dispatch-host [::mf/cross-bump] {:frame mf/frame-a})
        (let [cascades  (e2e/xray-cascades)
              frame-ids (set (map :frame cascades))]
          (is (contains? frame-ids mf/frame-a)
              ":counter/a cascade missing from :rf.xray/event-bundles")
          (is (contains? frame-ids mf/frame-b)
              ":counter/b cascade missing — cross-frame dispatch lost its :frame tag")
          (is (contains? frame-ids mf/frame-log)
              ":log cascade missing — fan-out bridge dropped the third frame"))))))

(deftest xray-focused-frame-tracks-host-dispatch-frame
  (testing "focused cascade :frame matches the host dispatch target after re-targeting Xray"
    (e2e/with-host-and-xray-frames
      {:install-host mf/install-and-init!}
      (fn []
        ;; Dispatch into :counter/b directly so a :counter/b cascade
        ;; lands on the bus.
        (e2e/dispatch-host [::mf/inc] {:frame mf/frame-b})
        ;; Re-target Xray onto :counter/b (the analogue of the user
        ;; flipping the frame-picker). The helper's default-install
        ;; seeds :target-frame to :rf/default; picking :counter/b is
        ;; the canonical multi-frame test gesture (see
        ;; parallel_frames_e2e_cljs_test for the same pattern).
        (e2e/dispatch-xray [:rf.xray/set-target-frame mf/frame-b])
        (is (= mf/frame-b (e2e/xray-focused-frame))
            "focused-event-bundle :frame is not :counter/b after re-targeting Xray onto :counter/b")))))

(ns day8.re-frame2-xray.panels.reactive-panel-frame-resolution-cljs-test
  "The Views panel classifies the host's subs through the OBSERVED frame's
  image, as Frames and the Routes tab do. `:rf.xray/reactive-data` runs
  inside Xray's own generation binding, so a sub-topology read resolved
  with no frame would classify the host's subs by the INSPECTOR's image.
  The rows cover the state the picker leaves (epoch history, no bundles, a
  picked target) and a target with no focus frame beside it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.live-frame :as rf.live-frame]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

(deftest views-topology-resolves-through-the-observed-frame
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id :host/app})
  (rf/reg-event :test/drop-focus-frame
    (fn [{:keys [db]} _] {:db (update db :focus dissoc :frame)}))
  (let [seen (atom [])]
    (with-redefs [rf.live-frame/call-with-frame-resolution
                  (fn [frame f] (swap! seen conj frame) (f))]
      (rf/with-frame :rf/xray
        (testing "a target picked through the picker, with history and no bundles"
          (rf/dispatch-sync [:rf.xray/set-frame :host/app])
          (rf/dispatch-sync [:rf.xray/sync-epoch-history
                             [{:epoch-id :e1 :frame :host/app :sub-runs [] :renders []}]])
          @(rf/subscribe [:rf.xray/reactive-data])
          (is (= :host/app (peek @seen))
              (str "the topology read resolved through " (pr-str (peek @seen)))))
        (testing "a target with no focus frame still resolves through the target"
          (rf/dispatch-sync [:test/drop-focus-frame])
          (reset! seen [])
          @(rf/subscribe [:rf.xray/reactive-data])
          (is (= [:host/app] @seen)
              (str "the topology read resolved through " (pr-str @seen))))))))

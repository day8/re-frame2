(ns day8.re-frame2-xray.panels-e2e.non-trivial-app-db-e2e-cljs-test
  "Distinct host mutations advance Xray selected epochs and current state."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.non-trivial-app-db
             :as nt]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private mutation-events
  "The scenario's 6-click sequence. Each
  event mutates a structurally distinct part of the app-db tree
  (scalar swap / map merge / vector append / vector swap / set add /
  multi-key flip)."
  [[::nt/toggle-theme]
   [::nt/toggle-notifications]
   [::nt/add-cart-item]
   [::nt/bump-first-item-qty]
   [::nt/register-new-sku]
   [::nt/revoke-write-and-collapse-sidebar]])

(deftest xray-app-db-diff-tracks-six-deep-mutations
  (testing "each of the 6 deep-tree mutations advances the spine focus and surfaces in selected-epoch-record"
    (e2e/with-host-and-xray-frames
      {:install-host nt/install-and-init!}
      (fn []
        ;; Walk the 6-event sequence. After each dispatch the spine's
        ;; focused epoch MUST advance to the latest event (the
        ;; panel-frozen class).
        (doseq [event mutation-events]
          (e2e/dispatch-host event)
          (let [record (e2e/sub-xray [:rf.xray/selected-epoch-record])]

            (is (= event (:trigger-event record))
                (str "spine focus did not advance to " (pr-str event)
                     " — panel-frozen regression"))))))))

(deftest xray-app-db-diff-final-state-matches-handler-effects
  (e2e/with-host-and-xray-frames
    {:install-host nt/install-and-init!}
    (fn []
      (doseq [event mutation-events] (e2e/dispatch-host event))
      (let [db (e2e/sub-xray [:rf.xray/target-frame-db])]
        (is (= {:theme :light :items 3 :first-qty 3 :sku? true :write? false}
               {:theme (get-in db [:settings :theme])
                :items (count (get-in db [:cart :items]))
                :first-qty (get-in db [:cart :items 0 :qty])
                :sku? (contains? (get-in db [:catalog :categories :books :groups :tech :skus])
                                 "BK-099")
                :write? (contains? (get-in db [:session :auth :scopes]) :write)}))))))

(deftest xray-epoch-history-records-six-mutations
  (testing ":rf.xray/epoch-history grows by exactly 6 over the 6 dispatches"
    (e2e/with-host-and-xray-frames
      {:install-host nt/install-and-init!}
      (fn []
        (let [before (count (e2e/sub-xray [:rf.xray/epoch-history]))]
          (doseq [event mutation-events]
            (e2e/dispatch-host event))
          (let [after (count (e2e/sub-xray [:rf.xray/epoch-history]))]
            (is (= (+ before 6) after)
                "epoch-history did not record all 6 mutations — :frame tag drop class")))))))

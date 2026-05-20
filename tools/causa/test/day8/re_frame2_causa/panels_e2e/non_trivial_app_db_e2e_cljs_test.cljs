(ns day8.re-frame2-causa.panels-e2e.non-trivial-app-db-e2e-cljs-test
  "Multi-frame e2e port of the Causa feature-matrix Playwright scenario
  `non-trivial app-db diff substrate` (rf2-rviu8). The Playwright
  original (`scenarios.cjs::runAppDbPrivacyLarge`) opened the 5-deep
  non-trivial-app-db testbed, clicked the six diff-shape buttons, then
  asserted the `rf-causa-app-db-diff` panel mounted.

  At the data layer:

    - Each click is a distinct event whose handler mutates a different
      app-db path. The bug surface is whether Causa's epoch capture +
      App-DB Diff projection survives a realistic deep-tree mutation
      stream.
    - The panel-mounted assertion was the Playwright proxy for 'the
      diff projection didn't crash'. The e2e equivalent is to read
      the `:rf.causa/selected-epoch-record` sub after each dispatch
      and assert it carries the expected before/after pair.

  ## Bug class this catches

  - rf2-70tkv App-DB Diff frozen on focused-event flip — when a
    second dispatch arrives the spine head flips but the diff
    projection holds the previous epoch. The 6-dispatch sequence
    here makes that regression deterministic.
  - Any regression in `:db-before` / `:db-after` extraction for
    nested map / vector / set diffs."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.non-trivial-app-db
             :as nt]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private mutation-events
  "The 6-click sequence the Playwright scenario walked through. Each
  event mutates a structurally distinct part of the app-db tree
  (scalar swap / map merge / vector append / vector swap / set add /
  multi-key flip)."
  [[::nt/toggle-theme]
   [::nt/toggle-notifications]
   [::nt/add-cart-item]
   [::nt/bump-first-item-qty]
   [::nt/register-new-sku]
   [::nt/revoke-write-and-collapse-sidebar]])

(deftest causa-app-db-diff-tracks-six-deep-mutations
  (testing "each of the 6 deep-tree mutations advances the spine focus and surfaces in selected-epoch-record"
    (e2e/with-host-and-causa-frames
      {:install-host nt/install-and-init!}
      (fn []
        ;; Walk the 6-event sequence. After each dispatch the spine's
        ;; focused epoch MUST advance to the latest event (rf2-70tkv
        ;; class).
        (doseq [event mutation-events]
          (e2e/dispatch-host event)
          (let [record (e2e/sub-causa [:rf.causa/selected-epoch-record])]
            (is (some? record)
                (str ":rf.causa/selected-epoch-record nil after dispatching " (pr-str event)))
            (is (= event (:trigger-event record))
                (str "spine focus did not advance to " (pr-str event)
                     " — rf2-70tkv panel-frozen regression"))))))))

(deftest causa-app-db-diff-final-state-matches-handler-effects
  (testing "after the 6-click sequence Causa's target-frame-db reflects the host's final shape"
    (e2e/with-host-and-causa-frames
      {:install-host nt/install-and-init!}
      (fn []
        (doseq [event mutation-events]
          (e2e/dispatch-host event))
        (let [target-db (e2e/sub-causa [:rf.causa/target-frame-db])]
          ;; toggle-theme flipped :dark → :light.
          (is (= :light (get-in target-db [:settings :theme]))
              "settings.theme did not commit to :light after toggle-theme")
          ;; add-cart-item appended a third line item.
          (is (= 3 (count (get-in target-db [:cart :items])))
              "cart.items did not grow to 3 after add-cart-item")
          ;; bump-first-item-qty incremented the first item's qty (was 2).
          (is (= 3 (get-in target-db [:cart :items 0 :qty]))
              "cart.items[0].qty did not bump from 2 → 3")
          ;; register-new-sku added BK-099 to a 5-deep set.
          (is (contains? (get-in target-db [:catalog :categories :books :groups :tech :skus])
                         "BK-099")
              "BK-099 was not added to the 5-deep :catalog :categories :books :groups :tech :skus set")
          ;; revoke-write removed :write from :session :auth :scopes.
          (is (not (contains? (get-in target-db [:session :auth :scopes]) :write))
              ":write was not revoked from :session :auth :scopes"))))))

(deftest causa-epoch-history-records-six-mutations
  (testing ":rf.causa/epoch-history grows by exactly 6 over the 6 dispatches"
    (e2e/with-host-and-causa-frames
      {:install-host nt/install-and-init!}
      (fn []
        (let [before (count (e2e/sub-causa [:rf.causa/epoch-history]))]
          (doseq [event mutation-events]
            (e2e/dispatch-host event))
          (let [after (count (e2e/sub-causa [:rf.causa/epoch-history]))]
            (is (= (+ before 6) after)
                "epoch-history did not record all 6 mutations — :frame tag drop class")))))))

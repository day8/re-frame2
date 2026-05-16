(ns cart-total.stories-cljs-test
  "Story tests for the existing Causa cart-total testbed.

  These prove the first Causa-in-Story slice reuses the real cart-total
  app and shared fixtures rather than a parallel toy model."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.async :as async-lib]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.loaders :as loaders]
            [re-frame.test-support :as test-support]
            [cart-total.core]
            [cart-total.fixtures :as fixtures]
            [cart-total.stories :as cart-stories]))

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (frame/ensure-default-frame!)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! assertions/trace-accumulators {})
  (story/clear-all!)
  (cart-stories/register-all!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

(use-fixtures :each {:before before! :after after!})

(deftest cart-total-story-registrations
  (testing "the Causa cart-total story, variants, tag, and workspace register"
    (is (contains? (story/list-tags) :feature/causa-cart-total))
    (is (story/registered? :story :story.causa.cart-total))
    (is (story/registered? :workspace :Workspace.causa.cart-total/debugging-states))
    (let [vs (story/variants-of :story.causa.cart-total)]
      (doseq [vid [:story.causa.cart-total/empty
                   :story.causa.cart-total/seeded-wrong-total
                   :story.causa.cart-total/checkout-snapshot
                   :story.causa.cart-total/live-basket-drift
                   :story.causa.cart-total/discounted-snapshot
                   ;; rf2-5kad2 boundary variants
                   :story.causa.cart-total/empty-after-clear
                   :story.causa.cart-total/stacked-same-item
                   :story.causa.cart-total/all-three-items
                   :story.causa.cart-total/friend-discount-applied
                   :story.causa.cart-total/discount-then-cleared]]
        (is (contains? vs vid) (str vid " registered")))
      (is (= 10 (count vs))))))

(deftest variants-reuse-shared-cart-total-fixtures
  (testing "Story variants are pinned to the shared Causa testbed event fixtures"
    (is (= fixtures/tutorial-seed-events
           (:events (story/variant->edn :story.causa.cart-total/seeded-wrong-total))))
    (is (= fixtures/live-basket-drift-events
           (:events (story/variant->edn :story.causa.cart-total/live-basket-drift))))))

(defn- assert-variant-passes! [done variant-id assert-result!]
  (-> (story/run-variant variant-id)
      (async-lib/then
        (fn [result]
          (is (= :ready (:lifecycle result)) (str variant-id " reached :ready"))
          (assert-result! result)
          (is (story/assertions-passing? result)
              (str variant-id " assertions: " (pr-str (:assertions result))))
          (story/destroy-variant! variant-id)
          (done)))))

(deftest seeded-wrong-total-variant-runs
  (async done
    (assert-variant-passes!
      done
      :story.causa.cart-total/seeded-wrong-total
      (fn [result]
        (is (= 2 (get-in result [:app-db :cart/items 0 :qty])))
        (is (= [] (get-in result [:app-db :checkout/items])))))))

(deftest live-basket-drift-variant-runs
  (async done
    (assert-variant-passes!
      done
      :story.causa.cart-total/live-basket-drift
      (fn [result]
        (is (= :apple (get-in result [:app-db :cart/items 0 :id])))
        (is (= 1 (get-in result [:app-db :cart/items 0 :qty])))
        (is (= 2 (get-in result [:app-db :checkout/items 0 :qty])))))))

(deftest discounted-snapshot-variant-runs
  (async done
    (assert-variant-passes!
      done
      :story.causa.cart-total/discounted-snapshot
      (fn [result]
        (is (= "STUDENT" (get-in result [:app-db :cart/discount :code])))
        (is (= 10 (get-in result [:app-db :cart/discount :percent])))))))

;; rf2-5kad2 boundary variants — at least one runtime assertion per variant
;; to prove the fixture reaches :ready and the assertions land.

(deftest empty-after-clear-variant-runs
  (async done
    (assert-variant-passes!
      done
      :story.causa.cart-total/empty-after-clear
      (fn [result]
        (is (= [] (get-in result [:app-db :cart/items])))
        (is (= [] (get-in result [:app-db :checkout/items])))))))

(deftest stacked-same-item-variant-runs
  (async done
    (assert-variant-passes!
      done
      :story.causa.cart-total/stacked-same-item
      (fn [result]
        (is (= 1 (count (get-in result [:app-db :cart/items]))))
        (is (= 5 (get-in result [:app-db :cart/items 0 :qty])))
        (is (= :apple (get-in result [:app-db :cart/items 0 :id])))))))

(deftest all-three-items-variant-runs
  (async done
    (assert-variant-passes!
      done
      :story.causa.cart-total/all-three-items
      (fn [result]
        (is (= 3 (count (get-in result [:app-db :cart/items]))))
        (is (= [:apple :bread :coffee]
               (mapv :id (get-in result [:app-db :cart/items]))))))))

(deftest friend-discount-applied-variant-runs
  (async done
    (assert-variant-passes!
      done
      :story.causa.cart-total/friend-discount-applied
      (fn [result]
        (is (= "FRIEND" (get-in result [:app-db :cart/discount :code])))
        (is (= 20 (get-in result [:app-db :cart/discount :percent])))))))

(deftest discount-then-cleared-variant-runs
  (async done
    (assert-variant-passes!
      done
      :story.causa.cart-total/discount-then-cleared
      (fn [result]
        (is (nil? (get-in result [:app-db :cart/discount])))
        (is (= 2 (get-in result [:app-db :cart/items 0 :qty])))))))

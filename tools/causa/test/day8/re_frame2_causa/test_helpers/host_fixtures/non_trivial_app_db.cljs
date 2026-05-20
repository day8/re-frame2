(ns day8.re-frame2-causa.test-helpers.host-fixtures.non-trivial-app-db
  "Headless host fixture matching `testbeds/non_trivial_app_db/core.cljs`
  — a realistic 5-deep app-db with 6 distinct diff-shape mutations.

  The point of this fixture is to drive the App-DB Diff panel's
  projection chain against a multi-leaf nested app-db so the e2e suite
  exercises the deep-tree diff path the Playwright scenario originally
  carried. The fixture leaves out the Reagent view layer (which is
  out of scope per the multi-frame e2e finding); the events + subs are
  bytewise-identical to the testbed.

  ## Bug class this catches

  - rf2-70tkv panel-frozen — App-DB Diff sub stops re-firing after
    the second host dispatch arrives; the diff would render the
    pre-dispatch app-db forever.
  - Any regression in `:rf.causa/selected-epoch-record` that
    silently drops the `:db-before` or `:db-after` slot for
    deep-tree mutations."
  (:require [re-frame.core :as rf]))

(def initial-db
  {:user      {:id "user-42"
               :profile {:name  "Ada Lovelace"
                         :email "ada@example.com"
                         :role  :admin}}
   :settings  {:theme :dark
               :locale "en-AU"
               :notifications {:email true :push false :marketing false}
               :feature-flags {:beta? true :new-cart? false :ai-search? true}}
   :cart      {:items [{:sku "BK-001" :title "Refactoring" :qty 2
                        :price {:currency :AUD :amount 42.50}}
                       {:sku "BK-002" :title "Domain Modelling" :qty 1
                        :price {:currency :AUD :amount 35.00}}]
               :total 120.00
               :coupon nil}
   :catalog   {:categories {:books {:groups {:tech {:skus #{"BK-001" "BK-002"}}}}}}
   :session   {:auth {:scopes #{:read :write}}
               :ui   {:sidebar-open? true}}
   :metrics   {:requests 0 :errors 0 :renders 0 :sub-runs 0 :flow-evals 0}})

(rf/reg-event-db ::initialise
  (fn [_db _ev] initial-db))

(rf/reg-event-db ::toggle-theme
  (fn [db _ev]
    (update-in db [:settings :theme] {:dark :light :light :dark})))

(rf/reg-event-db ::toggle-notifications
  (fn [db _ev]
    (update-in db [:settings :notifications]
               (fn [m]
                 (-> m
                     (update :email not)
                     (update :marketing not))))))

(rf/reg-event-db ::add-cart-item
  (fn [db _ev]
    (let [new-item {:sku "BK-099" :title "Patterns of Distributed Systems"
                    :qty 1 :price {:currency :AUD :amount 55.00}}]
      (-> db
          (update-in [:cart :items] (fnil conj []) new-item)
          (update-in [:cart :total] (fnil + 0) 55.00)))))

(rf/reg-event-db ::bump-first-item-qty
  (fn [db _ev]
    (-> db
        (update-in [:cart :items 0 :qty] inc)
        (update-in [:cart :items 0 :price :amount] + 42.50)
        (update-in [:cart :total] + 42.50))))

(rf/reg-event-db ::register-new-sku
  (fn [db _ev]
    (update-in db [:catalog :categories :books :groups :tech :skus]
               (fnil conj #{}) "BK-099")))

(rf/reg-event-db ::revoke-write-and-collapse-sidebar
  (fn [db _ev]
    (-> db
        (update-in [:session :auth :scopes] (fnil disj #{}) :write)
        (update-in [:session :ui :sidebar-open?] not))))

(rf/reg-sub :settings  (fn [db _] (:settings  db)))
(rf/reg-sub :cart      (fn [db _] (:cart      db)))
(rf/reg-sub :catalog   (fn [db _] (:catalog   db)))
(rf/reg-sub :session   (fn [db _] (:session   db)))

(defn install! [] nil)

(defn install-and-init! []
  (rf/dispatch-sync [::initialise])
  nil)

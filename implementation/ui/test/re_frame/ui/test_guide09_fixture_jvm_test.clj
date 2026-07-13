(ns re-frame.ui.test-guide09-fixture-jvm-test
  "Guide 09 §Tier 1 examples as fixtures (07 §7: every guide example
  compiles and runs as a fixture; an example needing internals explained
  is an API defect). The code block + the prose one-liners lift with the
  2026-07-12 respelling — the intent assertion reads through the
  ui.test/attrs projection, never direct keyword lookup on the node.

  The 'drive state with real events' Tier-1 prose loop is adapted to the S1
  structural subset: sub reads land S2, so cart membership arrives as a
  prop; the dispatch! → re-render → assert shape is the guide's. The mounted
  S2 example likewise drives state programmatically; compiled browser event
  dispatch remains explicitly staged at S3."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :refer [defview]]
            [re-frame.ui.test :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Fixture app surface
;; ---------------------------------------------------------------------------

(def fixture-catalog
  {42 {:id 42 :name "Hat" :price 9.99}})

(defn product [id] (get fixture-catalog id))

(defview product-card
  [{:keys [product]}]
  [:button {:on-click [:cart/add (:id product)]} "Add to cart"])

(defview toggle-card
  "The 'assert the button now reads Remove' card — membership is a prop
  at S1 (the structural subset; sub reads land S2)."
  [{:keys [product in-cart?]}]
  (if in-cart?
    [:button {:on-click [:cart/remove (:id product)]} "Remove"]
    [:button {:on-click [:cart/add (:id product)]} "Add to cart"]))

;; ---------------------------------------------------------------------------
;; The guide-09 Tier-1 code block, verbatim shape
;; ---------------------------------------------------------------------------

(deftest add-button-carries-intent
  (let [frame (uit/frame {:app-db {:cart #{} :catalog fixture-catalog}})
        tree  (uit/render [product-card {:product (product 42)}]
                          {:frame frame})]
    (is (= [:cart/add 42] (-> tree (uit/find :button) uit/attrs :on-click)))
    (is (= "Add to cart"  (-> tree (uit/find :button) uit/text)))))

;; "Assert structure and intent, not pixels: … because handlers are event
;; vectors, 'what does this button do' is an equality check."
(deftest intent-assertion-respelled-through-attrs
  (let [tree (uit/render [product-card {:product (product 42)}])]
    (is (= [:cart/add 42]
           (:on-click (uit/attrs (uit/find tree :button))))
        "the tree-contract respelling — same read, prefix form")))

;; "Drive state with real events: (ui.test/dispatch! frame [:cart/add 42]),
;; re-render, assert the button now reads 'Remove'."
(deftest drive-state-with-real-events
  (rf/reg-event :cart/add
    (fn [{:keys [db]} [_ id]] {:db (update db :cart conj id)}))
  (let [frame (uit/frame {:app-db {:cart #{} :catalog fixture-catalog}})]
    (uit/dispatch! frame [:cart/add 42])
    (let [in-cart? (contains? (:cart (rf/app-db-value frame)) 42)
          tree     (uit/render [toggle-card {:product (product 42)
                                             :in-cart? in-cart?}]
                               {:frame frame})]
      (is (= "Remove" (-> tree (uit/find :button) uit/text)))
      (is (= [:cart/remove 42]
             (-> tree (uit/find :button) uit/attrs :on-click))
          "the re-rendered button carries the new intent"))))

;; "Loading and error states are just app-db values you install."
(deftest states-are-app-db-values
  (let [frame (uit/frame {:app-db {:cart #{42}}})]
    (is (= #{42} (:cart (rf/app-db-value frame)))
        "install a state, render against it — no mocking layer")))

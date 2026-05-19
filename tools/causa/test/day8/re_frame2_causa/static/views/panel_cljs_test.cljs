(ns day8.re-frame2-causa.static.views.panel-cljs-test
  "CLJS wiring + view tests for the Static Views sub-tab
  (rf2-o5f5f.5)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.views.panel :as panel]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walker ------------------------------------------------------

(declare expand-tree)
(defn- expand-tree [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))
    (vector? tree) (mapv expand-tree tree)
    (seq? tree)    (map expand-tree tree)
    :else          tree))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-tree tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (when-let [tid (:data-testid (second node))]
                    (= 0 (.indexOf tid prefix)))))
           (hiccup-seq tree)))

(defn- setup-causa! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- fixture data -------------------------------------------------------

(def sample-views
  {:user/avatar
   {:doc  "avatar component"
    :file "src/user/avatar.cljs"
    :line 12
    :ns   'user.avatar}
   :cart/checkout-button
   {:doc  "checkout button"
    :file "src/cart/checkout.cljs"
    :line 88
    :ns   'cart.checkout}
   :route/link
   {:doc nil :file nil :line nil}})

;; -------------------------------------------------------------------------
;; (1) pure helpers
;; -------------------------------------------------------------------------

(deftest project-rows-sorts-by-id
  (let [rows (panel/project-rows sample-views)]
    (is (= [:cart/checkout-button :route/link :user/avatar]
           (mapv :id rows))
        "sorted alphabetically by pr-str of id")))

(deftest project-rows-keeps-doc-and-source-coord
  (let [rows  (panel/project-rows sample-views)
        avatar (some #(when (= :user/avatar (:id %)) %) rows)]
    (is (= "avatar component" (:doc avatar)))
    (is (= "src/user/avatar.cljs" (-> avatar :source-coord :file)))))

(deftest filter-rows-substring
  (let [rows (panel/project-rows sample-views)]
    (is (= rows (panel/filter-rows rows nil)))
    (is (= 1 (count (panel/filter-rows rows "avatar"))))
    (is (= 1 (count (panel/filter-rows rows "checkout"))))
    (is (= 0 (count (panel/filter-rows rows "nope"))))))

(deftest project-data-shape
  (let [data (panel/project-data sample-views nil)]
    (is (= 3 (:total data)))
    (is (false? (:silent? data)))
    (is (true? (:silent? (panel/project-data {} nil))))))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(deftest install-registers-subs
  (setup-causa!)
  (rf/with-frame :rf/causa
    (is (nil? @(rf/subscribe [:rf.causa.static.views/query]))
        "query slot defaults nil")))

(deftest set-query-writes-the-slot
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa.static.views/set-query "avatar"])
    (is (= "avatar" @(rf/subscribe [:rf.causa.static.views/query])))))

(deftest registry-override-feeds-the-composite
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.views/set-registry-override-for-test
       sample-views])
    (let [data @(rf/subscribe [:rf.causa.static.views/tab-data])]
      (is (= 3 (:total data)))
      (is (false? (:silent? data))))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.views/set-registry-override-for-test {}])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-views-empty"))))))

(deftest panel-renders-rows-from-override
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.views/set-registry-override-for-test
       sample-views])
    (let [tree (panel/Panel)
          rows (find-all-by-testid-prefix tree "rf-causa-static-views-row-")]
      (is (= 3 (count rows)) "three row surfaces rendered"))))

(deftest panel-renders-filtered-state-on-no-match
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.views/set-registry-override-for-test
       sample-views])
    (rf/dispatch-sync [:rf.causa.static.views/set-query "no-such-view"])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-views-empty-filtered"))))))

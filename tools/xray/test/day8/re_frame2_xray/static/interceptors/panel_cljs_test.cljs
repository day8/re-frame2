(ns day8.re-frame2-xray.static.interceptors.panel-cljs-test
  "CLJS wiring + view tests for the Static Interceptors sub-tab
  (rf2-o5f5f.6)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.interceptors.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

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

(defn- setup-xray! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- fixture data -------------------------------------------------------

(def sample-events-with-chains
  {:counter/inc
   {:event/kind   :db
    :interceptors [{:id :my/logging :before identity}
                   {:id :rf/db-handler :rf/default? true :before identity}]}

   :user/save
   {:event/kind   :fx
    :interceptors [{:id :my/logging :before identity}
                   {:id :rf/path    :before identity}
                   {:id :rf/fx-handler :rf/default? true :before identity}]}

   :anon/no-chain
   {:event/kind   :db
    :interceptors []}})

;; -------------------------------------------------------------------------
;; (1) pure helpers
;; -------------------------------------------------------------------------

(deftest collect-interceptors-collapses-by-id
  (let [rows (panel/collect-interceptors sample-events-with-chains)
        by-id (into {} (map (juxt :id identity) rows))]
    ;; 4 distinct interceptors: :my/logging, :rf/db-handler,
    ;; :rf/path, :rf/fx-handler
    (is (= 4 (count rows)))
    (is (= 2 (get-in by-id [:my/logging :chain-count]))
        ":my/logging appears on 2 chains")
    (is (= 1 (get-in by-id [:rf/path :chain-count]))
        ":rf/path appears on 1 chain")))

(deftest collect-interceptors-flags-default-marker
  (let [rows  (panel/collect-interceptors sample-events-with-chains)
        by-id (into {} (map (juxt :id identity) rows))]
    (is (true?  (get-in by-id [:rf/db-handler :default?]))
        "rf/db-handler is framework-default")
    (is (true?  (get-in by-id [:rf/fx-handler :default?]))
        "rf/fx-handler is framework-default")
    (is (false? (get-in by-id [:my/logging :default?]))
        "user-attached interceptor is NOT default")))

(deftest collect-interceptors-records-before-after-presence
  (let [rows  (panel/collect-interceptors sample-events-with-chains)
        by-id (into {} (map (juxt :id identity) rows))]
    (is (true?  (get-in by-id [:my/logging :before?])))
    (is (false? (get-in by-id [:my/logging :after?]))
        "no :after fn in the fixture's interceptors")))

(deftest filter-rows-substring
  (let [rows (panel/collect-interceptors sample-events-with-chains)]
    (is (= rows (panel/filter-rows rows nil)))
    (is (= 1 (count (panel/filter-rows rows "logging"))))
    (is (= 0 (count (panel/filter-rows rows "no-such-id"))))))

(deftest project-data-shape
  (let [data (panel/project-data sample-events-with-chains nil)]
    (is (= 4 (:total data)))
    (is (false? (:silent? data)))
    (is (true? (:silent? (panel/project-data {} nil)))
        "no events → silent")))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(deftest install-registers-subs
  (setup-xray!)
  (rf/with-frame :rf/xray
    (is (nil? @(rf/subscribe [:rf.xray.static.interceptors/query]))
        "query slot defaults nil")))

(deftest set-query-writes-the-slot
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.static.interceptors/set-query "logging"])
    (is (= "logging" @(rf/subscribe [:rf.xray.static.interceptors/query])))))

(deftest registry-override-feeds-the-composite
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.interceptors/set-registry-override-for-test
       sample-events-with-chains])
    (let [data @(rf/subscribe [:rf.xray.static.interceptors/tab-data])]
      (is (= 4 (:total data)))
      (is (false? (:silent? data))))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.interceptors/set-registry-override-for-test {}])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-xray-static-interceptors-empty"))))))

(deftest panel-renders-rows-from-override
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.interceptors/set-registry-override-for-test
       sample-events-with-chains])
    (let [tree (panel/Panel)
          rows (find-all-by-testid-prefix tree "rf-xray-static-interceptors-row-")]
      (is (= 4 (count rows)) "four collapsed interceptor rows rendered"))))

(deftest panel-renders-filtered-state-on-no-match
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.interceptors/set-registry-override-for-test
       sample-events-with-chains])
    (rf/dispatch-sync [:rf.xray.static.interceptors/set-query "no-such-id"])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-xray-static-interceptors-empty-filtered"))))))

;; -------------------------------------------------------------------------
;; (4) a11y list semantics (rf2-mq8wk)
;; -------------------------------------------------------------------------

(deftest panel-list-carries-list-semantics
  (testing "rf2-mq8wk — the interceptors <ul> is role=list, rows role=listitem"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.interceptors/set-registry-override-for-test
         sample-events-with-chains])
      (let [tree (panel/Panel)
            list-node (find-by-testid tree "rf-xray-static-interceptors-list")
            rows (find-all-by-testid-prefix
                   tree "rf-xray-static-interceptors-row-")]
        (is (= "list" (:role (second list-node))) "<ul> carries role=list")
        (is (seq rows) "rows rendered")
        (is (every? #(= "listitem" (:role (second %))) rows)
            "every row carries role=listitem")))))

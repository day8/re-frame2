(ns day8.re-frame2-causa.static.flows.panel-cljs-test
  "CLJS wiring + view tests for the Static Flows sub-tab (rf2-uhsqb).

  ## Scope

    1. **Registry wires the Static Flows subs + events** under
       `:rf.causa.static.flows/*`.

    2. **Pure projection** — `project-rows`, `filter-rows`,
       `project-data` cover the flat-list + filter shape with no
       runtime / DOM dependency.

    3. **Silent state** — no flows registered → empty body.

    4. **Flat-list rendering** — every registered flow surfaces as a
       row.

    5. **Search filter** — substring across flow-id + path + doc."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.flows.panel :as panel]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
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

(def sample-flows
  {:rf/default
   {:user/full-name
    {:id     :user/full-name
     :inputs [[:user :first] [:user :last]]
     :output (fn [_] "")
     :path   [:derived :full-name]
     :doc    "concat first + last"}
    :cart/total
    {:id     :cart/total
     :inputs [[:cart :items]]
     :output (fn [_] 0)
     :path   [:cart :total]
     :doc    "sum of cart items"}}})

;; -------------------------------------------------------------------------
;; (1) pure helpers
;; -------------------------------------------------------------------------

(deftest project-rows-flattens-and-sorts
  (testing "project-rows flattens {frame {id flow}} into a sorted row vec"
    (let [rows (panel/project-rows sample-flows)]
      (is (= 2 (count rows)) "two rows")
      (is (= [:cart/total :user/full-name]
             (mapv :flow-id rows))
          "sorted by id ascending")
      (is (every? #(= :rf/default (:frame %)) rows)
          ":frame stamped on every row"))))

(deftest project-rows-empty-snapshot
  (is (= [] (panel/project-rows {}))
      "empty snapshot → empty rows"))

(deftest filter-rows-substring
  (let [rows (panel/project-rows sample-flows)]
    (testing "blank query returns rows verbatim"
      (is (= rows (panel/filter-rows rows nil)))
      (is (= rows (panel/filter-rows rows "")))
      (is (= rows (panel/filter-rows rows "   "))))
    (testing "case-insensitive substring across id"
      (is (= 1 (count (panel/filter-rows rows "CART"))))
      (is (= 1 (count (panel/filter-rows rows "user")))))
    (testing "matches against doc"
      (is (= 1 (count (panel/filter-rows rows "concat")))))
    (testing "no match → empty"
      (is (= 0 (count (panel/filter-rows rows "no-such-thing")))))))

(deftest project-data-shape
  (let [data (panel/project-data sample-flows nil)]
    (testing "silent flag"
      (is (false? (:silent? data)))
      (is (true? (:silent? (panel/project-data {} nil)))))
    (testing "totals + filter flags"
      (is (= 2 (:total data)))
      (is (false? (:filtered? data)))
      (is (true? (:filtered? (panel/project-data sample-flows "cart")))))))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(deftest install-registers-subs
  (testing "register-causa-handlers! installs the static-flows subs + events"
    (setup-causa!)
    (rf/with-frame :rf/causa
      (is (nil? @(rf/subscribe [:rf.causa.static.flows/query]))
          "query slot defaults nil"))))

(deftest set-query-writes-the-slot
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa.static.flows/set-query "cart"])
    (is (= "cart" @(rf/subscribe [:rf.causa.static.flows/query])))
    (rf/dispatch-sync [:rf.causa.static.flows/set-query ""])
    (is (nil? @(rf/subscribe [:rf.causa.static.flows/query]))
        "blank string dissocs the slot")))

(deftest registered-flows-override-feeds-the-composite
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (let [data @(rf/subscribe [:rf.causa.static.flows/tab-data])]
      (is (= 2 (:total data)) "override surfaces two flows")
      (is (false? (:silent? data))))
    (rf/dispatch-sync
      [:rf.causa.static.flows/set-registered-flows-override-for-test nil])))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.flows/set-registered-flows-override-for-test {}])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-flows-empty"))
          "empty-state surface mounts"))))

(deftest panel-renders-rows-from-override
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (let [tree (panel/Panel)
          rows (find-all-by-testid-prefix tree "rf-causa-static-flows-row-")]
      (is (= 2 (count rows)) "two row surfaces rendered")
      (is (some? (find-by-testid tree "rf-causa-static-flows-search"))
          "search box rendered"))))

(deftest panel-renders-filtered-state
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (rf/dispatch-sync [:rf.causa.static.flows/set-query "no-such-flow"])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-flows-empty-filtered"))
          "empty-filtered surface mounts when query removes every row"))))

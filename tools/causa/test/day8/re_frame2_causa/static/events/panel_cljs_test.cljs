(ns day8.re-frame2-causa.static.events.panel-cljs-test
  "CLJS wiring + view tests for the Static Events sub-tab
  (rf2-o5f5f.6)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.events.panel :as panel]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
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

(def sample-events
  {:counter/inc
   {:event/kind :db
    :handler-fn (fn [db _] (update db :n (fnil inc 0)))
    :doc        "increment the counter"
    :interceptors [{:id :rf/db-handler :rf/default? true :before identity}]
    :file       "src/counter/events.cljs"
    :line       12
    :ns         'counter.events}

   :user/save
   {:event/kind :fx
    :handler-fn (fn [_cofx _ev] {:db {:saving? true} :fx []})
    :doc        "persist user"
    :interceptors [{:id :rf/path :before identity}
                   {:id :rf/fx-handler :rf/default? true :before identity}]
    :file       "src/user/events.cljs"
    :line       42
    :ns         'user.events}

   :ctx/touch
   {:event/kind :ctx
    :handler-fn (fn [ctx] (assoc ctx :touched? true))
    :interceptors [{:id :rf/ctx-handler :rf/default? true :before identity}]}})

;; -------------------------------------------------------------------------
;; (1) pure helpers (CLJS-side smoke; the JVM corpus carries the
;; full helpers contract in helpers_test.cljc)
;; -------------------------------------------------------------------------

(deftest project-rows-sorts-by-id
  (let [rows (panel/project-rows sample-events)]
    (is (= [:counter/inc :ctx/touch :user/save]
           (mapv :id rows))
        "sorted alphabetically by pr-str of id")))

(deftest project-rows-carries-kind-doc-and-interceptor-count
  (let [rows (panel/project-rows sample-events)
        save (some #(when (= :user/save (:id %)) %) rows)]
    (is (= :fx (:kind save)))
    (is (= "persist user" (:doc save)))
    (is (= 2 (:interceptor-count save))
        "user/save chain length matches the fixture's two-element vec")))

(deftest filter-rows-substring
  (let [rows (panel/project-rows sample-events)]
    (is (= rows (panel/filter-rows rows nil)))
    (is (= 1 (count (panel/filter-rows rows "counter"))))
    (is (= 1 (count (panel/filter-rows rows "ctx"))))
    (is (= 0 (count (panel/filter-rows rows "no-such-event"))))))

(deftest project-data-shape
  (let [data (panel/project-data sample-events nil nil)]
    (is (= 3 (:total data)))
    (is (false? (:silent? data)))
    (is (true? (:silent? (panel/project-data {} nil nil))))))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(deftest install-registers-subs
  (setup-causa!)
  (rf/with-frame :rf/causa
    (is (nil? @(rf/subscribe [:rf.causa.static.events/query]))
        "query slot defaults nil")
    (is (nil? @(rf/subscribe [:rf.causa.static.events/selected-id]))
        "selected-id slot defaults nil")
    (is (nil? @(rf/subscribe [:rf.causa.static.events/sim-input]))
        "sim-input slot defaults nil")
    (is (nil? @(rf/subscribe [:rf.causa.static.events/sim-result]))
        "sim-result slot defaults nil")))

(deftest set-query-writes-the-slot
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa.static.events/set-query "user"])
    (is (= "user" @(rf/subscribe [:rf.causa.static.events/query])))))

(deftest registry-override-feeds-the-composite
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (let [data @(rf/subscribe [:rf.causa.static.events/tab-data])]
      (is (= 3 (:total data)))
      (is (false? (:silent? data))))))

(deftest select-event-toggles-selection
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (rf/dispatch-sync [:rf.causa.static.events/select :counter/inc])
    (is (= :counter/inc @(rf/subscribe [:rf.causa.static.events/selected-id])))
    (rf/dispatch-sync [:rf.causa.static.events/select :counter/inc])
    (is (nil? @(rf/subscribe [:rf.causa.static.events/selected-id]))
        "clicking the same row clears the selection")
    (rf/dispatch-sync [:rf.causa.static.events/select :counter/inc])
    (rf/dispatch-sync [:rf.causa.static.events/select :user/save])
    (is (= :user/save @(rf/subscribe [:rf.causa.static.events/selected-id]))
        "clicking a different row swaps the selection")))

(deftest select-event-clears-stale-sim-state
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (rf/dispatch-sync [:rf.causa.static.events/select :counter/inc])
    (rf/dispatch-sync [:rf.causa.static.events/set-sim-input "{:a 1}"])
    (rf/dispatch-sync [:rf.causa.static.events/select :user/save])
    (is (nil? @(rf/subscribe [:rf.causa.static.events/sim-input]))
        "swapping selection clears sim-input")
    (is (nil? @(rf/subscribe [:rf.causa.static.events/sim-result]))
        "swapping selection clears sim-result")))

(deftest run-simulate-fires-handler-hermetically
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (rf/dispatch-sync
      [:rf.causa.static.events/run-simulate
       {:event-id :counter/inc :payload-edn nil}])
    (let [result @(rf/subscribe [:rf.causa.static.events/sim-result])]
      (is (true? (:ok? result)))
      (is (= :db (:kind result)))
      (is (= 1 (get-in result [:value :n]))
          "the inc handler ran against the synthetic empty db"))))

(deftest run-simulate-records-parse-error
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (rf/dispatch-sync
      [:rf.causa.static.events/run-simulate
       {:event-id :counter/inc :payload-edn "{unbalanced"}])
    (let [result @(rf/subscribe [:rf.causa.static.events/sim-result])]
      (is (false? (:ok? result)))
      (is (re-find #"Could not parse" (:reason result))))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test {}])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-events-empty"))))))

(deftest panel-renders-rows-from-override
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (let [tree (panel/Panel)
          rows (find-all-by-testid-prefix tree "rf-causa-static-events-row-")]
      (is (= 3 (count rows)) "three row surfaces rendered"))))

(deftest panel-renders-filtered-state-on-no-match
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (rf/dispatch-sync [:rf.causa.static.events/set-query "no-such-event"])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-events-empty-filtered"))))))

(deftest panel-renders-detail-card-when-selected
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (rf/dispatch-sync [:rf.causa.static.events/select :user/save])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-events-detail"))
          "detail card mounts when a row is selected")
      (is (some? (find-by-testid tree "rf-causa-static-events-chain-list"))
          "interceptor chain rendered in the detail card")
      (is (some? (find-by-testid tree "rf-causa-static-events-sim-form"))
          "simulate form rendered in the detail card"))))

(deftest panel-renders-simulate-result-after-run
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.events/set-registry-override-for-test
       sample-events])
    (rf/dispatch-sync [:rf.causa.static.events/select :counter/inc])
    (rf/dispatch-sync
      [:rf.causa.static.events/run-simulate
       {:event-id :counter/inc :payload-edn nil}])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-events-sim-result"))
          "result block renders after run-simulate fires"))))

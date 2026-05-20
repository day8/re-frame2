(ns day8.re-frame2-causa.static.schemas.panel-cljs-test
  "CLJS wiring + view tests for the Static Schemas sub-tab
  (rf2-o5f5f.4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.schemas.panel :as panel]
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

(def sample-registry
  {:schemas-by-frame
   {:rf/default
    {[:user]
     {:schema [:map [:id :int] [:name :string]]
      :doc    "user shape"
      :file   "src/user.cljs"
      :line   12
      :ns     'user}}}
   :events
   {:user/login
    {:spec [:tuple :keyword :string]
     :doc  "login event"
     :file "src/user.cljs"
     :line 42}
    :no-spec/event
    {:doc "no spec — should not surface"}}
   :subs
   {:user/full-name
    {:spec :string
     :doc  "full name sub"
     :file "src/user.cljs"
     :line 88}}})

;; -------------------------------------------------------------------------
;; (1) pure helpers
;; -------------------------------------------------------------------------

(deftest project-app-schema-rows-flattens
  (let [rows (panel/project-app-schema-rows (:schemas-by-frame sample-registry))]
    (is (= 1 (count rows)))
    (let [{:keys [kind id frame schema source-coord]} (first rows)]
      (is (= :app-db kind))
      (is (= [:user] id))
      (is (= :rf/default frame))
      (is (= [:map [:id :int] [:name :string]] schema))
      (is (= "src/user.cljs" (:file source-coord))))))

(deftest project-registrar-rows-keeps-spec-only
  (let [event-rows (panel/project-registrar-rows :event (:events sample-registry))]
    (is (= 1 (count event-rows))
        "entry without :spec is dropped"))
  (let [sub-rows (panel/project-registrar-rows :sub (:subs sample-registry))]
    (is (= 1 (count sub-rows)))
    (is (= :sub (:kind (first sub-rows))))))

(deftest project-rows-combines-and-sorts
  (let [rows (panel/project-rows (:schemas-by-frame sample-registry)
                                 (:events sample-registry)
                                 (:subs   sample-registry))]
    (is (= 3 (count rows))
        "app-db + 1 event + 1 sub = 3 rows")))

(deftest filter-rows-substring
  (let [rows (panel/project-rows (:schemas-by-frame sample-registry)
                                 (:events sample-registry)
                                 (:subs   sample-registry))]
    (is (= rows (panel/filter-rows rows nil)))
    (is (= 1 (count (panel/filter-rows rows "login"))))
    (is (= 0 (count (panel/filter-rows rows "nope"))))))

(deftest project-data-shape
  (let [data (panel/project-data (:schemas-by-frame sample-registry)
                                 (:events sample-registry)
                                 (:subs   sample-registry)
                                 nil)]
    (is (= 3 (:total data)))
    (is (false? (:silent? data)))
    (is (true? (:silent? (panel/project-data {} {} {} nil))))))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(deftest install-registers-subs
  (setup-causa!)
  (rf/with-frame :rf/causa
    (is (nil? @(rf/subscribe [:rf.causa.static.schemas/query]))
        "query slot defaults nil")))

(deftest set-query-writes-the-slot
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa.static.schemas/set-query "user"])
    (is (= "user" @(rf/subscribe [:rf.causa.static.schemas/query])))))

(deftest registry-override-feeds-the-composite
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [data @(rf/subscribe [:rf.causa.static.schemas/tab-data])]
      (is (= 3 (:total data)))
      (is (false? (:silent? data))))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.schemas/set-registry-override-for-test
       {:schemas-by-frame {} :events {} :subs {}}])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-causa-static-schemas-empty"))))))

(deftest panel-renders-rows-from-override
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [tree (panel/Panel)
          rows (find-all-by-testid-prefix tree "rf-causa-static-schemas-row-")]
      (is (= 3 (count rows)) "three row surfaces rendered"))))

(deftest panel-renders-jump-to-source-chips
  (setup-causa!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [tree (panel/Panel)
          chips (find-all-by-testid-prefix tree "causa-open-in-editor")]
      ;; Every fixture row carries a :file slot, so every row should
      ;; have an open chip resolved through the editor config.
      (is (pos? (count chips))
          "at least one jump-to-source chip rendered"))))

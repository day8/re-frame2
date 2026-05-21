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
  ;; nil frame-id = list every frame's app-db schemas (see
  ;; scope-app-schemas-to-frame).
  (let [data (panel/project-data (:schemas-by-frame sample-registry)
                                 (:events sample-registry)
                                 (:subs   sample-registry)
                                 nil
                                 nil)]
    (is (= 3 (:total data)))
    (is (false? (:silent? data)))
    (is (true? (:silent? (panel/project-data {} {} {} nil nil))))))

(deftest scope-app-schemas-to-frame-narrows
  (let [multi {:rf/default {[:a] {:schema :int}}
               :rf/cart    {[:b] {:schema :int}}}]
    (testing "nil frame-id passes through verbatim"
      (is (= multi (panel/scope-app-schemas-to-frame multi nil))))
    (testing "a frame-id keeps only that frame's app-db schemas"
      (is (= {:rf/default {[:a] {:schema :int}}}
             (panel/scope-app-schemas-to-frame multi :rf/default))))
    (testing "an absent frame-id yields an empty map"
      (is (= {} (panel/scope-app-schemas-to-frame multi :rf/nope))))))

(deftest project-data-scopes-app-db-schemas-but-not-global-specs
  (let [multi-by-frame {:rf/default {[:user] {:schema :map}}
                        :rf/cart    {[:cart] {:schema :map}}}
        events         (:events sample-registry)   ;; one spec'd event
        subs           (:subs   sample-registry)]  ;; one spec'd sub
    (testing "frame :rf/default → its 1 app-db schema + the 2 global specs"
      (let [data (panel/project-data multi-by-frame events subs :rf/default nil)]
        (is (= 3 (:total data)) "1 app-db (default) + 1 event + 1 sub")
        (is (= 1 (count (filterv #(= :app-db (:kind %)) (:schemas data))))
            "only :rf/default's app-db schema, not :rf/cart's")))
    (testing "frame :rf/cart → its 1 app-db schema + the same 2 global specs"
      (let [data (panel/project-data multi-by-frame events subs :rf/cart nil)]
        (is (= 3 (:total data)))
        (is (= [[:cart]]
               (mapv :id (filterv #(= :app-db (:kind %)) (:schemas data))))
            "only :rf/cart's app-db schema surfaces")))
    (testing "nil frame-id → both frames' app-db schemas + global specs"
      (is (= 4 (:total (panel/project-data multi-by-frame events subs nil nil)))))))

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

(def two-frame-registry
  "Two frames each carrying a distinct app-db schema, plus the shared
  process-global event + sub specs — fixture for the picker-scoping
  regression."
  {:schemas-by-frame
   {:rf/default    {[:user] {:schema [:map [:id :int]]}}
    :rf/cart-frame {[:cart] {:schema [:map [:n :int]]}}}
   :events {:user/login {:spec [:tuple :keyword]}}
   :subs   {:user/full-name {:spec :string}}})

(deftest tab-data-scopes-app-db-schemas-to-picker-frame
  (testing "the L1 frame picker scopes the app-db-schema rows — switching
            the picker frame changes which frame's app-db schemas list,
            while the process-global event + sub specs stay visible in
            every frame"
    (setup-causa!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync
        [:rf.causa.static.schemas/set-registry-override-for-test
         two-frame-registry])
      (testing "picker on :rf/default → its 1 app-db schema + 2 global specs"
        (rf/dispatch-sync [:rf.causa/select-frame :rf/default])
        (let [data    @(rf/subscribe [:rf.causa.static.schemas/tab-data])
              app-dbs (filterv #(= :app-db (:kind %)) (:schemas data))]
          (is (= 3 (:total data)) "1 app-db (default) + 1 event + 1 sub")
          (is (= [[:user]] (mapv :id app-dbs))
              "only :rf/default's app-db schema, not :rf/cart-frame's")))
      (testing "picker on :rf/cart-frame → its 1 app-db schema + 2 global specs"
        (rf/dispatch-sync [:rf.causa/select-frame :rf/cart-frame])
        (let [data    @(rf/subscribe [:rf.causa.static.schemas/tab-data])
              app-dbs (filterv #(= :app-db (:kind %)) (:schemas data))]
          (is (= 3 (:total data)))
          (is (= [[:cart]] (mapv :id app-dbs))
              "only :rf/cart-frame's app-db schema surfaces — NOT the global 2")))
      (rf/dispatch-sync
        [:rf.causa.static.schemas/set-registry-override-for-test nil]))))

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

;; -------------------------------------------------------------------------
;; (4) a11y list semantics (rf2-mq8wk)
;; -------------------------------------------------------------------------

(deftest panel-list-carries-list-semantics
  (testing "rf2-mq8wk — the schemas <ul> is role=list, rows role=listitem"
    (setup-causa!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync
        [:rf.causa.static.schemas/set-registry-override-for-test
         sample-registry])
      (let [tree (panel/Panel)
            list-node (find-by-testid tree "rf-causa-static-schemas-list")
            rows (find-all-by-testid-prefix tree "rf-causa-static-schemas-row-")]
        (is (= "list" (:role (second list-node))) "<ul> carries role=list")
        (is (seq rows) "rows rendered")
        (is (every? #(= "listitem" (:role (second %))) rows)
            "every row carries role=listitem")))))

;; -------------------------------------------------------------------------
;; (5) schema EDN renders through the shared widget (rf2-2kwhw + rf2-f026h)
;; -------------------------------------------------------------------------

(deftest schema-edn-renders-through-widget-with-copy
  (testing "rf2-2kwhw + rf2-f026h — the Malli schema renders via the shared
            cljs-devtools EDN widget and carries the universal copy button"
    (setup-causa!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync
        [:rf.causa.static.schemas/set-registry-override-for-test
         sample-registry])
      (let [tree (panel/Panel)
            widget (find-all-by-testid-prefix
                     tree "rf-causa-edn-widget-browse-")
            copy   (filterv (fn [node]
                              (and (vector? node)
                                   (map? (second node))
                                   (= "rf-causa-edn-widget-copy"
                                      (:class (second node)))))
                            (hiccup-seq tree))]
        (is (seq widget) "schema values render through the browse widget")
        (is (seq copy) "each schema value carries the copy affordance")))))

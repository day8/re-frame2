(ns day8.re-frame2-xray.static.schemas.panel-cljs-test
  "CLJS wiring + view tests for the Static Schemas sub-tab
  (rf2-o5f5f.4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.schemas.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `reset-all!` folds the trace-collector ring reset in, so the old
  ;; bespoke `xray-init!` (reset-all! + a REDUNDANT direct trace reset) is
  ;; gone (rf2-vj80u8). Default `:all` tier + plain-atom adapter.
  (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers ------------------------------------------------------
;;
;; PLAIN DESCENT — nothing is CALLED. These rows used
;; `rf.test-helpers/find-by-testid` / `…/find-by-testid-prefix`, which EXPAND
;; function components as they walk (rf2-vj80u8 retired this file's private
;; copies in favour of them).
;;
;; rf2-k97c.3 made that expansion UNSAFE. Every plain helper the panel used
;; to head with is now CALLED, so its markup is already realized in the tree
;; and a shallow walk suffices again — but the one fn-headed vector that
;; REMAINS is `[ei/edn-inspector-view …]`, a FRESCO BOUNDARY: a React
;; function component whose body may only run inside a React render window.
;; Applying it here would run `rf.fresco/sub` outside the collector, which
;; is not a leaf-expansion at all. So the widget stays a LEAF, exactly as
;; `panels/app_db_diff_cljs_test` already settled for the same reason.

(defn- hiccup-nodes [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-nodes tree)))

(defn- find-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (some-> (:data-testid (second node))
                          (.startsWith prefix))))
           (hiccup-nodes tree)))

(defn- setup-xray! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- panel-tree
  "The hiccup the view rows below walk, driven through the pure projection.

  rf2-k97c.3 — `panel/Panel` is now an `rf.fresco/defview` boundary, a real
  React function component whose body may only run inside a React render
  window, so `(panel/Panel)` is no longer a callable that answers hiccup.
  This helper REPRODUCES THE BOUNDARY'S READ EXACTLY — the one
  `:rf.xray.static.schemas/tab-data` query the boundary issues — and hands
  the value to `panel/panel-tree`, so every row below asserts on the same
  hiccup it asserted on before.

  The dispatcher is nil: no row here types into the search box, and the
  search box only calls it from `:on-change`. The boundary's OWN behaviour
  — first paint, liveness, frame targeting, evidence isolation, teardown
  and row identity — is `panel_fresco_boundary_dom_cljs_test`'s subject."
  []
  (panel/panel-tree @(rf/subscribe [:rf.xray.static.schemas/tab-data]) nil))

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
  (setup-xray!)
  (rf/with-frame :rf/xray
    (is (nil? @(rf/subscribe [:rf.xray.static.schemas/query]))
        "query slot defaults nil")))

(deftest set-query-writes-the-slot
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.static.schemas/set-query "user"])
    (is (= "user" @(rf/subscribe [:rf.xray.static.schemas/query])))))

(deftest registry-override-feeds-the-composite
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [data @(rf/subscribe [:rf.xray.static.schemas/tab-data])]
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
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         two-frame-registry])
      (testing "picker on :rf/default → its 1 app-db schema + 2 global specs"
        (rf/dispatch-sync [:rf.xray/select-frame :rf/default])
        (let [data    @(rf/subscribe [:rf.xray.static.schemas/tab-data])
              app-dbs (filterv #(= :app-db (:kind %)) (:schemas data))]
          (is (= 3 (:total data)) "1 app-db (default) + 1 event + 1 sub")
          (is (= [[:user]] (mapv :id app-dbs))
              "only :rf/default's app-db schema, not :rf/cart-frame's")))
      (testing "picker on :rf/cart-frame → its 1 app-db schema + 2 global specs"
        (rf/dispatch-sync [:rf.xray/select-frame :rf/cart-frame])
        (let [data    @(rf/subscribe [:rf.xray.static.schemas/tab-data])
              app-dbs (filterv #(= :app-db (:kind %)) (:schemas data))]
          (is (= 3 (:total data)))
          (is (= [[:cart]] (mapv :id app-dbs))
              "only :rf/cart-frame's app-db schema surfaces — NOT the global 2")))
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test nil]))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       {:schemas-by-frame {} :events {} :subs {}}])
    (let [tree (panel-tree)]
      (is (some? (find-by-testid tree "rf-xray-static-schemas-empty"))))))

(deftest panel-renders-rows-from-override
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [tree (panel-tree)
          rows (find-by-testid-prefix tree "rf-xray-static-schemas-row-")]
      (is (= 3 (count rows)) "three row surfaces rendered"))))

(deftest panel-renders-jump-to-source-chips
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [tree (panel-tree)
          chips (find-by-testid-prefix tree "xray-open-in-editor")]
      ;; Every fixture row carries a :file slot, so every row should
      ;; have an open chip resolved through the editor config.
      (is (pos? (count chips))
          "at least one jump-to-source chip rendered"))))

;; -------------------------------------------------------------------------
;; (4) a11y list semantics (rf2-mq8wk)
;; -------------------------------------------------------------------------

(deftest panel-list-carries-list-semantics
  (testing "rf2-mq8wk — the schemas <ul> is role=list, rows role=listitem"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         sample-registry])
      (let [tree (panel-tree)
            list-node (find-by-testid tree "rf-xray-static-schemas-list")
            rows (find-by-testid-prefix tree "rf-xray-static-schemas-row-")]
        (is (= "list" (:role (second list-node))) "<ul> carries role=list")
        (is (seq rows) "rows rendered")
        (is (every? #(= "listitem" (:role (second %))) rows)
            "every row carries role=listitem")))))

;; -------------------------------------------------------------------------
;; (5) schema EDN renders through the shared widget (rf2-2kwhw)
;; -------------------------------------------------------------------------

(defn- inspector-view-forms
  "Every `[ei/edn-inspector-view {…}]` form in the tree — the widget's
  FRESCO head, and now the only fn-headed vector the panel emits.

  No expansion of any kind. The panel CALLS every plain helper since
  rf2-k97c.3, so the tree is already realized down to this leaf, and the
  leaf must STAY a leaf: applying a boundary here would run
  `rf.fresco/sub` outside the collector."
  [tree]
  (filterv #(and (vector? %) (= ei/edn-inspector-view (first %)))
           (hiccup-nodes tree)))

(deftest schema-edn-renders-through-the-widgets-fresco-head
  (testing "rf2-2kwhw + rf2-oqa60 — the Malli schema renders via the
            shared EDN widget; rf2-k97c.3 — through its FRESCO head.

            This row used to assert on the widget's expanded
            `rf-xray-edn-inspector-*` CONTAINER testid, which only exists
            once the widget has been invoked. The walker above no longer
            invokes anything, and it must not: `ei/edn-inspector-view` is a
            boundary whose body may only run inside a React render window.
            So the claim moves up one level to the thing the panel actually
            emits — and in doing so it pins the HD-016 repair directly,
            which the old spelling could not: `ei/edn-inspector` is a plain
            fn, and a plain fn in hiccup head position is a loud error
            inside a Fresco body."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         sample-registry])
      (let [tree  (panel-tree)
            heads (inspector-view-forms tree)]
        ;; One schema value per row; the fixture projects three rows.
        (is (= 3 (count heads))
            (str "every row's schema renders through the widget's Fresco "
                 "head. Mount ids: "
                 (pr-str (mapv #(:mount-id (second %)) heads))))
        (is (empty? (filterv #(and (vector? %) (= ei/edn-inspector (first %)))
                             (hiccup-nodes tree)))
            "and NOT ONE `[ei/edn-inspector …]` Reagent head survives — that
             head is a plain fn, which is a loud error in a Fresco body, so
             a single survivor would take the whole panel down at runtime
             rather than degrade")
        (is (= (count heads) (count (set (map #(:mount-id (second %)) heads))))
            "each mount gets its OWN `:mount-id` — two mounts sharing one
             would share a width slot and a projection cache, which is the
             per-mount identity defect rf2-d2aj records")))))

;; -------------------------------------------------------------------------
;; (6) row React keys reach the RENDERER, not just the reader (rf2-k97c.3)
;; -------------------------------------------------------------------------

(deftest row-keys-ride-the-attribute-map-not-metadata
  (testing "rf2-k97c.3, RULING 2's key sweep — each catalogue row's React key
            is carried on a keyed FRAGMENT'S ATTRIBUTE MAP, which is the one
            spelling the shipped renderer reads.

            It was `^{:key …}` reader metadata on the row's vector literal.
            Reagent's `get-react-key` does read that, so it worked; Fresco's
            codec reads `:key` from an attribute map and reads Clojure
            metadata NOWHERE, so it would have gone inert the moment this
            panel started rendering through a boundary — and silently, since
            a lost key does not fail but degrades into index-based
            reconciliation.

            ASSERTING ON THE METADATA HERE WOULD BE A HOLLOW GATE: it would
            pass while React received nothing. The browser lane's W5 closes
            the loop on a real React commit."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         sample-registry])
      (let [tree      (panel-tree)
            fragments (filterv #(and (vector? %) (= :<> (first %))
                                     (map? (second %))
                                     (contains? (second %) :key))
                               (hiccup-nodes tree))
            rows      (find-by-testid-prefix tree "rf-xray-static-schemas-row-")]
        (is (= 3 (count rows))
            "PRECONDITION: the fixture's three rows rendered — a smaller
             count would make the key claim vacuous")
        (is (= (count rows) (count fragments))
            (str "every row is wrapped in a keyed fragment. Keys seen: "
                 (pr-str (mapv #(:key (second %)) fragments))))
        (is (every? #(some? (:key (second %))) fragments)
            "and each key is non-nil IN THE ATTRIBUTE MAP")
        (is (= (count fragments) (count (set (map #(:key (second %)) fragments))))
            "and the row keys are distinct from one another")))))

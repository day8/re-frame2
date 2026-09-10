(ns day8.re-frame2-xray.static.interceptors.panel-cljs-test
  "CLJS wiring + view tests for the Static Interceptors sub-tab
  (rf2-o5f5f.6)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.interceptors.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `reset-all!` folds the trace-collector ring reset in, so the old
  ;; bespoke `xray-init!` (reset-all! + a REDUNDANT direct trace reset) is
  ;; gone (rf2-vj80u8). Default `:all` tier + plain-atom adapter.
  (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers ------------------------------------------------------
;;
;; The private expand-tree / hiccup-seq / find-by-testid* copies this file
;; carried are semantically identical to `re-frame.test-helpers`; the tests
;; below call `rf.test-helpers/find-by-testid` / `rf.test-helpers/find-by-testid-prefix` directly
;; (rf2-vj80u8 — no Xray walker facade).

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
  `:rf.xray.static.interceptors/tab-data` query the boundary issues — and
  hands the value to `panel/panel-tree`, so every row below asserts on the
  same hiccup it asserted on before.

  The dispatcher is nil: no row here types into the search box, and the
  search box only calls it from `:on-change`. The boundary's OWN behaviour
  — first paint, liveness, frame targeting, evidence isolation, teardown
  and row identity — is `panel_fresco_boundary_dom_cljs_test`'s subject."
  []
  (panel/panel-tree @(rf/subscribe [:rf.xray.static.interceptors/tab-data])
                    nil))

;; ---- fixture data -------------------------------------------------------

;; EP-0018: every event registers under the ONE form — the framework
;; handler-wrapping interceptor is the single `:rf/event-handler` (the former
;; per-kind `:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler` ids + the
;; `:event/kind` sub-tag are gone). The fixture models that registrar shape.
(def sample-events-with-chains
  {:counter/inc
   {:interceptors [{:id :my/logging :before identity}
                   {:id :rf/event-handler :rf/default? true :before identity}]}

   :user/save
   {:interceptors [{:id :my/logging :before identity}
                   {:id :rf/path    :before identity}
                   {:id :rf/event-handler :rf/default? true :before identity}]}

   :anon/no-chain
   {:interceptors []}})

;; EP-0022 (rf2-0adhqs.7) — a chain may carry REFERENCES (bare keyword /
;; `[id arg]`) into the `:interceptor` registrar alongside inline values.
;; The catalogue must surface refs by their authored form + enrich them
;; from the registered descriptor.
(def sample-events-with-refs
  {:cart/add
   {:interceptors [:my/logging                       ; bare-keyword ref
                   [:rf.interceptor/path [:cart]]     ; parameterized [id arg] ref
                   {:id :rf/event-handler :rf/default? true :before identity}]}

   :cart/clear
   {:interceptors [:my/logging                       ; same ref → collapses
                   {:id :rf/event-handler :rf/default? true :before identity}]}})

;; A stub resolver standing in for `(rf/handler-meta {:source :store :kind :interceptor :id id})` so the
;; pure helper test never needs a live registrar.
(defn- stub-resolve-ref [icpt-id]
  (get {:my/logging          {:rf/interceptor-descriptor {:before identity}
                              :doc "logs every dispatch"}
        :rf.interceptor/path {:rf/interceptor-descriptor {:factory identity}
                              :doc "framework path interceptor"}}
       icpt-id))

;; -------------------------------------------------------------------------
;; (1) pure helpers
;; -------------------------------------------------------------------------

(deftest collect-interceptors-collapses-by-id
  (let [rows (panel/collect-interceptors sample-events-with-chains)
        by-id (into {} (map (juxt :id identity) rows))]
    ;; 3 distinct interceptors: :my/logging, :rf/event-handler, :rf/path
    ;; (EP-0018 — the one framework wrapper :rf/event-handler is shared by both
    ;; chains, so it collapses to a single row with chain-count 2).
    (is (= 3 (count rows)))
    (is (= 2 (get-in by-id [:my/logging :chain-count]))
        ":my/logging appears on 2 chains")
    (is (= 2 (get-in by-id [:rf/event-handler :chain-count]))
        ":rf/event-handler appears on both chains")
    (is (= 1 (get-in by-id [:rf/path :chain-count]))
        ":rf/path appears on 1 chain")))

(deftest collect-interceptors-flags-default-marker
  (let [rows  (panel/collect-interceptors sample-events-with-chains)
        by-id (into {} (map (juxt :id identity) rows))]
    (is (true?  (get-in by-id [:rf/event-handler :default?]))
        "rf/event-handler is framework-default")
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
    (is (= 3 (:total data)))
    (is (false? (:silent? data)))
    (is (true? (:silent? (panel/project-data {} nil)))
        "no events → silent")))

;; -------------------------------------------------------------------------
;; (1b) EP-0022 ref-aware collection (rf2-0adhqs.7)
;; -------------------------------------------------------------------------

(deftest collect-interceptors-surfaces-keyword-refs
  (let [rows  (panel/collect-interceptors sample-events-with-refs stub-resolve-ref)
        by-id (into {} (map (juxt :id identity) rows))]
    ;; 3 distinct ids: :my/logging (ref), :rf.interceptor/path (factory ref),
    ;; :rf/event-handler (inline value).
    (is (= 3 (count rows)))
    (is (true? (get-in by-id [:my/logging :ref?]))
        "a bare-keyword chain entry is surfaced as a reference")
    (is (= :my/logging (get-in by-id [:my/logging :authored]))
        "the authored ref form is the keyword itself")
    (is (= 2 (get-in by-id [:my/logging :chain-count]))
        "the ref collapses across both chains")
    (is (true? (get-in by-id [:my/logging :before?]))
        "the ref is enriched with its resolved descriptor's :before hook")
    (is (= "logs every dispatch" (get-in by-id [:my/logging :doc]))
        "the ref is enriched with the registered :doc")))

(deftest collect-interceptors-surfaces-factory-refs
  (let [rows  (panel/collect-interceptors sample-events-with-refs stub-resolve-ref)
        by-id (into {} (map (juxt :id identity) rows))
        path  (get by-id :rf.interceptor/path)]
    (is (true? (:ref? path)) "[id arg] entry is a reference")
    (is (= [:rf.interceptor/path [:cart]] (:authored path))
        "the authored form is the full [id arg] vector")
    (is (= [:cart] (:arg path)) "the factory arg is surfaced")
    (is (true? (:factory? path))
        "a :factory descriptor is reported as a factory ref")))

(deftest collect-interceptors-keeps-inline-values-non-ref
  (let [rows  (panel/collect-interceptors sample-events-with-refs stub-resolve-ref)
        by-id (into {} (map (juxt :id identity) rows))]
    (is (false? (get-in by-id [:rf/event-handler :ref?]))
        "an inline interceptor value is NOT a reference")
    (is (true? (get-in by-id [:rf/event-handler :default?]))
        "inline framework wrapper still flags :default?")))

(deftest collect-interceptors-arity-1-uses-default-resolver
  ;; The 1-arity (production) form must not throw on an unregistered ref —
  ;; default-resolve-ref is fail-soft.
  (let [rows (panel/collect-interceptors {:x {:interceptors [:unregistered/icpt]}})
        row  (first rows)]
    (is (= :unregistered/icpt (:id row)))
    (is (true? (:ref? row)))
    (is (false? (:before? row)) "unresolved ref reports no hooks")))

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
      (is (= 3 (:total data)))
      (is (false? (:silent? data))))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.interceptors/set-registry-override-for-test {}])
    (let [tree (panel-tree)]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-interceptors-empty"))))))

(deftest panel-renders-rows-from-override
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.interceptors/set-registry-override-for-test
       sample-events-with-chains])
    (let [tree (panel-tree)
          rows (rf.test-helpers/find-by-testid-prefix tree "rf-xray-static-interceptors-row-")]
      (is (= 3 (count rows)) "three collapsed interceptor rows rendered"))))

(deftest panel-renders-filtered-state-on-no-match
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.interceptors/set-registry-override-for-test
       sample-events-with-chains])
    (rf/dispatch-sync [:rf.xray.static.interceptors/set-query "no-such-id"])
    (let [tree (panel-tree)]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-interceptors-empty-filtered"))))))

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
      (let [tree (panel-tree)
            list-node (rf.test-helpers/find-by-testid tree "rf-xray-static-interceptors-list")
            rows (rf.test-helpers/find-by-testid-prefix
                   tree "rf-xray-static-interceptors-row-")]
        (is (= "list" (:role (second list-node))) "<ul> carries role=list")
        (is (seq rows) "rows rendered")
        (is (every? #(= "listitem" (:role (second %))) rows)
            "every row carries role=listitem")))))

;; -------------------------------------------------------------------------
;; (5) row identity reaches the RENDERER, not just Clojure metadata
;;     (rf2-k97c.3)
;; -------------------------------------------------------------------------

(deftest panel-rows-carry-their-key-in-an-attribute-map
  (testing "rf2-k97c.3 — every catalogue row carries its React key in an
            ATTRIBUTE MAP, which is the ONE spelling Fresco's codec reads
            (its head table: a literal `:key` in the attr map, on the
            fragment for `[:<> …]`). It reads Clojure metadata NOWHERE, so
            the `^{:key …}` this panel used to carry survives Reagent and
            reaches React as nothing under a boundary.

            A row asserting on that metadata is a HOLLOW GATE: it passes
            while React receives no key at all. And a lost key does not
            fail — it degrades into index-based reconciliation, which
            paints identically and corrupts identity only once the list
            changes shape, which is why this is asserted at all rather
            than left to the eye."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.interceptors/set-registry-override-for-test
         sample-events-with-chains])
      (let [tree      (panel-tree)
            list-node (rf.test-helpers/find-by-testid
                        tree "rf-xray-static-interceptors-list")
            row-forms (rf.test-helpers/children list-node)
            keys-seen (mapv #(:key (rf.test-helpers/attrs %)) row-forms)]
        (is (= 3 (count row-forms))
            "PRECONDITION: three rows rendered — otherwise every claim
             below is vacuous")
        (is (every? string? keys-seen)
            (str "every row's key is in its own attribute map. Got: "
                 (pr-str keys-seen)))
        (is (= (count keys-seen) (count (set keys-seen)))
            "and the keys are distinct, so React can tell the rows apart")
        (is (= (sort keys-seen)
               (sort (mapv #(pr-str (:id %))
                           (panel/collect-interceptors
                             sample-events-with-chains))))
            "the key EXPRESSION is unchanged by the move — still the row's
             own interceptor id, so identity means what it always meant")
        (is (every? #(nil? (meta %)) row-forms)
            "and nothing is left riding on Clojure metadata, which would be
             a second spelling the codec cannot see")))))

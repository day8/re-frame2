(ns re-frame.machines-hierarchical-cljs-test
  "CLJS-side coverage for hierarchical state-machine semantics under the
  Reagent reactive substrate.

  Mirrors the conformance fixtures
  ../spec/conformance/fixtures/{hierarchical-compound-transition,
  hierarchical-parent-fallthrough}.edn but exercises the runtime through
  `reg-machine` / `dispatch-sync` — the same surface real apps use.

  Concerns covered:
    - Compound state: sibling-leaf transition fires only the leaf
      exit/entry (LCA cascade).
    - Deepest-wins: leaf overrides parent for the same event id.
    - Wildcard precedence (rf2-fhb9): leaf `:*` shadows parent's explicit
      handler at the same event; parent fallthrough still works when the
      leaf declares neither explicit nor `:*`.

  Split out of `machines_cljs_test.cljs` (rf2-3vps4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(defn- seed-snapshot!
  "Force the snapshot for `machine-id` to a known value via an event-db.
  Used to *reposition* a machine to a non-initial state mid-test (e.g. to
  exercise a different leaf without rebuilding the whole machine)."
  [machine-id snap]
  (let [seed-id (keyword "test" (str "seed-" (namespace machine-id) "-" (name machine-id)))]
    (rf/reg-event-db seed-id
      (fn [db _] (assoc-in db [:rf/machines machine-id] snap)))
    (rf/dispatch-sync [seed-id])))

(deftest machine-hierarchical-cljs
  (testing "compound state — sibling-leaf transition fires only the leaf exit/entry"
    ;; Tracks which entry/exit hooks fired so we can assert the LCA cascade.
    (let [log (atom [])
          tag (fn [k] (fn [_ _] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:enter-auth (tag :enter-auth)
                     :exit-auth  (tag :exit-auth)
                     :enter-dash (tag :enter-dash)
                     :exit-dash  (tag :exit-dash)
                     :enter-set  (tag :enter-set)
                     :exit-set   (tag :exit-set)}
           :states
           {:authenticated
            {:initial :dashboard
             :entry   :enter-auth
             :exit    :exit-auth
             :states
             {:dashboard {:entry :enter-dash
                          :exit  :exit-dash
                          :on    {:open-settings :settings}}
              :settings  {:entry :enter-set
                          :exit  :exit-set
                          :on    {:close :dashboard}}}}}}]
      (rf/reg-machine :auth/flow machine)
      ;; The runtime cascades the declared :initial through compound
      ;; :initial chains on first-snapshot synthesis (rf2-m1tv), so the
      ;; first event dispatches against the deepest leaf without us
      ;; having to seed the snapshot manually. Per rf2-0z73, the
      ;; initial-state cascade ALSO fires every state's :entry action
      ;; on first-event bootstrap (shallowest-first along the initial
      ;; chain) — so the log accumulates :enter-auth + :enter-dash
      ;; BEFORE the sibling-leaf transition kicks in.
      (reset! log [])
      ;; Sibling-leaf transition. LCA is :authenticated; only the leaf
      ;; exit/entry hooks fire for the transition itself — the parent's
      ;; :enter-auth runs ONCE during the bootstrap cascade and does
      ;; NOT re-fire on the sibling transition (LCA is :authenticated).
      (rf/dispatch-sync [:auth/flow [:open-settings]])
      (is (= [:authenticated :settings] (:state (snapshot :auth/flow)))
          "snapshot moved to the sibling leaf")
      (is (= [:enter-auth :enter-dash :exit-dash :enter-set] @log)
          "initial-cascade :entry actions fired on bootstrap (:enter-auth + :enter-dash) followed by the sibling-leaf exit/entry (:exit-dash + :enter-set). The LCA parent's :enter-auth fires ONCE on bootstrap, not on the LCA-bounded sibling transition.")))

  (testing "deepest-wins — leaf overrides parent for same event id"
    (let [log (atom [])
          tag (fn [k] (fn [_ _] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:parent-help    (tag :parent)
                     :leaf-help      (tag :leaf)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:help {:action :parent-help}}      ;; internal — no :target
             :states
             {:dashboard {}                                ;; no :help handler
              :settings  {:on {:help {:action :leaf-help}}}}}}}]
      (rf/reg-machine :auth2/flow machine)
      ;; Initial cascade lands at [:authenticated :dashboard] without seeding.
      ;; Case 1: leaf has no :help — parent fallthrough.
      (reset! log [])
      (rf/dispatch-sync [:auth2/flow [:help]])
      (is (= [:authenticated :dashboard] (:state (snapshot :auth2/flow)))
          "internal transition — snapshot unchanged")
      (is (= [:parent] @log) "parent's :help action fired (fallthrough)")
      ;; Reposition to the :settings leaf for case 2.
      (seed-snapshot! :auth2/flow {:state [:authenticated :settings] :data {}})
      ;; Case 2: leaf handles :help — leaf wins, parent SHADOWED.
      (reset! log [])
      (rf/dispatch-sync [:auth2/flow [:help]])
      (is (= [:authenticated :settings] (:state (snapshot :auth2/flow)))
          "internal transition — snapshot unchanged")
      (is (= [:leaf] @log) "leaf's :help action fired; parent shadowed"))))

;; ---- wildcard precedence in hierarchical machines (rf2-fhb9) -------------
;; Per Spec 005 §Wildcard transitions and §Transition resolution: at each
;; level, explicit-event match beats `:*`; only if neither matches does the
;; runtime walk up to the parent. So a leaf's `:*` SHADOWS a parent's
;; explicit handler for the same event — wildcard wins at the deeper level
;; before parent fallthrough kicks in. This is the divergence point that
;; the §Wildcard transitions list at L213-218 used to muddle (rf2-fhb9).
(deftest machine-hierarchical-wildcard-precedence-cljs
  (testing "leaf :* shadows parent's explicit handler for the same event"
    (let [log (atom [])
          tag (fn [k] (fn [_ _] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:parent-explicit (tag :parent-explicit)
                     :leaf-wildcard   (tag :leaf-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:help {:action :parent-explicit}}    ;; explicit at parent
             :states
             {:dashboard
              {:on {:* {:action :leaf-wildcard}}}}}}}]      ;; :* at leaf
      (rf/reg-machine :rf2-fhb9/precedence machine)
      (reset! log [])
      (rf/dispatch-sync [:rf2-fhb9/precedence [:help]])
      ;; At leaf [:authenticated :dashboard]: explicit miss → :* hit. The
      ;; runtime stops walking; parent's explicit :help is shadowed.
      (is (= [:leaf-wildcard] @log)
          "leaf-level :* fired, parent's explicit :help shadowed (per-level rule)")
      (is (not (some #{:parent-explicit} @log))
          "parent's explicit handler must NOT fire when leaf :* matched")))

  (testing "parent fallthrough still works when leaf has neither explicit nor :*"
    (let [log (atom [])
          tag (fn [k] (fn [_ _] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:parent-explicit (tag :parent-explicit)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:help {:action :parent-explicit}
                       :*    {:action :parent-wildcard}}    ;; both at parent
             :states
             {:dashboard {}}}}}]                            ;; leaf has no :on
      (rf/reg-machine :rf2-fhb9/parent-walk machine)
      (reset! log [])
      ;; :help at parent — explicit beats parent's own :*.
      (rf/dispatch-sync [:rf2-fhb9/parent-walk [:help]])
      (is (= [:parent-explicit] @log)
          "explicit at the matching level beats :* at the same level")
      ;; :unknown at parent — falls through to parent's :*.
      (reset! log [])
      (rf/dispatch-sync [:rf2-fhb9/parent-walk [:unknown]])
      (is (= [:parent-wildcard] @log)
          "no explicit anywhere — parent's :* fires"))))

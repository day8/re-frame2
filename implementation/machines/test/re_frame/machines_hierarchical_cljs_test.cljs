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
    - Wildcard precedence: parent fallthrough works when the leaf
      declares neither explicit nor `:*`, and explicit beats `:*` at the
      matching level.
    - The compound DEFAULT self-transition (`:same-state`, no `:reenter?`)
      re-resolves its descendants.

  The pure SCXML corpus (`scxml_conformance_cljs_test`, both hosts) pins
  the leaf-`:*`-shadows-parent-explicit rule, the flat and compound
  external/internal self-transitions and the proper-ancestor restarts.

  Counterpart to the non-hierarchical coverage in the sibling
  `machines_*_cljs_test` namespaces."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- seed-snapshot!
  "Force the snapshot for `machine-id` to a known value via a `reg-event`
  seed handler (returning `{:rf.db/runtime …}`).
  Used to *reposition* a machine to a non-initial state mid-test (e.g. to
  exercise a different leaf without rebuilding the whole machine)."
  [machine-id snap]
  (let [seed-id (keyword "test" (str "seed-" (namespace machine-id) "-" (name machine-id)))]
    ;; Machine snapshots are durable runtime-db state.
    (rf/reg-event seed-id
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/machines :snapshots machine-id] snap)}))
    (rf/dispatch-sync [seed-id])))

(deftest machine-hierarchical-cljs
  (testing "compound state — sibling-leaf transition fires only the leaf exit/entry"
    ;; Tracks which entry/exit hooks fired so we can assert the LCA cascade.
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
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
      ;; :initial chains on first-snapshot synthesis, so the first event
      ;; dispatches against the deepest leaf without us having to seed the
      ;; snapshot manually. The initial-state cascade ALSO fires every
      ;; state's :entry action on first-event bootstrap (shallowest-first
      ;; along the initial chain) — so the log accumulates :enter-auth +
      ;; :enter-dash BEFORE the sibling-leaf transition kicks in.
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
          tag (fn [k] (fn [_] (swap! log conj k) {}))
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

;; ---- wildcard precedence in hierarchical machines ------------------------
;; Per Spec 005 §Wildcard transitions and §Transition resolution: at each
;; level, explicit-event match beats `:*`; only if neither matches does the
;; runtime walk up to the parent. So a leaf's `:*` SHADOWS a parent's
;; explicit handler for the same event — wildcard wins at the deeper level
;; before parent fallthrough kicks in (pinned on both hosts by the SCXML
;; corpus's `scxml-leaf-wildcard-shadows-parent-explicit`).
(deftest machine-hierarchical-wildcard-precedence-cljs
  (testing "parent fallthrough works when leaf has neither explicit nor :*"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
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

;; ---- external (:reenter? true) vs internal self-transitions ---------------
;; Per Spec 005 §Self-transitions: a self `:target` is INTERNAL BY
;; DEFAULT — the action fires, `:exit`/`:entry` do NOT, the configuration is
;; unchanged (XState-v5 semantics). The EXTERNAL self-transition — `:exit`
;; then the transition's `:action` then `:entry`, re-descending a compound's
;; `:initial` chain — is the opt-in `:reenter? true`. The SCXML
;; conformance corpus pins the flat and compound geometries on both hosts
;; (`scxml-external-self-transition-*`, `scxml-internal-self-transition-*`,
;; `scxml-default-self-transition-is-internal`,
;; `scxml-external-transition-to-ancestor-via-same-state-on-ancestor`); this
;; ns keeps the live compound DEFAULT case.
(deftest machine-self-transition-cljs
  (testing "DEFAULT self-transition on a COMPOUND state (:same-state, NO
            :reenter?) RE-RESOLVES its descendants — the compound itself is NOT
            exited/re-entered, but its active child is exited and the compound's
            :initial re-descends. XState v5: 'an explicit target re-resolves
            child states to their initial'. Verified against xstate@5.32.0."
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :session
           :data    {}
           :actions {:enter-session (tag :enter-session)
                     :exit-session  (tag :exit-session)
                     :enter-active  (tag :enter-active)
                     :exit-active   (tag :exit-active)
                     :enter-idle    (tag :enter-idle)
                     :exit-idle     (tag :exit-idle)
                     :renew         (tag :renew)}
           :states
           {:session
            {:initial :active
             :entry   :enter-session
             :exit    :exit-session
             ;; :same-state with NO :reenter? — re-resolves descendants (v5)
             :on      {:reauth {:target :same-state :action :renew}}
             :states
             {:active {:entry :enter-active :exit :exit-active
                       ;; sibling target — moves to :idle (relative to :session)
                       :on {:sleep :idle}}
              :idle   {:entry :enter-idle :exit :exit-idle}}}}}]
      (rf/reg-machine :self/compound-default-internal machine)
      (rf/dispatch-sync [:self/compound-default-internal [:rf2-eicq0/prime]])
      ;; Move the active child to the NON-initial :idle so the re-resolution to
      ;; :session's :initial (:active) is observable.
      (rf/dispatch-sync [:self/compound-default-internal [:sleep]])
      (reset! log [])
      (rf/dispatch-sync [:self/compound-default-internal [:reauth]])
      (is (= [:session :active] (:state (snapshot :self/compound-default-internal)))
          "compound self-target re-resolves descendants — re-descends :session's :initial (:active)")
      (is (= [:exit-idle :renew :enter-active] @log)
          "exit the active child :idle → action at the LCCA (:session) → re-enter :initial (:active); :session itself NOT exited/entered (no :reenter?)"))))

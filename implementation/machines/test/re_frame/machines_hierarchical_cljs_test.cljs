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
    - Wildcard precedence: leaf `:*` shadows parent's explicit
      handler at the same event; parent fallthrough still works when the
      leaf declares neither explicit nor `:*`.

  Counterpart to the non-hierarchical coverage in `machines_cljs_test.cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

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
;; before parent fallthrough kicks in.
(deftest machine-hierarchical-wildcard-precedence-cljs
  (testing "leaf :* shadows parent's explicit handler for the same event"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
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
;; Per Spec 005 §Self-transitions: a self/ancestor `:target` is INTERNAL BY
;; DEFAULT — the action fires, `:exit`/`:entry` do NOT, the configuration is
;; unchanged (XState-v5 semantics). The EXTERNAL self-transition — `:exit`
;; then the transition's `:action` then `:entry`, re-descending a compound's
;; `:initial` chain — is the opt-in `:reenter? true`. This ns exercises
;; the live runtime; the pure-engine ordering is pinned in the SCXML
;; conformance corpus (`scxml-external-self-transition-*` /
;; `scxml-reenter-*`).
(deftest machine-self-transition-cljs
  (testing "EXTERNAL self-transition (:target :same-state + :reenter? true) on
            a flat leaf — exit → action → entry, state unchanged"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :idle
           :data    {}
           :actions {:enter-idle (tag :enter-idle)
                     :exit-idle  (tag :exit-idle)
                     :poke       (tag :poke)}
           :states
           {:idle {:entry :enter-idle
                   :exit  :exit-idle
                   :on    {:refresh {:target :same-state :reenter? true :action :poke}}}}}]
      (rf/reg-machine :self/flat-same-state machine)
      ;; Prime: the first dispatch fires the bootstrap initial-cascade
      ;; entry. A benign unhandled event drives that bootstrap without
      ;; touching :idle, so the reset below leaves only the
      ;; self-transition's own exit/action/entry in the log.
      (rf/dispatch-sync [:self/flat-same-state [:rf2-46ban/prime]])
      (reset! log [])
      (rf/dispatch-sync [:self/flat-same-state [:refresh]])
      (is (= :idle (:state (snapshot :self/flat-same-state)))
          "external self-transition leaves the configuration at the source")
      (is (= [:exit-idle :poke :enter-idle] @log)
          "exit → action → entry — the state is re-entered (external semantics)")))

  (testing "EXTERNAL self-transition naming the state's OWN keyword (+ :reenter?
            true) — identical exit → action → entry as :same-state"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :idle
           :data    {}
           :actions {:enter-idle (tag :enter-idle)
                     :exit-idle  (tag :exit-idle)
                     :poke       (tag :poke)}
           :states
           {:idle {:entry :enter-idle
                   :exit  :exit-idle
                   :on    {:refresh {:target :idle :reenter? true :action :poke}}}}}]
      (rf/reg-machine :self/flat-own-keyword machine)
      (rf/dispatch-sync [:self/flat-own-keyword [:rf2-46ban/prime]])  ;; consume bootstrap entry
      (reset! log [])
      (rf/dispatch-sync [:self/flat-own-keyword [:refresh]])
      (is (= :idle (:state (snapshot :self/flat-own-keyword)))
          "a self-naming keyword target stays at the source state")
      (is (= [:exit-idle :poke :enter-idle] @log)
          "exit → action → entry — same as the :same-state sentinel")))

  (testing "INTERNAL self-transition (omit :target) — action ONLY, no
            exit/entry, state unchanged"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :idle
           :data    {}
           :actions {:enter-idle (tag :enter-idle)
                     :exit-idle  (tag :exit-idle)
                     :poke       (tag :poke)}
           :states
           {:idle {:entry :enter-idle
                   :exit  :exit-idle
                   :on    {:refresh {:action :poke}}}}}]    ;; no :target
      (rf/reg-machine :self/flat-internal machine)
      (rf/dispatch-sync [:self/flat-internal [:rf2-46ban/prime]])    ;; consume bootstrap entry
      (reset! log [])
      (rf/dispatch-sync [:self/flat-internal [:refresh]])
      (is (= :idle (:state (snapshot :self/flat-internal)))
          "internal self-transition leaves the configuration unchanged")
      (is (= [:poke] @log)
          "ONLY the action fired — no exit, no entry (internal semantics preserved)")))

  ;; ---- the v5 internal-default --------------------------------------------
  ;; A self/own-keyword `:target` WITHOUT `:reenter?` is INTERNAL. This is the
  ;; regression guard: a `:target :same-state` with no `:reenter?` must NOT
  ;; fire exit/entry.
  (testing "DEFAULT-INTERNAL self-transition (:target :same-state, NO
            :reenter?) — action ONLY, no exit/entry (XState-v5 flip, rf2-eicq0)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :idle
           :data    {}
           :actions {:enter-idle (tag :enter-idle)
                     :exit-idle  (tag :exit-idle)
                     :poke       (tag :poke)}
           :states
           {:idle {:entry :enter-idle
                   :exit  :exit-idle
                   ;; :target :same-state but NO :reenter? — internal by default
                   :on    {:refresh {:target :same-state :action :poke}}}}}]
      (rf/reg-machine :self/flat-default-internal machine)
      (rf/dispatch-sync [:self/flat-default-internal [:rf2-eicq0/prime]])
      (reset! log [])
      (rf/dispatch-sync [:self/flat-default-internal [:refresh]])
      (is (= :idle (:state (snapshot :self/flat-default-internal)))
          "default self-target leaves the configuration unchanged")
      (is (= [:poke] @log)
          "ONLY the action fired — :target :same-state is INTERNAL by default (v5 flip); the old external-default is GONE")))

  (testing "EXTERNAL self-transition on a COMPOUND state (:same-state +
            :reenter? true) — re-runs the state's :entry AND its :initial-child
            entry cascade"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :session
           :data    {}
           :actions {:enter-session (tag :enter-session)
                     :exit-session  (tag :exit-session)
                     :enter-active  (tag :enter-active)
                     :exit-active   (tag :exit-active)
                     :renew         (tag :renew)}
           :states
           {:session
            {:initial :active
             :entry   :enter-session
             :exit    :exit-session
             ;; declared ON the compound — :same-state + :reenter? re-enters :session
             :on      {:reauth {:target :same-state :reenter? true :action :renew}}
             :states
             {:active {:entry :enter-active :exit :exit-active}}}}}]
      (rf/reg-machine :self/compound-same-state machine)
      (rf/dispatch-sync [:self/compound-same-state [:rf2-46ban/prime]])  ;; consume bootstrap entries
      (reset! log [])
      (rf/dispatch-sync [:self/compound-same-state [:reauth]])
      (is (= [:session :active] (:state (snapshot :self/compound-same-state)))
          "compound external self-transition re-descends the :initial child")
      (is (= [:exit-active :exit-session :renew :enter-session :enter-active] @log)
          "exit cascade leaf→root → action → entry cascade root→leaf (re-runs :initial)")))

  (testing "DEFAULT self-transition on a COMPOUND state (:same-state, NO
            :reenter?) RE-RESOLVES its descendants — the compound itself is NOT
            exited/re-entered, but its active child is exited and the compound's
            :initial re-descends. XState v5: 'an explicit target re-resolves
            child states to their initial' (rf2-gt1pu, correcting an earlier
            rf2-eicq0 over-collapse). Verified against xstate@5.32.0."
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

;; ---- external (:reenter? true) transition to a PROPER ANCESTOR -----------
;; (LCCA ancestor-restart geometry)
;;
;; Per Spec 005 §Entry/exit cascading along the LCCA + XState v5 / SCXML
;; §3.13: a `:reenter? true` transition from a descendant leaf to one of its
;; PROPER ANCESTORS A restarts A — A's active subtree (including A) exits, the
;; transition action fires at the LCCA (A's parent), then A re-enters and
;; re-descends its `:initial` chain. RE-ENTERING THE ANCESTOR ITSELF is
;; OPT-IN (`:reenter? true`); an ancestor target WITHOUT `:reenter?` does
;; NOT exit/re-enter the ancestor BUT still RE-RESOLVES its active
;; descendants (children reset to :initial) — it is not a no-op.
;; This ns exercises the LIVE runtime (`reg-machine` / `dispatch-sync`);
;; the pure-engine geometry + ordering is pinned in the SCXML conformance
;; corpus (`scxml-external-transition-to-proper-ancestor-*`).
(deftest machine-ancestor-restart-cljs
  (testing ":reenter? true transition to a proper ANCESTOR restarts that
            ancestor — exit subtree (incl. ancestor) → action → re-enter
            ancestor → re-init its :initial child; configuration restored to
            the leaf"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :p
           :data    {}
           :actions {:enter-p (tag :enter-p) :exit-p (tag :exit-p)
                     :enter-a (tag :enter-a) :exit-a (tag :exit-a)
                     :enter-x (tag :enter-x) :exit-x (tag :exit-x)
                     :restart (tag :restart)}
           :states
           {:p {:entry :enter-p :exit :exit-p          ;; LCCA — neither re-fires
                :initial :a
                :states
                {:a {:entry :enter-a :exit :exit-a
                     :initial :x
                     :states
                     ;; :x targets its grandparent :a (a proper ancestor) by
                     ;; absolute vector + :reenter?; :a's :initial re-descends
                     ;; back to :x.
                     {:x {:entry :enter-x :exit :exit-x
                          :on {:restart {:target [:p :a] :reenter? true :action :restart}}}}}}}}}]
      (rf/reg-machine :ancestor/restart machine)
      ;; Drive the bootstrap initial-cascade with a benign unhandled event,
      ;; then reset the log so only the :restart transition is observed.
      (rf/dispatch-sync [:ancestor/restart [:rf2-emz8l/prime]])
      (reset! log [])
      (rf/dispatch-sync [:ancestor/restart [:restart]])
      (is (= [:p :a :x] (:state (snapshot :ancestor/restart)))
          "restarting :a re-descends its :initial back to [:p :a :x]")
      (is (= [:exit-x :exit-a :restart :enter-a :enter-x] @log)
          "exit :x then :a (deepest-first, incl. the ancestor) → action → re-enter :a → re-init :x; :p (the LCCA) does NOT re-fire")))

  (testing ":reenter? true ancestor target re-INITIALISES to the ancestor's
            :initial child even when a non-initial child was active at restart
            time"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :p
           :data    {}
           :actions {:enter-a (tag :enter-a)   :exit-a (tag :exit-a)
                     :enter-one (tag :enter-1) :exit-one (tag :exit-1)
                     :enter-two (tag :enter-2) :exit-two (tag :exit-2)}
           :states
           {:p {:initial :a
                :states
                {:a {:entry :enter-a :exit :exit-a
                     :initial :one
                     :states
                     {:one {:entry :enter-one :exit :exit-one
                            :on {:to-two :two}}
                      :two {:entry :enter-two :exit :exit-two
                            ;; restart the ancestor from the NON-initial child
                            :on {:restart {:target [:p :a] :reenter? true}}}}}}}}}]
      (rf/reg-machine :ancestor/reinit machine)
      ;; Bootstrap to [:p :a :one], then advance to the non-initial child :two.
      (rf/dispatch-sync [:ancestor/reinit [:rf2-emz8l/prime]])
      (rf/dispatch-sync [:ancestor/reinit [:to-two]])
      (is (= [:p :a :two] (:state (snapshot :ancestor/reinit)))
          "now resting at the non-initial child :two")
      (reset! log [])
      (rf/dispatch-sync [:ancestor/reinit [:restart]])
      (is (= [:p :a :one] (:state (snapshot :ancestor/reinit)))
          "restart re-initialises :a to its :initial child :one (not back to :two)")
      (is (= [:exit-2 :exit-a :enter-a :enter-1] @log)
          "exit :two then :a → re-enter :a → re-init :one")))

  ;; ---- ancestor target WITHOUT :reenter? RE-RESOLVES descendants -----------
  (testing "DEFAULT ancestor target (NO :reenter?) does NOT re-enter the
            ancestor itself, but RE-RESOLVES its active descendants — children
            reset to the ancestor's :initial. XState v5: 'an explicit target
            re-resolves child states to their initial' (rf2-gt1pu, correcting
            an earlier rf2-eicq0 over-collapse). Verified against xstate@5.32.0."
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :p
           :data    {}
           :actions {:enter-a (tag :enter-a)   :exit-a (tag :exit-a)
                     :enter-one (tag :enter-1) :exit-one (tag :exit-1)
                     :enter-two (tag :enter-2) :exit-two (tag :exit-2)
                     :touch     (tag :touch)}
           :states
           {:p {:initial :a
                :states
                {:a {:entry :enter-a :exit :exit-a   ;; the target — NOT re-entered
                     :initial :one
                     :states
                     {:one {:entry :enter-one :exit :exit-one
                            :on {:to-two :two}}
                      :two {:entry :enter-two :exit :exit-two
                            ;; ancestor target, NO :reenter? — re-resolves :a's descendants
                            :on {:touch {:target [:p :a] :action :touch}}}}}}}}}]
      (rf/reg-machine :ancestor/default-internal machine)
      (rf/dispatch-sync [:ancestor/default-internal [:rf2-eicq0/prime]])
      (rf/dispatch-sync [:ancestor/default-internal [:to-two]])
      (is (= [:p :a :two] (:state (snapshot :ancestor/default-internal)))
          "resting at the non-initial child :two")
      (reset! log [])
      (rf/dispatch-sync [:ancestor/default-internal [:touch]])
      (is (= [:p :a :one] (:state (snapshot :ancestor/default-internal)))
          "ancestor target re-resolves :a's descendants — active child resets to :a's :initial (:one)")
      (is (= [:exit-2 :touch :enter-1] @log)
          "exit the active descendant :two → action at the LCCA (:a) → re-enter :a's :initial (:one); :a and :p NOT exited/entered (no :reenter?)"))))

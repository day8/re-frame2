(ns day8.re-frame2-causa.panels.machine-inspector-view-cljs-test
  "View tests for the collapsed Machine Inspector panel (rf2-y9xmf).

  Post-collapse the Dynamic Machines panel is event-driven only:

    - BLANK when the focused event has no machine activity.
    - One per-machine section (topology + transition highlight + guards +
      actions + cascade + rings) when the focused event triggered a
      transition.
    - prev/next nav walks the spine to the prior/next event touching
      THE focused machine.

  ## Pure hiccup

  Same approach as every other Causa view test — walk the rendered
  hiccup tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers -----------------------------------------------------
;; Thin aliases over re-frame.test-helpers so the local call sites read
;; identically to before.

(def ^:private find-by-testid           th/find-by-testid)
(def ^:private find-all-by-testid-prefix th/find-by-testid-prefix)

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.causa/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.causa/set-machine-definitions-override-for-test definitions]))

(defn- override-epoch-history! [history]
  (rf/dispatch-sync
    [:rf.causa/set-epoch-history-for-test history]))

(defn- focus-epoch! [epoch-id]
  (rf/dispatch-sync
    [:rf.causa/set-focus-epoch-id-for-test epoch-id]))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-machine-inspector-handlers
  (testing "register-causa-handlers! installs the post-collapse handlers"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/registered-machines)))
    (is (some? (registrar/handler :sub :rf.causa/machine-snapshots)))
    (is (some? (registrar/handler :sub :rf.causa/machine-definitions)))
    (is (some? (registrar/handler :sub :rf.causa/machine-definitions-override)))
    (is (some? (registrar/handler :sub :rf.causa/selected-machine-id)))
    (is (some? (registrar/handler :sub :rf.causa/machine-inspector-data)))
    (is (some? (registrar/handler
                 :sub :rf.causa/machine-transitions-for-focused-event)))
    (is (some? (registrar/handler :sub :rf.causa/machine-scrubber-position)))
    (is (some? (registrar/handler :event :rf.causa/select-machine-id)))
    (is (some? (registrar/handler :event :rf.causa/clear-machine-selection)))
    (is (some? (registrar/handler :event :rf.causa/machine-state-clicked)))
    (is (some? (registrar/handler :event :rf.causa/machine-focus-prev)))
    (is (some? (registrar/handler :event :rf.causa/machine-focus-next)))
    (is (some? (registrar/handler :event :rf.causa/set-scrubber-position)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-registered-machines-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-machine-snapshots-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-machine-definitions-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-epoch-history-for-test)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-focus-epoch-id-for-test)))))

(deftest composite-defaults-to-empty-when-no-override
  (testing "with an empty machines override the composite returns the
            empty-shape map"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [])
      (let [d @(rf/subscribe [:rf.causa/machine-inspector-data])]
        (is (= [] (:machines d)))
        (is (= 0 (:total d)))
        (is (= :no-machines (:empty-kind d)))))))

;; ---- (2) empty state (no machines registered) --------------------------

(deftest empty-state-renders-when-no-machines
  (testing "with the override-empty machines slot the panel renders
            the empty-state surface"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector")))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-empty"))
            "empty-state container present")
        ;; rf2-6xezz · Mike-direction 2026-05-21 — the panel-icon
        ;; lived inside the deleted h1 heading.
        (is (nil? (find-by-testid tree "rf-causa-machine-inspector-panel-icon"))
            "panel header icon is gone (lived in the scrubbed h1)")))))

;; ---- (3) blank state (event has no machine activity) ------------------
;;
;; rf2-zdfbm — visibility-gate polarity. The Dynamic Machines panel is
;; **event-driven only** (panel docstring §4-15, Mike's 2026-05-19
;; redesign): TRULY BLANK when the focused event triggered no machine
;; transition; the per-machine topology surface ONLY mounts when a
;; transition fired. The earlier rf2-t5wp9 variant rendered an all-
;; machines topology in the blank state, which inverted the panel's
;; visibility (content on non-machine events, drowning the lens job).
;; These tests pin the correct (non-inverted) polarity.

(deftest blank-state-is-truly-blank-when-focused-event-has-no-machine-activity
  (testing "when machines are registered but the focused event triggered
            no transitions, the panel renders the TRULY BLANK affordance:
            the blank container is present, NO per-machine topology
            section/chart is rendered, and the focused-event surface is
            suppressed (rf2-zdfbm visibility gate)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login :checkout/flow])
      (override-definitions! {:auth/login    fixture-definition
                              :checkout/flow fixture-definition})
      (let [tree (machine-inspector/Panel)
            root (find-by-testid tree "rf-causa-machine-inspector")]
        (is (= "focused-event" (:data-view-mode (second root))))
        (is (= "false" (:data-has-records (second root))))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-blank"))
            "blank-state container present")
        (is (nil? (find-by-testid tree "rf-causa-machine-focused-event"))
            "no focused-event surface when cascade has no transitions")
        ;; The inversion guard — NO topology renders on a non-machine
        ;; event. Pre-fix (rf2-t5wp9) this surfaced one section + chart
        ;; per registered machine, producing the "content on non-machine
        ;; events" half of the inverted-visibility bug.
        (is (empty? (find-all-by-testid-prefix
                      tree "rf-causa-machine-inspector-blank-section-"))
            "no per-machine topology sections render in the blank state")
        (is (empty? (find-all-by-testid-prefix
                      tree "rf-causa-machine-inspector-blank-topology-"))
            "no topology chart renders in the blank state")
        (is (empty? (find-all-by-testid-prefix
                      tree "rf-causa-machines-topology"))
            "no Topology view mounts when the focused event is not
             machine-related")))))

(deftest blank-state-stays-blank-on-an-event-less-focused-epoch
  (testing "an explicitly-focused epoch whose :trace-events carry no
            machine transitions keeps the panel blank — the gate does
            not leak a topology even when epoch-history holds prior
            transitions for the machine (rf2-zdfbm)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      ;; epoch 1 carries an :auth/login transition; epoch 2 (the
      ;; focused epoch) is event-less. The panel must stay blank.
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :idle :data {}}
                   :after  {:state :authing :data {}}
                   :event [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}
         {:epoch-id 2 :trace-events []}])
      (focus-epoch! 2)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-blank"))
            "blank-state container present on the event-less focused epoch")
        (is (nil? (find-by-testid tree "rf-causa-machine-focused-event"))
            "focused-event surface suppressed — no transition this epoch")
        (is (empty? (find-all-by-testid-prefix
                      tree "rf-causa-machines-topology"))
            "no topology renders for a focused epoch with no transition")))))

;; ---- (4) focused-event lens (one section per transition) --------------

(deftest focused-event-lens-renders-one-section-per-transition
  (testing "an epoch whose :trace-events carry ≥ 1 :rf.machine/transition
            events yields one section per record"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1 :trace-events []}
         {:epoch-id 2
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle    :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:auth/submit]
                   :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 2)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-focused-event"))
            "the focused-event surface mounts when the cascade has a transition")
        (is (some? (find-by-testid
                     tree "rf-causa-machine-focused-event-section-auth/login"))
            "one section per transitioned machine")
        (is (some? (find-by-testid
                     tree "rf-causa-machine-focused-event-chart"))
            "the section renders the topology chart")
        (is (nil? (find-by-testid tree "rf-causa-machine-inspector-blank"))
            "the blank-state is suppressed when records exist")))))

(deftest gate-reads-the-migrated-rf-machine-transition-op-only
  (testing "the machine-relatedness gate keys on the `:rf.*` migrated op
            `:rf.machine/transition` (post-#1973). A focused epoch whose
            trace carries ONLY the pre-migration `:machine/transition`
            op does NOT trip the gate — the panel stays blank — while
            the migrated op shows the focused-event surface. Pins that
            the op name is load-bearing for detection (rf2-zdfbm): a
            stale `:machine/transition` read would misfire the gate and
            invert the panel's visibility."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      ;; epoch 1 — only the LEGACY (pre-#1973) op. Must NOT trip the gate.
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :idle :data {}}
                   :after  {:state :authing :data {}}
                   :event [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree (machine-inspector/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-machine-focused-event"))
            "legacy `:machine/transition` op does NOT show the focused-
             event surface")
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-blank"))
            "panel stays blank when only the legacy op is present"))
      ;; epoch 2 — the MIGRATED op. Must trip the gate.
      (override-epoch-history!
        [{:epoch-id 2
          :trace-events
          [{:id 2 :time 20 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :idle :data {}}
                   :after  {:state :authing :data {}}
                   :event [:auth/submit] :rf.trace/dispatch-id "d-2"}}]}])
      (focus-epoch! 2)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-focused-event"))
            "migrated `:rf.machine/transition` op shows the focused-event
             surface")
        (is (nil? (find-by-testid tree "rf-causa-machine-inspector-blank"))
            "blank suppressed when the migrated op fired")))))

(deftest focused-event-section-emits-from-and-to-highlight-ids
  (testing "the per-section chart carries data-from/to-highlight-id so
            the chart's render path applies the dashed-origin + bold-
            landing visual grammar"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle    :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree   (machine-inspector/Panel)
            chart  (find-by-testid
                     tree "rf-causa-machine-focused-event-chart")]
        (is (some? chart))
        (is (= "idle"    (:data-from-highlight-id (second chart))))
        (is (= "authing" (:data-to-highlight-id   (second chart))))))))

(deftest focused-event-lens-renders-multi-machine-cascade
  (testing "a cascade triggering transitions across multiple machines
            yields one section per machine, document-order"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login :checkout/flow :session/clock])
      (override-definitions! {:auth/login    fixture-definition
                              :checkout/flow fixture-definition
                              :session/clock fixture-definition})
      (override-epoch-history!
        [{:epoch-id 7
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle    :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}
           {:id 2 :time 11 :operation :rf.machine/transition
            :tags {:machine-id :checkout/flow
                   :before     {:state :idle :data {}}
                   :after      {:state :done :data {}}
                   :event      [:cart/sync] :rf.trace/dispatch-id "d-1"}}
           {:id 3 :time 12 :operation :rf.machine/transition
            :tags {:machine-id :session/clock
                   :before     {:state :idle :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:tick] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 7)
      (let [tree     (machine-inspector/Panel)
            sections (find-all-by-testid-prefix
                       tree "rf-causa-machine-focused-event-section-")]
        (is (= 3 (count sections))
            "three sections — one per transitioned machine")
        (is (= [":auth/login" ":checkout/flow" ":session/clock"]
               (mapv #(:data-machine-id (second %)) sections))
            "sections appear in cascade document order")))))

;; ---- (5) per-machine prev/next nav -------------------------------------

(deftest prev-next-nav-renders-when-a-machine-is-in-scope
  (testing "the per-machine prev/next nav appears in the header whenever
            the focused event has at least one machine section"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle    :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid
                     tree "rf-causa-machine-inspector-prev-next-nav"))
            "prev/next nav is visible when a machine is in scope")
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-prev")))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-next")))))))

(deftest prev-next-nav-hidden-in-blank-state
  (testing "the per-machine prev/next nav is hidden when no machine is
            in scope (the blank state)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-blank")))
        (is (nil? (find-by-testid
                    tree "rf-causa-machine-inspector-prev-next-nav"))
            "no nav when there is no machine in scope")))))

(deftest machine-focus-prev-walks-to-prior-event-touching-machine
  (testing "dispatching :rf.causa/machine-focus-prev moves the spine's
            focus to the prior epoch that ALSO touched the focused
            machine — skipping epochs whose cascade did not touch it"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login :checkout/flow])
      (override-definitions! {:auth/login    fixture-definition
                              :checkout/flow fixture-definition})
      ;; Epoch history: e1 touches :auth/login, e2 touches :checkout/flow only
      ;; (must be skipped), e3 touches :auth/login (the current focus).
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :idle :data {}}
                   :after  {:state :authing :data {}}
                   :event [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}
         {:epoch-id 2
          :trace-events
          [{:id 2 :time 20 :operation :rf.machine/transition
            :tags {:machine-id :checkout/flow
                   :before {:state :idle :data {}}
                   :after  {:state :done :data {}}
                   :event [:cart/sync] :rf.trace/dispatch-id "d-2"}}]}
         {:epoch-id 3
          :trace-events
          [{:id 3 :time 30 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :authing :data {}}
                   :after  {:state :done :data {}}
                   :event [:auth/done] :rf.trace/dispatch-id "d-3"}}]}])
      (focus-epoch! 3)
      (rf/dispatch-sync [:rf.causa/machine-focus-prev])
      (let [causa-db (frame/frame-app-db-value :rf/causa)]
        (is (= 1 (get-in causa-db [:focus :epoch-id]))
            "focus stepped from epoch 3 → epoch 1, skipping epoch 2")))))

(deftest machine-focus-next-walks-to-next-event-touching-machine
  (testing "dispatching :rf.causa/machine-focus-next moves the spine's
            focus forward to the next epoch that touched the focused
            machine"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login :checkout/flow])
      (override-definitions! {:auth/login    fixture-definition
                              :checkout/flow fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :idle :data {}}
                   :after  {:state :authing :data {}}
                   :event [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}
         {:epoch-id 2
          :trace-events
          [{:id 2 :time 20 :operation :rf.machine/transition
            :tags {:machine-id :checkout/flow
                   :before {:state :idle :data {}}
                   :after  {:state :done :data {}}
                   :event [:cart/sync] :rf.trace/dispatch-id "d-2"}}]}
         {:epoch-id 3
          :trace-events
          [{:id 3 :time 30 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :authing :data {}}
                   :after  {:state :done :data {}}
                   :event [:auth/done] :rf.trace/dispatch-id "d-3"}}]}])
      (focus-epoch! 1)
      (rf/dispatch-sync [:rf.causa/machine-focus-next])
      (let [causa-db (frame/frame-app-db-value :rf/causa)]
        (is (= 3 (get-in causa-db [:focus :epoch-id]))
            "focus stepped from epoch 1 → epoch 3, skipping epoch 2")))))

(deftest machine-focus-prev-is-noop-at-history-edge
  (testing "stepping prev when the focused epoch is already the first
            touching the machine leaves the focus untouched"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :idle :data {}}
                   :after  {:state :authing :data {}}
                   :event [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (rf/dispatch-sync [:rf.causa/machine-focus-prev])
      (let [causa-db (frame/frame-app-db-value :rf/causa)]
        (is (= 1 (get-in causa-db [:focus :epoch-id]))
            "focus stays at epoch 1 — no prior match")))))

;; ---- (6) events ---------------------------------------------------------

(deftest select-machine-id-event-writes-to-causa-frame
  (testing ":rf.causa/select-machine-id stores the id on the Causa frame
            (kept for share-URL + Sim-engine compatibility)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-machine-id :checkout/flow])
      (is (= :checkout/flow @(rf/subscribe [:rf.causa/selected-machine-id]))))))

(deftest clear-machine-selection-drops-the-pick
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-machine-id :checkout/flow])
    (rf/dispatch-sync [:rf.causa/clear-machine-selection])
    (is (nil? @(rf/subscribe [:rf.causa/selected-machine-id])))))

(deftest scrubber-position-slot-defaults-to-present
  (testing "the scrubber-position slot defaults to :present (the share-
            URL round-trips this slot even though the scrubber UI is gone)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= :present @(rf/subscribe [:rf.causa/machine-scrubber-position]))))))

(deftest set-scrubber-position-event-writes-the-slot
  (testing ":rf.causa/set-scrubber-position writes the slot for the
            share-URL surface"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-scrubber-position 3])
      (is (= 3 @(rf/subscribe [:rf.causa/machine-scrubber-position])))
      (rf/dispatch-sync [:rf.causa/set-scrubber-position :present])
      (is (= :present @(rf/subscribe [:rf.causa/machine-scrubber-position]))))))

;; ---- (7) frame isolation ------------------------------------------------

(deftest selection-state-does-not-leak-into-default-frame
  (testing "the panel's selection state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :auth/login (:selected-machine-id causa-db))
          "selection lands on Causa")
      (is (nil? (:selected-machine-id default-db))
          "selection did NOT leak into :rf/default"))))

;; ---------------------------------------------------------------------------
;; rf2-ppzid — React unique-key warning regression guard. The for-loop
;; in `focused-event-view` previously attached `^{:key …}` reader meta
;; to a function-call list form, losing the key. The fix routes per-row
;; children through `with-meta` so the `:key` meta lands on the
;; returned `[:section …]` vector. This test asserts the regression
;; cannot recur silently.
;; ---------------------------------------------------------------------------

(defn- meta-preserving-children [node]
  (cond
    (and (vector? node) (fn? (first node)))
    [(apply (first node) (rest node))]

    (vector? node)
    (if (map? (second node))
      (drop 2 node)
      (rest node))

    (seq? node) node

    :else nil))

(defn- raw-find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (tree-seq (some-fn vector? seq?) meta-preserving-children tree)))

(deftest focused-event-sections-carry-key-meta
  (testing "focused-event-section per-record for-loop ships per-section
            children carrying :key meta on the returned [:section …]
            vector (rf2-ppzid)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login :checkout/flow])
      (override-definitions! {:auth/login    fixture-definition
                              :checkout/flow fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before {:state :idle :data {}}
                   :after  {:state :authing :data {}}
                   :event [:auth/submit] :rf.trace/dispatch-id "d-1"}}
           {:id 2 :time 11 :operation :rf.machine/transition
            :tags {:machine-id :checkout/flow
                   :before {:state :idle :data {}}
                   :after  {:state :done :data {}}
                   :event [:cart/sync] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree     (machine-inspector/Panel)
            sections (raw-find-all-by-testid-prefix
                       tree "rf-causa-machine-focused-event-section-")]
        (when (seq sections)
          (doseq [section sections]
            (is (vector? section) "focused-event-section is a hiccup vector")
            (is (some? (some-> (meta section) :key))
                (str "focused-event-section carries :key meta — got "
                     (pr-str (meta section))))))))))

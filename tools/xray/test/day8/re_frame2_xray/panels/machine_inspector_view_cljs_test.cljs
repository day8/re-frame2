(ns day8.re-frame2-xray.panels.machine-inspector-view-cljs-test
  "View tests for the collapsed Machine Inspector panel (rf2-y9xmf).

  Post-collapse the Dynamic Machines panel is event-driven only:

    - BLANK when the focused event has no machine activity.
    - One per-machine section (topology + transition highlight + guards +
      actions + cascade + rings) when the focused event triggered a
      transition.
    - prev/next nav walks the spine to the prior/next event touching
      THE focused machine.

  ## Pure hiccup

  Same approach as every other Xray view test — walk the rendered
  hiccup tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            ;; Boot the optional machines artefact's late-bind hooks so
            ;; `reg-machine*` resolves rather than throwing
            ;; `:rf.error/machines-artefact-missing`.
            [re-frame.machines]
            ;; rf2-kq8nac (EP-0005) — the snapshot-egress chokepoint +
            ;; the schemas walker the `:data-schema` redaction bridge
            ;; consults. Required so the redaction tests can register a
            ;; machine carrying a `:sensitive?` `:data-schema` slot and
            ;; run a real transition trace through `project-trace-event`.
            [re-frame.classification :as classification]
            [re-frame.elision :as elision]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

;; ---- hiccup walkers -----------------------------------------------------
;; Thin aliases over re-frame.test-helpers so the local call sites read
;; identically to before.

(def ^:private find-by-testid           th/find-by-testid)
(def ^:private find-all-by-testid-prefix th/find-by-testid-prefix)

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (frame/reg-frame :rf/xray {}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.xray/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.xray/set-machine-definitions-override-for-test definitions]))

(defn- override-epoch-history! [history]
  (rf/dispatch-sync
    [:rf.xray/set-epoch-history-for-test history]))

(defn- focus-epoch! [epoch-id]
  (rf/dispatch-sync
    [:rf.xray/set-focus-epoch-id-for-test epoch-id]))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-machine-inspector-handlers
  (testing "register-xray-handlers! installs the post-collapse handlers"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray/registered-machines)))
    (is (some? (registrar/handler :sub :rf.xray/machine-snapshots)))
    (is (some? (registrar/handler :sub :rf.xray/machine-definitions)))
    (is (some? (registrar/handler :sub :rf.xray/selected-machine-id)))
    (is (some? (registrar/handler :sub :rf.xray/machine-inspector-data)))
    (is (some? (registrar/handler
                 :sub :rf.xray/machine-transitions-for-focused-event)))
    (is (some? (registrar/handler :sub :rf.xray/machine-scrubber-position)))
    (is (some? (registrar/handler :event :rf.xray/select-machine-id)))
    (is (some? (registrar/handler :event :rf.xray/clear-machine-selection)))
    (is (some? (registrar/handler :event :rf.xray/machine-state-clicked)))
    (is (some? (registrar/handler :event :rf.xray/machine-focus-prev)))
    (is (some? (registrar/handler :event :rf.xray/machine-focus-next)))
    (is (some? (registrar/handler :event :rf.xray/set-scrubber-position))))
  (testing "rf2-e8330v — production registration installs NO -for-test ids
            and no *-override subs; the test seam installs them"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :sub :rf.xray/machine-definitions-override)))
    (is (nil? (registrar/handler :sub :rf.xray/machine-snapshots-override)))
    (is (nil? (registrar/handler
                :event :rf.xray/set-registered-machines-override-for-test)))
    (is (nil? (registrar/handler
                :event :rf.xray/set-machine-snapshots-override-for-test)))
    (is (nil? (registrar/handler
                :event :rf.xray/set-machine-definitions-override-for-test)))
    (is (nil? (registrar/handler :event :rf.xray/set-epoch-history-for-test)))
    (is (nil? (registrar/handler :event :rf.xray/set-focus-epoch-id-for-test)))
    (xray-test-support/install-test-overrides!)
    (is (some? (registrar/handler :sub :rf.xray/machine-definitions-override)))
    (is (some? (registrar/handler :sub :rf.xray/machine-snapshots-override)))
    (is (some? (registrar/handler
                 :event :rf.xray/set-registered-machines-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.xray/set-machine-snapshots-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.xray/set-machine-definitions-override-for-test)))
    (is (some? (registrar/handler :event :rf.xray/set-epoch-history-for-test)))
    (is (some? (registrar/handler :event :rf.xray/set-focus-epoch-id-for-test)))))

(deftest composite-defaults-to-empty-when-no-override
  (testing "with an empty machines override the composite returns the
            empty-shape map"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines! [])
      (let [d @(rf/subscribe [:rf.xray/machine-inspector-data])]
        (is (= [] (:machines d)))
        (is (= 0 (:total d)))
        (is (= :no-machines (:empty-kind d)))))))

;; ---- (2) empty state (no machines registered) --------------------------

(deftest empty-state-renders-when-no-machines
  (testing "with the override-empty machines slot the panel renders
            the empty-state surface"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines! [])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-xray-machine-inspector")))
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-empty"))
            "empty-state container present")
        ;; rf2-6xezz · Mike-direction 2026-05-21 — the panel-icon
        ;; lived inside the deleted h1 heading.
        (is (nil? (find-by-testid tree "rf-xray-machine-inspector-panel-icon"))
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
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login :checkout/flow])
      (override-definitions! {:auth/login    fixture-definition
                              :checkout/flow fixture-definition})
      (let [tree (machine-inspector/Panel)
            root (find-by-testid tree "rf-xray-machine-inspector")]
        (is (= "focused-event" (:data-view-mode (second root))))
        (is (= "false" (:data-has-records (second root))))
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-blank"))
            "blank-state container present")
        (is (nil? (find-by-testid tree "rf-xray-machine-focused-event"))
            "no focused-event surface when cascade has no transitions")
        ;; The inversion guard — NO topology renders on a non-machine
        ;; event. Pre-fix (rf2-t5wp9) this surfaced one section + chart
        ;; per registered machine, producing the "content on non-machine
        ;; events" half of the inverted-visibility bug.
        (is (empty? (find-all-by-testid-prefix
                      tree "rf-xray-machine-inspector-blank-section-"))
            "no per-machine topology sections render in the blank state")
        (is (empty? (find-all-by-testid-prefix
                      tree "rf-xray-machine-inspector-blank-topology-"))
            "no topology chart renders in the blank state")
        (is (empty? (find-all-by-testid-prefix
                      tree "rf-xray-machines-topology"))
            "no Topology view mounts when the focused event is not
             machine-related")))))

(deftest blank-state-stays-blank-on-an-event-less-focused-epoch
  (testing "an explicitly-focused epoch whose :trace-events carry no
            machine transitions keeps the panel blank — the gate does
            not leak a topology even when epoch-history holds prior
            transitions for the machine (rf2-zdfbm)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-blank"))
            "blank-state container present on the event-less focused epoch")
        (is (nil? (find-by-testid tree "rf-xray-machine-focused-event"))
            "focused-event surface suppressed — no transition this epoch")
        (is (empty? (find-all-by-testid-prefix
                      tree "rf-xray-machines-topology"))
            "no topology renders for a focused epoch with no transition")))))

;; ---- (4) focused-event lens (one section per transition) --------------

(deftest focused-event-lens-renders-one-section-per-transition
  (testing "an epoch whose :trace-events carry ≥ 1 :rf.machine/transition
            events yields one section per record"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
        (is (some? (find-by-testid tree "rf-xray-machine-focused-event"))
            "the focused-event surface mounts when the cascade has a transition")
        (is (some? (find-by-testid
                     tree "rf-xray-machine-focused-event-section-auth/login"))
            "one section per transitioned machine")
        (is (some? (find-by-testid
                     tree "rf-xray-machine-focused-event-chart"))
            "the section renders the topology chart")
        (is (nil? (find-by-testid tree "rf-xray-machine-inspector-blank"))
            "the blank-state is suppressed when records exist")))))

(deftest focused-event-machine-start-renders-topology-not-blank-rf2-eldze
  (testing "a focused machine START / initial-entry epoch (a
            `:rf.machine/started` trace, NO `:rf.machine/transition`)
            renders the topology with the resulting initial state
            highlighted — NOT the 'does not target a state machine' empty
            state (rf2-eldze). Before the fix the focused-event lens only
            projected transitions, so a pure start produced zero records
            and the tab went blank."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:door/main])
      (override-definitions! {:door/main {:initial :closed
                                          :states  {:closed {:on {:push :open}}
                                                    :open   {}}}})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/started
            :tags {:machine-id :door/main
                   :state      :closed
                   :data       {:open? false}
                   :cause      :explicit
                   :rf.trace/dispatch-id "s-1"}}]}])
      (focus-epoch! 1)
      (let [tree    (machine-inspector/Panel)
            section (find-by-testid
                      tree "rf-xray-machine-focused-event-section-door/main")
            chart   (find-by-testid
                      tree "rf-xray-machine-focused-event-chart")]
        (is (some? (find-by-testid tree "rf-xray-machine-focused-event"))
            "the focused-event surface mounts for a machine birth")
        (is (some? section)
            "the per-machine section renders for the started machine")
        (is (= "true" (:data-start (second section)))
            "the section is flagged as a machine-birth record")
        (is (= ":closed" (:data-to-state (second section)))
            "to-state is the resulting initial state")
        (is (some? chart)
            "the topology chart renders for the birth (not the empty state)")
        ;; The initial state is the active state — surfaced as the to-
        ;; highlight on the chart props (no from-highlight on a birth).
        (is (= "closed" (:data-to-highlight-id (second chart)))
            "the initial state is highlighted via to-highlight")
        (is (= "" (:data-from-highlight-id (second chart)))
            "a birth has no from-highlight — there was no prior state")
        ;; rf2-g2axio — the birth story is now told by the SHARED
        ;; mini-pipeline's `:start` cascade row (carrying the `[START]`
        ;; kind pill), not the removed header badge.
        (is (some? (find-by-testid
                     tree "rf-xray-epoch-machine-cascade-kind-start"))
            "the mini-pipeline renders a [START] cascade-row pill for the birth")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-focused-event-start-badge"))
            "the removed header [START] badge no longer renders (rf2-g2axio)")
        (is (nil? (find-by-testid tree "rf-xray-machine-inspector-blank"))
            "the blank-state is suppressed — the bug was an empty tab here")))))

(deftest focused-event-guard-blocked-no-op-renders-topology-not-blank-rf2-skmc7
  (testing "a focused guard-blocked / NO-OP machine event (a
            `:rf.machine.event/unhandled-no-op` trace, NO
            `:rf.machine/transition`) renders the topology with the CURRENT
            state highlighted — NOT the 'does not target a state machine'
            empty state (rf2-skmc7). This is the SAME gap rf2-eldze fixed for
            the START case, for a different no-transition cause: the door
            `:may-close?`-fail close stays in :open as a no-op."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:door/main])
      (override-definitions! {:door/main {:initial :closed
                                          :states  {:closed {:on {:push :open}}
                                                    :open   {:on {:close :closed}}}}})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine.event/unhandled-no-op
            :tags {:machine-id :door/main
                   :state      :open
                   :event      [:door/close]
                   :rf.trace/dispatch-id "n-1"}}]}])
      (focus-epoch! 1)
      (let [tree    (machine-inspector/Panel)
            section (find-by-testid
                      tree "rf-xray-machine-focused-event-section-door/main")
            chart   (find-by-testid
                      tree "rf-xray-machine-focused-event-chart")]
        (is (some? (find-by-testid tree "rf-xray-machine-focused-event"))
            "the focused-event surface mounts for a guard-blocked no-op")
        (is (some? section)
            "the per-machine section renders for the no-op'd machine")
        (is (= "true" (:data-no-op (second section)))
            "the section is flagged as a no-op record")
        (is (= ":open" (:data-to-state (second section)))
            "to-state is the unchanged CURRENT state")
        (is (= ":open" (:data-from-state (second section)))
            "from-state == to-state — the machine stayed put")
        (is (some? chart)
            "the topology chart renders for the no-op (not the empty state)")
        ;; The current state is the active state — surfaced via :current-state
        ;; (NOT a from/to highlight, which would paint a misleading
        ;; state→state self-transition). The wrapper's highlight-id attrs are
        ;; suppressed so the chart paints a single active-state highlight.
        (is (= "" (:data-to-highlight-id (second chart)))
            "no to-highlight — a no-op is not a from→to landing")
        (is (= "" (:data-from-highlight-id (second chart)))
            "no from-highlight — a no-op is not a from→to origin")
        (is (nil? (find-by-testid tree "rf-xray-machine-inspector-blank"))
            "the blank-state is suppressed — the bug was an empty tab here")
        ;; rf2-g2axio — the no-op story now reads off the SHARED
        ;; mini-pipeline's `:no-op` cascade row (the `[NO OP]` qualifier
        ;; chip), not a bespoke header badge / forensic lens. The pipeline
        ;; host mounts; the removed header badge + lens are gone.
        (is (some? (find-by-testid
                     tree "rf-xray-machine-event-handler-mini-pipeline"))
            "the SHARED mini-pipeline mounts for a no-op")
        (is (some? (find-by-testid
                     tree "rf-xray-epoch-machine-cascade-no-op-qualifier"))
            "the mini-pipeline renders a [NO OP] cascade-row qualifier")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-focused-event-no-op-badge"))
            "the removed header [NO-OP] badge no longer renders (rf2-g2axio)")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-focused-transition-lens"))
            "the bespoke forensic lens is removed (rf2-g2axio)")))))

(deftest gate-reads-the-migrated-rf-machine-transition-op-only
  (testing "the machine-relatedness gate keys on the `:rf.*` migrated op
            `:rf.machine/transition` (post-#1973). A focused epoch whose
            trace carries ONLY the pre-migration `:machine/transition`
            op does NOT trip the gate — the panel stays blank — while
            the migrated op shows the focused-event surface. Pins that
            the op name is load-bearing for detection (rf2-zdfbm): a
            stale `:machine/transition` read would misfire the gate and
            invert the panel's visibility."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
        (is (nil? (find-by-testid tree "rf-xray-machine-focused-event"))
            "legacy `:machine/transition` op does NOT show the focused-
             event surface")
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-blank"))
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
        (is (some? (find-by-testid tree "rf-xray-machine-focused-event"))
            "migrated `:rf.machine/transition` op shows the focused-event
             surface")
        (is (nil? (find-by-testid tree "rf-xray-machine-inspector-blank"))
            "blank suppressed when the migrated op fired")))))

(deftest focused-event-section-emits-from-and-to-highlight-ids
  (testing "the per-section chart carries data-from/to-highlight-id so
            the chart's render path applies the dashed-origin + bold-
            landing visual grammar"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
                     tree "rf-xray-machine-focused-event-chart")]
        (is (some? chart))
        (is (= "idle"    (:data-from-highlight-id (second chart))))
        (is (= "authing" (:data-to-highlight-id   (second chart))))))))

(deftest focused-event-lens-binds-to-first-machine-in-trace-order-rf2-8og3k
  (testing "Dynamic-mode single-instance rule (spec/003 §Dynamic mode —
            single-instance, event-driven, rf2-8og3k): when the focused
            event's cascade transitioned multiple machine instances, the
            panel binds to EXACTLY ONE — the first transition trace in
            trace order (earliest `:rf.trace/at`; ties broken by
            trace-emission sequence within the same epoch). The host
            still records the cascade transition count so callers can
            distinguish 'one transition' from 'first of N'."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
            host     (find-by-testid tree "rf-xray-machine-focused-event")
            sections (find-all-by-testid-prefix
                       tree "rf-xray-machine-focused-event-section-")]
        (is (some? host) "focused-event host mounts")
        (is (= "1" (:data-section-count (second host)))
            "exactly one section rendered (Dynamic-mode single-instance)")
        (is (= "3" (:data-cascade-transition-count (second host)))
            "cascade transition count is preserved on the host")
        (is (= 1 (count sections))
            "exactly one machine section — the trace-order tiebreaker winner")
        (is (= [":auth/login"]
               (mapv #(:data-machine-id (second %)) sections))
            "the first-by-trace-order machine wins (lowest :id wins)")))))

;; ---- (4b) the SHARED EVENT HANDLER mini-pipeline (rf2-g2axio) -----------
;;
;; rf2-g2axio redesigned the Machine tab to render EXACTLY THREE elements:
;; Prev/Next + the SHARED EVENT HANDLER mini-pipeline + the chart. The
;; mini-pipeline is the SAME renderer + projection the Epoch panel's EVENT
;; HANDLER step uses — `epoch-view/machine-cascade-mini-pipeline` over the
;; focused epoch's `machine-cascade-rows` projection — so the two surfaces
;; cannot diverge. These tests pin: the bespoke forensic lens / snapshot
;; drill-in / chart-collapse chrome is GONE, the mini-pipeline mounts, and
;; it carries the SAME `rf-xray-epoch-machine-cascade-*` testids the Epoch
;; panel renders.

(deftest machine-tab-renders-exactly-three-elements-rf2-g2axio
  (testing "rf2-g2axio: the Machine tab renders EXACTLY THREE elements —
            the Prev/Next nav, the SHARED EVENT HANDLER mini-pipeline,
            and the chart — and NONE of the removed bespoke chrome (the
            focused-transition lens, the per-machine header ribbon, the
            list/canvas view-mode wrapper, the chart-collapse toggle/
            summary, the snapshot drill-in, the inline cancellation
            cascade)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
        ;; ELEMENT 1 — Prev/Next nav (in the header).
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-prev"))
            "element 1: Prev nav")
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-next"))
            "element 1: Next nav")
        ;; ELEMENT 2 — the SHARED mini-pipeline.
        (is (some? (find-by-testid
                     tree "rf-xray-machine-event-handler-mini-pipeline"))
            "element 2: the SHARED EVENT HANDLER mini-pipeline host")
        ;; ELEMENT 3 — the chart.
        (is (some? (find-by-testid
                     tree "rf-xray-machine-focused-event-chart"))
            "element 3: the topology chart")
        ;; REMOVED chrome — none of it renders.
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-focused-transition-lens"))
            "the bespoke forensic lens is removed")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-focused-event-header"))
            "the per-machine header ribbon is removed")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-snapshot-drill-in"))
            "the snapshot drill-in is removed")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-focused-event-list"))
            "the list/canvas view-mode wrapper is removed")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-chart-toggle-auth/login"))
            "the chart-collapse toggle is removed")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-cancellation-cascade"))
            "the inline cancellation cascade is removed")))))

(deftest machine-tab-mini-pipeline-is-the-shared-renderer-rf2-g2axio
  (testing "rf2-g2axio: the Machine tab's mini-pipeline IS the SAME
            renderer the Epoch panel's EVENT HANDLER step uses — it
            carries the SAME `rf-xray-epoch-handler-machine` cascade host
            + the SAME `rf-xray-epoch-machine-cascade-row-N` /
            `-ordinal-N` testids (no second bespoke renderer)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
        ;; The SHARED cascade host the Epoch panel renders.
        (is (some? (find-by-testid
                     tree "rf-xray-epoch-handler-machine"))
            "the shared `rf-xray-epoch-handler-machine` cascade host mounts")
        (is (some? (find-by-testid
                     tree "rf-xray-epoch-handler-machine-cascade-rows"))
            "the shared cascade rows host mounts")
        ;; The numbered cascade rows the Epoch panel renders — same testids.
        (is (some? (find-by-testid
                     tree "rf-xray-epoch-machine-cascade-row-1"))
            "the first numbered cascade row carries the SHARED testid")
        (is (some? (find-by-testid
                     tree "rf-xray-epoch-machine-cascade-ordinal-1"))
            "the cascade row's left-rail ordinal carries the SHARED testid")
        ;; The EVENT HANDLER orientation line the Epoch panel renders.
        (is (some? (find-by-testid
                     tree "rf-xray-epoch-event-handler-orientation"))
            "the SHARED EVENT HANDLER orientation line renders")))))

(deftest machine-tab-prev-next-moves-mini-pipeline-and-chart-together-rf2-g2axio
  (testing "rf2-g2axio: Prev/Next moves the focused epoch, so BOTH the
            SHARED mini-pipeline AND the chart highlights re-paint to the
            newly-focused epoch together (both read the same focus)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      ;; Two epochs that BOTH touch :auth/login with DIFFERENT transitions
      ;; so Prev visibly changes the chart highlights + the cascade rows.
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle    :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}
         {:epoch-id 2
          :trace-events
          [{:id 2 :time 20 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :authing :data {}}
                   :after      {:state :done    :data {}}
                   :event      [:auth/ok] :rf.trace/dispatch-id "d-2"}}]}])
      ;; Focus the LATER epoch (authing → done).
      (focus-epoch! 2)
      (let [tree  (machine-inspector/Panel)
            chart (find-by-testid tree "rf-xray-machine-focused-event-chart")]
        (is (= "authing" (:data-from-highlight-id (second chart)))
            "chart highlights the focused (later) epoch's from-state")
        (is (= "done" (:data-to-highlight-id (second chart)))
            "chart highlights the focused (later) epoch's to-state")
        (is (some? (find-by-testid
                     tree "rf-xray-machine-event-handler-mini-pipeline"))
            "the mini-pipeline renders for the focused epoch"))
      ;; Prev → the earlier epoch (idle → authing). Both re-read the focus.
      (rf/dispatch-sync [:rf.xray/machine-focus-prev])
      (let [tree  (machine-inspector/Panel)
            chart (find-by-testid tree "rf-xray-machine-focused-event-chart")]
        (is (= "idle" (:data-from-highlight-id (second chart)))
            "after Prev, the chart re-paints to the earlier epoch's from-state")
        (is (= "authing" (:data-to-highlight-id (second chart)))
            "after Prev, the chart re-paints to the earlier epoch's to-state")
        ;; The mini-pipeline reads the SAME focus, so it moved too — the
        ;; orientation line now reads the earlier epoch's pre-state (:idle).
        (let [orient (find-by-testid
                       tree "rf-xray-epoch-event-handler-orientation-state")]
          (is (some? orient)
              "the mini-pipeline's orientation state line renders")
          (is (str/includes? (pr-str orient) "idle")
              "after Prev, the mini-pipeline orientation reads the earlier
               epoch's pre-transition state — it moved WITH the chart"))))))

;; ---- (4c) declared-over-inferred Context shape (rf2-kq8nac · EP-0005) ----
;;
;; The Machine Inspector's focused-event chart surfaces the machine's
;; declared `:data-schema` Context shape (keys + type captions)
;; AUTHORITATIVELY, with the declared-vs-inferred indicator — consistent
;; with the Static Topology view (rf2-3q4k5b) and reusing the SAME
;; `topology-view/static-context-shape` / `static-context-inferred?`
;; (which delegate to machines-viz `context-shape`, no duplicate
;; derivation). When the machine declares no schema the chart falls back
;; to the one-sample inference (rf2-5tz9p's `inferred from :data` badge
;; stays).

(defn- machine-chart-props?
  "True iff `m` is the `MachineChart` props map. `reg-view` wraps the
  component as a MetaFn, so the rendered head is NOT the bare
  `mv-chart/MachineChart` var — match by the props SHAPE instead (the
  unique `:context-band-inferred?` + `:definition` + `:machine-id`
  triple the canvas threads only into the MachineChart mount)."
  [m]
  (and (map? m)
       (contains? m :context-band-inferred?)
       (contains? m :definition)
       (contains? m :machine-id)))

(defn- find-machine-chart-props
  "Walk the rendered panel hiccup and return the props map of the first
  `MachineChart` mount (the inner component the `machine-canvas/Chart`
  wrapper mounts). Depth-first; nil when absent."
  [hiccup]
  (cond
    (and (vector? hiccup) (machine-chart-props? (second hiccup)))
    (second hiccup)

    (vector? hiccup) (some find-machine-chart-props hiccup)
    (seq? hiccup)    (some find-machine-chart-props hiccup)
    :else            nil))

(def ^:private schema-fixture-definition
  "A machine carrying a `:data-schema` so the declared Context shape wins
  over the (deliberately misleading) one-sample `:data` inference."
  {:initial     :idle
   :data        {:retries nil}          ; misleading partial sample
   :data-schema [:map
                 [:retries :int]
                 [:token {:optional true} [:maybe :string]]]
   :states      {:idle    {:on {:start :authing}}
                 :authing {:on {:ok :done}}
                 :done    {:final? true}}})

(deftest focused-event-chart-shows-declared-context-shape-rf2-kq8nac
  (testing "rf2-kq8nac (EP-0005): when the focused machine declares a
            `:data-schema`, the focused-event chart's Context band shows
            the AUTHORITATIVE declared shape (off the schema's :map
            entries, NOT the misleading `:data` sample) and
            `:context-band-inferred?` reaches the chart FALSE (so the
            `inferred from :data` badge drops + `declared` shows)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:session/auth])
      (override-definitions! {:session/auth schema-fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :session/auth
                   :before     {:state :idle    :data {:retries 0}}
                   :after      {:state :authing :data {:retries 1}}
                   :event      [:start] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree  (machine-inspector/Panel)
            props (find-machine-chart-props tree)]
        (is (some? props) "the focused-event chart mounts MachineChart")
        (is (= {:retries "number" :token "string?"}
               (:context-band props))
            "the Context shape is the AUTHORITATIVE declared schema shape,
             not the one-sample inference")
        (is (false? (:context-band-inferred? props))
            "declared schema → :context-band-inferred? false reaches the
             chart (the `inferred from :data` badge drops, `declared`
             shows — consistent with the Static Topology view)")))))

(deftest focused-event-chart-infers-shape-when-no-schema-rf2-kq8nac
  (testing "rf2-kq8nac (EP-0005): absent a `:data-schema` the focused-event
            chart falls back to the one-sample inference and
            `:context-band-inferred?` reaches the chart TRUE (rf2-5tz9p's
            `inferred from :data` badge stays)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:cart/flow])
      (override-definitions! {:cart/flow {:initial :idle
                                          :data    {:hits 0 :trail []}
                                          :states  {:idle {:on {:add :busy}}
                                                    :busy {}}}})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :cart/flow
                   :before     {:state :idle :data {:hits 0 :trail []}}
                   :after      {:state :busy :data {:hits 1 :trail []}}
                   :event      [:add] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree  (machine-inspector/Panel)
            props (find-machine-chart-props tree)]
        (is (some? props))
        (is (= {:hits "number" :trail "vector"} (:context-band props))
            "no schema → shape inferred from one sample of initial :data")
        (is (true? (:context-band-inferred? props))
            "no schema → :context-band-inferred? true (badge stays)")))))

;; ---- (4d) live `:data` view renders redacted (rf2-kq8nac · EP-0005) ------
;;
;; The bead's task #2: VERIFY the panel's live `:data` view renders a
;; `:sensitive?` `:data-schema` slot REDACTED — confirming xray reads the
;; EGRESSED/projected snapshot, never raw machine state. Two surfaces:
;;
;;   1. The SHARED mini-pipeline cascade reads the focused epoch's
;;      `:trace-events`, which are egress-redacted at emit (epoch-capture
;;      sees the `project-trace-event`-projected event). This test drives
;;      a REAL transition trace through `project-trace-event` (the exact
;;      egress chokepoint) before seeding it as the focused epoch, then
;;      asserts no raw secret survives into the rendered panel hiccup.
;;
;;   2. The LIVE `:rf.xray/machine-snapshots` sub reads the RAW frame-db
;;      slot; `redact-live-snapshots` (rf2-kq8nac) routes it through the
;;      SAME chokepoint so a direct snapshot read is redacted too.

(def ^:private redaction-machine-id :session/auth-redaction)

(def ^:private sensitive-schema
  "A `:data-schema` — VALIDATION ONLY post-EP-0025 (props no longer classify).
  The FRAME declares the snapshot `:data` path sensitive (see the tests)."
  [:map
   [:retries :int]
   [:token [:maybe :string]]])

(defn- reg-sensitive-machine! []
  (rf/reg-machine redaction-machine-id
    {:initial     :anon
     :data        {:retries 0 :token nil}
     :data-schema sensitive-schema
     :states      {:anon   {:on {:login :authed}}
                   :authed {}}}))

(defn- declare-redaction-frame-marks!
  "Declare the redaction machine's snapshot `:data` token slot SENSITIVE on
  `frame-id` — the frame-owned classification (EP-0025, the sole app-db
  mechanism), keyed by the absolute runtime-db snapshot path. EP-0025: the
  imperative add-marks API is removed, so we install directly into the frame's
  elision registry (the kept substrate the commit-plane `:sensitive` effect
  writes through)."
  [frame-id]
  (elision/swap-elision-slot! frame-id
    (fn [reg]
      (assoc-in reg [:sensitive-declarations
                     [:rf.runtime/machines :snapshots redaction-machine-id :data :token]]
                {:source :effect}))))

(deftest panel-renders-sensitive-data-slot-redacted-rf2-kq8nac
  (testing "rf2-kq8nac / EP-0025: a FRAME-declared sensitive machine `:data`
            slot does NOT leak its raw value into the Machine Inspector
            panel. The focused epoch's `:trace-events` are the EGRESSED
            (project-trace-event-projected) events — exactly what
            epoch-capture sees at emit — so the `:before` / `:after`
            `:data` the mini-pipeline reads carries `:rf/redacted` in the
            sensitive slot, never the raw token. This pins that the panel
            reads the egressed snapshot, not raw machine state."
    (setup-xray-frame!)
    (reg-sensitive-machine!)
    ;; EP-0025: the FRAME declares the snapshot token slot sensitive (the
    ;; raw event below carries :frame :rf/default, so declare it there).
    (declare-redaction-frame-marks! :rf/default)
    (rf/with-frame :rf/xray
      (override-machines!    [redaction-machine-id])
      (override-definitions! {redaction-machine-id
                              {:initial :anon
                               :data-schema sensitive-schema
                               :states {:anon   {:on {:login :authed}}
                                        :authed {}}}})
      ;; A RAW transition trace carrying the secret token in :before/:after
      ;; :data — exactly what the runtime emits BEFORE egress.
      (let [raw-event {:operation :rf.machine/transition
                       :tags {:machine-id redaction-machine-id
                              :frame      :rf/default
                              :before     {:state :anon
                                           :data  {:retries 0
                                                   :token   "secret-jwt-before"}}
                              :after      {:state :authed
                                           :data  {:retries 1
                                                   :token   "secret-jwt-after"}}
                              :event      [:login]
                              :rf.trace/dispatch-id "d-1"}}
            ;; The egress chokepoint redacts :before/:after :data.token →
            ;; :rf/redacted against the FRAME's declared snapshot path
            ;; (EP-0025). Epoch-capture sees THIS projected event, so the
            ;; panel's :trace-events are redacted.
            egressed  (classification/project-trace-event raw-event)
            egressed* (assoc egressed :id 1 :time 10)]
        ;; The egress projection redacted the token both sides — pinned
        ;; here so a regression in frame-owned redaction surfaces in the panel.
        (is (= :rf/redacted (get-in egressed* [:tags :before :data :token]))
            "egress redacts the sensitive token (before)")
        (is (= :rf/redacted (get-in egressed* [:tags :after :data :token]))
            "egress redacts the sensitive token (after)")
        (override-epoch-history! [{:epoch-id 1 :trace-events [egressed*]}])
        (focus-epoch! 1)
        (let [tree     (machine-inspector/Panel)
              rendered (pr-str tree)]
          (is (some? (find-by-testid tree "rf-xray-machine-focused-event"))
              "the focused-event surface mounts for the redacted transition")
          (is (not (str/includes? rendered "secret-jwt"))
              "the raw sensitive token MUST NOT appear anywhere in the
               rendered panel — the `:data` view reads the EGRESSED
               snapshot (`:rf/redacted` in the sensitive slot), not raw
               machine state"))))))

(deftest live-snapshots-sub-redacts-sensitive-data-rf2-kq8nac
  (testing "rf2-kq8nac / EP-0025: the `:rf.xray/machine-snapshots` sub reads
            the RAW frame-db slot, so it routes each live snapshot through
            the snapshot-egress chokepoint stamped with the target frame. A
            FRAME-declared sensitive `:data` slot in the live snapshot reads
            back `:rf/redacted`; the plain sibling rides verbatim; an
            undeclared machine's snapshot passes through untouched."
    (setup-xray-frame!)
    (reg-sensitive-machine!)
    ;; EP-0025: the inspected (target) frame declares the snapshot path. The
    ;; redaction fn is exercised directly with that frame-id below.
    (declare-redaction-frame-marks! :rf/xray)
    (rf/with-frame :rf/xray
      ;; Seed the live snapshots slot directly (the test override stands
      ;; in for a populated `[:rf.runtime/machines :snapshots]` in runtime-db); the sub
      ;; redacts on read.
      (rf/dispatch-sync
        [:rf.xray/set-machine-snapshots-override-for-test nil])
      ;; Drive the redaction through the live sub by pinning a frame-db
      ;; snapshot. We exercise the sub's redaction fn directly on a
      ;; populated snapshots map (the sub composes target-frame +
      ;; target-frame-db → this map) to keep the assertion independent of a
      ;; live machine runtime under the plain-atom test substrate.
      (let [snaps    {redaction-machine-id
                      {:state :authed
                       :data  {:retries 2 :token "secret-jwt-live"}}}
            redacted (#'machine-inspector/redact-live-snapshots :rf/xray snaps)]
        (is (= :rf/redacted
               (get-in redacted [redaction-machine-id :data :token]))
            "the live snapshot's frame-declared slot is redacted on read")
        (is (= 2 (get-in redacted [redaction-machine-id :data :retries]))
            "the plain sibling rides verbatim")
        (is (not (str/includes? (pr-str redacted) "secret-jwt-live"))
            "no raw secret survives the live-snapshot redaction")))))

(deftest blank-state-renders-verbatim-empty-state-text-rf2-8og3k
  (testing "spec/003 §Empty state — focused event does not target a state
            machine (rf2-8og3k): the panel renders ONLY the verbatim
            placeholder string when the focused event triggered no
            transitions. No chart, no lens, no history ribbon — just
            that one line."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (let [tree    (machine-inspector/Panel)
            blank   (find-by-testid tree "rf-xray-machine-inspector-blank")
            message (find-by-testid tree "rf-xray-machine-inspector-blank-message")]
        (is (some? blank)  "blank-state container present")
        (is (some? message) "verbatim message container present")
        ;; The verbatim string per spec/003 §Empty state.
        (is (= "This event does not target a state machine"
               (last message))
            "the empty-state surface renders the verbatim spec text")
        ;; No lens, no chart, no history ribbon in the empty state.
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-focused-transition-lens"))
            "no lens in the empty state")
        (is (nil? (find-by-testid tree "rf-xray-machine-focused-event-chart"))
            "no chart in the empty state")))))

;; ---- (5) per-machine prev/next nav -------------------------------------

(deftest prev-next-nav-renders-when-a-machine-is-in-scope
  (testing "the per-machine prev/next nav appears in the header whenever
            the focused event has at least one machine section"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
                     tree "rf-xray-machine-inspector-prev-next-nav"))
            "prev/next nav is visible when a machine is in scope")
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-prev")))
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-next")))))))

(deftest prev-next-nav-hidden-in-blank-state
  (testing "the per-machine prev/next nav is hidden when no machine is
            in scope (the blank state)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines! [:auth/login])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-blank")))
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-inspector-prev-next-nav"))
            "no nav when there is no machine in scope")))))

(deftest machine-focus-prev-walks-to-prior-event-touching-machine
  (testing "dispatching :rf.xray/machine-focus-prev moves the spine's
            focus to the prior epoch that ALSO touched the focused
            machine — skipping epochs whose cascade did not touch it"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
      (rf/dispatch-sync [:rf.xray/machine-focus-prev])
      (let [xray-db (frame/frame-app-db-value :rf/xray)]
        (is (= 1 (get-in xray-db [:focus :epoch-id]))
            "focus stepped from epoch 3 → epoch 1, skipping epoch 2")))))

(deftest machine-focus-next-walks-to-next-event-touching-machine
  (testing "dispatching :rf.xray/machine-focus-next moves the spine's
            focus forward to the next epoch that touched the focused
            machine"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
      (rf/dispatch-sync [:rf.xray/machine-focus-next])
      (let [xray-db (frame/frame-app-db-value :rf/xray)]
        (is (= 3 (get-in xray-db [:focus :epoch-id]))
            "focus stepped from epoch 1 → epoch 3, skipping epoch 2")))))

(deftest machine-focus-prev-is-noop-at-history-edge
  (testing "stepping prev when the focused epoch is already the first
            touching the machine leaves the focus untouched"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
      (rf/dispatch-sync [:rf.xray/machine-focus-prev])
      (let [xray-db (frame/frame-app-db-value :rf/xray)]
        (is (= 1 (get-in xray-db [:focus :epoch-id]))
            "focus stays at epoch 1 — no prior match")))))

(deftest machine-focus-prev-routes-through-spine-and-stamps-retro-rf2-nugvv
  (testing "rf2-nugvv — the per-machine prev/next jump mutates focus
            through the spine's `focus-cascade-reducer`, NOT a bare
            `[:focus :epoch-id]` write. A bare epoch-id write is
            silently overridden by `compose-focus`'s LIVE+unpaused
            head-tracking (`eff-epoch-id` snaps back to head), which is
            why the buttons were dead on the live panel. The fix stamps
            `:mode :retro` + resolves the target epoch's settling
            `:dispatch-id` so the navigation sticks — this test pins
            both so the regression cannot recur silently."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
      (focus-epoch! 3)
      (rf/dispatch-sync [:rf.xray/machine-focus-prev])
      (let [xray-db (frame/frame-app-db-value :rf/xray)
            focus   (:focus xray-db)]
        (is (= 1 (:epoch-id focus))
            "focus stepped to epoch 1 (the prior auth/login epoch)")
        (is (= "d-1" (:dispatch-id focus))
            "the target epoch's settling dispatch-id is resolved + pinned
             (the spine-routed mutation, not a bare epoch-id write)")
        (is (= :retro (:mode focus))
            "the jump stamps :mode :retro so compose-focus stops head-
             tracking and the navigation holds")))))

;; ---- (5b) Share affordance removed (rf2-nugvv) -------------------------

(deftest share-button-and-affordance-removed-rf2-nugvv
  (testing "rf2-nugvv (Mike, 2026-06-04) — the Machine panel's Share
            button is removed. Neither the header toolbar button nor the
            share-modal-open wiring survives. The panel was the sole UI
            entry point to the Xray share modal."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
      (let [tree (machine-inspector/Panel)]
        ;; The header still mounts (prev/next nav is in scope).
        (is (some? (find-by-testid tree "rf-xray-machine-inspector-header"))
            "panel header still mounts")
        (is (some? (find-by-testid
                     tree "rf-xray-machine-inspector-prev-next-nav"))
            "prev/next nav remains the header toolbar affordance")
        ;; The Share button is gone.
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-inspector-share-button"))
            "the Share button no longer renders in the panel header"))))
  (testing "the share-modal-open event + the share subs are no longer
            registered — the whole share surface (share.cljs + the modal)
            went with the button"
    (setup-xray-frame!)
    (is (nil? (registrar/handler :event :rf.xray/share-modal-open))
        "share-modal-open event is unregistered")
    (is (nil? (registrar/handler :event :rf.xray/share-modal-close))
        "share-modal-close event is unregistered")
    (is (nil? (registrar/handler :sub :rf.xray/share-modal-open?))
        "share-modal-open? sub is unregistered")
    (is (nil? (registrar/handler :sub :rf.xray/share-url))
        "share-url sub is unregistered")
    (is (nil? (registrar/handler :sub :rf.xray/cascade-export))
        "cascade-export sub (rode the same modal) is unregistered")))

;; ---- (6) events ---------------------------------------------------------

(deftest select-machine-id-event-writes-to-xray-frame
  (testing ":rf.xray/select-machine-id stores the id on the Xray frame
            (kept for Sim-engine + Instances-jump focus; the share-URL
            consumer was removed in rf2-nugvv)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-machine-id :checkout/flow])
      (is (= :checkout/flow @(rf/subscribe [:rf.xray/selected-machine-id]))))))

(deftest clear-machine-selection-drops-the-pick
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/select-machine-id :checkout/flow])
    (rf/dispatch-sync [:rf.xray/clear-machine-selection])
    (is (nil? @(rf/subscribe [:rf.xray/selected-machine-id])))))

(deftest scrubber-position-slot-defaults-to-present
  (testing "the scrubber-position slot defaults to :present (the
            `:after`-rings overlay reads this slot to gate ring rendering
            to the :present position, even though the scrubber UI and the
            share-URL round-trip are both gone)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (= :present @(rf/subscribe [:rf.xray/machine-scrubber-position]))))))

(deftest set-scrubber-position-event-writes-the-slot
  (testing ":rf.xray/set-scrubber-position writes the slot read by the
            `:after`-rings overlay"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-scrubber-position 3])
      (is (= 3 @(rf/subscribe [:rf.xray/machine-scrubber-position])))
      (rf/dispatch-sync [:rf.xray/set-scrubber-position :present])
      (is (= :present @(rf/subscribe [:rf.xray/machine-scrubber-position]))))))

;; ---- (7) frame isolation ------------------------------------------------

(deftest selection-state-does-not-leak-into-default-frame
  (testing "the panel's selection state lives on :rf/xray, never :rf/default"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-machine-id :auth/login]))
    (let [xray-db   (frame/frame-app-db-value :rf/xray)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :auth/login (:selected-machine-id xray-db))
          "selection lands on Xray")
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
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
                       tree "rf-xray-machine-focused-event-section-")]
        (when (seq sections)
          (doseq [section sections]
            (is (vector? section) "focused-event-section is a hiccup vector")
            (is (some? (some-> (meta section) :key))
                (str "focused-event-section carries :key meta — got "
                     (pr-str (meta section))))))))))

;; ---- (6) rf2-3d987 layout fixes -----------------------------------------
;;
;; Pin the 8 cohesive layout changes (rf2-3d987) so a future refactor that
;; quietly drops one of them has a test marker to fail against. The DOM-
;; measurement assertions (sibling gap, side-by-side at ≥800px) live in
;; the Playwright suite — these JVM tests pin the hiccup-level invariants.

(defn- style-of
  "Return the inline :style map of a hiccup node, or nil."
  [node]
  (when (and (vector? node) (map? (second node)))
    (:style (second node))))

(deftest rf2-3d987-issue-1-focused-event-section-has-gap
  (testing "rf2-3d987 issue #1 (preserved through rf2-g2axio): the
            focused-event-section's children sit on a flex column with a
            non-zero :gap so the two surviving sub-panels (the SHARED
            mini-pipeline + the chart) get visible breathing room rather
            than reading as one wall of grey."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
      (let [tree    (machine-inspector/Panel)
            section (find-by-testid
                      tree "rf-xray-machine-focused-event-section-auth/login")
            style   (style-of section)]
        (is (some? section)   "focused-event-section mounts")
        (is (= "flex" (:display style))   "section is a flex container")
        (is (= "column" (:flex-direction style))
            "section is a flex column")
        (is (some? (:gap style))
            "section carries a :gap so siblings get breathing room")))))

(deftest rf2-3d987-issue-8-section-has-panel-breathing-room
  (testing "rf2-3d987 issue #8: focused-event-section's outer margin
            grew from 12px to 16px so the card has visible breathing
            room from the panel host edge at every viewport width."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
      (let [tree    (machine-inspector/Panel)
            section (find-by-testid
                      tree "rf-xray-machine-focused-event-section-auth/login")
            margin  (-> section style-of :margin)]
        (is (some? section)   "section mounts")
        (is (= "16px" margin)
            (str "section uses the 16px (gap-4) margin so it has "
                 "breathing room from the panel host edge — got "
                 (pr-str margin)))))))

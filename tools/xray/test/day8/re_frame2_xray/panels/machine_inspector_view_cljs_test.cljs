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
    (is (some? (registrar/handler :sub :rf.xray/machine-definitions-override)))
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
    (is (some? (registrar/handler :event :rf.xray/set-scrubber-position)))
    (is (some? (registrar/handler
                 :event :rf.xray/set-registered-machines-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.xray/set-machine-snapshots-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.xray/set-machine-definitions-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.xray/set-epoch-history-for-test)))
    (is (some? (registrar/handler
                 :event :rf.xray/set-focus-epoch-id-for-test)))))

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

;; ---- (4b) focused-transition lens (rf2-2n34o · spec/003 §Focused-transition lens) -----

(defn- register-machine-guard-meta! [machine-id guard-id source]
  ;; Bypass `reg-machine` — register the handler-meta entry directly so
  ;; the lens has data to render under JVM/Node tests without booting
  ;; the `reg-machine` macro pipeline.
  (registrar/register!
    :machine-guard [machine-id guard-id]
    {:rf/guard-id        guard-id
     :rf/machine-id      machine-id
     :rf.handler/source  source
     :handler-fn         (fn [_] true)}))

(defn- register-machine-action-meta! [machine-id action-id source]
  (registrar/register!
    :machine-action [machine-id action-id]
    {:rf/action-id       action-id
     :rf/machine-id      machine-id
     :rf.handler/source  source
     :handler-fn         (fn [_] nil)}))

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

(deftest lens-renders-target-instance-and-transition-rf2-2n34o
  (testing "spec/003 §Focused-transition lens — rendered shape: the lens
            block sits above the chart and carries Target Machine
            Instance: + TRANSITION (from → to) lines."
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
            lens   (find-by-testid tree "rf-xray-machine-focused-transition-lens")
            target (find-by-testid tree "rf-xray-machine-lens-target-instance")
            transition (find-by-testid tree "rf-xray-machine-lens-transition")]
        (is (some? lens)        "the focused-transition lens mounts above the chart")
        (is (= ":auth/login" (:data-machine-id (second lens))))
        (is (some? target)      "Target Machine Instance: line present")
        (is (some? transition)  "TRANSITION line present")))))

(deftest lens-renders-guard-source-and-return-rf2-2n34o
  (testing "spec/003 §Focused-transition lens — GUARDS RUN: id + fn-source
            (from `rf/handler-meta :machine-guard`) + return value
            rendered. The trace's `:rf.machine/guard-evaluated` event
            carries the outcome :pass / :fail; the lens prints
            `→ return true` / `→ return false`."
    (setup-xray-frame!)
    (register-machine-guard-meta! :auth/login :token?
      "(fn [data] (get-in data [:session :token]))")
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
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}
           {:id 2 :time 11 :operation :rf.machine/guard-evaluated
            :tags {:machine-id :auth/login :guard-id :token? :outcome :pass}}]}])
      (focus-epoch! 1)
      (let [tree    (machine-inspector/Panel)
            guard   (find-by-testid tree "rf-xray-machine-lens-guard-token?")
            guards-run (find-by-testid tree "rf-xray-machine-lens-guards-run")
            hiccup-strs (->> guard flatten (filter string?) (str/join " "))]
        (is (some? guards-run) "GUARDS RUN section present")
        (is (some? guard) "guard block rendered for :token?")
        (is (= "pass" (:data-outcome (second guard))))
        (is (str/includes? hiccup-strs ":token?")
            "guard id rendered")
        (is (str/includes?
              hiccup-strs "(fn [data] (get-in data [:session :token]))")
            "guard fn-source rendered from handler-meta")
        (is (str/includes? hiccup-strs "→ return true")
            "return value rendered as `→ return true`")))))

(deftest lens-renders-action-source-and-dispatch-follow-on-rf2-2n34o
  (testing "spec/003 §Focused-transition lens — ACTIONS RUN: id + fn-source
            + `:fx :dispatch → [<event>]` follow-on rendered. The action's
            `:outcome` slot on the trace carries the returned `{:fx [...]}`
            map; the lens extracts the `:dispatch` entries."
    (setup-xray-frame!)
    (register-machine-action-meta! :auth/login :fetch!
      "(fn [data] {:fx [[:dispatch [:loading/complete]]]})")
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
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}
           {:id 2 :time 11 :operation :rf.machine/action-ran
            :tags {:machine-id :auth/login
                   :action-id :fetch!
                   :outcome   {:fx [[:dispatch [:loading/complete]]]}}}]}])
      (focus-epoch! 1)
      (let [tree        (machine-inspector/Panel)
            actions-run (find-by-testid tree "rf-xray-machine-lens-actions-run")
            action      (find-by-testid tree "rf-xray-machine-lens-action-fetch!")
            dispatch    (find-by-testid
                          tree "rf-xray-machine-lens-action-dispatch-fetch!-0")
            hiccup-strs (->> action flatten (filter string?) (str/join " "))]
        (is (some? actions-run) "ACTIONS RUN section present")
        (is (some? action) "action block rendered for :fetch!")
        (is (= "1" (:data-dispatch-count (second action))))
        (is (some? dispatch) "downstream :dispatch line rendered")
        (is (str/includes? hiccup-strs ":fetch!"))
        (is (str/includes?
              hiccup-strs "(fn [data] {:fx [[:dispatch [:loading/complete]]]})")
            "action fn-source rendered from handler-meta")
        (is (str/includes?
              hiccup-strs ":fx :dispatch → [:loading/complete]")
            "downstream dispatch event rendered after `→ :fx :dispatch →`")))))

(deftest lens-falls-back-to-placeholder-when-source-absent-rf2-2n34o
  (testing "spec/003 §Focused-transition lens — when no handler-meta is
            registered (programmatic `reg-machine*` path, or production-
            elided), the lens renders a muted '(fn source unavailable)'
            line instead of crashing."
    (setup-xray-frame!)
    ;; No register-machine-guard-meta! call — handler-meta returns nil.
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
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}
           {:id 2 :time 11 :operation :rf.machine/guard-evaluated
            :tags {:machine-id :auth/login :guard-id :token? :outcome :pass}}]}])
      (focus-epoch! 1)
      (let [tree  (machine-inspector/Panel)
            guard (find-by-testid tree "rf-xray-machine-lens-guard-token?")
            hiccup-strs (->> guard flatten (filter string?) (str/join " "))]
        (is (some? guard) "guard block rendered without crashing")
        (is (str/includes? hiccup-strs "(fn source unavailable)")
            "muted fallback rendered when handler-meta is absent")))))

;; ---- (4c) snapshot drill-in (rf2-lxvn6 · spec/021 §10 widget contract) ----

(deftest snapshot-drill-in-renders-before-and-after-snapshots
  (testing "the focused-event section's snapshot drill-in surface mounts
            the BEFORE and AFTER snapshots through the first-class
            edn-inspector widget (rf2-oqa60 phase 1 · rf2-lxvn6 phase 4).
            Per spec/021 §10 the per-machine `:panel-id` qualifier keeps
            two machines' expansion state independent; the `:before` /
            `:after` phase suffix scopes the two sibling mounts on the
            same machine."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle
                                :data  {:user nil :retries 0}}
                   :after      {:state :authing
                                :data  {:user "alice" :retries 1}}
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree   (machine-inspector/Panel)
            drill  (find-by-testid tree "rf-xray-machine-snapshot-drill-in")
            before (find-by-testid
                     tree "rf-xray-machine-snapshot-block-auth/login-before")
            after  (find-by-testid
                     tree "rf-xray-machine-snapshot-block-auth/login-after")]
        (is (some? drill)
            "drill-in section mounts when before/after snapshots are present")
        (is (= ":auth/login" (:data-machine-id (second drill)))
            "drill-in carries the per-machine id so tests can scope assertions")
        (is (= "true" (:data-has-before (second drill)))
            "before snapshot presence surfaced on the host")
        (is (= "true" (:data-has-after (second drill)))
            "after snapshot presence surfaced on the host")
        (is (some? before)
            "BEFORE snapshot block renders")
        (is (some? after)
            "AFTER snapshot block renders")
        (is (= "before" (:data-phase (second before)))
            "BEFORE block carries the :before phase tag")
        (is (= "after" (:data-phase (second after)))
            "AFTER block carries the :after phase tag")))))

(defn- find-popup-affordance-containers
  "Walk hiccup (already expand-tree'd by the find-by-testid helper) and
  collect every edn-inspector container that carries
  `:data-rf-popup-affordance? \"1\"` (the widget surfaces the opt as a
  data-attr on its outer `:div`)."
  [tree]
  (filter (fn [n]
            (and (vector? n) (map? (second n))
                 (= "1" (:data-rf-popup-affordance? (second n)))))
          (tree-seq (some-fn vector? seq?) seq tree)))

(deftest snapshot-drill-in-edn-inspector-carries-popup-affordance
  (testing "rf2-l4625 — every snapshot drill-in edn-inspector mount in
            the Machine Inspector panel passes
            `:popup-affordance? true` so the operator can pop the
            snapshot into the popup overlay. After expansion the widget's
            outer `:div` surfaces the opt as a `data-rf-popup-affordance?`
            attribute."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle
                                :data  {:user nil :retries 0}}
                   :after      {:state :authing
                                :data  {:user "alice" :retries 1}}
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree       (machine-inspector/Panel)
            drill      (find-by-testid tree "rf-xray-machine-snapshot-drill-in")
            containers (find-popup-affordance-containers drill)]
        (is (some? drill) "drill-in section mounts")
        (is (seq containers)
            "drill-in surfaces the popup-affordance attr on its
             edn-inspector containers")))))

(deftest snapshot-drill-in-suppressed-when-legacy-trace-lacks-snapshots
  (testing "legacy trace fixtures that pre-date the commit-or-finalize
            snapshot pair (only `:from`/`:to` keys, no `:before`/`:after`
            maps) suppress the drill-in entirely — the section renders
            nothing rather than empty blocks. This keeps the M.10 surface
            from showing 'snapshot · (uninitialised)' chrome on legacy
            traces."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        ;; Only legacy `:from`/`:to` tags — no `:before`/`:after` snapshots.
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :from       :idle
                   :to         :authing
                   :event      [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree (machine-inspector/Panel)]
        ;; The focused-event surface still mounts (the lens uses the
        ;; from/to legacy slots), but the snapshot drill-in is hidden.
        (is (some? (find-by-testid tree "rf-xray-machine-focused-event"))
            "focused-event surface still mounts on a legacy trace")
        (is (nil? (find-by-testid tree "rf-xray-machine-snapshot-drill-in"))
            "snapshot drill-in is suppressed when before/after snapshots
             are absent")))))

(deftest snapshot-drill-in-renders-when-only-after-snapshot-present
  (testing "an epoch that carries only an `:after` snapshot (e.g. machine
            initialisation events emit no `:before`) still surfaces the
            drill-in — the surface degrades gracefully, rendering only
            the present block."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :after      {:state :idle :data {}}
                   :event      [:auth/init] :rf.trace/dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree  (machine-inspector/Panel)
            drill (find-by-testid tree "rf-xray-machine-snapshot-drill-in")]
        (is (some? drill)
            "drill-in renders when at least one snapshot is present")
        (is (= "false" (:data-has-before (second drill)))
            "before-presence surface reflects the missing :before slot")
        (is (= "true" (:data-has-after (second drill)))
            "after-presence surface reflects the present :after slot")
        (is (nil? (find-by-testid
                    tree "rf-xray-machine-snapshot-block-auth/login-before"))
            "BEFORE block is omitted when its snapshot is nil")
        (is (some? (find-by-testid
                     tree "rf-xray-machine-snapshot-block-auth/login-after"))
            "AFTER block renders when its snapshot is present")))))

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

;; ---- (6) events ---------------------------------------------------------

(deftest select-machine-id-event-writes-to-xray-frame
  (testing ":rf.xray/select-machine-id stores the id on the Xray frame
            (kept for share-URL + Sim-engine compatibility)"
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
  (testing "the scrubber-position slot defaults to :present (the share-
            URL round-trips this slot even though the scrubber UI is gone)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (= :present @(rf/subscribe [:rf.xray/machine-scrubber-position]))))))

(deftest set-scrubber-position-event-writes-the-slot
  (testing ":rf.xray/set-scrubber-position writes the slot for the
            share-URL surface"
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

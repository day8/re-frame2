(ns day8.re-frame2-xray.panels.epoch.hard-machine-fidelity-cljs-test
  "Rendering-FIDELITY assertions for the canonical HARD machine
  (`:hvac/controller`, the `machine_epochs` testbed's MACHINE 4): deep
  compound nesting plus two parallel regions, driven through the LIVE
  substrate. The engine tests assert what the ENGINE PRODUCES; these assert
  what the TOOL DISPLAYS, through the layers that feed the render:

    - `proj/machine-cascade-rows` — the Epoch panel's machine cascade.
    - `mih/project-focused-event-transitions` — the Machine Inspector's
      focused-event view-model.
    - `chart-layout/project-definition` — the machines-viz topology
      projector that feeds the chart.

  The LCA order and the internal / external self-transition cases are the
  deck-wide harness's (`machine-epochs-harness-cljs-test`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as mih]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ============================================================================
;; The hard machine — a self-contained copy of the testbed's :hvac/controller.
;; ============================================================================
;;
;; Kept verbatim-equivalent to `machine_epochs/core.cljs` MACHINE 4 (the
;; trail-action factory + the parallel/compound/self-transition shape). The
;; testbed's copy is what an operator SEES; this copy is what the test DRIVES.
;; A drift between them is acceptable only if the rendered facts asserted
;; below still hold — those facts are the contract, not the literal source.

(defn- trail-action
  "Append one labeled tag to `[:data :trail]` — the cascade-order recorder.
  `label` is `<phase>:<state>`."
  [nm label]
  (with-meta
    (fn [{data :data}]
      {:data (update data :trail (fnil conj []) label)})
    {:name nm}))

(def hvac-controller-machine
  {:type :parallel
   :data {:trail []}
   :regions
   {:climate
    {:initial :idle
     :states
     {:idle
      {:tags #{:climate/idle}
       :on   {:hvac/power-cycle {:target :running :action :enter-running}}}
      :running
      {:tags    #{:climate/running}
       :initial :conditioning
       :entry   :enter-running-level
       :exit    :exit-running-level
       :on      {:hvac/power-cycle {:target :idle :action :back-to-idle}}
       :states
       {:conditioning
        {:tags    #{:climate/conditioning}
         :initial :heating
         :entry   :enter-conditioning
         :exit    :exit-conditioning
         :states
         {:heating
          {:tags  #{:climate/heating}
           :entry :enter-heating
           :exit  :exit-heating
           :on    {:hvac/mode-toggle {:target :cooling :action :swap-mode}}}
          :cooling
          {:tags  #{:climate/cooling}
           :entry :enter-cooling
           :exit  :exit-cooling
           :on    {:hvac/mode-toggle {:target :heating :action :swap-mode}}}}}}}}}
    :fan
    {:initial :off
     :states
     {:off
      {:tags #{:fan/off}
       :on   {:hvac/power-cycle {:target :on :action :fan-on}}}
      :on
      {:tags  #{:fan/on}
       :entry :enter-fan-on
       :exit  :exit-fan-on
       :on    {:hvac/power-cycle {:target :off :action :fan-off}
               ;; :reenter? true — external self-transition
               :hvac/nudge {:target :same-state :reenter? true :action :nudge-fan}
               :hvac/tweak {:action :tweak-fan}}}}}}
   :actions
   {:enter-running       (trail-action 'enter-running       :action:power-on)
    :enter-running-level (trail-action 'enter-running-level :entry:running)
    :exit-running-level  (trail-action 'exit-running-level  :exit:running)
    :back-to-idle        (trail-action 'back-to-idle        :action:power-off)
    :enter-conditioning  (trail-action 'enter-conditioning  :entry:conditioning)
    :exit-conditioning   (trail-action 'exit-conditioning   :exit:conditioning)
    :enter-heating       (trail-action 'enter-heating       :entry:heating)
    :exit-heating        (trail-action 'exit-heating        :exit:heating)
    :enter-cooling       (trail-action 'enter-cooling       :entry:cooling)
    :exit-cooling        (trail-action 'exit-cooling        :exit:cooling)
    :swap-mode           (trail-action 'swap-mode           :action:swap-mode)
    :fan-on              (trail-action 'fan-on              :action:fan-on)
    :fan-off             (trail-action 'fan-off             :action:fan-off)
    :enter-fan-on        (trail-action 'enter-fan-on        :entry:fan-on)
    :exit-fan-on         (trail-action 'exit-fan-on         :exit:fan-on)
    :nudge-fan           (trail-action 'nudge-fan           :action:nudge)
    :tweak-fan           (trail-action 'tweak-fan           :action:tweak)}})

;; ============================================================================
;; fixture
;; ============================================================================

(use-fixtures :each
  ;; `make-xray-runtime-fixture`: the `:all` reset
  ;; tier — install (== preload's alias) + registry + mount idempotency
  ;; sentinels plus the trace-collector rings — over the Reagent adapter
  ;; this suite renders through.
  (xray-test-support/make-xray-runtime-fixture {:adapter rf.adapter.reagent/adapter}))

(defn- setup! []
  (registry/register-xray-handlers!)
  ;; Activate the trace-collector so machine `trace/emit!` calls land in
  ;; Xray's ring buffer (the same surface the Epoch panel reads).
  (preload/register-trace-collector!)
  (rf/reg-machine :hvac/controller hvac-controller-machine)
  ;; Start to the initial parallel configuration; clear any start trace so
  ;; the per-case capture below contains only that case's macrostep.
  (rf/dispatch-sync [:hvac/controller [:rf.machine/start]]))

(defn- drive!
  "Dispatch one event into the hard machine and return the trace stream it
  produced as an epoch-record-shaped map. Resets the trace buffer first so
  the captured cascade is exactly this macrostep."
  [event-v]
  (trace-collector/reset-for-test!)
  (rf/dispatch-sync [:hvac/controller event-v])
  {:event-id     :hvac/controller
   :trigger-event [:hvac/controller event-v]
   :trace-events (vec (trace-collector/buffer-for-test))})

(defn- drive-other!
  "Like `drive!` but for an arbitrary machine event-id (the
  bootstrap-scope guard drives a SECOND machine so the captured macrostep
  is exactly its birth)."
  [machine-id event-v]
  (trace-collector/reset-for-test!)
  (rf/dispatch-sync [machine-id event-v])
  {:event-id      machine-id
   :trigger-event [machine-id event-v]
   :trace-events  (vec (trace-collector/buffer-for-test))})

(defn- cascade [record]
  (proj/machine-cascade-rows (:trace-events record)))

(defn- rows-of-kind [rows kind]
  (filterv #(= kind (:kind %)) rows))

;; ============================================================================
;; PARALLEL regions: one event, BOTH regions render
;; ============================================================================

(deftest power-cycle-renders-parallel-broadcast-legibly
  (testing "`:hvac/power-cycle` is handled by BOTH regions in ONE
            macrostep. A parallel machine commits ONE snapshot per macrostep,
            so the cascade renders a SINGLE aggregate transition row whose
            region→state map shows both regions moved, and action rows from
            both regions."
    (setup!)
    (let [record (drive! [:hvac/power-cycle])
          rows   (cascade record)
          tx     (rows-of-kind rows :transition)
          tx-row (first tx)
          action-ids (set (map :action-id (rows-of-kind rows :action)))
          ;; the inspector view-model the chart + focused-lens consume
          inspector (mih/project-focused-event-transitions
                      (:trace-events record)
                      {:hvac/controller hvac-controller-machine})]
      ;; ONE aggregate transition row — the parallel macrostep commits once.
      (is (= 1 (count tx))
          "a parallel machine renders ONE aggregate transition row per
           macrostep (one snapshot commit)")
      ;; …whose before/after `:state` is a region→state MAP showing BOTH
      ;; regions moved — the legible parallel render the chart highlights.
      (is (= {:climate [:idle] :fan :off} (:from-state tx-row))
          "the transition's FROM renders both regions' prior leaves")
      (is (= {:climate [:running :conditioning :heating] :fan :on}
             (:to-state tx-row))
          "the transition's TO renders both regions' new leaves — climate
           descended its full initial cascade to the deepest leaf, fan swung
           to :on, in one event")
      ;; Action rows from BOTH regions surface in the cascade (the broadcast
      ;; hit both): climate's :fan-on counterpart + fan's entry.
      (is (contains? action-ids :enter-heating)
          "climate region's deep entry action renders in the cascade")
      (is (contains? action-ids :enter-fan-on)
          "fan region's entry action renders in the cascade — proof the one
           event broadcast to both regions")
      ;; No `:no-op` row — both regions handled the event (a genuine parallel
      ;; broadcast is NOT an unhandled no-op).
      (is (empty? (rows-of-kind rows :no-op))
          "a handled parallel broadcast renders NO benign-no-op notice")
      ;; The inspector's focused-event lens projects the macrostep's single
      ;; transition record — its :after snapshot carries BOTH regions so the
      ;; chart highlights a leaf in each.
      (is (= 1 (count inspector))
          "the inspector projects the macrostep's single transition record")
      (is (= {:climate [:running :conditioning :heating] :fan :on}
             (get-in (first inspector) [:after :state]))
          "the inspector record's :after snapshot carries both regions' leaves
           — the chart highlights an active leaf in EACH region"))))

;; ============================================================================
;; DEEP COMPOUND nesting + PARALLEL regions in the chart topology
;; ============================================================================

(deftest topology-projection-renders-compound-and-parallel-legibly
  (testing "the topology projector renders the four-level compound
            path inside a parallel machine, with no single initial path, and
            the summary counts exclude the synthetic layout chrome"
    (let [{:keys [nodes initial-path] :as graph}
          (chart-layout/project-definition hvac-controller-machine)
          ;; Occupiable states only — machines-viz keeps the IN-REGION
          ;; `:path` plus a `:region` tag, so identity is the pair.
          region+path (set (map (juxt :region :path)
                                (remove chart-layout/synthetic-node? nodes)))]
      (is (nil? initial-path)
          "a parallel machine has no single initial path — each region owns its own")
      (is (contains? region+path [:climate [:running :conditioning :heating]])
          "the DEEPEST compound leaf rendered — the four-level path is legible")
      (is (= {:state-count 7 :region-count 2 :transition-count 8}
             (chart-layout/semantic-counts graph))
          "7 occupiable states / 2 regions / 8 transitions — chrome excluded"))))

;; ============================================================================
;; The machine's BIRTH is not the benign no-op
;; ============================================================================

;; A minimal machine whose INITIAL state carries an entry action, so its
;; birth observably runs the `:initial-entry` cascade (the hvac machine's
;; initial leaves `:idle` / `:off` have no entry handlers, so they'd run
;; zero action rows on bootstrap — no positive signal to assert against).
(def initial-entry-machine
  {:initial :booting
   :data    {}
   :states  {:booting {:entry :on-boot}}
   :actions {:on-boot (fn [{data :data}] {:data (assoc data :booted? true)})}})

(deftest bootstrap-renders-initial-entry-not-no-op
  (testing "regression guard — a machine's BIRTH (`[:rf.machine/start]`) runs
            its `:initial-entry` cascade and is never classified as an
            unhandled-event no-op (`[NO OP] staying in {state}` would read
            false for a machine that ENTERED its initial config)"
    (registry/register-xray-handlers!)
    (preload/register-trace-collector!)
    (rf/reg-machine :iu3no/boot initial-entry-machine)
    (let [record  (drive-other! :iu3no/boot [:rf.machine/start])
          rows    (cascade record)
          phases  (set (map :phase (rows-of-kind rows :action)))]
      (is (empty? (rows-of-kind rows :no-op))
          "the start renders NO benign-no-op cell — it is the machine's
           birth, not an ignored event")
      (is (contains? phases :initial-entry)
          "the start runs its :initial-entry cascade — the boot action
           row carries the :initial-entry phase"))))

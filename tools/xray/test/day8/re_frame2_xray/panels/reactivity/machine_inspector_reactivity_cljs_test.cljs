(ns day8.re-frame2-xray.panels.reactivity.machine-inspector-reactivity-cljs-test
  "Sub-reactivity guard for the Machine Inspector panel's focused-event
  lens.

  The bug class — \"No machine activity\" when the panel's
  focused-event sub returns empty rows for cascades that drove real
  machine transitions. It has two sides: focus tracking
  (`:rf.xray/focus :epoch-id` auto-derives in LIVE mode), and the
  framework (machine transitions are emitted with the `:frame`
  tag so epoch capture keeps them).

  This test exercises the panel-side reactivity contract at the unit
  level — synthetic `:trace-events` are injected directly into
  `:epoch-history` via the test seam, so it does NOT depend on the
  framework side to pass; this guard tracks the panel-side reactivity
  invariant independently."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

;; ---- fixtures -----------------------------------------------------------

(defn- epoch-with-transition
  "Build an epoch record whose `:trace-events` carries one
  `:rf.machine/transition` event. The lens reads `:trace-events`
  directly off the focused epoch record (see `focused-epoch-record`
  in `machine_inspector_helpers.cljc`)."
  [epoch-id dispatch-id machine-id from-state to-state event-v]
  (h/mock-epoch epoch-id dispatch-id {} {}
                {:trace-events
                 [(h/machine-transition-event
                    1 machine-id from-state to-state event-v
                    {:frame :rf/default :dispatch-id dispatch-id})]}))

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(def epoch-history
  [(epoch-with-transition :e1 :c1 :title/flow :idle :loading
                          [:title/refresh])
   (epoch-with-transition :e2 :c2 :title/flow :loading :loaded
                          [:title/loaded])])

;; ---- tests --------------------------------------------------------------

(deftest machine-transitions-for-focused-event-tracks-focus-flip
  (testing "the focused-event lens returns different
            per-transition records for the two cascades. Distinct
            from-state / to-state pairs prove the sub re-fired on
            the focus flip."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (is (= [[:idle :loading]]
           (mapv (juxt :from-state :to-state)
                 (h/read-sub :rf.xray/machine-transitions-for-focused-event)))
        "one transition fired in :e1's cascade window: idle → loading")
    (h/focus-cascade! :c2)
    (is (= [[:loading :loaded]]
           (mapv (juxt :from-state :to-state)
                 (h/read-sub :rf.xray/machine-transitions-for-focused-event)))
        "focus flip → the lens re-fired with :e2's transition: loading →
         loaded")))

;; ---- fired-this-epoch edge-ids flow into the lens (G3) ------------------
;;
;; The focused-event lens attaches `:fired-edge-ids` (canonical
;; machines-viz edge-ids — B7) to each per-machine record, so the chart
;; wiring (machine_inspector → machine-canvas/Chart → MachineChart) lands
;; the FIRED treatment on the real chart edges. The ids agree with the
;; live chart by construction (extract-fired-edge-ids projects the same
;; definition through project-definition). This is the wiring half of G3.

(def ^:private toy-flow-definition
  "Two-transition flow whose edges the focused-epoch traces fire:
   :idle --:title/refresh--> :loading --:title/loaded--> :loaded"
  {:initial :idle
   :states  {:idle    {:on {:title/refresh :loading}}
             :loading {:on {:title/loaded :loaded}}
             :loaded  {:final? true}}})

(deftest machine-lens-attaches-canonical-fired-edge-ids
  (testing "G3 wire half — each focused-event record carries
            `:fired-edge-ids`: the canonical machines-viz edge-id for the
            transition that fired this epoch, agreeing with the live chart"
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    ;; Seed the machine definition so extract-fired-edge-ids can project
    ;; it through project-definition (the canonical-id source).
    (h/dispatch-xray!
      [:rf.xray/set-machine-definitions-override-for-test
       {:title/flow toy-flow-definition}])
    (h/focus-cascade! :c1)
    (let [rec (first (h/read-sub :rf.xray/machine-transitions-for-focused-event))
          ;; the canonical id the LIVE chart mints for idle→loading on
          ;; :title/refresh — fired ids MUST equal this (B7 agreement).
          expected-id (->> (:edges (chart-layout/project-definition toy-flow-definition))
                           (some (fn [e]
                                   (when (and (= [:idle]    (:from-path e))
                                              (= [:loading] (:to-path e))
                                              (= :title/refresh (:event e)))
                                     (:id e)))))]
      (is (= #{expected-id} (:fired-edge-ids rec))
          "the record's fired-edge-ids equals the canonical live-chart id"))))

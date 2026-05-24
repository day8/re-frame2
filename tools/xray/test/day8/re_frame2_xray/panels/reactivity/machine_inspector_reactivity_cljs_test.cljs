(ns day8.re-frame2-xray.panels.reactivity.machine-inspector-reactivity-cljs-test
  "Sub-reactivity guard for the Machine Inspector panel's focused-event
  lens (rf2-dhoc9).

  The rf2-2f8jv bug class — \"No machine activity\" when the panel's
  focused-event sub returned empty rows for cascades that drove real
  machine transitions. rf2-70tkv fixed the focus-tracking side
  (`:rf.xray/focus :epoch-id` now auto-derives in LIVE mode), and
  rf2-hwuki (PR #1596, in flight at the time of writing) fixes the
  framework side (machine transitions are emitted with the `:frame`
  tag so epoch capture doesn't drop them).

  This test exercises the post-fix reactivity contract at the unit
  level — synthetic `:trace-events` are injected directly into
  `:epoch-history` via the test seam, so it does NOT depend on the
  framework-side rf2-hwuki fix to pass. Production cascades carrying
  real machine transitions will surface in the lens once rf2-hwuki
  lands; this guard tracks the panel-side reactivity invariant
  independently."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
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
  (testing "rf2-dhoc9 — the focused-event lens returns different
            per-transition records for the two cascades. Distinct
            from-state / to-state pairs prove the sub re-fired on
            the focus flip."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [transitions-1 (h/read-sub :rf.xray/machine-transitions-for-focused-event)]
      (is (= 1 (count transitions-1))
          "one transition fired in :e1's cascade window")
      (is (= :idle (:from-state (first transitions-1)))
          "first transition: idle → loading")
      (is (= :loading (:to-state (first transitions-1))))
      (h/focus-cascade! :c2)
      (let [transitions-2 (h/read-sub :rf.xray/machine-transitions-for-focused-event)]
        (is (= 1 (count transitions-2)))
        (is (= :loading (:from-state (first transitions-2)))
            "second transition: loading → loaded")
        (is (= :loaded (:to-state (first transitions-2))))
        (is (not= transitions-1 transitions-2)
            "machine-inspector lens re-fired with new transition
             records — rf2-70tkv regression class")))))

(deftest machine-lens-tracks-machine-id-across-flip
  (testing "rf2-dhoc9 — the lens projects the focused epoch's
            machine-transition events; flipping focus to an epoch
            with a DIFFERENT machine surfaces the different
            machine-id."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history!
      [(epoch-with-transition :e1 :c1 :title/flow :idle :loading
                              [:title/refresh])
       (epoch-with-transition :e2 :c2 :auth/flow :anon :signed-in
                              [:auth/login])])
    (h/focus-cascade! :c1)
    (let [transitions-1 (h/read-sub :rf.xray/machine-transitions-for-focused-event)]
      (is (= :title/flow (:machine-id (first transitions-1)))))
    (h/focus-cascade! :c2)
    (let [transitions-2 (h/read-sub :rf.xray/machine-transitions-for-focused-event)]
      (is (= :auth/flow (:machine-id (first transitions-2)))
          "focus flip surfaces the new epoch's machine — the lens
           rebinds end-to-end through the sub chain"))))

(deftest machine-lens-empty-on-epoch-without-transitions
  (testing "rf2-dhoc9 — an epoch without transition trace events
            yields `[]`. The reactive contract still holds: when
            focus flips between an epoch-with-transitions and an
            empty epoch, the lens output changes from a populated
            vector to empty."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history!
      [(epoch-with-transition :e1 :c1 :title/flow :idle :loading
                              [:title/refresh])
       ;; :e2 has no :rf.machine/transition events.
       (h/mock-epoch :e2 :c2 {} {})])
    (h/focus-cascade! :c1)
    (is (= 1 (count (h/read-sub :rf.xray/machine-transitions-for-focused-event)))
        "focus :c1 → one transition record")
    (h/focus-cascade! :c2)
    (is (= [] (h/read-sub :rf.xray/machine-transitions-for-focused-event))
        "focus :c2 → empty lens; the sub re-fired to the silent-by-
         default contract (rf2-g3ghh)")))

;; ---- fired-this-epoch edge-ids flow into the lens (rf2-qeemm, G3) -------
;;
;; The focused-event lens attaches `:fired-edge-ids` (canonical
;; machines-viz edge-ids — B7) to each per-machine record, so the chart
;; wiring (machine_inspector → machine-canvas/Chart → MachineChart) lands
;; the FIRED treatment on the real chart edges. The ids agree with the
;; live chart by construction (extract-fired-edge-ids projects the same
;; definition through parse-definition). This is the wiring half of G3.

(def ^:private toy-flow-definition
  "Two-transition flow whose edges the focused-epoch traces fire:
   :idle --:title/refresh--> :loading --:title/loaded--> :loaded"
  {:initial :idle
   :states  {:idle    {:on {:title/refresh :loading}}
             :loading {:on {:title/loaded :loaded}}
             :loaded  {:final? true}}})

(deftest machine-lens-attaches-canonical-fired-edge-ids
  (testing "rf2-qeemm (G3 wire half) — each focused-event record carries
            `:fired-edge-ids`: the canonical machines-viz edge-id for the
            transition that fired this epoch, agreeing with the live chart"
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    ;; Seed the machine definition so extract-fired-edge-ids can project
    ;; it through parse-definition (the canonical-id source).
    (h/dispatch-xray!
      [:rf.xray/set-machine-definitions-override-for-test
       {:title/flow toy-flow-definition}])
    (h/focus-cascade! :c1)
    (let [records (h/read-sub :rf.xray/machine-transitions-for-focused-event)
          rec     (first records)
          ;; the canonical id the LIVE chart mints for idle→loading on
          ;; :title/refresh — fired ids MUST equal this (B7 agreement).
          expected-id (->> (:edges (chart-layout/parse-definition toy-flow-definition))
                           (some (fn [e]
                                   (when (and (= [:idle]    (:from-path e))
                                              (= [:loading] (:to-path e))
                                              (= :title/refresh (:event e)))
                                     (:id e)))))]
      (is (= 1 (count records)) "one transition fired in :e1")
      (is (string? expected-id) "the live chart mints a canonical id")
      (is (= #{expected-id} (:fired-edge-ids rec))
          "the record's fired-edge-ids equals the canonical live-chart id")
      ;; cross-check the canonical id agrees with the B7 helper run on the
      ;; same definition + the focused epoch's transition trace (the same
      ;; computation the sub performs internally).
      (is (= #{expected-id}
             (trace-state/extract-fired-edge-ids
               toy-flow-definition
               [{:operation :rf.machine/transition
                 :tags      {:machine-id :title/flow
                             :before {:state :idle}
                             :after  {:state :loading}}
                 :event     [:title/refresh]}]
               :title/flow))
          ":fired-edge-ids agrees with extract-fired-edge-ids (B7)")
      ;; flipping focus re-fires the lens with the NEXT epoch's fired arm
      (h/focus-cascade! :c2)
      (let [rec-2 (first (h/read-sub :rf.xray/machine-transitions-for-focused-event))
            id-2  (->> (:edges (chart-layout/parse-definition toy-flow-definition))
                       (some (fn [e]
                               (when (and (= [:loading] (:from-path e))
                                          (= [:loaded]  (:to-path e))
                                          (= :title/loaded (:event e)))
                                 (:id e)))))]
        (is (= #{id-2} (:fired-edge-ids rec-2))
            "focus flip → the lens re-fires with the new epoch's fired edge")
        (is (not= (:fired-edge-ids rec) (:fired-edge-ids rec-2))
            "the two epochs fired DIFFERENT edges")))))

(deftest machine-lens-fired-edge-ids-empty-without-definition
  (testing "rf2-qeemm — with NO introspectable definition the fired set is
            empty (extract-fired-edge-ids has no chart edges to match), so
            the chart simply shows no fired highlight"
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    ;; no definition override → :rf.xray/machine-definitions resolves nil
    ;; for :title/flow (machine-meta isn't registered in this unit rig).
    (h/focus-cascade! :c1)
    (let [rec (first (h/read-sub :rf.xray/machine-transitions-for-focused-event))]
      (is (some? rec) "the transition record still surfaces")
      (is (= #{} (:fired-edge-ids rec))
          "no definition → empty fired set (no chart edges to match)"))))

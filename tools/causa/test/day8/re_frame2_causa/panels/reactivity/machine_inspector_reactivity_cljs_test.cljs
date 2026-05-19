(ns day8.re-frame2-causa.panels.reactivity.machine-inspector-reactivity-cljs-test
  "Sub-reactivity guard for the Machine Inspector panel's focused-event
  lens (rf2-dhoc9).

  The rf2-2f8jv bug class — \"No machine activity\" when the panel's
  focused-event sub returned empty rows for cascades that drove real
  machine transitions. rf2-70tkv fixed the focus-tracking side
  (`:rf.causa/focus :epoch-id` now auto-derives in LIVE mode), and
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
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

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
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [transitions-1 (h/read-sub :rf.causa/machine-transitions-for-focused-event)]
      (is (= 1 (count transitions-1))
          "one transition fired in :e1's cascade window")
      (is (= :idle (:from-state (first transitions-1)))
          "first transition: idle → loading")
      (is (= :loading (:to-state (first transitions-1))))
      (h/focus-cascade! :c2)
      (let [transitions-2 (h/read-sub :rf.causa/machine-transitions-for-focused-event)]
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
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history!
      [(epoch-with-transition :e1 :c1 :title/flow :idle :loading
                              [:title/refresh])
       (epoch-with-transition :e2 :c2 :auth/flow :anon :signed-in
                              [:auth/login])])
    (h/focus-cascade! :c1)
    (let [transitions-1 (h/read-sub :rf.causa/machine-transitions-for-focused-event)]
      (is (= :title/flow (:machine-id (first transitions-1)))))
    (h/focus-cascade! :c2)
    (let [transitions-2 (h/read-sub :rf.causa/machine-transitions-for-focused-event)]
      (is (= :auth/flow (:machine-id (first transitions-2)))
          "focus flip surfaces the new epoch's machine — the lens
           rebinds end-to-end through the sub chain"))))

(deftest machine-lens-empty-on-epoch-without-transitions
  (testing "rf2-dhoc9 — an epoch without transition trace events
            yields `[]`. The reactive contract still holds: when
            focus flips between an epoch-with-transitions and an
            empty epoch, the lens output changes from a populated
            vector to empty."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history!
      [(epoch-with-transition :e1 :c1 :title/flow :idle :loading
                              [:title/refresh])
       ;; :e2 has no :rf.machine/transition events.
       (h/mock-epoch :e2 :c2 {} {})])
    (h/focus-cascade! :c1)
    (is (= 1 (count (h/read-sub :rf.causa/machine-transitions-for-focused-event)))
        "focus :c1 → one transition record")
    (h/focus-cascade! :c2)
    (is (= [] (h/read-sub :rf.causa/machine-transitions-for-focused-event))
        "focus :c2 → empty lens; the sub re-fired to the silent-by-
         default contract (rf2-g3ghh)")))

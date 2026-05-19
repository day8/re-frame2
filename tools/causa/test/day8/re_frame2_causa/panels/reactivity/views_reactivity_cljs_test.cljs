(ns day8.re-frame2-causa.panels.reactivity.views-reactivity-cljs-test
  "Sub-reactivity guard for the Views panel's primary composite
  (rf2-dhoc9).

  The rf2-70tkv bug class for Views: the panel pivots on the focused
  cascade's `:renders` + `:sub-runs` via `:rf.causa/views-focused-
  cascade-pair`. Pre-fix the panel froze when the LIVE pill auto-
  followed because the spine's `:epoch-id` slot stayed pinned to the
  previously-clicked cascade. This test asserts the post-rf2-70tkv
  contract: `:rf.causa/views-data` (the primary panel-consumed sub)
  re-fires with new render shapes when focus flips between epochs
  that carry distinct `:renders` payloads."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

;; ---- fixtures -----------------------------------------------------------

(defn- epoch-with-renders
  "Wrap `mock-epoch` with a `:renders` payload — the Views composite's
  load-bearing read."
  [epoch-id dispatch-id renders]
  (-> (h/mock-epoch epoch-id dispatch-id {} {})
      (assoc :renders renders)))

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(def epoch-history
  [(epoch-with-renders :e1 :c1
                       [{:render-key [:counter/badge :tok-A]
                         :elapsed-ms 3}])
   (epoch-with-renders :e2 :c2
                       [{:render-key [:counter/badge :tok-B]
                         :elapsed-ms 5}])])

;; ---- tests --------------------------------------------------------------

(deftest views-data-sub-tracks-focus-flip
  (testing "rf2-dhoc9 — focus on cascade :c1 yields a different
            `:rf.causa/views-data` than focus on cascade :c2; the
            renders payloads differ so the composite map differs."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [sig-1 (h/read-sub :rf.causa/views-data)]
      (is (= :c1 (:dispatch-id sig-1))
          "first read pivots on :c1")
      (h/focus-cascade! :c2)
      (let [sig-2 (h/read-sub :rf.causa/views-data)]
        (is (= :c2 (:dispatch-id sig-2)))
        (is (not= sig-1 sig-2)
            "views-data sub did not track focus flip — Views panel
             would render stale render data (rf2-70tkv regression)")))))

(deftest views-focused-cascade-pair-tracks-focus-flip
  (testing "rf2-dhoc9 — the inner `:rf.causa/views-focused-cascade-
            pair` sub returns the focused epoch record, not the head
            fallback. Distinct `:epoch-id`s in the pair payload pin
            the contract tighter than the composite test above."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [pair-1 (h/read-sub :rf.causa/views-focused-cascade-pair)]
      (is (= :e1 (get-in pair-1 [:current :epoch-id]))
          "focus on :c1 → current cascade is :e1")
      (h/focus-cascade! :c2)
      (let [pair-2 (h/read-sub :rf.causa/views-focused-cascade-pair)]
        (is (= :e2 (get-in pair-2 [:current :epoch-id])))
        (is (not= (:current pair-1) (:current pair-2))
            "focused-cascade-pair re-fired with the new current
             cascade")))))

(deftest views-data-renders-payload-tracks-focus-flip
  (testing "rf2-dhoc9 — the Views panel renders the focused cascade's
            `:renders` vector. Asserting on the projected output
            confirms the sub chain to the panel's render-time surface
            tracks focus, not just the composite map identity."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [pair-1 (h/read-sub :rf.causa/views-focused-cascade-pair)]
      (is (= [{:render-key [:counter/badge :tok-A]
               :elapsed-ms 3}]
             (:renders (:current pair-1)))
          "focus on :c1 → :renders payload is :tok-A")
      (h/focus-cascade! :c2)
      (let [pair-2 (h/read-sub :rf.causa/views-focused-cascade-pair)]
        (is (= [{:render-key [:counter/badge :tok-B]
                 :elapsed-ms 5}]
               (:renders (:current pair-2)))
            "focus on :c2 → :renders payload is :tok-B (NOT :tok-A —
             would indicate the sub returned a stale cached value)")))))

(ns day8.re-frame2-machines-viz.visual-constants-cljs-test
  "Pins the `:density` contract of spec/API.md §Density: three maps sharing
  one numeric key set, the corner-radius lock, monotonic scaling, and
  `chart-for-density` resolution."
  (:require
    #?(:clj  [clojure.test :refer [deftest is testing]]
       :cljs [cljs.test    :refer-macros [deftest is testing]])
    [day8.re-frame2-machines-viz.visual-constants :as vc]))

(deftest density-variants-share-key-set
  (testing "a key missing from one density, or a non-number value, reaches the
            renderer as a silent nil / NaN"
    (is (= (set (keys vc/chart-regular))
           (set (keys vc/chart-compact))
           (set (keys vc/chart-cosy))))
    (doseq [m [vc/chart-compact vc/chart-regular vc/chart-cosy]]
      (is (every? number? (vals m))))))

(deftest chart-typography-meets-chart-floor
  (testing "the regular density sits at the published chart floor, state 13 / edge 11"
    (is (= 13 (:state-title-px vc/chart)))
    (is (= 11 (:edge-label-px vc/chart)))))

(deftest chart-arrowhead-quiet-smaller-than-primary
  (testing "the quiet `__in` head < the entry head < the primary `__out` head, so
            the source→event→target pair reads as one transition"
    (is (< (:arrow-width-quiet vc/chart)
           (:arrow-width-entry vc/chart)
           (:arrow-width vc/chart)))))

(deftest density-variants-respect-corner-radius-lock
  (is (= [6 6 6] (map :corner-radius [vc/chart-compact vc/chart-regular vc/chart-cosy]))))

(deftest density-scaled-keys-are-monotonic
  (doseq [k [:state-title-px :edge-label-px
             :dot-grid-spacing-px
             :arrow-width :arrow-width-quiet :arrow-width-entry
             :compound-radius
             :state-title-height :container-title-height
             :event-chip-min-w :event-chip-min-h :event-chip-px
             :region-title-height :pseudo-size]]
    (is (< (get vc/chart-compact k)
           (get vc/chart-regular k)
           (get vc/chart-cosy k))
        (str k " is monotonic compact < regular < cosy"))))

(deftest regular-is-the-default-density
  (is (= vc/chart-regular vc/chart (vc/chart-for-density nil))))

(deftest chart-for-density-resolves-named-densities
  (is (= [:compact :regular :cosy] vc/densities))
  (is (= [vc/chart-compact vc/chart-regular vc/chart-cosy]
         (map vc/chart-for-density vc/densities))))

(deftest chart-for-density-unknown-throws
  (is (= :rf.error/machines-viz-unknown-chart-density
         (try (vc/chart-for-density :spacious) nil
              (catch #?(:clj Exception :cljs :default) e
                (:rf.error/id (ex-data e)))))))

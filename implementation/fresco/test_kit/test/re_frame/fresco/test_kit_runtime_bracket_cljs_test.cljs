(ns re-frame.fresco.test-kit-runtime-bracket-cljs-test
  "`rt/render-and-commit!` and `rt/reset-bracket`, witnessed — the bracket
  going red included.

  The suite runs under the bracket it tests. The rows that plant a leak
  run the bracket's own `:after` half by hand, with `cljs.test`'s
  reporter swapped for one that records, so the planted failure is read
  as data rather than counted against this run; that run resets the
  runtime, so the suite's own bracket then finds it clean."
  (:require [cljs.test :as t :refer-macros [async deftest is use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.test-support :as rf.test-support]))

(rf/reg-sub ::left (fn [db _] (:left db)))
(rf/reg-sub ::right (fn [db _] (:right db)))

(rf.fresco/defview left-probe [_] [:p (rf.fresco/sub [::left])])

(def ^:private frame-kw ::app)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true})
  rf.fresco.test.runtime/reset-bracket)

(defn- app! []
  (rf/make-frame {:id frame-kw :initial-events [[:rf/set-db {:left 1 :right 2}]]}))

;; ---------------------------------------------------------------------------
;; render-and-commit!
;; ---------------------------------------------------------------------------

(deftest a-committed-boundary-answers-its-element-its-reads-and-a-release
  (app!)
  (let [{:keys [element reads release]}
        (rf.fresco.test.runtime/render-and-commit!
          frame-kw (fn [_] [:p (rf.fresco/sub [::left]) (rf.fresco/sub [::right])]))]
    (is (= "p" (.-type element)) "the React element the body rendered")
    (is (= #{[frame-kw [::left]] [frame-kw [::right]]} reads))
    (is (= {:cells 2 :cell-refs 2 :boundaries 1 :edges 2}
           (select-keys (rf.fresco.test.runtime/residue)
                        [:cells :cell-refs :boundaries :edges]))
        "COMMITTED, not merely rendered: the boundary holds a reader slot on each cell")
    (release)
    (is (= 0 (:boundaries (rf.fresco.test.runtime/residue)))
        "and the release takes its memberships back")))

(deftest a-defview-head-runs-the-body-it-was-minted-from
  (app!)
  (let [{:keys [element reads release]}
        (rf.fresco.test.runtime/render-and-commit! frame-kw left-probe)]
    (is (= #{[frame-kw [::left]]} reads))
    (is (= "p" (.-type element)))
    (release)))

;; ---------------------------------------------------------------------------
;; reset-bracket — red on a leak, green without one, and reset either way
;; ---------------------------------------------------------------------------

(defonce ^:private !captured (atom []))

(defmethod t/report [::captured :fail] [m] (swap! !captured conj m))
(defmethod t/report [::captured :pass] [m] (swap! !captured conj m))

(defn- bracket-after!
  "Run the bracket's own `:after` half with every report recorded rather
  than counted, and answer a promise of the recorded reports."
  []
  (let [previous (:reporter (t/get-current-env))]
    (reset! !captured [])
    (t/update-current-env! [:reporter] (constantly ::captured))
    (js/Promise.
      (fn [resolve]
        (((:after rf.fresco.test.runtime/reset-bracket))
         (fn []
           (t/update-current-env! [:reporter] (constantly previous))
           (resolve @!captured)))))))

(deftest a-reaction-left-alive-fails-the-bracket-and-is-reset-after-the-reading
  (async done
    (app!)
    ;; THE PLANT: a committed boundary nobody releases holds its cell, and
    ;; the cell holds its reaction alive.
    (rf.fresco.test.runtime/render-and-commit! frame-kw (fn [_] (rf.fresco/sub [::left]) nil))
    (-> (bracket-after!)
        (.then (fn [reports]
                 (is (= [:fail] (mapv :type reports))
                     "one failure, reported by the bracket itself")
                 (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                        (select-keys (:actual (first reports))
                                     [:cells :cell-refs :boundaries :edges]))
                     "naming what was left alive — so the reading came BEFORE the reset")
                 (is (every? zero? (vals (rf.fresco.test.runtime/residue)))
                     "and then it reset, so the next test starts from empty tables")))
        (.then (fn [_] (done))))))

(deftest the-same-boundary-released-passes-the-bracket
  (async done
    (app!)
    (let [{:keys [release]}
          (rf.fresco.test.runtime/render-and-commit! frame-kw (fn [_] (rf.fresco/sub [::left]) nil))]
      (release))
    (-> (bracket-after!)
        (.then (fn [reports]
                 (is (= [:pass] (mapv :type reports))
                     "the plant removed, the same bracket passes — after waiting
                      out the reapers, which a reading taken at once would not")))
        (.then (fn [_] (done))))))

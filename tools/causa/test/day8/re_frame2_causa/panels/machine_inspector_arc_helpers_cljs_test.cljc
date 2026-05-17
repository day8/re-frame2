(ns day8.re-frame2-causa.panels.machine-inspector-arc-helpers-cljs-test
  "Pure-data tests for Causa's Machine Inspector per-instance state-arc
  helpers (rf2-nqw0v, Phase 5).

  Dual-target via the `_cljs_test.cljc` extension — Cognitect's CLJ
  test-runner picks the ns up via the `.*-test$` regex; Shadow's
  `:node-test` build picks it up via `cljs-test$`. Same pattern every
  Causa helper test uses.

  ## What's under test

    1. `build-arc`           — fold trace buffer + definition into
                              chronological arc-points (origin + each
                              transition).
    2. `arc-length` / `max-scrub-index` — geometry.
    3. `clamp-position` / `resolve-position-index` — scrubber bounds.
    4. `trim-arc`            — slice arc to a scrubber position.
    5. `highlight-state-at`  — historic state at scrubber position.
    6. `at-present?`         — head detection.
    7. `arc-segments`        — adjacent pair-vec for the renderer.
    8. `nodes->index` / `point-center` — node-centre resolution.
    9. `format-*` helpers    — display formatters."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.machine-inspector-arc-helpers
             :as h]))

;; ---- fixtures -----------------------------------------------------------

(def ^:private fixture-def
  {:initial :idle
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(defn- transition-event
  ([id from to event] (transition-event id from to event id))
  ([id from to event time]
   {:id id :time time
    :operation :rf.machine/transition
    :tags {:machine-id :auth/login
           :from from
           :to to
           :event event
           :dispatch-id (str "d-" id)}}))

(defn- microstep-event
  [id from to event]
  {:id id :time id
   :operation :rf.machine.microstep/transition
   :tags {:machine-id :auth/login
          :from from
          :to to
          :event event
          :dispatch-id (str "d-" id)}})

;; ---- (1) build-arc ------------------------------------------------------

(deftest build-arc-empty-when-machine-id-nil
  (is (= [] (h/build-arc [] nil fixture-def))))

(deftest build-arc-origin-only-when-no-transitions
  (testing "with no transitions in the buffer the arc carries just the
            initial-state origin"
    (let [arc (h/build-arc [] :auth/login fixture-def)]
      (is (= 1 (count arc)))
      (let [origin (first arc)]
        (is (= 0      (:idx origin)))
        (is (= :idle  (:state origin)))
        (is (nil?     (:from origin)))
        (is (nil?     (:event origin)))
        (is (false?   (:microstep? origin)))))))

(deftest build-arc-folds-transitions-into-trajectory
  (let [buf [(transition-event 1 :idle    :authing [:auth/start])
             (transition-event 2 :authing :done    [:auth/ok])]
        arc (h/build-arc buf :auth/login fixture-def)]
    (is (= 3 (count arc)) "origin + two transitions")
    (is (= [:idle :authing :done] (mapv :state arc)))
    (is (= [0 1 2] (mapv :idx arc)))
    (is (= [:auth/start] (-> arc (nth 1) :event)))
    (is (= "d-1" (-> arc (nth 1) :dispatch-id)))))

(deftest build-arc-sorts-by-id-when-out-of-order
  (testing "events arriving out of order in the buffer still produce a
            chronological arc"
    (let [buf [(transition-event 2 :authing :done    [:auth/ok])
               (transition-event 1 :idle    :authing [:auth/start])]
          arc (h/build-arc buf :auth/login fixture-def)]
      (is (= [:idle :authing :done] (mapv :state arc))))))

(deftest build-arc-filters-by-machine-id
  (let [buf [(transition-event 1 :idle :authing [:auth/start])
             {:id 2 :time 2 :operation :rf.machine/transition
              :tags {:machine-id :checkout/flow
                     :from :foo :to :bar :event [:other]}}]
        arc (h/build-arc buf :auth/login fixture-def)]
    (is (= 2 (count arc)) "the other machine's transition is dropped")
    (is (= [:idle :authing] (mapv :state arc)))))

(deftest build-arc-includes-microstep-with-flag
  (let [buf [(transition-event 1 :idle    :authing [:auth/start])
             (microstep-event  2 :authing :checking [:always])]
        arc (h/build-arc buf :auth/login fixture-def)]
    (is (= 3 (count arc)))
    (is (false? (:microstep? (nth arc 1))))
    (is (true?  (:microstep? (nth arc 2))))))

(deftest build-arc-synthesises-origin-from-oldest-from-when-no-definition
  (testing "with no definition map the arc origin reads off the oldest
            transition's :from slot"
    (let [buf [(transition-event 1 :idle :authing [:auth/start])]
          arc (h/build-arc buf :auth/login nil)]
      (is (= 2 (count arc)))
      (is (= :idle (:state (first arc)))))))

(deftest build-arc-empty-when-no-definition-and-no-transitions
  (is (= [] (h/build-arc [] :auth/login nil))))

;; ---- (2) length + index helpers ----------------------------------------

(deftest arc-length-counts-points
  (is (= 0 (h/arc-length [])))
  (is (= 0 (h/arc-length nil)))
  (is (= 3 (h/arc-length [{} {} {}]))))

(deftest max-scrub-index-returns-n-minus-1
  (is (= -1 (h/max-scrub-index [])))
  (is (= 0  (h/max-scrub-index [{}])))
  (is (= 2  (h/max-scrub-index [{} {} {}]))))

;; ---- (3) clamp + resolve -----------------------------------------------

(deftest clamp-position-handles-special-values
  (let [arc [{} {} {}]]
    (is (= :present (h/clamp-position arc nil)))
    (is (= :present (h/clamp-position arc :present)))
    (is (= 0       (h/clamp-position arc 0)))
    (is (= 2       (h/clamp-position arc 2)))
    (is (= :present (h/clamp-position arc 99))
        "out-of-bounds positive flips to :present (sticky head)")
    (is (= 0       (h/clamp-position arc -5))
        "negative clamps to 0")))

(deftest clamp-position-empty-arc
  (is (= :present (h/clamp-position [] 0)))
  (is (= :present (h/clamp-position [] :present))))

(deftest resolve-position-index-maps-present-to-tail
  (let [arc [{} {} {}]]
    (is (= 2 (h/resolve-position-index arc :present)))
    (is (= 0 (h/resolve-position-index arc 0)))
    (is (= 1 (h/resolve-position-index arc 1)))
    (is (= -1 (h/resolve-position-index [] :present)))))

;; ---- (4) trim-arc ------------------------------------------------------

(deftest trim-arc-present-returns-full-arc
  (let [arc [{:idx 0} {:idx 1} {:idx 2}]]
    (is (= arc (h/trim-arc arc :present)))))

(deftest trim-arc-index-slices-inclusive
  (let [arc [{:idx 0} {:idx 1} {:idx 2}]]
    (is (= [{:idx 0}]                 (h/trim-arc arc 0)))
    (is (= [{:idx 0} {:idx 1}]        (h/trim-arc arc 1)))
    (is (= arc                        (h/trim-arc arc 2)))))

(deftest trim-arc-empty-returns-empty
  (is (= [] (h/trim-arc [] :present)))
  (is (= [] (h/trim-arc nil 0))))

(deftest trim-arc-out-of-bounds-clamps
  (let [arc [{:idx 0} {:idx 1}]]
    (is (= arc (h/trim-arc arc 99)) "high → :present → full arc")
    (is (= [{:idx 0}] (h/trim-arc arc -3)) "negative → 0 → origin only")))

;; ---- (5) highlight-state-at --------------------------------------------

(deftest highlight-state-at-returns-state-at-position
  (let [arc [{:idx 0 :state :idle}
             {:idx 1 :state :authing}
             {:idx 2 :state :done}]]
    (is (= :idle    (h/highlight-state-at arc 0)))
    (is (= :authing (h/highlight-state-at arc 1)))
    (is (= :done    (h/highlight-state-at arc 2)))
    (is (= :done    (h/highlight-state-at arc :present)))))

(deftest highlight-state-at-nil-when-empty
  (is (nil? (h/highlight-state-at [] :present)))
  (is (nil? (h/highlight-state-at nil 0))))

;; ---- (6) at-present? ---------------------------------------------------

(deftest at-present?-detects-head
  (let [arc [{:idx 0} {:idx 1} {:idx 2}]]
    (is (true?  (h/at-present? arc :present)))
    (is (true?  (h/at-present? arc 2))   "explicit tail idx counts as present")
    (is (false? (h/at-present? arc 0)))
    (is (false? (h/at-present? arc 1)))))

(deftest at-present?-empty-arc-is-present
  (is (true? (h/at-present? [] :present)))
  (is (true? (h/at-present? [] 0))))

;; ---- (7) arc-segments --------------------------------------------------

(deftest arc-segments-pairs-adjacent
  (let [arc [{:idx 0} {:idx 1} {:idx 2}]
        segs (h/arc-segments arc)]
    (is (= 2 (count segs)))
    (is (= {:idx 0} (-> segs first :from)))
    (is (= {:idx 1} (-> segs first :to)))
    (is (= {:idx 1} (-> segs second :from)))
    (is (= {:idx 2} (-> segs second :to)))))

(deftest arc-segments-empty-when-arc-too-short
  (is (= [] (h/arc-segments [])))
  (is (= [] (h/arc-segments [{:idx 0}]))))

;; ---- (8) nodes->index + point-center -----------------------------------

(deftest nodes->index-keys-by-node-id
  (let [nodes [{:node-id "idle" :x 0 :y 0 :width 100 :height 40}
               {:node-id "authing" :x 200 :y 0 :width 100 :height 40}]
        idx   (h/nodes->index nodes)]
    (is (= 2 (count idx)))
    (is (= 0 (get-in idx ["idle" :x])))))

(deftest point-center-returns-node-midpoint
  (let [nodes [{:node-id "idle" :x 10 :y 20 :width 100 :height 40}]
        idx   (h/nodes->index nodes)
        ;; A trivial id-fn that just turns the state into its name string.
        id-fn (fn [state] (when state (name state)))
        point {:idx 0 :state :idle}]
    (is (= [60 40] (h/point-center point idx id-fn))
        "centre = (x + w/2, y + h/2)")))

(deftest point-center-nil-when-node-missing
  (let [idx   {}
        id-fn (fn [s] (name s))
        point {:idx 0 :state :foo}]
    (is (nil? (h/point-center point idx id-fn)))))

;; ---- (9) format helpers ------------------------------------------------

(deftest format-point-tooltip-origin
  (let [t (h/format-point-tooltip {:idx 0 :state :idle :from nil :event nil})]
    (is (some? t))
    (is (re-find #"origin" t))
    (is (re-find #":idle" t))))

(deftest format-point-tooltip-transition
  (let [t (h/format-point-tooltip
            {:idx 2 :state :done :from :authing
             :event [:auth/ok] :time 5000})]
    (is (re-find #":authing" t))
    (is (re-find #":done" t))
    (is (re-find #":auth/ok" t))
    (is (re-find #"@5000ms" t))))

(deftest format-position-label-renders-modes
  (let [arc [{:idx 0} {:idx 1} {:idx 2}]]
    (is (re-find #"present" (h/format-position-label arc :present)))
    (is (re-find #"step 1"  (h/format-position-label arc 1)))
    (is (re-find #"present" (h/format-position-label arc nil)))))

(deftest format-position-label-empty-arc
  (is (re-find #"no history" (h/format-position-label [] :present))))

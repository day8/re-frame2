(ns re-frame.story-scrubber-xref-test
  "JVM unit tests for the trace × scrubber cross-reference (rf2-sxwvf).

  The cross-reference logic lives in `re-frame.story.ui.scrubber-xref`
  as pure data → data so the predicates run under the JVM target. The
  Reagent / DOM / ratom wiring lives in
  `re-frame.story.ui.scrubber{,.cljs}` and
  `re-frame.story.ui.trace{,.cljs}` and is exercised by the CLJS smoke
  + Playwright browser specs.

  Coverage:
  - cascade-id resolution from an epoch record's `:trace-events`.
  - cascade-id resolution under history-lookup (epoch present vs evicted).
  - max-event-id resolution under history-lookup.
  - filter-cascades-up-to:
      - nil cap is the identity.
      - cap drops cascades whose max event-id exceeds it.
      - cap retains cascades whose every event id ≤ cap.
      - cascades with no event ids pass under any cap.
  - cascade-matches-selected-epoch? matches on :dispatch-id."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.ui.scrubber-xref :as xref]))

;; ---- fixture data --------------------------------------------------------

(def ^:private cascade-a
  "Cascade with dispatch-id 100, max event-id 6."
  {:dispatch-id 100
   :event       [:foo]
   :handler     {:id 2 :tags {:dispatch-id 100 :phase :run-end}}
   :fx          {:id 3 :tags {:dispatch-id 100}}
   :effects     [{:id 4 :tags {:dispatch-id 100 :fx-id :db}}]
   :subs        [{:id 5 :tags {:dispatch-id 100 :sub-id :sub/foo}}]
   :renders     [{:id 6 :tags {:dispatch-id 100 :render-key [:app/root nil]}}]
   :other       []})

(def ^:private cascade-b
  "Cascade with dispatch-id 200, max event-id 12 (emitted later)."
  {:dispatch-id 200
   :event       [:bar]
   :handler     {:id 8 :tags {:dispatch-id 200 :phase :run-end}}
   :fx          {:id 9 :tags {:dispatch-id 200}}
   :effects     [{:id 10 :tags {:dispatch-id 200 :fx-id :db}}]
   :subs        [{:id 11 :tags {:dispatch-id 200 :sub-id :sub/foo}}]
   :renders     [{:id 12 :tags {:dispatch-id 200 :render-key [:app/root nil]}}]
   :other       []})

(def ^:private cascade-no-ids
  "Cascade with no event ids (degenerate). Must pass under any cap."
  {:dispatch-id 300
   :event       [:baz]
   :handler     nil :fx nil :effects [] :subs [] :renders []
   :other       [{:tags {:dispatch-id 300}}]})

(def ^:private epoch-a
  {:epoch-id     7
   :frame        :v/a
   :trigger-event [:foo]
   :trace-events [{:id 2 :tags {:dispatch-id 100}}
                  {:id 6 :tags {:dispatch-id 100}}]})

(def ^:private epoch-b
  {:epoch-id     8
   :frame        :v/a
   :trigger-event [:bar]
   :trace-events [{:id 8  :tags {:dispatch-id 200}}
                  {:id 12 :tags {:dispatch-id 200}}]})

(def ^:private epoch-synthetic
  "Synthetic epoch from reset-frame-db! — :trace-events is empty."
  {:epoch-id     9
   :frame        :v/a
   :trigger-event [:rf.epoch/db-replaced]
   :trace-events []})

(def ^:private history [epoch-a epoch-b epoch-synthetic])

;; ---- cascade-id-from-trace-events ----------------------------------------

(deftest cascade-id-from-trace-events-extracts-dispatch-id
  (testing "first :dispatch-id tag wins"
    (is (= 100 (xref/cascade-id-from-trace-events epoch-a)))
    (is (= 200 (xref/cascade-id-from-trace-events epoch-b)))))

(deftest cascade-id-from-trace-events-tolerates-empty
  (testing "synthetic epoch with no events returns nil"
    (is (nil? (xref/cascade-id-from-trace-events epoch-synthetic)))))

(deftest cascade-id-from-trace-events-falls-back-to-parent-dispatch-id
  (testing "when no :dispatch-id, :parent-dispatch-id is used"
    (let [ep {:trace-events [{:id 1 :tags {:parent-dispatch-id 999}}]}]
      (is (= 999 (xref/cascade-id-from-trace-events ep))))))

;; ---- cascade-id-for-epoch -----------------------------------------------

(deftest cascade-id-for-epoch-finds-present-epoch
  (testing "epoch present in history resolves to its cascade-id"
    (is (= 100 (xref/cascade-id-for-epoch history 7)))
    (is (= 200 (xref/cascade-id-for-epoch history 8)))))

(deftest cascade-id-for-epoch-nil-for-missing
  (testing "epoch absent from history returns nil (the epoch was evicted
            by the ring buffer's depth cap)"
    (is (nil? (xref/cascade-id-for-epoch history 999)))))

(deftest cascade-id-for-epoch-nil-for-synthetic
  (testing "synthetic epoch (empty :trace-events) returns nil"
    (is (nil? (xref/cascade-id-for-epoch history 9)))))

(deftest cascade-id-for-epoch-nil-for-nil-id
  (testing "nil epoch-id (no scrub in flight) returns nil — the trace
            panel reads this as 'no highlight'"
    (is (nil? (xref/cascade-id-for-epoch history nil)))))

;; ---- max-trace-event-id-for-epoch ---------------------------------------

(deftest max-trace-event-id-for-epoch-resolves-cap
  (testing "max trace event id is the cap for filtering"
    (is (= 6  (xref/max-trace-event-id-for-epoch history 7)))
    (is (= 12 (xref/max-trace-event-id-for-epoch history 8)))))

(deftest max-trace-event-id-for-epoch-nil-cases
  (testing "absent / synthetic / nil cases return nil"
    (is (nil? (xref/max-trace-event-id-for-epoch history 999)))
    (is (nil? (xref/max-trace-event-id-for-epoch history 9)))
    (is (nil? (xref/max-trace-event-id-for-epoch history nil)))))

;; ---- filter-cascades-up-to ----------------------------------------------

(deftest filter-cascades-up-to-nil-cap-is-identity
  (testing "nil cap returns the input vector unchanged"
    (let [cs [cascade-a cascade-b cascade-no-ids]]
      (is (= cs (xref/filter-cascades-up-to cs nil))))))

(deftest filter-cascades-up-to-drops-later-cascades
  (testing "cascades with max-event-id > cap drop out"
    (let [cs [cascade-a cascade-b]
          ;; cap 6 = end of cascade-a; cascade-b's max id is 12 > 6
          visible (xref/filter-cascades-up-to cs 6)]
      (is (= [cascade-a] visible))
      (is (not-any? #(= 200 (:dispatch-id %)) visible)))))

(deftest filter-cascades-up-to-retains-at-cap
  (testing "cascade whose max id equals the cap is visible"
    (let [cs [cascade-a cascade-b]]
      ;; cap 12 admits both — cascade-b's max id = 12.
      (is (= [cascade-a cascade-b]
             (xref/filter-cascades-up-to cs 12))))))

(deftest filter-cascades-up-to-retains-id-less-cascades
  (testing "cascades whose every event carries no :id pass under any cap"
    (let [cs [cascade-a cascade-b cascade-no-ids]
          visible (xref/filter-cascades-up-to cs 6)]
      ;; cascade-a (max 6) + cascade-no-ids (no ids) survive
      (is (= 2 (count visible)))
      (is (some #(= 100 (:dispatch-id %)) visible))
      (is (some #(= 300 (:dispatch-id %)) visible))
      (is (not-any? #(= 200 (:dispatch-id %)) visible)))))

(deftest filter-cascades-up-to-returns-vector
  (testing "result is a vector (consumer iterates with `for` keyed by id)"
    (is (vector? (xref/filter-cascades-up-to [cascade-a] nil)))
    (is (vector? (xref/filter-cascades-up-to [cascade-a cascade-b] 6)))
    (is (vector? (xref/filter-cascades-up-to [] nil)))))

;; ---- cascade-matches-selected-epoch? ------------------------------------

(deftest cascade-matches-selected-epoch?-matches-on-dispatch-id
  (testing "cascade :dispatch-id == selected-cascade-id → true"
    (is (true?  (xref/cascade-matches-selected-epoch? cascade-a 100)))
    (is (false? (xref/cascade-matches-selected-epoch? cascade-a 200)))))

(deftest cascade-matches-selected-epoch?-nil-selected-is-false
  (testing "nil selected-cascade-id always returns false (no scrub ⇒
            no row highlights)"
    (is (false? (xref/cascade-matches-selected-epoch? cascade-a nil)))
    (is (false? (xref/cascade-matches-selected-epoch? cascade-b nil)))))

(deftest cascade-matches-selected-epoch?-ungrouped-cascade
  (testing "a cascade with :dispatch-id :ungrouped does not match a
            numeric selected-cascade-id (the projection lifts
            free-floating traces under :ungrouped — they never produce
            an epoch)"
    (let [c {:dispatch-id :ungrouped}]
      (is (false? (xref/cascade-matches-selected-epoch? c 100))))))

;; ---- end-to-end shape ---------------------------------------------------

(deftest end-to-end-scrub-to-cascade-a
  (testing "scrub to epoch-a → only cascade-a is visible, cascade-a is
            highlighted, cascade-b is filtered out (emitted after the
            selected epoch settled)"
    (let [selected-epoch    7
          cap               (xref/max-trace-event-id-for-epoch history selected-epoch)
          selected-cascade  (xref/cascade-id-for-epoch       history selected-epoch)
          all-cascades      [cascade-a cascade-b]
          visible           (xref/filter-cascades-up-to all-cascades cap)]
      (is (= 6 cap))
      (is (= 100 selected-cascade))
      (is (= [cascade-a] visible))
      (is (true?  (xref/cascade-matches-selected-epoch? cascade-a selected-cascade)))
      (is (false? (xref/cascade-matches-selected-epoch? cascade-b selected-cascade))))))

(deftest end-to-end-scrub-to-cascade-b
  (testing "scrub to epoch-b → both cascades visible, cascade-b is the
            one that landed the selected epoch (highlighted)"
    (let [selected-epoch    8
          cap               (xref/max-trace-event-id-for-epoch history selected-epoch)
          selected-cascade  (xref/cascade-id-for-epoch       history selected-epoch)
          all-cascades      [cascade-a cascade-b]
          visible           (xref/filter-cascades-up-to all-cascades cap)]
      (is (= 12 cap))
      (is (= 200 selected-cascade))
      (is (= [cascade-a cascade-b] visible))
      (is (false? (xref/cascade-matches-selected-epoch? cascade-a selected-cascade)))
      (is (true?  (xref/cascade-matches-selected-epoch? cascade-b selected-cascade))))))

(deftest end-to-end-no-scrub
  (testing "no scrub in flight → no filter, no highlight"
    (let [selected-epoch    nil
          cap               (xref/max-trace-event-id-for-epoch history selected-epoch)
          selected-cascade  (xref/cascade-id-for-epoch       history selected-epoch)
          all-cascades      [cascade-a cascade-b]
          visible           (xref/filter-cascades-up-to all-cascades cap)]
      (is (nil? cap))
      (is (nil? selected-cascade))
      (is (= [cascade-a cascade-b] visible))
      (is (false? (xref/cascade-matches-selected-epoch? cascade-a selected-cascade)))
      (is (false? (xref/cascade-matches-selected-epoch? cascade-b selected-cascade))))))

(deftest end-to-end-scrub-to-synthetic-epoch
  (testing "scrub to a reset-frame-db! synthetic epoch → cap is nil
            (every cascade visible) but no cascade is highlighted (the
            synthetic epoch carried no producing cascade)"
    (let [selected-epoch    9
          cap               (xref/max-trace-event-id-for-epoch history selected-epoch)
          selected-cascade  (xref/cascade-id-for-epoch       history selected-epoch)
          all-cascades      [cascade-a cascade-b]
          visible           (xref/filter-cascades-up-to all-cascades cap)]
      (is (nil? cap))
      (is (nil? selected-cascade))
      (is (= [cascade-a cascade-b] visible))
      (is (false? (xref/cascade-matches-selected-epoch? cascade-a selected-cascade)))
      (is (false? (xref/cascade-matches-selected-epoch? cascade-b selected-cascade))))))

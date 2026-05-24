(ns day8.re-frame2-xray.filters.hidden-test
  "Pure-data tests for the events-ribbon 'N hidden by filters' message
  derivation (rf2-jvghz, defect #1; frame-decoupled per rf2-4vp5j).

  CLJC so the JVM corpus exercises the hidden-count derivation +
  visibility predicate without a CLJS runtime — the helper is pure
  data, no atoms, no I/O.

  Per rf2-4vp5j Workstream C the FRAME is a view SCOPE, not a filter —
  it is never counted as hidden, never a cause, never reset by Clear
  Filters. The summary model carries no `:frame` key and
  `any-filter-active?` ignores frame state entirely."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.filters.hidden :as hidden]))

;; ---- hidden-count -------------------------------------------------------

(deftest hidden-count-is-raw-minus-filtered
  (is (= 3 (hidden/hidden-count 5 2)))
  (is (= 0 (hidden/hidden-count 4 4))
      "nothing hidden when filtered == raw")
  (is (= 4 (hidden/hidden-count 4 0))
      "filtered-to-empty hides the whole raw set"))

(deftest hidden-count-clamps-at-zero
  (testing "a transient skew where filtered briefly exceeds raw never
            yields a negative count"
    (is (= 0 (hidden/hidden-count 2 5)))))

;; ---- present? predicates ------------------------------------------------

(deftest pills-present?-detects-any-bucket
  (is (false? (hidden/pills-present? nil)))
  (is (false? (hidden/pills-present? {:in [] :out []})))
  (is (true?  (hidden/pills-present? {:in [{:pattern :a}] :out []})))
  (is (true?  (hidden/pills-present? {:in [] :out [{:pattern :b}]}))))

(deftest mutes-present?-detects-nonempty-set
  (is (false? (hidden/mutes-present? nil)))
  (is (false? (hidden/mutes-present? #{})))
  (is (true?  (hidden/mutes-present? #{:a}))))

;; ---- any-filter-active? -------------------------------------------------

(deftest any-filter-active?-true-for-each-filter-surface
  (is (false? (hidden/any-filter-active?
                {:filters {:in [] :out []} :muted #{}})))
  (is (true?  (hidden/any-filter-active?
                {:filters {:in [{:pattern :a}] :out []} :muted #{}}))
      "an IN pill counts")
  (is (true?  (hidden/any-filter-active?
                {:filters {:in [] :out []} :muted #{:noise}}))
      "a mute counts"))

(deftest any-filter-active?-ignores-frame-scope
  (testing "rf2-4vp5j — the frame is a view SCOPE, not a filter; a frame
            in the state map (even if a caller passes one) never makes
            any-filter-active? true on its own"
    (is (false? (hidden/any-filter-active?
                  {:filters {:in [] :out []} :frame :rf/cart-frame :muted #{}}))
        "a frame selection alone is NOT an active filter")))

(deftest any-filter-active?-independent-of-hidden-count
  (testing "a filter can be active yet hide nothing (it matches every
            current cascade) — any-filter-active? is about whether a
            Clear Filters click would reset anything, not about the
            count"
    (is (true? (hidden/any-filter-active?
                 {:filters {:in [{:pattern :a}] :out []}
                  :muted #{}})))))

;; ---- indicator-visible? -------------------------------------------------

(deftest indicator-visible?-only-when-something-hidden
  (is (false? (hidden/indicator-visible? 0)))
  (is (true?  (hidden/indicator-visible? 1)))
  (is (true?  (hidden/indicator-visible? 9))))

;; ---- pill-summaries -----------------------------------------------------

(deftest pill-summaries-flatten-in-then-out
  (let [summaries (hidden/pill-summaries
                    {:in  [{:pattern :auth/login}]
                     :out [{:pattern :noise/tick}]})]
    (is (= 2 (count summaries)))
    (is (= :in  (:mode (first summaries))))
    (is (= :out (:mode (second summaries))))
    (is (= ":auth/login" (:label (first summaries))))
    (is (= ":noise/tick" (:label (second summaries))))))

(deftest pill-summaries-carry-typed-glyph
  (testing "a :machine typed pill surfaces its 'M' glyph + machine-id
            label so the indicator names it as the cause"
    (let [[s] (hidden/pill-summaries
                {:in [{:kind :machine :params {:machine-id :checkout/fsm}}]
                 :out []})]
      (is (= "M" (:glyph s)))
      (is (= ":checkout/fsm" (:label s))))))

(deftest pill-summaries-empty-for-no-pills
  (is (= [] (hidden/pill-summaries {:in [] :out []})))
  (is (= [] (hidden/pill-summaries nil))))

;; ---- summary (the full model) -------------------------------------------

(deftest summary-machine-pill-suppressing-rows
  (testing "the persisted :machine IN-pill case from the bug — raw has 5
            visible rows, the pill leaves 1; the summary reports 4 hidden,
            visible, with the pill named as cause"
    (let [s (hidden/summary 5 1
                            {:filters {:in [{:kind :machine
                                             :params {:machine-id :checkout/fsm}}]
                                       :out []}
                             :muted #{}})]
      (is (= 4 (:hidden s)))
      (is (true? (:visible? s)))
      (is (true? (:any-active? s)))
      (is (= 5 (:raw-count s)))
      (is (= 1 (:filtered-count s)))
      (is (= 1 (count (:pills s))))
      (is (= ":checkout/fsm" (:label (first (:pills s)))))
      (is (= 0 (:muted-count s))))))

(deftest summary-carries-no-frame-key
  (testing "rf2-4vp5j — the frame is a view scope, not a filter; the
            summary model never carries a `:frame` key, even if a caller
            leaks one into the state map"
    (let [s (hidden/summary 6 4 {:filters {:in [] :out []}
                                 :frame :rf/other-frame
                                 :muted #{}})]
      (is (not (contains? s :frame))
          "no :frame in the message model — frame is a scope, not a cause"))))

(deftest summary-filtered-to-empty-still-visible
  (testing "the filtered-to-empty case — raw has rows, the pill leaves
            zero; the message MUST still render (this is the
            looked-broken case)"
    (let [s (hidden/summary 6 0
                            {:filters {:in [{:pattern :a}] :out []}
                             :muted #{}})]
      (is (= 6 (:hidden s)))
      (is (true? (:visible? s))))))

(deftest summary-filtered-to-one-visible
  (testing "the filtered-to-one case — raw 4, filtered 1; reports 3
            hidden and renders"
    (let [s (hidden/summary 4 1
                            {:filters {:in [{:pattern :a}] :out []}
                             :muted #{}})]
      (is (= 3 (:hidden s)))
      (is (true? (:visible? s))))))

(deftest summary-nothing-hidden-not-visible
  (testing "no filter active, filtered == raw → no message"
    (let [s (hidden/summary 7 7
                            {:filters {:in [] :out []} :muted #{}})]
      (is (= 0 (:hidden s)))
      (is (false? (:visible? s)))
      (is (false? (:any-active? s))))))

(deftest summary-counts-mutes
  (let [s (hidden/summary 5 3
                          {:filters {:in [] :out []}
                           :muted #{:noise/tick :user/mouse-move}})]
    (is (= 2 (:hidden s)))
    (is (= 2 (:muted-count s)))
    (is (true? (:visible? s)))))

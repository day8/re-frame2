(ns re-frame.hicasso.receipt-spike-cljs-test
  "SPIKE WITNESS — rf2-hic-081. Delete with the spike.

  Three questions, and the third is the one the spike is for.

  1. Does the receipt REPORT the mechanical attempt facts, per boundary,
     on a real slice of an app?
  2. Is the allocation ATTACHED-ONLY — nothing built, nothing counted,
     nothing retained while nobody is receipting?
  3. Do the numbers come from the runtime's own leavings rather than from
     a counter in a hot loop? Question 3 is answered by construction (no
     counter exists to remove) and CHECKED here by the negative control:
     with the sink detached, the same renders leave the table empty and
     the projection says `unknown` rather than `[]`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.evidence :as evidence]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.receipt :as receipt]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::receipt-spike)

(rf/reg-sub :rc/left  (fn [db _] (:left db)))
(rf/reg-sub :rc/right (fn [db _] (:right db)))
(rf/reg-sub :rc/row   (fn [db [_ i]] (get-in db [:rows i])))

(rf/reg-event :rc/seed (fn [_ [_ db]] {:db db}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (collector/reset-runtime!)
                      (receipt/detach!))}))

(defn- seed! []
  (rf/dispatch-sync frame-id [:rc/seed {:left 1 :right 2 :rows [:a :b :c]}]))

;; Two bodies with deliberately different mechanical profiles: `wide` makes
;; many nodes from few reads, `deep` makes few nodes from many reads —
;; including a REPEATED read, so read-calls and unique-reads separate.

(defn- wide-body [_]
  (let [l (h/sub [:rc/left])]
    (into [:ul] (for [i (range 5)] [:li (str l "-" i)]))))

(defn- deep-body [_]
  (let [l  (h/sub [:rc/left])
        r  (h/sub [:rc/right])
        l2 (h/sub [:rc/left])                ; the repeat
        r0 (h/sub [:rc/row 0])]
    [:p (str l r l2 r0)]))

(defn- named [f nm]
  (unchecked-set f "displayName" nm)
  f)

(defn- render! [body-fn]
  (collector/render-body frame-id body-fn {}))

(defn- row-for [rs view]
  (first (filter #(= view (:view %)) rs)))

;; ---------------------------------------------------------------------------
;; 1. The receipt reports
;; ---------------------------------------------------------------------------

(deftest the-receipt-reports-mechanical-attempt-facts-per-boundary
  (seed!)
  (receipt/attach!)
  (let [wide (named wide-body "spike/wide")
        deep (named deep-body "spike/deep")]
    (render! wide)
    (render! wide)
    (render! deep)
    (let [rs (receipt/rows)
          w  (row-for rs "spike/wide")
          d  (row-for rs "spike/deep")]
      (testing "one row per boundary, keyed by the view's own name"
        (is (= #{"spike/wide" "spike/deep"} (set (map :view rs)))))

      (testing "ATTEMPTS count body runs, so two renders of one view read two"
        (is (= 2 (:attempts w)))
        (is (= 1 (:attempts d))))

      (testing "READ CALLS are every call, duplicates included"
        (is (= 2 (:read-calls w)) "one read, twice over two attempts")
        (is (= 4 (:read-calls d)) "four calls, one of them a repeat"))

      (testing "UNIQUE READS are the distinct keys of the LAST attempt"
        (is (= 1 (:unique-reads w)))
        (is (= 3 (:unique-reads d)) "four calls, three distinct keys"))

      (testing "CACHE HITS separate warm from cold — nothing is committed
                here, so every read is a cold probe and hits are zero"
        (is (= 0 (:cache-hits w)))
        (is (= 0 (:cache-hits d))))

      (testing "CODEC NODES separate a wide body from a deep one"
        (is (< (:codec-nodes d) (:codec-nodes w))
            (str "wide=" (:codec-nodes w) " deep=" (:codec-nodes d)))
        (is (pos? (:codec-nodes d)))))))

(deftest a-committed-read-is-a-cache-hit-so-the-field-answers-both-ways
  (seed!)
  (let [deep (named deep-body "spike/deep")]
    ;; First render cold, then COMMIT its read set, then render again.
    (render! deep)
    (let [entry (collector/last-reads)
          stop  (collector/commit-boundary! entry (fn []))]
      (receipt/attach!)
      (render! deep)
      (let [d (row-for (receipt/rows) "spike/deep")]
        (is (= 4 (:read-calls d)))
        (is (= 4 (:cache-hits d))
            "every read now finds a committed cell — the field is not stuck at zero"))
      (stop))))

;; ---------------------------------------------------------------------------
;; 2. Attached-only
;; ---------------------------------------------------------------------------

(deftest detached-the-receipt-counts-nothing-and-holds-nothing
  (seed!)
  (let [wide (named wide-body "spike/wide")]
    (is (nil? @receipt/!sink) "the fixture leaves it detached")
    (dotimes [_ 20] (render! wide))
    (is (nil? @receipt/!sink) "twenty renders attached nothing")
    (is (nil? (receipt/rows)) "and left no table to read")))

(deftest detaching-drops-the-table-so-nothing-is-retained
  (seed!)
  (let [wide (named wide-body "spike/wide")]
    (receipt/attach!)
    (render! wide)
    (is (= 1 (count (receipt/rows))))
    (receipt/detach!)
    (is (nil? (receipt/rows)))
    (render! wide)
    (is (nil? (receipt/rows)) "a render after detach re-attaches nothing")))

;; ---------------------------------------------------------------------------
;; 3. The projection tells the truth about not having looked
;; ---------------------------------------------------------------------------

(deftest detached-the-projection-says-unknown-and-never-an-empty-roster
  (let [p (receipt/projection)]
    (is (= evidence/unknown (:receipts p))
        "a receipt nobody took is unknown, not `[]`")
    (is (false? (:complete? p)))
    (is (= :opaque (:basis p)))
    (is (= :opaque (:reason (:loss p))))))

(deftest attached-the-projection-carries-the-rows-and-claims-completeness
  (seed!)
  (receipt/attach!)
  (render! (named wide-body "spike/wide"))
  (let [p (receipt/projection)]
    (is (= :observation (:basis p)))
    (is (true? (:complete? p)))
    (is (nil? (:loss p)))
    (is (= 1 (count (:receipts p))))))

(ns day8.re-frame2-xray.panels.app-db-diff-cache-isolation-cljs-test
  "Frame-isolation guard for the App-DB Diff per-epoch caches (rf2-nfgps).

  ## The bug class this catches

  `app-db-diff-subs` holds three process-global `defonce` caches
  (`diff-cache`, `redacted-modified-cache`, `flow-writes-cache`). Before
  rf2-nfgps they were keyed by `:epoch-id` ALONE and pruned with
  `(select-keys live)` where `live` was the CURRENT observed frame's
  epoch-ids.

  The framework's epoch contract guarantees `:epoch-id` is unique only
  WITHIN a frame's history — globally-unique epoch-ids are an
  implementation detail of the current global-counter scheme, NOT a
  spec promise. So in a multi-frame app two frames can carry the same
  epoch-id, and an `:epoch-id`-only cache key would:

    1. **Read-bleed** — frame B's read at epoch 5 returns frame A's
       cached diff (a different frame's data, violating frame isolation).
    2. **Cross-frame eviction** — a switch to frame B prunes the cache
       to frame B's epoch-ids, evicting frame A's still-live entries.

  rf2-nfgps keys the caches by `[frame-id epoch-id]` and prunes only the
  OBSERVED frame's keyspace. This file asserts both isolation halves at
  the SUB level (the actual write path) plus the pure prune helper.

  ## Why stub source subs (not the full spine chain)

  The unit-under-test is the cache keying inside the three subs. Their
  three DIRECT inputs are `:rf.xray/epoch-history`,
  `:rf.xray/focus-epoch-id`, and `:rf.xray/observed-frame` — the latter
  two the leaf derives from `:rf.xray/focus` + `:rf.xray/target-frame`.
  We register minimal stub source subs reading controlled app-db slots
  so we can pin an OVERLAPPING epoch-id (5) across two distinct observed
  frames deterministically — the precise scenario the global-counter
  impl never produces today but the contract permits. Driving the deep
  spine focus-composition chain would auto-assign globally-unique
  epoch-ids and defeat the test's purpose."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]))

;; ---- source-sub + seam stubs --------------------------------------------
;;
;; `install!` registers the leaf's own subs (`observed-frame`,
;; `focus-epoch-id`, the three cached subs, …) but NOT the cross-ns
;; source subs it depends on (`epoch-history` lives in epoch.cljs,
;; `focus` in spine.cljs, `target-frame` in epoch.cljs). We register
;; thin app-db-reading stubs so the test owns the full input triple.

(defn- install-source-stubs! []
  (rf/reg-sub :rf.xray/epoch-history
    (fn [db _] (get db :epoch-history [])))
  ;; `:rf.xray/observed-frame` = (or (:frame focus) target); seed both
  ;; through the focus map so one slot drives the whole observed-frame +
  ;; focus-epoch-id derivation.
  (rf/reg-sub :rf.xray/focus
    (fn [db _] (get db :focus)))
  (rf/reg-sub :rf.xray/target-frame
    (fn [db _] (get db :target-frame :rf/default))))

(defn- seed!
  "Drive the controlled inputs onto the `:rf/xray` app-db: the focus map
  (carrying `:frame` = observed frame and `:epoch-id` = focused epoch)
  and the epoch-history vector."
  [{:keys [frame epoch-id history]}]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:test/seed
                       {:focus         {:frame frame :epoch-id epoch-id}
                        :epoch-history history}])))

(defn- read-diff [frame epoch-id history]
  (seed! {:frame frame :epoch-id epoch-id :history history})
  (rf/with-frame :rf/xray
    (rf/subscribe-once [:rf.xray/selected-epoch-diff])))

(defn- mk-record
  "Minimal `:rf/epoch-record` carrying the slots the diff sub reads:
  `:epoch-id`, `:db-before`, `:db-after`."
  [epoch-id db-before db-after]
  {:epoch-id  epoch-id
   :db-before db-before
   :db-after  db-after})

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (reset! subs/diff-cache {})
                (reset! subs/redacted-modified-cache {})
                (reset! subs/flow-writes-cache {})
                ;; The `:rf/xray` frame the subs run in.
                (rf/reg-frame :rf/xray {})
                (rf/reg-event-db :test/seed
                  (fn [_db [_ slots]] slots))
                (install-source-stubs!)
                (subs/install!))}))

;; ---- (1) pure prune helper ----------------------------------------------

(deftest cache-key-compounds-frame-and-epoch
  (testing "rf2-nfgps — the cache key is [frame-id epoch-id], so two
            frames with the SAME epoch-id occupy DISTINCT keys"
    (is (= [:frame-a 5] (#'subs/cache-key :frame-a 5)))
    (is (= [:frame-b 5] (#'subs/cache-key :frame-b 5)))
    (is (not= (#'subs/cache-key :frame-a 5)
              (#'subs/cache-key :frame-b 5))
        "overlapping epoch-id across frames must NOT collapse to one key")))

(deftest prune-cache-preserves-foreign-frames-and-ages-own-stale
  (testing "rf2-nfgps — prune-cache ages out only the OBSERVED frame's
            stale entries; every other frame's entry survives, and the
            freshly-computed entry is always kept"
    (let [history-a [(mk-record 5 {} {:x 1})]   ; frame-a live = {5}
          ;; Pre-seed: frame-a's epoch 5 (live) + epoch 9 (stale, not in
          ;; history-a) + frame-b's epoch 5 (foreign — must survive).
          cache     {[:frame-a 5] :a5-old
                     [:frame-a 9] :a9-stale
                     [:frame-b 5] :b5}
          pruned    (#'subs/prune-cache cache :frame-a history-a
                                        [:frame-a 5] :a5-new)]
      (is (= :a5-new (get pruned [:frame-a 5]))
          "the freshly-computed entry overwrites and survives")
      (is (false? (contains? pruned [:frame-a 9]))
          "frame-a's epoch 9 is stale (absent from history-a) — aged out")
      (is (= :b5 (get pruned [:frame-b 5]))
          "frame-b's epoch 5 is a DIFFERENT frame — must NOT be evicted
           by a frame-a prune (the cross-frame-eviction half of the bug)"))))

;; ---- (2) sub-level read isolation ---------------------------------------

(deftest overlapping-epoch-ids-do-not-bleed-across-frames
  (testing "rf2-nfgps — two frames sharing epoch-id 5 each read their OWN
            diff; frame B's read must NOT return frame A's cached diff"
    ;; Frame A: epoch 5 is {} → {:a 1}.
    (let [rec-a    (mk-record 5 {} {:a 1})
          diff-a   (read-diff :frame-a 5 [rec-a])]
      (is (= [[[:a] nil 1 :added]] diff-a)
          "frame A's epoch-5 diff is the {} → {:a 1} projection")
      (is (= diff-a (get @subs/diff-cache [:frame-a 5]))
          "frame A's diff cached under the compound [frame-a 5] key")

      ;; Frame B: SAME epoch-id 5 but a DIFFERENT pair {:b 2} → {:b 99}.
      ;; With epoch-id-only keying this read would short-circuit on
      ;; frame A's cached value (read-bleed). With [frame-id epoch-id]
      ;; keying it misses and computes frame B's own diff.
      (let [rec-b  (mk-record 5 {:b 2} {:b 99})
            diff-b (read-diff :frame-b 5 [rec-b])]
        (is (= [[[:b] 2 99 :modified]] diff-b)
            "frame B computed its OWN epoch-5 diff — no read-bleed from
             frame A's cache")
        (is (not= diff-a diff-b)
            "the two frames' epoch-5 diffs are distinct")
        (is (= diff-b (get @subs/diff-cache [:frame-b 5]))
            "frame B's diff cached under [frame-b 5]")
        ;; The eviction half: frame A's entry survives frame B's read.
        (is (= diff-a (get @subs/diff-cache [:frame-a 5]))
            "frame A's epoch-5 cache survived frame B's read — no
             cross-frame eviction (rf2-nfgps isolation invariant)")))))

(deftest re-read-same-frame-epoch-hits-cache
  (testing "rf2-nfgps — caching still works WITHIN a frame: a second read
            of the same [frame epoch] returns the cached value (==)"
    (let [rec    (mk-record 7 {:n 0} {:n 1})
          first  (read-diff :frame-a 7 [rec])
          ;; Re-seed identical inputs; the cache must short-circuit and
          ;; return the SAME (identical) vector it stored on miss.
          second (read-diff :frame-a 7 [rec])]
      (is (= first second))
      (is (identical? first (get @subs/diff-cache [:frame-a 7]))
          "the stored entry is the very object the first read produced"))))

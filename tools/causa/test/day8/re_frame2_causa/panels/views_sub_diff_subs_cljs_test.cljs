(ns day8.re-frame2-causa.panels.views-sub-diff-subs-cljs-test
  "Per-leaf tests for `views-sub-diff-subs` (rf2-xjhhp Phase 2 of
  rf2-abts7).

  Exercises:

    - The pure `build-records` helper against synthetic epoch records
      (no re-frame runtime needed).
    - The composite sub `:rf.causa/views-sub-diff-for-focused-event`
      end-to-end through Causa's umbrella registry — install
      Causa's handlers, allocate the `:rf/causa` frame, write the
      epoch-history slot + focus the cascade via the spine, then read
      the composite via `subscribe`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.views-sub-diff-subs :as subs]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (reset! subs/sub-diff-cache {}))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- pure helper -----------------------------------------------------------

(deftest build-records-empty-sub-runs
  (testing "no sub-runs in the current epoch → empty records vector"
    (is (= [] (subs/build-records {:sub-runs   []
                                   :db-before  {}
                                   :db-after   {}})))))

(deftest build-records-three-recomputed-subs
  (testing "three sub-runs against a host-registered sub graph → three
            records, each with its sub-id + query-v preserved + a
            diff-sections vector from the Phase 1 engine"
    ;; Register three host-frame subs against the global registrar so
    ;; `compute-sub` can run them against `:db-before` / `:db-after`.
    (rf/reg-sub :test/counter      (fn [db _] (get db :counter)))
    (rf/reg-sub :test/items        (fn [db _] (get db :items)))
    (rf/reg-sub :test/user-profile (fn [db _] (get db :user)))
    (let [db-before {:counter 0
                     :items   [{:id 1 :qty 2}]
                     :user    {:name "Alice" :email "a@x"}}
          db-after  {:counter 1
                     :items   [{:id 1 :qty 3} {:id 2 :qty 1}]
                     :user    {:name "Alice" :email "alice@x"}}
          current   {:sub-runs  [{:sub-id :test/counter
                                  :query-v [:test/counter]
                                  :recomputed? true}
                                 {:sub-id :test/items
                                  :query-v [:test/items]
                                  :recomputed? true}
                                 {:sub-id :test/user-profile
                                  :query-v [:test/user-profile]
                                  :recomputed? true}]
                     :db-before db-before
                     :db-after  db-after}
          records   (subs/build-records current)]
      (is (= 3 (count records)))
      (is (= [:test/counter :test/items :test/user-profile]
             (mapv :sub-id records)))
      ;; Counter went 0 → 1 — scalar :modified at root.
      (let [counter-rec (first records)]
        (is (= 0 (:before-value counter-rec)))
        (is (= 1 (:after-value counter-rec)))
        (is (false? (:unchanged? counter-rec))))
      ;; Items grew an item AND mutated an existing :qty.
      (let [items-rec (nth records 1)]
        (is (= [{:id 1 :qty 2}] (:before-value items-rec)))
        (is (= [{:id 1 :qty 3} {:id 2 :qty 1}] (:after-value items-rec)))
        (is (seq (:diff-sections items-rec))
            "non-empty diff-sections for changed vector"))
      ;; User profile mutated :email but kept :name.
      (let [user-rec (nth records 2)]
        (is (false? (:unchanged? user-rec)))
        (is (seq (:diff-sections user-rec)))))))

(deftest build-records-unchanged-sub-flagged
  (testing "a sub-run whose value did not change (e.g. db slice didn't
            mutate the slot this sub reads) → :unchanged? true with
            empty :diff-sections"
    (rf/reg-sub :test/unchanged (fn [db _] (get db :unchanged)))
    (let [db-before {:unchanged {:x 1 :y 2} :counter 0}
          db-after  {:unchanged {:x 1 :y 2} :counter 1}
          current   {:sub-runs  [{:sub-id :test/unchanged
                                  :query-v [:test/unchanged]
                                  :recomputed? true}]
                     :db-before db-before
                     :db-after  db-after}
          [rec]     (subs/build-records current)]
      (is (true? (:unchanged? rec))
          "value identical pre/post → :unchanged?")
      (is (= [] (:diff-sections rec)))
      (is (= {:x 1 :y 2} (:before-value rec)))
      (is (= {:x 1 :y 2} (:after-value rec))))))

(deftest build-records-sentinel-handling
  (testing "Spec 015 sentinels (`:rf/redacted` / `:rf/large`) in sub
            outputs: identical sentinels collapse to :unchanged?;
            sentinel ↔ real-value diverges as a :modified leaf with
            both sides preserved (Phase 1 engine contract)"
    (rf/reg-sub :test/sentinel-passthrough
      (fn [db _] (get db :slot)))
    ;; Case A — both sides equal-sentinel → unchanged.
    (let [db-before {:slot {:value :rf/redacted}}
          db-after  {:slot {:value :rf/redacted}}
          current   {:sub-runs  [{:sub-id  :test/sentinel-passthrough
                                  :query-v [:test/sentinel-passthrough]
                                  :recomputed? true}]
                     :db-before db-before
                     :db-after  db-after}
          [rec]     (subs/build-records current)]
      (is (true? (:unchanged? rec))
          "identical sentinels → :unchanged? per the Phase 1 contract"))
    ;; Case B — sentinel on one side, real value on the other → diff.
    (let [db-before {:slot {:value :rf/redacted}}
          db-after  {:slot {:value 42}}
          current   {:sub-runs  [{:sub-id  :test/sentinel-passthrough
                                  :query-v [:test/sentinel-passthrough]
                                  :recomputed? true}]
                     :db-before db-before
                     :db-after  db-after}
          [rec]     (subs/build-records current)]
      (is (false? (:unchanged? rec))
          "sentinel ↔ real value → :modified, NOT :unchanged?")
      (is (seq (:diff-sections rec))
          "diff-sections carry the change point"))))

;; ---- leaf install / catalogue --------------------------------------------

(deftest leaf-install-registers-the-sub
  (subs/install!)
  (is (some? (registrar/handler :sub :rf.causa/views-sub-diff-for-focused-event))))

(deftest cache-atom-is-leaf-level
  (is (some? subs/sub-diff-cache))
  (is (map? @subs/sub-diff-cache)))

;; ---- composite sub end-to-end --------------------------------------------

(defn- seed-causa! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(deftest composite-returns-empty-when-no-cascade-focused
  (testing "with no cascade focused + empty epoch-history → empty
            records (defensive contract: composite never throws on a
            dormant frame)"
    (seed-causa!)
    (rf/with-frame :rf/causa
      (let [out @(rf/subscribe [:rf.causa/views-sub-diff-for-focused-event])]
        (is (= [] (:records out))
            "no current cascade → empty records")))))

(deftest composite-populates-records-from-focused-cascade
  (testing "with an epoch in history carrying :sub-runs + :db-before /
            :db-after AND the spine focused on the cascade, the
            composite produces one record per sub-run with the diff
            sections populated"
    (seed-causa!)
    ;; Register a host sub the cascade's sub-runs will reference.
    (rf/reg-sub :test/total (fn [db _] (get db :total)))
    (let [epoch {:epoch-id    :e1
                 :dispatch-id 7
                 :event-id    7
                 :db-before   {:total 10}
                 :db-after    {:total 12}
                 :sub-runs    [{:sub-id :test/total
                                :query-v [:test/total]
                                :recomputed? true}]
                 :renders     []}]
      (rf/with-frame :rf/causa
        ;; Seed Causa's app-db: epoch-history + focus pinned to the
        ;; cascade. `:rf.causa/sync-epoch-history` is the test-friendly
        ;; entry-point time-travel-events ships.
        (rf/dispatch-sync [:rf.causa/sync-epoch-history [epoch]])
        ;; Pin focus to the cascade (mode flips to :retro).
        (rf/dispatch-sync [:rf.causa/focus-cascade 7 nil])
        (let [out @(rf/subscribe [:rf.causa/views-sub-diff-for-focused-event])]
          (is (= 1 (count (:records out)))
              "one sub-run → one record")
          (let [rec (first (:records out))]
            (is (= :test/total (:sub-id rec)))
            (is (= 10 (:before-value rec)))
            (is (= 12 (:after-value rec)))
            (is (false? (:unchanged? rec)))))))))

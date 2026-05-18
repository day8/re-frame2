(ns day8.re-frame2-causa.panels.views-subs-cljs-test
  "Regression test for the Views panel's focused-cascade lookup
  (rf2-w15el; rf2-ak3ty prereq).

  Per `ai/findings/2026-05-18-tab-focus-invariant-audit.md` §Views the
  Views composite previously looked up the focused cascade in the
  epoch ring buffer by `:dispatch-id` against `:event-id` /
  `:trigger-event` head — none of which match in production because
  `:dispatch-id` is an opaque router counter and the epoch record
  carries an unrelated `:event-id` (event keyword) + `:epoch-id`
  (per-frame counter). The lookup always fell through to head; the
  panel-gallery testbed masked the bug by setting
  `:epoch-id == :dispatch-id` in fixtures.

  After rf2-ak3ty the spine's `:rf.causa/focus` sub now carries
  `:epoch-id`, and `views_subs.cljs/find-cascade-index` honours it
  directly. This test pins the invariant: focusing event A vs event B
  in the L2 list MUST change which cascade record the composite
  surfaces — never the head fallback when a different cascade is
  focused."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-test-support/reset-all!}))

(defn- seed-causa! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- epoch
  "Build a minimal epoch record with distinct `:epoch-id` and
  `:dispatch-id` — the production shape the audit identified as
  failing under the old lookup."
  [{:keys [epoch-id dispatch-id event-id renders sub-runs]}]
  {:epoch-id      epoch-id
   :dispatch-id   dispatch-id
   :event-id      event-id
   :trigger-event [event-id]
   :renders       (vec (or renders []))
   :sub-runs      (vec (or sub-runs []))
   :committed-at  (* 1000 epoch-id)
   :db-before     {}
   :db-after      {}
   :trace-events  [{:op-type     :event/dispatched
                    :dispatch-id dispatch-id
                    :event-v     [event-id]}]})

(deftest focused-cascade-pair-resolves-by-epoch-id
  (testing "Focus pinned via `:rf.causa/focus-cascade` resolves the
            focused cascade by `:epoch-id` (not head) — distinct
            :epoch-id / :dispatch-id pair, two cascades, focus on the
            EARLIER one → composite surfaces the earlier cascade's
            :renders / :sub-runs, not the head's."
    (seed-causa!)
    (let [earlier (epoch {:epoch-id     10
                          :dispatch-id  100
                          :event-id     :counter/inc
                          :renders      [{:render-key [:counter/badge :tok-A]
                                          :elapsed-ms 3}]
                          :sub-runs     [{:sub-id      :counter/value
                                          :query-v     [:counter/value]
                                          :recomputed? true
                                          :elapsed-ms  1}]})
          head    (epoch {:epoch-id     11
                          :dispatch-id  101
                          :event-id     :counter/dec
                          :renders      [{:render-key [:counter/badge :tok-B]
                                          :elapsed-ms 5}]
                          :sub-runs     [{:sub-id      :counter/value
                                          :query-v     [:counter/value]
                                          :recomputed? true
                                          :elapsed-ms  2}]})]
      (rf/with-frame :rf/causa
        ;; Seed the ring buffer.
        (rf/dispatch-sync [:rf.causa/sync-epoch-history [earlier head]])
        ;; Pin focus to the EARLIER cascade by its dispatch-id; the
        ;; spine resolves the corresponding :epoch-id from the buffer.
        (rf/dispatch-sync [:rf.causa/focus-cascade 100 nil])
        (let [pair  @(rf/subscribe [:rf.causa/views-focused-cascade-pair])
              focus (:focus pair)]
          (is (= 100 (:dispatch-id focus))
              "focus carries the dispatch-id we pinned")
          (is (= 10 (:epoch-id focus))
              "spine resolved :epoch-id from :rf.causa/focus-cascade
               (rf2-ak3ty)")
          (is (= 0 (:index pair))
              "composite indexed the EARLIER cascade, not the head
               (regression for rf2-w15el — pre-fix this was 1)")
          (is (= 10 (:epoch-id (:current pair)))
              "current cascade is the focused (earlier) record")
          (is (= [{:render-key [:counter/badge :tok-A]
                   :elapsed-ms 3}]
                 (:renders (:current pair)))
              "current cascade's :renders are the focused-event's
               renders, not the head's"))))))

(deftest focused-cascade-pair-falls-back-to-head-in-live-mode
  (testing "In LIVE mode with no explicit focus, `:epoch-id` is nil
            and the composite falls back to head — same behaviour the
            gallery relies on, preserved post-fix."
    (seed-causa!)
    (let [e1 (epoch {:epoch-id     20
                     :dispatch-id  200
                     :event-id     :counter/inc
                     :renders      [{:render-key [:counter/badge :tok-1]
                                     :elapsed-ms 1}]})
          e2 (epoch {:epoch-id     21
                     :dispatch-id  201
                     :event-id     :counter/dec
                     :renders      [{:render-key [:counter/badge :tok-2]
                                     :elapsed-ms 2}]})]
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/sync-epoch-history [e1 e2]])
        ;; No focus-cascade event → spine stays in LIVE mode.
        (let [pair (-> @(rf/subscribe [:rf.causa/views-focused-cascade-pair]))]
          (is (= 1 (:index pair))
              "LIVE mode → head fallback (last index)")
          (is (= 21 (:epoch-id (:current pair)))
              "current cascade is the head (e2)"))))))

(deftest focused-cascade-pair-falls-back-to-head-when-epoch-evicted
  (testing "Focus pins an epoch-id that is no longer in the ring
            buffer (evicted) → composite falls back to head rather
            than rendering empty. Defensive contract: stale focus
            never blanks the panel."
    (seed-causa!)
    (let [live (epoch {:epoch-id     31
                       :dispatch-id  301
                       :event-id     :counter/inc
                       :renders      [{:render-key [:counter/badge :tok-X]
                                       :elapsed-ms 1}]})]
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/sync-epoch-history [live]])
        ;; Pin focus to a dispatch-id NOT in the buffer — :epoch-id
        ;; resolves to nil; composite falls back to head.
        (rf/dispatch-sync [:rf.causa/focus-cascade 999 nil])
        (let [pair @(rf/subscribe [:rf.causa/views-focused-cascade-pair])]
          (is (nil? (:epoch-id (:focus pair)))
              "no :epoch-id resolution for an evicted/unknown cascade")
          (is (= 31 (:epoch-id (:current pair)))
              "fallback to head when focus can't be resolved"))))))

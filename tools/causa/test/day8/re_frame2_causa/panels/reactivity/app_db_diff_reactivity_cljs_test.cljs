(ns day8.re-frame2-causa.panels.reactivity.app-db-diff-reactivity-cljs-test
  "Sub-reactivity guard for the App-db Diff panel's primary view sub
  (rf2-dhoc9).

  The rf2-70tkv bug class: App-db Diff content stayed frozen on the
  previously-pinned epoch when the LIVE pill auto-followed a new
  cascade. Root cause was a stale `:selected-epoch-id` slot that the
  panel pivoted on; the fix re-pivoted the sub chain on `:rf.causa/
  focus-epoch-id` (a thin projection of the spine sub's `:epoch-id`
  axis). This test asserts the post-fix contract: the panel's primary
  `:rf.causa/app-db-diff` sub re-fires with a NEW value when focus
  flips between two epochs.

  ## What this guards against

  Any future regression in the reactivity chain
  `:rf.causa/focus → :rf.causa/focus-epoch-id → :rf.causa/selected-
  epoch-record → :rf.causa/app-db-diff` will surface as a test
  failure here, in millis, rather than as a frozen panel in
  Playwright (10 s + browser).

  Companion file to rf2-70tkv's `focus-sub-live-auto-follows-epoch-id-
  rf2-70tkv` integration test (which guards the spine slot's
  reactivity); this file extends the guard to the panel surface that
  consumes the spine sub."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

;; ---- fixtures -----------------------------------------------------------

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(def epoch-history
  [(h/mock-epoch :e1 :c1 {} {:counter 1})
   (h/mock-epoch :e2 :c2 {:counter 1} {:counter 2})])

;; ---- tests --------------------------------------------------------------

(deftest app-db-diff-sub-re-fires-on-focus-flip
  (testing "rf2-dhoc9 — the App-db Diff composite produces a different
            map for two distinct focus-epoch selections. The first
            cascade's diff differs from the second cascade's diff
            (`{} → {:counter 1}` vs `{:counter 1} → {:counter 2}`); if
            the sub chain doesn't track the focus flip both reads
            return the same map and the inequality fails."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [sig-1 (h/read-sub :rf.causa/app-db-diff)]
      ;; Sanity — the first focus produces the :e1 epoch's diff.
      (is (= :rf/default (:target-frame sig-1)))
      (is (false? (:history-empty? sig-1))
          "history is present → composite renders the diff body")
      (h/focus-cascade! :c2)
      (let [sig-2 (h/read-sub :rf.causa/app-db-diff)]
        ;; The whole composite must change — different focused epoch,
        ;; different diff payload.
        (is (not= sig-1 sig-2)
            "App-db Diff sub did not track focus flip — sub-chain
             reactivity broken (rf2-70tkv regression class)")))))

(deftest selected-epoch-diff-tracks-focus-flip
  (testing "rf2-dhoc9 — the inner `:rf.causa/selected-epoch-diff` sub
            (the per-cascade triples App-db Diff renders) returns
            different triple vectors for the two epochs. This is the
            tightest assertion of the reactive contract — the
            composite test above guards the panel surface; this guards
            the load-bearing inner sub."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [diff-1 (h/read-sub :rf.causa/selected-epoch-diff)]
      (is (= [{:op :added :path [:counter] :before nil :after 1}]
             diff-1)
          ":e1's diff — counter went nil → 1")
      (h/focus-cascade! :c2)
      (let [diff-2 (h/read-sub :rf.causa/selected-epoch-diff)]
        (is (= [{:op :modified :path [:counter] :before 1 :after 2}]
               diff-2)
            ":e2's diff — counter went 1 → 2")
        (is (not= diff-1 diff-2)
            ":selected-epoch-diff sub re-fired with new triples")))))

(deftest live-mode-auto-follows-new-cascade-rf2-70tkv
  (testing "rf2-dhoc9 + rf2-70tkv — Mike's exact repro: user is in LIVE
            on epoch :e1; a new cascade :c2 arrives with epoch :e2; the
            panel auto-advances to :e2's diff without an explicit click.
            Pre-rf2-70tkv the legacy `:selected-epoch-id` slot stayed
            pinned to :e1 and the panel froze."
    (h/setup-causa-frame!)
    (h/seed-cascades! [(first cascades)])
    (h/seed-epoch-history! [(first epoch-history)])
    ;; First read at LIVE on :e1.
    (let [sig-1 (h/read-sub :rf.causa/app-db-diff)]
      (is (false? (:history-empty? sig-1))
          "head epoch :e1 is the focus in LIVE mode")
      ;; Cascade :c2 arrives — both trace buffer and epoch history grow.
      (h/seed-cascades! cascades)
      (h/seed-epoch-history! epoch-history)
      (let [sig-2 (h/read-sub :rf.causa/app-db-diff)]
        (is (not= sig-1 sig-2)
            "rf2-70tkv — LIVE mode auto-follows the head cascade; the
             App-db Diff panel rebinds to the new head's diff")))))

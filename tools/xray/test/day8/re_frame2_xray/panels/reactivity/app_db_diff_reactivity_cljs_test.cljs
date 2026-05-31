(ns day8.re-frame2-xray.panels.reactivity.app-db-diff-reactivity-cljs-test
  "Sub-reactivity guard for the App-db Diff panel's primary view sub
  (rf2-dhoc9).

  The rf2-70tkv bug class: App-db Diff content stayed frozen on the
  previously-pinned epoch when the LIVE pill auto-followed a new
  cascade. Root cause was a stale `:selected-epoch-id` slot that the
  panel pivoted on; the fix re-pivoted the sub chain on `:rf.xray/
  focus-epoch-id` (a thin projection of the spine sub's `:epoch-id`
  axis). This test asserts the post-fix contract: the panel's primary
  `:rf.xray/app-db-diff` sub re-fires with a NEW value when focus
  flips between two epochs.

  ## What this guards against

  Any future regression in the reactivity chain
  `:rf.xray/focus → :rf.xray/focus-epoch-id → :rf.xray/selected-
  epoch-record → :rf.xray/app-db-diff` will surface as a test
  failure here, in millis, rather than as a frozen panel in
  Playwright (10 s + browser).

  Companion file to rf2-70tkv's `focus-sub-live-auto-follows-epoch-id-
  rf2-70tkv` integration test (which guards the spine slot's
  reactivity); this file extends the guard to the panel surface that
  consumes the spine sub."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

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
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [sig-1 (h/read-sub :rf.xray/app-db-diff)]
      ;; Sanity — the first focus produces the :e1 epoch's diff.
      (is (= :rf/default (:target-frame sig-1)))
      (is (false? (:history-empty? sig-1))
          "history is present → composite renders the diff body")
      (h/focus-cascade! :c2)
      (let [sig-2 (h/read-sub :rf.xray/app-db-diff)]
        ;; The whole composite must change — different focused epoch,
        ;; different diff payload.
        (is (not= sig-1 sig-2)
            "App-db Diff sub did not track focus flip — sub-chain
             reactivity broken (rf2-70tkv regression class)")))))

(deftest selected-epoch-diff-tracks-focus-flip
  (testing "rf2-dhoc9 — the inner `:rf.xray/selected-epoch-diff` sub
            (the per-cascade triples App-db Diff renders) returns
            different triple vectors for the two epochs. This is the
            tightest assertion of the reactive contract — the
            composite test above guards the panel surface; this guards
            the load-bearing inner sub."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [diff-1 (h/read-sub :rf.xray/selected-epoch-diff)]
      ;; rf2-5j7ch — the App-DB `:diff` sub post-processes the
      ;; wholesale root-replacement that Editscript emits for
      ;; `{} → {populated}` into per-key adds. Operator UX wants the
      ;; per-key view for cold-boot epochs ("what landed?") rather
      ;; than the engine-stable single-row root replacement. Non-
      ;; empty-side replacements still surface wholesale.
      (is (= [[[:counter] nil 1 :added]]
             diff-1)
          ":e1's diff — empty-to-populated expands to per-key adds")
      (h/focus-cascade! :c2)
      (let [diff-2 (h/read-sub :rf.xray/selected-epoch-diff)]
        (is (= [[[:counter] 1 2 :modified]]
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
    (h/setup-xray-frame!)
    (h/seed-cascades! [(first cascades)])
    (h/seed-epoch-history! [(first epoch-history)])
    ;; First read at LIVE on :e1.
    (let [sig-1 (h/read-sub :rf.xray/app-db-diff)]
      (is (false? (:history-empty? sig-1))
          "head epoch :e1 is the focus in LIVE mode")
      ;; Cascade :c2 arrives — both trace buffer and epoch history grow.
      (h/seed-cascades! cascades)
      (h/seed-epoch-history! epoch-history)
      (let [sig-2 (h/read-sub :rf.xray/app-db-diff)]
        (is (not= sig-1 sig-2)
            "rf2-70tkv — LIVE mode auto-follows the head cascade; the
             App-db Diff panel rebinds to the new head's diff")))))

;; ---- rf2-yng0y — atomic focused-epoch before-image ----------------------
;;
;; The zoom-nav stale-frame flash (rf2-yng0y) was a stale `:before` that
;; lagged the focused `:epoch-id` by one animation frame, because the
;; before-image resolved through a deep composed chain
;; (`:focus → focus-epoch-id → selected-epoch-record → app-db-state`).
;; The fix collapses that chain into ONE sub
;; (`:rf.xray/app-db-current+diff`) that resolves `:before` and
;; `:epoch-id` from the SAME epoch record in a single computation, so
;; they can never disagree.
;;
;; The flash itself is timing-sensitive (it only paints under real mouse
;; timing vs rAF batching, which a unit test cannot reproduce
;; deterministically). What we CAN assert deterministically is the
;; ATOMICITY INVARIANT that makes the flash impossible by construction:
;; the sub never returns a `{:before :epoch-id}` pair where `:before`
;; differs from the `:db-before` of the record named by `:epoch-id`.

(defn- db-before-of
  "The `:db-before` of the `epoch-id` record in the fixture history,
  or nil when `epoch-id` is nil / absent."
  [epoch-id]
  (some (fn [r] (when (= epoch-id (:epoch-id r)) (:db-before r)))
        epoch-history))

(defn- assert-atomic!
  "Read `:rf.xray/app-db-current+diff` and assert the atomicity
  invariant: `:before` equals the `:db-before` of `:epoch-id`."
  [label]
  (let [{:keys [before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
    (is (= (db-before-of epoch-id) before)
        (str label " — :before must equal the :db-before of :epoch-id "
             "(atomicity invariant rf2-yng0y); got before=" (pr-str before)
             " epoch-id=" (pr-str epoch-id)))
    epoch-id))

(deftest current+diff-before-is-atomic-with-epoch-id
  (testing "rf2-yng0y — the atomic sub's `:before` is ALWAYS the
            `:db-before` of its own `:epoch-id`, across every focus
            selection. There is no `{:before :epoch-id}` pair where the
            two name different epochs — the stale-`before` glitch is
            impossible by construction."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    ;; Focus :c1 (epoch :e1, db-before {}).
    (h/focus-cascade! :c1)
    (is (= :e1 (assert-atomic! "focus :c1")))
    (let [{:keys [before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
      (is (= :e1 epoch-id))
      (is (= {} before) ":e1's db-before is the empty map"))
    ;; Flip to :c2 (epoch :e2, db-before {:counter 1}) — the slot the
    ;; flash used to surface on. before + epoch-id move together.
    (h/focus-cascade! :c2)
    (is (= :e2 (assert-atomic! "focus :c2")))
    (let [{:keys [before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
      (is (= :e2 epoch-id))
      (is (= {:counter 1} before) ":e2's db-before is {:counter 1}"))
    ;; Flip back — invariant holds in both directions.
    (h/focus-cascade! :c1)
    (is (= :e1 (assert-atomic! "focus :c1 (return)")))))

(deftest current+diff-value-is-stable-while-before-tracks-focus
  (testing "rf2-yng0y — the App-DB tab is a CURRENT-STATE inspector:
            `:value` is the live target-frame-db (constant as the
            operator scrubs epochs); only `:before` moves per epoch.
            Confirms the panel's atomic contract — value steady, before
            tracking — so the diff overlay is the sole per-epoch change."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [v1 (:value (h/read-sub :rf.xray/app-db-current+diff))
          b1 (:before (h/read-sub :rf.xray/app-db-current+diff))]
      (h/focus-cascade! :c2)
      (let [v2 (:value (h/read-sub :rf.xray/app-db-current+diff))
            b2 (:before (h/read-sub :rf.xray/app-db-current+diff))]
        (is (= v1 v2)
            ":value is constant across the scrub (live target-frame-db)")
        (is (not= b1 b2)
            ":before moves with the focused epoch — the diff overlay is
             the only per-epoch change")))))

(deftest app-db-state-section-model-tracks-focused-before
  (testing "rf2-yng0y — `:rf.xray/app-db-state` (the panel's consumed
            section model, now derived from the atomic sub) carries the
            focused epoch's `:db-before` as the diff pre-image. The
            section model's `:before-top` reflects the focused epoch and
            moves atomically with the focus flip — no stale carryover."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [m1 (h/read-sub :rf.xray/app-db-state)]
      (h/focus-cascade! :c2)
      (let [m2 (h/read-sub :rf.xray/app-db-state)]
        (is (not= (:before-top m1) (:before-top m2))
            "section model's :before-top re-derives per focused epoch
             (atomic with the focus flip — no stale before)")))))

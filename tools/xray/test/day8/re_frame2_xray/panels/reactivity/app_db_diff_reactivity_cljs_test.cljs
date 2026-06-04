(ns day8.re-frame2-xray.panels.reactivity.app-db-diff-reactivity-cljs-test
  "Sub-reactivity guard for the App-db Diff panel's primary view sub
  (rf2-dhoc9).

  The rf2-70tkv bug class: App-db Diff content stayed frozen on the
  previously-pinned epoch when the LIVE pill auto-followed a new
  cascade. Root cause was a stale `:selected-epoch-id` slot that the
  panel pivoted on; the fix re-pivoted the sub chain on `:rf.xray/
  focus-epoch-id` (a thin projection of the spine sub's `:epoch-id`
  axis). This test asserts the post-fix contract: the panel's primary
  `:rf.xray/app-db-current+diff` sub re-fires with a NEW value when
  focus flips between two epochs.

  rf2-p53m2 — the panel's primary sub is `:rf.xray/app-db-current+diff`
  (→ `:rf.xray/app-db-state`); the former `:rf.xray/app-db-diff` /
  `:rf.xray/selected-epoch-diff` composite family had no production view
  consumer and was pruned. The reactivity guards below pivot on the
  live surface.

  ## What this guards against

  Any future regression in the reactivity chain
  `:rf.xray/focus → :rf.xray/focus-epoch-id → :rf.xray/app-db-current+
  diff` will surface as a test failure here, in millis, rather than as
  a frozen panel in Playwright (10 s + browser).

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

(deftest app-db-current+diff-sub-re-fires-on-focus-flip
  (testing "rf2-dhoc9 / rf2-p53m2 — the panel's primary
            `:rf.xray/app-db-current+diff` sub produces a different map
            for two distinct focus-epoch selections (`{} → {:counter 1}`
            vs `{:counter 1} → {:counter 2}`); if the sub chain doesn't
            track the focus flip both reads return the same map and the
            inequality fails."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [sig-1 (h/read-sub :rf.xray/app-db-current+diff)]
      ;; Sanity — the first focus resolves the :e1 epoch.
      (is (= :e1 (:epoch-id sig-1)))
      (is (= {:counter 1} (:value sig-1))
          "history is present → :value is :e1's db-after")
      (h/focus-cascade! :c2)
      (let [sig-2 (h/read-sub :rf.xray/app-db-current+diff)]
        ;; The whole map must change — different focused epoch,
        ;; different value/before/epoch-id.
        (is (not= sig-1 sig-2)
            "app-db-current+diff sub did not track focus flip — sub-chain
             reactivity broken (rf2-70tkv regression class)")
        (is (= :e2 (:epoch-id sig-2)))))))

(deftest live-mode-auto-follows-new-cascade-rf2-70tkv
  (testing "rf2-dhoc9 + rf2-70tkv — Mike's exact repro: user is in LIVE
            on epoch :e1; a new cascade :c2 arrives with epoch :e2; the
            panel auto-advances to :e2 without an explicit click.
            Pre-rf2-70tkv the legacy `:selected-epoch-id` slot stayed
            pinned to :e1 and the panel froze."
    (h/setup-xray-frame!)
    (h/seed-cascades! [(first cascades)])
    (h/seed-epoch-history! [(first epoch-history)])
    ;; First read at LIVE on :e1.
    (let [sig-1 (h/read-sub :rf.xray/app-db-current+diff)]
      (is (= :e1 (:epoch-id sig-1))
          "head epoch :e1 is the focus in LIVE mode")
      ;; Cascade :c2 arrives — both trace buffer and epoch history grow.
      (h/seed-cascades! cascades)
      (h/seed-epoch-history! epoch-history)
      (let [sig-2 (h/read-sub :rf.xray/app-db-current+diff)]
        (is (not= sig-1 sig-2)
            "rf2-70tkv — LIVE mode auto-follows the head cascade; the
             App-db panel rebinds to the new head")
        (is (= :e2 (:epoch-id sig-2)))))))

;; ---- rf2-02j4r / rf2-yng0y — atomic per-epoch-delta before+after --------
;;
;; The zoom-nav stale-frame flash (rf2-yng0y) was a stale `:before` that
;; lagged the focused `:epoch-id` by one animation frame, because the
;; before-image resolved through a deep composed chain
;; (`:focus → focus-epoch-id → selected-epoch-record → app-db-state`).
;; The fix collapses that chain into ONE sub
;; (`:rf.xray/app-db-current+diff`) that resolves the focused record's
;; slots in a single computation, so they can never disagree.
;;
;; rf2-02j4r REVERSED the rf2-yng0y `:value = live-db` design: `:value`
;; is now the focused epoch's `:db-after`, so the inline diff is the
;; per-epoch delta `db-before(N) → db-after(N)` — NOT the cumulative
;; live-vs-`db-before(N)` that bled later events onto earlier selections.
;; Both `:value` and `:before` are pulled from the SAME focused record,
;; which STRENGTHENS atomicity (every slot from one record).
;;
;; The flash itself is timing-sensitive (it only paints under real mouse
;; timing vs rAF batching, which a unit test cannot reproduce
;; deterministically). What we CAN assert deterministically is the
;; ATOMICITY INVARIANT that makes the flash impossible by construction:
;; the sub never returns a `{:value :before :epoch-id}` triple where
;; `:value`/`:before` differ from the `:db-after`/`:db-before` of the
;; record named by `:epoch-id`.

(defn- db-before-of
  "The `:db-before` of the `epoch-id` record in the fixture history,
  or nil when `epoch-id` is nil / absent."
  [epoch-id]
  (some (fn [r] (when (= epoch-id (:epoch-id r)) (:db-before r)))
        epoch-history))

(defn- db-after-of
  "The `:db-after` of the `epoch-id` record in the fixture history,
  or nil when `epoch-id` is nil / absent."
  [epoch-id]
  (some (fn [r] (when (= epoch-id (:epoch-id r)) (:db-after r)))
        epoch-history))

(defn- assert-atomic!
  "Read `:rf.xray/app-db-current+diff` and assert the atomicity
  invariant: `:before` equals the `:db-before` of `:epoch-id` AND
  `:value` equals its `:db-after` (per-epoch-delta, rf2-02j4r)."
  [label]
  (let [{:keys [value before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
    (is (= (db-before-of epoch-id) before)
        (str label " — :before must equal the :db-before of :epoch-id "
             "(atomicity invariant rf2-yng0y); got before=" (pr-str before)
             " epoch-id=" (pr-str epoch-id)))
    (is (= (db-after-of epoch-id) value)
        (str label " — :value must equal the :db-after of :epoch-id "
             "(per-epoch-delta, rf2-02j4r); got value=" (pr-str value)
             " epoch-id=" (pr-str epoch-id)))
    epoch-id))

(deftest current+diff-before-is-atomic-with-epoch-id
  (testing "rf2-yng0y / rf2-02j4r — the atomic sub's `:before` is ALWAYS
            the `:db-before` of its own `:epoch-id` and `:value` is its
            `:db-after`, across every focus selection. No
            `{:value :before :epoch-id}` triple names a different epoch
            for any slot — the stale-`before` glitch is impossible by
            construction, and the diff is always the epoch's own delta."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    ;; Focus :c1 (epoch :e1, db-before {}, db-after {:counter 1}).
    (h/focus-cascade! :c1)
    (is (= :e1 (assert-atomic! "focus :c1")))
    (let [{:keys [value before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
      (is (= :e1 epoch-id))
      (is (= {} before) ":e1's db-before is the empty map")
      (is (= {:counter 1} value) ":e1's db-after is {:counter 1}"))
    ;; Flip to :c2 (epoch :e2, db-before {:counter 1}, db-after
    ;; {:counter 2}) — the slot the flash used to surface on. value +
    ;; before + epoch-id all move together.
    (h/focus-cascade! :c2)
    (is (= :e2 (assert-atomic! "focus :c2")))
    (let [{:keys [value before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
      (is (= :e2 epoch-id))
      (is (= {:counter 1} before) ":e2's db-before is {:counter 1}")
      (is (= {:counter 2} value) ":e2's db-after is {:counter 2}"))
    ;; Flip back — invariant holds in both directions.
    (h/focus-cascade! :c1)
    (is (= :e1 (assert-atomic! "focus :c1 (return)")))))

(deftest current+diff-value-and-before-both-track-focus
  (testing "rf2-02j4r — the App-DB tab shows the SELECTED epoch's OWN
            delta: BOTH `:value` (db-after) and `:before` (db-before)
            move per epoch, so the inline diff is db-before(N) →
            db-after(N) and nothing later bleeds in. (This REVERSES the
            rf2-yng0y `:value = live-db, constant as you scrub` design.)"
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [v1 (:value (h/read-sub :rf.xray/app-db-current+diff))
          b1 (:before (h/read-sub :rf.xray/app-db-current+diff))]
      (h/focus-cascade! :c2)
      (let [v2 (:value (h/read-sub :rf.xray/app-db-current+diff))
            b2 (:before (h/read-sub :rf.xray/app-db-current+diff))]
        (is (not= v1 v2)
            ":value moves with the focused epoch (db-after(N)) — NOT a
             constant live-db (rf2-02j4r reversal)")
        (is (not= b1 b2)
            ":before moves with the focused epoch (db-before(N))")
        (is (= {:counter 1} v1) ":e1's value is its db-after")
        (is (= {:counter 2} v2) ":e2's value is its db-after")))))

;; ---- rf2-02j4r — no later-event bleed onto an earlier selection ---------
;;
;; THE BUG: focusing a NON-head epoch N showed the CUMULATIVE diff
;; live-db vs db-before(N) — every change from N forward to NOW lit up,
;; so a key added by a LATER event bled onto epoch N's selection. This
;; test focuses an EARLIER epoch after a later event has occurred and
;; asserts the sub yields exactly epoch N's own delta (db-after(N)),
;; with no trace of the later event's key. The head-only test passes
;; under the OLD buggy code (live == db-after at head); this one focuses
;; a non-head epoch, so it FAILS under the old code and PASSES only with
;; the per-epoch-delta fix.

(def bleed-cascades
  [(h/cascade :early :rf/default)
   (h/cascade :late  :rf/default)])

(def bleed-history
  ;; :early adds :media/deep; :late (the LATER event) adds :media/shallow.
  ;; The live head db carries BOTH keys.
  [(h/mock-epoch :ep-early :early
                 {:counter 1}
                 {:counter 1 :media/deep :on})
   (h/mock-epoch :ep-late  :late
                 {:counter 1 :media/deep :on}
                 {:counter 1 :media/deep :on :media/shallow :on})])

(deftest earlier-epoch-shows-own-delta-no-later-bleed
  (testing "rf2-02j4r — selecting an EARLIER epoch after a later event
            occurred shows ONLY that epoch's own delta; the later
            event's added key does NOT bleed in. Focus :ep-early (which
            added :media/deep): :value must be ep-early's db-after
            (carrying :media/deep but NOT :media/shallow), and the diff
            against :before introduces ONLY :media/deep."
    (h/setup-xray-frame!)
    (h/seed-cascades! bleed-cascades)
    (h/seed-epoch-history! bleed-history)
    ;; Scrub BACK to the earlier epoch (the head is :ep-late).
    (h/focus-cascade! :early)
    (let [{:keys [value before epoch-id]}
          (h/read-sub :rf.xray/app-db-current+diff)]
      (is (= :ep-early epoch-id) "focus resolved to the earlier epoch")
      (is (= {:counter 1 :media/deep :on} value)
          ":value is ep-early's OWN db-after — :media/deep present")
      (is (not (contains? value :media/shallow))
          "rf2-02j4r — :media/shallow (added by the LATER :late event)
           must NOT bleed into the earlier selection's :value")
      (is (= {:counter 1} before)
          ":before is ep-early's db-before (no :media/* keys yet)")
      ;; The inline diff (value vs before) introduces ONLY :media/deep.
      (let [added-keys (remove (set (keys before)) (keys value))]
        (is (= [:media/deep] (vec added-keys))
            "the per-epoch delta adds exactly :media/deep — nothing the
             later event introduced")))
    ;; Sanity: focusing the LATER epoch shows ITS own delta (:media/shallow).
    (h/focus-cascade! :late)
    (let [{:keys [value before]} (h/read-sub :rf.xray/app-db-current+diff)]
      (is (contains? value :media/shallow)
          ":late's db-after carries :media/shallow")
      (let [added-keys (remove (set (keys before)) (keys value))]
        (is (= [:media/shallow] (vec added-keys))
            ":late's per-epoch delta adds exactly :media/shallow")))))

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

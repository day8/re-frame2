(ns day8.re-frame2-xray.panels.reactivity.app-db-diff-reactivity-cljs-test
  "Sub-reactivity guard for the App-db Diff panel's primary view sub.

  The bug class: App-db Diff content frozen on a previously-pinned
  epoch while the LIVE pill auto-follows a new cascade — what a panel
  pivoting on a stale `:selected-epoch-id` slot would show. The sub
  chain pivots on `:rf.xray/focus-epoch-id` (a thin projection of the
  spine sub's `:epoch-id` axis). This test asserts the contract: the
  panel's primary `:rf.xray/app-db-current+diff` sub re-fires with a
  NEW value when focus flips between two epochs.

  The panel's primary sub is `:rf.xray/app-db-current+diff`
  (→ `:rf.xray/app-db-state`); there is no `:rf.xray/app-db-diff` /
  `:rf.xray/selected-epoch-diff` composite family. The reactivity
  guards below pivot on the live surface.

  ## What this guards against

  Any future regression in the reactivity chain
  `:rf.xray/focus → :rf.xray/focus-epoch-id → :rf.xray/app-db-current+
  diff` will surface as a test failure here, in millis, rather than as
  a frozen panel in Playwright (10 s + browser).

  Companion file to the `focus-sub-live-auto-follows-epoch-id-
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

(deftest live-mode-auto-follows-new-cascade-rf2-70tkv
  (testing "the repro: user is in LIVE
            on epoch :e1; a new cascade :c2 arrives with epoch :e2; the
            panel auto-advances to :e2 without an explicit click. A panel
            pivoting on a `:selected-epoch-id` slot would stay pinned to
            :e1 and freeze."
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
            "LIVE mode auto-follows the head cascade; the
             App-db panel rebinds to the new head")
        (is (= :e2 (:epoch-id sig-2)))))))

;; ---- atomic per-epoch-delta before+after --------------------------------
;;
;; A zoom-nav stale-frame flash would be a stale `:before` lagging the
;; focused `:epoch-id` by one animation frame, as it would if the
;; before-image resolved through a deep composed chain
;; (`:focus → focus-epoch-id → selected-epoch-record → app-db-state`).
;; ONE sub (`:rf.xray/app-db-current+diff`) resolves the focused record's
;; slots in a single computation, so they can never disagree.
;;
;; `:value` is the focused epoch's `:db-after`, so the inline diff is the
;; per-epoch delta `db-before(N) → db-after(N)` — NOT the cumulative
;; live-vs-`db-before(N)`, which would bleed later events onto earlier
;; selections. Both `:value` and `:before` are pulled from the SAME focused record,
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
  `:value` equals its `:db-after` (per-epoch-delta)."
  [label]
  (let [{:keys [value before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
    (is (= (db-before-of epoch-id) before)
        (str label " — :before must equal the :db-before of :epoch-id "
             "(atomicity invariant); got before=" (pr-str before)
             " epoch-id=" (pr-str epoch-id)))
    (is (= (db-after-of epoch-id) value)
        (str label " — :value must equal the :db-after of :epoch-id "
             "(per-epoch-delta); got value=" (pr-str value)
             " epoch-id=" (pr-str epoch-id)))
    epoch-id))

(deftest current+diff-before-is-atomic-with-epoch-id
  (testing "the atomic sub's `:before` is ALWAYS
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
    ;; {:counter 2}) — the slot a stale-before flash would surface on. value +
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

;; ---- no later-event bleed onto an earlier selection ---------------------
;;
;; The failure this pins: focusing a NON-head epoch N and showing the
;; CUMULATIVE diff live-db vs db-before(N) — every change from N forward
;; to NOW would light up, so a key added by a LATER event would bleed onto
;; epoch N's selection. This test focuses an EARLIER epoch after a later
;; event has occurred and asserts the sub yields exactly epoch N's own
;; delta (db-after(N)), with no trace of the later event's key. A
;; head-only test cannot tell the two apart (live == db-after at head);
;; this one focuses a non-head epoch, so only the per-epoch delta passes.

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
  (testing "selecting an EARLIER epoch after a later event
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
          ":media/shallow (added by the LATER :late event)
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
  (testing "`:rf.xray/app-db-state` (the panel's consumed
            section model, derived from the atomic sub) carries the
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

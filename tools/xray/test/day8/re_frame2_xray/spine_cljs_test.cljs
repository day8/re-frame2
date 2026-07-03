(ns day8.re-frame2-xray.spine-cljs-test
  "Direct CLJS coverage for the spine substrate (rf2-adve5) — pure-
  reducer + sub composition + the legacy-slot shim contract.

  Per `tools/xray/spec/018-Event-Spine.md` §6 Spine binding the spine
  sub `:rf.xray/focus` is the one axis every dependent surface reads
  from. This file asserts:

  1. The pure reducers (`focus-cascade-reducer`, `follow-head-reducer`,
     `toggle-live-pause-reducer`, `set-frame-reducer`, `focus-step-
     reducer`, `preview-cascade-reducer`) — JVM-runnable shape, no
     re-frame machinery needed.
  2. `compose-focus` derives `:head?` + the effective `:dispatch-id`
     correctly across LIVE / RETRO / paused / evicted-cascade states.
  3. Focus is the single source of truth (rf2-uy7nz) —
     `:rf.xray/select-dispatch-id` and `:rf.xray/focus-event` write
     the spine `:focus` slot (carrying `:epoch-id`); App-DB-diff / Views
     follow it through `:rf.xray/focus` → `:rf.xray/focus-epoch-id`.
     There is no mirror slot.
  4. The registered sub composes the slot + cascades via the standard
     re-frame reactive path."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch-api]
            [re-frame.epoch.state :as epoch-state]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus-resolver]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- cascade fixture ----------------------------------------------------

(defn- cascade
  "Build a minimal cascade shape — enough for the spine helpers'
  by-id lookups + head detection. Per
  `re-frame.trace.projection/group-by-event` cascades carry at least
  `:dispatch-id` and `:frame`; tests of the spine module don't need
  the full domino shape, just the identifier slots."
  [dispatch-id frame-id]
  {:dispatch-id dispatch-id
   :frame       frame-id
   :event       nil
   :handler     nil
   :fx          nil
   :effects     []
   :subs        []
   :renders     []
   :other       []})

(def ^:private fixture-cascades
  "Three-cascade fixture; oldest-first per group-by-event' contract.
  c3 is the head."
  [(cascade :c1 :rf/default)
   (cascade :c2 :rf/default)
   (cascade :c3 :rf/default)])

;; -------------------------------------------------------------------------
;; (1) Pure helpers — head / by-id / step
;; -------------------------------------------------------------------------

(deftest head-cascade-returns-last-entry
  (is (= (last fixture-cascades) (spine/head-cascade fixture-cascades)))
  (is (nil? (spine/head-cascade []))
      "empty cascade vector → nil head"))

(deftest head-dispatch-id-returns-last-id
  (is (= :c3 (spine/head-dispatch-id fixture-cascades)))
  (is (nil? (spine/head-dispatch-id []))))

(deftest cascade-by-id-finds-existing
  (is (= (nth fixture-cascades 1)
         (spine/cascade-by-id fixture-cascades :c2)))
  (is (nil? (spine/cascade-by-id fixture-cascades :nonexistent)))
  (is (nil? (spine/cascade-by-id fixture-cascades nil))
      "nil id → nil match"))

(deftest step-dispatch-id-prev-stays-bounded
  (testing "stepping prev from c2 → c1; from c1 → c1 (bounded)"
    (is (= :c1 (spine/step-dispatch-id fixture-cascades :c2 -1)))
    (is (= :c1 (spine/step-dispatch-id fixture-cascades :c1 -1)))))

(deftest step-dispatch-id-next-stays-bounded
  (testing "stepping next from c2 → c3; from c3 → c3 (bounded)"
    (is (= :c3 (spine/step-dispatch-id fixture-cascades :c2 +1)))
    (is (= :c3 (spine/step-dispatch-id fixture-cascades :c3 +1)))))

(deftest step-from-nil-starts-at-head
  (testing "nil current-id (no focus yet) → step from head"
    (is (= :c2 (spine/step-dispatch-id fixture-cascades nil -1))
        "prev from head → c2")
    (is (= :c3 (spine/step-dispatch-id fixture-cascades nil +1))
        "next from head → c3 (bounded at head)")))

(deftest step-from-evicted-starts-at-head
  (testing "an evicted current-id (not in cascades) → step from head"
    (is (= :c2 (spine/step-dispatch-id fixture-cascades :gone -1))
        "evicted id → step from head")))

(deftest step-on-empty-cascade-vector-returns-nil
  (is (nil? (spine/step-dispatch-id [] :c1 +1)))
  (is (nil? (spine/step-dispatch-id [] nil -1))))

;; ---- frame-strict cascade lookup (rf2-bz7flo) ---------------------------

(def ^:private cross-frame-cascades
  "Two cascades SHARE dispatch-id :cx in different frames with distinct
  payloads (distinct head dispatch-ids around them). Mirrors the
  framework projection's `[frame dispatch-id]` grouping, which emits a
  separate cascade record per frame for a cross-frame id collision."
  [(assoc (cascade :cx :frame/a) :event [:a-event])
   (assoc (cascade :cx :frame/b) :event [:b-event])])

(deftest cascade-by-focus-frame-strict-rf2-bz7flo
  (testing "rf2-bz7flo — focus carrying a :frame selects the cascade in
            THAT frame, not the first same-id match in the vector"
    (is (= [:b-event]
           (:event (spine/cascade-by-focus cross-frame-cascades
                                           {:dispatch-id :cx :frame :frame/b})))
        "focus on :frame/b picks the :frame/b cascade though :frame/a's
         same-id cascade sits earlier")
    (is (= [:a-event]
           (:event (spine/cascade-by-focus cross-frame-cascades
                                           {:dispatch-id :cx :frame :frame/a})))
        "focus on :frame/a picks the :frame/a cascade"))
  (testing "no cascade for the focused frame → nil (no foreign-frame fallback)"
    (is (nil? (spine/cascade-by-focus cross-frame-cascades
                                      {:dispatch-id :cx :frame :frame/c}))))
  (testing "frameless focus degrades to id-only (first match)"
    (is (= [:a-event]
           (:event (spine/cascade-by-focus cross-frame-cascades
                                           {:dispatch-id :cx})))))
  (testing "nil dispatch-id → nil"
    (is (nil? (spine/cascade-by-focus cross-frame-cascades {})))
    (is (nil? (spine/cascade-by-focus cross-frame-cascades nil)))))

(deftest step-cascade-keeps-frame-and-id-in-lockstep-rf2-bz7flo
  (testing "rf2-bz7flo — step-cascade returns the WHOLE stepped row so
            its :frame comes from the row stepped to, not an id-only
            re-resolution that could land on a foreign frame's same-id
            cascade. Walk a multi-frame vector where step lands on a
            cascade whose id also exists earlier in a different frame."
    (let [cascades [(cascade :c0 :frame/a)
                    (assoc (cascade :cx :frame/a) :event [:a-event])
                    (assoc (cascade :cx :frame/b) :event [:b-event])]]
      ;; stepping next from :c0 lands on index 1 — the :frame/a :cx row.
      (let [stepped (spine/step-cascade cascades :c0 +1)]
        (is (= :cx (:dispatch-id stepped)))
        (is (= :frame/a (:frame stepped))
            "the stepped row's frame is :frame/a (the row at the stepped
             index), NOT :frame/b which carries the same dispatch-id")
        (is (= [:a-event] (:event stepped))))
      ;; step-dispatch-id stays a thin projection of the same walk.
      (is (= :cx (spine/step-dispatch-id cascades :c0 +1))))))

(deftest step-cascade-current-lookup-is-frame-strict-rf2-xj3kbn
  (testing "rf2-xj3kbn — when the CURRENT dispatch-id itself is
            duplicated across frames, the current-position lookup must
            match `[frame dispatch-id]`, not the bare id. The id-only
            lookup found the FIRST same-id cascade (an earlier frame's)
            and stepped from the wrong index."
    ;; :cx is the HEAD (idx 2, :frame/b) but also occurs at idx 0
    ;; (:frame/a). prev from the head must land on :mid (idx 1), NOT be
    ;; swallowed as a false boundary no-op off the idx-0 :frame/a row.
    (let [cascades [(assoc (cascade :cx :frame/a) :event [:a-event])
                    (assoc (cascade :mid :frame/b) :event [:mid-event])
                    (assoc (cascade :cx :frame/b) :event [:b-event])]]
      (testing "frame-strict 4-arity: current is :cx @ :frame/b (head)"
        (let [stepped (spine/step-cascade cascades :frame/b :cx -1)]
          (is (= :mid (:dispatch-id stepped))
              "prev from the :frame/b :cx head lands on :mid, not a no-op
               off the earlier :frame/a :cx")
          (is (= :frame/b (:frame stepped)))
          (is (= [:mid-event] (:event stepped)))))
      (testing "frame-strict 4-arity: current is :cx @ :frame/a steps prev
                to the head row at idx 0 (a true boundary)"
        (let [stepped (spine/step-cascade cascades :frame/a :cx -1)]
          (is (= :cx (:dispatch-id stepped)))
          (is (= :frame/a (:frame stepped))
              "prev from idx 0 clamps to idx 0 — its own :frame/a row")))
      (testing "id-only 3-arity preserved (frameless callers): finds the
                first :cx at idx 0"
        (let [stepped (spine/step-cascade cascades :cx -1)]
          (is (= :frame/a (:frame stepped))
              "no current-frame → id-only fallback (first match at idx 0)")))
      (testing "frame-strict falls back to id-only when no frame-matching
                current row exists (stale stored frame must not hide a
                valid current row)"
        (let [stepped (spine/step-cascade cascades :frame/nope :cx -1)]
          (is (= :frame/a (:frame stepped))
              "no :cx in :frame/nope → id-only fallback to idx 0"))))))

;; ---- focusable-head-frame-id (rf2-boyc2) --------------------------------

(deftest focusable-head-frame-id-returns-head-cascade-frame
  (testing "rf2-boyc2 — the head focusable cascade's :frame is the seed
            frame for first-mount `:target-frame` + `:epoch-history`. The
            picker-driven `set-frame-reducer` aligns the same two axes
            on a frame change; this helper extends the alignment to the
            first-mount path so the App-DB panel doesn't render the boot
            empty-state when pre-mount cascades exist on a non-default
            frame."
    (let [cascades [(cascade :c1 :cart-frame)
                    (cascade :c2 :checkout-frame)
                    (cascade :c3 :cart-frame)]]
      (is (= :cart-frame (spine/focusable-head-frame-id cascades))
          "head focusable cascade is :c3 — its :frame is :cart-frame"))))

(deftest focusable-head-frame-id-skips-ungrouped
  (testing "rf2-boyc2 — :ungrouped bucket is filtered out so the seed
            frame is always a real, L2-visible cascade's frame. Without
            the filter the bucket (no `:frame` slot) could be chosen as
            the head. EP-0002 (rf2-bd4div): a nil seed leaves the target
            UNSELECTED (`set-target-frame`'s `(or frame-id
            default-target-frame)` = nil) — it no longer resolves to a
            synthesised `:rf/default`."
    (let [cascades [(cascade :c1 :cart-frame)
                    (cascade :ungrouped nil)]]
      (is (= :cart-frame (spine/focusable-head-frame-id cascades))
          ":ungrouped is filtered; head focusable cascade is :c1"))))

(deftest focusable-head-frame-id-empty-cascades-returns-nil
  (testing "rf2-boyc2 — no focusable cascades → nil. EP-0002 (rf2-bd4div):
            callers seed `defaults/default-target-frame` = nil, so the
            cold-start path leaves the target UNSELECTED (no `:rf/default`
            synthesis)."
    (is (nil? (spine/focusable-head-frame-id [])))
    (is (nil? (spine/focusable-head-frame-id
                [(cascade :ungrouped nil)]))
        "only the :ungrouped bucket present → nil")))

;; -------------------------------------------------------------------------
;; (2) compose-focus — :head? + effective :dispatch-id derivation
;; -------------------------------------------------------------------------

(deftest compose-focus-empty-slot-defaults-to-live-at-head
  (testing "empty :focus slot with cascades → :live :dispatch-id at head"
    (let [r (spine/compose-focus nil fixture-cascades)]
      (is (= :c3 (:dispatch-id r))
          "no slot → snap to head")
      (is (= :live (:mode r)))
      (is (true? (:head? r))))))

(deftest compose-focus-live-with-stale-id-snaps-head
  (testing ":live mode + stored id that no longer exists → snap to head"
    (let [r (spine/compose-focus {:dispatch-id :gone :mode :live}
                                 fixture-cascades)]
      (is (= :c3 (:dispatch-id r)))
      (is (true? (:head? r))))))

(deftest compose-focus-retro-preserves-stored-id
  (testing ":retro mode preserves the stored id even if not the head"
    (let [r (spine/compose-focus {:dispatch-id :c1 :mode :retro}
                                 fixture-cascades)]
      (is (= :c1 (:dispatch-id r)))
      (is (= :retro (:mode r)))
      (is (false? (:head? r))))))

(deftest compose-focus-empty-cascades-snaps-nil
  (testing "no cascades yet → nil :dispatch-id, :head? true (vacuously)"
    (let [r (spine/compose-focus nil [])]
      (is (nil? (:dispatch-id r)))
      (is (true? (:head? r))))))

(deftest compose-focus-derives-frame-from-cascade
  (testing ":frame derives from the focused cascade when the picker
            and cascade frames agree (the common case post-click —
            focus-cascade-reducer writes the cascade's frame onto the
            slot so they're consistent)"
    (let [r (spine/compose-focus {:dispatch-id :c2 :mode :retro
                                  :frame :rf/default}
                                 fixture-cascades)]
      (is (= :rf/default (:frame r))
          "frame comes from the cascade record"))))

(deftest compose-focus-stale-slot-frame-falls-through
  (testing "rf2-oziyr — when the slot's :frame restricts the focusable
            walk to an empty subset (e.g. mid-transition between
            picker selections), :frame falls back to the slot value so
            the picker label and the panel stay consistent"
    (let [r (spine/compose-focus {:dispatch-id :c2 :mode :retro
                                  :frame :unknown-frame}
                                 fixture-cascades)]
      (is (= :unknown-frame (:frame r))
          "no cascade in :unknown-frame → slot's :frame wins as fallback"))))

(deftest compose-focus-paused-flag-rides-through
  (let [r (spine/compose-focus {:dispatch-id nil :mode :live :paused? true}
                               fixture-cascades)]
    (is (true? (:paused? r)))
    (is (= :live (:mode r)))
    (is (true? (:head? r)))))

(deftest compose-focus-previewing-flag-rides-through
  (let [r (spine/compose-focus {:dispatch-id :c2 :mode :retro
                                :previewing? true}
                               fixture-cascades)]
    (is (true? (:previewing? r)))))

;; ---- frame-picker scoping (rf2-oziyr) -----------------------------------

(def ^:private multi-frame-fixture
  "Mixed-frame fixture — :cart-frame and :checkout-frame interleaved.
  Latest overall is the checkout cascade :c4; latest in :cart-frame is
  :c3. Used to verify compose-focus's picker-aware head selection."
  [(cascade :c1 :cart-frame)
   (cascade :c2 :checkout-frame)
   (cascade :c3 :cart-frame)
   (cascade :c4 :checkout-frame)])

(deftest compose-focus-picker-frame-scopes-head-walk
  (testing "rf2-oziyr — when the picker (slot :frame) restricts to one
            frame, LIVE head tracking auto-snaps to that frame's head,
            not the global head"
    (let [r (spine/compose-focus {:frame :cart-frame :mode :live}
                                 multi-frame-fixture)]
      (is (= :c3 (:dispatch-id r))
          "head of :cart-frame is :c3, NOT :c4 (the global head)")
      (is (= :cart-frame (:frame r)))
      (is (true? (:head? r))))
    (let [r (spine/compose-focus {:frame :checkout-frame :mode :live}
                                 multi-frame-fixture)]
      (is (= :c4 (:dispatch-id r))
          "head of :checkout-frame is :c4")
      (is (= :checkout-frame (:frame r))))))

(deftest compose-focus-nil-picker-frame-keeps-global-head
  (testing "rf2-oziyr — no picker restriction (slot :frame nil) keeps
            the pre-existing behaviour: LIVE tracks global head"
    (let [r (spine/compose-focus {:frame nil :mode :live}
                                 multi-frame-fixture)]
      (is (= :c4 (:dispatch-id r))
          "global head wins when picker is unset"))))

;; -------------------------------------------------------------------------
;; (3) focus-cascade-reducer — writes :focus (single source of truth)
;; -------------------------------------------------------------------------

(deftest focus-cascade-reducer-writes-focus-slot
  (let [db {}
        r  (spine/focus-cascade-reducer db :c2 :rf/default)]
    (is (= :c2 (get-in r [:focus :dispatch-id])))
    (is (= :retro (get-in r [:focus :mode])))
    (is (= :rf/default (get-in r [:focus :frame])))))

(deftest focus-cascade-reducer-writes-epoch-into-focus
  (testing "rf2-uy7nz — the reducer stamps the settling epoch into the
            spine `[:focus :epoch-id]` slot (the single source of truth
            App-DB-diff / Views follow via :rf.xray/focus-epoch-id). No
            mirror slot is written — the dead :selected-epoch-id /
            :selected-dispatch-id / :selected-dispatch slots are gone."
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default 77)]
      (is (= 77 (get-in r [:focus :epoch-id])) ":focus :epoch-id carries the epoch")
      (is (not (contains? r :selected-epoch-id))
          "dead :selected-epoch-id mirror slot is no longer written")
      (is (not (contains? r :selected-dispatch-id))
          "dead :selected-dispatch-id slot is no longer written")
      (is (not (contains? r :selected-dispatch))
          "dead :selected-dispatch slot is no longer written"))))

(deftest focus-cascade-reducer-without-frame-omits-frame
  (let [r (spine/focus-cascade-reducer {} :c2 nil)]
    (is (= :c2 (get-in r [:focus :dispatch-id])))
    (is (nil? (get-in r [:focus :frame]))
        "no :frame key in the focus slot when frame-id was nil")))

;; -------------------------------------------------------------------------
;; (4) focus-step-reducer — prev/next stepping
;; -------------------------------------------------------------------------

(deftest focus-step-reducer-prev-flips-to-retro
  (let [db {:focus {:dispatch-id :c3 :mode :live}}
        r  (spine/focus-step-reducer db fixture-cascades -1)]
    (is (= :c2 (get-in r [:focus :dispatch-id])))
    (is (= :retro (get-in r [:focus :mode])))
    (is (not (contains? r :selected-dispatch-id))
        "dead :selected-dispatch-id mirror slot is no longer written")))

(deftest focus-step-reducer-next-returns-to-head-as-live
  (let [db {:focus {:dispatch-id :c2 :mode :retro}}
        r  (spine/focus-step-reducer db fixture-cascades +1)]
    (is (= :c3 (get-in r [:focus :dispatch-id])))
    (is (= :live (get-in r [:focus :mode]))
        "stepping back to head implicitly re-engages LIVE")))

(deftest focus-step-reducer-from-empty-slot-snaps-to-head-then-steps
  (testing "rf2-s0s5x Phase A — with an empty `:focus` slot the
            current-id is derived through `compose-focus` (which snaps
            to head in :live). The spine `:focus` slot is the sole
            source of truth — there is no legacy selection slot to lift
            a stale id from."
    (let [db {}
          r  (spine/focus-step-reducer db fixture-cascades -1)]
      (is (= :c2 (get-in r [:focus :dispatch-id]))
          "step prev: empty :focus → composer derives head (c3) → prev
           lands on c2 (one step back from head)"))))

;; -------------------------------------------------------------------------
;; (5) follow-head-reducer
;; -------------------------------------------------------------------------

(deftest follow-head-reducer-snaps-to-live
  (let [db {:focus {:dispatch-id :c1 :epoch-id 5 :mode :retro :paused? true}}
        r  (spine/follow-head-reducer db)]
    (is (nil? (get-in r [:focus :dispatch-id]))
        "follow-head clears :dispatch-id so compose-focus snaps to head")
    (is (= :live (get-in r [:focus :mode])))
    (is (false? (get-in r [:focus :paused?])))
    (is (nil? (get-in r [:focus :epoch-id]))
        ":focus :epoch-id cleared in lockstep")))

;; -------------------------------------------------------------------------
;; (6) toggle-live-pause-reducer
;; -------------------------------------------------------------------------

(deftest toggle-live-pause-reducer-flips-paused
  (let [db {:focus {:mode :live :paused? false}}
        r  (spine/toggle-live-pause-reducer db)]
    (is (true? (get-in r [:focus :paused?])))
    (let [r2 (spine/toggle-live-pause-reducer r)]
      (is (false? (get-in r2 [:focus :paused?]))
          "second toggle reverses"))))

(deftest toggle-live-pause-reducer-noop-in-retro
  (testing "in :retro, toggle-live-pause is a no-op — Space has no
            meaning when the user has pinned an older row"
    (let [db {:focus {:mode :retro :paused? false}}
          r  (spine/toggle-live-pause-reducer db)]
      (is (= db r) "db unchanged"))))

;; -------------------------------------------------------------------------
;; (7) set-frame-reducer + preview-cascade-reducer
;; -------------------------------------------------------------------------

(deftest set-frame-reducer-clears-dispatch-id
  (let [db {:focus {:dispatch-id :c2 :frame :rf/default :mode :retro}}
        r  (spine/set-frame-reducer db :app/dialog)]
    (is (= :app/dialog (get-in r [:focus :frame])))
    (is (nil? (get-in r [:focus :dispatch-id]))
        "frame switch snaps to new frame's head")
    (is (= :live (get-in r [:focus :mode])))))

;; -------------------------------------------------------------------------
;; rf2-ug1r6 + rf2-thodq — picker also drives :target-frame +
;; :epoch-history so every per-frame composite reads the picked frame's
;; epochs. See `set-frame-reducer` docstring for the shared root cause.
;; -------------------------------------------------------------------------

(deftest set-frame-reducer-writes-target-frame
  (testing "the picker IS the user's 'observe this frame' gesture —
            align the legacy :target-frame slot with the new
            [:focus :frame] axis so every per-frame composite reads
            off one consistent frame identity."
    (let [db {:focus {:dispatch-id :c1 :frame :rf/default :mode :live}
              :target-frame :rf/default}
          r  (spine/set-frame-reducer db :cart-frame)]
      (is (= :cart-frame (:target-frame r))
          ":target-frame follows the picker selection"))))

(deftest set-frame-reducer-reseeds-epoch-history-with-resolved-vector
  (testing "the 3-arg arity takes the resolved per-frame epoch history
            and overwrites the slot — the App-DB Diff selected-epoch-*
            chain + the Views focused-cascade-pair both pivot on this
            slot, so a stale `:rf/default`-history slot is what caused
            both panels to render empty-state after a picker change."
    (let [cart-history [{:epoch-id 10 :dispatch-id 100
                         :db-before {:k 1} :db-after {:k 2}}
                        {:epoch-id 11 :dispatch-id 101
                         :db-before {:k 2} :db-after {:k 3}}]
          db           {:epoch-history [{:epoch-id 1}]   ; stale :rf/default head
                        :target-frame  :rf/default}
          r            (spine/set-frame-reducer db :cart-frame cart-history)]
      (is (= cart-history (:epoch-history r))
          ":epoch-history is the picked frame's ring contents")
      (is (= :cart-frame (:target-frame r))
          ":target-frame switches in lockstep")
      (is (= :cart-frame (get-in r [:focus :frame]))
          "[:focus :frame] still tracks the picker"))))

(deftest set-frame-reducer-default-arity-clears-epoch-history
  (testing "the 2-arg arity (back-compat for callers that don't have
            the framework's per-frame ring handy) writes an empty
            history — preserves the per-frame invariant rather than
            leaving the slot stale across the switch."
    (let [db {:epoch-history [{:epoch-id 1}]
              :target-frame  :rf/default}
          r  (spine/set-frame-reducer db :cart-frame)]
      (is (= [] (:epoch-history r))
          "no resolved history → slot cleared, never left stale")
      (is (= :cart-frame (:target-frame r))))))

(deftest preview-cascade-reducer-sets-previewing-true
  (let [db {}
        r  (spine/preview-cascade-reducer db :c2)]
    (is (true? (get-in r [:focus :previewing?])))
    (is (= :c2 (get-in r [:focus :dispatch-id])))))

(deftest preview-cascade-reducer-nil-clears-preview
  (let [db {:focus {:dispatch-id :c1 :previewing? true :mode :retro}}
        r  (spine/preview-cascade-reducer db nil)]
    (is (false? (get-in r [:focus :previewing?])))
    (is (= :c1 (get-in r [:focus :dispatch-id]))
        "committed selection survives preview-clear")))

(deftest preview-cascade-reducer-writes-epoch-id-rf2-yng0y
  (testing "rf2-yng0y — the 3-arity preview reducer writes :epoch-id in
            lockstep with :dispatch-id so the spine's two focus axes
            never desync mid-preview (the App-DB before-image follows
            :epoch-id). Pre-fix the preview wrote :dispatch-id alone,
            leaving :epoch-id pinned to the committed epoch — a latent
            LIVE-mode correctness bug."
    (let [db {:focus {:dispatch-id :c1 :epoch-id :e1 :mode :live}}
          r  (spine/preview-cascade-reducer db :c2 :e2)]
      (is (true? (get-in r [:focus :previewing?])))
      (is (= :c2 (get-in r [:focus :dispatch-id]))
          ":dispatch-id moved to the previewed cascade")
      (is (= :e2 (get-in r [:focus :epoch-id]))
          ":epoch-id moved with it — the two axes stay in lockstep")))
  (testing "rf2-yng0y — nil epoch-id (the 2-arity delegate, or an
            unresolved preview) leaves :epoch-id untouched rather than
            clobbering it to nil; the before-image stays on the last
            resolved epoch until a real epoch resolves."
    (let [db {:focus {:dispatch-id :c1 :epoch-id :e1 :mode :live}}
          r  (spine/preview-cascade-reducer db :c2 nil)]
      (is (= :c2 (get-in r [:focus :dispatch-id])))
      (is (= :e1 (get-in r [:focus :epoch-id]))
          "unresolved preview epoch-id is non-destructive")))
  (testing "rf2-yng0y — the 2-arity arity is preserved (back-compat) and
            leaves :epoch-id untouched."
    (let [db {:focus {:epoch-id :e1}}
          r  (spine/preview-cascade-reducer db :c2)]
      (is (= :c2 (get-in r [:focus :dispatch-id])))
      (is (= :e1 (get-in r [:focus :epoch-id]))))))

(deftest preview-cascade-reducer-clear-restores-committed-selection-rf2-uo0rc5
  (testing "rf2-uo0rc.5 — a hover-then-leave gesture in RETRO restores the
            COMMITTED selection. Pre-fix the nil-arm cleared only
            :previewing?, leaving :dispatch-id / :epoch-id pinned at the
            PREVIEWED value (compose-focus honours the stored slot-id in
            RETRO), so focus stayed on the hovered cascade rather than the
            originally-committed one."
    (let [committed {:dispatch-id :c1 :epoch-id :e1 :mode :retro}
          ;; 1) user has :c1 committed; 2) hovers :c2 (preview-start);
          ;; 3) leaves (preview-clear).
          previewed (spine/preview-cascade-reducer {:focus committed} :c2 :e2)
          cleared   (spine/preview-cascade-reducer previewed nil)]
      (testing "the preview moved focus transiently"
        (is (= :c2 (get-in previewed [:focus :dispatch-id])))
        (is (= :e2 (get-in previewed [:focus :epoch-id])))
        (is (true? (get-in previewed [:focus :previewing?]))))
      (testing "preview-clear restores the COMMITTED :c1 / :e1, not the previewed :c2 / :e2"
        (is (= :c1 (get-in cleared [:focus :dispatch-id]))
            ":dispatch-id restored to the committed cascade")
        (is (= :e1 (get-in cleared [:focus :epoch-id]))
            ":epoch-id restored to the committed epoch")
        (is (false? (get-in cleared [:focus :previewing?])))
        (is (nil? (get-in cleared [:focus :pre-preview]))
            "the backup slot is dropped after restore — no residue")))))

(deftest preview-cascade-reducer-move-keeps-original-backup-rf2-uo0rc5
  (testing "rf2-uo0rc.5 — moving the hover across rows within one gesture
            keeps the ORIGINAL committed backup (not a previously-previewed
            value), so leaving still lands on the committed selection."
    (let [committed {:dispatch-id :c1 :epoch-id :e1 :mode :retro}
          hover-c2  (spine/preview-cascade-reducer {:focus committed} :c2 :e2)
          hover-c3  (spine/preview-cascade-reducer hover-c2 :c3 :e3)
          cleared   (spine/preview-cascade-reducer hover-c3 nil)]
      (is (= :c3 (get-in hover-c3 [:focus :dispatch-id]))
          "the latest hover wins while previewing")
      (is (= :c1 (get-in cleared [:focus :dispatch-id]))
          "leaving restores the ORIGINAL committed :c1, never the intermediate :c2")
      (is (= :e1 (get-in cleared [:focus :epoch-id]))))))

(deftest preview-cascade-reducer-no-backup-when-nothing-committed-rf2-uo0rc5
  (testing "rf2-uo0rc.5 — preview-clear with no prior gesture (no backup)
            is a safe no-op on the committed slot (back-compat with the
            pre-fix `nil-clears-preview` contract)."
    (let [db {:focus {:dispatch-id :c1 :previewing? true :mode :retro}}
          r  (spine/preview-cascade-reducer db nil)]
      (is (false? (get-in r [:focus :previewing?])))
      (is (= :c1 (get-in r [:focus :dispatch-id]))
          "committed selection survives when there is no backup to restore"))))

;; The handler-level cross-frame regression for rf2-uo0rc.5 lives in
;; section (9) alongside the other `seed-cascades!`-driven handler tests
;; (`preview-cascade-handler-does-not-persist-cross-frame-rekey-rf2-uo0rc5`).

;; -------------------------------------------------------------------------
;; (8) Registered :rf.xray/focus sub — reactive composition
;; -------------------------------------------------------------------------

;; Forward-declare the `epoch` helper (defined in section 10) so the
;; rf2-70tkv reactive test below can build epoch-history fixtures.
(declare epoch)

(defn- focus-sub []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/focus])))

(defn- seed-cascades! [cascades-vec]
  ;; Build a minimal trace-buffer that re-projects to the desired
  ;; cascade vector. Each cascade needs one trace event carrying the
  ;; cascade's id so group-by-event' frame-index pairs the right
  ;; events. The simplest shape: one :rf.event/dispatched per cascade.
  (let [events (map-indexed
                 (fn [i {:keys [dispatch-id frame]}]
                   {:id        (inc i)
                    :op-type   :rf.event
                    :operation :rf.event/dispatched
                    :tags      {:rf.trace/dispatch-id dispatch-id
                                :frame       frame
                                :rf.event/v       [(keyword "evt" (str (name dispatch-id)))]}})
                 cascades-vec)]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer (vec events)]))))

(deftest focus-sub-returns-default-shape-on-empty-buffer
  (setup-xray-frame!)
  (let [r (focus-sub)]
    (is (= :live (:mode r))
        "empty buffer → :live (head-tracking)")
    (is (nil? (:dispatch-id r)))
    (is (true? (:head? r)))))

(deftest focus-sub-snaps-to-head-on-empty-slot
  (setup-xray-frame!)
  (seed-cascades! fixture-cascades)
  (let [r (focus-sub)]
    (is (= :c3 (:dispatch-id r))
        "empty :focus slot → :live → snap to head c3")
    (is (true? (:head? r)))))

(deftest focus-sub-preserves-retro-selection
  (setup-xray-frame!)
  (seed-cascades! fixture-cascades)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event :c1 :rf/default]))
  (let [r (focus-sub)]
    (is (= :c1 (:dispatch-id r)))
    (is (= :retro (:mode r)))
    (is (false? (:head? r)))))

(deftest focus-sub-shimmed-by-legacy-select-dispatch-id
  (testing "dispatching the legacy :rf.xray/select-dispatch-id writes
            through to the spine — proves the shim runs in BOTH
            directions (legacy event → spine sub)"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id :c2 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c2 (:dispatch-id r))
          "spine focus tracks the legacy selection event")
      (is (= :retro (:mode r))))))

(deftest focus-sub-step-events-walk-cascades
  (setup-xray-frame!)
  (seed-cascades! fixture-cascades)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event :c3 :rf/default])
    (rf/dispatch-sync [:rf.xray/focus-event-prev]))
  (let [r (focus-sub)]
    (is (= :c2 (:dispatch-id r)) "prev steps from c3 to c2")
    (is (= :retro (:mode r))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event-prev]))
  (let [r (focus-sub)]
    (is (= :c1 (:dispatch-id r)) "prev again → c1"))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event-next])
    (rf/dispatch-sync [:rf.xray/focus-event-next]))
  (let [r (focus-sub)]
    (is (= :c3 (:dispatch-id r)) "two nexts → back to head")
    (is (= :live (:mode r))
        "stepping back to head re-engages LIVE")))

(deftest follow-head-event-resnaps-to-head
  (setup-xray-frame!)
  (seed-cascades! fixture-cascades)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event :c1 :rf/default])
    (rf/dispatch-sync [:rf.xray/follow-head]))
  (let [r (focus-sub)]
    (is (= :c3 (:dispatch-id r)) "follow-head snaps back to head c3")
    (is (= :live (:mode r)))
    (is (true? (:head? r)))))

(deftest toggle-live-pause-event-flips-paused
  (setup-xray-frame!)
  (seed-cascades! fixture-cascades)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/toggle-live-pause]))
  (let [r (focus-sub)]
    (is (true? (:paused? r)))
    (is (= :live (:mode r)))))

;; -------------------------------------------------------------------------
;; (8b) Self-noise filter agreement (rf2-qlvq8)
;; -------------------------------------------------------------------------
;;
;; The event-side `spine/db->cascades` walk (head-id / step / focus
;; resolution + the machine-inspector scrubber) MUST return the same
;; filtered set as the reactive `:rf.xray/cascades` sub. Both strip
;; Xray-internal cascades via `self-noise/xray-internal-cascade?`. The
;; case the filter exists for: an `:rf.xray/*` event dispatched WITHOUT
;; a `{:frame :rf/xray}` option lands on `:rf/default` and slips past
;; the ingest gate, surfacing as a cascade in the raw projection. Before
;; rf2-qlvq8 the sub stripped it but `db->cascades` did not, so j/k
;; stepping / click-on-head LIVE detection / composed focus could
;; resolve a target the user never sees in L2.

(defn- seed-mixed-cascades!
  "Seed the trace buffer with two cascades on `:rf/default`: a normal
  host event (`:order/submit`) and a frameless `:rf.xray/*` event
  (`:rf.xray/select-tab`) that landed on the host frame. The xray-
  internal one is the projection head (last) — exactly the position
  that would mis-resolve head-id if it survived the event-side walk."
  []
  (let [events [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
                 :tags {:rf.trace/dispatch-id :c-host
                        :frame :rf/default
                        :rf.event/v [:order/submit]}}
                {:id 2 :op-type :rf.event :operation :rf.event/dispatched
                 :tags {:rf.trace/dispatch-id :c-xray
                        :frame :rf/default
                        :rf.event/v [:rf.xray/select-tab]}}]]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer (vec events)]))))

(deftest db->cascades-strips-xray-internal-cascade
  (testing "rf2-qlvq8 — db->cascades applies the self-noise filter so the
            frameless :rf.xray/* cascade on :rf/default is absent from the
            event-side walk"
    (setup-xray-frame!)
    (seed-mixed-cascades!)
    (let [db        @(frame/app-db-container :rf/xray)
          cascades  (spine/db->cascades db)
          ids       (mapv :dispatch-id cascades)]
      (is (= [:c-host] ids)
          ":c-xray (an :rf.xray/* cascade) stripped from db->cascades")
      (is (= :c-host (spine/head-dispatch-id cascades))
          "head-id resolves to the host cascade, not the xray-internal one")
      (is (= :c-host (spine/focusable-head-id cascades))
          "focusable-head-id ignores the xray-internal cascade"))))

(deftest db->cascades-agrees-with-reactive-cascades-sub
  (testing "rf2-qlvq8 — the event-side walk and the reactive sub return the
            SAME filtered set for the frameless :rf.xray/*-on-:rf/default
            case the filter handles"
    (setup-xray-frame!)
    (seed-mixed-cascades!)
    (let [db          @(frame/app-db-container :rf/xray)
          event-side  (spine/db->cascades db)
          reactive    (rf/with-frame :rf/xray
                        @(rf/subscribe [:rf.xray/cascades]))]
      (is (= event-side reactive)
          "db->cascades and :rf.xray/cascades agree")
      (is (= [:c-host] (mapv :dispatch-id reactive))
          "both omit the xray-internal cascade — sanity that the filter fired"))))

;; -------------------------------------------------------------------------
;; (9) Live auto-follow head (rf2-s0s5x Phase A)
;; -------------------------------------------------------------------------
;;
;; Per the bead's Phase A acceptance: when `:mode :live` and a new
;; cascade arrives, the effective focus auto-advances. Previously
;; `compose-focus` honoured a non-nil stored slot-id even in :live
;; mode (so stepping back to head with `k` left the slot pinned and
;; later head arrivals went unfollowed). The composer now treats
;; LIVE as 'always head' unless paused — and the legacy slot writes
;; are still honoured for paused/retro selections.

(deftest compose-focus-live-with-stored-id-tracks-head
  (testing "rf2-s0s5x Phase A — LIVE always tracks head, even when a
            stored slot-id exists and matches the previous head. The
            stored id might be the previous head pinned by
            focus-step-reducer landing back on head; once a fresher
            cascade arrives, focus auto-advances."
    (let [r (spine/compose-focus {:dispatch-id :c2 :mode :live}
                                 fixture-cascades)]
      (is (= :c3 (:dispatch-id r))
          "LIVE mode → effective id is the current head, not the stored id")
      (is (true? (:head? r))))))

(deftest compose-focus-paused-live-preserves-stored-id
  (testing "rf2-s0s5x Phase A — when paused, LIVE auto-follow is
            suspended so the user can inspect a pinned cascade
            without losing it as new traffic arrives. The composer
            falls back to slot-id behaviour."
    (let [r (spine/compose-focus {:dispatch-id :c1 :mode :live :paused? true}
                                 fixture-cascades)]
      (is (= :c1 (:dispatch-id r))
          "paused LIVE → stored slot-id wins")
      (is (true? (:paused? r))))))

(deftest compose-focus-paused-live-with-evicted-id-snaps-head
  (testing "paused LIVE + the stored id has been evicted from the
            buffer → snap to head (no orphan focus). Matches the
            non-paused eviction fallback."
    (let [r (spine/compose-focus {:dispatch-id :gone :mode :live :paused? true}
                                 fixture-cascades)]
      (is (= :c3 (:dispatch-id r)))
      (is (true? (:head? r))))))

(deftest focus-sub-live-auto-follows-new-head
  (testing "rf2-s0s5x Phase A acceptance — user is in LIVE on c3
            (head); a new cascade c4 arrives; focus auto-advances to
            c4 without the user clicking the LIVE pill."
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    ;; Initial state — LIVE, focused on head c3 (implicit, slot-id nil).
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r)))
      (is (= :live (:mode r))))
    ;; User steps prev then next — lands on c3 again, slot-id is now :c3.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event-prev])
      (rf/dispatch-sync [:rf.xray/focus-event-next]))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r)))
      (is (= :live (:mode r))
          "stepping back to head re-engages LIVE per focus-step-reducer"))
    ;; A new cascade c4 arrives — head shifts.
    (seed-cascades! (conj fixture-cascades (cascade :c4 :rf/default)))
    (let [r (focus-sub)]
      (is (= :c4 (:dispatch-id r))
          "LIVE auto-follows the new head — focus advances to c4")
      (is (true? (:head? r))))))

(deftest focus-sub-retro-does-not-auto-follow
  (testing "in :retro the focus stays pinned even when new cascades
            arrive — the user has explicitly opted out of LIVE."
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event :c1 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c1 (:dispatch-id r)))
      (is (= :retro (:mode r))))
    ;; New cascade arrives. Focus stays on c1.
    (seed-cascades! (conj fixture-cascades (cascade :c4 :rf/default)))
    (let [r (focus-sub)]
      (is (= :c1 (:dispatch-id r))
          ":retro pins the focus through arrivals")
      (is (= :retro (:mode r))))))

(deftest focus-sub-live-auto-follows-epoch-id-rf2-70tkv
  (testing "rf2-70tkv — the reactive :rf.xray/focus sub auto-derives
            :epoch-id to the head cascade's settling epoch in LIVE
            mode. Mike repro: user clicks an L2 row (writes
            :selected-epoch-id + focus :epoch-id), then clicks
            Follow-head. Pre-fix the focus sub left :epoch-id pinned
            to the clicked epoch even after follow-head; every
            epoch-keyed panel (Views, Machines, App-DB diff via
            shim) stayed frozen. Post-fix the sub re-derives
            :epoch-id from head."
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      ;; Seed an epoch-history covering all three cascades.
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]])
      ;; User clicks the earliest cascade — pins focus to :c1/:e1.
      (rf/dispatch-sync [:rf.xray/focus-event :c1 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c1 (:dispatch-id r)))
      (is (= :e1 (:epoch-id r)))
      (is (= :retro (:mode r))))
    ;; User clicks Follow-head — :mode flips to :live; the stored
    ;; :focus slot's :dispatch-id is cleared, but pre-rf2-70tkv the
    ;; legacy :selected-epoch-id stayed on :e1 AND the focus sub's
    ;; :epoch-id stayed on :e1 too. Post-fix the sub re-derives
    ;; from head.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/follow-head]))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r))
          "LIVE → :dispatch-id tracks head (existing behaviour)")
      (is (= :live (:mode r)))
      (is (= :e3 (:epoch-id r))
          "rf2-70tkv — :epoch-id ALSO tracks head, so Views /
           Machines / App-DB-diff rebind to the head cascade's
           settling epoch"))
    ;; A new cascade c4 arrives with epoch e4 — focus sub auto-
    ;; advances both axes.
    (seed-cascades! (conj fixture-cascades (cascade :c4 :rf/default)))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [(epoch :e1 :c1) (epoch :e2 :c2)
                          (epoch :e3 :c3) (epoch :e4 :c4)]]))
    (let [r (focus-sub)]
      (is (= :c4 (:dispatch-id r)))
      (is (= :e4 (:epoch-id r))
          "rf2-70tkv — auto-track keeps :epoch-id and :dispatch-id
           in lockstep on every head arrival"))))

(deftest focus-sub-paused-live-does-not-auto-follow
  (testing "paused LIVE freezes auto-follow — user paused to inspect,
            so new arrivals don't pull focus away."
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      ;; Get into :live mode with slot-id pinned on c2 (paused inspection
      ;; of a non-head cascade — possible via focus-step then pause).
      (rf/dispatch-sync [:rf.xray/focus-event-prev])
      (rf/dispatch-sync [:rf.xray/focus-event-next]))
    ;; Now slot-id = :c3, mode = :live. Pause.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-live-pause]))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r)))
      (is (true? (:paused? r))))
    ;; New cascade arrives. Paused LIVE stays put.
    (seed-cascades! (conj fixture-cascades (cascade :c4 :rf/default)))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r))
          "paused LIVE pins focus through arrivals")
      (is (true? (:paused? r))))))

(deftest preview-cascade-handler-does-not-persist-cross-frame-rekey-rf2-uo0rc5
  (testing "rf2-uo0rc.5 — the :rf.xray/preview-cascade HANDLER must not
            persist a cross-frame :target-frame / :epoch-history re-key
            from a transient hover. Pre-fix it called
            reseed-epoch-history-for-frame, which re-keys those slots onto
            the previewed cascade's frame; on preview-clear (frame nil)
            the reseed was a no-op, so the re-key was never reverted —
            after hovering a cross-frame row and leaving, the committed
            focus resolved its epoch against the WRONG ring."
    (setup-xray-frame!)
    ;; Cascades span two frames; the head (:c3) is in :rf/default. :c2
    ;; lives in a DIFFERENT frame (:cart-frame).
    (seed-cascades! [(cascade :c1 :rf/default)
                     (cascade :c2 :cart-frame)
                     (cascade :c3 :rf/default)])
    ;; Establish a committed cross-frame axis: :target-frame :rf/default
    ;; with a committed epoch-history ring.
    (let [committed-history [{:epoch-id :e-committed :dispatch-id :c3}]]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/sync-epoch-history committed-history]))
      (let [before (rf/app-db-value :rf/xray)]
        ;; Hover the cross-frame :c2 row, then leave (preview-clear).
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/preview-cascade :c2]))
        (let [during (rf/app-db-value :rf/xray)]
          (testing "the preview did NOT re-key the committed cross-frame axis"
            (is (= (:target-frame before) (:target-frame during))
                ":target-frame is untouched by the transient hover")
            (is (= (:epoch-history before) (:epoch-history during))
                ":epoch-history is untouched by the transient hover")))
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/preview-cascade nil]))
        (let [after (rf/app-db-value :rf/xray)]
          (testing "after hover-then-leave the committed axis is intact"
            (is (= (:target-frame before) (:target-frame after))
                ":target-frame survives the full preview gesture")
            (is (= committed-history (:epoch-history after))
                ":epoch-history still holds the committed ring — the
                 committed focus now resolves against the RIGHT ring")))))))

;; -------------------------------------------------------------------------
;; (10) :epoch-id resolution — rf2-ak3ty
;;
;; Spec/018 §6 Spine events says `:rf.xray/focus-event <id>` MUST
;; "compute `:epoch-id` from cascades". The reducer takes a resolved
;; epoch-id (the event handler in `install!` resolves it from the
;; Xray `:epoch-history` slot via `epoch-id-for-cascade`), then
;; stamps the `:focus :epoch-id` slot — the single source of truth the
;; App-db diff panel reads via :rf.xray/focus-epoch-id (rf2-uy7nz).
;; -------------------------------------------------------------------------

(defn- epoch
  "Build a minimal `:rf/epoch-record` carrying the slots
  `epoch-id-for-cascade` looks at — a `:trace-events` vector with one
  dispatch-id-tagged event, plus the literal `:dispatch-id` slot the
  test fallback honours."
  [epoch-id dispatch-id]
  {:epoch-id     epoch-id
   :dispatch-id  dispatch-id
   :trace-events [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
                   :tags {:rf.trace/dispatch-id dispatch-id}}]})

(deftest epoch-id-for-cascade-walks-trace-events
  (testing "resolves :epoch-id from an epoch's :trace-events :dispatch-id tag"
    (let [history [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]]
      (is (= :e2 (spine/epoch-id-for-cascade history :c2)))
      (is (= :e3 (spine/epoch-id-for-cascade history :c3))))))

(deftest epoch-id-for-cascade-falls-back-to-literal-dispatch-id
  (testing "synthetic test epochs without :trace-events resolve via
            literal :dispatch-id slot"
    (let [history [{:epoch-id :e1 :dispatch-id 7}
                   {:epoch-id :e2 :dispatch-id 8}]]
      (is (= :e2 (spine/epoch-id-for-cascade history 8))))))

(deftest epoch-id-for-cascade-missing-returns-nil
  (testing "no matching epoch → nil (cascade evicted from ring buffer,
            or mid-build before its epoch record commits)"
    (is (nil? (spine/epoch-id-for-cascade [] :c1)))
    (is (nil? (spine/epoch-id-for-cascade [(epoch :e1 :c1)] :c-gone)))
    (is (nil? (spine/epoch-id-for-cascade nil :c1)))
    (is (nil? (spine/epoch-id-for-cascade [(epoch :e1 :c1)] nil)))))

;; ---- rf2-5qp4g — dispatch-id-for-epoch reverse lookup -------------------
;;
;; Reverse of `epoch-id-for-cascade`. Drives the `:rf.xray/focus-epoch`
;; event handler — the Epoch panel's DISPATCH step's parent-epoch
;; navigation chip clicks land with an epoch-id and need to drive the
;; spine's `focus-cascade-reducer`, which keys on dispatch-id.

(deftest dispatch-id-for-epoch-resolves-via-trace-events
  (testing "rf2-5qp4g — resolves :dispatch-id from an epoch's
            :trace-events :dispatch-id tag (same walker as
            common/dispatch-id-of-epoch)"
    (let [history [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]]
      (is (= :c2 (spine/dispatch-id-for-epoch history :e2)))
      (is (= :c3 (spine/dispatch-id-for-epoch history :e3))))))

(deftest dispatch-id-for-epoch-falls-back-to-literal-dispatch-id
  (testing "rf2-5qp4g — synthetic test epochs that lack :trace-events
            still resolve via the literal :dispatch-id slot"
    (let [history [{:epoch-id :e1 :dispatch-id 7}
                   {:epoch-id :e2 :dispatch-id 8}]]
      (is (= 8 (spine/dispatch-id-for-epoch history :e2))))))

(deftest dispatch-id-for-epoch-missing-returns-nil
  (testing "rf2-5qp4g — no matching epoch → nil (epoch evicted,
            wrong-frame, or never landed in this buffer)"
    (is (nil? (spine/dispatch-id-for-epoch [] :e1)))
    (is (nil? (spine/dispatch-id-for-epoch [(epoch :e1 :c1)] :e-gone)))
    (is (nil? (spine/dispatch-id-for-epoch nil :e1)))
    (is (nil? (spine/dispatch-id-for-epoch [(epoch :e1 :c1)] nil)))))

;; ---- compose-focus :epoch-id auto-follow (rf2-70tkv) --------------------
;;
;; Mike repro 2026-05-19 watching parallel-frames @ localhost:8030:
;;   1. Click '+' in host app  →  new cascade lands
;;   2. L2 list shows the new event  ✓
;;   3. focus :mode is :live (the LIVE pill); :dispatch-id auto-tracks
;;      head via rf2-s0s5x Phase A  ✓
;;   4. BUT App-db Diff content does NOT update  ✗
;;
;; Root cause: compose-focus was hardcoded to return `(:epoch-id focus)`
;; from the stored slot regardless of mode. So once an earlier event
;; wrote :focus :epoch-id (any click on an L2 row, scrubbing in Time
;; Travel, the :rf.xray/select-epoch chip in the diff), every panel
;; pivoting on focus :epoch-id (Views' focused-cascade-pair, Machine
;; Inspector's focused-event lens, App-DB Diff via [:focus :epoch-id])
;; stayed wired to the stale epoch while :dispatch-id correctly tracked
;; head.
;;
;; Fix: a 4-arity `compose-focus` accepts `epoch-history` and re-
;; derives :epoch-id from the head cascade in LIVE+unpaused mode,
;; mirroring how :dispatch-id auto-tracks head. The 3-arity (test
;; rigs, back-compat callers) preserves the original passthrough
;; behaviour. The reactive `:rf.xray/focus` sub in `install!` uses
;; the 4-arity so every panel auto-tracks `:epoch-id` for free.

(deftest compose-focus-3-arity-preserves-stored-epoch-id
  (testing "rf2-70tkv — the 3-arity (no epoch-history input) preserves
            the original behaviour for back-compat callers + test rigs"
    (let [r (spine/compose-focus {:dispatch-id :c2 :mode :retro
                                  :epoch-id :e-stale}
                                 fixture-cascades
                                 false)]
      (is (= :e-stale (:epoch-id r))
          "no epoch-history supplied → stored :epoch-id passes through"))))

(deftest compose-focus-4-arity-live-auto-derives-epoch-id-from-head
  (testing "rf2-70tkv — in LIVE+unpaused mode the 4-arity re-derives
            :epoch-id to the head cascade's settling epoch. Bug repro
            in pure-data form: a stored :focus :epoch-id of :e1 from a
            prior L2 click must NOT survive a LIVE switch + head
            arrival."
    (let [history [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]
          r       (spine/compose-focus {:dispatch-id nil :mode :live
                                        :epoch-id :e1}
                                       fixture-cascades false history)]
      (is (= :c3 (:dispatch-id r))
          ":dispatch-id tracks head (existing rf2-s0s5x Phase A)")
      (is (= :e3 (:epoch-id r))
          ":epoch-id ALSO tracks head — the stale stored :e1 is
           overwritten so panels rebind on every new arrival"))))

(deftest compose-focus-4-arity-live-paused-honours-stored-epoch-id
  (testing "rf2-70tkv — LIVE-paused is the explicit 'freeze inspection'
            gesture: the stored :epoch-id rides through untouched
            (matching how :dispatch-id stays on the stored slot when
            paused)"
    (let [history [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]
          r       (spine/compose-focus {:dispatch-id :c1 :mode :live
                                        :epoch-id :e1 :paused? true}
                                       fixture-cascades false history)]
      (is (= :c1 (:dispatch-id r))
          "paused → :dispatch-id pins to stored slot")
      (is (= :e1 (:epoch-id r))
          "paused → :epoch-id pins to stored slot"))))

(deftest compose-focus-4-arity-retro-honours-stored-epoch-id
  (testing "rf2-70tkv — RETRO mode pins the cascade and its settling
            epoch in lockstep, so the stored :epoch-id is the
            authoritative value (the focus-cascade-reducer / focus-step-
            reducer already write it consistently with :dispatch-id)"
    (let [history [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]
          r       (spine/compose-focus {:dispatch-id :c1 :mode :retro
                                        :epoch-id :e1}
                                       fixture-cascades false history)]
      (is (= :c1 (:dispatch-id r)))
      (is (= :e1 (:epoch-id r))
          "retro pin honoured — picker / scrubber wrote both axes
           together"))))

(deftest compose-focus-4-arity-live-head-with-no-epoch-match-returns-nil
  (testing "rf2-70tkv — multi-frame edge case. The picker's frame
            (and thus the per-frame :epoch-history slot keyed off
            :target-frame) can disagree with the head cascade's
            frame in mid-transition states; the head's settling
            epoch might also have been evicted from the ring buffer.
            When the head's epoch is NOT in epoch-history we return
            nil rather than the stored slot.

            Every panel uniformly treats nil as 'no pin, use head
            fallback' (App-DB Diff's `(peek history)`, Views'
            `(dec (count history))`, Machine Inspector's `(peek
            history)`). Keeping a stale stored id here would
            resurrect the very freeze the auto-track was meant to
            eliminate."
    (let [history [(epoch :e1 :c1) (epoch :e2 :c2)]
          r       (spine/compose-focus {:dispatch-id nil :mode :live
                                        :epoch-id :e1}
                                       fixture-cascades false history)]
      (is (= :c3 (:dispatch-id r))
          "head cascade is :c3")
      (is (nil? (:epoch-id r))
          ":e3 is not in history → nil (NOT the stale :e1)"))))

(deftest compose-focus-4-arity-live-empty-history-returns-nil
  (testing "rf2-70tkv — empty epoch-history (cold start; buffer-cleared)
            in LIVE mode → :epoch-id nil so the App-DB Diff's
            `(peek history)` fallback path resolves correctly."
    (let [r (spine/compose-focus {:dispatch-id nil :mode :live
                                  :epoch-id :e-stale}
                                 fixture-cascades false [])]
      (is (nil? (:epoch-id r))
          "empty history → no resolution possible → nil overrides
           any stale stored id"))))

(deftest focus-cascade-reducer-4-arg-writes-epoch-id
  (testing "the 4-arg reducer writes :epoch-id into the :focus slot —
            App-db pivots from L2-list clicks via :rf.xray/focus-epoch-id
            once this lands (rf2-ak3ty); rf2-uy7nz: no mirror slot"
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default :e2)]
      (is (= :e2 (get-in r [:focus :epoch-id])))
      (is (not (contains? r :selected-epoch-id))
          "no :selected-epoch-id mirror slot is written")
      (is (= :c2 (get-in r [:focus :dispatch-id]))))))

(deftest focus-cascade-reducer-3-arg-leaves-epoch-id-nil
  (testing "the back-compat 3-arg reducer leaves :epoch-id nil — the
            focus sub still rebinds on :dispatch-id, but epoch-keyed
            surfaces won't pivot until a 4-arg call lands"
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default)]
      (is (nil? (get-in r [:focus :epoch-id])))
      (is (not (contains? r :selected-epoch-id))))))

(deftest focus-cascade-event-resolves-epoch-id-from-history
  (testing "the registered :rf.xray/focus-event event resolves
            :epoch-id from the Xray app-db's :epoch-history slot —
            end-to-end the spine focus now carries both ids in
            lockstep (rf2-ak3ty)"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]])
      (rf/dispatch-sync [:rf.xray/focus-event :c2 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c2 (:dispatch-id r)))
      (is (= :e2 (:epoch-id r))
          "spine sub carries the resolved epoch-id — the
           load-bearing prereq for rf2-w15el (Views) and the
           App-db pivot fix"))))

(deftest focus-cascade-event-pivots-focus-epoch-id
  (testing "App-db's :rf.xray/focus-epoch-id sub pivots on L2-list
            click — proves the spine writes the focused epoch into
            `[:focus :epoch-id]` so App-db rebinds from a row click
            without any per-panel change (rf2-uy7nz: single source of
            truth, no mirror slot)"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]])
      (rf/dispatch-sync [:rf.xray/focus-event :c1 :rf/default]))
    (let [focus-epoch (rf/with-frame :rf/xray
                        @(rf/subscribe [:rf.xray/focus-epoch-id]))]
      (is (= :e1 focus-epoch)
          "App-db's :rf.xray/focus-epoch-id sub rebinds to the focused
           cascade's epoch — pivot now works from L2 list clicks"))))

(deftest focus-step-reducer-4-arg-writes-epoch-id
  (testing "step events resolve :epoch-id for the new dispatch-id into
            the spine `[:focus :epoch-id]` slot (rf2-uy7nz: no mirror)"
    (let [db       {:focus {:dispatch-id :c3 :mode :live}}
          history  [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]
          r        (spine/focus-step-reducer db fixture-cascades history -1)]
      (is (= :c2 (get-in r [:focus :dispatch-id])))
      (is (= :e2 (get-in r [:focus :epoch-id])))
      (is (not (contains? r :selected-epoch-id))))))

(deftest follow-head-reducer-clears-focus-epoch-id
  (testing "follow-head clears the focus epoch in lockstep with the
            dispatch-id slot — the App-db panel returns to its landing
            view (rf2-uy7nz: single source of truth at [:focus :epoch-id])"
    (let [db {:focus {:dispatch-id :c1 :epoch-id :e1 :mode :retro}}
          r  (spine/follow-head-reducer db)]
      (is (nil? (get-in r [:focus :epoch-id])))
      (is (not (contains? r :selected-epoch-id))))))

;; -------------------------------------------------------------------------
;; (11) rf2-fzbrw — no aggregate-all-events state
;;
;; The spine's invariant is 'one focused event at a time'. Two specific
;; reachable states would otherwise break that invariant:
;;
;;   (i)  Stepping prev from the first event lands focus on the
;;        `:ungrouped` bucket (registry-time emits / lifecycle / REPL
;;        evals) — the L4 panels reading the focused cascade then have
;;        no event to render and degrade into an aggregate look.
;;   (ii) The composed `:rf.xray/focus` sub returns
;;        `:dispatch-id nil` while the buffer carries real events —
;;        same downstream effect.
;;
;; The fix has three layers; this section covers the spine ones (B
;; and C in the bead's fix sketch).
;; -------------------------------------------------------------------------

(def ^:private fixture-cascades-with-ungrouped
  "Two real cascades + the `:ungrouped` bucket the projection emits
  for events that carry no `:dispatch-id` tag. The bucket sorts to
  the head by `first-id`'s `MAX_SAFE_INTEGER` sentinel; for the spine
  walk we treat the bucket as not present, so the walkable head is
  still the latest real cascade (`:c2` here)."
  [(cascade :c1 :rf/default)
   (cascade :c2 :rf/default)
   (cascade :ungrouped nil)])

(deftest focusable-cascades-drops-ungrouped-bucket
  (testing "rf2-fzbrw — :ungrouped is filtered out of the spine walk
            (it carries no event vector → not a valid focus target)"
    (is (= 2 (count (spine/focusable-cascades
                      fixture-cascades-with-ungrouped))))
    (is (every? #(not= :ungrouped (:dispatch-id %))
                (spine/focusable-cascades
                  fixture-cascades-with-ungrouped)))
    (is (= fixture-cascades
           (spine/focusable-cascades fixture-cascades))
        "no :ungrouped → no-op")))

;; -------------------------------------------------------------------------
;; rf2-r9lyy — :ungrouped opt-in surface (Option B)
;;
;; Mike 2026-05-19 closure of rf2-q60yf: the `:settings/show-ungrouped?`
;; knob flips the bucket from "always stripped" to "user-revealed". The
;; spine respects the opt-in: when on, the bucket is a valid focusable
;; target so clicking the L2 row pins it; when off (default), the
;; existing strict behaviour applies and pinning to :ungrouped snaps to
;; head.
;; -------------------------------------------------------------------------

(deftest focusable-cascades-include-ungrouped-when-opt-in
  (testing "rf2-r9lyy — `show-ungrouped? true` keeps the bucket so the
            user can focus it"
    (is (= 3 (count (spine/focusable-cascades
                      fixture-cascades-with-ungrouped true))))
    (is (some #(= :ungrouped (:dispatch-id %))
              (spine/focusable-cascades
                fixture-cascades-with-ungrouped true)))
    (is (= 2 (count (spine/focusable-cascades
                      fixture-cascades-with-ungrouped false)))
        "show-ungrouped? false preserves the strict default")))

(deftest focusable-head-id-includes-ungrouped-when-opt-in
  (testing "rf2-r9lyy — when `show-ungrouped?` is on the head walk
            considers :ungrouped as a possible head (the bucket sorts
            last per the first-id sentinel)"
    (is (= :ungrouped
           (spine/focusable-head-id
             fixture-cascades-with-ungrouped true)))
    (is (= :c2
           (spine/focusable-head-id
             fixture-cascades-with-ungrouped false))
        "show-ungrouped? false preserves the strict default")))

(deftest compose-focus-pins-ungrouped-when-opt-in
  (testing "rf2-r9lyy — `show-ungrouped? true` lets a stored
            `:dispatch-id :ungrouped` stick (effective id is
            :ungrouped, not the head)"
    (let [r (spine/compose-focus {:dispatch-id :ungrouped :mode :retro}
                                 fixture-cascades-with-ungrouped
                                 true)]
      (is (= :ungrouped (:dispatch-id r))
          "stored :ungrouped is pinnable under opt-in"))))

(deftest compose-focus-defaults-to-strict-when-opt-in-off
  (testing "rf2-r9lyy — `show-ungrouped? false` (default) preserves
            the rf2-fzbrw contract: a stored :ungrouped snaps to head"
    (let [r (spine/compose-focus {:dispatch-id :ungrouped :mode :retro}
                                 fixture-cascades-with-ungrouped
                                 false)]
      (is (= :c2 (:dispatch-id r))
          "show-ungrouped? off → snap to head"))
    (let [r (spine/compose-focus {:dispatch-id :ungrouped :mode :retro}
                                 fixture-cascades-with-ungrouped)]
      (is (= :c2 (:dispatch-id r))
          "2-arity (legacy) preserves the strict default"))))

(deftest focus-step-reducer-walks-into-ungrouped-when-opt-in
  (testing "rf2-r9lyy — when `show-ungrouped?` is on, stepping forward
            from the last real cascade lands on :ungrouped (the bucket
            sorts at the head per the first-id sentinel)"
    (let [db {:focus {:dispatch-id :c2 :mode :retro}}
          r  (spine/focus-step-reducer db
                                       fixture-cascades-with-ungrouped
                                       []
                                       +1
                                       true)]
      (is (= :ungrouped (get-in r [:focus :dispatch-id]))
          "show-ungrouped? on → step forward into the bucket"))))

(deftest focus-step-reducer-skips-ungrouped-when-opt-in-off
  (testing "rf2-r9lyy — when `show-ungrouped?` is off (default), the
            step walk never lands on :ungrouped (rf2-fzbrw default)"
    (let [db {:focus {:dispatch-id :c2 :mode :retro}}
          r  (spine/focus-step-reducer db
                                       fixture-cascades-with-ungrouped
                                       []
                                       +1
                                       false)]
      (is (= db r)
          "show-ungrouped? off → boundary no-op at the last real cascade"))))

(deftest focus-step-reducer-prev-on-first-event-is-no-op
  (testing "rf2-fzbrw — [<] on the first (oldest) real event returns db
            unchanged. The boundary is a true no-op so focus cannot
            shuffle into the :ungrouped bucket or any other aggregate
            state."
    (let [db {:focus {:dispatch-id :c1 :mode :retro}}
          r  (spine/focus-step-reducer db
                                       fixture-cascades-with-ungrouped
                                       -1)]
      (is (= db r) "db unchanged — boundary no-op"))))

(deftest focus-step-reducer-next-on-head-is-no-op
  (testing "rf2-fzbrw — [>] on the head (newest real event) returns db
            unchanged. Symmetric counterpart of the [<] boundary."
    (let [db {:focus {:dispatch-id :c2 :mode :live}}
          r  (spine/focus-step-reducer db
                                       fixture-cascades-with-ungrouped
                                       +1)]
      (is (= db r) "db unchanged at head"))))

(deftest focus-step-reducer-prev-from-real-skips-ungrouped
  (testing "rf2-fzbrw — stepping prev from the only real-event focus
            never lands on :ungrouped. With a single real cascade plus
            the bucket, [<] at the only real event is a no-op."
    (let [cascades [(cascade :ungrouped nil)
                    (cascade :only-real :rf/default)]
          db       {:focus {:dispatch-id :only-real :mode :live}}
          r        (spine/focus-step-reducer db cascades -1)]
      (is (= db r)
          "single real event → [<] is a no-op, not a slide into :ungrouped"))))

(deftest focus-step-reducer-empty-focusable-buffer-is-no-op
  (testing "buffer carries only the :ungrouped bucket (no real events
            yet) → stepping is a no-op; the reducer cannot manufacture
            a focusable cascade out of nothing."
    (let [cascades [(cascade :ungrouped nil)]
          db       {}
          r        (spine/focus-step-reducer db cascades -1)]
      (is (= db r)))))

(deftest focus-step-reducer-respects-picker-frame
  (testing "rf2-oziyr — when the picker has scoped to one frame, [◀]
            and [▶] walk only that frame's cascades. Cross-frame
            cascades are invisible to the nav so the user can't
            accidentally step into a frame they're not inspecting."
    (let [cascades [(cascade :c1 :cart-frame)
                    (cascade :c2 :checkout-frame)
                    (cascade :c3 :cart-frame)
                    (cascade :c4 :checkout-frame)]
          db       {:focus {:frame :cart-frame
                            :dispatch-id :c3
                            :mode :live}}
          r        (spine/focus-step-reducer db cascades -1)]
      (is (= :c1 (get-in r [:focus :dispatch-id]))
          "prev from :c3 (cart head) skips :c2 (checkout) and lands on :c1
           (the only other cart cascade)"))))

(deftest focus-step-reducer-prev-from-live-head-is-frame-strict-rf2-xj3kbn
  (testing "rf2-xj3kbn — prev from LIVE head, no picker frame stored, when
            the HEAD dispatch-id is ALSO present in an earlier frame. The
            composer resolves the head as `[:cx :frame/b]`; the step
            current-position lookup must key off `[frame dispatch-id]` so
            prev lands on the immediate previous ROW (in the head's frame),
            not be swallowed as a false boundary no-op off the earlier
            `:frame/a` same-id cascade."
    ;; LIVE list: [:cx@a, :mid@b, :cx@b(head)] — :cx duplicated across
    ;; frames. slot-frame nil (no picker), :focus empty → composer derives
    ;; the head :cx @ :frame/b.
    (let [cascades [(cascade :cx :frame/a)
                    (cascade :mid :frame/b)
                    (cascade :cx :frame/b)]
          db       {:focus {:mode :live}}
          r        (spine/focus-step-reducer db cascades -1)]
      (is (not= db r)
          "prev is a REAL step, not a no-op — the pre-fix id-only lookup
           found :cx @ :frame/a (idx 0) and clamped prev to a no-op")
      (is (= :mid (get-in r [:focus :dispatch-id]))
          "prev from the :frame/b :cx head lands on the immediately
           previous row, :mid")
      (is (= :frame/b (get-in r [:focus :frame]))
          "the stepped row's frame is :frame/b — frame + id stay in
           lockstep with the row actually stepped to")
      (is (= :retro (get-in r [:focus :mode]))
          "stepping off head pins RETRO"))))

(deftest focus-step-reducer-frame-strict-step-resolves-epoch-rf2-xj3kbn
  (testing "rf2-xj3kbn — the frame-strict prev resolves the stepped row's
            settling :epoch-id (the downstream panels pivot on it). The
            cross-frame same-id collision must not leak the wrong frame's
            epoch into the focus."
    (let [cascades [(cascade :cx :frame/a)
                    (cascade :mid :frame/b)
                    (cascade :cx :frame/b)]
          ;; epoch ring keyed to :mid's settling epoch (the row prev lands on)
          history  [{:epoch-id 42 :dispatch-id :mid}]
          db       {:focus {:mode :live}}
          r        (spine/focus-step-reducer db cascades history -1)]
      (is (= :mid (get-in r [:focus :dispatch-id])))
      (is (= 42 (get-in r [:focus :epoch-id]))
          "the stepped :mid row's settling epoch resolves into focus"))))

(deftest compose-focus-snaps-to-head-when-slot-id-is-ungrouped
  (testing "rf2-fzbrw — even in :retro, a stored slot pointing at
            :ungrouped (or a no-longer-present cascade) snaps to head.
            The spine MUST NOT surface nil :dispatch-id while focusable
            cascades exist."
    (let [r (spine/compose-focus {:dispatch-id :ungrouped :mode :retro}
                                 fixture-cascades-with-ungrouped)]
      (is (= :c2 (:dispatch-id r))
          "stored :ungrouped → effective head (:c2)")
      (is (true? (:head? r))))))

(deftest compose-focus-snaps-to-head-when-slot-id-nil-in-retro
  (testing "rf2-fzbrw — even in :retro mode, nil slot-id with a
            non-empty focusable buffer snaps to head. The unreachable
            state 'buffer non-empty + focus nil' is structurally
            blocked at the compose layer."
    (let [r (spine/compose-focus {:dispatch-id nil :mode :retro}
                                 fixture-cascades)]
      (is (= :c3 (:dispatch-id r))
          "nil slot-id + non-empty buffer + :retro → snap to head")
      (is (true? (:head? r))))))

;; -------------------------------------------------------------------------
;; (12) Click on head stays LIVE (rf2-xzzih)
;;
;; Phase A (rf2-s0s5x / PR #1452) made any L2 click flip mode → :retro.
;; But clicking the latest event (head) shouldn't pin the user to RETRO —
;; they're still at head, so new arrivals should continue to auto-advance.
;; The fix: the click-handler reducer is now head-aware. Mike's quote:
;; "If I'm on the last event (the most recent one) and another event
;; comes in, I should change to that new event. Ie. I'm live."
;; -------------------------------------------------------------------------

(deftest focusable-head-id-skips-ungrouped
  (testing "focusable-head-id returns the latest focusable cascade —
            not the literal last entry if that's the :ungrouped bucket"
    (is (= :c3 (spine/focusable-head-id fixture-cascades)))
    (is (= :c2 (spine/focusable-head-id
                 fixture-cascades-with-ungrouped))
        ":ungrouped sorts last (first-id sentinel) but focusable head
         is the latest real cascade — :c2 not :ungrouped")
    (is (nil? (spine/focusable-head-id [])))
    (is (nil? (spine/focusable-head-id
                [{:dispatch-id :ungrouped :frame nil}])))))

(deftest focus-cascade-reducer-5-arg-head-stays-live
  (testing "rf2-xzzih — when dispatch-id == head-id the reducer picks
            :live so subsequent arrivals auto-advance"
    (let [r (spine/focus-cascade-reducer {} :c3 :rf/default :e3 :c3)]
      (is (= :c3 (get-in r [:focus :dispatch-id])))
      (is (= :live (get-in r [:focus :mode]))
          "head click → :live (auto-follow continues)")
      (is (= :e3 (get-in r [:focus :epoch-id]))))))

(deftest focus-cascade-reducer-5-arg-non-head-pins-retro
  (testing "rf2-xzzih — clicking a non-head cascade still pins to :retro"
    (let [r (spine/focus-cascade-reducer {} :c1 :rf/default :e1 :c3)]
      (is (= :c1 (get-in r [:focus :dispatch-id])))
      (is (= :retro (get-in r [:focus :mode]))
          "non-head click → :retro (auto-follow suspended)"))))

(deftest focus-cascade-reducer-nil-head-id-defaults-retro
  (testing "rf2-xzzih — the 4-arg back-compat path (no head-id supplied)
            preserves the pre-fix :retro default. Keeps existing
            callers' contract intact."
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default :e2 nil)]
      (is (= :retro (get-in r [:focus :mode]))))
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default :e2)]
      (is (= :retro (get-in r [:focus :mode]))
          "4-arg arity defaults to retro"))
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default)]
      (is (= :retro (get-in r [:focus :mode]))
          "3-arg arity defaults to retro"))))

(deftest focus-cascade-event-clicking-head-stays-live
  (testing "rf2-xzzih end-to-end — dispatching :rf.xray/focus-event
            on the head cascade keeps spine in :live so a subsequent
            arrival auto-advances the focus"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event :c3 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r)))
      (is (= :live (:mode r))
          "head click → mode stays :live")
      (is (true? (:head? r))))
    ;; A new cascade arrives — focus must auto-advance to the new head.
    (seed-cascades! (conj fixture-cascades (cascade :c4 :rf/default)))
    (let [r (focus-sub)]
      (is (= :c4 (:dispatch-id r))
          "new arrival auto-advances because mode stayed :live")
      (is (= :live (:mode r)))
      (is (true? (:head? r))))))

(deftest focus-cascade-event-clicking-non-head-pins-retro
  (testing "rf2-xzzih — clicking a non-head cascade pins :retro and
            blocks auto-advance through subsequent arrivals"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event :c1 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c1 (:dispatch-id r)))
      (is (= :retro (:mode r)) "non-head click → :retro"))
    ;; New cascade arrives — :retro must NOT auto-advance.
    (seed-cascades! (conj fixture-cascades (cascade :c4 :rf/default)))
    (let [r (focus-sub)]
      (is (= :c1 (:dispatch-id r))
          ":retro pins focus through arrivals")
      (is (= :retro (:mode r))))))

(deftest legacy-select-dispatch-id-clicking-head-stays-live
  (testing "rf2-xzzih — the legacy `:rf.xray/select-dispatch-id`
            event (the spine-shim entry used by machine / cancellation
            panels) shares the head-aware mode pick so clicks from
            those panels onto the head cascade also keep :live"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id :c3 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r)))
      (is (= :live (:mode r))
          "legacy click on head → :live (parity with focus-cascade event)"))))

;; -------------------------------------------------------------------------
;; (12) rf2-q8hvw — cross-frame L2-row click re-seeds :epoch-history
;;
;; Multi-frame app, picker UNTOUCHED. The L2 list spans every frame (the
;; cascades sub is whole-buffer; frame-scoping only kicks in once the
;; picker is touched). The `:epoch-history` slot is seeded at boot from
;; the HEAD frame and re-keyed only by the frame PICKER + the
;; `:rf.xray/epoch-recorded` listener (which appends only when the
;; recording frame IS the current `:target-frame`). So clicking an L2
;; row for a cascade that settled in a NON-head frame used to resolve
;; its settling epoch against the wrong frame's ring → epoch-id nil →
;; the App-DB diff / Views / Machine Inspector render empty/stale for a
;; cascade that genuinely settled an epoch.
;;
;; This section drives the ACTUAL failing path (not a single-frame
;; green test): it seeds two frames' framework rings, leaves the picker
;; untouched (slot keyed on the boot head frame), then dispatches the
;; real `:rf.xray/focus-event` event for a non-head-frame row and
;; asserts the clicked cascade's epoch resolves end-to-end — through the
;; spine focus sub, the App-DB-diff `:rf.xray/focus-epoch-id` projection,
;; and the `find-epoch-record` lookup the App-DB diff panel runs against
;; the re-seeded `:epoch-history` slot.
;; -------------------------------------------------------------------------

(defn- seed-framework-epoch-ring!
  "Append epoch records into the framework's per-frame ring (the same
  atom `rf/epoch-history` reads). Each record carries the literal
  `:dispatch-id` slot `common/dispatch-id-of-epoch` reads, so the
  spine's epoch-id ⇄ dispatch-id correlation resolves."
  [frame-id records]
  (doseq [{:keys [epoch-id dispatch-id]} records]
    (epoch-state/record! {:frame       frame-id
                          :epoch-id    epoch-id
                          :dispatch-id dispatch-id})))

(def ^:private multi-frame-cascades
  "Head frame `:rf/default` (c3 is the global head); a second frame
  `:cart-frame` carries its own cascade. Whole-buffer, oldest-first —
  this is what the L2 list shows with the picker untouched."
  [(cascade :c1 :rf/default)
   (cascade :c2 :rf/default)
   (cascade :cart-c9 :cart-frame)
   (cascade :c3 :rf/default)])

(defn- mount-multi-frame-picker-untouched! []
  ;; Fresh framework rings — the runtime-reset fixture does NOT clear
  ;; the epoch histories, so wipe them so this test owns the slot state.
  (epoch-api/clear-history!)
  (setup-xray-frame!)
  ;; Populate BOTH frames' framework rings.
  (seed-framework-epoch-ring! :rf/default  [{:epoch-id :e1 :dispatch-id :c1}
                                            {:epoch-id :e2 :dispatch-id :c2}
                                            {:epoch-id :e3 :dispatch-id :c3}])
  (seed-framework-epoch-ring! :cart-frame  [{:epoch-id :cart-e9 :dispatch-id :cart-c9}])
  ;; Whole-buffer cascade list (picker untouched).
  (seed-cascades! multi-frame-cascades)
  ;; Boot seeding: `:target-frame` + `:epoch-history` keyed on the head
  ;; frame, exactly as `mount.cljs`'s first-mount hook does via
  ;; `:rf.xray/set-target-frame`. The picker is NEVER touched after this.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default])))

(deftest focus-cascade-cross-frame-click-reseeds-epoch-history-rf2-q8hvw
  (testing "rf2-q8hvw — clicking an L2 row for a NON-head-frame cascade
            (picker untouched) re-seeds :epoch-history onto that frame
            and resolves the clicked cascade's settling epoch (not nil)"
    (mount-multi-frame-picker-untouched!)
    ;; Pre-condition guard: with the picker untouched the slot holds the
    ;; HEAD frame's ring, and the cart cascade's epoch is absent from it.
    ;; This is the state in which the pre-fix resolution returned nil.
    (let [boot-history (rf/with-frame :rf/xray
                         @(rf/subscribe [:rf.xray/epoch-history]))]
      (is (= :rf/default (rf/with-frame :rf/xray
                           @(rf/subscribe [:rf.xray/target-frame])))
          "boot :target-frame is the head frame (picker untouched)")
      (is (nil? (spine/epoch-id-for-cascade boot-history :cart-c9))
          "RED baseline: cart cascade's epoch is NOT in the boot
           head-frame ring — resolving against the un-reseeded slot
           yields nil (the bug)"))
    ;; The actual failing path: dispatch the real L2-row-click event for
    ;; the non-head-frame cascade.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event :cart-c9 :cart-frame]))
    ;; GREEN: the spine focus now carries the clicked cascade's epoch.
    (let [r (focus-sub)]
      (is (= :cart-c9 (:dispatch-id r)))
      (is (= :cart-e9 (:epoch-id r))
          "the clicked cross-frame cascade's settling epoch resolves —
           the spine focus sub carries it (was nil pre-fix)"))
    ;; The slot itself is now re-keyed onto the clicked cascade's frame.
    (let [target  (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/target-frame]))
          history (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/epoch-history]))]
      (is (= :cart-frame target)
          ":target-frame re-keyed onto the clicked cascade's frame")
      (is (= [:cart-e9] (mapv :epoch-id history))
          ":epoch-history is the clicked frame's ring contents"))
    ;; End-to-end App-DB-diff facing surfaces resolve the clicked epoch.
    (let [selected (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/focus-epoch-id]))
          history  (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/epoch-history]))]
      (is (= :cart-e9 selected)
          "App-DB diff's :rf.xray/focus-epoch-id projection pivots to the
           clicked cascade's epoch")
      (is (= :cart-e9 (:epoch-id (focus-resolver/find-epoch-record selected history)))
          "the App-DB diff's find-epoch-record lookup finds the clicked
           epoch's record in the re-seeded slot — the panel renders the
           right epoch's diff rather than an empty/stale state"))))

(deftest focus-cascade-same-frame-click-leaves-slot-untouched-rf2-q8hvw
  (testing "rf2-q8hvw — clicking a row in the CURRENT target frame is a
            no-op for the slot (the re-seed only fires on a frame change);
            the head frame's epoch resolves as before"
    (mount-multi-frame-picker-untouched!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event :c2 :rf/default]))
    (let [r       (focus-sub)
          target  (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/target-frame]))
          history (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/epoch-history]))]
      (is (= :c2 (:dispatch-id r)))
      (is (= :e2 (:epoch-id r))
          "same-frame click resolves the head frame's epoch unchanged")
      (is (= :rf/default target)
          ":target-frame stays on the head frame — no spurious re-key")
      (is (= [:e1 :e2 :e3] (mapv :epoch-id history))
          ":epoch-history is still the head frame's full ring"))))

(deftest focus-epoch-cross-frame-reseeds-epoch-history-rf2-q8hvw
  (testing "rf2-q8hvw — :rf.xray/focus-epoch for an epoch whose record
            lives in a non-target frame re-seeds the slot before
            resolving the settling dispatch-id"
    (mount-multi-frame-picker-untouched!)
    ;; Seed the slot so the cart record is discoverable by the handler's
    ;; record-frame lookup (the Epoch panel's parent-epoch chip resolves
    ;; the epoch-id from a record already in view). Then the handler must
    ;; re-key onto the cart frame and resolve against its full ring.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [(epoch :e1 :c1) (epoch :e2 :c2)
                          (assoc (epoch :cart-e9 :cart-c9) :frame :cart-frame)
                          (epoch :e3 :c3)]])
      (rf/dispatch-sync [:rf.xray/focus-epoch :cart-e9]))
    (let [r       (focus-sub)
          target  (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/target-frame]))]
      (is (= :cart-e9 (:epoch-id r))
          "focus-epoch pins the cross-frame epoch")
      (is (= :cart-c9 (:dispatch-id r))
          "the settling dispatch-id resolves from the re-seeded ring")
      (is (= :cart-frame target)
          ":target-frame re-keyed onto the epoch's frame"))))

(deftest select-dispatch-id-cross-frame-reseeds-epoch-history-rf2-o1c3r
  (testing "rf2-o1c3r — :rf.xray/select-dispatch-id (the legacy
            machine-inspector / trace / mcp-server entry point) for a
            cascade that settled in a NON-head frame re-seeds
            `:epoch-history` onto that frame BEFORE resolving the
            settling epoch, exactly as the sibling :rf.xray/focus-event
            handler does (rf2-q8hvw). Drives the ACTUAL failing path —
            picker untouched, slot keyed on the boot head frame — and
            asserts the clicked dispatch's epoch resolves (not nil)."
    (mount-multi-frame-picker-untouched!)
    ;; RED baseline: with the picker untouched the slot holds the HEAD
    ;; frame's ring, and the cart cascade's epoch is absent from it — the
    ;; pre-fix `(get db :epoch-history [])` read resolved nil here.
    (let [boot-history (rf/with-frame :rf/xray
                         @(rf/subscribe [:rf.xray/epoch-history]))]
      (is (= :rf/default (rf/with-frame :rf/xray
                           @(rf/subscribe [:rf.xray/target-frame])))
          "boot :target-frame is the head frame (picker untouched)")
      (is (nil? (spine/epoch-id-for-cascade boot-history :cart-c9))
          "RED baseline: the cart cascade's epoch is NOT in the boot
           head-frame ring — resolving against the un-reseeded slot
           yields nil (the o1c3r bug)"))
    ;; The actual failing path: dispatch the real :rf.xray/select-dispatch-id
    ;; event for the non-head-frame cascade.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id :cart-c9 :cart-frame]))
    ;; GREEN: the spine focus now carries the clicked dispatch's epoch.
    (let [r (focus-sub)]
      (is (= :cart-c9 (:dispatch-id r)))
      (is (= :cart-e9 (:epoch-id r))
          "the clicked cross-frame dispatch's settling epoch resolves —
           the spine focus sub carries it (was nil pre-fix)"))
    ;; The slot itself is re-keyed onto the clicked cascade's frame, and
    ;; the App-DB-diff-facing projection follows.
    (let [target      (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/target-frame]))
          history     (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/epoch-history]))
          focus-epoch (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/focus-epoch-id]))]
      (is (= :cart-frame target)
          ":target-frame re-keyed onto the clicked cascade's frame")
      (is (= [:cart-e9] (mapv :epoch-id history))
          ":epoch-history is the clicked frame's ring contents")
      (is (= :cart-e9 focus-epoch)
          "App-DB diff's :rf.xray/focus-epoch-id projection pivots to the
           clicked dispatch's epoch — the panel renders the right epoch's
           diff rather than an empty/stale state"))))

(deftest select-dispatch-id-same-frame-leaves-slot-untouched-rf2-o1c3r
  (testing "rf2-o1c3r — selecting a dispatch-id in the CURRENT target
            frame is a no-op for the slot (the re-seed only fires on a
            frame change); the head frame's epoch resolves as before"
    (mount-multi-frame-picker-untouched!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id :c2 :rf/default]))
    (let [r       (focus-sub)
          target  (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/target-frame]))
          history (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/epoch-history]))]
      (is (= :c2 (:dispatch-id r)))
      (is (= :e2 (:epoch-id r))
          "same-frame select resolves the head frame's epoch unchanged")
      (is (= :rf/default target)
          ":target-frame stays on the head frame — no spurious re-key")
      (is (= [:e1 :e2 :e3] (mapv :epoch-id history))
          ":epoch-history is still the head frame's full ring"))))

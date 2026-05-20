(ns day8.re-frame2-causa.spine-cljs-test
  "Direct CLJS coverage for the spine substrate (rf2-adve5) — pure-
  reducer + sub composition + the legacy-slot shim contract.

  Per `tools/causa/spec/018-Event-Spine.md` §6 Spine binding the spine
  sub `:rf.causa/focus` is the one axis every dependent surface reads
  from. This file asserts:

  1. The pure reducers (`focus-cascade-reducer`, `follow-head-reducer`,
     `toggle-live-pause-reducer`, `set-frame-reducer`, `focus-step-
     reducer`, `preview-cascade-reducer`) — JVM-runnable shape, no
     re-frame machinery needed.
  2. `compose-focus` derives `:head?` + the effective `:dispatch-id`
     correctly across LIVE / RETRO / paused / evicted-cascade states.
  3. The legacy shim — `:rf.causa/select-dispatch-id` writes through
     to `:focus`, and `:rf.causa/focus-cascade` writes through to the
     legacy `:selected-dispatch-id` slot. Existing panels continue to
     read the legacy slot; new spine consumers read `:rf.causa/focus`.
  4. The registered sub composes the slot + cascades via the standard
     re-frame reactive path."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.spine :as spine]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- cascade fixture ----------------------------------------------------

(defn- cascade
  "Build a minimal cascade shape — enough for the spine helpers'
  by-id lookups + head detection. Per
  `re-frame.trace.projection/group-cascades` cascades carry at least
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
  "Three-cascade fixture; oldest-first per group-cascades' contract.
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
            the head, and `:target-frame nil` would resolve via
            `set-target-frame`'s `(or frame-id default-target-frame)`
            to `:rf/default` — wiping out a real pre-mount frame's
            history."
    (let [cascades [(cascade :c1 :cart-frame)
                    (cascade :ungrouped nil)]]
      (is (= :cart-frame (spine/focusable-head-frame-id cascades))
          ":ungrouped is filtered; head focusable cascade is :c1"))))

(deftest focusable-head-frame-id-empty-cascades-returns-nil
  (testing "rf2-boyc2 — no focusable cascades → nil. Callers fall back
            to `defaults/default-target-frame` so the cold-start path
            behaves identically to pre-fix."
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
;; (3) focus-cascade-reducer — writes :focus + legacy shim
;; -------------------------------------------------------------------------

(deftest focus-cascade-reducer-writes-focus-slot
  (let [db {}
        r  (spine/focus-cascade-reducer db :c2 :rf/default)]
    (is (= :c2 (get-in r [:focus :dispatch-id])))
    (is (= :retro (get-in r [:focus :mode])))
    (is (= :rf/default (get-in r [:focus :frame])))))

(deftest focus-cascade-reducer-writes-legacy-shim
  (testing "the legacy :selected-dispatch-id + :selected-dispatch slots
            update in lockstep so existing event-detail / machine-
            inspector panels keep rendering"
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default)]
      (is (= :c2 (:selected-dispatch-id r)))
      (is (= {:dispatch-id :c2 :frame :rf/default}
             (:selected-dispatch r))))))

(deftest focus-cascade-reducer-without-frame-omits-frame
  (let [r (spine/focus-cascade-reducer {} :c2 nil)]
    (is (= :c2 (get-in r [:focus :dispatch-id])))
    (is (= :c2 (:selected-dispatch-id r)))
    (is (= {:dispatch-id :c2} (:selected-dispatch r))
        "no :frame key when frame-id was nil")))

;; -------------------------------------------------------------------------
;; (4) focus-step-reducer — prev/next stepping
;; -------------------------------------------------------------------------

(deftest focus-step-reducer-prev-flips-to-retro
  (let [db {:focus {:dispatch-id :c3 :mode :live} :selected-dispatch-id :c3}
        r  (spine/focus-step-reducer db fixture-cascades -1)]
    (is (= :c2 (get-in r [:focus :dispatch-id])))
    (is (= :retro (get-in r [:focus :mode])))
    (is (= :c2 (:selected-dispatch-id r))
        "legacy shim updated alongside")))

(deftest focus-step-reducer-next-returns-to-head-as-live
  (let [db {:focus {:dispatch-id :c2 :mode :retro} :selected-dispatch-id :c2}
        r  (spine/focus-step-reducer db fixture-cascades +1)]
    (is (= :c3 (get-in r [:focus :dispatch-id])))
    (is (= :live (get-in r [:focus :mode]))
        "stepping back to head implicitly re-engages LIVE")))

(deftest focus-step-reducer-from-empty-slot-snaps-to-head-then-steps
  (testing "rf2-s0s5x Phase A — with an empty `:focus` slot the
            current-id is derived through `compose-focus` (which
            snaps to head in :live), not lifted off the legacy
            `:selected-dispatch-id` slot. The legacy slot is a
            shim-write-target now, not a read source — keeping
            spine as the canonical source of truth."
    (let [db {:selected-dispatch-id :c2}
          r  (spine/focus-step-reducer db fixture-cascades -1)]
      (is (= :c2 (get-in r [:focus :dispatch-id]))
          "step prev: empty :focus → composer derives head (c3) → prev
           lands on c2 (one step back from head)"))))

;; -------------------------------------------------------------------------
;; (5) follow-head-reducer
;; -------------------------------------------------------------------------

(deftest follow-head-reducer-snaps-to-live
  (let [db {:focus {:dispatch-id :c1 :mode :retro :paused? true}
            :selected-dispatch-id :c1
            :selected-dispatch    {:dispatch-id :c1}}
        r  (spine/follow-head-reducer db)]
    (is (nil? (get-in r [:focus :dispatch-id]))
        "follow-head clears :dispatch-id so compose-focus snaps to head")
    (is (= :live (get-in r [:focus :mode])))
    (is (false? (get-in r [:focus :paused?])))
    (is (nil? (:selected-dispatch-id r))
        "legacy slots cleared in lockstep")
    (is (nil? (:selected-dispatch r)))))

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
    (let [db {:focus {:mode :retro :paused? false} :selected-dispatch-id :c1}
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

;; -------------------------------------------------------------------------
;; (8) Registered :rf.causa/focus sub — reactive composition
;; -------------------------------------------------------------------------

;; Forward-declare the `epoch` helper (defined in section 10) so the
;; rf2-70tkv reactive test below can build epoch-history fixtures.
(declare epoch)

(defn- focus-sub []
  (rf/with-frame :rf/causa
    @(rf/subscribe [:rf.causa/focus])))

(defn- seed-cascades! [cascades-vec]
  ;; Build a minimal trace-buffer that re-projects to the desired
  ;; cascade vector. Each cascade needs one trace event carrying the
  ;; cascade's id so group-cascades' frame-index pairs the right
  ;; events. The simplest shape: one :event/dispatched per cascade.
  (let [events (map-indexed
                 (fn [i {:keys [dispatch-id frame]}]
                   {:id        (inc i)
                    :op-type   :event
                    :operation :event/dispatched
                    :tags      {:dispatch-id dispatch-id
                                :frame       frame
                                :event       [(keyword "evt" (str (name dispatch-id)))]}})
                 cascades-vec)]
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sync-trace-buffer (vec events)]))))

(deftest focus-sub-returns-default-shape-on-empty-buffer
  (setup-causa-frame!)
  (let [r (focus-sub)]
    (is (= :live (:mode r))
        "empty buffer → :live (head-tracking)")
    (is (nil? (:dispatch-id r)))
    (is (true? (:head? r)))))

(deftest focus-sub-snaps-to-head-on-empty-slot
  (setup-causa-frame!)
  (seed-cascades! fixture-cascades)
  (let [r (focus-sub)]
    (is (= :c3 (:dispatch-id r))
        "empty :focus slot → :live → snap to head c3")
    (is (true? (:head? r)))))

(deftest focus-sub-preserves-retro-selection
  (setup-causa-frame!)
  (seed-cascades! fixture-cascades)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/focus-cascade :c1 :rf/default]))
  (let [r (focus-sub)]
    (is (= :c1 (:dispatch-id r)))
    (is (= :retro (:mode r)))
    (is (false? (:head? r)))))

(deftest focus-sub-shimmed-by-legacy-select-dispatch-id
  (testing "dispatching the legacy :rf.causa/select-dispatch-id writes
            through to the spine — proves the shim runs in BOTH
            directions (legacy event → spine sub)"
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id :c2 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c2 (:dispatch-id r))
          "spine focus tracks the legacy selection event")
      (is (= :retro (:mode r))))))

(deftest legacy-selected-dispatch-id-shimmed-by-focus-cascade
  (testing "dispatching the new :rf.causa/focus-cascade writes
            through to the legacy slot — existing event-detail panel
            keeps reading :selected-dispatch-id unchanged"
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-cascade :c2 :rf/default]))
    (let [legacy (rf/with-frame :rf/causa
                   @(rf/subscribe [:rf.causa/selected-dispatch-id]))]
      (is (= :c2 legacy)
          "legacy sub rebinds because the focus-cascade event also
           wrote the legacy slot"))))

(deftest focus-sub-step-events-walk-cascades
  (setup-causa-frame!)
  (seed-cascades! fixture-cascades)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/focus-cascade :c3 :rf/default])
    (rf/dispatch-sync [:rf.causa/focus-cascade-prev]))
  (let [r (focus-sub)]
    (is (= :c2 (:dispatch-id r)) "prev steps from c3 to c2")
    (is (= :retro (:mode r))))
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/focus-cascade-prev]))
  (let [r (focus-sub)]
    (is (= :c1 (:dispatch-id r)) "prev again → c1"))
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/focus-cascade-next])
    (rf/dispatch-sync [:rf.causa/focus-cascade-next]))
  (let [r (focus-sub)]
    (is (= :c3 (:dispatch-id r)) "two nexts → back to head")
    (is (= :live (:mode r))
        "stepping back to head re-engages LIVE")))

(deftest follow-head-event-resnaps-to-head
  (setup-causa-frame!)
  (seed-cascades! fixture-cascades)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/focus-cascade :c1 :rf/default])
    (rf/dispatch-sync [:rf.causa/follow-head]))
  (let [r (focus-sub)]
    (is (= :c3 (:dispatch-id r)) "follow-head snaps back to head c3")
    (is (= :live (:mode r)))
    (is (true? (:head? r)))))

(deftest toggle-live-pause-event-flips-paused
  (setup-causa-frame!)
  (seed-cascades! fixture-cascades)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/toggle-live-pause]))
  (let [r (focus-sub)]
    (is (true? (:paused? r)))
    (is (= :live (:mode r)))))

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
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    ;; Initial state — LIVE, focused on head c3 (implicit, slot-id nil).
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r)))
      (is (= :live (:mode r))))
    ;; User steps prev then next — lands on c3 again, slot-id is now :c3.
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-cascade-prev])
      (rf/dispatch-sync [:rf.causa/focus-cascade-next]))
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
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-cascade :c1 :rf/default]))
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
  (testing "rf2-70tkv — the reactive :rf.causa/focus sub auto-derives
            :epoch-id to the head cascade's settling epoch in LIVE
            mode. Mike repro: user clicks an L2 row (writes
            :selected-epoch-id + focus :epoch-id), then clicks
            Follow-head. Pre-fix the focus sub left :epoch-id pinned
            to the clicked epoch even after follow-head; every
            epoch-keyed panel (Views, Machines, App-DB diff via
            shim) stayed frozen. Post-fix the sub re-derives
            :epoch-id from head."
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      ;; Seed an epoch-history covering all three cascades.
      (rf/dispatch-sync [:rf.causa/sync-epoch-history
                         [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]])
      ;; User clicks the earliest cascade — pins focus to :c1/:e1.
      (rf/dispatch-sync [:rf.causa/focus-cascade :c1 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c1 (:dispatch-id r)))
      (is (= :e1 (:epoch-id r)))
      (is (= :retro (:mode r))))
    ;; User clicks Follow-head — :mode flips to :live; the stored
    ;; :focus slot's :dispatch-id is cleared, but pre-rf2-70tkv the
    ;; legacy :selected-epoch-id stayed on :e1 AND the focus sub's
    ;; :epoch-id stayed on :e1 too. Post-fix the sub re-derives
    ;; from head.
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/follow-head]))
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
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sync-epoch-history
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
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      ;; Get into :live mode with slot-id pinned on c2 (paused inspection
      ;; of a non-head cascade — possible via focus-step then pause).
      (rf/dispatch-sync [:rf.causa/focus-cascade-prev])
      (rf/dispatch-sync [:rf.causa/focus-cascade-next]))
    ;; Now slot-id = :c3, mode = :live. Pause.
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-live-pause]))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r)))
      (is (true? (:paused? r))))
    ;; New cascade arrives. Paused LIVE stays put.
    (seed-cascades! (conj fixture-cascades (cascade :c4 :rf/default)))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r))
          "paused LIVE pins focus through arrivals")
      (is (true? (:paused? r))))))

;; -------------------------------------------------------------------------
;; (10) :epoch-id resolution — rf2-ak3ty
;;
;; Spec/018 §6 Spine events says `:rf.causa/focus-cascade <id>` MUST
;; "compute `:epoch-id` from cascades". The reducer takes a resolved
;; epoch-id (the event handler in `install!` resolves it from the
;; Causa `:epoch-history` slot via `epoch-id-for-cascade`), then
;; stamps both the `:focus :epoch-id` slot and the legacy
;; `:selected-epoch-id` shim slot the App-db diff panel reads.
;; -------------------------------------------------------------------------

(defn- epoch
  "Build a minimal `:rf/epoch-record` carrying the slots
  `epoch-id-for-cascade` looks at — a `:trace-events` vector with one
  dispatch-id-tagged event, plus the literal `:dispatch-id` slot the
  test fallback honours."
  [epoch-id dispatch-id]
  {:epoch-id     epoch-id
   :dispatch-id  dispatch-id
   :trace-events [{:id 1 :op-type :event :operation :event/dispatched
                   :tags {:dispatch-id dispatch-id}}]})

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
;; Travel, the :rf.causa/select-epoch chip in the diff), every panel
;; pivoting on focus :epoch-id (Views' focused-cascade-pair, Machine
;; Inspector's focused-event lens, App-DB Diff via the
;; :selected-epoch-id shim slot) stayed wired to the stale epoch
;; while :dispatch-id correctly tracked head.
;;
;; Fix: a 4-arity `compose-focus` accepts `epoch-history` and re-
;; derives :epoch-id from the head cascade in LIVE+unpaused mode,
;; mirroring how :dispatch-id auto-tracks head. The 3-arity (test
;; rigs, back-compat callers) preserves the original passthrough
;; behaviour. The reactive `:rf.causa/focus` sub in `install!` uses
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
  (testing "the 4-arg reducer writes :epoch-id into the :focus slot AND
            the legacy :selected-epoch-id shim slot — App-db pivots
            from L2-list clicks once this lands (rf2-ak3ty)"
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default :e2)]
      (is (= :e2 (get-in r [:focus :epoch-id])))
      (is (= :e2 (:selected-epoch-id r))
          "legacy slot the App-db panel reads pivots in lockstep")
      (is (= :c2 (get-in r [:focus :dispatch-id])))
      (is (= :c2 (:selected-dispatch-id r))))))

(deftest focus-cascade-reducer-3-arg-leaves-epoch-id-nil
  (testing "the back-compat 3-arg reducer leaves :epoch-id nil — the
            focus sub still rebinds on :dispatch-id, but epoch-keyed
            surfaces won't pivot until a 4-arg call lands"
    (let [r (spine/focus-cascade-reducer {} :c2 :rf/default)]
      (is (nil? (get-in r [:focus :epoch-id])))
      (is (nil? (:selected-epoch-id r))))))

(deftest focus-cascade-event-resolves-epoch-id-from-history
  (testing "the registered :rf.causa/focus-cascade event resolves
            :epoch-id from the Causa app-db's :epoch-history slot —
            end-to-end the spine focus now carries both ids in
            lockstep (rf2-ak3ty)"
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sync-epoch-history
                         [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]])
      (rf/dispatch-sync [:rf.causa/focus-cascade :c2 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c2 (:dispatch-id r)))
      (is (= :e2 (:epoch-id r))
          "spine sub carries the resolved epoch-id — the
           load-bearing prereq for rf2-w15el (Views) and the
           App-db pivot fix"))))

(deftest focus-cascade-event-writes-selected-epoch-id-legacy-slot
  (testing "App-db's :rf.causa/selected-epoch-id sub pivots on L2-list
            click — proves the spine writes through the legacy shim
            so App-db (and Time-Travel highlight) rebind from a row
            click without any per-panel change"
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sync-epoch-history
                         [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]])
      (rf/dispatch-sync [:rf.causa/focus-cascade :c1 :rf/default]))
    (let [legacy (rf/with-frame :rf/causa
                   @(rf/subscribe [:rf.causa/selected-epoch-id]))]
      (is (= :e1 legacy)
          "App-db's :selected-epoch-id sub rebinds to the focused
           cascade's epoch — pivot now works from L2 list clicks"))))

(deftest focus-step-reducer-4-arg-writes-epoch-id
  (testing "step events resolve :epoch-id for the new dispatch-id"
    (let [db       {:focus {:dispatch-id :c3 :mode :live}
                    :selected-dispatch-id :c3}
          history  [(epoch :e1 :c1) (epoch :e2 :c2) (epoch :e3 :c3)]
          r        (spine/focus-step-reducer db fixture-cascades history -1)]
      (is (= :c2 (get-in r [:focus :dispatch-id])))
      (is (= :e2 (get-in r [:focus :epoch-id])))
      (is (= :e2 (:selected-epoch-id r))))))

(deftest follow-head-reducer-clears-selected-epoch-id
  (testing "follow-head clears the App-db legacy slot in lockstep with
            the dispatch-id slot — the panel returns to its landing
            view"
    (let [db {:focus                {:dispatch-id :c1 :epoch-id :e1
                                     :mode :retro}
              :selected-dispatch-id :c1
              :selected-epoch-id    :e1}
          r  (spine/follow-head-reducer db)]
      (is (nil? (get-in r [:focus :epoch-id])))
      (is (nil? (:selected-epoch-id r))))))

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
;;   (ii) The composed `:rf.causa/focus` sub returns
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
    (let [db {:focus {:dispatch-id :c2 :mode :retro}
              :selected-dispatch-id :c2}
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
    (let [db {:focus {:dispatch-id :c2 :mode :retro}
              :selected-dispatch-id :c2}
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
    (let [db {:focus {:dispatch-id :c1 :mode :retro}
              :selected-dispatch-id :c1}
          r  (spine/focus-step-reducer db
                                       fixture-cascades-with-ungrouped
                                       -1)]
      (is (= db r) "db unchanged — boundary no-op"))))

(deftest focus-step-reducer-next-on-head-is-no-op
  (testing "rf2-fzbrw — [>] on the head (newest real event) returns db
            unchanged. Symmetric counterpart of the [<] boundary."
    (let [db {:focus {:dispatch-id :c2 :mode :live}
              :selected-dispatch-id :c2}
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
          db       {:focus {:dispatch-id :only-real :mode :live}
                    :selected-dispatch-id :only-real}
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
      (is (= :c3 (:selected-dispatch-id r)))
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
  (testing "rf2-xzzih end-to-end — dispatching :rf.causa/focus-cascade
            on the head cascade keeps spine in :live so a subsequent
            arrival auto-advances the focus"
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-cascade :c3 :rf/default]))
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
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-cascade :c1 :rf/default]))
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
  (testing "rf2-xzzih — the legacy `:rf.causa/select-dispatch-id`
            event (the spine-shim entry used by machine / cancellation
            panels) shares the head-aware mode pick so clicks from
            those panels onto the head cascade also keep :live"
    (setup-causa-frame!)
    (seed-cascades! fixture-cascades)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id :c3 :rf/default]))
    (let [r (focus-sub)]
      (is (= :c3 (:dispatch-id r)))
      (is (= :live (:mode r))
          "legacy click on head → :live (parity with focus-cascade event)"))))

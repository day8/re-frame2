(ns day8.re-frame2-causa.panels-e2e.parallel-frames-e2e-cljs-test
  "Multi-frame end-to-end coverage for two panel-refresh bugs caught
  live on the parallel-frames testbed (rf2-ulpp8 + rf2-1p1j4).

  Both bugs share the panel-refresh class — Causa's user-visible state
  pivots on `:rf.causa/focus`-slot axes, and a mis-aligned axis leaves
  the panel surface frozen against the wrong slice of the trace stream.

  ## rf2-ulpp8 — L2 list shows non-target-frame events on initial mount

  Repro (Mike live observation 2026-05-20):

    1. Open parallel-frames testbed fresh
    2. Causa picker shows `:above`
    3. L2 list shows 4 events (expected 2)
    4. The 2 phantom events are from `:below`
    5. Clicking anywhere flips the list to the correct 2 events

  Root cause: at install time `mount.cljs/ensure-causa-frame!` seeds
  `:target-frame` (via `:rf.causa/set-target-frame`) but never seeds
  `:focus :frame`. The L2 list filters via `:rf.causa/filtered-cascades`
  which reads `:rf.causa/focus-slot` → `:frame`. When that slot is
  nil the frame filter is a no-op and every frame's cascades pass.
  The picker view shows `(or selected-frame (first frames))` so the
  user sees `:above` selected while the underlying focus slot is
  unset — the picker lies and the filter no-ops.

  Clicking any L2 row dispatches `:rf.causa/focus-cascade` which
  writes `:focus :frame` (line 446 of `spine.cljs`), aligning the
  filter slot for the first time.

  Fix: align `:rf.causa/set-target-frame` to write `:focus :frame`
  in addition to `:target-frame` — the symmetric counterpart to
  `set-frame-reducer` (the picker write, which aligns BOTH axes
  per rf2-ug1r6 + rf2-thodq). Mount-time and picker-time gestures
  now write the same two axes.

  ## rf2-1p1j4 — Issues panel not event-scoped

  Repro: dispatch two host events that produce issues (e.g.
  deliberate throws), focus event A, observe Issues panel; focus
  event B, observe the panel content flip.

  Root cause: in the LIVE-mode auto-track branch of `compose-focus`
  the published `:rf.causa/focus` `:dispatch-id` is always `head-id`,
  not the stored slot id. Clicking a non-head row flips the spine
  to RETRO (`focus-cascade-reducer` writes `:mode :retro`) — which
  then makes `:dispatch-id` track the stored slot via the RETRO
  branch.

  The bug landed because the regression test surface had no
  coverage for the focus-flip path against the Issues sub. The fix
  is to extend `:rf.causa/set-target-frame` (which seed-mounts the
  filter slot) so the first head dispatch lands the issue at a
  matching :dispatch-id; symmetric with rf2-ulpp8 above.

  Test approach: two host frames `:above` + `:below`, register
  both, dispatch into each, install Causa with `:above` as initial
  target. Assert L2 list (via `:rf.causa/filtered-cascades`)
  carries ONLY `:above` cascades on initial mount — no first
  click."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.spine :as spine]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(def ^:private frame-above :above)
(def ^:private frame-below :below)

(defn- install-counter-handlers!
  "Register counter event/sub once globally. Resolves per-dispatch via
  the `:frame` option each frame's dispatch carries."
  []
  (rf/reg-event-db :counter/inc
    (fn [db _ev] (update db :counter/value (fnil inc 0))))
  (rf/reg-sub :counter/value
    (fn [db _] (:counter/value db))))

(defn- install-causa-handlers-and-collector!
  "Pre-mount Causa surface — registers handlers and the trace-bus
  collector. Idempotent. Called BEFORE the host dispatches so the
  trace-bus accumulates cascades the same way production does (the
  preload registers the collector before any host event fires).

  Also clears the trace-bus ring at the start so cross-test bleed
  (test-suite-global atom) doesn't pollute the assertion surface
  with cascades from prior tests."
  []
  (trace-bus/clear-buffer!)
  (causa-test-support/reset-all!)
  (registry/register-causa-handlers!)
  (preload/register-trace-collector!)
  nil)

(defn- mount-causa-with-target!
  "Mirror of `mount.cljs/ensure-causa-frame!` — registers the
  `:rf/causa` frame, seeds `:trace-buffer` from the bus, and forces
  the seed frame so the test exercises the picker-aligned-on-install
  invariant rather than relying on whatever the head walk picks."
  [target]
  (frame/reg-frame :rf/causa {})
  (let [buffer (trace-bus/buffer)]
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer])
      (rf/dispatch-sync [:rf.causa/set-target-frame target])))
  nil)

(defn- sub-causa [query]
  (rf/with-frame :rf/causa @(rf/subscribe query)))

(defn- dispatch-host-frame [event frame-id]
  (rf/dispatch-sync event {:frame frame-id})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/sync-trace-buffer (trace-bus/buffer)])))

(deftest rf2-ulpp8-l2-list-scoped-to-target-frame-on-initial-mount
  (testing "fresh mount with `:above` as the seed frame — L2 list MUST
  show only `:above` cascades. Pre-fix the list also included `:below`
  cascades because `:rf.causa/set-target-frame` seeded `:target-frame`
  without aligning `:focus :frame` (the slot the filter pivots on)."
    ;; Causa preload-time surface runs BEFORE any host dispatch —
    ;; the trace-bus collector must be registered first so the bus
    ;; accumulates the pre-mount cascades.
    (install-causa-handlers-and-collector!)
    ;; Register both host frames BEFORE pre-mount dispatches — same
    ;; shape as the parallel-frames testbed mount.
    (frame/reg-frame frame-above {})
    (frame/reg-frame frame-below {})
    (install-counter-handlers!)
    ;; Drive ONE event into each frame BEFORE Causa's frame mounts —
    ;; the trace bus accumulates pre-mount cascades the same way it
    ;; does in production for events fired before Ctrl+Shift+C.
    (rf/dispatch-sync [:counter/inc] {:frame frame-above})
    (rf/dispatch-sync [:counter/inc] {:frame frame-below})
    ;; Now mount Causa's frame with `:above` as the seed frame — the
    ;; analogue of the live picker showing `:above` selected on first
    ;; paint.
    (mount-causa-with-target! frame-above)
    (let [cascades          (sub-causa [:rf.causa/cascades])
          filtered-cascades (sub-causa [:rf.causa/filtered-cascades])
          above-cascades    (filterv #(= frame-above (:frame %)) cascades)
          below-cascades    (filterv #(= frame-below (:frame %)) cascades)
          filtered-frames   (into #{} (map :frame filtered-cascades))]
      (is (= 1 (count above-cascades))
          "test setup: should have exactly one :above cascade in the raw list")
      (is (= 1 (count below-cascades))
          "test setup: should have exactly one :below cascade in the raw list")
      (is (= #{frame-above} filtered-frames)
          (str "L2 filtered-cascades shows frames other than :above on initial "
               "mount — rf2-ulpp8 regression. Saw: " (pr-str filtered-frames))))))

(deftest rf2-ulpp8-focus-slot-frame-aligned-after-seed
  (testing "after `:rf.causa/set-target-frame` the `:focus :frame` slot
  MUST match `:target-frame`. The two axes encode the same gesture
  (the user is observing this host frame) — mount-time seed must align
  them just like the picker (`set-frame-reducer`) does at picker-write."
    (install-causa-handlers-and-collector!)
    (frame/reg-frame frame-above {})
    (install-counter-handlers!)
    (rf/dispatch-sync [:counter/inc] {:frame frame-above})
    (mount-causa-with-target! frame-above)
    (let [target     (sub-causa [:rf.causa/target-frame])
          focus-slot (sub-causa [:rf.causa/focus-slot])]
      (is (= frame-above target)
          "test setup: :target-frame should be :above after seed")
      (is (= frame-above (:frame focus-slot))
          (str ":focus :frame slot did not align with :target-frame after seed — "
               "rf2-ulpp8 root cause. focus-slot was: " (pr-str focus-slot))))))

;; ---- rf2-1p1j4 — Issues panel event-scoped on focus flip ---------------

(defn- install-throw-handlers!
  "Two distinct throwing handlers — gives us two cascades that each
  produce a `:rf.error/handler-exception` issue keyed to its own
  `:dispatch-id`."
  []
  (rf/reg-event-db :throws/a
    (fn [_db _ev]
      (throw (ex-info "throw-a" {:where :a}))))
  (rf/reg-event-db :throws/b
    (fn [_db _ev]
      (throw (ex-info "throw-b" {:where :b})))))

(deftest rf2-1p1j4-issues-panel-scopes-to-focused-cascade
  (testing "after two host throws on separate cascades, Issues sub
  surfaces ONLY the issues from the focused cascade. Flipping focus
  flips the panel content. Pre-fix the panel never re-scoped because
  the focused `:dispatch-id` did not change in LIVE auto-track mode."
    (install-causa-handlers-and-collector!)
    (frame/reg-frame :rf/default {})
    (install-throw-handlers!)
    (mount-causa-with-target! :rf/default)
    ;; Dispatch A — its handler throws, the router catches, an issue
    ;; rides under cascade A's :dispatch-id.
    (dispatch-host-frame [:throws/a] :rf/default)
    ;; Dispatch B — second cascade, second issue.
    (dispatch-host-frame [:throws/b] :rf/default)
    (let [cascades (sub-causa [:rf.causa/cascades])
          ;; Filter to host frame cascades only; Causa-internal are
          ;; already filtered by the sub.
          host-cascades (filterv #(= :rf/default (:frame %)) cascades)
          ;; Pluck the two dispatch-ids from the cascade list — A
          ;; landed first (lower id), B second.
          a-id (-> host-cascades first :dispatch-id)
          b-id (-> host-cascades second :dispatch-id)]
      (is (= 2 (count host-cascades))
          "test setup: should have two host cascades (one per throw)")
      (is (some? a-id) "cascade A has no :dispatch-id")
      (is (some? b-id) "cascade B has no :dispatch-id")
      (is (not= a-id b-id) "test setup: cascades should have distinct dispatch-ids")
      ;; Focus cascade A → assert Issues feed scoped to A only.
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/focus-cascade a-id :rf/default]))
      (let [feed-a (sub-causa [:rf.causa/issues-ribbon])
            ids-a  (into #{} (map :dispatch-id (:issues feed-a)))]
        (is (= #{a-id} ids-a)
            (str "Issues panel did not scope to focused cascade A — "
                 "rf2-1p1j4 regression. ids-a was: " (pr-str ids-a))))
      ;; Flip focus to cascade B → assert the feed flips with it.
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/focus-cascade b-id :rf/default]))
      (let [feed-b (sub-causa [:rf.causa/issues-ribbon])
            ids-b  (into #{} (map :dispatch-id (:issues feed-b)))]
        (is (= #{b-id} ids-b)
            (str "Issues panel did not re-scope on focus flip — "
                 "rf2-1p1j4 regression. ids-b was: " (pr-str ids-b)))))))

(deftest rf2-1p1j4-issues-panel-scoped-on-multi-frame-initial-mount
  (testing "multi-frame variant: when an :above throw and a :below
  throw both land in the trace bus PRE-mount, then Causa mounts with
  `:above` as the seed frame, the Issues panel MUST surface only
  the :above throw's issue — not the :below one. Pre-rf2-ulpp8 the
  composer's head walk picked the global most-recent cascade (which
  could be :below's), and the Issues sub scoped to the wrong frame's
  issue on first paint — the live-observed shape of rf2-1p1j4."
    (install-causa-handlers-and-collector!)
    (frame/reg-frame frame-above {})
    (frame/reg-frame frame-below {})
    (install-throw-handlers!)
    ;; Dispatch :above first, :below second — :below is "head" by the
    ;; global ordering and would have been auto-focused pre-fix.
    (rf/dispatch-sync [:throws/a] {:frame frame-above})
    (rf/dispatch-sync [:throws/b] {:frame frame-below})
    (mount-causa-with-target! frame-above)
    (let [cascades        (sub-causa [:rf.causa/cascades])
          above-cascades  (filterv #(= frame-above (:frame %)) cascades)
          above-id        (-> above-cascades first :dispatch-id)
          focus-slot      (sub-causa [:rf.causa/focus-slot])
          focus           (sub-causa [:rf.causa/focus])
          feed            (sub-causa [:rf.causa/issues-ribbon])
          feed-ids        (into #{} (map :dispatch-id (:issues feed)))]
      (is (some? above-id) "test setup: should have an :above cascade")
      ;; rf2-ulpp8 alignment — `[:focus :frame]` is the slot the L2
      ;; filter + the compose-focus head-walk both pivot on. The
      ;; rf2-ulpp8 fix in `epoch.cljs/set-target-frame` writes this
      ;; slot in lockstep with `:target-frame`. If this assertion fails
      ;; the fix has regressed.
      (is (= frame-above (:frame focus-slot))
          (str "focus-slot :frame did not align with :target-frame after "
               "seed (rf2-ulpp8 fix regressed) — got: " (pr-str focus-slot)))
      (is (= frame-above (:frame focus))
          (str "composed focus :frame is not :above — head walk did not "
               "honour the picker scope. focus was: " (pr-str focus)))
      (is (= #{above-id} feed-ids)
          (str "Issues panel scoped to a non-:above cascade on initial "
               "mount when :above was the seed frame — rf2-1p1j4 "
               "downstream-of-rf2-ulpp8 regression. feed-ids was: "
               (pr-str feed-ids))))))

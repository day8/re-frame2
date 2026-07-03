(ns day8.re-frame2-xray.panels-e2e.parallel-frames-e2e-cljs-test
  "Multi-frame end-to-end coverage for two panel-refresh bugs caught
  live on the parallel-frames testbed (rf2-ulpp8 + rf2-1p1j4).

  Both bugs share the panel-refresh class — Xray's user-visible state
  pivots on `:rf.xray/focus`-slot axes, and a mis-aligned axis leaves
  the panel surface frozen against the wrong slice of the trace stream.

  ## rf2-ulpp8 — L2 list shows non-target-frame events on initial mount

  Repro (Mike live observation 2026-05-20):

    1. Open parallel-frames testbed fresh
    2. Xray picker shows `:above`
    3. L2 list shows 4 events (expected 2)
    4. The 2 phantom events are from `:below`
    5. Clicking anywhere flips the list to the correct 2 events

  Root cause: at install time `mount.cljs/ensure-xray-frame!` seeds
  `:target-frame` (via `:rf.xray/set-target-frame`) but never seeds
  `:focus :frame`. The L2 list filters via `:rf.xray/filtered-cascades`
  which reads `:rf.xray/focus-slot` → `:frame`. When that slot is
  nil the frame filter is a no-op and every frame's cascades pass.
  The picker view shows `(or selected-frame (first frames))` so the
  user sees `:above` selected while the underlying focus slot is
  unset — the picker lies and the filter no-ops.

  Clicking any L2 row dispatches `:rf.xray/focus-event` which
  writes `:focus :frame` (line 446 of `spine.cljs`), aligning the
  filter slot for the first time.

  Fix: align `:rf.xray/set-target-frame` to write `:focus :frame`
  in addition to `:target-frame` — the symmetric counterpart to
  `set-frame-reducer` (the picker write, which aligns BOTH axes
  per rf2-ug1r6 + rf2-thodq). Mount-time and picker-time gestures
  now write the same two axes.

  ## rf2-1p1j4 — Issues panel not event-scoped

  Repro: dispatch two host events that produce issues (e.g.
  deliberate throws), focus event A, observe Issues panel; focus
  event B, observe the panel content flip.

  Root cause: in the LIVE-mode auto-track branch of `compose-focus`
  the published `:rf.xray/focus` `:dispatch-id` is always `head-id`,
  not the stored slot id. Clicking a non-head row flips the spine
  to RETRO (`focus-cascade-reducer` writes `:mode :retro`) — which
  then makes `:dispatch-id` track the stored slot via the RETRO
  branch.

  The bug landed because the regression test surface had no
  coverage for the focus-flip path against the Issues sub. The fix
  is to extend `:rf.xray/set-target-frame` (which seed-mounts the
  filter slot) so the first head dispatch lands the issue at a
  matching :dispatch-id; symmetric with rf2-ulpp8 above.

  Test approach: two host frames `:above` + `:below`, register
  both, dispatch into each, install Xray with `:above` as initial
  target. Assert L2 list (via `:rf.xray/filtered-cascades`)
  carries ONLY `:above` cascades on initial mount — no first
  click."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private frame-above :above)
(def ^:private frame-below :below)

(defn- install-counter-handlers!
  "Register counter event/sub once globally. Resolves per-dispatch via
  the `:frame` option each frame's dispatch carries."
  []
  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _ev] {:db (update db :counter/value (fnil inc 0))}))
  (rf/reg-sub :counter/value
    (fn [db _] (:counter/value db))))

(defn- install-xray-handlers-and-collector!
  "Pre-mount Xray surface — registers handlers and the trace collector
  listener. Idempotent. Called BEFORE the host dispatches so the
  framework's per-frame rings + Xray's frameless secondary ring
  accumulate cascades the same way production does (the preload
  registers the listener before any host event fires).

  Resets the rings + frameless secondary ring + framework dedup
  table at the start so cross-test bleed (test-suite-global atoms)
  doesn't pollute the assertion surface with cascades from prior
  tests."
  []
  (trace-collector/reset-for-test!)
  (xray-test-support/reset-all!)
  (registry/register-xray-handlers!)
  (preload/register-trace-collector!)
  nil)

(defn- mount-xray-with-target!
  "Mirror of `mount.cljs/ensure-xray-frame!` — registers the
  `:rf/xray` frame, seeds `:trace-buffer` from the framework's
  per-frame rings + Xray's frameless secondary ring via the
  `refresh-trace-rings!` sync entrypoint (per the rf2-3g9nw D3=b
  ruling), and forces the seed frame so the test exercises the
  picker-aligned-on-install invariant rather than relying on whatever
  the head walk picks."
  [target]
  (frame/reg-frame :rf/xray {})
  ;; D3=b sync entrypoint — snapshot every registered host frame's
  ;; per-frame ring + the frameless secondary ring into Xray's
  ;; `:trace-buffer` slot so the cascade subs read the pre-mount
  ;; events on the next subscribe. Bypasses the production microtask
  ;; coalescer for deterministic test ordering.
  (trace-collector/refresh-trace-rings!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-target-frame target]))
  nil)

(defn- sub-xray [query]
  (rf/with-frame :rf/xray @(rf/subscribe query)))

(defn- dispatch-host-frame [event frame-id]
  (rf/dispatch-sync event {:frame frame-id})
  ;; D3=b sync entrypoint — refresh Xray's app-db slot from the
  ;; framework's per-frame rings + Xray's secondary ring deterministically
  ;; after each host dispatch, bypassing the microtask coalescer.
  (trace-collector/refresh-trace-rings!)
  (rf/with-frame :rf/xray
    ;; rf2-jio48 — Issues panel reads the focused epoch's
    ;; :trace-events (not the global trace bus). Re-target so Xray's
    ;; :epoch-history slot mirrors the framework's per-frame ring for
    ;; the host frame the test is observing.
    (rf/dispatch-sync [:rf.xray/set-target-frame frame-id])))

(deftest rf2-ulpp8-l2-list-scoped-to-target-frame-on-initial-mount
  (testing "fresh mount with `:above` as the seed frame — L2 list MUST
  show only `:above` cascades. Pre-fix the list also included `:below`
  cascades because `:rf.xray/set-target-frame` seeded `:target-frame`
  without aligning `:focus :frame` (the slot the filter pivots on)."
    ;; Xray preload-time surface runs BEFORE any host dispatch —
    ;; the trace-bus collector must be registered first so the bus
    ;; accumulates the pre-mount cascades.
    (install-xray-handlers-and-collector!)
    ;; Register both host frames BEFORE pre-mount dispatches — same
    ;; shape as the parallel-frames testbed mount.
    (frame/reg-frame frame-above {})
    (frame/reg-frame frame-below {})
    (install-counter-handlers!)
    ;; Drive ONE event into each frame BEFORE Xray's frame mounts —
    ;; the trace bus accumulates pre-mount cascades the same way it
    ;; does in production for events fired before Ctrl+Shift+C.
    (rf/dispatch-sync [:counter/inc] {:frame frame-above})
    (rf/dispatch-sync [:counter/inc] {:frame frame-below})
    ;; Now mount Xray's frame with `:above` as the seed frame — the
    ;; analogue of the live picker showing `:above` selected on first
    ;; paint.
    (mount-xray-with-target! frame-above)
    (let [cascades          (sub-xray [:rf.xray/cascades])
          filtered-cascades (sub-xray [:rf.xray/filtered-cascades])
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
  (testing "after `:rf.xray/set-target-frame` the `:focus :frame` slot
  MUST match `:target-frame`. The two axes encode the same gesture
  (the user is observing this host frame) — mount-time seed must align
  them just like the picker (`set-frame-reducer`) does at picker-write."
    (install-xray-handlers-and-collector!)
    (frame/reg-frame frame-above {})
    (install-counter-handlers!)
    (rf/dispatch-sync [:counter/inc] {:frame frame-above})
    (mount-xray-with-target! frame-above)
    (let [target     (sub-xray [:rf.xray/target-frame])
          focus-slot (sub-xray [:rf.xray/focus-slot])]
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
  (rf/reg-event :throws/a
    (fn [_ _ev]
      (throw (ex-info "throw-a" {:where :a}))))
  (rf/reg-event :throws/b
    (fn [_ _ev]
      (throw (ex-info "throw-b" {:where :b})))))

(deftest rf2-1p1j4-issues-panel-scopes-to-focused-cascade
  (testing "after two host throws on separate cascades, Issues sub
  surfaces ONLY the issues from the focused epoch's :trace-events
  (rf2-jio48 — focused-epoch scope per spec/021 §1.2 + §8). Flipping
  focus flips the panel content. Pre-fix the panel never re-scoped
  because the focused `:dispatch-id` did not change in LIVE auto-
  track mode."
    (install-xray-handlers-and-collector!)
    (frame/reg-frame :rf/default {})
    (install-throw-handlers!)
    (mount-xray-with-target! :rf/default)
    ;; Dispatch A — its handler throws, the router catches, an issue
    ;; rides under cascade A's :dispatch-id and lands in A's epoch.
    (dispatch-host-frame [:throws/a] :rf/default)
    ;; Dispatch B — second cascade, second issue, second epoch.
    (dispatch-host-frame [:throws/b] :rf/default)
    (let [cascades (sub-xray [:rf.xray/cascades])
          ;; Filter to host frame cascades only; Xray-internal are
          ;; already filtered by the sub.
          host-cascades (filterv #(= :rf/default (:frame %)) cascades)
          ;; Pluck the two dispatch-ids from the cascade list — A
          ;; landed first (lower id), B second.
          a-id (-> host-cascades first :dispatch-id)
          b-id (-> host-cascades second :dispatch-id)
          ;; Pull :exception-message from each row's :raw tags — the
          ;; runtime stamps the ex-info message under :exception-
          ;; message but `short-description` prefers `:reason` (which
          ;; is the generic "Event handler threw."). We descend into
          ;; :raw to distinguish A from B.
          row-msg-set (fn [feed]
                        (into #{}
                              (map #(get-in % [:raw :tags :exception-message]))
                              (:issues feed)))]
      (is (= 2 (count host-cascades))
          "test setup: should have two host cascades (one per throw)")
      (is (some? a-id) "cascade A has no :dispatch-id")
      (is (some? b-id) "cascade B has no :dispatch-id")
      (is (not= a-id b-id) "test setup: cascades should have distinct dispatch-ids")
      ;; Focus cascade A → assert Issues feed scoped to A's epoch only.
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/focus-event a-id :rf/default]))
      (let [feed-a (sub-xray [:rf.xray/issues-ribbon])
            msgs   (row-msg-set feed-a)]
        (is (= #{"throw-a"} msgs)
            (str "Issues panel did not scope to focused epoch (cascade A) — "
                 "rf2-1p1j4 / rf2-jio48 regression. messages: "
                 (pr-str msgs))))
      ;; Flip focus to cascade B → assert the feed flips with it.
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/focus-event b-id :rf/default]))
      (let [feed-b (sub-xray [:rf.xray/issues-ribbon])
            msgs   (row-msg-set feed-b)]
        (is (= #{"throw-b"} msgs)
            (str "Issues panel did not re-scope on focus flip — "
                 "rf2-1p1j4 / rf2-jio48 regression. messages: "
                 (pr-str msgs)))))))

(deftest rf2-1p1j4-issues-panel-scoped-on-multi-frame-initial-mount
  (testing "multi-frame variant: when an :above throw and a :below
  throw both land in the trace bus PRE-mount, then Xray mounts with
  `:above` as the seed frame, the Issues panel MUST surface only
  the :above throw's issue — not the :below one. Pre-rf2-ulpp8 the
  composer's head walk picked the global most-recent cascade (which
  could be :below's), and the Issues sub scoped to the wrong frame's
  issue on first paint — the live-observed shape of rf2-1p1j4.

  rf2-jio48 — panel is now focused-epoch-scoped (spec/021 §8); the
  assertion shape changed from a `:dispatch-id` set to a description-
  substring set since rows no longer carry `:dispatch-id` (the focused
  epoch IS the scope)."
    (install-xray-handlers-and-collector!)
    (frame/reg-frame frame-above {})
    (frame/reg-frame frame-below {})
    (install-throw-handlers!)
    ;; Dispatch :above first, :below second — :below is "head" by the
    ;; global ordering and would have been auto-focused pre-fix.
    (rf/dispatch-sync [:throws/a] {:frame frame-above})
    (rf/dispatch-sync [:throws/b] {:frame frame-below})
    (mount-xray-with-target! frame-above)
    (let [cascades        (sub-xray [:rf.xray/cascades])
          above-cascades  (filterv #(= frame-above (:frame %)) cascades)
          above-id        (-> above-cascades first :dispatch-id)
          focus-slot      (sub-xray [:rf.xray/focus-slot])
          focus           (sub-xray [:rf.xray/focus])
          feed            (sub-xray [:rf.xray/issues-ribbon])
          msgs            (into #{}
                                (map #(get-in % [:raw :tags :exception-message]))
                                (:issues feed))]
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
      (is (= #{"throw-a"} msgs)
          (str "Issues panel did not surface :above's exception on initial "
               "mount when :above was the seed frame — rf2-1p1j4 / "
               "rf2-jio48 / rf2-ulpp8 regression. messages: "
               (pr-str msgs))))))

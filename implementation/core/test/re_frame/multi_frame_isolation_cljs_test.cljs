(ns re-frame.multi-frame-isolation-cljs-test
  "Multi-frame isolation contract — node-CLJS port of the
  `tools/causa/testbeds/parallel_frames` Playwright spec (rf2-lcg1z,
  Wave 2 of the Playwright→CLJS migration rf2-tglku).

  The parallel-frames testbed mounts the SAME app code path in TWO
  frames on ONE page (`:above` and `:below`) with zero cross-frame
  coupling. The browser smoke walked through counter / clock-tick
  isolation, machine-driven HTTP-mock fan-out, and the Causa target-
  frame round-trip — 27 assertions total, 23 of which are pure data-
  layer contracts that need no browser.

  Per Spec 002 §Per-instance frames + Spec 006 §The cache is held
  inside the frame container, frames are isolated reactive contexts.
  This test pins the contract the Playwright spec exercised:

    1. Each frame carries its own app-db; writes to one don't bleed
       into the other.
    2. Each frame's subs read only that frame's app-db.
    3. Events dispatched with `:frame :above` fire handlers against
       `:above`'s app-db only; `:below` stays untouched, and vice
       versa.
    4. Cross-frame sub computation is REJECTED by the
       `with-frame`-scoped subscribe — a sub running in `:above` cannot
       reach `:below`'s app-db (per
       [[feedback_frames_are_isolated_contexts]] — frames are isolated
       contexts; cross-frame sub computation is an anti-pattern). The
       only correct cross-frame read is the explicit framework API
       `rf/get-frame-db` (used by Causa's panel layer, NOT by user
       subs).
    5. Frames can be destroyed independently — destroying `:below`
       leaves `:above`'s app-db / sub-cache / handler resolution
       intact.

  ## Out of scope here (covered elsewhere)

  - Causa-side `:rf.causa/set-target-frame` round-trip + L2 filtering
    on multi-frame mount — covered by
    `tools/causa/test/.../panels_e2e/parallel_frames_e2e_cljs_test.cljs`
    (rf2-ulpp8 / rf2-1p1j4).
  - Cross-frame fan-out via fx with `:frame` opts — covered by
    `tools/causa/test/.../panels_e2e/multi_frame_isolation_e2e_cljs_test.cljs`
    (rf2-83d4x cross-frame routing class).
  - State-machine + mock-fetch closure over originating frame —
    covered by the machines artefact's per-frame machine-state tests
    plus the cross-bump fan-out above. The parallel-frames Playwright
    spec's title-flow assertions exercised the SAME machine-fx-on-
    frame contract those tests already cover; no need to re-stage
    `:title/flow` here.

  The frame ids `:above` / `:below` match the parallel-frames
  testbed's id-prefix convention (Spec Conventions §Feature
  modularity), keeping the contract surface visually identifiable
  with the Causa-displayable showcase that still ships at
  `tools/causa/testbeds/parallel_frames/`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

;; ---- Frame ids match the parallel-frames testbed ---------------------------

(def ^:private frame-above :above)
(def ^:private frame-below :below)

;; ---- Handler registry — registered ONCE, shared across both frames --------
;;
;; Per Spec 002 §Frames: handlers / subs live in the global registrar;
;; the frame envelope's `:frame` opt picks the app-db they resolve
;; against on dispatch. The contract under test is that handlers
;; registered once produce per-frame state evolution.

(defn- install-handlers! []
  ;; `:initialise` seeds the per-frame counter + tick slots so the
  ;; first sub read does not return nil.
  (rf/reg-event-db ::initialise
    (fn [_db _ev] {:counter 0 :ticks 0}))

  ;; Counter handlers (Spec 002 §Per-instance frames — same handler,
  ;; per-frame state).
  (rf/reg-event-db ::counter-inc
    (fn [db _ev] (update db :counter (fnil inc 0))))

  ;; Clock-tick handler (rf2-gxgmt — on-demand parallel-frames Tick
  ;; button) — same shape as counter, different slot. Mirrors the
  ;; testbed's per-frame tick semantics without the dispatch chain.
  (rf/reg-event-db ::clock-tick
    (fn [db _ev] (update db :ticks (fnil inc 0))))

  (rf/reg-sub ::counter (fn [db _] (:counter db)))
  (rf/reg-sub ::ticks   (fn [db _] (:ticks db))))

(defn- seed-frames!
  "Register `:above` + `:below` and dispatch the `:initialise` event
  against each so their app-dbs land on the canonical zero shape
  before any test-event fires. Mirrors the parallel-frames testbed's
  `(reg-frame :above {:on-create [::initialise]})` mount path."
  []
  (frame/reg-frame frame-above {})
  (frame/reg-frame frame-below {})
  (rf/dispatch-sync [::initialise] {:frame frame-above})
  (rf/dispatch-sync [::initialise] {:frame frame-below}))

(defn- sub-in
  "Subscribe inside `frame-id` and dereference — always returns a
  value, never a Reaction."
  [frame-id query]
  (rf/with-frame frame-id @(rf/subscribe query)))

;; ---- 1. Two frames mount; both carry isolated initial app-db --------------

(deftest two-frames-mount-with-isolated-initial-state
  (testing "after seed both frames carry {:counter 0 :ticks 0}; each is its own app-db value"
    (install-handlers!)
    (seed-frames!)
    (let [above-db (rf/get-frame-db frame-above)
          below-db (rf/get-frame-db frame-below)]
      (is (= {:counter 0 :ticks 0} above-db)
          ":above carries the seeded shape")
      (is (= {:counter 0 :ticks 0} below-db)
          ":below carries the seeded shape")
      ;; The two app-db values are equal-but-distinct — `:above` and
      ;; `:below` have their OWN containers per Spec 002. Identity
      ;; over per-frame `:app-db` records is the cleanest pin on the
      ;; isolation guarantee.
      (is (not (identical? (:app-db (frame/frame frame-above))
                           (:app-db (frame/frame frame-below))))
          ":above and :below must not share the same app-db container"))))

;; ---- 2. Counter isolation (Playwright assertions 9, 10, 22, 23) -----------

(deftest counter-dispatched-on-one-frame-does-not-bleed-into-the-other
  (testing "three ::counter-inc on :above + one on :below leaves above=3, below=1"
    (install-handlers!)
    (seed-frames!)
    ;; Three increments on :above.
    (dotimes [_ 3]
      (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
    (is (= 3 (:counter (rf/get-frame-db frame-above)))
        ":above's counter advanced to 3 after three ::counter-inc dispatches")
    (is (= 0 (:counter (rf/get-frame-db frame-below)))
        "ISOLATION VIOLATION — :below's counter changed despite no ::counter-inc against it")
    ;; One increment on :below.
    (rf/dispatch-sync [::counter-inc] {:frame frame-below})
    (is (= 3 (:counter (rf/get-frame-db frame-above)))
        ":above stays at 3 after the :below dispatch (no cross-frame bleed)")
    (is (= 1 (:counter (rf/get-frame-db frame-below)))
        ":below advanced to 1 after its single ::counter-inc")))

;; ---- 3. Clock-tick isolation (Playwright assertions 11, 12) ---------------

(deftest clock-tick-on-one-frame-does-not-bleed-into-the-other
  (testing "two ::clock-tick on :above + one on :below leaves above=2, below=1"
    (install-handlers!)
    (seed-frames!)
    (rf/dispatch-sync [::clock-tick] {:frame frame-above})
    (rf/dispatch-sync [::clock-tick] {:frame frame-above})
    (is (= 2 (:ticks (rf/get-frame-db frame-above)))
        ":above ticked twice")
    (is (= 0 (:ticks (rf/get-frame-db frame-below)))
        "ISOLATION VIOLATION — :below's tick counter changed without a dispatch")
    (rf/dispatch-sync [::clock-tick] {:frame frame-below})
    (is (= 2 (:ticks (rf/get-frame-db frame-above)))
        ":above's tick count stays at 2 (no fan-in from :below's tick)")
    (is (= 1 (:ticks (rf/get-frame-db frame-below)))
        ":below ticked once")))

;; ---- 4. Subs scoped to the frame they run inside --------------------------
;;
;; Per Spec 006 §Per-frame sub-cache + Spec 002 §View ergonomics, a
;; sub running under `rf/with-frame :above` sees `:above`'s app-db.
;; Switching the frame switches the lens. The same sub keyword
;; resolves to two distinct values when invoked under the two frames.

(deftest subs-see-only-the-frame-they-run-inside
  (testing "::counter sub returns 3 under :above-frame and 1 under :below-frame"
    (install-handlers!)
    (seed-frames!)
    (dotimes [_ 3] (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
    (rf/dispatch-sync [::counter-inc] {:frame frame-below})
    (is (= 3 (sub-in frame-above [::counter]))
        ":above's ::counter sub returns its own counter (3)")
    (is (= 1 (sub-in frame-below [::counter]))
        ":below's ::counter sub returns its own counter (1) — NOT :above's value")
    ;; Same ::ticks sub, two frames, both zero (initial). Pins the
    ;; sub-lens-follows-frame contract on the un-driven axis too.
    (is (= 0 (sub-in frame-above [::ticks]))
        ":above's ::ticks sub returns 0 — not :below's value")
    (is (= 0 (sub-in frame-below [::ticks]))
        ":below's ::ticks sub returns 0 — independent of :above's counter")))

;; ---- 5. Cross-frame sub computation is rejected by `with-frame` -----------
;;
;; Per feedback_frames_are_isolated_contexts — subs MUST NOT reach
;; into another frame's app-db. The runtime contract is that
;; `with-frame` is the ONLY scoping affordance for subs; there is no
;; (sub :other-frame [...]) user-API. Cross-frame reads must go
;; through the framework's explicit `rf/get-frame-db` (used by Causa,
;; not by user subs).
;;
;; The negative pin here: a sub running under :above does NOT see
;; :below's data. The previous test (#4) showed the positive lens-
;; follows-frame behaviour; this test shows the absence-of-leakage in
;; both directions at once and documents `rf/get-frame-db` as the
;; only correct cross-frame read.

(deftest no-cross-frame-sub-leakage-and-get-frame-db-is-the-only-read
  (testing "subs scope to their frame; rf/get-frame-db is the only legitimate cross-frame read"
    (install-handlers!)
    (seed-frames!)
    ;; Diverge the two frames so any leak would show up as the wrong
    ;; number.
    (dotimes [_ 5] (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
    (dotimes [_ 2] (rf/dispatch-sync [::counter-inc] {:frame frame-below}))
    ;; Subs scope to their frame — :above sees 5, :below sees 2.
    (is (= 5 (sub-in frame-above [::counter])))
    (is (= 2 (sub-in frame-below [::counter])))
    ;; rf/get-frame-db (the explicit framework API) is the only
    ;; cross-frame read; it returns the requested frame's app-db
    ;; value REGARDLESS of which frame the caller is "inside".
    (is (= 5 (:counter (rf/get-frame-db frame-above)))
        "rf/get-frame-db :above returns :above's app-db")
    (is (= 2 (:counter (rf/get-frame-db frame-below)))
        "rf/get-frame-db :below returns :below's app-db")
    ;; And running rf/get-frame-db from inside one frame returns the
    ;; OTHER frame's value — proving the API is frame-id-keyed, not
    ;; ambient-frame-keyed. (This is the Causa panel-layer's read
    ;; path.)
    (rf/with-frame frame-above
      (is (= 2 (:counter (rf/get-frame-db frame-below)))
          "rf/get-frame-db is frame-id-keyed — works from any ambient frame"))))

;; ---- 6. Frames can be destroyed independently -----------------------------
;;
;; Per Spec 002 §Destroy, destroying one frame removes only that
;; frame from `frame/frames`; other frames keep their app-db, sub-
;; cache, and router atoms intact. The Playwright spec did not
;; exercise destroy (the test runs in a single page lifecycle), but
;; the migration contract is "each frame is its own thing" — destroy
;; isolation IS that contract's natural follow-on.

(deftest destroying-one-frame-leaves-the-other-intact
  (testing "destroy-frame! :below leaves :above's app-db, sub-cache, and dispatch path live"
    (install-handlers!)
    (seed-frames!)
    (dotimes [_ 4] (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
    (dotimes [_ 7] (rf/dispatch-sync [::counter-inc] {:frame frame-below}))
    ;; Capture :above's container identities before destroy.
    (let [above-record-pre (frame/frame frame-above)
          above-app-db-pre (:app-db above-record-pre)
          above-sub-cache  (:sub-cache above-record-pre)]
      ;; Destroy :below.
      (rf/destroy-frame! frame-below)
      ;; :below is gone from the registry.
      (is (nil? (frame/frame frame-below))
          "destroy-frame! removed :below from the registry")
      ;; :above's record survived (same container objects).
      (let [above-record-post (frame/frame frame-above)]
        (is (some? above-record-post)
            ":above is still registered after :below's destroy")
        (is (identical? above-app-db-pre (:app-db above-record-post))
            ":above's app-db container is unchanged")
        (is (identical? above-sub-cache (:sub-cache above-record-post))
            ":above's sub-cache atom is unchanged"))
      ;; :above's app-db value is unchanged.
      (is (= 4 (:counter (rf/get-frame-db frame-above)))
          ":above's counter still reads 4")
      ;; :above's sub still resolves cleanly.
      (is (= 4 (sub-in frame-above [::counter]))
          ":above's ::counter sub still resolves to 4")
      ;; A new dispatch into :above still routes correctly post-destroy.
      (rf/dispatch-sync [::counter-inc] {:frame frame-above})
      (is (= 5 (:counter (rf/get-frame-db frame-above)))
          ":above's counter advanced to 5 after a post-destroy dispatch"))))

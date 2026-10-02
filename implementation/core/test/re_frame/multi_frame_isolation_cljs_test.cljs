(ns re-frame.multi-frame-isolation-cljs-test
  "Multi-frame isolation contract, pinned on the data layer.

  Two frames (`:above` and `:below`) run the SAME app code path on ONE
  page with zero cross-frame coupling. Counter isolation, per-frame sub
  scoping and independent destroy are pure data-layer
  contracts that need no browser.

  Per Spec 002 §Per-instance frames + Spec 006 §The cache is held
  inside the frame container, frames are isolated reactive contexts.
  This test pins that contract:

    1. Each frame carries its own app-db; writes to one don't bleed
       into the other.
    2. Each frame's subs read only that frame's app-db.
    3. Events dispatched with `:frame :above` fire handlers against
       `:above`'s app-db only; `:below` stays untouched, and vice
       versa.
    4. Cross-frame sub computation is REJECTED by the
       `with-frame`-scoped subscribe — a sub running in `:above` cannot
       reach `:below`'s app-db (frames are isolated
       contexts; cross-frame sub computation is an anti-pattern). The
       only correct cross-frame read is the explicit framework API
       `rf/app-db-value` (used by Xray's panel layer, NOT by user
       subs).
    5. Frames can be destroyed independently — destroying `:below`
       leaves `:above`'s app-db / sub-cache / handler resolution
       intact.

  ## Out of scope here (covered elsewhere)

  - Xray-side `:rf.xray/set-target-frame` round-trip + L2 filtering
    on multi-frame mount — covered by
    `tools/xray/test/.../panels_e2e/parallel_frames_e2e_cljs_test.cljs`.
  - Cross-frame fan-out via fx with `:frame` opts — covered by
    `tools/xray/test/.../panels_e2e/multi_frame_isolation_e2e_cljs_test.cljs`
    (the cross-frame routing class).
  - State-machine + mock-fetch closure over originating frame —
    covered by the machines artefact's per-frame machine-state tests
    plus the cross-bump fan-out above; there is no need to stage a
    machine flow here.

  The frame ids `:above` / `:below` match the two-frame isolation
  testbed's (`tools/xray/testbeds/two_frame_isolation/`), keeping the
  contract surface visually identifiable with that Xray-displayable
  showcase."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- Frame ids match the two-frame isolation testbed ----------------------

(def ^:private frame-above :above)
(def ^:private frame-below :below)

;; ---- Handler registry — registered ONCE, shared across both frames --------
;;
;; Per Spec 002 §Frames: handlers / subs live in the global registrar;
;; the frame envelope's `:frame` opt picks the app-db they resolve
;; against on dispatch. The contract under test is that handlers
;; registered once produce per-frame state evolution.

(defn- install-handlers! []
  ;; `:initialise` seeds the per-frame counter slot so the first sub read
  ;; does not return nil.
  (rf/reg-event ::initialise
    (fn [{:keys [db]} _ev] {:db {:counter 0}}))

  ;; Counter handlers (Spec 002 §Per-instance frames — same handler,
  ;; per-frame state).
  (rf/reg-event ::counter-inc
    (fn [{:keys [db]} _ev] {:db (update db :counter (fnil inc 0))}))

  (rf/reg-sub ::counter (fn [db _] (:counter db))))

(defn- seed-frames!
  "Register `:above` + `:below` and dispatch the `:initialise` event
  against each so their app-dbs land on the canonical zero shape
  before any test-event fires — the same end state as
  `(make-frame {:id :above :initial-events [[::initialise]]})`."
  []
  (rf/make-frame {:id frame-above})
  (rf/make-frame {:id frame-below})
  (rf/dispatch-sync [::initialise] {:frame frame-above})
  (rf/dispatch-sync [::initialise] {:frame frame-below}))

(defn- sub-in
  "Subscribe inside `frame-id` and dereference — always returns a
  value, never a Reaction."
  [frame-id query]
  (rf/with-frame frame-id @(rf/subscribe query)))

;; ---- 1. Counter isolation -------------------------------------------------

(deftest counter-dispatched-on-one-frame-does-not-bleed-into-the-other
  (testing "three ::counter-inc on :above + one on :below leaves above=3, below=1"
    (install-handlers!)
    (seed-frames!)
    ;; Three increments on :above.
    (dotimes [_ 3]
      (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
    (is (= 3 (:counter (rf/app-db-value frame-above)))
        ":above's counter advanced to 3 after three ::counter-inc dispatches")
    (is (= 0 (:counter (rf/app-db-value frame-below)))
        "ISOLATION VIOLATION — :below's counter changed despite no ::counter-inc against it")
    ;; One increment on :below.
    (rf/dispatch-sync [::counter-inc] {:frame frame-below})
    (is (= 3 (:counter (rf/app-db-value frame-above)))
        ":above stays at 3 after the :below dispatch (no cross-frame bleed)")
    (is (= 1 (:counter (rf/app-db-value frame-below)))
        ":below advanced to 1 after its single ::counter-inc")))

;; ---- 2. Cross-frame sub computation is rejected by `with-frame` -----------
;;
;; Frames are isolated contexts — subs MUST NOT reach
;; into another frame's app-db. The runtime contract is that
;; `with-frame` is the ONLY scoping affordance for subs; there is no
;; (sub :other-frame [...]) user-API. Cross-frame reads must go
;; through the framework's explicit `rf/app-db-value` (used by Xray,
;; not by user subs).
;;
;; Per Spec 006 §Per-frame sub-cache + Spec 002 §View ergonomics, a sub
;; running under `rf/with-frame :above` sees `:above`'s app-db and NOT
;; `:below`'s: the same sub keyword resolves to two distinct values under
;; the two frames. This test pins that in both directions at once and
;; documents `rf/app-db-value` as the only correct cross-frame read.

(deftest no-cross-frame-sub-leakage-and-app-db-value-is-the-only-read
  (testing "subs scope to their frame; rf/app-db-value is the only legitimate cross-frame read"
    (install-handlers!)
    (seed-frames!)
    ;; Diverge the two frames so any leak would show up as the wrong
    ;; number.
    (dotimes [_ 5] (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
    (dotimes [_ 2] (rf/dispatch-sync [::counter-inc] {:frame frame-below}))
    ;; Subs scope to their frame — :above sees 5, :below sees 2.
    (is (= 5 (sub-in frame-above [::counter])))
    (is (= 2 (sub-in frame-below [::counter])))
    ;; rf/app-db-value (the explicit framework API) is the only
    ;; cross-frame read; it returns the requested frame's app-db
    ;; value REGARDLESS of which frame the caller is "inside".
    (is (= 5 (:counter (rf/app-db-value frame-above)))
        "rf/app-db-value :above returns :above's app-db")
    (is (= 2 (:counter (rf/app-db-value frame-below)))
        "rf/app-db-value :below returns :below's app-db")
    ;; And running rf/app-db-value from inside one frame returns the
    ;; OTHER frame's value — proving the API is frame-id-keyed, not
    ;; ambient-frame-keyed. (This is the Xray panel-layer's read
    ;; path.)
    (rf/with-frame frame-above
      (is (= 2 (:counter (rf/app-db-value frame-below)))
          "rf/app-db-value is frame-id-keyed — works from any ambient frame"))))

;; ---- 3. Frames can be destroyed independently -----------------------------
;;
;; Per Spec 002 §Destroy, destroying one frame removes only that
;; frame from `rf.frame/frames`; other frames keep their app-db, sub-
;; cache, and router atoms intact. The contract is "each frame is its
;; own thing" — destroy isolation IS that contract's natural follow-on.

(deftest destroying-one-frame-leaves-the-other-intact
  (testing "destroy-frame! :below leaves :above's app-db, sub-cache, and dispatch path live"
    (install-handlers!)
    (seed-frames!)
    (dotimes [_ 4] (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
    (dotimes [_ 7] (rf/dispatch-sync [::counter-inc] {:frame frame-below}))
    ;; Capture :above's container identities before destroy.
    (let [above-record-pre (rf.frame/frame frame-above)
          above-app-db-pre (:app-db above-record-pre)
          above-sub-cache  (:sub-cache above-record-pre)]
      ;; Destroy :below.
      (rf/destroy-frame! frame-below)
      ;; :below is gone from the registry.
      (is (nil? (rf.frame/frame frame-below))
          "destroy-frame! removed :below from the registry")
      ;; :above's record survived (same container objects).
      (let [above-record-post (rf.frame/frame frame-above)]
        (is (some? above-record-post)
            ":above is still registered after :below's destroy")
        (is (identical? above-app-db-pre (:app-db above-record-post))
            ":above's app-db container is unchanged")
        (is (identical? above-sub-cache (:sub-cache above-record-post))
            ":above's sub-cache atom is unchanged"))
      ;; :above's app-db value is unchanged.
      (is (= 4 (:counter (rf/app-db-value frame-above)))
          ":above's counter still reads 4")
      ;; :above's sub still resolves cleanly.
      (is (= 4 (sub-in frame-above [::counter]))
          ":above's ::counter sub still resolves to 4")
      ;; A new dispatch into :above still routes correctly post-destroy.
      (rf/dispatch-sync [::counter-inc] {:frame frame-above})
      (is (= 5 (:counter (rf/app-db-value frame-above)))
          ":above's counter advanced to 5 after a post-destroy dispatch"))))

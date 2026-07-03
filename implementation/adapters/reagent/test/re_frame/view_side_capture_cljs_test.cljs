(ns re-frame.view-side-capture-cljs-test
  "Per rf2-9hoos — the three view-side captures phase-A adds to the
  view-render instrumentation so Xray's Views table (phase-B) can show,
  per view, its mount/rerender/unmount ACTION and the per-view REASON it
  rendered:

    1. view→sub edges — `:rf.view/rendered` carries `:rf.view/deref-subs`, the
       vector of subscription query-vectors THIS view deref'd during the
       render (its own read-set), distinct from the cascade-wide
       `:rf.view/cause-subs` (which over-reports).
    2. mount-vs-rerender — `:rf.view/rendered` carries `:rf.view/mount?`, `true`
       on a component instance's first render and `false` thereafter.
    3. unmount — a NEW `:rf.view/unmounted` op fires when a registered
       view instance tears down, carrying `:rf.view/id` + `:frame` (+ the
       `:rf.view/render-key` instance tuple).

  Every capture gates behind `interop/debug-enabled?`; production absence
  is pinned by the elision probe (`npm run test:elision`).

  Exercises the Reagent adapter side. The emit sites are the
  substrate-agnostic `views.cljs` frame-aware-view wrapper, so the shape
  is uniform across adapters; the unmount mechanism (per-render-instance
  reaction dispose) is tested here against the Reagent reaction primitive
  available headlessly (no DOM) — the same path
  `re-frame.substrate.spine-dispose-cljs-test` uses to exercise dispose
  without JSDOM/Playwright.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views :as views]
            [re-frame.epoch]) ;; load so :epoch/run-cause hook is bound
  (:require-macros [re-frame.core :refer [reg-view]]
                   [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- in-op-set [op-set]
  (fn [ev] (contains? op-set (:operation ev))))

;; ===========================================================================
;; 1. view→sub edges — :rf.view/deref-subs
;; ===========================================================================

(deftest rf-view-rendered-carries-deref-subs-the-view-reads
  (testing ":rf.view/rendered carries :rf.view/deref-subs — the query-vectors THIS
   view deref'd during its render (its OWN read-set), so a consumer can
   filter the cascade-wide changed subs down to this view's reasons"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-sub :rf2-9hoos/a (fn [_ _] 1))
      (rf/reg-sub :rf2-9hoos/b (fn [_ _] 2))
      (rf/reg-view ^{:rf/id :rf2-9hoos/reader} reader-view []
        @(rf/subscribe [:rf2-9hoos/a])
        @(rf/subscribe [:rf2-9hoos/b])
        [:span "ok"])
      ((rf/view :rf2-9hoos/reader))
      (let [ev   (first (:rf.view/rendered @observed))
            subs (get-in ev [:tags :rf.view/deref-subs])]
        (is (some? ev) "an :rf.view/rendered event was emitted")
        (is (vector? subs) ":rf.view/deref-subs is a vector")
        (is (= #{[:rf2-9hoos/a] [:rf2-9hoos/b]} (set subs))
            ":rf.view/deref-subs lists exactly the two subs the view deref'd")))))

(deftest deref-subs-is-per-view-not-cascade-wide
  (testing ":rf.view/deref-subs is scoped to the view that read the sub — a sub a
   sibling view (or the handler) reads does NOT bleed into this view's
   read-set, unlike the cascade-wide :rf.view/cause-subs"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-sub :rf2-9hoos/mine    (fn [_ _] :mine))
      (rf/reg-sub :rf2-9hoos/sibling (fn [_ _] :sibling))
      (rf/reg-view ^{:rf/id :rf2-9hoos/only-mine} only-mine-view []
        @(rf/subscribe [:rf2-9hoos/mine])
        [:span "x"])
      ;; A different view reads the sibling sub.
      (rf/reg-view ^{:rf/id :rf2-9hoos/only-sibling} only-sibling-view []
        @(rf/subscribe [:rf2-9hoos/sibling])
        [:span "y"])
      ((rf/view :rf2-9hoos/only-mine))
      ((rf/view :rf2-9hoos/only-sibling))
      (let [evs (:rf.view/rendered @observed)
            mine-ev (first (filter #(= :rf2-9hoos/only-mine
                                       (get-in % [:tags :rf.view/id])) evs))
            sib-ev  (first (filter #(= :rf2-9hoos/only-sibling
                                       (get-in % [:tags :rf.view/id])) evs))]
        (is (= [[:rf2-9hoos/mine]] (get-in mine-ev [:tags :rf.view/deref-subs]))
            "only-mine's read-set is exactly its own sub")
        (is (= [[:rf2-9hoos/sibling]] (get-in sib-ev [:tags :rf.view/deref-subs]))
            "only-sibling's read-set is exactly its own sub — no bleed")))))

(deftest structural-render-has-no-deref-subs
  (testing "a view that reads no subs (a pure structural render) omits
   :rf.view/deref-subs — the consumer reads its absence as 'parent re-render',
   the unnamed structural reason"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-view ^{:rf/id :rf2-9hoos/structural} structural-view []
        [:span "no subs"])
      ((rf/view :rf2-9hoos/structural))
      (let [ev (first (:rf.view/rendered @observed))]
        (is (some? ev))
        (is (not (contains? (:tags ev) :rf.view/deref-subs))
            ":rf.view/deref-subs omitted when the view derefs no subs")))))

;; ===========================================================================
;; 2. mount-vs-rerender — :rf.view/mount?
;; ===========================================================================

(deftest rf-view-rendered-carries-mount-flag
  (testing ":rf.view/rendered carries a boolean :rf.view/mount? on every emit"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-view ^{:rf/id :rf2-9hoos/flagged} flagged-view []
        [:span "x"])
      ((rf/view :rf2-9hoos/flagged))
      (let [ev (first (:rf.view/rendered @observed))]
        (is (some? ev))
        (is (contains? (:tags ev) :rf.view/mount?) ":rf.view/mount? slot is present")
        (is (boolean? (get-in ev [:tags :rf.view/mount?])) ":rf.view/mount? is a boolean")))))

(deftest first-render-of-an-instance-is-mount-rest-are-rerenders
  (testing "first-render?! returns true the first time a render-key is
   seen and false thereafter — the mount-vs-rerender discriminator that
   backs :rf.view/mount? (a real Reagent instance reuses its render-key across
   re-renders, so the same key recurs)"
    (let [rk [:rf2-9hoos/instance 42]]
      (is (true? (views/first-render?! rk))
          "first sighting of the render-key → mount")
      (is (false? (views/first-render?! rk))
          "second sighting (a re-render of the same instance) → rerender")
      (is (false? (views/first-render?! rk))
          "third sighting → still a rerender"))))

(deftest distinct-instances-each-mount
  (testing "two distinct component instances (distinct instance-tokens)
   each report :rf.view/mount? true on their first render — headless direct
   invocation mints a fresh token per call, mirroring per-mount-fresh"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-view ^{:rf/id :rf2-9hoos/two-mounts} two-mounts-view []
        [:span "x"])
      (let [render (rf/view :rf2-9hoos/two-mounts)]
        (render)   ;; instance A — fresh token → mount
        (render))  ;; instance B — fresh token → mount
      (let [evs (:rf.view/rendered @observed)]
        (is (= 2 (count evs)))
        (is (every? #(true? (get-in % [:tags :rf.view/mount?])) evs)
            "both fresh instances report :rf.view/mount? true")))))

;; ===========================================================================
;; 3. unmount — :rf.view/unmounted
;; ===========================================================================

(deftest emit-view-unmounted-fires-the-op-with-view-id-and-frame
  (testing "emit-view-unmounted! emits :rf.view/unmounted carrying
   :rf.view/id, :frame and the :rf.view/render-key instance tuple"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/unmounted})
                                     :shape :by-op}]
      (views/emit-view-unmounted! :rf2-9hoos/torn [:rf2-9hoos/torn 7] :rf/default)
      (let [ev (first (:rf.view/unmounted @observed))
            t  (:tags ev)]
        (is (some? ev) "an :rf.view/unmounted event was emitted")
        (is (= :rf2-9hoos/torn (:rf.view/id t)) ":rf.view/id present")
        (is (= :rf/default (:frame t)) ":frame present")
        (is (= [:rf2-9hoos/torn 7] (:rf.view/render-key t)) ":rf.view/render-key tuple present")))))

(deftest install-unmount-hook-fires-emit-on-reaction-dispose
  (testing "install-unmount-hook! builds a lifecycle reaction whose
   disposal fires :rf.view/unmounted — the per-render-instance
   reaction-dispose mechanism that, on a real Reagent mount, the
   component's render reaction disposes on componentWillUnmount. Driven
   here by disposing the reaction directly (no DOM), mirroring
   spine-dispose-cljs-test."
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/unmounted})
                                     :shape :by-op}]
      (let [rea (views/install-unmount-hook! :rf2-9hoos/lifecycle
                                             [:rf2-9hoos/lifecycle 1]
                                             :rf/default)]
        (is (some? rea)
            "a lifecycle reaction is created under the Reagent reaction primitive")
        (is (empty? (:rf.view/unmounted @observed))
            "no unmount emit before disposal")
        ;; Deref once so the reaction is realised, then dispose — the
        ;; teardown signal a real componentWillUnmount sends.
        @rea
        (interop/dispose! rea)
        (let [ev (first (:rf.view/unmounted @observed))]
          (is (some? ev) ":rf.view/unmounted fired on reaction disposal")
          (is (= :rf2-9hoos/lifecycle (get-in ev [:tags :rf.view/id])))
          (is (= [:rf2-9hoos/lifecycle 1] (get-in ev [:tags :rf.view/render-key]))))))))

;; ===========================================================================
;; co-existence — the new fields ride alongside the existing rf2-25zo2 shape
;; ===========================================================================

(deftest existing-rf-view-rendered-tags-still-present
  (testing "the rf2-9hoos additions are additive — :rf.view/id, :frame,
   :rf.view/render-key (rf2-25zo2) still ride every :rf.view/rendered emit
   alongside the new :rf.view/mount? / :rf.view/deref-subs"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-view ^{:rf/id :rf2-9hoos/coexist} coexist-view []
        [:span "x"])
      ((rf/view :rf2-9hoos/coexist))
      (let [ev (first (:rf.view/rendered @observed))
            t  (:tags ev)]
        (is (= :rf2-9hoos/coexist (:rf.view/id t)))
        (is (some? (:frame t)))
        (is (vector? (:rf.view/render-key t)))
        (is (contains? t :rf.view/mount?))))))

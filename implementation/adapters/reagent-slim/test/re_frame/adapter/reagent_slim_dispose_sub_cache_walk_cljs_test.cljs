(ns re-frame.adapter.reagent-slim-dispose-sub-cache-walk-cljs-test
  "Pins the reagent-slim adapter's `dispose-adapter!` four-MUST list
  item 1 (rf2-jcjul + Spec 006 §Adapter disposal lifecycle): cancel
  all in-flight reactive subscriptions by walking every live frame's
  per-frame sub-cache and disposing each cached Reaction.

  Mirrors the Reagent adapter's
  `re-frame.dispose-adapter-sub-cache-walk-cljs-test` so the cross-
  adapter parity from rf2-jcjul stays pinned at all three substrates'
  user-facing surfaces. The unit-tier coverage of the underlying
  `spine/dispose-frame-sub-caches!` helper lives in
  `re-frame.substrate.spine-dispose-cljs-test`; this file covers the
  through-the-slim-adapter shape.

  Pre-rf2-jcjul this adapter's `dispose-adapter!` was a no-op
  (`nil` return; comment claimed 'Reactions GC themselves') — but
  that's the headless / test-fixture path the spec calls out as the
  exact reason for the walk: no component unmount fires before the
  adapter goes away, so the per-frame sub-cache's Reactions stay
  pinned at ref-count 1 forever and the adapter slot can't be reused
  cleanly. The spine-backed walk now covers this.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]))

;; ---- fixture --------------------------------------------------------------
;;
;; Cold-start. The unit under test IS `dispose-adapter!`, so we install
;; the slim adapter ourselves at the top of each test and let the test
;; body call dispose-adapter! to drive the walk. Each test cleans up
;; after itself so a re-run is idempotent.

(defn fresh-slim [test-fn]
  ;; Wipe lifecycle state — adapter slot + disposed breadcrumb +
  ;; frame registry — so the test starts from a never-installed cold
  ;; state. The `reset-lifecycle-state-for-tests!` seam exists for
  ;; exactly this purpose (rf2-6wxys).
  (adapter/reset-lifecycle-state-for-tests!)
  (reset! frame/frames {})
  (rf/init! reagent-slim-adapter/adapter)
  (frame/ensure-default-frame!)
  (test-fn)
  ;; Best-effort post-clean: if the test body left the adapter
  ;; installed, dispose it; if already disposed, the breadcrumb
  ;; lookup makes this a no-op.
  (when (adapter/current-adapter)
    (adapter/dispose-adapter!))
  (reset! frame/frames {})
  (adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each fresh-slim)

;; ---- helpers --------------------------------------------------------------

(defn- cached-reactions-across-all-frames
  "Return a seq of every cached `:reaction` across every live frame's
  sub-cache. Walks `@frame/frames` the same way the adapter's
  `dispose-adapter!` walk does."
  []
  (for [[_ frame-record] @frame/frames
        :let  [cache (:sub-cache frame-record)]
        :when cache
        [_k entry] @cache
        :let  [r (:reaction entry)]
        :when r]
    r))

(defn- sub-cache-counts
  "Return `{frame-id <entry-count>}` for every frame with a sub-cache."
  []
  (into {}
        (for [[fid frame-record] @frame/frames
              :let [cache (:sub-cache frame-record)]
              :when cache]
          [fid (count @cache)])))

;; ---- tests ----------------------------------------------------------------

(deftest dispose-adapter-walks-and-disposes-cached-reactions-across-every-frame
  (testing "after dispose-adapter!, every cached Reaction across every
  live frame is disposed AND every frame's sub-cache atom is empty"
    ;; Set up two frames each with a cached subscription, mirroring the
    ;; counter-with-stories shape (one frame per Story variant).
    (rf/reg-frame :walk/a {})
    (rf/reg-frame :walk/b {})
    (rf/reg-event-db :seed (fn [_ [_ n]] {:n n}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed 1] {:frame :walk/a})
    (rf/dispatch-sync [:seed 2] {:frame :walk/b})

    ;; Materialise + deref so the sub cache holds live Reactions.
    (let [r-a (rf/subscribe :walk/a [:n])
          r-b (rf/subscribe :walk/b [:n])]
      (is (= 1 @r-a))
      (is (= 2 @r-b))

      (let [precount (sub-cache-counts)]
        (is (>= (get precount :walk/a 0) 1)
            "precondition: walk/a's sub-cache holds the [:n] entry")
        (is (>= (get precount :walk/b 0) 1)
            "precondition: walk/b's sub-cache holds the [:n] entry"))

      ;; Snapshot every Reaction across every frame BEFORE dispose so
      ;; we can inspect their disposed? after the walk. (After the walk
      ;; the caches are empty, so we couldn't reach the Reactions
      ;; through the cache anymore.)
      (let [reactions-before (vec (cached-reactions-across-all-frames))]
        (is (>= (count reactions-before) 2)
            "precondition: at least one Reaction per frame is cached")
        (is (every? #(satisfies? ratom/IDisposable %) reactions-before)
            "precondition: snapshotted handles satisfy reagent2's disposal contract")

        (let [disposed (atom #{})]
          (doseq [r reactions-before]
            (ratom/add-on-dispose! r (fn [& _] (swap! disposed conj r))))

          ;; Drive the walk.
          (adapter/dispose-adapter!)

          ;; Invariant 1: every previously-cached Reaction is now
          ;; disposed. Assert through reagent2's public disposal callback
          ;; surface.
          (doseq [r reactions-before]
            (is (contains? @disposed r)
                (str "post-dispose: Reaction " (pr-str r)
                     " on a frame's sub-cache fired its dispose hook")))))

      ;; Invariant 2: every frame's sub-cache atom is empty.
      (doseq [[fid frame-record] @frame/frames
              :let [cache (:sub-cache frame-record)]
              :when cache]
        (is (= {} @cache)
            (str "post-dispose: frame " (pr-str fid)
                 "'s sub-cache atom is empty"))))))

(deftest dispose-adapter-walk-tolerates-an-empty-frames-registry
  (testing "dispose-adapter! on an installed slim adapter with no live
  frames is a no-op (no throw)"
    ;; Pre-dispose: drop every frame, then dispose. This is the post-
    ;; make-reset-runtime-fixture shape: the fixture resets frames BEFORE
    ;; calling dispose-adapter!, so dispose-adapter! sees an empty
    ;; registry.
    (reset! frame/frames {})
    (is (nil? (adapter/dispose-adapter!))
        "dispose-adapter! returns nil with an empty frames registry — no throw")
    (is (true? (adapter/adapter-disposed?))
        "the disposed-adapter breadcrumb is set after the no-op walk")))

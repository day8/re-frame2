(ns re-frame.dispose-adapter-sub-cache-walk-cljs-test
  "Pins the Reagent adapter's `dispose-adapter!` four-MUST list item 1
  (rf2-a47kq + Spec 006 §Adapter disposal lifecycle): cancel all
  in-flight reactive subscriptions by walking every live frame's
  per-frame sub-cache and disposing each cached Reaction.

  The reactive-graph reaping path (Reagent reaps a Reaction once its
  last watcher drops) handles the mounted-component case. This walk
  covers the test-fixture / headless path where no component unmount
  fires before the adapter goes away — pre-rf2-a47kq the walk was a
  no-op and the cached Reactions were leaked across teardown.

  Three observable invariants:

    1. After `dispose-adapter!`, every cached Reaction across every live
       frame's sub-cache reports `disposed? = true` via Reagent's own
       state predicate.
    2. After `dispose-adapter!`, every frame's sub-cache atom is
       empty `{}`.
    3. The walk is best-effort: a throwing per-entry dispose does NOT
       abort the rest of the walk (every other cached Reaction in the
       same cache + every cache in subsequent frames still gets
       disposed and cleared).

  ns ends in -cljs-test so shadow-cljs's `:node-test` build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ---- fixture --------------------------------------------------------------
;;
;; Cold-start. The unit under test IS `dispose-adapter!`, so we install
;; the Reagent adapter ourselves at the top of each test and let the
;; test body call dispose-adapter! to drive the walk. Each test cleans
;; up after itself so a re-run is idempotent.

(defn fresh-reagent [test-fn]
  ;; Wipe lifecycle state — adapter slot + disposed breadcrumb +
  ;; frame registry — so the test starts from a never-installed cold
  ;; state. The `reset-lifecycle-state-for-tests!` seam exists for
  ;; exactly this purpose (rf2-6wxys).
  (adapter/reset-lifecycle-state-for-tests!)
  (reset! frame/frames {})
  (rf/init! reagent-adapter/adapter)
  (frame/ensure-default-frame!)
  (test-fn)
  ;; Best-effort post-clean: if the test body left the adapter
  ;; installed, dispose it; if already disposed, the breadcrumb
  ;; lookup makes this a no-op.
  (when (adapter/current-adapter)
    (adapter/dispose-adapter!))
  (reset! frame/frames {})
  (adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each fresh-reagent)

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
            "precondition: snapshotted handles satisfy Reagent's disposal contract")

        (let [disposed (atom #{})]
          (doseq [r reactions-before]
            (ratom/add-on-dispose! r (fn [& _] (swap! disposed conj r))))

          ;; Drive the walk.
          (adapter/dispose-adapter!)

          ;; Invariant 1: every previously-cached Reaction is now
          ;; disposed. Assert through Reagent's public disposal callback
          ;; surface rather than its private state sentinel.
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

(deftest dispose-adapter-walk-is-best-effort
  (testing "a throwing per-entry dispose does NOT abort the rest of the walk"
    (rf/reg-frame :walk/a {})
    (rf/reg-frame :walk/b {})
    (rf/reg-event-db :seed (fn [_ _] {:n 1}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed] {:frame :walk/a})
    (rf/dispatch-sync [:seed] {:frame :walk/b})

    (let [r-a (rf/subscribe :walk/a [:n])
          r-b (rf/subscribe :walk/b [:n])]
      (is (= 1 @r-a))
      (is (= 1 @r-b))

      ;; Inject a sentinel reaction into walk/a's sub-cache whose
      ;; dispose path throws — mirrors a misbehaving downstream
      ;; (e.g. an on-dispose hook raising). The walk must still drain
      ;; the rest of walk/a's cache AND walk/b's cache.
      (let [cache-a (:sub-cache (frame/frame :walk/a))
            ;; A bare object with no IDispose impl — `interop/dispose!`
            ;; routes through `:adapter/dispose!` (stock Reagent's
            ;; `ratom/dispose!`) which assumes the IDisposable contract
            ;; and throws on a plain map. The walk's per-entry try
            ;; swallows the throw.
            poison-entry {:reaction (js-obj "not" "a reaction")}]
        (swap! cache-a assoc [:poison] poison-entry)

        ;; Also snapshot the real reactions so we can verify they were
        ;; reached.
        (let [reactions-before [r-a r-b]
              disposed         (atom #{})]
          (doseq [r reactions-before]
            (ratom/add-on-dispose! r (fn [& _] (swap! disposed conj r))))

          (adapter/dispose-adapter!)

          (doseq [r reactions-before]
            (is (contains? @disposed r)
                "the walk reached and disposed the real Reaction past the poison entry"))

          (is (= {} @(:sub-cache (frame/frame :walk/a)))
              "walk/a's cache was still cleared despite the throw")
          (is (= {} @(:sub-cache (frame/frame :walk/b)))
              "walk/b's cache was still cleared after the throwing walk/a entry"))))))

(deftest dispose-adapter-walk-tolerates-an-empty-frames-registry
  (testing "dispose-adapter! on an installed Reagent adapter with no live
  frames is a no-op (no throw)"
    ;; Pre-dispose: drop every frame, then dispose. This is the post-
    ;; reset-runtime-fixture-factory shape: the fixture resets frames BEFORE
    ;; calling dispose-adapter!, so dispose-adapter! sees an empty
    ;; registry.
    (reset! frame/frames {})
    (is (nil? (adapter/dispose-adapter!))
        "dispose-adapter! returns nil with an empty frames registry — no throw")
    (is (true? (adapter/adapter-disposed?))
        "the disposed-adapter breadcrumb is set after the no-op walk")))

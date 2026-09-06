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
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

;; ---- fixture --------------------------------------------------------------
;;
;; Cold-start. The unit under test IS `dispose-adapter!`, so we install
;; the slim adapter ourselves at the top of each test and let the test
;; body call dispose-adapter! to drive the walk. Each test cleans up
;; after itself so a re-run is idempotent.

(defn with-fresh-slim-adapter [test-fn]
  ;; Wipe lifecycle state — adapter slot + disposed breadcrumb +
  ;; frame registry — so the test starts from a never-installed cold
  ;; state. The `reset-lifecycle-state-for-tests!` seam exists for
  ;; exactly this purpose (rf2-6wxys).
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (reset! rf.frame/frames {})
  (rf/init! rf.adapter.reagent-slim/adapter)
  (rf.frame/ensure-default-frame!)
  (test-fn)
  ;; Best-effort post-clean: if the test body left the adapter
  ;; installed, dispose it; if already disposed, the breadcrumb
  ;; lookup makes this a no-op.
  (when (rf.substrate.adapter/current-adapter)
    (rf.substrate.adapter/dispose-adapter!))
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each with-fresh-slim-adapter)

;; ---- helpers --------------------------------------------------------------

(defn- cached-reactions-across-all-frames
  "Return a seq of every cached `:reaction` across every live frame's
  sub-cache. Walks `@frame/frames` the same way the adapter's
  `dispose-adapter!` walk does."
  []
  (for [[_ frame-record] @rf.frame/frames
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
        (for [[fid frame-record] @rf.frame/frames
              :let [cache (:sub-cache frame-record)]
              :when cache]
          [fid (count @cache)])))

;; ---- tests ----------------------------------------------------------------

(deftest dispose-adapter-walks-and-disposes-cached-reactions-across-every-frame
  (testing "after dispose-adapter!, every cached Reaction across every
  live frame is disposed AND every frame's sub-cache atom is empty"
    ;; Set up two frames each with a cached subscription, mirroring the
    ;; counter-with-stories shape (one frame per Story variant).
    (rf/make-frame {:id :walk/a})
    (rf/make-frame {:id :walk/b})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed 1] {:frame :walk/a})
    (rf/dispatch-sync [:seed 2] {:frame :walk/b})

    ;; Materialise + deref so the sub cache holds live Reactions.
    (let [r-a (rf/subscribe [:n] {:frame :walk/a})
          r-b (rf/subscribe [:n] {:frame :walk/b})]
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
          (rf.substrate.adapter/dispose-adapter!)

          ;; Invariant 1: every previously-cached Reaction is now
          ;; disposed. Assert through reagent2's public disposal callback
          ;; surface.
          (doseq [r reactions-before]
            (is (contains? @disposed r)
                (str "post-dispose: Reaction " (pr-str r)
                     " on a frame's sub-cache fired its dispose hook")))))

      ;; Invariant 2: every frame's sub-cache atom is empty.
      (doseq [[fid frame-record] @rf.frame/frames
              :let [cache (:sub-cache frame-record)]
              :when cache]
        (is (= {} @cache)
            (str "post-dispose: frame " (pr-str fid)
                 "'s sub-cache atom is empty"))))))

;; ---- the poison entry has to be one the disposer actually CALLS ------------
;;
;; rf2-vy0a. Until this bead the poison entry here was a bare
;; `(js-obj "not" "a reaction")`, and it never threw. The ratom family's
;; claimed-generation disposer (`spine/make-ratom-dispose-dispatch`)
;; dispatches `re-frame.disposable/IDisposable` → the substrate's
;; `IDisposable` → `:else nil`; a bare `js-obj` satisfies NEITHER protocol,
;; so the entry was SKIPPED in silence. Every assertion in the test passed
;; and the per-entry failure path it was written for was never reached — the
;; test proved visit-and-clear and nothing else.
;;
;; `throwing-cached-reaction` reifies reagent2's own `IDisposable` — whose
;; methods are `dispose!` / `add-on-dispose!`, NOT `-dispose`, and that
;; naming detail is the whole reason the bare object fell through — so the
;; real disposal route lands in a body that throws a sentinel this test
;; allocated. A sentinel rather than a message because "the FIRST failure
;; specifically" is unprovable against an error the runtime minted.
;;
;; Mirrors `re-frame.dispose-adapter-sub-cache-walk-cljs-test`'s
;; `throwing-cached-reaction`, which rf2-ss8x built for the stock-Reagent
;; surface after finding the same vacuity there.

(defn- throwing-cached-reaction
  "A sub-cache-shaped `:reaction` whose disposal throws `sentinel` and
  records the attempt in `attempts`. Implements reagent2's `IDisposable`
  so the adapter's claimed-generation disposer dispatches into it exactly
  as it would into a real Reaction."
  [sentinel attempts]
  (reify ratom/IDisposable
    (dispose! [_]
      (swap! attempts inc)
      (throw sentinel))
    (add-on-dispose! [_ _f] nil)))

(deftest dispose-adapter-walk-drains-past-a-throwing-entry-then-rethrows
  (testing "rf2-sx77q G3 + rf2-ss8x, made load-bearing by rf2-vy0a: a throwing
  per-entry dispose does NOT abort the rest of the walk, and the failure is
  rethrown to the caller once the drain is complete. The tolerance and the
  rethrow are both spine-shared but were pinned ONLY on the Reagent adapter;
  this slim sibling closes the gap so a future spine refactor that drops
  either is caught at the slim surface too (slim claims drop-in Reagent
  parity)."
    (rf/make-frame {:id :walk/a})
    (rf/make-frame {:id :walk/b})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed] {:frame :walk/a})
    (rf/dispatch-sync [:seed] {:frame :walk/b})

    (let [r-a (rf/subscribe [:n] {:frame :walk/a})
          r-b (rf/subscribe [:n] {:frame :walk/b})]
      (is (= 1 @r-a))
      (is (= 1 @r-b))

      ;; Inject a sentinel reaction into walk/a's sub-cache whose dispose
      ;; path throws — mirrors a misbehaving downstream (e.g. a user
      ;; `:on-dispose` hook raising). The walk must still drain the rest of
      ;; walk/a's cache AND walk/b's cache, and then surface this exact value.
      (let [sentinel (ex-info "poison entry disposal" {::poison true})
            attempts (atom 0)
            cache-a  (:sub-cache (rf.frame/frame :walk/a))]
        (swap! cache-a assoc [:poison]
               {:reaction (throwing-cached-reaction sentinel attempts)})

        (let [reactions-before [r-a r-b]
              disposed         (atom #{})]
          (doseq [r reactions-before]
            (ratom/add-on-dispose! r (fn [& _] (swap! disposed conj r))))

          (let [thrown (try (rf.substrate.adapter/dispose-adapter!)
                            ::returned-normally
                            (catch :default e e))]
            ;; (1) THE POISON ACTUALLY FIRED. Without this the rest of the
            ;; test is satisfied by an entry that was silently skipped —
            ;; which is exactly what it was before rf2-vy0a.
            (is (= 1 @attempts)
                "the poison entry's disposer was CALLED, exactly once — not
                 skipped by the dispatch's :else branch, and not retried")

            ;; (2) DRAIN EVERYTHING past it, in the same frame and a later one.
            (doseq [r reactions-before]
              (is (contains? @disposed r)
                  "the walk reached and disposed the real Reaction past the poison entry"))
            (is (= {} @(:sub-cache (rf.frame/frame :walk/a)))
                "walk/a's cache was still cleared despite the throw")
            (is (= {} @(:sub-cache (rf.frame/frame :walk/b)))
                "walk/b's cache was still cleared after the throwing walk/a entry")

            ;; (3) RETHROW, and the IDENTICAL value rather than a wrapper.
            (is (identical? sentinel thrown)
                "rf/destroy-adapter! rethrew the poison entry's own error
                 object, unwrapped, after the drain finished")))))))

(deftest dispose-adapter-walk-tolerates-an-empty-frames-registry
  (testing "dispose-adapter! on an installed slim adapter with no live
  frames is a no-op (no throw)"
    ;; Pre-dispose: drop every frame, then dispose. This is the post-
    ;; make-reset-runtime-fixture shape: the fixture resets frames BEFORE
    ;; calling dispose-adapter!, so dispose-adapter! sees an empty
    ;; registry.
    (reset! rf.frame/frames {})
    (is (nil? (rf.substrate.adapter/dispose-adapter!))
        "dispose-adapter! returns nil with an empty frames registry — no throw")
    (is (true? (rf.substrate.adapter/adapter-disposed?))
        "the disposed-adapter breadcrumb is set after the no-op walk")))

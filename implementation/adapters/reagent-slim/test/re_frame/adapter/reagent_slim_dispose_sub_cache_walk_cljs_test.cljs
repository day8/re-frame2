(ns re-frame.adapter.reagent-slim-dispose-sub-cache-walk-cljs-test
  "The reagent-slim `dispose-adapter!` walks every live frame's sub-cache and
  disposes each cached Reaction (Spec 006 §Adapter disposal lifecycle, MUST
  1): on a headless path no unmount fires, so without the walk the Reactions
  stay pinned forever. Mirrors the Reagent adapter's
  `re-frame.dispose-adapter-sub-cache-walk-cljs-test`; the spine helper's
  unit tier is `re-frame.substrate.spine-dispose-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

;; The unit under test IS `dispose-adapter!`, so each test starts from a
;; never-installed cold state and installs the slim adapter itself.
(defn with-fresh-slim-adapter [test-fn]
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (reset! rf.frame/frames {})
  (rf/init! rf.adapter.reagent-slim/adapter)
  (rf.frame/ensure-default-frame!)
  (test-fn)
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
    (rf/make-frame {:id :walk/a})
    (rf/make-frame {:id :walk/b})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed 1] {:frame :walk/a})
    (rf/dispatch-sync [:seed 2] {:frame :walk/b})

    (let [r-a (rf/subscribe [:n] {:frame :walk/a})
          r-b (rf/subscribe [:n] {:frame :walk/b})]
      (is (= [1 2] [@r-a @r-b]))
      (is (every? pos? (map #(get (sub-cache-counts) % 0) [:walk/a :walk/b]))
          "precondition: each frame's sub-cache holds the [:n] entry")
      ;; Snapshot the Reactions now: after the walk the caches are empty.
      (let [reactions-before (vec (cached-reactions-across-all-frames))
            disposed         (atom #{})]
        (is (>= (count reactions-before) 2) "precondition: a Reaction per frame is cached")
        (doseq [r reactions-before]
          (ratom/add-on-dispose! r (fn [& _] (swap! disposed conj r))))
        (rf.substrate.adapter/dispose-adapter!)
        (is (= (set reactions-before) @disposed)
            "every previously-cached Reaction fired its dispose hook")
        (is (= #{0} (set (vals (sub-cache-counts))))
            "every frame's sub-cache is empty")))))

;; The poison entry must be one the disposer actually CALLS: the ratom
;; disposer (`spine/make-ratom-dispose-dispatch`) silently skips anything
;; satisfying neither `re-frame.disposable/IDisposable` nor reagent2's
;; `IDisposable`, so a bare `js-obj` would prove visit-and-clear and nothing
;; else. It throws a sentinel this test allocated, so the rethrow can be
;; checked by identity.

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
  (testing "a throwing per-entry dispose does NOT abort the rest of the walk,
  and the failure is rethrown to the caller once the drain is complete"
    (rf/make-frame {:id :walk/a})
    (rf/make-frame {:id :walk/b})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed] {:frame :walk/a})
    (rf/dispatch-sync [:seed] {:frame :walk/b})

    (let [r-a (rf/subscribe [:n] {:frame :walk/a})
          r-b (rf/subscribe [:n] {:frame :walk/b})]
      (is (= [1 1] [@r-a @r-b]))
      (let [sentinel (ex-info "poison entry disposal" {::poison true})
            attempts (atom 0)
            cache-a  (:sub-cache (rf.frame/frame :walk/a))]
        (swap! cache-a assoc [:poison]
               {:reaction (throwing-cached-reaction sentinel attempts)})

        (let [disposed (atom #{})]
          (doseq [r [r-a r-b]]
            (ratom/add-on-dispose! r (fn [& _] (swap! disposed conj r))))
          (let [thrown (try (rf.substrate.adapter/dispose-adapter!)
                            ::returned-normally
                            (catch :default e e))]
            (is (= [1 #{r-a r-b} {} {}]
                   [@attempts @disposed
                    @(:sub-cache (rf.frame/frame :walk/a))
                    @(:sub-cache (rf.frame/frame :walk/b))])
                "[poison-attempts disposed cache-a cache-b]: the poison was called once, and the walk still disposed both real Reactions and cleared both caches")
            (is (identical? sentinel thrown)
                "the walk rethrew the poison entry's own error, unwrapped, after the drain")))))))

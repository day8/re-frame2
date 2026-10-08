(ns re-frame.dispose-adapter-sub-cache-walk-cljs-test
  "The Reagent `dispose-adapter!` walks every live frame's sub-cache and
  disposes each cached Reaction (Spec 006 §Adapter disposal lifecycle, MUST
  1), which matters on the headless path where no unmount fires. The walk is
  best-effort but not silent: a throwing entry or root does not abort the
  drain, and once everything was attempted the FIRST failure is rethrown
  unchanged, with later ones attached as secondary evidence."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; The unit under test IS `dispose-adapter!`, so each test starts from a
;; never-installed cold state and installs the adapter itself.
(defn- cold-start-fixture [test-fn]
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (reset! rf.frame/frames {})
  (rf/init! rf.adapter.reagent/adapter)
  (rf.frame/ensure-default-frame!)
  (test-fn)
  (when (rf.substrate.adapter/current-adapter)
    (rf.substrate.adapter/dispose-adapter!))
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each cold-start-fixture)

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

;; ---- drain-then-rethrow ----------------------------------------
;;
;; Drain everything even when one fails, do not swallow the failure, and
;; surface the FIRST of several (the later ones are usually its consequences).
;; The poison is a sentinel this test allocated, so the rethrow is checked by
;; identity, and it reifies Reagent's `IDisposable` because the disposer
;; silently skips anything satisfying neither that nor
;; `re-frame.disposable/IDisposable`.

(defn- throwing-cached-reaction
  "A sub-cache-shaped `:reaction` whose disposal throws `sentinel` and
  records the attempt in `attempts`. Implements Reagent's `IDisposable`
  so the adapter's claimed-generation disposer dispatches into it exactly
  as it would into a real Reaction."
  [sentinel attempts]
  (reify ratom/IDisposable
    (dispose! [_]
      (swap! attempts inc)
      (throw sentinel))
    (add-on-dispose! [_ _f] nil)))

(deftest dispose-adapter-drains-everything-then-rethrows-the-first-failure
  (testing "a throwing per-entry dispose does NOT abort the rest of the walk,
  AND the failure is rethrown to the caller once the drain is complete"
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
            (is (= [#{r-a r-b} {} {} 1]
                   [@disposed
                    @(:sub-cache (rf.frame/frame :walk/a))
                    @(:sub-cache (rf.frame/frame :walk/b))
                    @attempts])
                "[disposed cache-a cache-b poison-attempts]: the walk disposed both real Reactions past the poison, cleared both caches, and tried the poison once")
            ;; A drain that swallowed the failure returns nil here while every
            ;; drain assertion above passes.
            (is (identical? sentinel thrown)
                "the walk rethrew the poison entry's own error object, unwrapped, after the drain")
            (is (= [nil true :rf.error/adapter-disposed]
                   [(rf.substrate.adapter/current-adapter)
                    (rf.substrate.adapter/adapter-disposed?)
                    (try (rf.substrate.adapter/make-state-container {})
                         nil
                         (catch :default e (:rf.error/id (ex-data e))))])
                "terminal state despite the throw: no install slot, the disposed breadcrumb set, and public delegation reporting :rf.error/adapter-disposed")))))))

(deftest dispose-adapter-rethrows-the-first-of-several-failures
  (testing "with more than one cleanup failure the FIRST encountered value is
  the thrown primary, and the later ones ride it as secondary evidence"
    (rf/make-frame {:id :walk/multi})
    (let [attempts (atom 0)
          ;; Distinct sentinels so identity — not message, not order of
          ;; assertion — decides which one became primary. Insertion order
          ;; into the cache map is the traversal order the drain sees.
          poisons  (mapv (fn [i] (ex-info (str "poison " i) {::poison i}))
                         (range 3))
          cache    (:sub-cache (rf.frame/frame :walk/multi))]
      (doseq [[i sentinel] (map-indexed vector poisons)]
        (swap! cache assoc [:poison i]
               {:reaction (throwing-cached-reaction sentinel attempts)}))

      (let [thrown (try (rf.substrate.adapter/dispose-adapter!)
                        ::returned-normally
                        (catch :default e e))
            ;; Traversal order over a CLJS map is not a contract, so the
            ;; primary is "whichever the drain met first", identified by
            ;; membership rather than by index.
            ;;
            ;; Read through `unchecked-get` on the same string key the spine
            ;; writes: `:advanced` renames neither, so this assertion holds in
            ;; a release build too.
            secondary (when (instance? js/Object thrown)
                        (unchecked-get thrown "rfAdapterTeardownSecondaryErrors"))]
        (is (= [3 true 2 (set (remove #(identical? % thrown) poisons)) {}]
               [@attempts
                (boolean (some #(identical? % thrown) poisons))
                (count (array-seq secondary))
                (set (array-seq secondary))
                @cache])
            "[attempts primary-is-a-sentinel? secondaries secondary-set cache]: every poison was attempted, an unwrapped sentinel is primary, exactly the other two ride it as secondary evidence, and the cache was still cleared")))))

(deftest dispose-adapter-rethrows-a-falsey-primary-by-presence
  (testing "a cleanup that throws nil is captured by PRESENCE, not truthiness —
  a truthiness accumulator would silently drop it and report clean success"
    (rf/make-frame {:id :walk/falsey})
    (let [attempts (atom 0)
          cache    (:sub-cache (rf.frame/frame :walk/falsey))
          outcome  (atom ::unset)]
      (swap! cache assoc [:poison]
             {:reaction (throwing-cached-reaction nil attempts)})
      (try (rf.substrate.adapter/dispose-adapter!)
           (reset! outcome ::returned-normally)
           (catch :default e (reset! outcome [::threw e])))
      (is (= [1 [::threw nil] {}] [@attempts @outcome @cache])
          "the nil-throwing entry was attempted, its nil still reaches the caller as a throw rather than a clean return, and the cache was still cleared"))))

(deftest dispose-adapter-drains-every-root-then-rethrows
  (testing "one throwing root unmount does not strand its siblings, and the
  identical failure reaches the caller only after every root was attempted"
    ;; Roots are registered through the adapter's own `:render` slot and
    ;; observed through `reagent.dom.client` spies, which the adapter resolves
    ;; at call time.
    (reset! rf.frame/frames {})
    (let [sentinel      (ex-info "root unmount" {::root true})
          unmount-calls (atom [])
          bad-root      #js {:rf-test-root-tag "bad"  :unmount (fn [] nil)}
          good-root     #js {:rf-test-root-tag "good" :unmount (fn [] nil)}
          pending       (atom [bad-root good-root])]
      (with-redefs [rdc/create-root (fn
                                      ([_]   (let [[r] @pending] (swap! pending rest) r))
                                      ([_ _] (let [[r] @pending] (swap! pending rest) r)))
                    rdc/render      (fn ([_ _] nil) ([_ _ _] nil) ([_ _ _ _] nil))
                    rdc/unmount     (fn [root]
                                      (swap! unmount-calls conj root)
                                      (when (identical? root bad-root)
                                        (throw sentinel))
                                      nil)]
        (let [render-fn (:render rf.adapter.reagent/adapter)]
          (render-fn [:div "bad"] #js {} nil)
          (render-fn [:div "good"] #js {} nil)

          (let [thrown (try (rf.substrate.adapter/dispose-adapter!)
                            ::returned-normally
                            (catch :default e e))]
            (is (= [2 #{bad-root good-root} true true nil]
                   [(count @unmount-calls) (set @unmount-calls) (identical? sentinel thrown)
                    (rf.substrate.adapter/adapter-disposed?) (rf.substrate.adapter/current-adapter)])
                "[unmounts unmounted rethrew-sentinel? disposed? install-slot]: each root was attempted once, the identical failure reached the caller after the drain, and the adapter still ended disposed")))))))

(deftest dispose-adapter-happy-teardown-still-returns-nil
  (testing "a teardown with nothing failing is unchanged: nil return over a
  live frame and sub cache, and a fresh rf/init! installs over it"
    (reset! rf.frame/frames {})
    (rf/make-frame {:id :walk/clean})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed] {:frame :walk/clean})
    (is (= 1 @(rf/subscribe [:n] {:frame :walk/clean})))
    (is (= [nil true] [(rf.substrate.adapter/dispose-adapter!) (rf.substrate.adapter/adapter-disposed?)])
        "a clean drain still returns nil — the rethrow is failure-only")

    ;; And a fresh generation installs over the disposed one.
    (rf/init! rf.adapter.reagent/adapter)
    (is (= :rf.adapter/reagent (:kind (rf.substrate.adapter/current-adapter)))
        "a fresh rf/init! installs a new generation after teardown")))

(deftest claimed-generation-cleanup-keeps-public-delegation-terminal
  (testing "cleanup disposes through its claimed generation while every public
  and re-entrant lifecycle path already observes terminal disposal"
    (rf/make-frame {:id :walk/claimed})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed] {:frame :walk/claimed})
    (let [reaction (rf/subscribe [:n] {:frame :walk/claimed})
          observed (atom nil)]
      (is (= 1 @reaction))
      (ratom/add-on-dispose!
       reaction
       (fn [& _]
         (let [delegation-error
               (try
                 (rf.substrate.adapter/make-state-container {})
                 nil
                 (catch :default e
                   (:rf.error/id (ex-data e))))]
           (reset! observed
                   {:current-spec (rf.substrate.adapter/current-adapter)
                    :delegation-error delegation-error
                    :nested-destroy (rf.substrate.adapter/dispose-adapter!)}))))

      (rf.substrate.adapter/dispose-adapter!)

      (is (= {:current-spec nil
              :delegation-error :rf.error/adapter-disposed
              :nested-destroy nil}
             @observed)
          "the claimed disposer runs without reopening public admission or a second cleanup owner"))))

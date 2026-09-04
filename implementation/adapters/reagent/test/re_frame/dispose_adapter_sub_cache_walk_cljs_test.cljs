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
    4. Best-effort is not silent (rf2-ss8x): once the drain has attempted
       every Reaction and every root and ownership is finalized, the FIRST
       captured failure is rethrown to the `rf/destroy-adapter!` caller,
       unchanged, with any later failures attached as secondary evidence.

  ns ends in -cljs-test so shadow-cljs's `:node-test` build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
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

(defn- cold-start-fixture [test-fn]
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

(use-fixtures :each cold-start-fixture)

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

;; ---- drain-then-rethrow (rf2-ss8x) ----------------------------------------
;;
;; Spec 006 §Adapter disposal lifecycle makes teardown failure THREE
;; constraints at once, and they pull against each other: drain every
;; Reaction and root even when one fails; do not swallow the failure; and
;; when several fail, surface the FIRST one, because the later ones are
;; usually its consequences. Before rf2-ss8x the shared spine drain got the
;; first right and the second wrong — `(catch :default _ nil)` at each step —
;; so `rf/destroy-adapter!` returned a clean nil over a teardown that had
;; malfunctioned, and this file's own poison proof pinned that nil as
;; correct.
;;
;; A throwing disposer needs a value the test can assert IDENTITY on, not
;; just a message: "the first failure specifically" is unprovable against an
;; error the runtime minted.
;;
;; It also has to be a value the disposer actually CALLS. The ratom family's
;; claimed-generation disposer dispatches
;; `re-frame.disposable/IDisposable` → the substrate's `IDisposable` →
;; `:else nil`, so the bare `(js-obj "not" "a reaction")` this file used
;; before rf2-ss8x fell through the `:else` and was skipped in silence — it
;; proved the walk VISITED the entry and cleared the cache, but nothing ever
;; threw, so the per-entry catch it was written to pin was never reached.
;; `throwing-cached-reaction` reifies Reagent's own `IDisposable` (whose
;; methods are `dispose!` / `add-on-dispose!`) so the real disposal route —
;; `dispose!-dispatch` → `dispose-once!` → `ratom/dispose!` — lands in a body
;; that throws a sentinel this test allocated.

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
      (is (= 1 @r-a))
      (is (= 1 @r-b))

      ;; Inject a poison entry into walk/a's sub-cache whose dispose throws
      ;; — mirrors a misbehaving downstream (e.g. a user `:on-dispose` hook
      ;; raising). The walk must still drain the rest of walk/a's cache AND
      ;; walk/b's cache, and then surface this exact value.
      (let [sentinel (ex-info "poison entry disposal" {::poison true})
            attempts (atom 0)
            cache-a  (:sub-cache (frame/frame :walk/a))]
        (swap! cache-a assoc [:poison]
               {:reaction (throwing-cached-reaction sentinel attempts)})

        (let [reactions-before [r-a r-b]
              disposed         (atom #{})]
          (doseq [r reactions-before]
            (ratom/add-on-dispose! r (fn [& _] (swap! disposed conj r))))

          (let [thrown (try (adapter/dispose-adapter!)
                            ::returned-normally
                            (catch :default e e))]
            ;; (1) DRAIN EVERYTHING — the siblings past the poison entry,
            ;; in the same frame and in a later one, were still disposed.
            (doseq [r reactions-before]
              (is (contains? @disposed r)
                  "the walk reached and disposed the real Reaction past the poison entry"))
            (is (= {} @(:sub-cache (frame/frame :walk/a)))
                "walk/a's cache was still cleared despite the throw")
            (is (= {} @(:sub-cache (frame/frame :walk/b)))
                "walk/b's cache was still cleared after the throwing walk/a entry")
            (is (= 1 @attempts)
                "the poison entry was disposed exactly once — not retried")

            ;; (2) RETHROW — and (3) the IDENTICAL value, not a wrapper.
            ;; This is the assertion the pre-rf2-ss8x drain fails: it
            ;; returned nil here while every drain assertion above passed.
            (is (identical? sentinel thrown)
                "rf/destroy-adapter! rethrew the poison entry's own error object,
                unwrapped, after the drain finished")

            ;; (4) Terminal lifecycle state despite the throw.
            (is (nil? (adapter/current-adapter-spec))
                "the install slot is cleared even though cleanup threw")
            (is (true? (adapter/adapter-disposed?))
                "the disposed breadcrumb is set even though cleanup threw")
            (is (= :rf.error/adapter-disposed
                   (try (adapter/make-state-container {})
                        nil
                        (catch :default e (:rf.error/id (ex-data e)))))
                "public delegation reports :rf.error/adapter-disposed after a failed teardown")))))))

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
          cache    (:sub-cache (frame/frame :walk/multi))]
      (doseq [[i sentinel] (map-indexed vector poisons)]
        (swap! cache assoc [:poison i]
               {:reaction (throwing-cached-reaction sentinel attempts)}))

      (let [thrown (try (adapter/dispose-adapter!)
                        ::returned-normally
                        (catch :default e e))
            ;; Traversal order over a CLJS map is not a contract, so the
            ;; primary is "whichever the drain met first", identified by
            ;; membership rather than by index.
            secondary (when (instance? js/Object thrown)
                        (.-rfAdapterTeardownSecondaryErrors thrown))]
        (is (= 3 @attempts)
            "every poison entry was attempted — one failure did not abandon the rest")
        (is (some #(identical? % thrown) poisons)
            "the thrown value is one of the sentinels, unwrapped")
        (is (some? secondary)
            "later failures were attached to the primary as secondary evidence")
        (is (= 2 (alength secondary))
            "both later failures were retained")
        (is (= (set (remove #(identical? % thrown) poisons))
               (set (array-seq secondary)))
            "the secondary evidence is exactly the failures that were not primary")
        (is (= {} @cache)
            "the sub-cache was still cleared")))))

(deftest dispose-adapter-rethrows-a-falsey-primary-by-presence
  (testing "a cleanup that throws nil is captured by PRESENCE, not truthiness —
  a truthiness accumulator would silently drop it and report clean success"
    (rf/make-frame {:id :walk/falsey})
    (let [attempts (atom 0)
          cache    (:sub-cache (frame/frame :walk/falsey))
          outcome  (atom ::unset)]
      (swap! cache assoc [:poison]
             {:reaction (throwing-cached-reaction nil attempts)})
      (try (adapter/dispose-adapter!)
           (reset! outcome ::returned-normally)
           (catch :default e (reset! outcome [::threw e])))
      (is (= 1 @attempts) "the nil-throwing entry was attempted")
      (is (= [::threw nil] @outcome)
          "a thrown nil still reaches the caller as a throw, not as a clean return")
      (is (= {} @cache) "the sub-cache was still cleared"))))

(deftest dispose-adapter-drains-every-root-then-rethrows
  (testing "one throwing root unmount does not strand its siblings, and the
  identical failure reaches the caller only after every root was attempted"
    ;; The active-roots cell is private to the spine closure, so the roots
    ;; are registered the way production registers them — through the
    ;; adapter's own `:render` slot — and observed through spies on
    ;; `reagent.dom.client`. Both adapter ops resolve their rdc fn at CALL
    ;; time precisely so `with-redefs` reaches them, which keeps this proof
    ;; deterministic and DOM-free under :node-test.
    (reset! frame/frames {})
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
        (let [render-fn (:render reagent-adapter/adapter)]
          (render-fn [:div "bad"] #js {} nil)
          (render-fn [:div "good"] #js {} nil)

          (let [thrown (try (adapter/dispose-adapter!)
                            ::returned-normally
                            (catch :default e e))]
            (is (some #(identical? bad-root %) @unmount-calls)
                "the throwing root's unmount was attempted")
            (is (some #(identical? good-root %) @unmount-calls)
                "the healthy sibling was still drained despite the throw")
            (is (= 2 (count @unmount-calls))
                "each snapshot root was attempted exactly once — no retry of a consumed root")
            (is (identical? sentinel thrown)
                "the identical root-unmount failure reached the caller, after the drain")
            (is (true? (adapter/adapter-disposed?))
                "the disposed breadcrumb is set even though a root unmount threw")
            (is (nil? (adapter/current-adapter-spec))
                "active-root ownership released with the install slot despite the throw")))))))

(deftest dispose-adapter-happy-teardown-still-returns-nil
  (testing "a teardown with nothing failing is unchanged: nil return, and an
  empty frames/roots registry stays no-op-safe"
    (reset! frame/frames {})
    (rf/make-frame {:id :walk/clean})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed] {:frame :walk/clean})
    (is (= 1 @(rf/subscribe [:n] {:frame :walk/clean})))
    (is (nil? (adapter/dispose-adapter!))
        "a clean drain still returns nil — the rethrow is failure-only")
    (is (true? (adapter/adapter-disposed?)))

    ;; And a fresh generation installs over the disposed one.
    (rf/init! reagent-adapter/adapter)
    (is (= :rf.adapter/reagent (adapter/current-adapter))
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
                 (adapter/make-state-container {})
                 nil
                 (catch :default e
                   (:rf.error/id (ex-data e))))]
           (reset! observed
                   {:current-spec (adapter/current-adapter-spec)
                    :delegation-error delegation-error
                    :nested-destroy (adapter/dispose-adapter!)}))))

      (adapter/dispose-adapter!)

      (is (= {:current-spec nil
              :delegation-error :rf.error/adapter-disposed
              :nested-destroy nil}
             @observed)
          "the claimed disposer runs without reopening public admission or a second cleanup owner"))))

(deftest dispose-adapter-walk-tolerates-an-empty-frames-registry
  (testing "dispose-adapter! on an installed Reagent adapter with no live
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

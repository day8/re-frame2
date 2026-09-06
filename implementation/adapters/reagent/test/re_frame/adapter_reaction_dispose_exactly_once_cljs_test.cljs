(ns re-frame.adapter-reaction-dispose-exactly-once-cljs-test
  "Pins the stock Reagent adapter's exactly-once Reaction disposal
  (rf2-rzeko; Spec 006 §On-dispose hooks). The adapter promises that a
  derived value's disposal is idempotent and re-entrant safe: every
  on-dispose callback fires EXACTLY ONCE in registration order, a second
  `dispose!` is a no-op, and a `dispose!` re-entered from inside a
  callback cannot recurse. Stock Reagent 2.0.1's `reagent.ratom/dispose!`
  keeps neither half on its own — it leaves `on-dispose`/`on-dispose-arr`
  intact and re-invokes them on every call, and `Reaction.-remove-watch`
  auto-disposes when the last outward watch drops — so the adapter arms
  every Reaction it creates with a construction-time exactly-once guard.

  Four proofs, all through the actual public paths (the adapter map's
  `:make-derived-value`, the routed `re-frame.interop` surface, the
  claimed-generation `dispose-adapter!` walk, and core `add-watch` /
  `remove-watch` for stock auto-disposal):

    1. A double `interop/dispose!` fires each callback exactly once, in
       registration order.
    2. A callback that (conditionally) re-enters `interop/dispose!` on
       the same Reaction returns without recursion; it and its later
       sibling each fire once.
    3. Host/adapter crossover: explicit adapter disposal followed by
       stock auto-disposal (last outward watch removed), and the
       opposite order, both leave the callback count at one — and the
       one-shot path still releases the source wire (no recompute after
       disposal).
    4. Shutdown + unmount interleaving through the claimed-generation
       path: `dispose-adapter!` walks the sub-cache, then a lingering
       mounted owner's watch removal auto-disposes the same cached
       Reaction — the sub-cache teardown callbacks do not re-fire.

  ns ends in -cljs-test so shadow-cljs's `:node-test` build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; ---- fixture --------------------------------------------------------------
;;
;; Cold-start, mirroring dispose-adapter-sub-cache-walk-cljs-test: the
;; routed interop surface and the claimed-generation dispose path are both
;; under test, so each test installs the Reagent adapter itself and cleans
;; up so a re-run is idempotent.

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

(defn- make-source-and-derived
  "Build a real source container and derived Reaction through the adapter
  map's own contract slots. `compute-count` observes recomputes so tests
  can assert the source wire is released by disposal."
  [initial compute-count]
  (let [make-container (:make-state-container rf.adapter.reagent/adapter)
        make-derived   (:make-derived-value rf.adapter.reagent/adapter)
        src            (make-container initial)
        rx             (make-derived [src] (fn [v]
                                             (swap! compute-count inc)
                                             (inc v)))]
    {:src src :rx rx}))

(defn- cached-reaction
  "The `:reaction` cached for `frame-id`'s single sub-cache entry."
  [frame-id]
  (let [cache (:sub-cache (rf.frame/frame frame-id))]
    (some (fn [[_k entry]] (:reaction entry)) @cache)))

;; ---- 1. double dispose --------------------------------------------------

(deftest double-dispose-fires-each-callback-exactly-once-in-order
  (testing "a second interop/dispose! re-fires nothing; the first fired the
  callbacks in registration order"
    (let [compute-count (atom 0)
          {:keys [rx]}  (make-source-and-derived 1 compute-count)
          fired         (atom [])]
      (is (= 2 @rx) "precondition: the derived value computes")
      (rf.interop/add-on-dispose! rx (fn [_] (swap! fired conj :cb-1)))
      (rf.interop/add-on-dispose! rx (fn [_] (swap! fired conj :cb-2)))
      (rf.interop/dispose! rx)
      (is (= [:cb-1 :cb-2] @fired)
          "first dispose! fired both callbacks, in registration order")
      (rf.interop/dispose! rx)
      (is (= [:cb-1 :cb-2] @fired)
          "second dispose! re-fired nothing (exactly-once)"))))

;; ---- 2. re-entrant dispose ------------------------------------------------

(deftest re-entrant-dispose-returns-without-recursion
  (testing "a callback that re-enters interop/dispose! on the same Reaction
  neither recurses nor re-fires the callback set"
    (let [compute-count (atom 0)
          {:keys [rx]}  (make-source-and-derived 1 compute-count)
          fired         (atom [])
          re-entered?   (atom false)]
      (is (= 2 @rx) "precondition: the derived value computes")
      ;; Conditional re-entry (first invocation only) so that WITHOUT the
      ;; guard this proof goes red by count rather than by stack overflow.
      (rf.interop/add-on-dispose! rx
        (fn [r]
          (swap! fired conj :re-entrant-cb)
          (when-not @re-entered?
            (reset! re-entered? true)
            (rf.interop/dispose! r))))
      (rf.interop/add-on-dispose! rx (fn [_] (swap! fired conj :after-cb)))
      (rf.interop/dispose! rx)
      (is (= [:re-entrant-cb :after-cb] @fired)
          "each callback fired exactly once despite the re-entrant dispose!"))))

;; ---- 3. host/adapter crossover --------------------------------------------
;;
;; Stock `Reaction.-remove-watch` auto-disposes when the last outward watch
;; drops and `auto-run` is nil. Explicit disposal does NOT clear the outward
;; `watches` map, so a mounted owner that unmounts later re-enters
;; `dispose!` from inside stock Reagent — a path the adapter never sees.

(deftest adapter-dispose-then-host-auto-dispose-fires-once
  (testing "explicit adapter disposal, then removing the final outward watch
  (stock auto-disposal): callbacks fire once; the source wire is released"
    (let [compute-count (atom 0)
          {:keys [src rx]} (make-source-and-derived 1 compute-count)
          fired         (atom 0)]
      (is (= 2 @rx) "precondition: the derived value computes")
      ;; Put the Reaction on the push path so it holds a live source watch.
      (rf.interop/activate-derived-value! rx)
      (rf.interop/add-on-dispose! rx (fn [_] (swap! fired inc)))
      (add-watch rx ::owner (fn [_ _ _ _] nil))
      (let [computes-before-dispose @compute-count]
        (rf.interop/dispose! rx)
        (is (= 1 @fired) "explicit disposal fired the callback once")
        ;; One-shot path intact: the source wire is released, so a source
        ;; write no longer recomputes the disposed Reaction.
        (reset! src 41)
        (r/flush)
        (is (= computes-before-dispose @compute-count)
            "post-dispose source write drove no recompute (source wire released)"))
      ;; The lingering owner unmounts: stock -remove-watch auto-disposes.
      (remove-watch rx ::owner)
      (is (= 1 @fired)
          "stock auto-disposal after explicit disposal re-fired nothing"))))

(deftest host-auto-dispose-then-adapter-dispose-fires-once
  (testing "stock auto-disposal (final outward watch removed), then explicit
  adapter disposal: callbacks fire once"
    (let [compute-count (atom 0)
          {:keys [rx]}  (make-source-and-derived 1 compute-count)
          fired         (atom 0)]
      (is (= 2 @rx) "precondition: the derived value computes")
      (rf.interop/add-on-dispose! rx (fn [_] (swap! fired inc)))
      (add-watch rx ::owner (fn [_ _ _ _] nil))
      (remove-watch rx ::owner)
      (is (= 1 @fired) "stock auto-disposal fired the callback once")
      (rf.interop/dispose! rx)
      (is (= 1 @fired)
          "explicit disposal after stock auto-disposal re-fired nothing"))))

;; ---- 4. shutdown + unmount interleaving ------------------------------------
;;
;; The claimed-generation path: `dispose-adapter!` walks every live frame's
;; sub-cache and disposes each cached Reaction through the generation's own
;; disposer (NOT the routed hook, which terminal teardown has already
;; closed). A mounted owner's outward watch survives that walk; its later
;; removal auto-disposes the same Reaction inside stock Reagent.

(deftest shutdown-then-unmount-does-not-refire-sub-cache-teardown
  (testing "dispose-adapter! then a lingering owner's watch removal: the
  cached Reaction's teardown callbacks fire once"
    (rf/make-frame {:id :once/a})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed 1] {:frame :once/a})
    (let [handle (rf/subscribe [:n] {:frame :once/a})
          _      (is (= 1 @handle) "precondition: the sub materialises")
          rx     (cached-reaction :once/a)
          fired  (atom 0)]
      (is (some? rx) "precondition: the sub-cache holds the Reaction")
      (rf.interop/add-on-dispose! rx (fn [_] (swap! fired inc)))
      ;; A mounted owner watches the cached Reaction across shutdown.
      (add-watch rx ::owner (fn [_ _ _ _] nil))
      (rf.substrate.adapter/dispose-adapter!)
      (is (= 1 @fired)
          "claimed-generation shutdown disposed the cached Reaction once")
      ;; The owner unmounts after shutdown: stock auto-disposal re-enters
      ;; dispose! on the already-disposed Reaction.
      (remove-watch rx ::owner)
      (is (= 1 @fired)
          "post-shutdown unmount re-fired no teardown callback"))))

(deftest unmount-then-shutdown-does-not-refire-sub-cache-teardown
  (testing "a lingering owner's watch removal (stock auto-disposal evicting
  the cache slot), then dispose-adapter!: teardown callbacks fire once"
    (rf/make-frame {:id :once/b})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed 2] {:frame :once/b})
    (let [handle (rf/subscribe [:n] {:frame :once/b})
          _      (is (= 2 @handle) "precondition: the sub materialises")
          rx     (cached-reaction :once/b)
          fired  (atom 0)]
      (is (some? rx) "precondition: the sub-cache holds the Reaction")
      (rf.interop/add-on-dispose! rx (fn [_] (swap! fired inc)))
      (add-watch rx ::owner (fn [_ _ _ _] nil))
      ;; The owner unmounts first: stock auto-disposal fires the teardown,
      ;; whose sub-cache closure evicts the slot (pre-existing behaviour).
      (remove-watch rx ::owner)
      (is (= 1 @fired) "stock auto-disposal fired the teardown once")
      (is (nil? (cached-reaction :once/b))
          "auto-disposal evicted the sub-cache slot (teardown preserved)")
      ;; Adapter shutdown then walks a cache that no longer holds it — and
      ;; even a direct second disposal of the same Reaction is a no-op.
      (rf.substrate.adapter/dispose-adapter!)
      (is (= 1 @fired)
          "adapter shutdown after unmount re-fired no teardown callback"))))

(ns re-frame.standard-epochs-views-subs-lifecycle-cljs-test
  "The view and subscription lifecycle the standard-epochs testbed's
  Views/subscriptions section demonstrates
  (`tools/xray/testbeds/standard_epochs/core.cljs`, which is test-free),
  asserted here on a copy of its subs under the same `:standard-epochs/*`
  ids; the testbed is never loaded. Child A reads an L1→L2→L3 chain plus
  the arg-keyed `[:standard-epochs/greater-than? N]`; Child B renders a prop
  and subscribes nothing.

  Subscribe/unsubscribe pairs stand in for A's mount and unmount, read
  against the frame's `:sub-cache` (keyed by query-vector), as in
  `re-frame.sub-dispose-view-cljs-test`; the render-cause test drives a
  real `(rf/view ...)` render inside a cascade."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- the section's subs and events -----------------------------------------
;;
;; Child A's chain is rooted at :views/chain-input (NOT :base, which feeds the
;; testbed's flow).

(defn- register-section-subs! []
  (rf/reg-sub :standard-epochs/chain-root            ;; L1
    (fn [db _] (get-in db [:views :chain-input])))
  (rf/reg-sub :standard-epochs/chain-doubled         ;; L2
    {:inputs [[:standard-epochs/chain-root]]}
    (fn [[root] _] (* 2 root)))
  (rf/reg-sub :standard-epochs/chain-labelled        ;; L3
    {:inputs [[:standard-epochs/chain-doubled]]}
    (fn [[doubled] _] (str "2×input = " doubled)))
  (rf/reg-sub :standard-epochs/greater-than?         ;; DYNAMIC, arg-keyed
    {:inputs [[:standard-epochs/chain-root]]}
    (fn [[root] [_ threshold]] (> root threshold))))

(defn- register-section-events! []
  (rf/reg-event :standard-epochs/seed
    (fn [{:keys [db]} _] {:db {:views {:a-mounted?  false
                       :b-mounted?  false
                       :threshold   5
                       :chain-input 1
                       :b-prop      "alpha"}}}))
  (rf/reg-event :standard-epochs/perturb-chain
    (fn [{:keys [db]} _] {:db (update-in db [:views :chain-input] inc)})))

(defn- mount-a!
  "Child A's mount-time subscribes at `threshold` (its prop), returning the
  held reactions for a later `unmount-a!`."
  [threshold]
  {:chain     (rf/subscribe [:standard-epochs/chain-labelled])
   :gt        (rf/subscribe [:standard-epochs/greater-than? threshold])
   :threshold threshold})

(defn- unmount-a!
  "The unmount path: every subscribe A made on mount drops its derefer."
  [{:keys [threshold]}]
  (rf/unsubscribe [:standard-epochs/chain-labelled])
  (rf/unsubscribe [:standard-epochs/greater-than? threshold]))

(defn- sub-cache []
  @(:sub-cache (rf.frame/frame :rf/default)))

(defn- cached? [query-v]
  (contains? (sub-cache) query-v))

(def ^:private chain-read-set
  [[:standard-epochs/chain-labelled]
   [:standard-epochs/chain-doubled]
   [:standard-epochs/chain-root]])

;; ---- the lifecycle ---------------------------------------------------------

(deftest mounting-a-creates-chain-and-arg-keyed-cache-entries
  (testing "mounting Child A caches its L3 and [:greater-than? 5] reads, and
   the cascaded L1/L2 chain inputs it never names"
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:standard-epochs/seed])
    (is (empty? (sub-cache)) "precondition: cold cache before mount")
    (let [held (mount-a! 5)]
      (is (= ["2×input = 2" false] [@(:chain held) @(:gt held)])
          "L3 computes against the seeded root 1, which is NOT > 5")
      (is (= [] (remove cached? (conj chain-read-set [:standard-epochs/greater-than? 5])))
          "A's whole read-set is cached, including the cascaded chain inputs")
      (unmount-a! held))))

(deftest changing-the-arg-creates-a-distinct-cache-entry
  (testing "the parameterized-sub cache is keyed by arg: re-rendering A at
   N=10 caches [:greater-than? 10] alongside [:greater-than? 5]"
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:standard-epochs/seed])
    (let [held-5 (mount-a! 5)]
      @(:gt held-5)
      (is (= [true false]
             [(cached? [:standard-epochs/greater-than? 5]) (cached? [:standard-epochs/greater-than? 10])])
          "precondition: [:gt? 5] cached after the N=5 mount, and no [:gt? 10] slot yet")
      (let [held-10 (mount-a! 10)]
        @(:gt held-10)
        (is (= [true true false]
               [(cached? [:standard-epochs/greater-than? 10]) (cached? [:standard-epochs/greater-than? 5])
                (identical? (:gt held-5) (:gt held-10))])
            "[:gt? 10] is a new slot beside [:gt? 5], with its own reaction")
        (unmount-a! held-5)
        (unmount-a! held-10)))))

(deftest perturbing-chain-input-recomputes-the-chain
  (testing "with A mounted, perturbing the chain root propagates through
   L1 → L2 → L3 to the value the view reads"
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:standard-epochs/seed])
    (let [held (mount-a! 5)]
      (is (= "2×input = 2" @(:chain held))
          "precondition: L3 = 2×1 against the seeded root")
      (rf/dispatch-sync [:standard-epochs/perturb-chain])
      (is (= [2 "2×input = 4" false]
             [(get-in (rf.frame/frame-app-db-value :rf/default) [:views :chain-input])
              @(:chain held) @(:gt held)])
          "the root advanced 1 → 2, L3 recomputed to 2×2, and [:gt? 5] stays false")
      (unmount-a! held)))

  (testing "once the shared root crosses the threshold the arg-keyed sub flips"
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:standard-epochs/seed])
    (let [held (mount-a! 1)]
      (is (false? @(:gt held)) "precondition: root 1 is not > 1")
      (rf/dispatch-sync [:standard-epochs/perturb-chain])
      (is (true? @(:gt held)) "[:gt? 1] flipped true after root 1 → 2")
      (unmount-a! held))))

(deftest unmounting-a-disposes-every-sub-and-records-the-unmount
  (testing "unmounting A drops the last derefer on every sub it held, so
   last-reader-gone GC evicts the chain and every [:gt? N] entry, each
   recorded on the :rf.sub/dispose stream"
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:standard-epochs/seed])
    (let [held-5  (mount-a! 5)
          _       (do @(:chain held-5) @(:gt held-5))
          held-10 (mount-a! 10)
          _       (do @(:chain held-10) @(:gt held-10))]
      (is (= [] (remove cached? (conj chain-read-set
                                      [:standard-epochs/greater-than? 5]
                                      [:standard-epochs/greater-than? 10])))
          "precondition: the whole accumulated read-set is cached")
      (with-trace-recorder! [disposes {:pred #(= :rf.sub/dispose (:operation %))}]
        ;; Both mounts hold chain-labelled (ref-count 2), so only the LAST
        ;; drop disposes it.
        (unmount-a! held-5)
        (is (cached? [:standard-epochs/chain-labelled])
            "chain L3 still cached after the first unmount — ref-count 2→1")
        (unmount-a! held-10)
        (is (empty? (sub-cache)) "A's whole read-set evicted; cache drained")
        (let [ids (into #{} (map #(get-in % [:tags :rf.sub/id])) @disposes)]
          (is (= [[] #{:no-more-derefers}]
                 [(remove ids [:standard-epochs/chain-labelled :standard-epochs/chain-doubled
                               :standard-epochs/chain-root :standard-epochs/greater-than?])
                  (into #{} (map #(get-in % [:tags :rf.sub/reason])) @disposes)])
              ":rf.sub/dispose recorded each chain level and the arg-keyed sub, every one :no-more-derefers"))))))

;; ---- render cause: sub-driven (A) vs props-driven (B) ----------------------
;;
;; The views.cljs wrapper stamps :rf.view/triggered-by on :rf.view/rendered
;; when an own sub changed value in the cascade, and omits it otherwise; the
;; consumer reads the absence as ← props / parent re-render.

(def ^:private view-rendered-pred
  #(= :rf.view/rendered (:operation %)))

(deftest child-a-render-named-by-its-sub-child-b-by-props
  (testing "Child A's in-cascade re-render names its changed sub on
   :rf.view/triggered-by; Child B's, reading no sub, names none"
    (register-section-subs!)
    (register-section-events!)
    (rf/dispatch-sync [:standard-epochs/seed])
    (rf/reg-view ^{:rf/id :standard-epochs/child-a} child-a [_threshold]
      [:div @(rf/subscribe [:standard-epochs/chain-labelled])])
    (rf/reg-view ^{:rf/id :standard-epochs/child-b} child-b [prop]
      [:div prop])

    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      ;; The handler's read is the sub's first recompute in the cascade, so it
      ;; reports value-changed? into the in-flight buffer before A renders.
      (let [render-a (rf/view :standard-epochs/child-a)]
        (rf/reg-event :standard-epochs/perturb-then-render-a
          (fn [_ _]
            @(rf/subscribe [:standard-epochs/chain-labelled])
            (render-a 5)
            {}))
        (rf/dispatch-sync [:standard-epochs/perturb-then-render-a]))
      (let [render-b (rf/view :standard-epochs/child-b)]
        (rf/reg-event :standard-epochs/render-b
          (fn [_ _]
            (render-b "beta")
            {}))
        (rf/dispatch-sync [:standard-epochs/render-b]))

      (let [ev-for (fn [id] (first (filter #(= id (get-in % [:tags :rf.view/id])) @traces)))
            a-ev   (ev-for :standard-epochs/child-a)
            b-ev   (ev-for :standard-epochs/child-b)]
        (is (= [:standard-epochs/chain-labelled true false]
               [(get-in a-ev [:tags :rf.view/triggered-by])
                (some? b-ev) (contains? (:tags b-ev) :rf.view/triggered-by)])
            "A's render is attributed to its own changed sub; B rendered and names NO sub")))))

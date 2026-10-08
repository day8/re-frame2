(ns re-frame.sub-dispose-view-cljs-test
  "`:rf.sub/dispose` through the Reagent substrate, which the JVM cache
  tests (`re-frame.sub-dispose-trace-test`) cannot reach. Bare
  `rf/subscribe` / `rf/unsubscribe` pairs and render-shaped reactions
  stand in for mounted views, keeping the tests headless. A layer-2 sub's
  `add-on-dispose!` (installed in `re-frame.subs/compute-and-cache!`)
  releases its inputs through `unsubscribe!`, so the input evictions are
  observable here. The emit is dev-only (`interop/debug-enabled?`); its
  elision is `npm run test:elision`'s."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helpers ---------------------------------------------------------------

(def ^:private sub-dispose-pred
  #(= :rf.sub/dispose (:operation %)))

(defn- dispose-by-id
  "The recorded dispose events for one `sub-id`."
  [traces sub-id]
  (filterv #(= sub-id (-> % :tags :rf.sub/id)) traces))

(defn- reason [ev] (-> ev :tags :rf.sub/reason))

(defn- reason+frame [ev] [(reason ev) (-> ev :tags :frame)])

;; ---- the view's subscribe-on-mount / unsubscribe-on-unmount pair ----------

(deftest view-unmount-emits-rf-sub-dispose-on-input-cascade
  (testing "unsubscribing a layer-2 sub's last derefer emits one
   :rf.sub/dispose per evicted input, with :reason :no-more-derefers"
    (rf/reg-event :rf2-e9g4g/init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :rf2-e9g4g.view/a (fn [db _] (:a db)))
    (rf/reg-sub :rf2-e9g4g.view/b (fn [db _] (:b db)))
    (rf/reg-sub :rf2-e9g4g.view/sum
      {:inputs [[:rf2-e9g4g.view/a] [:rf2-e9g4g.view/b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:rf2-e9g4g/init])

    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      (let [r (rf/subscribe [:rf2-e9g4g.view/sum])]
        (is (= [5 true] [@r (empty? @traces)])
            "precondition: the sub computes, and nothing is evicted while it is held"))
      (rf/unsubscribe [:rf2-e9g4g.view/sum])
      (let [a-evs (dispose-by-id @traces :rf2-e9g4g.view/a)
            b-evs (dispose-by-id @traces :rf2-e9g4g.view/b)]
        (is (= [[[:no-more-derefers :rf/default]] [[:no-more-derefers :rf/default]]]
               [(mapv reason+frame a-evs) (mapv reason+frame b-evs)])
            "each input fired exactly one :rf.sub/dispose on the cascade, :no-more-derefers on the canonical frame")
        (is (every? #(and (= :rf.sub (:op-type %)) (vector? (-> % :tags :rf.sub/query-v)))
                    (concat a-evs b-evs))
            "each emit rides the :rf.sub family with a vector :rf.sub/query-v")))))

(deftest multi-derefer-emits-only-on-last-drop
  (testing "with two derefers, the first unsubscribe drops the ref-count
   2 -> 1 and emits nothing; the last one evicts the sub and its input"
    (rf/reg-event :rf2-e9g4g/init (fn [{:keys [db]} _] {:db {:v 42}}))
    (rf/reg-sub :rf2-e9g4g.multi/v (fn [db _] (:v db)))
    (rf/reg-sub :rf2-e9g4g.multi/doubled
      {:inputs [[:rf2-e9g4g.multi/v]]}
      (fn [[v] _] (* 2 v)))
    (rf/dispatch-sync [:rf2-e9g4g/init])

    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      (let [r1 (rf/subscribe [:rf2-e9g4g.multi/doubled])
            r2 (rf/subscribe [:rf2-e9g4g.multi/doubled])]
        (is (= [84 true] [@r1 (identical? r1 r2)])
            "precondition: two subscribes return the SAME reaction (cache reuse)"))
      (rf/unsubscribe [:rf2-e9g4g.multi/doubled])
      (is (empty? @traces)
          "first derefer drop: the slot's ref-count is still 1, so NO :rf.sub/dispose")
      (rf/unsubscribe [:rf2-e9g4g.multi/doubled])
      (is (= [[:no-more-derefers] [:no-more-derefers]]
             [(mapv reason (dispose-by-id @traces :rf2-e9g4g.multi/doubled))
              (mapv reason (dispose-by-id @traces :rf2-e9g4g.multi/v))])
          "the LAST drop evicted the parent and, by cascade, its input: one :no-more-derefers emit each"))))

;; ---- Reagent reaction-dispose and conditional teardown ---------------------

(deftest reaction-disposal-fires-rf-sub-dispose
  (testing "disposing the render reaction that reads a layer-2 sub (what
   componentWillUnmount does) drops its watch on the sub's cached
   Reaction, which disposes itself; `compute-and-cache!`'s on-dispose hook
   evicts the slot and its inputs"
    (rf/reg-event :rf2-b2bxk/init (fn [{:keys [db]} _] {:db {:a 11 :b 13}}))
    (rf/reg-sub :rf2-b2bxk.rea/a (fn [db _] (:a db)))
    (rf/reg-sub :rf2-b2bxk.rea/b (fn [db _] (:b db)))
    (rf/reg-sub :rf2-b2bxk.rea/sum
      {:inputs [[:rf2-b2bxk.rea/a] [:rf2-b2bxk.rea/b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:rf2-b2bxk/init])

    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      (let [render (ratom/make-reaction
                     (fn [] @(rf/subscribe [:rf2-b2bxk.rea/sum]))
                     :auto-run true)]
        (is (= [24 true] [@render (empty? @traces)])
            "precondition: the sub computes, and nothing is evicted while the reaction is held")
        (rf.interop/dispose! render)
        ;; The parent emits too: Spec 006 §Reference counting and disposal
        ;; promises the emit at the eviction site, and this reap is the one
        ;; where the hook itself removes the slot. A cache-driven eviction
        ;; removes the slot first, so the hook stays quiet there and
        ;; nothing double-emits.
        (is (= [[[:no-more-derefers :rf/default]] [[:no-more-derefers :rf/default]] [:no-more-derefers]]
               [(mapv reason+frame (dispose-by-id @traces :rf2-b2bxk.rea/a))
                (mapv reason+frame (dispose-by-id @traces :rf2-b2bxk.rea/b))
                (mapv reason (dispose-by-id @traces :rf2-b2bxk.rea/sum))])
            (str "both inputs and the parent's own slot each emit exactly one :rf.sub/dispose; got "
                 (pr-str @traces)))))))

(deftest conditional-teardown-unsubscribe-evicts-only-its-own-slots
  (testing "the `rf/unsubscribe` a conditionally rendered child's cleanup
   fires (r/with-let :finally, componentWillUnmount, an effect cleanup)
   evicts that sub and its inputs, while a sub the surviving render holds
   stays cached. No reactive render stops derefing here, so this pins the
   explicit-unsubscribe half of the lifecycle, not auto-track."
    (rf/reg-event :rf2-b2bxk/init (fn [{:keys [db]} _] {:db {:n 5 :a 3 :b 4}}))
    (rf/reg-sub :rf2-b2bxk.cond-rea/n (fn [db _] (:n db)))
    (rf/reg-sub :rf2-b2bxk.cond-rea/a (fn [db _] (:a db)))
    (rf/reg-sub :rf2-b2bxk.cond-rea/b (fn [db _] (:b db)))
    (rf/reg-sub :rf2-b2bxk.cond-rea/sum
      {:inputs [[:rf2-b2bxk.cond-rea/a] [:rf2-b2bxk.cond-rea/b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:rf2-b2bxk/init])

    (let [n-rea   (rf/subscribe [:rf2-b2bxk.cond-rea/n])
          sum-rea (rf/subscribe [:rf2-b2bxk.cond-rea/sum])]
      (with-trace-recorder! [traces {:pred sub-dispose-pred}]
        (try
          (is (= [5 7 true] [@n-rea @sum-rea (empty? @traces)])
              "precondition: both subs read the seeded app-db, and nothing is evicted while both are held")
          (rf/unsubscribe [:rf2-b2bxk.cond-rea/sum])
          (let [evicted [[:no-more-derefers :rf/default]]]
            (is (= [evicted evicted evicted []]
                   (mapv #(mapv reason+frame (dispose-by-id @traces %))
                         [:rf2-b2bxk.cond-rea/sum :rf2-b2bxk.cond-rea/a
                          :rf2-b2bxk.cond-rea/b :rf2-b2bxk.cond-rea/n]))
                ":sum and both its inputs evicted once each, and the still-held :n stays cached"))
          (finally
            (rf/unsubscribe [:rf2-b2bxk.cond-rea/n])))))))

;; ---- an explicit hold survives the render that stops reading it ------------
;;
;; An explicit `rf/subscribe` is a ref-counted hold on every adapter (Spec 006
;; §Which lifetime governs a ratom adapter). A stock Reagent `Reaction` disposes
;; itself the moment its last watcher drops, so a render that stops reading a
;; sub, or a watch removed from it, reaches the cache's on-dispose hook while
;; the explicit hold still counts. The hook keeps that slot, releasing only the
;; render owner's reference, and re-arms itself, so the later `rf/unsubscribe`
;; evicts the sub and cascades to its inputs. A render-only read holds nothing
;; else, so the flip alone frees it.

(defn- reg-sum-subs! []
  (rf/reg-event ::init (fn [_ _] {:db {:a 3 :b 4}}))
  (rf/reg-sub ::a (fn [db _] (:a db)))
  (rf/reg-sub ::b (fn [db _] (:b db)))
  (rf/reg-sub ::sum {:inputs [[::a] [::b]]} (fn [[a b] _] (+ a b)))
  (rf/dispatch-sync [::init]))

(defn- slot [query-v]
  (get @(:sub-cache (rf.frame/frame :rf/default)) query-v))

(defn- dispose-counts
  "`{sub-id n}` over the recorded `:rf.sub/dispose` events."
  [traces]
  (frequencies (map #(-> % :tags :rf.sub/id) traces)))

(defn- eviction-outcome
  "The dispose counts, and the `[::sum]`, `[::a]`, `[::b]` slots."
  [traces]
  [(dispose-counts traces) (mapv slot [[::sum] [::a] [::b]])])

(def ^:private all-evicted-once [{::sum 1 ::a 1 ::b 1} [nil nil nil]])

(defn- conditional-render
  "A render-shaped reaction reading `[::sum]` while `read?` is true."
  [read?]
  (ratom/make-reaction
    (fn [] (when @read? @(rf/subscribe [::sum])) :rendered)
    :auto-run true))

(deftest explicit-hold-survives-a-render-that-stops-reading
  (testing "an explicit subscribe plus a render read; the render stops
   reading: the slot is kept at ref-count 1 and nothing is evicted, and the
   later rf/unsubscribe evicts the sub and both inputs, each exactly once"
    (reg-sum-subs!)
    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      (let [held   (rf/subscribe [::sum])
            read?  (ratom/atom true)
            render (conditional-render read?)]
        (try
          @render
          (is (= [7 2] [@held (:ref-count (slot [::sum]))])
              "precondition: the sub computes, held by the explicit subscribe plus the render")
          (reset! read? false)
          (is (= [true 1 true 7]
                 [(empty? @traces) (:ref-count (slot [::sum]))
                  (identical? held (:reaction (slot [::sum]))) @held])
              "the flip evicted nothing and released only the render's reference; the kept slot serves and reads the held reaction")
          (rf/unsubscribe [::sum])
          (is (= all-evicted-once (eviction-outcome @traces))
              "the unsubscribe evicted the sub and both inputs, each exactly once, and no slot survives")
          (finally
            (rf.interop/dispose! render)))))))

(deftest explicit-hold-survives-a-dropped-watch
  (testing "an explicit subscribe, then a watch added and removed: the slot
   is kept at ref-count 1 and nothing is evicted, and the later
   rf/unsubscribe evicts the sub and both inputs, each exactly once"
    (reg-sum-subs!)
    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      (let [held (rf/subscribe [::sum])]
        (is (= 7 @held) "precondition: the sub computes")
        (add-watch held ::w (fn [_ _ _ _] nil))
        (remove-watch held ::w)
        (is (= [true 1] [(empty? @traces) (:ref-count (slot [::sum]))])
            "dropping the last watch evicted nothing, and the explicit hold remains")
        (rf/unsubscribe [::sum])
        (is (= all-evicted-once (eviction-outcome @traces))
            "the unsubscribe evicted the sub and both inputs, each exactly once, and no slot survives")))))

(deftest render-only-read-is-freed-by-the-flip
  (testing "control: with no explicit hold, the render that stops reading
   evicts the sub and both inputs at once, each exactly once"
    (reg-sum-subs!)
    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      (let [read?  (ratom/atom true)
            render (conditional-render read?)]
        (try
          @render
          (is (= 1 (:ref-count (slot [::sum])))
              "precondition: the render's reference is the only one")
          (reset! read? false)
          (is (= all-evicted-once (eviction-outcome @traces))
              "the flip evicted the sub and both inputs, each exactly once, and no slot survives")
          (finally
            (rf.interop/dispose! render)))))))

(deftest re-entrant-dispose-releases-the-render-reference-once
  (testing "a dispose re-entered by a callback registered between the
   cache's hook and the render's holding reaches the re-armed hook before
   that holding is dropped; the render's reference is released once, so the
   explicit hold keeps the slot at ref-count 1"
    (reg-sum-subs!)
    (with-trace-recorder! [traces {:pred sub-dispose-pred}]
      (let [held        (rf/subscribe [::sum])
            re-entered? (atom false)
            _           (rf.interop/add-on-dispose! held
                          (fn [_]
                            (when-not @re-entered?
                              (reset! re-entered? true)
                              (rf.interop/dispose! held))))
            read?       (ratom/atom true)
            render      (conditional-render read?)]
        (try
          @render
          (is (= 2 (:ref-count (slot [::sum])))
              "precondition: the explicit hold plus the render's reference")
          (reset! read? false)
          (is (= [true true 1] [@re-entered? (empty? @traces) (:ref-count (slot [::sum]))])
              "the callback re-entered dispose, which evicted nothing and released the render's reference once, not twice")
          (rf/unsubscribe [::sum])
          (is (= {::sum 1 ::a 1 ::b 1} (dispose-counts @traces))
              "the unsubscribe evicted the sub and both inputs, each exactly once")
          (finally
            (rf.interop/dispose! render)))))))

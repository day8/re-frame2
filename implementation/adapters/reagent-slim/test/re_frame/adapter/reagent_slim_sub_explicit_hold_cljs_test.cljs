(ns re-frame.adapter.reagent-slim-sub-explicit-hold-cljs-test
  "An explicit `rf/subscribe` is a ref-counted hold on reagent-slim, as on
  every adapter (Spec 006 §Which lifetime governs a ratom adapter). A
  reagent-slim `Reaction` disposes itself when its last watcher drops, so a
  render that stops reading a sub, or a watch removed from it, reaches the
  cache's on-dispose hook while that hold still counts; the hook keeps the
  slot, releasing only the render owner's reference, and re-arms itself.
  The stock-Reagent twin of these deftests lives in
  `re-frame.sub-dispose-view-cljs-test`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up; no
  DOM required."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

(def ^:private sub-dispose-pred
  "Predicate matching the `:rf.sub/dispose` trace operation."
  #(= :rf.sub/dispose (:operation %)))

;; ===========================================================================
;; An explicit hold survives the render that stops reading it
;; ===========================================================================
;;
;; An explicit `rf/subscribe` is a ref-counted hold on every adapter (Spec 006
;; §Which lifetime governs a ratom adapter). A reagent-slim `Reaction` disposes
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
          (is (= 7 @held) "precondition: the sub computes")
          (is (= 2 (:ref-count (slot [::sum])))
              "precondition: the explicit hold plus the render's reference")
          (reset! read? false)
          (is (empty? @traces) "the flip evicted nothing")
          (is (= 1 (:ref-count (slot [::sum])))
              "the render's reference was released; the explicit hold remains")
          (is (identical? held (:reaction (slot [::sum])))
              "the kept slot serves the reaction the caller holds")
          (is (= 7 @held) "the kept sub still reads")
          (rf/unsubscribe [::sum])
          (is (= {::sum 1 ::a 1 ::b 1} (dispose-counts @traces))
              "the unsubscribe evicted the sub and both inputs, each exactly once")
          (is (every? nil? (map slot [[::sum] [::a] [::b]]))
              "no slot survives the unsubscribe")
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
        (is (empty? @traces) "dropping the last watch evicted nothing")
        (is (= 1 (:ref-count (slot [::sum]))) "the explicit hold remains")
        (rf/unsubscribe [::sum])
        (is (= {::sum 1 ::a 1 ::b 1} (dispose-counts @traces))
            "the unsubscribe evicted the sub and both inputs, each exactly once")
        (is (every? nil? (map slot [[::sum] [::a] [::b]]))
            "no slot survives the unsubscribe")))))

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
          (is (= {::sum 1 ::a 1 ::b 1} (dispose-counts @traces))
              "the flip evicted the sub and both inputs, each exactly once")
          (is (every? nil? (map slot [[::sum] [::a] [::b]]))
              "no slot survives the flip")
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
          (is @re-entered? "precondition: the callback re-entered dispose")
          (is (empty? @traces) "the re-entered dispose evicted nothing")
          (is (= 1 (:ref-count (slot [::sum])))
              "the render's reference was released once, not twice")
          (rf/unsubscribe [::sum])
          (is (= {::sum 1 ::a 1 ::b 1} (dispose-counts @traces))
              "the unsubscribe evicted the sub and both inputs, each exactly once")
          (finally
            (rf.interop/dispose! render)))))))

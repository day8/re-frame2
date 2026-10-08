(ns re-frame.subs-render-owned-retention-cljs-test
  "A render-owned holding (Spec 006 §Which lifetime governs a ratom adapter)
  ends with EITHER end of it: the owner's dispose or the claimed reaction's.

  Tied to the owner alone, every claim would push one more release closure onto
  the owner and record the reaction there, so a mounted component whose
  conditional read toggles, or whose parametric query changes, would keep every
  disposed reaction it ever read, each closing over its memo's last app-db.
  The mirror failure is the claimed reaction accumulating a callback or a
  record per owner that ever read it.

  `claim-render-owned-ref!` is `#?(:cljs ...)`-only and fires only under the
  `:adapter/reactive-owner` hook the ratom family publishes, so both ratom
  adapters run the same scenarios, with a `make-reaction` as the owning render.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.ratom :as stock-ratom]
            [reagent2.ratom :as slim-ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

(def ^:private configs
  [{:label    "reagent-slim"
    :adapter  rf.adapter.reagent-slim/adapter
    :ratom    slim-ratom/atom
    :reaction slim-ratom/make-reaction
    :start!   slim-ratom/activate!
    :flush!   slim-ratom/flush!
    :release! slim-ratom/dispose!}
   {:label    "reagent"
    :adapter  rf.adapter.reagent/adapter
    :ratom    stock-ratom/atom
    :reaction stock-ratom/make-reaction
    :start!   stock-ratom/run
    :flush!   stock-ratom/flush!
    :release! stock-ratom/dispose!}])

(def ^:private frame-id ::frame)

(def ^:private cycles 5)

(defn- each-adapter
  "Run `scenario` once per ratom adapter, each from a cold-started lifecycle."
  [scenario]
  (doseq [{:keys [label adapter] :as config} configs]
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (reset! rf.frame/frames {})
    (rf/init! adapter)
    (rf.frame/ensure-default-frame!)
    (try
      (testing label
        (rf/make-frame {:id frame-id})
        (rf/reg-event ::seed (fn [_ _] {:db {:n 1 :details "d"}}))
        (rf/reg-sub ::n (fn [db _] (:n db)))
        (rf/reg-sub ::details (fn [db _] (:details db)))
        (rf/dispatch-sync [::seed] {:frame frame-id})
        (scenario config))
      (finally
        (when (rf.substrate.adapter/current-adapter)
          (rf.substrate.adapter/dispose-adapter!))
        (reset! rf.frame/frames {})
        (rf.substrate.adapter/reset-lifecycle-state-for-tests!)))))

(defn- cached [query-v]
  (some-> (get @rf.frame/frames frame-id) :sub-cache deref (get query-v)))

(defn- owner-harness
  "An owning reaction whose body is `(read-fn @lever note!)`; `pull!` is one
  re-render with a new lever value, and `note!` records each reaction read."
  [{:keys [ratom reaction start! flush! release!]} initial read-fn]
  (let [lever   (ratom initial)
        renders (volatile! 0)
        seen    (volatile! [])
        owner   (reaction (fn []
                            (vswap! renders inc)
                            (read-fn @lever (fn [r] (vswap! seen conj r) r))))]
    (start! owner)
    {:owner    owner
     :renders  renders
     :seen     seen
     :pull!    (fn [v] (reset! lever v) (flush!))
     :dispose! (fn [] (release! owner))}))

(defn- callback-count
  "On-dispose callbacks `reaction` carries; both ratom kernels keep them in
  `on-dispose-arr`."
  [reaction]
  (or (some-> (.-on-dispose-arr ^clj reaction) .-length) 0))

(defn- holding-keys [owner]
  (set (keys (some-> (.-rfSubRefs ^js owner) deref))))

(deftest render-owned-toggled-read-is-not-retained
  ;; Each OFF render drops the conditional reaction's last watcher, disposing
  ;; and evicting it; each ON render builds and claims a fresh one.
  (each-adapter
    (fn [config]
      (let [{:keys [owner renders seen pull! dispose!]}
            (owner-harness config true
                           (fn [show? note!]
                             [@(rf/subscribe [::n] {:frame frame-id})
                              (when show?
                                @(note! (rf/subscribe [::details] {:frame frame-id})))]))
            callbacks-after-first-render (callback-count owner)]
        (dotimes [_ cycles]
          (pull! false)
          (pull! true))
        (pull! false)
        ;; The first three slots are preconditions: every toggle re-ran the
        ;; owner, each ON render got a NEW reaction, and the last one was evicted.
        (is (= [(+ 2 (* 2 cycles)) (inc cycles) nil
                callbacks-after-first-render #{[frame-id [::n]]} 1]
               [@renders (count (into #{} (map goog/getUid) @seen)) (cached [::details])
                (callback-count owner) (holding-keys owner) (:ref-count (cached [::n]))]))
        (dispose!)
        (is (nil? (cached [::n])) "disposing the owner still releases what it holds")))))

(deftest render-owned-owner-churn-leaves-the-reaction-clean
  ;; A keeper holds the reaction alive while visiting owners read it and dispose.
  (each-adapter
    (fn [config]
      (let [read-n     (fn [_ note!] @(note! (rf/subscribe [::n] {:frame frame-id})))
            keeper     (owner-harness config nil read-n)
            r          (:reaction (cached [::n]))
            callbacks0 (callback-count r)]
        (dotimes [_ cycles]
          (let [visitor (owner-harness config nil read-n)]
            (is (= 2 (:ref-count (cached [::n]))) "a visiting owner holds its own reference")
            ((:dispose! visitor))))
        (is (= [true 1 callbacks0 true]
               [(identical? r (:reaction (cached [::n]))) (:ref-count (cached [::n]))
                (callback-count r) (<= (or (some-> (.-rfSubHolders ^js r) .-size) 0) 1)]))
        ((:dispose! keeper))
        (is (nil? (cached [::n])) "the keeper's dispose releases the last reference")))))

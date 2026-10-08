(ns re-frame.subs-render-owned-frame-slot-cljs-test
  "A render-owned reference (Spec 006 §Ratom-family lifetime) is ONE reference
  per (owning reaction, cache SLOT), and a slot is (frame, query): one render
  may read the same query from two frames, two cached reactions under one
  cache key.

  The key must be exactly as fine as a slot. Too coarse (the query alone, or
  the frame alone) and two slots collapse into one holding, so each render's
  claim fails the identity guard against the other's and the `:ref-count`s
  climb with renders rather than readers. Too fine (per read) and every read
  looks new, with the same result. One owner reading the same query from two
  frames, a duplicate of one of those reads, and a second query in the first
  frame catches all three.

  `claim-render-owned-ref!` is `#?(:cljs ...)`-only and fires only under the
  `:adapter/reactive-owner` hook the ratom family publishes, so this runs on
  both ratom adapters. A `make-reaction` started with `activate!`/`run` and
  re-run by `flush!` IS the owning render reaction, and each re-render is
  driven off a ratom the test owns, so a render is one hop from the test.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.ratom :as stock-ratom]
            [reagent2.ratom :as slim-ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

;; reagent2 dropped stock Reagent's `IRunnable` and spells the first capture
;; run `activate!`; everything else lines up name for name.
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

(defn- with-adapter
  "Cold-start `adapter`, run `body-fn`, then tear the lifecycle back down so the
  next adapter starts from a never-installed state."
  [adapter body-fn]
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (reset! rf.frame/frames {})
  (rf/init! adapter)
  (rf.frame/ensure-default-frame!)
  (try
    (body-fn)
    (finally
      (when (rf.substrate.adapter/current-adapter)
        (rf.substrate.adapter/dispose-adapter!))
      (reset! rf.frame/frames {})
      (rf.substrate.adapter/reset-lifecycle-state-for-tests!))))

(defn- ref-count [frame-id query-v]
  (some-> (get @rf.frame/frames frame-id) :sub-cache deref (get query-v) :ref-count))

(defn- register-fixtures! []
  (rf/make-frame {:id :kk986/frame-a})
  (rf/make-frame {:id :kk986/frame-b})
  (rf/reg-event :kk986/seed (fn [{:keys [db]} [_ n m]] {:db (assoc db :n n :m m)}))
  (rf/reg-sub :kk986/n (fn [db _] (:n db)))
  (rf/reg-sub :kk986/m (fn [db _] (:m db)))
  (rf/dispatch-sync [:kk986/seed 1 11] {:frame :kk986/frame-a})
  (rf/dispatch-sync [:kk986/seed 2 22] {:frame :kk986/frame-b}))

(deftest render-owned-refs-hold-one-reference-per-slot
  (doseq [{:keys [label adapter ratom reaction start! flush! release!]} configs]
    (with-adapter adapter
      (fn []
        (testing label
          (register-fixtures!)
          (let [tick    (ratom 0)
                renders (volatile! 0)
                value   (volatile! nil)
                owner   (reaction
                          (fn []
                            @tick
                            (vswap! renders inc)
                            (vreset! value
                                     [@(rf/subscribe [:kk986/n] {:frame :kk986/frame-a})
                                      @(rf/subscribe [:kk986/n] {:frame :kk986/frame-b})
                                      @(rf/subscribe [:kk986/n] {:frame :kk986/frame-a})
                                      @(rf/subscribe [:kk986/m] {:frame :kk986/frame-a})])))
                counts  (fn []
                          [(ref-count :kk986/frame-a [:kk986/n])
                           (ref-count :kk986/frame-b [:kk986/n])
                           (ref-count :kk986/frame-a [:kk986/m])])]
            (start! owner)
            (is (= [1 [1 2 1 11] [1 1 1]] [@renders @value (counts)]))
            ;; The render counter guards against a body that silently stopped
            ;; re-running, which would leave every count at 1 for the wrong reason.
            (doseq [render-no [2 3 4]]
              (swap! tick inc)
              (flush!)
              (is (= [render-no [1 1 1]] [@renders (counts)])))
            (release! owner)
            (is (= [nil nil nil] (counts)) "disposing the owner releases each slot once")))))))

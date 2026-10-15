(ns re-frame.m11-recipe-reactive-owner-cljs-test
  "The published M-11 imperative widget recipe owns one live tracker per mount and releases it at unmount."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(def ^:private fid ::gauge-frame)
(def ^:private gauge-query [::gauge-value])

(defn- setup! []
  (rf/make-frame {:id fid :doc "M-11 recipe fixture frame"})
  (rf/reg-event ::seed (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (rf/reg-sub ::gauge-value (fn [db _] (:n db)))
  (rf/dispatch-sync [::seed 10] {:frame fid}))

(defn- write! [n]
  (rf/dispatch-sync [::seed n] {:frame fid}))

(defn- ref-count []
  (or (:ref-count (get @(:sub-cache (rf.frame/frame fid)) gauge-query)) 0))

(defn- recipe-mount!
  [handle feed!]
  (let [{:keys [subscribe]} handle
        reaction (subscribe gauge-query)]
    {:reaction reaction
     :driver   (r/track! (fn [] (feed! @reaction)))}))

(defn- recipe-unmount!
  [{:keys [driver reaction]}]
  (some-> driver r/dispose!)
  (rf/unsubscribe reaction))

(defn- add-watch-mount!
  [handle feed!]
  (let [{:keys [subscribe]} handle
        reaction  (subscribe gauge-query)
        watch-key (gensym "gauge-feed-")]
    (feed! @reaction)                                  ; plain deref — no context
    (add-watch reaction watch-key (fn [_ _ _ v] (feed! v)))
    {:reaction reaction :watch-key watch-key}))

(defn- add-watch-unmount! [{:keys [reaction watch-key]}]
  (remove-watch reaction watch-key)
  (rf/unsubscribe reaction))

(defn- recorder []
  (let [log (atom [])]
    [log (fn [v] (swap! log conj v))]))

;; The naive recipe is the negative control for the per-mount tracker below.
(deftest the-add-watch-shape-is-deaf-after-mount
  (testing "plain deref and add-watch seed once but receive no changes"
    (setup!)
    (let [handle (rf/capture-frame fid)
          [log feed!] (recorder)
          mounted (add-watch-mount! handle feed!)]
      (try
        (is (= [10] @log))

        (write! 20)
        (r/flush)
        (ratom/flush!)
        (is (= [10] @log))
        (finally
          (add-watch-unmount! mounted))))))

(deftest the-repaired-recipe-feeds-its-widget-on-every-commit
  (testing "track! seeds eagerly and feeds each change once"
    (setup!)
    (let [handle (rf/capture-frame fid)
          [log feed!] (recorder)
          mounted (recipe-mount! handle feed!)]
      (try
        (is (= [10] @log))

        (write! 20)
        (is (= [10 20] @log))

        (write! 30)
        (r/flush)
        (write! 30)
        (r/flush)
        (is (= [10 20 30] @log))
        (finally
          (recipe-unmount! mounted)))

      (testing "unmount stops feeds and releases the cache slot"
        (write! 40)
        (r/flush)
        (is (= [[10 20 30] 0] [@log (ref-count)]))))))

(deftest two-mounts-of-one-query-need-no-watch-keys
  (testing "shared query reactions have independent per-mount trackers"
    (setup!)
    (let [handle (rf/capture-frame fid)
          [log-a feed-a!] (recorder)
          [log-b feed-b!] (recorder)
          mount-a (recipe-mount! handle feed-a!)
          mount-b (recipe-mount! handle feed-b!)]
      (is (= [true 2 [10] [10]]
             [(= (:reaction mount-a) (:reaction mount-b)) (ref-count) @log-a @log-b]))

      (write! 20)
      (r/flush)
      (is (= [[10 20] [10 20]] [@log-a @log-b]))

      (recipe-unmount! mount-a)

      (write! 30)
      (r/flush)
      (is (= [[10 20] [10 20 30]] [@log-a @log-b]))

      (recipe-unmount! mount-b)
      (is (zero? (ref-count))))))

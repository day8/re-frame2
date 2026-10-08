(ns re-frame.adapter.test-react-dispose-sub-cache-cljs-test
  "test-react's `dispose-adapter!` clears every live frame's sub-cache. Its
  derived values never auto-dispose, so without the walk a cached slot would
  survive a dispose/reinstall and a later subscribe would read a stale value."
  (:require [re-frame.adapter.test-react :as rf.adapter.test-react]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]
            #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.adapter.test-react/adapter}))

(deftest dispose-adapter-clears-sub-caches-across-multiple-frames
  (rf/reg-event ::seed (fn [_ctx [_ v]] {:db {:n v}}))
  (rf/reg-sub ::n (fn [db _] (:n db)))
  (let [other  (rf.frame/make-anon-frame-record! {:doc "second frame"})
        frames [:rf/default other]
        slots  #(mapv (fn [f] (set (keys @(:sub-cache (rf.frame/frame f))))) frames)]
    (rf/dispatch-sync [::seed 1] {:frame :rf/default})
    (rf/dispatch-sync [::seed 2] {:frame other})
    (doseq [f frames] (rf.subs/subscribe [::n] {:frame f}))
    (is (= [#{[::n]} #{[::n]}] (slots)) "control: both frames carry a slot")
    (rf.substrate.adapter/dispose-adapter!)
    (is (= [#{} #{}] (slots)) "the walk covers every frame, not just :rf/default")
    (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter)))

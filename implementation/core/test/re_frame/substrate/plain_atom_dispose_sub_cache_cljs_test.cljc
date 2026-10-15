(ns re-frame.substrate.plain-atom-dispose-sub-cache-cljs-test
  "plain-atom's `dispose-adapter!` clears every live frame's sub-cache, on both
  runtimes. Frames outlive the adapter, so without the walk a cached slot would
  survive a dispose/reinstall and a later subscribe would reuse the pre-dispose
  derived value. The SSR adapter's disposer is pinned by
  `re-frame.ssr.derived-value-disposal-cljs-test`."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest dispose-adapter-clears-sub-caches-across-multiple-frames
  (rf/reg-event ::seed (fn [_ctx [_ v]] {:db {:n v}}))
  (rf/reg-sub ::n (fn [db _] (:n db)))
  (rf/reg-sub ::n+1 {:inputs [[::n]]} (fn [[n] _] (inc n)))
  (let [other      (rf.frame/make-anon-frame-record! {:doc "second frame"})
        frames     [:rf/default other]
        slots      #(mapv (fn [f] (set (keys @(:sub-cache (rf.frame/frame f))))) frames)
        subscribe! #(mapv (fn [f] (rf.subs/subscribe [::n+1] {:frame f})) frames)]
    (rf/dispatch-sync [::seed 1] {:frame :rf/default})
    (rf/dispatch-sync [::seed 2] {:frame other})
    (let [before (subscribe!)]
      (is (= [#{[::n] [::n+1]} #{[::n] [::n+1]}] (slots))
          "control: both frames hold the layer-2 sub and its input")
      (rf.substrate.adapter/dispose-adapter!)
      (is (= [#{} #{}] (slots)) "the walk covers every frame, not just :rf/default")
      (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
      (let [after (subscribe!)]
        (is (= [false false] (mapv identical? before after))
            "a re-subscribe builds a fresh derived value, not the pre-dispose one")
        (is (= [2 3] (mapv deref after)))))))

(ns re-frame.plain-atom-dispose-cljs-test
  "The plain-atom adapter on CLJS (the SSR / headless-on-CLJS host) honours the
  sub-cache's symmetric input-release contract (Spec 006 §Reference counting and
  disposal). On CLJS `re-frame.interop` routes `add-on-dispose!` / `dispose!`
  through late-bind hooks the plain-atom adapter publishes, and its derived
  value reifies the disposal protocol; without either, a layer-2+ sub's input
  ref-counts would never drop on evict. The JVM side is pinned by
  `re-frame.sub-cache-test`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.disposable :as rf.disposable]
            [re-frame.frame :as rf.frame]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- ref-counts
  "query-v -> :ref-count for every slot in `:rf/default`'s sub-cache."
  []
  (into {} (map (fn [[k v]] [k (:ref-count v)])) @(:sub-cache (rf.frame/frame :rf/default))))

(deftest layer-2-disposal-respects-shared-inputs-on-cljs-plain-atom
  (rf/reg-event :init (fn [_ _] {:db {:a 2 :b 3 :c 4}}))
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :b (fn [db _] (:b db)))
  (rf/reg-sub :c (fn [db _] (:c db)))
  (rf/reg-sub :ab {:inputs [[:a] [:b]]} (fn [[a b] _] (+ a b)))
  (rf/reg-sub :ac {:inputs [[:a] [:c]]} (fn [[a c] _] (+ a c)))
  (rf/dispatch-sync [:init])
  (rf.subs/subscribe [:ab] {:frame :rf/default})
  (rf.subs/subscribe [:ac] {:frame :rf/default})
  (is (= {[:ab] 1 [:ac] 1 [:a] 2 [:b] 1 [:c] 1} (ref-counts)))
  (rf.subs/unsubscribe :rf/default [:ab])
  (is (= {[:ac] 1 [:a] 1 [:c] 1} (ref-counts))
      "the shared input dropped by exactly one; :b went with its only holder")
  (rf.subs/unsubscribe :rf/default [:ac])
  (is (= {} (ref-counts))))

(deftest dispose-is-re-entrant-safe-on-cljs-plain-atom
  ;; A callback that re-enters `-dispose`, and a second plain `-dispose`, must
  ;; leave every callback fired exactly once; a double-fired input release
  ;; would evict a still-referenced input.
  (let [dv    ((:make-derived-value rf.substrate.plain-atom/adapter) [(atom 0)] identity)
        fires (atom 0)]
    (rf.disposable/-add-on-dispose dv (fn [] (rf.disposable/-dispose dv)))
    (rf.disposable/-add-on-dispose dv (fn [] (swap! fires inc)))
    (rf.disposable/-dispose dv)
    (rf.disposable/-dispose dv)
    (is (= 1 @fires))))

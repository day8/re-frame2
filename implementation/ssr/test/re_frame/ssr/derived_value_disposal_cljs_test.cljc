(ns re-frame.ssr.derived-value-disposal-cljs-test
  "A layer-2 subscription disposed under the SSR adapter releases its declared
  inputs, on both runtimes.

  The sub-cache releases a layer-2 entry's inputs from the on-dispose callback
  it registers through `re-frame.interop/add-on-dispose!`, and fires it through
  `re-frame.interop/dispose!` when the entry is evicted. On CLJS both calls are
  routed to the installed adapter, so a derived value that adapter cannot
  dispose keeps every input at its ref-count until the frame is destroyed. A
  per-request frame bounds that; a long-lived frame accumulates it.

  Subs are registered INSIDE each test body: in the shared node process a
  sibling namespace's `registrar/clear-all!` wipes ns-load-time registrations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]))

;; COLD-START the adapter slot: this ns shares the node bundle with suites
;; that seat other adapters, and `init!` raises when handed a different one.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

(def ^:private counter (atom 0))

(defn- fresh-frame!
  "A frame under an id no other test in this shared process has used."
  []
  (let [fid (keyword "rf.ssr-disposal" (str "f" (swap! counter inc)))]
    (rf/make-frame {:id fid})
    fid))

(defn- cache-of [fid]
  (:sub-cache (rf.frame/frame fid)))

(defn- reg-layered-subs! []
  (rf/reg-sub ::a (fn [db _] (:a db)))
  (rf/reg-sub ::ab {:inputs [[::a]]} (fn [[a] _] a)))

(deftest a-disposed-layer-2-sub-releases-its-inputs
  (reg-layered-subs!)
  (let [fid   (fresh-frame!)
        cache (cache-of fid)]
    (try
      ;; Twice: a leaked input reference would survive the first cycle and
      ;; still be there, one higher, after the second.
      (doseq [cycle [1 2]]
        (testing (str "subscribe / unsubscribe cycle " cycle)
          (let [r (rf/subscribe [::ab] {:frame fid})]
            @r
            (is (= 1 (get-in @cache [[::a] :ref-count]))
                "while the layer-2 entry is live it holds exactly one reference on its input")
            (rf/unsubscribe r)
            (is (nil? (get @cache [::ab]))
                "the layer-2 entry is evicted at its last reference")
            (is (nil? (get @cache [::a]))
                "and disposing it released its input, which is evicted with it"))))
      (finally (rf/destroy-frame! fid)))))

(deftest destroying-and-remaking-the-frame-leaves-no-cache-entries
  (reg-layered-subs!)
  (let [fid (fresh-frame!)]
    @(rf/subscribe [::ab] {:frame fid})
    (rf/destroy-frame! fid)
    (rf/make-frame {:id fid})
    (let [cache (cache-of fid)]
      (try
        (is (empty? @cache) "the re-made frame starts with no cache entries")
        (let [r (rf/subscribe [::ab] {:frame fid})]
          @r
          (rf/unsubscribe r))
        (is (empty? @cache)
            "a subscribe / unsubscribe cycle on the re-made frame leaves no cache entries")
        (finally (rf/destroy-frame! fid))))))

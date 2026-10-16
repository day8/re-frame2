(ns re-frame.ssr-multi-frame-isolation-test
  "Per-frame hydration isolation on the `testbeds/ssr_multi_frame` shape:
  three frames, one payload bundle of per-frame slices, three
  `[:rf/hydrate slice] {:frame fid}` dispatches. Each slice lands on its own
  frame only (Spec 011 §Frames are per-request, Spec 002 §Routing the
  dispatch envelope), and later dispatches stay frame-isolated. The test
  names are the testbed migration's targets (tools/story/spec/Migration-Audit.md)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; Frame ids and payload mirror testbeds/ssr_multi_frame.
(def ^:private frame-a   :counter/a)
(def ^:private frame-b   :counter/b)
(def ^:private frame-log :log)

(defn- route-slice [route-id]
  {:rf.runtime/routing {:current {:route-id route-id}}})

(def ^:private per-frame-payload
  {frame-a   {:rf/version 1 :rf/app-db {:n 10} :rf/runtime-db (route-slice :page/a)}
   frame-b   {:rf/version 1 :rf/app-db {:n 99} :rf/runtime-db (route-slice :page/b)}
   frame-log {:rf/version 1
              :rf/app-db  {:entries [{:from :ssr :note "hello"} {:from :ssr :note "world"}]}
              :rf/runtime-db (route-slice :page/log)}})

(defn- bootstrap-and-hydrate! []
  (rf/reg-event ::counter-init (fn [_ _] {:db {:n 0}}))
  (rf/reg-event ::log-init     (fn [_ _] {:db {:entries []}}))
  (rf/reg-event ::inc          (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/reg-sub :n       (fn [db _] (:n db)))
  (rf/reg-sub :entries (fn [db _] (:entries db)))
  ;; The route slice is runtime-db state.
  (rf.subs/reg-runtime-sub :route-id (fn [rt _] (get-in rt [:rf.runtime/routing :current :route-id])))
  (rf/make-frame {:id frame-a   :initial-events [[::counter-init]]})
  (rf/make-frame {:id frame-b   :initial-events [[::counter-init]]})
  (rf/make-frame {:id frame-log :initial-events [[::log-init]]})
  (doseq [[fid slice] per-frame-payload]
    (rf/dispatch-sync [:rf/hydrate slice] {:frame fid})))

(defn- sub-in [query fid] (rf/subscribe-once query {:frame fid}))

(deftest multi-frame-hydrate-seeds-each-frame-from-its-own-payload-slice
  (bootstrap-and-hydrate!)
  (is (= [10 99 (get-in per-frame-payload [frame-log :rf/app-db :entries])]
         [(sub-in [:n] frame-a) (sub-in [:n] frame-b) (sub-in [:entries] frame-log)])))

(defn- stashed-hydration [fid]
  (get-in (rf/frame-state-value fid) [:rf.db/runtime :rf.runtime/ssr :hydration]))

(deftest multi-frame-hydrate-stashes-per-frame-hydration-metadata
  (testing "each hydrated frame carries the metadata; the never-hydrated
            default frame does not"
    (bootstrap-and-hydrate!)
    (is (= [true true true false]
           (mapv #(some? (stashed-hydration %)) [frame-a frame-b frame-log :rf/default])))))

(deftest multi-frame-subscribe-once-resolves-against-explicit-frame-id
  (testing "the same query-v resolves each explicit frame's own value"
    (bootstrap-and-hydrate!)
    (is (= [:page/a :page/b :page/log]
           (mapv #(sub-in [:route-id] %) [frame-a frame-b frame-log])))))

(deftest multi-frame-dispatch-isolation-per-frame
  (bootstrap-and-hydrate!)
  (rf/dispatch-sync [::inc] {:frame frame-a})
  (is (= [11 99] [(sub-in [:n] frame-a) (sub-in [:n] frame-b)]))
  (rf/dispatch-sync [::inc] {:frame frame-b})
  (rf/dispatch-sync [::inc] {:frame frame-b})
  (is (= [11 101] [(sub-in [:n] frame-a) (sub-in [:n] frame-b)])))

(ns re-frame.event-metadata-carriage-test
  "The dispatched event vector reaches its handler as itself, metadata
  included, by every dispatch path — `dispatch`, `dispatch-sync`, an `:fx`
  `:dispatch` and a `:dispatch-later` — and an interceptor reading the
  `:event` coeffect sees the same metadata the handler does (Spec 002
  §Routing: the dispatch envelope).

  Every claim reads handler and interceptor arguments, so the namespace runs
  under `clojure -M:test:prod-gate` too."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(def ^:private tag {:app/origin :sso})

(deftest event-vector-metadata-reaches-the-handler-by-every-dispatch-path
  (let [seen (atom [])
        done (atom nil)]
    (rf/reg-interceptor :meta/guard
      {:before (fn [ctx]
                 (swap! seen conj [:interceptor (meta (rf.interceptor/get-coeffect ctx :event))])
                 ctx)})
    (rf/reg-event :meta/target {:interceptors [:meta/guard]}
      (fn [cofx event]
        (swap! seen conj [:handler (meta event) (meta (:event cofx))])
        (deliver @done true)
        {}))
    (rf/reg-event :meta/via-fx
      (fn [_ _] {:fx [[:dispatch ^{:app/origin :sso} [:meta/target]]]}))
    (rf/reg-event :meta/via-later
      (fn [_ _] {:fx [[:dispatch-later {:ms 1 :event ^{:app/origin :sso} [:meta/target]}]]}))
    (doseq [[label run!]
            [["dispatch-sync"   #(rf/dispatch-sync ^{:app/origin :sso} [:meta/target])]
             ["dispatch"        #(rf/dispatch ^{:app/origin :sso} [:meta/target])]
             [":fx :dispatch"   #(rf/dispatch-sync [:meta/via-fx])]
             [":dispatch-later" #(rf/dispatch-sync [:meta/via-later])]]]
      (testing label
        (reset! seen [])
        (reset! done (promise))
        (run!)
        (is (true? (deref @done 2000 false)) "the target handler ran")
        (is (= [[:interceptor tag] [:handler tag tag]] @seen)
            "the interceptor's :event coeffect, the handler's event argument and its :event coeffect all carry the metadata")))))

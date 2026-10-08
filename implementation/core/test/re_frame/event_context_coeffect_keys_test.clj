(ns re-frame.event-context-coeffect-keys-test
  "The framework-injected coeffect key set is exact (Spec 002 §Event context
  threads both partitions). The frame stamp travels only as `:rf.frame/id`,
  never as a bare `:frame`, and `:trace-id` joins only when the dispatch
  threads one, so a stray key cannot silently ride into every handler's
  coeffects.

  The `:frame` keys that remain legitimate are not coeffects: the `:frame`
  dispatch and subscribe opt, the envelope's `:frame`, the fx-handler and HTTP
  interceptor ctx `:frame`, and trace and error-record `:frame` tags."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (when-let [clear-schemas! (rf.late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- capture-coeffects
  "Dispatch `[:capture]` on `frame-id` with `opts` and return the coeffects map
  the handler saw."
  [frame-id opts]
  (let [captured (atom nil)]
    (rf/reg-interceptor :capture/probe
      {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
    (rf/reg-event :capture
      {:interceptors [:capture/probe]}
      (fn [_ _] {}))
    (rf/dispatch-sync [:capture] (assoc opts :frame frame-id))
    @captured))

(deftest framework-coeffect-key-set-is-exact
  (rf/make-frame {:id :ck/exact})
  (let [framework-keys #{:db :event :rf.db/runtime :rf.frame/id :rf.cofx :rf.cofx/mint-policy :source}]
    (doseq [[label opts expected] [["a vanilla event" {} framework-keys]
                                   ["a threaded :trace-id adds only :trace-id"
                                    {:trace-id "tid-1"} (conj framework-keys :trace-id)]]]
      (testing label
        (let [cofx (capture-coeffects :ck/exact opts)]
          (is (= expected (set (keys cofx))))
          (is (= :ck/exact (:rf.frame/id cofx))))))))

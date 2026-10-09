(ns re-frame.success-path-call-site-test
  "`:rf.trace/call-site` — the invocation coord the `dispatch` / `dispatch-sync`
  macro stamps — rides success-path trace events at the top level, as it rides
  error events (`re-frame.source-coord-jvm-test`). It is dev-only by design:
  under `-Dre-frame.debug=false` the macro builds no coord, so every deftest is
  `^:requires-debug` and the production-gate lane skips it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

(deftest ^:requires-debug event-dispatched-success-carries-call-site
  (rf/reg-event :rf2-twt7m/noop (fn [_ _] {}))
  (let [[enqueue] (events-of (record-traces #(rf/dispatch-sync [:rf2-twt7m/noop]))
                             :rf.event/dispatched)
        cs        (:rf.trace/call-site enqueue)]
    (is (symbol? (:ns cs)))
    (is (integer? (:line cs)))
    (is (re-find #"success_path_call_site_test" (:file cs))
        (str ":file should point at this test file — got " (:file cs)))
    (is (not (contains? (:tags enqueue) :rf.trace/call-site))
        ":rf.trace/call-site rides at top level, not under :tags")))

(deftest ^:requires-debug cascade-success-traces-carry-call-site
  (rf/reg-fx :rf2-twt7m/my-fx (fn [_ _] :ok))
  (rf/reg-event :rf2-twt7m/cascade
    (fn [_ _] {:db {:n 1} :fx [[:rf2-twt7m/my-fx {}]]}))
  (let [evs (record-traces #(rf/dispatch-sync [:rf2-twt7m/cascade]))]
    (is (= {:rf.event/db-changed true :rf.fx/do-fx true :rf.fx/handled true}
           (into {} (for [op [:rf.event/db-changed :rf.fx/do-fx :rf.fx/handled]]
                      [op (contains? (first (events-of evs op)) :rf.trace/call-site)])))
        "every emit inside the cascade carries the dispatch's call-site")))

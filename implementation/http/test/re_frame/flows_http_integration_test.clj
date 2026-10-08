(ns re-frame.flows-http-integration-test
  "http × flows × cancellation. It lives in the http artefact because http's
  test classpath carries flows, while ssr (home of the other flows
  composition tests) does not depend on http.

  A cancel handler typically returns
  `{:db (update db :http/in-flight dissoc id) :fx [[:rf.http/managed-abort id]]}`.
  The pending :db runs through flows before the single install, and :fx walks
  only after it. So a flow over the mirrored in-flight slot lands its cleared
  value in the same install as the dissoc; and a flow throw aborts the whole
  event before install, so the abort fx must not fire against a request
  app-db still calls pending."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; rf/reg-flow needs the flows artefact loaded.
            [re-frame.flows]
            ;; Registers the :rf.http/managed-abort fx.
            [re-frame.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(def ^:dynamic ^:private *captured* nil)

(def ^:private reset-runtime-fixture
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(use-fixtures :each
  (fn [test-fn]
    (reset-runtime-fixture
      (fn []
        (let [captured (atom [])]
          (binding [*captured* captured]
            (rf.trace.tooling/register-listener! ::flows-http-recorder #(swap! captured conj %))
            (try
              (test-fn)
              (finally
                (rf.trace.tooling/unregister-listener! ::flows-http-recorder)))))))))

(defn- prime-in-flight!
  "Seed an in-flight handle as `:rf.http/managed` records one, including the
  `:frame` that is part of the registry key, with a recording `abort-fn`."
  [request-id abort-fn]
  (rf.http.registry/seed-in-flight-for-test!
    {:request-id request-id
     :actor-id   nil
     :frame      :rf/default
     :url        (str "/api/" (name request-id))
     :abort-fn   abort-fn}))

(defn- reg-issue-and-cancel! []
  (rf/reg-event :http/issue
    (fn [{:keys [db]} [_ request-id]]
      {:db (assoc-in db [:http/in-flight request-id] true)}))
  (rf/reg-event :http/cancel
    (fn [{:keys [db]} [_ request-id]]
      {:db (update db :http/in-flight dissoc request-id)
       :fx [[:rf.http/managed-abort request-id]]})))

(deftest cancellation-event-flow-reevals-over-cleared-in-flight-slot-atomically
  (let [abort-calls (atom [])
        flow-evals  (atom [])]
    (prime-in-flight! :load-articles #(swap! abort-calls conj %))
    (rf/reg-flow :http/pending-count {:inputs [[:http/in-flight]] :output-path [:derived :pending-count]}
                 (fn [in-flight]
                   (swap! flow-evals conj (set (keys in-flight)))
                   (count in-flight)))
    (reg-issue-and-cancel!)
    (rf/dispatch-sync [:http/issue :load-articles])
    (reset! flow-evals [])
    (rf/dispatch-sync [:http/cancel :load-articles])
    (is (= {:http/in-flight {} :derived {:pending-count 0}}
           (select-keys (rf/app-db-value :rf/default) [:http/in-flight :derived]))
        "the cleared count landed in the same install as the dissoc")
    (is (= [#{}] @flow-evals) "one flow eval, over the post-handler db")
    (is (= [:user] @abort-calls) "the abort fx fired once, after install")))

(deftest flow-throw-on-cancellation-event-aborts-managed-abort-fx-too
  (let [abort-calls (atom [])]
    (prime-in-flight! :load-articles #(swap! abort-calls conj %))
    (reg-issue-and-cancel!)
    ;; Seed before the throwing flow exists.
    (rf/dispatch-sync [:http/issue :load-articles])
    (let [baseline-db (rf/app-db-value :rf/default)]
      (rf/reg-flow :http/boom {:inputs [[:http/in-flight]] :output-path [:derived :http-doomed]}
                   (fn [_] (throw (ex-info "flow boom on cancel" {}))))
      (reset! *captured* [])
      (rf/dispatch-sync [:http/cancel :load-articles])
      (is (= baseline-db (rf/app-db-value :rf/default)) "the dissoc was discarded with the flow's write")
      (is (empty? @abort-calls) "the abort fx did not fire")
      (is (= [true false]
             [(boolean (some #(= :rf.flow/failed (:operation %)) @*captured*))
              (boolean (some #(= :rf.event/db-changed (:operation %)) @*captured*))])
          "the flow failed and nothing installed"))))

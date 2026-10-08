(ns re-frame.event-emit-cljs-test
  "The always-on event-emit substrate: one tight record per processed event,
  and an `:outcome` that reports every failed dispatch as non-`:ok`. Runs on
  the JVM (both postures) and Node. Fan-out, unregister and listener-throw
  isolation are the shared registry's, pinned by `re-frame.emit-substrate-test`;
  the `:advanced` + `goog.DEBUG=false` witness is
  `re-frame.event-emit-elision-prod-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; The schema-validation and flow-run hooks come from the optional schemas and
;; flows artefacts; the outcome tests stub them through the late-bind table,
;; so the fixture restores that table, and resets those artefacts' registries
;; so another namespace's schema or flow cannot reject a clean dispatch here.
;; `clear-all!` wipes the whole registrar, which the shared `:node-test`
;; bundle needs back afterwards, so it is snapshotted and restored.
(defn- reset-runtime [test-fn]
  (let [registrar-before (rf.test-support/snapshot-registrar)]
    (rf.registrar/clear-all!)
    (reset! rf.frame/frames {})
    (rf.schemas/clear-schemas-by-frame!)
    (rf.flows/reset-flows!)
    (rf.trace.tooling/clear-listeners!)
    (rf.event-emit/clear-event-listeners!)
    ;; `init!` refuses a different adapter than the one seated, and the
    ;; `:node-test` bundle seats others, so destroy before seating.
    (rf/destroy-adapter!)
    (rf/init! rf.substrate.plain-atom/adapter)
    (rf/make-frame {:id :rf/default})
    (let [hooks-before @rf.late-bind/hooks]
      (try
        (rf/with-frame :rf/default
          (test-fn))
        (finally
          (reset! rf.late-bind/hooks hooks-before)
          (rf.late-bind/invalidate-cache! :schemas/validate-app-schema!)
          (rf.late-bind/invalidate-cache! :flows/run-flows-on-db)
          (rf.test-support/restore-registrar! registrar-before))))))

(use-fixtures :each reset-runtime)

(defn- record-events!
  "Register an event-emit listener and return the atom its records land in."
  []
  (let [seen (atom [])]
    (rf.event-emit/register-event-listener! :test/recorder #(swap! seen conj %))
    seen))

(deftest listener-fires-on-event
  (let [seen (record-events!)]
    (rf/reg-event :evt/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:evt/inc "payload"])
    (is (= [{:event [:evt/inc "payload"] :event-id :evt/inc :frame :rf/default :outcome :ok}]
           (mapv #(dissoc % :time :elapsed-ms) @seen))
        "one record per processed event, carrying only the tight keys")
    (is (number? (:time (first @seen))))
    (is (nat-int? (:elapsed-ms (first @seen))))))

(deftest listener-marks-handler-exception-as-error-outcome
  (let [seen (record-events!)]
    (rf/reg-event :evt/throw (fn [_ _] {:db (throw (ex-info "kaboom" {}))}))
    (rf/dispatch-sync [:evt/throw])
    (is (= [:error] (mapv :outcome @seen)))))

(deftest listener-marks-schema-rejection-as-non-ok-outcome
  ;; A rejected candidate app-db installs nothing, though the handler did not throw.
  (rf.late-bind/set-fn! :schemas/validate-app-schema!
                        (fn [_db-after _event-id _frame _continue?] false))
  (let [seen (record-events!)]
    (rf/reg-event :evt/writes (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/dispatch-sync [:evt/writes])
    (is (= [:rolled-back] (mapv :outcome @seen)))))

(deftest listener-marks-flow-throw-as-non-ok-outcome
  ;; A flow throw aborts the event: no install, so the handler's `:db` never lands.
  (rf.late-bind/set-fn! :flows/run-flows-on-db
                        (fn [_frame _db _runtime-db _exact-owner]
                          (throw (ex-info "flow output blew up"
                                          {:rf.flow/failed-id :flow/derived}))))
  (let [seen (record-events!)]
    (rf/reg-event :evt/writes (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/dispatch-sync [:evt/writes])
    (is (= [:flow-error] (mapv :outcome @seen)))
    (is (not (contains? (rf/app-db-value :rf/default) :n)))))

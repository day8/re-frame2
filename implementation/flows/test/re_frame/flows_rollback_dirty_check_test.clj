(ns re-frame.flows-rollback-dirty-check-test
  "The flow pass advances a frame's dirty-check rows and drains its queued
  output-path vacations into the pending `:db`. When the event then fails to
  commit — a schema rejection, a chain error, or a final-effects abort — that
  bookkeeping rolls back with the discarded candidate. A row left advanced
  would make the next same-input drain skip the flow, so its output would
  never materialise; a drained vacation would be lost.

  A predicate validator stands in for Malli (Spec 010 §Non-Malli validators)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(defn- with-schema-validator-reset [test-fn]
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (try
    (test-fn)
    (finally
      (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  with-schema-validator-reset)

(defn- db [] (rf/app-db-value :rf/default))

(deftest app-db-rejection-restores-flow-last-inputs-and-recomputes-on-next-clean-drain
  (rf.schemas/set-schema-fns!
    {:validate (fn [schema value] (boolean (schema value)))
     :explain  (fn [schema value] (when-not (schema value) {:failed value}))})
  (let [reject-out? (atom false)]
    (rf/reg-event :seed (fn [_ _] {:db {:n 1}}))
    (rf/reg-event :touch-other (fn [{:keys [db]} _] {:db (assoc db :other true)}))
    (rf/reg-app-schema [:out] {:frame :rf/default} (fn [out] (or (nil? out) (not @reject-out?))))
    (rf/dispatch-sync [:seed])
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:out]} (fn [n] (* 2 n)))
    (reset! reject-out? true)
    ;; The flow computes :out into the candidate, which the schema rejects.
    (rf/dispatch-sync [:touch-other])
    (is (= {:n 1} (db)))
    (reset! reject-out? false)
    ;; A same-input drain recomputes only because the row was rolled back.
    (rf/dispatch-sync [:touch-other])
    (is (= {:n 1 :out 2 :other true} (db)))))

(deftest chain-error-handler-throw-does-not-poison-fresh-flow-first-firing
  ;; The flows `:after` is skipped on an interceptor error: no flow trace, no
  ;; advanced row, so the next same-input drain still materialises the output.
  (rf/reg-event :seed (fn [_ _] {:db {:n 1}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-flow :derived {:inputs [[:n]] :output-path [:out]} (fn [n] (* n 10)))
  (rf/reg-event :boom (fn [_ _] (throw (ex-info "handler boom" {:src :test}))))
  (let [ops (atom [])]
    (rf/register-listener! :trace ::spurious (fn [ev] (swap! ops conj (:operation ev))))
    (try
      (rf/dispatch-sync [:boom])
      (finally
        (rf/unregister-listener! :trace ::spurious)))
    (is (not-any? #{:rf.flow/computed :rf.event/db-pending-post-flow} @ops)))
  (rf/dispatch-sync [:seed])
  (is (= {:n 1 :out 10} (db))))

(deftest chain-error-preserves-in-drain-queued-vacation
  ;; A vacation queued for the next drain survives an errored event and is
  ;; applied by the following clean one.
  (rf/reg-event :seed (fn [_ _] {:db {:n 5 :stale 99}}))
  (rf/reg-flow :keep {:inputs [[:n]] :output-path [:out-keep]} (fn [n] (* 7 n)))
  (rf/dispatch-sync [:seed])
  (rf.flows.registry/record-abandoned-output-path! :rf/default [:stale])
  (rf/reg-event :boom (fn [_ _] (throw (ex-info "boom" {:src :test}))))
  (rf/dispatch-sync [:boom])
  (is (= [#{[:stale]} {:n 5 :stale 99 :out-keep 35}]
         [(rf.flows.registry/abandoned-output-paths-snapshot :rf/default) (db)]))
  (rf/reg-event :touch (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
  (rf/dispatch-sync [:touch])
  (is (= [#{} {:n 5 :out-keep 35 :touched true}]
         [(rf.flows.registry/abandoned-output-paths-snapshot :rf/default) (db)])))

(deftest legacy-root-abort-restores-flow-dirty-check
  ;; A user `:after` inserts the retired `:rf/runtime` root after the in-chain
  ;; guard, so the flows `:after` runs cleanly and advances the row before the
  ;; commit aborts at the final-effects boundary.
  (rf/reg-event :seed (fn [_ _] {:db {:n 4}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-flow :derived {:inputs [[:n]] :output-path [:out]} (fn [n] (* n 10)))
  (rf/reg-interceptor :test/insert-legacy-root
                      {:after (fn [ctx] (update-in ctx [:effects :db] assoc :rf/runtime {}))})
  (rf/reg-event :legacy {:interceptors [:test/insert-legacy-root]} (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:legacy])
  (is (= {:n 4} (db)))
  (rf/dispatch-sync [:seed])
  (is (= {:n 4 :out 40} (db))))

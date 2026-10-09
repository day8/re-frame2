(ns re-frame.event-model-conformance-cljs-test
  "Adversarial conformance for the public one-form event model.

  Core's focused suites own individual `reg-event`, coeffect-policy and
  observability behaviour. This cross-artefact suite locks the boundaries that
  are easiest to weaken while those tests remain green: supplied recordable
  coeffects are validated at the durable boundary; the closed effect map is
  enforced at the final effects boundary, with no db-return convenience; only
  framework-authority handlers write `:rf.db/runtime` silently; and the
  `^:no-doc` retired forms throw, register nothing, and notify the always-on
  error channel.

  The fixture supplies an ambient `:rf/default` frame and clears the always-on
  listener registries between cases. These `.cljc` tests run on both JVM and
  CLJS."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(defn- clear-observation-state! []
  (rf.error-emit/clear-error-listeners!)
  (rf.event-emit/clear-event-listeners!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [test-fn]
    (clear-observation-state!)
    (test-fn)
    (clear-observation-state!)))

(defn- thrown-error-id
  "Call `thunk` and return the `:rf.error/id` of the ExceptionInfo it raises, or
  `:no-throw` if it did not throw."
  [thunk]
  (try
    (thunk)
    :no-throw
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) exception
      (:rf.error/id (ex-data exception)))))

(defn- traces-during
  "The trace events emitted while `thunk` runs."
  [thunk]
  (let [traces (atom [])]
    (rf/register-listener! :trace :evt-conf/recorder #(swap! traces conj %))
    (try (thunk) (finally (rf/unregister-listener! :trace :evt-conf/recorder)))
    @traces))

(defn- shape-recoveries
  "The `:recovery` of each `:rf.error/effect-map-shape` trace naming `offending-key`."
  [traces offending-key]
  (->> traces
       (filter #(and (= :rf.error/effect-map-shape (:operation %))
                     (= offending-key (get-in % [:tags :offending-key]))))
       (mapv :recovery)))

;; Spec-Schemas §`:rf.cofx`: the retired draft opt has no dedicated error id;
;; it rides the generic unknown-opt warning with a did-you-mean.
(deftest dispatch-opt-rf-world-inputs-is-the-generic-unknown-opt-with-did-you-mean
  (rf/reg-event :evt-conf/inspect-renamed (fn [_ _] {}))
  (let [warning (->> (traces-during #(rf/dispatch-sync [:evt-conf/inspect-renamed]
                                                       {:rf.world/inputs {:rf/time-ms 1781078400123}}))
                     (filter #(= :rf.warning/unknown-dispatch-opt (:operation %)))
                     first)]
    ;; The reason lists every known opt, `:rf.cofx` among them, so only the
    ;; hint's own wording proves the did-you-mean.
    (is (re-find #"did you mean `:rf\.cofx`\?" (str (get-in warning [:tags :reason]))))))

(deftest supplied-recordable-non-edn-value-is-cofx-value-invalid
  (let [handler-ran? (atom false)]
    (rf/reg-cofx :evt-conf/host-fact {:recordable? true :provided? true})
    (rf/reg-event :evt-conf/reads-host-fact
      {:rf.cofx/requires [:evt-conf/host-fact]}
      (fn [_ _] (reset! handler-ran? true) {}))
    (is (= [:rf.error/cofx-value-invalid false]
           [(thrown-error-id #(rf/dispatch-sync [:evt-conf/reads-host-fact]
                                                {:rf.cofx {:evt-conf/host-fact (fn [] :a-host-handle)}}))
            @handler-ran?])
        "a host value cannot cross the durable boundary, and the handler never runs")))

(defn- restore-hook! [hook-id previous]
  (if previous
    (rf.late-bind/set-fn! hook-id previous)
    (swap! rf.late-bind/hooks dissoc hook-id))
  (rf.late-bind/invalidate-cache! hook-id))

(deftest supplied-recordable-value-failing-schema-is-cofx-value-invalid-in-production
  ;; A stand-in `[:enum …]` validator exercises the schema hook without the
  ;; schemas artefact.
  (let [hook-ids [:schemas/validate-with-registered-fn :schemas/explain-with-registered-fn]
        previous (mapv rf.late-bind/get-fn hook-ids)]
    (try
      (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
        (fn [schema value]
          (and (vector? schema)
               (= :enum (first schema))
               (contains? (set (rest schema)) value))))
      (rf.late-bind/set-fn! :schemas/explain-with-registered-fn
        (fn [schema value] {:schema schema :value value :failed true}))
      (let [delivered-fact (atom ::unset)]
        (rf/reg-cofx :evt-conf/graded-fact
          {:recordable? true :provided? true :schema [:enum :allowed-a :allowed-b]})
        (rf/reg-event :evt-conf/reads-graded-fact
          {:rf.cofx/requires [:evt-conf/graded-fact]}
          (fn [{:keys [evt-conf/graded-fact]} _] (reset! delivered-fact graded-fact) {}))
        (is (= :rf.error/cofx-value-invalid
               (thrown-error-id #(rf/dispatch-sync [:evt-conf/reads-graded-fact]
                                                   {:rf.cofx {:evt-conf/graded-fact :not-allowed}}))))
        (rf/dispatch-sync [:evt-conf/reads-graded-fact] {:rf.cofx {:evt-conf/graded-fact :allowed-a}})
        (is (= :allowed-a @delivered-fact) "a value satisfying its :schema is delivered flat"))
      (finally
        (run! (fn [[hook-id prior]] (restore-hook! hook-id prior)) (map vector hook-ids previous))))))

;; There is no db-return convenience: a bare app-db-shaped map is a foreign
;; top-level effect key, refused before anything commits.
(deftest reg-event-bare-app-db-shaped-return-is-effect-map-shape-not-committed
  (rf/reg-event :evt-conf/bare-seed! (fn [{:keys [db]} _] {:db (assoc db :count :seeded)}))
  (rf/reg-event :evt-conf/bare-db-return (fn [_ _] {:count 1}))
  (rf/dispatch-sync [:evt-conf/bare-seed!])
  (let [traces (traces-during #(rf/dispatch-sync [:evt-conf/bare-db-return]))]
    (is (= :seeded (:count (rf/app-db-value :rf/default))) "the bare return was not committed")
    (is (= [:fix-effect] (shape-recoveries traces :count)))))

(deftest reg-event-app-handler-runtime-effect-keeps-the-diagnostic-unless-framework-authority
  (rf/reg-event :evt-conf/app-writes-runtime
    (fn [_ _] {:rf.db/runtime {:rf.runtime/marker :app-wrote}}))
  (rf/reg-event :evt-conf/fw-writes-runtime
    {:rf/framework-authority? true}
    (fn [_ _] {:rf.db/runtime {:rf.runtime/marker :fw-wrote}}))
  (let [recoveries (fn [event-id]
                     (->> (traces-during #(rf/dispatch-sync [event-id]))
                          (filter #(= :rf.warning/app-handler-runtime-effect (:operation %)))
                          (mapv :recovery)))]
    (is (= [:warned] (recoveries :evt-conf/app-writes-runtime)) "an app handler keeps the diagnostic")
    (is (= [] (recoveries :evt-conf/fw-writes-runtime)) "a framework-authority handler writes silently")))

;; An `:after` interceptor is the second route into the one boundary that
;; decides; a foreign key it injects gets the same verdict as a returned one.
(deftest reg-event-final-effects-boundary-refuses-after-interceptor-foreign-key
  (rf/reg-interceptor :evt-conf/inject-foreign
    {:after (fn [ctx] (assoc-in ctx [:effects :evt-conf/foreign] :should-be-dropped))})
  (rf/reg-event :evt-conf/handler-with-after
    {:interceptors [:evt-conf/inject-foreign]}
    (fn [{:keys [db]} _] {:db (assoc db :committed :yes)}))
  (let [traces (traces-during #(rf/dispatch-sync [:evt-conf/handler-with-after]))]
    (is (nil? (:committed (rf/app-db-value :rf/default))) "the handler's :db write did not commit")
    (is (= [:fix-effect] (shape-recoveries traces :evt-conf/foreign)))))

(deftest retired-forms-fan-out-throw-and-register-nothing
  (let [seen-errors (atom [])]
    (rf.error-emit/register-error-listener! :evt-conf/removal-recorder
      (fn [error-record] (swap! seen-errors conj (:error error-record))))
    (doseq [[retired-form error-id event-id]
            [[rf/reg-event-db  :rf.error/reg-event-db-removed  :evt-conf/db-noreg]
             [rf/reg-event-fx  :rf.error/reg-event-fx-removed  :evt-conf/fx-noreg]
             [rf/reg-event-ctx :rf.error/reg-event-ctx-removed :evt-conf/ctx-noreg]]]
      ;; The listener must already have the record when the call throws.
      (is (= [error-id error-id nil]
             [(thrown-error-id #(retired-form event-id (fn [_ _] nil)))
              (some #{error-id} @seen-errors)
              (rf.registrar/lookup :event event-id)])))))

(deftest handled-event-fans-out-on-the-always-on-events-channel
  (let [seen-events (atom [])]
    (rf.event-emit/register-event-listener! :evt-conf/handled-recorder
      (fn [event-record] (swap! seen-events conj event-record)))
    (rf/make-frame {:id :evt-conf/events-main})
    (rf/reg-event :evt-conf/events-probe
      {:frame :evt-conf/events-main}
      (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
    (rf/dispatch-sync [:evt-conf/events-probe] {:frame :evt-conf/events-main})
    (is (= [{:event-id :evt-conf/events-probe :frame :evt-conf/events-main :outcome :ok}]
           (mapv #(select-keys % [:event-id :frame :outcome]) @seen-events))
        "one record per processed event, naming the resolved frame")))

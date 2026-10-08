(ns re-frame.events-test
  "`reg-event` (EP-0018): registration-time rejection of the retired
  positional chain, malformed and inline `:interceptors`, a bare interceptor
  and `:boundary?` without `:schema`; `(rf/clear :event id)`; and a non-map
  handler return.

  Everything here holds in both postures except the
  `rf.interop/debug-enabled?` arm, which reads the dev-only trace stream."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
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
  ;; `init!` does not create `:rf/default`, and framework operations need a
  ;; carried frame.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(def ^:private noop-icpt-value
  ;; An interceptor VALUE: legal only as `reg-interceptor`'s input, so every
  ;; use below is a negative case.
  {:id     :test/noop
   :before identity
   :after  identity})

;; ---- registration-time rejection ------------------------------------------

(deftest positional-interceptor-vector-is-rejected-loudly
  (is (= {:rf.error/id :rf.error/reg-event-bad-middle-slot
          :where       'rf/reg-event
          :recovery    :fix-registration
          :got         [noop-icpt-value]}
         (select-keys (try (rf/reg-event :test.bpmszk/vector-middle
                             [noop-icpt-value]
                             (fn [{:keys [db]} _] {:db db}))
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))
                      [:rf.error/id :where :recovery :got]))
      "a positional vector in the middle slot")
  (is (= :rf.error/reg-event-bad-arity
         (try (rf/reg-event :test.bpmszk/meta-plus-vector
                {:doc "old three-tail shape"}
                [noop-icpt-value]
                (fn [{:keys [db]} _] {:db db}))
              nil
              (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))
      "metadata followed by a positional vector"))

(deftest malformed-metadata-map-interceptors-is-rejected-loudly
  ;; A non-vector value and a non-ref entry are the generic error; an inline
  ;; interceptor value gets the reference-only error naming it.
  (rf/reg-interceptor :test/ref-ok {:before identity :after identity})
  (doseq [[interceptors expected]
          [[noop-icpt-value
            {:rf.error/id :rf.error/reg-event-bad-interceptors}]
           [[:test/ref-ok "not-an-interceptor"]
            {:rf.error/id :rf.error/reg-event-bad-interceptors}]
           [[noop-icpt-value]
            {:rf.error/id :rf.error/inline-interceptor-removed
             :offending   noop-icpt-value}]]]
    (let [expected (merge {:reg-fn   "reg-event"
                           :id       :test.bpmszk/bad
                           :recovery :fix-registration}
                          expected)]
      (is (= expected
             (select-keys (try (rf/reg-event :test.bpmszk/bad
                                 {:interceptors interceptors}
                                 (fn [{:keys [db]} _] {:db db}))
                               nil
                               (catch clojure.lang.ExceptionInfo e (ex-data e)))
                          (keys expected)))
          (pr-str interceptors)))))

(deftest bare-interceptor-rejected-at-registration
  ;; Read as the metadata map, a bare interceptor would be silently dropped.
  (is (= {:rf.error/id :rf.error/reg-event-bare-interceptor
          :reg-fn      "reg-event"
          :where       'rf/reg-event
          :slot        :middle
          :recovery    :fix-registration}
         (select-keys (try (rf/reg-event :test.3ut12/data-probe
                             noop-icpt-value
                             (fn [{:keys [db]} _] {:db db}))
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))
                      [:rf.error/id :reg-fn :where :slot :recovery]))))

(deftest boundary-without-schema-rejected-at-registration
  (is (= {:rf.error/id :rf.error/at-boundary-missing-schema
          :reg-fn      "reg-event"
          :id          :test.iftj4/data-probe
          :recovery    :no-recovery}
         (select-keys (try (rf/reg-event :test.iftj4/data-probe
                             {:boundary? true}
                             (fn [_ _] {}))
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))
                      [:rf.error/id :reg-fn :id :recovery])))
  (is (nil? (rf.registrar/lookup :event :test.iftj4/data-probe))
      "the rejection precedes the registry write"))

;; ---- clearing --------------------------------------------------------------

(deftest clear-event-removes-a-single-handler
  (let [runs (atom 0)]
    (rf/reg-event :test.6z20/foo (fn [_ _] (swap! runs inc) {}))
    (rf/dispatch-sync [:test.6z20/foo])
    (rf/clear :event :test.6z20/foo)
    (rf/dispatch-sync [:test.6z20/foo])
    (is (= 1 @runs) "the handler ran before the clear and not after")))

;; ---- non-map handler return ------------------------------------------------

(deftest reg-event-non-map-return-traces-bad-return-error
  ;; `[[:dispatch …]]` is the shape of an `:fx` value; a non-map return must
  ;; not be honoured as one.
  (let [traces     (atom [])
        other-runs (atom 0)
        returned   [[:dispatch [:other]]]
        db-before  (rf/app-db-value :rf/default)]
    (rf/register-listener! :trace ::bad-vector (fn [ev] (swap! traces conj ev)))
    (rf/reg-event :other (fn [{:keys [db]} _] (swap! other-runs inc) {:db db}))
    (rf/reg-event :test.k3bj/vector-return (fn [_ _] returned))
    (rf/dispatch-sync [:test.k3bj/vector-return])
    (is (zero? @other-runs) "the returned vector was not walked as :fx")
    (is (= db-before (rf/app-db-value :rf/default)))
    (when rf.interop/debug-enabled?
      (is (= [[:no-recovery {:event-id      :test.k3bj/vector-return
                             :event         [:test.k3bj/vector-return]
                             :returned      returned
                             :returned-type (type returned)}]]
             (->> @traces
                  (filter #(and (= :error (:op-type %))
                                (= :rf.error/effect-handler-bad-return (:operation %))))
                  (mapv (juxt :recovery
                              #(select-keys (:tags %)
                                            [:event-id :event :returned :returned-type])))))))))

;; ---- retired registration names -------------------------------------------

(deftest retired-reg-event-stubs-register-nothing
  (try (rf/reg-event-db :test.slice-z/db-noreg (fn [_ _] nil)) (catch clojure.lang.ExceptionInfo _ nil))
  (try (rf/reg-event-fx :test.slice-z/fx-noreg (fn [_ _] nil)) (catch clojure.lang.ExceptionInfo _ nil))
  (try (rf/reg-event-ctx :test.slice-z/ctx-noreg (fn [_ _] nil)) (catch clojure.lang.ExceptionInfo _ nil))
  (is (= [nil nil nil]
         (mapv #(rf.registrar/lookup :event %)
               [:test.slice-z/db-noreg :test.slice-z/fx-noreg :test.slice-z/ctx-noreg]))))

(ns re-frame.machine-schema-arity-test
  "The single registration home and the public `reg-machine` event-vector
  `:schema` arity. The `:where :machine-data` walker resolves `[:schemas :data]`
  through the `:rf/machine` registration stamp, so the opts arity must stamp it
  too:

   1. A machine registered via `(reg-machine* id {:schema EventSchema} machine)`
      validates its `[:schemas :data]`.
   2. The opts `:schema` validates the dispatched OUTER event vector at the
      `:where :event` boundary, rejecting a malformed vector before the handler
      runs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact publishes its late-bind hooks
            ;; (`:machines/reg-machine` etc.) so `rf/reg-machine` resolves.
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            ;; The schemas artefact ships the registered-validator hot path the
            ;; `:where :machine-data` / `:where :event` boundaries route through;
            ;; the `.malli` adapter ns publishes Malli validate/explain into the
            ;; late-bind table.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- collect-machine-data-traces!
  "Run `f` while collecting every `:rf.error/schema-validation-failure`
  trace event whose `:where` is `:machine-data`."
  [f]
  (rf.machines.test-support/with-trace-capture traces
    (f)
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :machine-data (-> % :tags :where)))
             @traces)))

(defn- collect-event-traces!
  "Run `f` while collecting every `:rf.error/schema-validation-failure`
  trace event whose `:where` is `:event` (the outer-vector boundary)."
  [f]
  (rf.machines.test-support/with-trace-capture traces
    (f)
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :event (-> % :tags :where)))
             @traces)))

;; ---- fixtures -------------------------------------------------------------

(def ^:private flow-id :auth.login/flow)

(def ^:private Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

(def ^:private AuthLoginEvent
  "Outer event-vector schema: `:submit` carries Credentials; framework-internal
  sub-events admit :any; the trailing `[:? :any]` admits a managed-HTTP reply.
  Deliberately permissive, because the data-schema test below dispatches ad-hoc
  inner sub-events."
  [:cat [:= :auth.login/flow]
   [:or
    [:cat [:= :auth.login/submit] Credentials]
    [:vector :any]]
   [:? :any]])

(def ^:private AuthLoginData
  "Machine `:data` schema: a sensitive token + a plain attempt counter."
  [:map
   [:attempts {:default 0} :int]
   [:token    {:sensitive? true} [:maybe :string]]
   [:error    [:maybe :string]]])

;; ---- (1) auto-stamp / live [:schemas :data] via the event-:schema arity ----

(deftest event-schema-arity-makes-data-schema-live
  (testing "a machine registered via (reg-machine* id {:schema ...} machine)
            has a live [:schemas :data] schema"
    (rf.machines/reg-machine* flow-id {:schema AuthLoginEvent}
      {:initial :idle
       :data    {:attempts 0 :token nil :error nil}
       :schemas {:data AuthLoginData}
       :actions {:break (fn [_] {:data {:attempts "nope" :token nil :error nil}})}
       :states  {:idle {:on {:auth.login/break {:target :idle :action :break}}}}})
    (rf/dispatch-sync [flow-id [:noop]])
    (is (= [flow-id]
           (mapv (comp :machine-id :tags)
                 (collect-machine-data-traces! #(rf/dispatch-sync [flow-id [:auth.login/break]])))))))

;; ---- (2) the event-vector :schema validates the outer vector ---------------

(deftest event-schema-arity-validates-outer-vector
  (testing "the :schema opts key validates the dispatched OUTER event vector at
            the :where :event boundary — a malformed :submit payload is rejected
            BEFORE the handler runs; a well-formed one passes"
    (rf.machines/reg-machine* flow-id
      {:schema [:tuple [:= flow-id] [:tuple [:= :auth.login/submit] Credentials]]}
      {:initial :idle
       :data    {:attempts 0 :token nil :error nil}
       :schemas {:data AuthLoginData}
       :actions {:clear (fn [_] {:data {:error nil}})}
       :states  {:idle       {:on {:auth.login/submit {:target :submitting
                                                       :action :clear}}}
                 :submitting {}}})
    (let [traces (collect-event-traces!
                   #(rf/dispatch-sync
                      [flow-id [:auth.login/submit {:email "a@b.com" :password "short"}]]))]
      (is (= [true false] [(<= 1 (count traces)) (= :submitting (rf.machines.test-support/machine-state flow-id))])
          "a :where :event trace fired and the malformed event did NOT transition"))
    (rf/dispatch-sync
      [flow-id [:auth.login/submit {:email "a@b.com" :password "longenough"}]])
    (is (= :submitting (rf.machines.test-support/machine-state flow-id)))))

;; ---- single-home invariants ------------------------------------------------

(deftest opts-must-not-carry-reserved-machine-meta
  (is (= :rf.error/machine-reserved-meta-in-opts
         (try (rf.machines/reg-machine* :rf.machine-arity/reserved
                {:rf/machine? true}
                {:initial :idle :states {:idle {}}})
              nil
              (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))
      "the framework-owned :rf/machine? in opts is rejected — the home stamps it"))

(deftest reg-machine-rejects-non-map-opts
  (testing "a non-map opts slot is rejected with the canonical error id, carrying the value"
    (is (= {:rf.error/id :rf.error/invalid-machine-opts :value []}
           (select-keys (try (rf.machines/reg-machine* :rf.machine-arity/bad-vec
                               []
                               {:initial :idle :states {:idle {}}})
                             nil
                             (catch clojure.lang.ExceptionInfo e (ex-data e)))
                        [:rf.error/id :value]))))
  (testing "an explicit nil opts is the no-opts path (legal)"
    (is (= :rf.machine-arity/nil-opts
           (rf.machines/reg-machine* :rf.machine-arity/nil-opts
             nil
             {:initial :idle :states {:idle {}}})))))

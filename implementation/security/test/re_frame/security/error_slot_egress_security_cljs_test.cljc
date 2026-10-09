(ns re-frame.security.error-slot-egress-security-cljs-test
  "Adversarial tests for error slots containing caller-supplied data.

  Machine action `:exception-data` is redacted when the addressed machine is
  sensitive, or when its frame cannot be resolved. Route navigation `:error`
  is redacted when the route's params or query schema contains a sensitive
  slot. Both projections stamp the resulting trace sensitive so default MCP
  egress drops the whole event as defence in depth.

  These protections target AI/MCP and log egress. Plain machines and routes
  remain unredacted when a known frame supplies no sensitive declaration."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            ;; SIDE-EFFECT REQUIRE, no alias: the schemas facade publishes the
            ;; `:schemas/validate-with-registered-fn` and
            ;; `:schemas/redact-validation-tags` late-bind hooks, and loads the
            ;; Malli adapter they delegate to. Without it `route-url` soft-passes
            ;; (no validation throw) and the route sensitivity oracle is unbound.
            [re-frame.schemas]
            ;; SIDE-EFFECT REQUIRE, no alias: `rf/reg-route` is a late-bound
            ;; facade over the OPTIONAL `day8/re-frame2-routing` artefact,
            ;; which `re-frame.core` deliberately does not require. Loading
            ;; this ns is what publishes the routing implementation the facade
            ;; binds to; no `routing/` var is named here.
            [re-frame.routing]
            ;; A validation reject short-circuits before browser effects, so
            ;; the handler can be driven directly with synthetic coeffects.
            [re-frame.routing.navigate :as rf.routing.navigate]
            #?(:clj  [re-frame.test-support :as rf.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support :refer-macros [with-trace-recorder!]])
            ;; Drive the production trace emitter so tests observe the top-level
            ;; sensitivity stamp consumed by MCP egress.
            [re-frame.trace :as rf.trace]
            [re-frame.security.gen :as rf.security.gen]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture))

(def ^:private sentinel "S3CR3T-rf2-zsm03-ERROR-SLOT-DO-NOT-LEAK")

(defn- contains-sentinel?
  "True when the sentinel survives anywhere in `x`, including stringified data."
  [x]
  (rf.security.gen/contains-string? x sentinel))

(def ^:private exception-data {:user-token sentinel :doc-id [sentinel]})

(defn- emit-machine-action-exception
  "Emit the `:tags` shape `trace-action-failure!` builds through the production
  trace path, addressing machines by the `ids` map (a nil there removes the
  slot), and return the delivered envelope after projection and sensitivity
  hoisting."
  [ids]
  (with-trace-recorder! [traces]
    (rf.trace/emit-error! :rf.error/machine-action-exception
                          (into {} (remove (comp nil? val))
                                (merge {:frame             :rf/default
                                        :action-id         :do/thing
                                        :state-path        [:running]
                                        :exception-message "boom"
                                        :exception-data    exception-data
                                        :reason            "Machine action threw."
                                        :recovery          :no-recovery}
                                       ids)))
    (first (filter #(= :rf.error/machine-action-exception (:operation %))
                   @traces))))

(deftest machine-exception-data-redaction-follows-the-addressed-machine
  ;; Live actors are addressed by :actor-id, which wins over the :machine-id
  ;; fallback; an unresolvable frame fails closed even for a plain machine.
  (rf/reg-event :sec/sensitive-machine {:sensitive [[:data :token]]} (fn [_ _] nil))
  (rf/reg-event :sec/plain-machine (fn [_ _] nil))
  (doseq [[label ids] [["sensitive by :machine-id" {:machine-id :sec/sensitive-machine}]
                       ["sensitive :actor-id beats a plain :machine-id"
                        {:actor-id :sec/sensitive-machine :machine-id :sec/plain-machine}]
                       ["plain machine, no frame" {:actor-id :sec/plain-machine :frame nil}]]]
    (let [out (emit-machine-action-exception ids)]
      (is (= [:rf/redacted true false]
             [(-> out :tags :exception-data) (:sensitive? out) (contains-sentinel? out)])
          label)))
  (let [out (emit-machine-action-exception {:machine-id :sec/plain-machine})]
    (is (= [exception-data nil] [(-> out :tags :exception-data) (:sensitive? out)])
        "a plain machine in a known frame keeps :exception-data verbatim")))

(defn- capture-navigate-failure
  "Invoke navigation with a sentinel-bearing invalid param and return the
  emitted schema-validation trace. The reject precedes all browser effects."
  [params-schema]
  (rf/reg-route :sec/route {:params params-schema} "/doc/:doc")
  (with-trace-recorder! [traces]
    (rf.routing.navigate/navigate-handler {:db {} :rf.frame/id :rf/default}
                                          [:rf.route/navigate {:to :sec/route :params {:doc sentinel}}])
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest navigate-error-redacted-for-sensitive-route
  (let [trace (capture-navigate-failure [:map [:doc {:sensitive? true} :int]])]
    (is (= [:rf/redacted true false]
           [(-> trace :tags :error) (:sensitive? trace) (contains-sentinel? trace)]))))

(deftest navigate-error-verbatim-for-plain-route
  (let [trace (capture-navigate-failure [:map [:doc :int]])]
    (is (= [true false] [(map? (-> trace :tags :error)) (contains? trace :sensitive?)])
        ":error rides as the raw ex-data map with no :sensitive? stamp")))

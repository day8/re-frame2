(ns re-frame.security.error-slot-egress-security-cljs-test
  "Adversarial tests for error slots containing caller-supplied data.

  Machine action `:exception-data` is redacted when the addressed machine is
  sensitive, or when its frame cannot be resolved. Route navigation `:error`
  is redacted when the route's params or query schema contains a sensitive
  slot. Both projections stamp the resulting trace sensitive so default MCP
  egress drops the whole event as defence in depth.

  These protections target AI/MCP and log egress. Plain machines and routes
  remain unredacted when a known frame supplies no sensitive declaration."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            ;; Publishes the Malli late-bind validate/explain/sensitive hooks;
            ;; without it `route-url` soft-passes (no validation throw) and the
            ;; `:schemas/redact-validation-tags` sensitivity oracle is unbound.
            [re-frame.schemas.malli]
            [re-frame.mcp-base.sensitive :as rf.mcp-base.sensitive]
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

(def ^:private sensitive-machine-id :sec/sensitive-machine)
(def ^:private plain-machine-id     :sec/plain-machine)

(defn- declare-machine-marks!
  "Register the event metadata from which machine classification is derived."
  []
  (rf/reg-event sensitive-machine-id
                {:sensitive [[:data :token]]}
                (fn [_ _] nil))
  (rf/reg-event plain-machine-id (fn [_ _] nil)))

(defn- machine-action-exception-tags
  "The EXACT `:tags` shape `trace-action-failure!` hands to
  `trace/emit-error!` (registration.cljc), carrying the sentinel-bearing
  `:exception-data`, with the addressed id keyed under `id-key`.

  Live actors use `:actor-id`; `:machine-id` is the fallback. The explicit
  frame lets plain-machine tests exercise the resolved, non-redacting path."
  [id-key machine-id]
  {id-key             machine-id
   :frame             :rf/default
   :action-id         :do/thing
   :state-path        [:running]
   :event             [machine-id [:tick]]
   :exception-message "boom"
   :exception-data    {:user-token sentinel :doc-id [sentinel]}
   :reason            "Machine action threw."
   :recovery          :no-recovery})

(defn- emit-machine-action-exception
  "Emit a machine exception through the production trace path and return the
  delivered envelope after projection and sensitivity hoisting."
  [tags]
  (with-trace-recorder! [traces]
    (rf.trace/emit-error! :rf.error/machine-action-exception tags)
    (first (filter #(= :rf.error/machine-action-exception (:operation %))
                   @traces))))

(defn- emit-machine-action-exception*
  "Emit through the real path with the addressed id keyed under `id-key`."
  [id-key machine-id]
  (emit-machine-action-exception (machine-action-exception-tags id-key machine-id)))

(defn- emit-machine-action-exception-for
  "Emit through the `:machine-id` fallback lookup."
  [machine-id]
  (emit-machine-action-exception* :machine-id machine-id))

(deftest machine-exception-data-redacted-for-sensitive-machine
  (testing "a sensitive machine's :exception-data is redacted on the
            production emit path, the top-level :sensitive? flag is hoisted, the
            sentinel never survives, and MCP egress drops the event when
            sensitive reads are disabled"
    (declare-machine-marks!)
    (let [out  (emit-machine-action-exception-for sensitive-machine-id)
          tags (:tags out)
          [kept dropped] (rf.mcp-base.sensitive/strip-sensitive [out] false)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (= :rf/redacted (:exception-data tags)) ":exception-data redacted")
      (is (true? (:sensitive? out)) "top-level :sensitive? hoisted")
      (is (not (contains? tags :sensitive?))
          ":sensitive? stripped from :tags after the hoist (no double-stamp)")
      (is (not (contains-sentinel? out))
          (str "the sentinel leaked into the machine-action-exception trace: "
               (pr-str tags)))
      ;; Structural slots remain available to locate the failure.
      (is (= sensitive-machine-id (:machine-id tags)) ":machine-id kept")
      (is (= :do/thing (:action-id tags)) ":action-id kept")
      (is (= "boom" (:exception-message tags)) ":exception-message kept")
      (is (= 1 dropped) "the sensitive machine exception event dropped")
      (is (empty? kept) "nothing egressed with --allow-sensitive-reads disabled"))))

(deftest machine-exception-data-verbatim-for-plain-machine
  (testing "a machine with no :sensitive mark keeps
            :exception-data verbatim (the seam is precise, not a blanket
            scrub) and is NOT classified sensitive at egress, whether the
            trace addresses it by :machine-id or by the preferred :actor-id"
    (declare-machine-marks!)
    (doseq [id-key [:machine-id :actor-id]]
      (let [out  (emit-machine-action-exception* id-key plain-machine-id)
            tags (:tags out)]
        (is (some? out) (str id-key ": the machine-action-exception trace was delivered"))
        (is (map? (:exception-data tags)) (str id-key ": :exception-data NOT redacted"))
        (is (not (:sensitive? out))
            (str id-key ": no top-level :sensitive? when the machine declares nothing sensitive"))
        (is (false? (rf.mcp-base.sensitive/sensitive-event? out))
            (str id-key ": MCP egress treats it as non-sensitive"))))))

(deftest machine-exception-data-redacted-for-nil-frame
  (testing "a machine-action-exception whose trace carries no
            :frame (nil / unresolvable frame) FAILS CLOSED: :exception-data is
            elided to :rf/redacted and the TOP-LEVEL :sensitive? flag is
            hoisted. Even a plain
            machine (no author :sensitive mark) redacts here, because the
            author-shaped :exception-data cannot be path-walked safely without
            a resolved frame."
    (declare-machine-marks!)
    (let [tags-no-frame (dissoc (machine-action-exception-tags
                                  :actor-id plain-machine-id)
                                :frame)
          out           (emit-machine-action-exception tags-no-frame)
          tags          (:tags out)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (nil? (:frame tags)) "the trace carries no resolvable :frame")
      (is (= :rf/redacted (:exception-data tags))
          ":exception-data redacted (fail-closed on a nil frame)")
      (is (true? (:sensitive? out)) "top-level :sensitive? hoisted")
      (is (not (contains-sentinel? out))
          (str "the sentinel leaked into the nil-frame exception trace: "
               (pr-str tags))))))

;; Production emits address live instances by :actor-id; :machine-id remains a
;; fallback. Both paths must apply the same classification.
(deftest actor-id-takes-precedence-over-machine-id
  (testing "when both keys are present the classification lookup prefers
            :actor-id: an :actor-id pointing at the SENSITIVE machine redacts
            even though :machine-id points at the PLAIN one (proves the
            `(or (:actor-id …) (:machine-id …))` precedence, not the fallback)"
    (declare-machine-marks!)
    (let [tags (-> (machine-action-exception-tags :actor-id sensitive-machine-id)
                   (assoc :machine-id plain-machine-id))
          out  (emit-machine-action-exception tags)
          out-tags (:tags out)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (= :rf/redacted (:exception-data out-tags))
          ":exception-data redacted via the PREFERRED :actor-id (sensitive)")
      (is (true? (:sensitive? out)) "top-level :sensitive? hoisted from the :actor-id machine")
      (is (not (contains-sentinel? out))
          "the sentinel never survives when :actor-id wins the lookup"))))

(def ^:private sensitive-params-schema
  ;; The rejected value appears in route-url's exception data.
  [:map [:doc {:sensitive? true} :int]])

(def ^:private plain-params-schema
  [:map [:doc :int]])

(defn- capture-navigate-failure
  "Invoke navigation with a sentinel-bearing invalid param and return the
  emitted schema-validation trace. The reject precedes all browser effects."
  [params-schema]
  (rf/reg-route :sec/route {:params params-schema} "/doc/:doc")
  (with-trace-recorder! [traces]
    ;; :doc must be rejected by the integer schema.
    (rf.routing.navigate/navigate-handler {:db {} :rf.frame/id :rf/default}
                               [:rf.route/navigate {:to :sec/route :params {:doc sentinel}}])
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest navigate-error-redacted-for-sensitive-route
  (testing "a navigate whose route :params schema is :sensitive?
            elides the :error slot (carrying the failing param value) to
            :rf/redacted, stamps :sensitive?, and never egresses the sentinel"
    (let [trace (capture-navigate-failure sensitive-params-schema)
          tags  (:tags trace)]
      (is (some? trace) "a schema-validation-failure trace fired for the reject")
      (when trace
        (is (= :rf/redacted (:error tags)) ":error slot redacted")
        ;; The trace builder hoists the tags-level stamp for MCP egress.
        (is (true? (:sensitive? trace)) "top-level :sensitive? stamped")
        (is (not (contains-sentinel? trace))
            (str "the sentinel leaked into the navigate :error trace: "
                 (pr-str trace)))
        ;; Structural slots survive.
        (is (= :sec/route (:route-id tags)) ":route-id kept")
        (is (= :event (:where tags)) ":where kept")))))

(deftest navigate-error-verbatim-for-plain-route
  (testing "a navigate whose route :params schema has no
            :sensitive? slot rides :error verbatim (no over-redaction)"
    (let [trace (capture-navigate-failure plain-params-schema)
          tags  (:tags trace)]
      (is (some? trace) "a schema-validation-failure trace fired")
      (when trace
        (is (not= :rf/redacted (:error tags)) ":error NOT redacted")
        (is (map? (:error tags)) ":error rode through as the raw ex-data map")
        ;; Read the envelope: `:tags` never carries `:sensitive?`.
        (is (not (contains? trace :sensitive?))
            "no :sensitive? stamp on a non-sensitive route")))))

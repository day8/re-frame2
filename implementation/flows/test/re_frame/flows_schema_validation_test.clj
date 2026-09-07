(ns re-frame.flows-schema-validation-test
  "JVM coverage for Spec 013 §Flow output validation.

  Pins the `:schema` flow-map key as load-bearing: a flow's computed
  `:derive` value is validated against its optional `:schema` on every
  recompute, dev-only, via the pluggable validator seam the rest of
  Spec 010 uses (`:schemas/validate-with-registered-fn` /
  `:schemas/explain-with-registered-fn`). Before this slice the key was
  stored in the registrar and runtime registry but never read by any
  code path.

  The tests register a predicate-based validator via
  `set-schema-fns!` rather than depending on Malli being on the
  classpath — the seam is pluggable per Spec 010 §Non-Malli validators,
  so a tiny `(fn [schema value] (schema value))` validator exercises the
  exact production code path while keeping the test self-contained.

  Contract pinned:
    - conforming output: no error trace; value written as normal.
    - non-conforming output: `:rf.error/schema-validation-failure
      :where :flow-output` emitted with `:rf.flow/id` / `:path` /
      `:value` / `:explain` / `:recovery :no-recovery`; the value is
      STILL written (observational, not a rollback).
    - no `:schema`: validator never consulted.
    - no validator registered: soft-pass (no error trace).
    - production gate: with `debug-enabled?` false the validation is
      silent (the whole surface DCEs in prod CLJS)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            ;; Loading `re-frame.flows` wires the flow late-bind hooks the
            ;; `rf/reg-flow` bodies below drive (the flow-output validation
            ;; path this suite pins).
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]))

;; ---- per-test reset / schema-violation recorder --------------------------
;;
;; The standard runtime reset (registrar baseline + frames + flows/schemas +
;; plain-atom adapter + ambient `:rf/default` scope) is owned by
;; `make-reset-runtime-fixture`. Layered AROUND it, a concern-specific
;; fixture resets the pluggable schema-validator seam (which the standard
;; reset does not touch) and records `:rf.error/schema-validation-failure`
;; traces for the body to assert against.

(def ^:dynamic ^:private *captured* nil)

(defn- with-schema-violation-recorder
  "Reset the pluggable schema validator, bind `*captured*`, and record every
  schema-validation-failure trace for the duration of one test; unregister
  the recorder and reset the validator again afterwards so no validator
  leaks into a sibling suite."
  [test-fn]
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (let [captured (atom [])]
    (binding [*captured* captured]
      (rf.trace/register-listener!
        ::schema-violation-recorder
        (fn [ev]
          (when (= :rf.error/schema-validation-failure (:operation ev))
            (swap! captured conj ev))))
      (try
        (test-fn)
        (finally
          (rf.trace/unregister-listener! ::schema-violation-recorder)
          (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns))))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  with-schema-violation-recorder)

;; A trivial pluggable validator: a schema here is a 1-arg predicate fn.
;; This exercises the `:schemas/validate-with-registered-fn` seam without
;; pulling Malli onto the test classpath (Spec 010 §Non-Malli validators).
(defn- install-predicate-validator! []
  (rf.schemas/set-schema-fns!
    {:validate (fn [schema value] (boolean (schema value)))
     :explain  (fn [schema value]
                 (when-not (schema value)
                   {:failed value}))}))

(defn- violations [] @*captured*)

;; ---------------------------------------------------------------------------
;; 1. Conforming output: no error trace; value written.
;; ---------------------------------------------------------------------------

(deftest conforming-output-passes-silently
  (testing "a flow whose output conforms to :schema emits no violation and writes the value"
    (install-predicate-validator!)
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area] :schema (fn [v] (and (integer? v) (pos? v)))} (fn [w h] (* w h)))
    (rf/dispatch-sync [:seed])
    (is (= 12 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "the flow computed and wrote its output")
    (is (empty? (violations))
        "a conforming output emits no :rf.error/schema-validation-failure")))

;; ---------------------------------------------------------------------------
;; 2. Non-conforming output: violation emitted, value STILL written.
;; ---------------------------------------------------------------------------

(deftest non-conforming-output-emits-violation-but-still-writes
  (testing "a flow whose output violates :schema emits :where :flow-output and STILL writes (observational)"
    (install-predicate-validator!)
    ;; Output is negative; the schema demands a non-negative integer.
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:w 3 :h -4}}))
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area] :schema (fn [v] (and (integer? v) (not (neg? v))))} (fn [w h] (* w h)))
    (rf/dispatch-sync [:seed])
    (is (= -12 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "the output is STILL written — flow validation is observational, not a rollback")
    (let [vs (violations)]
      (is (= 1 (count vs))
          "exactly one violation fired for the non-conforming output")
      (let [ev   (first vs)
            tags (:tags ev)]
        (is (= :rf.error/schema-validation-failure (:operation ev)))
        (is (= :error      (:op-type ev))          "op-type :error")
        (is (= :flow-output (:where tags))         ":where :flow-output")
        (is (= :area        (:rf.flow/id tags))    ":rf.flow/id names the failing flow")
        (is (= :area        (:failing-id tags))    ":failing-id mirrors the flow id")
        (is (= [:rect :area] (:path tags))         ":path is the flow's output path")
        ;; EP-0015 fail-closed — the flow's `:schema` here is a
        ;; PREDICATE FN (`(fn [v] ...)`), which is OPAQUE to the pure-data
        ;; walker (`walker/schema-opaque?`: non-vector, non-keyword). The
        ;; shared redaction seam (`:schemas/redact-validation-tags`) the
        ;; flow-output emit-site routes through fails CLOSED on an opaque
        ;; schema: it cannot prove the value is non-sensitive (an opaque
        ;; compiled/fn schema may carry a `{:sensitive? true}` slot the walker
        ;; can't see), so it scrubs every value-bearing slot to `:rf/redacted`
        ;; and stamps `:sensitive? true`. The failing value is therefore NOT
        ;; carried verbatim on the trace — but it IS still written to app-db
        ;; (asserted above: validation is observational, not a rollback). The
        ;; supported route to surface the raw value is registering the VECTOR
        ;; Malli form (walkable, provably flag-free), per the
        ;; `:rf.warning/schema-walker-opaque` nudge.
        (is (= :rf/redacted (:value tags))         ":value redacted fail-closed (opaque fn schema)")
        ;; `build-event` hoists `:recovery` AND `:sensitive?` from `:tags` to
        ;; the event's top level (per the trace error-shape hoist contract —
        ;; `trace/build-event` dissocs both from `:tags` and re-stamps them at
        ;; the top so Spec 009 §Privacy's top-level-only `:sensitive?` read
        ;; sees the fail-closed redaction signal).
        (is (= true         (:sensitive? ev))      ":sensitive? stamped (hoisted to top level) by fail-closed redaction")
        (is (= :no-recovery (:recovery ev))        ":recovery :no-recovery (hoisted to top level)")
        (is (= :rf/default  (:frame tags))         ":frame stamped")
        ;; `:explain` is a value-bearing slot — scrubbed to the sentinel by the
        ;; same fail-closed redaction (it carried `{:failed -12}` pre-redaction).
        (is (= :rf/redacted (:explain tags))       ":explain redacted symmetrically with :value")
        (is (string? (:reason tags))               ":reason is a human-readable string")))))

;; ---------------------------------------------------------------------------
;; 3. No :schema: validator never consulted.
;; ---------------------------------------------------------------------------

(deftest absent-schema-skips-validation
  (testing "a flow without :schema never consults the validator (no violation even on a 'bad' value)"
    (let [validator-calls (atom 0)]
      (rf.schemas/set-schema-fns!
        {:validate (fn [_ _] (swap! validator-calls inc) false)})
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
      (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* w h)))
      (rf/dispatch-sync [:seed])
      (is (zero? @validator-calls)
          "no :schema means the validator is never invoked")
      (is (empty? (violations))
          "no :schema means no violation regardless of output value"))))

;; ---------------------------------------------------------------------------
;; 4. No validator registered: soft-pass.
;; ---------------------------------------------------------------------------

(deftest no-validator-soft-passes
  (testing "with the validator set to nil, a :schema flow soft-passes (no violation)"
    ;; nil validator => the `:schemas/validate-with-registered-fn` seam
    ;; treats 'no validator' as 'no validation' (Spec 010 soft-pass).
    (rf.schemas/set-schema-fns! {:validate nil})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area] :schema (fn [_] false)} (fn [w h] (* w h))) ;; would reject everything IF consulted
    (rf/dispatch-sync [:seed])
    (is (= 12 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "value written")
    (is (empty? (violations))
        "no registered validator => soft-pass, no violation")))

;; ---------------------------------------------------------------------------
;; 5. Production gate: silent under debug-enabled? false.
;; ---------------------------------------------------------------------------

(deftest production-gate-elides-validation
  (testing "with debug-enabled? false the whole validation surface is silent (prod elision mirror)"
    (install-predicate-validator!)
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area] :schema (fn [_] false)} (fn [w h] (* w h))) ;; would reject IF the gate let it run
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/dispatch-sync [:seed]))
    (is (= 12 (get-in (rf/app-db-value :rf/default) [:rect :area]))
        "value written even with validation elided")
    (is (empty? (violations))
        "no violation under debug-enabled? false — the surface DCEs in prod")))

;; ---------------------------------------------------------------------------
;; 6. Cascade: an upstream flow's bad output is validated independently of
;;    a downstream flow that consumes it (per-flow :schema).
;; ---------------------------------------------------------------------------

(deftest each-flow-validates-its-own-output
  (testing "in a flow-reads-flow cascade each flow validates its own output against its own :schema"
    (install-predicate-validator!)
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:cart {:items [{:price 10} {:price -5}]}}}))
    ;; subtotal sums the (here intentionally negative-capable) prices.
    (rf/reg-flow :cart/subtotal {:inputs [[:cart :items]] :output-path [:cart :subtotal] :schema (fn [v] (and (integer? v) (not (neg? v))))} (fn [items] (reduce + 0 (map :price items))))
    ;; total doubles the subtotal; demands a non-negative result too.
    (rf/reg-flow :cart/total {:inputs [[:cart :subtotal]] :output-path [:cart :total] :schema (fn [v] (and (integer? v) (not (neg? v))))} (fn [subtotal] (* 2 subtotal)))
    (rf/dispatch-sync [:seed])
    ;; subtotal = 10 + -5 = 5 (conforms); total = 10 (conforms). No violation.
    (is (= 5  (get-in (rf/app-db-value :rf/default) [:cart :subtotal])))
    (is (= 10 (get-in (rf/app-db-value :rf/default) [:cart :total])))
    (is (empty? (violations))
        "both outputs conform — no violation")
    ;; Now drive the subtotal negative; subtotal violates, total (= 2*subtotal)
    ;; also violates. Each surfaces independently with its own :rf.flow/id.
    (reset! *captured* [])
    (rf/dispatch-sync [:seed] )         ;; re-seed is a no-op write (same value)
    (rf/reg-event :seed-neg (fn [{:keys [db]} _] {:db {:cart {:items [{:price -100}]}}}))
    (rf/dispatch-sync [:seed-neg])
    (let [ids (set (map #(get-in % [:tags :rf.flow/id]) (violations)))]
      (is (= #{:cart/subtotal :cart/total} ids)
          "both flows' bad outputs surface, each attributed to its own id"))))

;; ---------------------------------------------------------------------------
;; 7. rf2-6eh5h — declaration presence is KEY-presence, not value truthiness.
;;    A flow registered with an explicit {:schema nil} DELEGATES the exact
;;    nil token to the registered validator (the value is opaque per Spec
;;    010); only an ABSENT key skips validation (case 3 above is the
;;    control).
;; ---------------------------------------------------------------------------

(deftest present-nil-schema-is-delegated-not-skipped
  (testing "an explicit {:schema nil} flow declaration reaches the validator
            verbatim; the false verdict emits :where :flow-output"
    (let [seen (atom [])]
      (rf.schemas/set-schema-fns!
        {:validate (fn [schema _value] (swap! seen conj schema) false)})
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
      (rf/reg-flow :area
                   {:inputs [[:w] [:h]] :output-path [:rect :area] :schema nil}
                   (fn [w h] (* w h)))
      (rf/dispatch-sync [:seed])
      (is (= [nil] @seen)
          "the EXACT nil token reached the validator, exactly once")
      (is (= 1 (count (violations)))
          "the false verdict emitted exactly one flow-output violation")
      (is (= :flow-output (get-in (first (violations)) [:tags :where]))
          ":where :flow-output — the flow surface's own discriminator")
      (is (= 12 (get-in (rf/app-db-value :rf/default) [:rect :area]))
          "the value is still written — flow validation stays observational"))))

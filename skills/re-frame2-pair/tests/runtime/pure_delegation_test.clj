;;;; tests/runtime/pure_delegation_test.clj
;;;;
;;;; Structural (AST) pin that the SHIPPED preload `re-frame2-pair.runtime`
;;;; DELEGATES to the tested pure core `re-frame2-pair.pure` (rf2-etsj8p).
;;;;
;;;; The genuinely-pure decision logic — the cascade / consequence projections,
;;;; the multi-frame operating-frame resolver, the id-validation core, the
;;;; streaming queue transforms (routing / eviction / drain), the epoch timing +
;;;; matcher, the snapshot-scope resolver, and the orient assembler — lives in
;;;; `preload/re_frame2_pair/pure.cljc` and is exercised DIRECTLY by the CLJS
;;;; node-test build (`tests/fixture/`, `npm run test:pure`). That is the "test
;;;; the shipped code" half. This file is the WIRING half: it parses the
;;;; runtime source and asserts every stateful/framework-touching wrapper
;;;; actually threads its live gate into the corresponding `pure/*` fn — so a
;;;; refactor that forked the logic back into the runtime (re-introducing the
;;;; drift the retired Babashka mirrors suffered) trips RED here.
;;;;
;;;; Run: bb tests/runtime/pure_delegation_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns pure-delegation-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [runtime-support :as rt]))

(def ^:private form-contains? rt/form-contains?)

(defn- named-form
  "The top-level `(def…|defn…|defn- sym …)` form for `sym`, or nil. Matches
   `def`, `defn`, and `defn-` so both value aliases and fn wrappers resolve."
  [sym]
  (some (fn [form]
          (when (and (seq? form)
                     (#{'def 'defn 'defn-} (first form))
                     (= sym (second form)))
            form))
        rt/all-forms))

(defn- ns-form []
  (some (fn [form] (when (and (seq? form) (= 'ns (first form))) form)) rt/all-forms))

;; ---------------------------------------------------------------------------
;; The require
;; ---------------------------------------------------------------------------

(deftest runtime-requires-the-pure-core
  (is (form-contains? #(= 're-frame2-pair.pure %) (ns-form))
      "runtime.cljs must `:require` re-frame2-pair.pure — the SHIPPED pure core it delegates to (rf2-etsj8p)."))

;; ---------------------------------------------------------------------------
;; Delegation pairs — each runtime name must reference its `pure/*` counterpart
;; ---------------------------------------------------------------------------

(def ^:private delegations
  "runtime-name -> the `pure/*` symbol its body/value must reference."
  '{reserved-tool-frame?         pure/reserved-tool-frame?
    app-frame-ids                pure/app-frame-ids
    current-frame                pure/resolve-operating-frame
    ambiguous-frame-error        pure/ambiguous-frame-envelope
    nearest-ids                  pure/nearest-ids
    validate-registered          pure/validate-against-known
    cascade-error-ops            pure/cascade-error-ops
    cascade-errors               pure/cascade-errors
    db-diff-summary              pure/db-diff-summary
    outcome-tier                 pure/outcome-tier
    machine-transitions-summary  pure/machine-transitions-summary
    redact-sensitive-event-vector pure/redact-sensitive-event-vector
    cascade-summary              pure/cascade-summary
    consequence-from-summary     pure/consequence-from-summary
    restore-cascade-summary      pure/restore-cascade-projection
    dispatch-trace-to-subs!      pure/dispatch-trace-to-subs
    dispatch-epoch-to-subs!      pure/dispatch-epoch-to-subs
    epoch-elapsed-ms             pure/epoch-elapsed-ms
    epoch-matches?               pure/epoch-matches?
    attribute-pair-epoch!        pure/attribute-pair-epoch
    last-pair-epoch              pure/pick-pair-epoch
    subscribe!                   pure/make-subscription
    drain-subscription!          pure/drain
    all-snapshot-slices          pure/all-snapshot-slices
    snapshot-state               pure/resolve-snapshot-frames
    orient-registrar-kinds       pure/orient-registrar-kinds
    orient                       pure/assemble-orient})

(deftest every-runtime-wrapper-delegates-to-its-pure-counterpart
  (doseq [[rt-name pure-sym] delegations]
    (testing (str rt-name " -> " pure-sym)
      (let [form (named-form rt-name)]
        (is (some? form)
            (str "runtime.cljs must define `" rt-name "`."))
        (is (form-contains? #(= pure-sym %) form)
            (str "`" rt-name "` MUST delegate to `" pure-sym
                 "` — the SHIPPED pure helper the node-test exercises (rf2-etsj8p).")))))
  ;; The buffer-budget defaults are pure-core aliases too.
  (doseq [rt-name '[default-max-buffered-events default-max-buffered-bytes]]
    (let [form (named-form rt-name)]
      (is (and (some? form) (form-contains? #(and (symbol? %)
                                                  (= "pure" (namespace %))) form))
          (str "`" rt-name "` MUST alias the pure-core constant (rf2-etsj8p).")))))

;; ---------------------------------------------------------------------------
;; Gate injection — the parameterised fns must thread the LIVE session gate in
;; ---------------------------------------------------------------------------

(deftest raw-state-gate-is-threaded-into-the-redaction-fns
  (doseq [rt-name '[redact-sensitive-event-vector cascade-summary restore-cascade-summary]]
    (is (form-contains? #(= :allow-raw-state? %) (named-form rt-name))
        (str "`" rt-name "` MUST thread the live `:allow-raw-state?` gate into the pure fn (rf2-etsj8p)."))))

(deftest privacy-gate-is-threaded-into-the-streaming-drop
  (is (form-contains? #(= :include-sensitive? %) (named-form 'on-trace-streaming))
      "on-trace-streaming MUST thread the live `:include-sensitive?` privacy posture into pure/streaming-drop? (rf2-etsj8p)."))

(let [{:keys [fail error]} (run-tests 'pure-delegation-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))

;;;; tests/runtime/pure_delegation_test.clj — the SHIPPED preload
;;;; `re-frame2-pair.runtime` delegates its decision logic to
;;;; `re-frame2-pair.pure`, which the fixture's node-test exercises directly
;;;; (`tests/fixture/`, `npm run test:pure`). This pins the wiring half, so a
;;;; refactor that forks the logic back into the runtime — out of reach of
;;;; those tests — turns red here.
;;;;
;;;; Run: bb tests/runtime/pure_delegation_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns pure-delegation-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [runtime-support :as rt]))

(defn- named-form
  "The top-level `def` / `defn` / `defn-` form for `sym` — value aliases and
   fn wrappers both resolve."
  [sym]
  (some #(when (and (seq? %) (#{'def 'defn 'defn-} (first %)) (= sym (second %))) %)
        rt/all-forms))

(def ^:private delegations
  "runtime-name -> the `pure/*` symbol its body or value must reference."
  '{reserved-tool-frame?         pure/reserved-tool-frame?
    app-frame-ids                pure/app-frame-ids
    current-frame                pure/resolve-operating-frame
    ambiguous-frame-error        pure/ambiguous-frame-envelope
    nearest-ids                  pure/nearest-ids
    validate-registered          pure/validate-against-known
    cascade-summary              pure/cascade-summary
    consequence-from-summary     pure/consequence-from-summary
    restore-cascade-summary      pure/restore-cascade-projection
    epoch-elapsed-ms             pure/epoch-elapsed-ms
    epoch-matches?               pure/epoch-matches?
    attribute-pair-epoch!        pure/attribute-pair-epoch
    last-pair-epoch              pure/pick-pair-epoch
    all-snapshot-slices          pure/all-snapshot-slices
    snapshot-state               pure/resolve-snapshot-frames
    orient-registrar-kinds       pure/orient-registrar-kinds
    orient                       pure/assemble-orient})

(deftest every-runtime-wrapper-delegates-to-its-pure-counterpart
  (doseq [[rt-name pure-sym] delegations]
    (testing (str rt-name " -> " pure-sym)
      (is (rt/mentions? pure-sym (named-form rt-name))
          (str rt-name " must be defined and delegate to " pure-sym)))))

(deftest raw-state-gate-is-threaded-into-the-redaction-fns
  (doseq [rt-name '[cascade-summary restore-cascade-summary]]
    (is (rt/mentions? :allow-raw-state? (named-form rt-name))
        (str rt-name " must thread the live :allow-raw-state? gate into the pure fn"))))

(let [{:keys [fail error]} (run-tests 'pure-delegation-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

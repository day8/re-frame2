;;;; tests/runtime/raw_state_tap_test.clj — the raw-state gate on tap>
;;;; payloads. The MCP server's `signal-runtime!` calls
;;;; `configure-raw-state!` once per build; with the gate OFF,
;;;; `app-db-reset!`'s tap> payload is projected through
;;;; `rf/project-egress` at the on-box redacted boundary. A bare REPL session
;;;; (no MCP server attached) defaults to verbatim payloads.
;;;;
;;;; Run: bb tests/runtime/raw_state_tap_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns raw-state-tap-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(defn- top-form [head-sym name-sym]
  (some #(when (and (seq? %) (= head-sym (first %)) (= name-sym (second %))) %) rt/all-forms))

(deftest raw-state-gate-elides-tap-payloads
  (let [elide (top-form 'defn- 'maybe-elide-for-tap)]
    (is (rt/mentions? :allow-raw-state? (top-form 'defn 'configure-raw-state!))
        "configure-raw-state! (called by the MCP server) must accept :allow-raw-state?")
    (is (rt/form-contains? #(and (map? %) (= true (:allow-raw-state? %)))
                           (top-form 'defonce 'raw-state-config))
        "raw-state-config defaults to {:allow-raw-state? true} for a bare REPL session")
    (is (rt/calls? 'rf/project-egress elide)
        "maybe-elide-for-tap projects through rf/project-egress when the gate is OFF")
    (is (str/includes? (pr-str elide) ":rf.egress/local-redacted")
        "and names the on-box redacted boundary")
    (is (rt/calls? 'maybe-elide-for-tap (top-form 'defn 'app-db-reset!))
        "app-db-reset!'s tap> payload routes through maybe-elide-for-tap")))

(let [{:keys [fail error]} (run-tests 'raw-state-tap-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

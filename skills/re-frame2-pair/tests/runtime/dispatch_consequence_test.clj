;;;; tests/runtime/dispatch_consequence_test.clj
;;;;
;;;; Babashka-runnable structural pin that the runtime preload defines the
;;;; wire-boundary consequence + validation surface the MCP `dispatch` tool
;;;; routes through:
;;;;
;;;;   - `dispatch-consequence!`  — the DEFAULT sync dispatch. Returns the
;;;;     re-frame2 CONSEQUENCE (`:db-changed?` / `:changed-paths` /
;;;;     `:effects-fired` / `:no-op?`) so a no-op is VISIBLE, not a fake
;;;;     `{:mode :sync}` ack. VALIDATEs the event-id FIRST and ECHOes the
;;;;     resolved event.
;;;;   - `validate-event-id` / `validate-registered` — the call-time id
;;;;     check against the LIVE registrar. An unknown id returns
;;;;     `:reason :unknown-id` with `:nearest` matches — never a silent
;;;;     no-op (the no-silent-swallow principle applied to the wire).
;;;;
;;;; Run: bb tests/runtime/dispatch_consequence_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dispatch-consequence-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj.
;; Alias the vars the assertions below use.
(def ^:private defn-named rt/defn-named)
(def ^:private form-contains? rt/form-contains?)

(deftest defines-dispatch-consequence
  (is (some? (defn-named 'dispatch-consequence!))
      "runtime.cljs must define `dispatch-consequence!` — the default sync dispatch surface the MCP dispatch tool routes through (rf2-3bu3d.2)."))

(deftest defines-validate-event-id
  (is (some? (defn-named 'validate-event-id))
      "runtime.cljs must define `validate-event-id` — the call-time event-id registry check (rf2-3bu3d.3)."))

(deftest defines-validate-registered
  (is (some? (defn-named 'validate-registered))
      "runtime.cljs must define `validate-registered` — the generic registry-validation helper (rf2-3bu3d.3)."))

(deftest consequence-delegates-to-tested-pure-projection
  (let [form (defn-named 'dispatch-consequence!)]
    (is (some? form))
    (when form
      ;; The consequence projection (`:db-changed?` / `:changed-paths` /
      ;; `:effects-fired` / `:no-op?`) now lives in the SHIPPED pure helper
      ;; `re-frame2-pair.pure/consequence-from-summary`, exercised directly by
      ;; the CLJS node-test (rf2-etsj8p). `consequence-from-summary` here is
      ;; the thin runtime wrapper that delegates to it — pin the delegation so
      ;; the runtime keeps calling the tested code.
      (let [proj (defn-named 'consequence-from-summary)]
        (is (some? proj) "consequence-from-summary wrapper is present")
        (is (form-contains? #(= 'pure/consequence-from-summary %) proj)
            "consequence-from-summary MUST delegate to the tested pure projection (rf2-etsj8p).")))))

(deftest consequence-echoes-resolved-event
  (let [form (defn-named 'dispatch-consequence!)]
    (is (form-contains? #(= :resolved %) form)
        "dispatch-consequence! MUST echo the parsed event under :resolved (rf2-3bu3d.3).")))

(deftest validate-delegates-to-tested-pure-core
  (let [form (defn-named 'validate-registered)]
    ;; The `:unknown-id` / `:nearest` envelope shape is produced by the SHIPPED
    ;; pure helper `re-frame2-pair.pure/validate-against-known` (node-tested,
    ;; rf2-etsj8p); `validate-registered` reads the LIVE registry and delegates
    ;; the decision. Pin the delegation so the runtime keeps calling the tested
    ;; code — the no-silent-swallow behaviour is asserted in the node-test.
    (is (form-contains? #(= 'pure/validate-against-known %) form)
        "validate-registered MUST delegate to the tested pure validation core (rf2-etsj8p).")))

(deftest nearest-ids-helper-present
  (is (some? (defn-named 'nearest-ids))
      "runtime.cljs must define `nearest-ids` — the edit-distance nearest-match ranking (rf2-3bu3d.3)."))

(let [{:keys [fail error]} (run-tests 'dispatch-consequence-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))

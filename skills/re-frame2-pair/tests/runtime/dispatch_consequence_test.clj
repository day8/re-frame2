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
;;;; The delegations behind this surface — `validate-registered`,
;;;; `consequence-from-summary` and `nearest-ids` into `re-frame2-pair.pure` —
;;;; are pinned in `pure_delegation_test.clj`'s `delegations` table.
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
      "runtime.cljs must define `dispatch-consequence!` — the default sync dispatch surface the MCP dispatch tool routes through."))

(deftest defines-validate-event-id
  (is (some? (defn-named 'validate-event-id))
      "runtime.cljs must define `validate-event-id` — the call-time event-id registry check."))

(deftest consequence-echoes-resolved-event
  (let [form (defn-named 'dispatch-consequence!)]
    (is (form-contains? #(= :resolved %) form)
        "dispatch-consequence! MUST echo the parsed event under :resolved.")))

(let [{:keys [fail error]} (run-tests 'dispatch-consequence-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))

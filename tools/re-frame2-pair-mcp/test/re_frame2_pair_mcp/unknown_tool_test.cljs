(ns re-frame2-pair-mcp.unknown-tool-test
  "Server-boundary unknown-tool guard tests (rf2-4mc6q1).

  The dispatcher's own `:unknown-tool` branch (`tools/dispatch-tool*`)
  resolves an unregistered name to the recovery-shaped `:unknown-tool`
  envelope — but it is reached only AFTER `ensure-connection!`. The real
  MCP server handler (`server.cljs/handle-call`) runs `ensure-connection!`
  for EVERY tool BEFORE the dispatcher. On a stock / misconfigured install
  with NO nREPL port, that connection step REJECTS with
  `:nrepl-port-not-found` and the unknown name never reaches the
  dispatcher — so a typo or removed alias (e.g. `registry-list`) returned a
  misleading discovery error, MASKING the `:unknown-tool` recovery
  affordances (`:hint` → tools/list, `:available-tools`, `:did-you-mean`).

  These tests pin the missing OUTER ring: the pre-connection guard
  (`tools/refuse-unknown-tool`) and the server `handle-call` ordering
  that proves the refusal precedes `ensure-connection!` — discovery never
  runs; the session-state stays pristine. Mirrors `writes_test.cljs`
  (rf2-wz66k7), the symmetric pre-connection write-gate."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.writes :as writes]))

(use-fixtures :each
  {:before (fn []
             (server/reset-session-state-for-tests!)
             (writes/set-allow-writes! false))
   :after  (fn []
             (server/reset-session-state-for-tests!)
             (writes/set-allow-writes! false))})

(def ^:private read-edn tu/extract-edn)
(def ^:private err? tu/error?)

;; ---------------------------------------------------------------------------
;; refuse-unknown-tool — the pure pre-dispatch predicate.
;; ---------------------------------------------------------------------------

(deftest refuse-unknown-tool-rejects-an-absent-name
  (let [result (tools/refuse-unknown-tool "no-such-tool")]
    (is (some? result) "an unregistered name is refused at the boundary")
    (is (err? result) "the refusal is an isError envelope")
    (let [edn (read-edn result)]
      (is (false? (:ok? edn)))
      (is (= :unknown-tool (:reason edn)))
      (is (= "no-such-tool" (:tool edn)) "the refusal names the requested tool")
      ;; Recovery affordances — identical to the dispatcher's branch.
      (is (string? (:hint edn)))
      (is (re-find #"tools/list" (:hint edn))
          "hint points the agent at tools/list to recover")
      (is (vector? (:available-tools edn)))
      (is (some #{"snapshot"} (:available-tools edn))
          ":available-tools carries the real catalogue"))))

(deftest refuse-unknown-tool-offers-nearest-match
  ;; A near-miss typo of a real name surfaces a :did-you-mean pointer —
  ;; the same recovery the dispatcher branch emits (rf2-tkmik).
  (let [result (tools/refuse-unknown-tool "snapsho")
        edn    (read-edn result)]
    (is (= :unknown-tool (:reason edn)))
    (is (= "snapshot" (:did-you-mean edn))
        "near typo `snapsho` suggests `snapshot`")
    (is (re-find #"did you mean" (:hint edn))
        "hint inlines the nearest-match suggestion")))

(deftest refuse-unknown-tool-passes-registered-names-through
  (doseq [tool ["snapshot" "get-path" "dispatch" "eval-cljs" "subscribe"
                "trace-window" "discover-app" "restore-epoch" "replace-app-db"
                "list-handlers"]]
    (is (nil? (tools/refuse-unknown-tool tool))
        (str tool " is a registered tool — must proceed to dispatch"))))

;; ---------------------------------------------------------------------------
;; Server boundary — the refusal precedes ensure-connection! (no discovery).
;;
;; The session-state is reset (pristine, :discovered? false) by the
;; fixture, and no --port-file / env is configured in the test harness, so
;; the real discovery cascade would REJECT with :nrepl-port-not-found if
;; `handle-call` reached `ensure-connection!`. Proving the result is
;; :unknown-tool (NOT :nrepl-port-not-found) AND that the session stayed
;; pristine shows the refusal ran FIRST.
;; ---------------------------------------------------------------------------

(deftest unknown-tool-refused-before-connection
  (async done
    (-> (server/handle-call-for-tests {} "no-such-tool" #js {} nil)
        (.then (fn [result]
                 (is (err? result) "unknown tool rides as isError")
                 (let [edn (read-edn result)]
                   (is (= :unknown-tool (:reason edn))
                       "unknown tool returns :unknown-tool, NOT :nrepl-port-not-found")
                   (is (= "no-such-tool" (:tool edn)))
                   (is (re-find #"tools/list" (:hint edn))
                       "the recovery hint survives at the server boundary")
                   (is (vector? (:available-tools edn))))
                 (let [snap (server/session-state-snapshot)]
                   (is (false? (:discovered? snap))
                       "discovery was NOT run — the refusal short-circuited before ensure-connection!")
                   (is (nil? (:discovery-error snap))
                       "no discovery attempt recorded — connection step never reached"))
                 (done)))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) (done))))))

;; ---------------------------------------------------------------------------
;; Server-boundary regression guard: a removed alias such as `registry-list`
;; (renamed to `list-handlers` per rf2-4y595) must diagnose as :unknown-tool
;; at the boundary — the exact masking the bead reported.
;; ---------------------------------------------------------------------------

(deftest removed-alias-diagnoses-as-unknown-tool-not-discovery-error
  (async done
    (-> (server/handle-call-for-tests {} "registry-list" #js {} nil)
        (.then (fn [result]
                 (is (err? result))
                 (let [edn (read-edn result)]
                   (is (= :unknown-tool (:reason edn))
                       "a removed alias diagnoses as :unknown-tool, not :nrepl-port-not-found")
                   (is (= "registry-list" (:tool edn)))
                   ;; `registry-list` is too far in edit distance from the
                   ;; renamed `list-handlers` to trip the :did-you-mean
                   ;; near-match cutoff — but the live catalogue still
                   ;; carries the replacement, so the agent recovers via
                   ;; the :available-tools list + the tools/list hint.
                   (is (some #{"list-handlers"} (:available-tools edn))
                       "the renamed tool is enumerated in the live catalogue")
                   (is (re-find #"tools/list" (:hint edn))))
                 (done)))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) (done))))))

;; ---------------------------------------------------------------------------
;; structuredContent on the boundary refusal preserves the keyword
;; namespace (rf2-or8s29) — the envelope rides through `wire/err-text`, the
;; same dual-slot constructor the dispatcher uses, so the SDK-friendly slot
;; is namespace-faithful (not a raw, namespace-lossy clj->js).
;; ---------------------------------------------------------------------------

(deftest unknown-tool-boundary-structured-content-is-faithful
  (async done
    (-> (server/handle-call-for-tests {} "no-such-tool" #js {} nil)
        (.then (fn [result]
                 (let [sc (j/get result :structuredContent)]
                   (is (= "unknown-tool" (j/get sc "reason"))
                       "structuredContent :reason keeps its token")
                   (is (= false (j/get sc "ok?"))
                       ":ok? false round-trips through the structured slot")
                   (is (= "no-such-tool" (j/get sc "tool"))))
                 (done)))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) (done))))))

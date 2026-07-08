(ns re-frame2-pair-mcp.closed-world-test
  "Server-boundary closed-world dispatch tests (rf2-6amhbt).

  The server handler (`server.cljs/handle-call`) runs `ensure-connection!`
  for the connected tools BEFORE the dispatcher. But two tools —
  `get-stream-controls` and `get-re-frame2-pair-instructions` — read only
  server-local state (resource-control atoms / an inline text `def`) with
  NO nREPL round-trip. On a stock / degraded install with no nREPL port,
  routing them through `ensure-connection!` would REJECT with
  `:nrepl-port-not-found` and the closed-world body would never run,
  contradicting the spec/003 'answers even when the runtime is down'
  contract.

  These tests pin the OUTER ring: the pre-connection closed-world dispatch
  (`registry/closed-world-tool?` + the server `handle-call` branch) proves
  the tool answers SUCCESSFULLY before `ensure-connection!` — discovery
  never runs; the session-state stays pristine. Mirrors
  `unknown_tool_test.cljs` and `writes_test.cljs`, the symmetric
  pre-connection guards."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(def ^:private read-edn tu/extract-edn)
(def ^:private err? tu/error?)

;; ---------------------------------------------------------------------------
;; closed-world-tool? — the pure predicate.
;; ---------------------------------------------------------------------------

(deftest closed-world-tool?-flags-exactly-the-server-local-reads
  (is (true? (registry/closed-world-tool? "get-stream-controls")))
  (is (true? (registry/closed-world-tool? "get-re-frame2-pair-instructions")))
  ;; The predicate is re-exported onto the façade the server consumes.
  (is (identical? tools/closed-world-tool? registry/closed-world-tool?))
  ;; Everything else needs a live runtime — NOT closed-world. A false
  ;; positive here would route a nREPL-dependent tool around the
  ;; connection and hand it a nil conn.
  (doseq [tool ["discover-app" "eval-cljs" "dispatch" "snapshot" "get-path"
                "subscribe" "list-streams" "restore-epoch" "replace-app-db"]]
    (is (false? (registry/closed-world-tool? tool))
        (str tool " needs a live runtime — must NOT be closed-world")))
  (is (false? (registry/closed-world-tool? "no-such-tool"))
      "an unknown name is never closed-world"))

;; ---------------------------------------------------------------------------
;; Server boundary — the closed-world dispatch precedes ensure-connection!
;;
;; The session-state is reset (pristine, :discovered? false) by the
;; fixture, and no --port-file / env is configured, so the real discovery
;; cascade would REJECT with :nrepl-port-not-found if `handle-call`
;; reached `ensure-connection!`. Proving the result is a SUCCESS envelope
;; (isError false, :ok? true) AND that the session stayed pristine shows
;; the dispatch ran WITHOUT discovery.
;; ---------------------------------------------------------------------------

(deftest instructions-answered-before-connection
  (async done
    (-> (server/handle-call-for-tests {} "get-re-frame2-pair-instructions" #js {} nil)
        (.then (fn [result]
                 (is (not (err? result))
                     "closed-world instructions succeed with NO nREPL — not a degraded error")
                 (let [edn (read-edn result)]
                   (is (true? (:ok? edn)))
                   (is (not= :nrepl-port-not-found (:reason edn))
                       "never the degraded discovery error")
                   (is (string? (:text edn)) "the onboarding prose rides back"))
                 (let [snap (server/session-state-snapshot)]
                   (is (false? (:discovered? snap))
                       "discovery was NOT run — dispatched before ensure-connection!")
                   (is (nil? (:discovery-error snap))
                       "no discovery attempt recorded — connection step never reached"))
                 (done)))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) (done))))))

(deftest stream-controls-answered-before-connection
  (async done
    (-> (server/handle-call-for-tests {} "get-stream-controls" #js {} nil)
        (.then (fn [result]
                 (is (not (err? result))
                     "closed-world stream-controls succeed with NO nREPL")
                 (let [edn (read-edn result)]
                   (is (true? (:ok? edn)))
                   (is (not= :nrepl-port-not-found (:reason edn)))
                   ;; The in-process resource-control payload rides back —
                   ;; a wired-but-empty handler would fail this.
                   (is (map? (:config edn)) "server-local :config payload present")
                   (is (contains? edn :concurrent-streams)))
                 (let [snap (server/session-state-snapshot)]
                   (is (false? (:discovered? snap))
                       "discovery was NOT run — dispatched before ensure-connection!")
                   (is (nil? (:discovery-error snap))))
                 (done)))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) (done))))))

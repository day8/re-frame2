(ns re-frame2-pair-mcp.writes-test
  "Server-boundary write-gate tests (rf2-wz66k7).

  The write-tool BODIES (`restore-epoch-tool` / `replace-app-db-tool`)
  refuse `:rf.error/writes-disabled` as their first action without
  touching nREPL — already covered (restore_epoch_test /
  replace_app_db_test). But the real MCP server handler
  (`server.cljs/handle-call`) runs `ensure-connection!` for EVERY tool
  BEFORE the tool body. On a stock install with NO nREPL port, that
  connection step REJECTS and the tool body's gate NEVER fires, so a
  disabled `restore-epoch` / `replace-app-db` returned a misleading
  `:nrepl-port-not-found` (or ran discovery / elicitation) instead of the
  intended destructive-tool refusal — the default-safe write posture was
  observably FALSE at the MCP boundary.

  These tests pin the missing OUTER ring: the pre-connection guard
  (`writes/refuse-pre-connection`) and the server `handle-call` ordering
  that proves the refusal precedes `ensure-connection!` (discovery never
  runs; the session-state stays pristine)."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.server :as server]
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
;; refuse-pre-connection — the pure pre-dispatch predicate.
;; ---------------------------------------------------------------------------

(deftest refuse-pre-connection-gates-the-two-write-tools-when-off
  (writes/set-allow-writes! false)
  (doseq [tool ["restore-epoch" "replace-app-db"]]
    (let [result (writes/refuse-pre-connection tool)]
      (is (some? result) (str tool " is refused at the boundary when writes are OFF"))
      (is (err? result))
      (let [edn (read-edn result)]
        (is (= :rf.error/writes-disabled (:reason edn)))
        (is (= tool (:tool edn)) "the refusal names the specific tool")))))

(deftest refuse-pre-connection-passes-write-tools-through-when-on
  (writes/set-allow-writes! true)
  (doseq [tool ["restore-epoch" "replace-app-db"]]
    (is (nil? (writes/refuse-pre-connection tool))
        (str tool " falls through to normal dispatch when writes are ON"))))

(deftest refuse-pre-connection-never-gates-non-write-tools
  (writes/set-allow-writes! false)
  (doseq [tool ["snapshot" "get-path" "dispatch" "eval-cljs" "subscribe"
                "trace-window" "discover-app"]]
    (is (nil? (writes/refuse-pre-connection tool))
        (str tool " is not a gated write tool — must proceed to dispatch"))))

;; ---------------------------------------------------------------------------
;; Server boundary — the refusal precedes ensure-connection! (no discovery).
;; ---------------------------------------------------------------------------

(defn- assert-refused-locally [tool-name done]
  ;; The session-state is reset (pristine, :discovered? false) by the
  ;; fixture, and no --port-file / env is configured in the test harness,
  ;; so the real discovery cascade would REJECT with :nrepl-port-not-found
  ;; if `handle-call` reached `ensure-connection!`. Proving the result is
  ;; :rf.error/writes-disabled (NOT :nrepl-port-not-found) AND that the
  ;; session stayed pristine shows the refusal ran FIRST.
  (-> (server/handle-call-for-tests {} tool-name #js {} nil)
      (.then (fn [result]
               (is (err? result))
               (let [edn (read-edn result)]
                 (is (= :rf.error/writes-disabled (:reason edn))
                     (str tool-name " returns writes-disabled, NOT nrepl-port-not-found"))
                 (is (= tool-name (:tool edn))))
               (let [snap (server/session-state-snapshot)]
                 (is (false? (:discovered? snap))
                     "discovery was NOT run — the refusal short-circuited before ensure-connection!")
                 (is (nil? (:discovery-error snap))
                     "no discovery attempt recorded — connection step never reached"))
               (done)))
      (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) (done)))))

(deftest restore-epoch-refused-before-connection
  (async done
    (writes/set-allow-writes! false)
    (assert-refused-locally "restore-epoch" done)))

(deftest replace-app-db-refused-before-connection
  (async done
    (writes/set-allow-writes! false)
    (assert-refused-locally "replace-app-db" done)))

;; ---------------------------------------------------------------------------
;; Server-level error envelope — namespace-preserving structuredContent
;; (rf2-or8s29).
;;
;; The discovery-error / handler-threw envelopes are built in server.cljs
;; OUTSIDE the per-tool callbacks. Before rf2-or8s29 they used a raw
;; `(clj->js payload)` for structuredContent, which truncates the
;; namespace on `:rf.error/*` reason VALUES (`:rf.error/foo` →
;; `"foo"`). Routing them through `wire/result` preserves the
;; fully-qualified token in the SDK-friendly structured slot. Here we
;; drive a NON-write tool through the pristine, no-port harness so
;; `ensure-connection!` rejects and `handle-call*` builds the
;; discovery-error envelope; we assert both the EDN text slot AND the
;; structured slot agree on the reason, namespace intact.
;; ---------------------------------------------------------------------------

(deftest discovery-error-structured-content-preserves-reason-namespace
  (async done
    (writes/set-allow-writes! false)
    (-> (server/handle-call-for-tests {} "snapshot" #js {} nil)
        (.then (fn [result]
                 (is (err? result) "discovery failure rides as isError")
                 (let [edn          (read-edn result)
                       reason       (:reason edn)
                       reason-token (str (symbol reason))
                       sc           (j/get result :structuredContent)]
                   ;; The canonical EDN reason is a keyword.
                   (is (keyword? reason) "the EDN reason is a keyword")
                   ;; The structured slot must carry the reason under its
                   ;; colon-less FULLY-QUALIFIED token — proving the
                   ;; envelope went through wire/result, not a raw clj->js
                   ;; (a namespaced reason would otherwise be truncated).
                   (is (= reason-token (j/get sc "reason"))
                       "structuredContent :reason keeps its fully-qualified token")
                   (is (= false (j/get sc "ok?"))
                       ":ok? false round-trips through the structured slot"))
                 (done)))
        (.catch (fn [e] (is false (str "handle-call rejected: " (.-message e))) (done))))))

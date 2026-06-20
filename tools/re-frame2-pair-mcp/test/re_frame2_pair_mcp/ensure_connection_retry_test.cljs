(ns re-frame2-pair-mcp.ensure-connection-retry-test
  "Regression guard for the sticky-discovery-error wedge.

  The contract: discovery failure records `:discovery-error` for
  diagnostics ONLY; while `:discovered?` is false, each tool call
  RE-RUNS the cascade. A recoverable failure (shadow not up at the
  first call) self-heals on a later call once the operator starts the
  build — there is no permanent wedge that requires restarting the MCP
  server.

  The 2-arity `ensure-connection!` injects the discovery thunk so the
  retry contract is unit-testable without the npm SDK / a live shadow."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(deftest discovery-failure-is-retried-not-sticky
  (testing "a failed discovery does NOT wedge the session — the next call re-attempts and can succeed"
    (async done
      (let [calls (atom 0)
            err   (ex-info ":rf.error/pair-mcp-nrepl-port-not-found"
                           {:rf.error/id :rf.error/pair-mcp-nrepl-port-not-found})
            conn  (nrepl/make-conn 6001 "127.0.0.1")
            ;; First invocation rejects (shadow not up yet); the second
            ;; succeeds (operator started the build) by recording the
            ;; discovered conn the way the real `discover-and-cache!` does.
            discover-fn
            (fn [_launch-flags]
              (let [n (swap! calls inc)]
                (if (= n 1)
                  (js/Promise.reject err)
                  (do (server/mark-discovered-for-tests! conn)
                      (js/Promise.resolve :ok)))))]
        ;; First tool call: discovery fails. `:discovery-error` recorded.
        (-> (server/ensure-connection! {} discover-fn)
            (.then (fn [_]
                     (is false "first call must REJECT (discovery failed)")
                     (done)))
            (.catch
             (fn [e1]
               (is (= err e1) "first failure surfaces the structured discovery error")
               (is (some? (:discovery-error (server/session-state-snapshot)))
                   "the failure is recorded for diagnostics")
               (is (false? (:discovered? (server/session-state-snapshot)))
                   "still not discovered — the session is not wedged")
               ;; Second tool call: while not discovered, the cascade
               ;; re-runs discovery here rather than replaying the cached
               ;; error, so this attempt reaches the (now-succeeding) thunk.
               (-> (server/ensure-connection! {} discover-fn)
                   (.then (fn [resolved-conn]
                            (is (= 2 @calls)
                                "discovery was RE-INVOKED on the retry (not short-circuited)")
                            (is (= conn resolved-conn)
                                "the retry resolves to the freshly-discovered conn")
                            (is (true? (:discovered? (server/session-state-snapshot)))
                                "the session is now attached")
                            (done)))
                   (.catch (fn [_e2]
                             (is false "the retry must SUCCEED once discovery recovers")
                             (done)))))))))))

(deftest repeated-failures-keep-resurfacing-fresh-errors
  (testing "while discovery keeps failing, each call re-runs it and surfaces a fresh rejection (no permanent wedge)"
    (async done
      (let [calls (atom 0)
            err   (ex-info ":rf.error/pair-mcp-nrepl-port-not-found"
                           {:rf.error/id :rf.error/pair-mcp-nrepl-port-not-found})
            discover-fn (fn [_launch-flags]
                          (swap! calls inc)
                          (js/Promise.reject err))]
        (-> (server/ensure-connection! {} discover-fn)
            (.catch (fn [_e1]
                      (is (= 1 @calls))
                      ;; A second call must ALSO re-invoke discovery rather
                      ;; than replaying the cached error without trying.
                      (-> (server/ensure-connection! {} discover-fn)
                          (.catch (fn [_e2]
                                    (is (= 2 @calls)
                                        "the second failing call re-ran discovery (not a cached replay)")
                                    (done)))))))))))

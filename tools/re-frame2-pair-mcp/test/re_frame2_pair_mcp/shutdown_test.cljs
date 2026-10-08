(ns re-frame2-pair-mcp.shutdown-test
  "stdin-EOF session teardown. When the MCP host closes stdin the server must
  close the persistent nREPL socket and exit 0; an idle socket would otherwise
  keep the event loop alive. This is the hermetic counterpart of the
  real-boundary subprocess test `test/stdin-eof-shutdown.cjs`, which also
  covers EOF before the first tool call and a duplicate terminal event: here
  `exit-fn` is injected, so nothing exits."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]))

(use-fixtures :each
  {:before (fn []
             (server/reset-session-state-for-tests!)
             (server/reset-shutdown-latch-for-tests!)
             ;; No SDK server, so teardown's `server.close()` settles at once.
             (server/set-server-instance-for-tests! nil))
   :after  (fn []
             (server/reset-session-state-for-tests!)
             (server/reset-shutdown-latch-for-tests!)
             (server/set-server-instance-for-tests! nil))})

(deftest eof-closes-socket-and-exits-zero
  (async done
    (let [end-count (atom 0)
          exit-args (atom [])
          conn      (nrepl/make-conn 6543 "127.0.0.1")]
      (swap! conn assoc
             :socket  (j/lit {:end (fn [] (swap! end-count inc) nil)})
             :closed? false)
      (server/mark-discovered-for-tests! conn)
      (-> (server/shutdown! "unit-test EOF" (fn [code] (swap! exit-args conj code)))
          (.then (fn [_]
                   (is (= 1 @end-count) "the persistent nREPL socket was closed exactly once")
                   (is (= [0] @exit-args) "exited exactly once with code 0")))
          (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

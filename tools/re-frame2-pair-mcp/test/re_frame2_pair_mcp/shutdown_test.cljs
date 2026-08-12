(ns re-frame2-pair-mcp.shutdown-test
  "stdin-EOF session teardown (rf2-j538f7.32).

  The MCP host owns process lifecycle: when it closes stdin, Node reaches
  EOF and the server must retire the session — close the persistent nREPL
  socket and exit 0. Before the fix no `process.stdin` `end` listener was
  installed, so a completed tool's idle nREPL socket kept the event loop
  alive indefinitely.

  These are the hermetic unit counterparts to the real-boundary subprocess
  regression (`test/stdin-eof-shutdown.cjs`): they grade `shutdown!`'s
  teardown ordering and one-shot idempotency directly, with an injected
  `exit-fn` so no process actually exits and no npm SDK / live shadow is
  required."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]))

(use-fixtures :each
  {:before (fn []
             (server/reset-session-state-for-tests!)
             (server/reset-shutdown-latch-for-tests!)
             ;; No SDK server captured — teardown's `server.close()` is a
             ;; Promise.resolve nil, so the exit path settles immediately.
             (server/set-server-instance-for-tests! nil))
   :after  (fn []
             (server/reset-session-state-for-tests!)
             (server/reset-shutdown-latch-for-tests!)
             (server/set-server-instance-for-tests! nil))})

(defn- conn-with-recording-socket
  "A discovered conn whose socket records `.end` invocations, so the test
  can assert `nrepl/close!` fired exactly once on EOF."
  [end-count]
  (let [conn (nrepl/make-conn 6543 "127.0.0.1")]
    (swap! conn assoc
           :socket  (j/lit {:end (fn [] (swap! end-count inc) nil)})
           :closed? false)
    conn))

(deftest eof-closes-socket-drops-conn-and-exits-zero
  (testing "shutdown! closes the nREPL socket, clears :conn, and exits 0 exactly once"
    (async done
      (let [end-count (atom 0)
            exit-args (atom [])
            conn      (conn-with-recording-socket end-count)]
        (server/mark-discovered-for-tests! conn)
        (-> (server/shutdown! "unit-test EOF" (fn [code] (swap! exit-args conj code)))
            (.then (fn [_]
                     (is (= 1 @end-count) "the persistent nREPL socket was closed exactly once")
                     (is (nil? (:conn (server/session-state-snapshot)))
                         "the session conn reference was dropped")
                     (is (= [0] @exit-args) "exited exactly once with code 0")))
            (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

(deftest duplicate-terminal-event-is-a-no-op
  (testing "a second shutdown! (end then close) does not double-close or re-exit"
    (async done
      (let [end-count (atom 0)
            exit-args (atom [])
            conn      (conn-with-recording-socket end-count)
            exit-fn   (fn [code] (swap! exit-args conj code))]
        (server/mark-discovered-for-tests! conn)
        (-> (server/shutdown! "first EOF" exit-fn)
            (.then (fn [_]
                     ;; The duplicate terminal signal — must claim nothing.
                     (-> (server/shutdown! "duplicate close" exit-fn)
                         (.then (fn [r]
                                  (is (= :already-shutting-down r)
                                      "the duplicate shutdown short-circuits")
                                  (is (= 1 @end-count)
                                      "the socket was NOT closed a second time")
                                  (is (= [0] @exit-args)
                                      "exit fired exactly once across both calls"))))))
            (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

(deftest eof-before-first-tool-exits-cleanly
  (testing "EOF with no discovered conn (no socket ever opened) still exits 0"
    (async done
      (let [exit-args (atom [])]
        ;; Pristine session — :conn is nil (discovery never ran).
        (is (nil? (:conn (server/session-state-snapshot))))
        (-> (server/shutdown! "early EOF" (fn [code] (swap! exit-args conj code)))
            (.then (fn [_]
                     (is (= [0] @exit-args) "exited 0 with nothing to close")))
            (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

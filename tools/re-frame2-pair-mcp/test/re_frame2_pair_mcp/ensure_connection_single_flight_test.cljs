(ns re-frame2-pair-mcp.ensure-connection-single-flight-test
  "Single-flight guard for session discovery + endpoint replacement
  (rf2-3fc89f.23).

  MCP permits concurrent tool calls, so two calls can observe the same
  pre-transition session state — `:discovered? false`, or a cached port that
  just changed. The OLD `ensure-connection!` did a bare check-then-act:
  concurrent first calls each ran discovery, and concurrent port-change calls
  each closed the old conn and published a fresh one. The fix routes every
  state-changing path through `run-transition!`, so the FIRST caller becomes
  the transition owner and every concurrent caller awaits the same Promise —
  exactly one discovery, exactly one close of the old conn, exactly one
  authoritative conn published.

  The 2-arity `ensure-connection!` injects the discovery thunk so these
  contracts are unit-testable without the npm SDK / a live shadow."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]
            ["fs" :as fs]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(defn- with-fs-read
  "Install `stub-fn` as `fs.readFileSync`; returns a 0-arity restore thunk."
  [stub-fn]
  (let [orig (.-readFileSync fs)]
    (set! (.-readFileSync fs) stub-fn)
    (fn restore! [] (set! (.-readFileSync fs) orig))))

(defn- reads-port
  "A `readFileSync` stub returning `port` (as bytes) for ANY path — models a
  cached port file whose content just changed to `port`."
  [port]
  (fn [^js _path] (js/Buffer.from (str port) "utf8")))

(deftest concurrent-first-calls-run-discovery-exactly-once
  (testing "two simultaneous pristine ensure-connection! calls share ONE discovery and resolve to the SAME conn"
    (async done
      (let [calls (atom 0)
            conn  (nrepl/make-conn 6001 "127.0.0.1")
            ;; Discovery resolves asynchronously (a real cascade awaits IO),
            ;; so both callers are in flight before it settles.
            discover-fn
            (fn [_flags]
              (swap! calls inc)
              (-> (js/Promise.resolve nil)
                  (.then (fn [_] (server/mark-discovered-for-tests! conn) :ok))))
            p1 (server/ensure-connection! {} discover-fn)
            p2 (server/ensure-connection! {} discover-fn)]
        (is (some? (:transition (server/session-state-snapshot)))
            "the transition slot is claimed while discovery is in flight")
        (-> (js/Promise.all #js [p1 p2])
            (.then (fn [^js rs]
                     (is (= 1 @calls)
                         "discovery ran EXACTLY once for two concurrent first calls")
                     (is (identical? conn (aget rs 0)) "caller 1 got the discovered conn")
                     (is (identical? conn (aget rs 1)) "caller 2 got the SAME conn")
                     (is (true? (:discovered? (server/session-state-snapshot))))
                     (is (nil? (:transition (server/session-state-snapshot)))
                         "the transition slot is cleared after settle")))
            (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

(deftest concurrent-port-change-calls-replace-endpoint-exactly-once
  (testing "two simultaneous cached-port-change calls close the old conn ONCE and publish ONE replacement"
    (async done
      (let [end-count (atom 0)
            old-conn  (nrepl/make-conn 7001 "127.0.0.1")
            restore!  (with-fs-read (reads-port 7002))]  ; cached file now reads a NEW port
        (swap! old-conn assoc
               :socket (j/lit {:end (fn [] (swap! end-count inc) nil)})
               :closed? false)
        (server/set-discovered-for-tests!
          {:conn old-conn :port 7001 :port-file "/proj/target/shadow-cljs/nrepl.port"
           :project-home "/proj"})
        (let [;; The replacement path must NOT invoke discovery — a rejecting
              ;; thunk proves it isn't called.
              never (fn [_] (js/Promise.reject (js/Error. "discovery must not run on a port change")))
              p1    (server/ensure-connection! {} never)
              p2    (server/ensure-connection! {} never)]
          (-> (js/Promise.all #js [p1 p2])
              (.then (fn [^js rs]
                       (is (= 1 @end-count) "the old conn was closed EXACTLY once")
                       (is (identical? (aget rs 0) (aget rs 1))
                           "both callers got the SAME replacement conn")
                       (is (= 7002 (:port @(aget rs 0))) "the replacement targets the new port")
                       (is (= 7002 (:port (server/session-state-snapshot)))
                           "session records the new port")
                       (is (nil? (:transition (server/session-state-snapshot)))
                           "the transition slot is cleared after settle")))
              (.catch (fn [e]
                        (is false (str "unexpected reject: " (.-message e))) nil))
              ;; `restore!` is the same idempotent `set!` on both arms, so it
              ;; moves to the single trailing step, still ahead of `done`.
              (.then (fn [_] (restore!) (done)))))))))

(deftest concurrent-failed-discovery-rejects-all-then-retries
  (testing "a shared discovery failure rejects every waiter, clears the slot, and a later call retries successfully"
    (async done
      (let [calls (atom 0)
            err   (ex-info ":rf.error/pair-mcp-nrepl-port-not-found"
                           {:rf.error/id :rf.error/pair-mcp-nrepl-port-not-found})
            fail  (fn [_]
                    (swap! calls inc)
                    (-> (js/Promise.resolve nil) (.then (fn [_] (js/Promise.reject err)))))
            p1    (server/ensure-connection! {} fail)
            p2    (server/ensure-connection! {} fail)]
        (-> (js/Promise.allSettled #js [p1 p2])
            (.then (fn [^js results]
                     (is (= 1 @calls)
                         "one shared discovery for two concurrent first calls")
                     (is (= "rejected" (j/get (aget results 0) :status)) "caller 1 rejected")
                     (is (= "rejected" (j/get (aget results 1) :status)) "caller 2 rejected")
                     (is (nil? (:transition (server/session-state-snapshot)))
                         "the in-flight slot is cleared after the shared failure")
                     (is (false? (:discovered? (server/session-state-snapshot)))
                         "still not discovered — the session is not wedged")
                     ;; A later call retries: slot clear, discovered? false.
                     (let [conn  (nrepl/make-conn 6001 "127.0.0.1")
                           ok-fn (fn [_]
                                   (swap! calls inc)
                                   (server/mark-discovered-for-tests! conn)
                                   (js/Promise.resolve :ok))]
                       ;; RETURNED into the outer chain, so the retry is awaited
                       ;; by the single trailing `done` instead of finishing the
                       ;; row itself while two `.catch`es are still downstream.
                       (-> (server/ensure-connection! {} ok-fn)
                           (.then (fn [c]
                                    (is (= 2 @calls) "discovery RE-RAN on the retry")
                                    (is (identical? conn c) "the retry resolved to the fresh conn")
                                    (is (true? (:discovered? (server/session-state-snapshot))))))
                           (.catch (fn [e2]
                                     (is false (str "retry must succeed: " (.-message e2)))
                                     nil))))))
            (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

(deftest fast-path-needs-no-transition
  (testing "when the cached port-file still reads the same port, ensure-connection! returns the cached conn with no transition"
    (async done
      (let [conn     (nrepl/make-conn 7001 "127.0.0.1")
            restore! (with-fs-read (reads-port 7001))]   ; unchanged
        (server/set-discovered-for-tests!
          {:conn conn :port 7001 :port-file "/proj/target/shadow-cljs/nrepl.port"
           :project-home "/proj"})
        (-> (server/ensure-connection! {} (fn [_] (js/Promise.reject (js/Error. "must not re-discover"))))
            (.then (fn [resolved]
                     (is (identical? conn resolved) "the cached conn is reused")
                     (is (nil? (:transition (server/session-state-snapshot)))
                         "the fast path never opened a transition")))
            (.catch (fn [e]
                      (is false (str "fast path must not reject: " (.-message e))) nil))
            (.then (fn [_] (restore!) (done))))))))

(ns re-frame2-pair-mcp.ensure-connection-single-flight-test
  "Single-flight session transitions in `ensure-connection!`. MCP permits
  concurrent tool calls, so two calls can see the same pre-transition state;
  the first becomes the transition owner and the rest await its Promise —
  one discovery, one close of the old conn, one conn published. The 2-arity
  injects the discovery thunk, so no SDK or live shadow is needed."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]
            ["fs" :as fs]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(deftest concurrent-first-calls-run-discovery-exactly-once
  (async done
    (let [calls       (atom 0)
          conn        (nrepl/make-conn 6001 "127.0.0.1")
          ;; Discovery settles asynchronously, so both callers are in flight first.
          discover-fn (fn [_flags]
                        (swap! calls inc)
                        (-> (js/Promise.resolve nil)
                            (.then (fn [_] (server/mark-discovered-for-tests! conn) :ok))))
          p1          (server/ensure-connection! {} discover-fn)
          p2          (server/ensure-connection! {} discover-fn)]
      (-> (js/Promise.all #js [p1 p2])
          (.then (fn [^js rs]
                   (is (= 1 @calls) "discovery ran EXACTLY once for two concurrent first calls")
                   (is (= [conn conn] (vec rs)) "both callers got the discovered conn")
                   (is (nil? (:transition (server/session-state-snapshot)))
                       "the settled transition is released, so later calls see port changes")))
          (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

(deftest concurrent-port-change-calls-replace-endpoint-exactly-once
  (async done
    (let [end-count (atom 0)
          old-conn  (nrepl/make-conn 7001 "127.0.0.1")
          orig-read (.-readFileSync fs)]
      ;; The cached port file now reads a NEW port.
      (set! (.-readFileSync fs) (fn [^js _path] (js/Buffer.from "7002" "utf8")))
      (swap! old-conn assoc
             :socket (j/lit {:end (fn [] (swap! end-count inc) nil)})
             :closed? false)
      (server/set-discovered-for-tests!
        {:conn old-conn :port 7001 :port-file "/proj/target/shadow-cljs/nrepl.port"
         :project-home "/proj"})
      (let [;; A port change must not re-run discovery: this thunk would reject.
            never (fn [_] (js/Promise.reject (js/Error. "discovery must not run on a port change")))
            p1    (server/ensure-connection! {} never)
            p2    (server/ensure-connection! {} never)]
        (-> (js/Promise.all #js [p1 p2])
            (.then (fn [^js rs]
                     (is (= 1 @end-count) "the old conn was closed EXACTLY once")
                     (is (identical? (aget rs 0) (aget rs 1)) "both callers got the SAME replacement conn")
                     (is (= [7002 7002] [(:port @(aget rs 0)) (:port (server/session-state-snapshot))])
                         "the replacement and the session both target the new port")))
            (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
            (.then (fn [_] (set! (.-readFileSync fs) orig-read) (done))))))))

(deftest concurrent-failed-discovery-rejects-all-then-retries
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
                   (is (= 1 @calls) "one shared discovery for two concurrent first calls")
                   (is (= [["rejected" err] ["rejected" err]]
                          (mapv #(vector (j/get % :status) (j/get % :reason)) results))
                       "every waiter rejects with the structured discovery error")
                   ;; The failure is not sticky: a later call re-runs discovery.
                   (let [conn  (nrepl/make-conn 6001 "127.0.0.1")
                         ok-fn (fn [_]
                                 (swap! calls inc)
                                 (server/mark-discovered-for-tests! conn)
                                 (js/Promise.resolve :ok))]
                     (-> (server/ensure-connection! {} ok-fn)
                         (.then (fn [_] (is (= 2 @calls) "discovery RE-RAN on the retry")))))))
          (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

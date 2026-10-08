(ns re-frame2-pair-mcp.port-file-stability-test
  "Port-file discovery stability in `server/ensure-connection!`.

  The server caches the exact `:port-file` discovery resolved and re-reads
  it on every tool call: the same port keeps the cached connection, a new
  port replaces it, a vanished file forces rediscovery. A derived
  `<project-home>/.shadow-cljs/nrepl.port` would point an explicit
  `target/shadow-cljs/nrepl.port` at a file that does not exist, and every
  call would then reconnect and reset the per-connection build caches."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]
            ["fs" :as fs]
            ["path" :as node-path]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(defn- with-fs-read
  "Install `stub-fn` as `fs.readFileSync`; returns the restore thunk, which
  each test calls in its last step, before `done`."
  [stub-fn]
  (let [orig (.-readFileSync fs)]
    (set! (.-readFileSync fs) stub-fn)
    (fn restore! [] (set! (.-readFileSync fs) orig))))

(deftest cached-explicit-port-file-stays-on-connection-when-unchanged
  (async done
    (let [explicit-pf "C:/repo/target/shadow-cljs/nrepl.port"
          conn        (nrepl/make-conn 7001 "127.0.0.1")
          ;; Only the exact discovered path exists.
          restore!    (with-fs-read (fn [^js path]
                                      (if (= (str path) explicit-pf)
                                        "7001"
                                        (throw (js/Error. "ENOENT")))))]
      (server/set-discovered-for-tests!
        {:conn conn :port 7001 :port-file explicit-pf
         :project-home "C:/repo/target/shadow-cljs"})
      (-> (server/ensure-connection! {} (fn [_] (js/Promise.reject (js/Error. "must not re-discover"))))
          (.then (fn [resolved-conn]
                   (is (= conn resolved-conn)
                       "the cached conn is reused, with no spurious reconnect")))
          (.catch (fn [e]
                    (is false (str "ensure-connection! must NOT reject: " (.-message e)))
                    nil))
          (.then (fn [_] (restore!) (done)))))))

;; ---------------------------------------------------------------------------
;; The cwd scan keeps the winning candidate's absolute path, so a session
;; seeded from it recovers from an ephemeral-port nREPL restart through the
;; same per-call re-read; a `:port-file nil` would strand it on the dead port.
;; ---------------------------------------------------------------------------

(def ^:private shadow-probe-fails
  (fn [_host _port] (js/Promise.resolve nil)))

(def ^:private roots-unsupported
  (fn [] (js/Promise.resolve {:status :error
                              :error  {:reason :workspace-discovery-unsupported}})))

(deftest cwd-discovery-shape-recovers-across-ephemeral-restart
  (async done
    (let [cwd-pf   (node-path/join (js/process.cwd) ".nrepl-port")
          content  (atom "7101")
          restore! (with-fs-read (fn [^js path]
                                   (if (= (str path) cwd-pf)
                                     @content
                                     (throw (js/Error. "ENOENT")))))]
      ;; Roots unsupported and the HTTP probe down leave the cwd scan.
      (-> (nrepl/discover-port* nil nil shadow-probe-fails roots-unsupported)
          (.then
            (fn [r]
              ;; Seed the session from the discovery result verbatim.
              (let [conn-p1 (nrepl/make-conn (:port r) "127.0.0.1")]
                (server/set-discovered-for-tests!
                  {:conn conn-p1 :port (:port r) :port-file (:port-file r)
                   :project-home (:project-home r)})
                ;; The nREPL restarts on a new port and rewrites the same file.
                (reset! content "7102")
                (-> (server/ensure-connection!
                      {} (fn [_] (js/Promise.reject
                                   (js/Error. "must not re-run the discovery cascade"))))
                    (.then
                      (fn [conn']
                        (is (= {:new-port 7102 :fresh-conn? true :p1-closed? true :session-port 7102}
                               {:new-port     (:port @conn')
                                :fresh-conn?  (not (identical? conn-p1 conn'))
                                :p1-closed?   (:closed? @conn-p1)
                                :session-port (:port (server/session-state-snapshot))}))))))))
          (.catch (fn [e]
                    (is false (str "must not reject: " (.-message e)))
                    nil))
          (.then (fn [_] (restore!) (done)))))))

(deftest cwd-cached-file-vanish-forces-rediscovery
  (async done
    (let [cwd-pf      (node-path/join (js/process.cwd) ".nrepl-port")
          redisc?     (atom false)
          restore!    (with-fs-read (fn [_] (throw (js/Error. "ENOENT"))))
          discover-fn (fn [_]
                        (reset! redisc? true)
                        (server/mark-discovered-for-tests!
                          (nrepl/make-conn 7103 "127.0.0.1"))
                        (js/Promise.resolve :ok))]
      (server/set-discovered-for-tests!
        {:conn (nrepl/make-conn 7101 "127.0.0.1") :port 7101 :port-file cwd-pf :project-home nil})
      (-> (server/ensure-connection! {} discover-fn)
          (.then (fn [_]
                   (is (true? @redisc?)
                       "a vanished port-file forces rediscovery, not a silent reuse")))
          (.catch (fn [e]
                    (is false (str "rediscovery should succeed here: " (.-message e)))
                    nil))
          (.then (fn [_] (restore!) (done)))))))

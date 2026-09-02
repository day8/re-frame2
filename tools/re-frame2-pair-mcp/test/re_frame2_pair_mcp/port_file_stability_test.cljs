(ns re-frame2-pair-mcp.port-file-stability-test
  "Regression for port-file discovery stability.

  Discovery surfaces the exact `:port-file` it resolved (nrepl.cljs);
  the server caches that path verbatim. A second `ensure-connection!`
  re-reads the SAME file, sees the SAME port, and stays on the cached
  connection — rather than deriving a fixed
  `<project-home>/.shadow-cljs/nrepl.port`, which for an explicit
  `target/shadow-cljs/nrepl.port` would point at a non-existent
  `target/shadow-cljs/.shadow-cljs/nrepl.port` and make the next
  `ensure-connection!` mis-read the path as missing, close the socket,
  force rediscovery, and reset the per-connection build/probe caches on
  every tool call.

  These tests exercise the server's `ensure-connection!` cached-path
  branch directly with a stubbed `fs.readFileSync`, asserting the
  cached conn is REUSED when the cached port-file still reads the same
  port — i.e. no spurious 'file vanished' reconnect."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]
            ["fs" :as fs]
            ["path" :as node-path]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(defn- with-fs-read
  "Install `stub-fn` as `fs.readFileSync` for the duration of `body`,
  restoring afterwards. Returns a 0-arity restore thunk (the async
  bodies here call it inside their final `.then` before `(done)`)."
  [stub-fn]
  (let [orig (.-readFileSync fs)]
    (set! (.-readFileSync fs) stub-fn)
    (fn restore! [] (set! (.-readFileSync fs) orig))))

(defn- reads-port-at
  "A `readFileSync` stub returning `content` for `wanted-path` (exact
  string match) and throwing ENOENT for any other path — modelling the
  cached file present at exactly the discovered path and NOWHERE else."
  [wanted-path content]
  (fn [^js path]
    (if (= (str path) wanted-path)
      content
      (throw (js/Error. "ENOENT")))))

(deftest cached-explicit-port-file-stays-on-connection-when-unchanged
  (testing "ensure-connection! reuses the cached conn when the EXACT cached port-file still reads the same port (rf2-ww877w)"
    (async done
      (let [explicit-pf "C:/repo/target/shadow-cljs/nrepl.port"
            conn        (nrepl/make-conn 7001 "127.0.0.1")
            ;; The exact file discovery resolved is present and reads 7001;
            ;; a DERIVED path
            ;; (C:/repo/target/shadow-cljs/.shadow-cljs/nrepl.port) does NOT
            ;; exist, so caching THAT would mis-fire the "vanished" branch.
            restore!    (with-fs-read (reads-port-at explicit-pf "7001"))]
        (server/set-discovered-for-tests!
          {:conn conn :port 7001 :port-file explicit-pf
           :project-home "C:/repo/target/shadow-cljs"})
        ;; A second tool call: discovered? is true, the cached port-file
        ;; reads the same port ⇒ fast path, same conn, no reconnect.
        (-> (server/ensure-connection! {} (fn [_] (js/Promise.reject (js/Error. "must not re-discover"))))
            (.then (fn [resolved-conn]
                     (is (= conn resolved-conn)
                         "the cached conn is REUSED — no spurious reconnect")
                     (is (true? (:discovered? (server/session-state-snapshot)))
                         "session stays discovered (no forced rediscovery)")
                     (is (= explicit-pf (:port-file (server/session-state-snapshot)))
                         "the cached port-file is the exact explicit path, unchanged")))
            (.catch (fn [e]
                      (is false (str "ensure-connection! must NOT reject: " (.-message e)))
                      nil))
            ;; `restore!` is the same idempotent `set!` on both arms, so it
            ;; moves to the single trailing step — still ahead of `done`, which
            ;; is what keeps the stub from leaking into the next test.
            (.then (fn [_] (restore!) (done))))))))

(deftest cached-probe-winning-candidate-stays-on-connection
  (testing "a winning HTTP-probe candidate (target/shadow-cljs/nrepl.port) is reused when unchanged (rf2-ww877w)"
    (async done
      ;; The probe winner is target/shadow-cljs/nrepl.port, NOT a
      ;; derived .shadow-cljs/nrepl.port. Cache the winning path; re-read
      ;; sees the same port ⇒ stay put.
      (let [winning-pf "/abs/proj/root/target/shadow-cljs/nrepl.port"
            conn       (nrepl/make-conn 6789 "127.0.0.1")
            restore!   (with-fs-read (reads-port-at winning-pf "6789"))]
        (server/set-discovered-for-tests!
          {:conn conn :port 6789 :port-file winning-pf
           :project-home "/abs/proj/root"})
        (-> (server/ensure-connection! {} (fn [_] (js/Promise.reject (js/Error. "must not re-discover"))))
            (.then (fn [resolved-conn]
                     (is (= conn resolved-conn)
                         "cached conn reused — the winning candidate path re-read fine")
                     (is (= winning-pf (:port-file (server/session-state-snapshot)))
                         "the cached port-file is the winning candidate, not a derived one")))
            (.catch (fn [e]
                      (is false (str "ensure-connection! must NOT reject: " (.-message e)))
                      nil))
            (.then (fn [_] (restore!) (done))))))))

;; ---------------------------------------------------------------------------
;; CWD-fallback restart recovery (rf2-q774o). The step-5 cwd scan retains the
;; winning candidate's cwd-resolved absolute identity, so a session seeded
;; from THAT discovery shape recovers from an ephemeral-port nREPL restart
;; through the same per-tool-call re-read every other file-backed branch
;; uses. Pre-fix, the cwd result carried :port-file nil, ensure-connection!
;; took the cached-conn fast path forever, and the session stayed stranded
;; on the dead port until the whole MCP server was restarted.
;; ---------------------------------------------------------------------------

(def ^:private shadow-probe-fails
  "discover-project-home stub: shadow HTTP probe unreachable → step 5."
  (fn [_host _port] (js/Promise.resolve nil)))

(def ^:private roots-unsupported
  "roots-discovery stub: client exposes no roots/list → fall through."
  (fn [] (js/Promise.resolve {:status :error
                              :error  {:reason :workspace-discovery-unsupported}})))

(deftest cwd-discovery-shape-recovers-across-ephemeral-restart
  (testing "a session seeded from the ACTUAL cwd discovery result replaces P1 with P2 when the file is rewritten (rf2-q774o)"
    (async done
      (let [cwd-pf   (node-path/join (js/process.cwd) ".nrepl-port")
            ;; The one cwd candidate present reads whatever `content` holds —
            ;; P1 during discovery, rewritten to P2 to model the nREPL
            ;; restarting on a fresh ephemeral port.
            content  (atom "7101")
            restore! (with-fs-read (fn [^js path]
                                     (if (= (str path) cwd-pf)
                                       @content
                                       (throw (js/Error. "ENOENT")))))]
        ;; Step 5 discovery: roots unsupported, HTTP probe down → cwd scan.
        (-> (nrepl/discover-port* nil nil shadow-probe-fails roots-unsupported)
            (.then
              (fn [r]
                (is (= 7101 (:port r)) "cwd discovery attached to P1")
                ;; Seed the session from the discovery result VERBATIM — the
                ;; contract under test is that this shape carries enough for
                ;; restart recovery. (Pre-fix it carried :port-file nil, and
                ;; this witness then fails on the P2 assertions below.)
                (let [conn-p1 (nrepl/make-conn (:port r) "127.0.0.1")]
                  (server/set-discovered-for-tests!
                    {:conn conn-p1 :port (:port r) :port-file (:port-file r)
                     :project-home (:project-home r)})
                  ;; The nREPL restarts on a new ephemeral port and rewrites
                  ;; the SAME file; the old port is dead.
                  (reset! content "7102")
                  (-> (server/ensure-connection!
                        {} (fn [_] (js/Promise.reject
                                     (js/Error. "must not re-run the discovery cascade"))))
                      (.then
                        (fn [conn']
                          (is (= 7102 (:port @conn'))
                              "the authoritative connection targets P2 — the session self-healed")
                          (is (not (identical? conn-p1 conn'))
                              "a FRESH conn replaces the stale one (empty build caches)")
                          (is (true? (:closed? @conn-p1))
                              "the P1 connection was closed")
                          (is (= 1 (:generation @conn-p1))
                              "…exactly once (close! bumps the generation once)")
                          (is (= 7102 (:port (server/session-state-snapshot)))
                              "the cached endpoint follows the file")))))))
            (.catch (fn [e]
                      (is false (str "must not reject: " (.-message e)))
                      nil))
            (.then (fn [_] (restore!) (done))))))))

(deftest cwd-cached-file-vanish-forces-rediscovery
  (testing "a cwd-derived cache whose file vanishes enters the existing rediscovery path (rf2-q774o)"
    (async done
      (let [cwd-pf   (node-path/join (js/process.cwd) ".nrepl-port")
            conn-p1  (nrepl/make-conn 7101 "127.0.0.1")
            redisc?  (atom false)
            ;; Every read throws — shadow/nREPL shut down entirely.
            restore! (with-fs-read (fn [_] (throw (js/Error. "ENOENT"))))
            discover-fn (fn [_]
                          (reset! redisc? true)
                          (server/mark-discovered-for-tests!
                            (nrepl/make-conn 7103 "127.0.0.1"))
                          (js/Promise.resolve :ok))]
        (server/set-discovered-for-tests!
          {:conn conn-p1 :port 7101 :port-file cwd-pf :project-home nil})
        (-> (server/ensure-connection! {} discover-fn)
            (.then (fn [_]
                     (is (true? @redisc?)
                         "a vanished cwd port-file forces rediscovery, not a silent reuse of P1")))
            (.catch (fn [e]
                      (is false (str "rediscovery should succeed here: " (.-message e)))
                      nil))
            (.then (fn [_] (restore!) (done))))))))

(deftest cached-port-file-genuine-disappearance-still-rediscovers
  (testing "when the cached (exact) port-file genuinely vanishes, ensure-connection! still rediscovers — the fix doesn't mask real shutdowns"
    (async done
      (let [explicit-pf "C:/repo/target/shadow-cljs/nrepl.port"
            conn        (nrepl/make-conn 7001 "127.0.0.1")
            redisc?     (atom false)
            ;; Every read throws — the file is truly gone (shadow stopped).
            restore!    (with-fs-read (fn [_] (throw (js/Error. "ENOENT"))))
            discover-fn (fn [_]
                          (reset! redisc? true)
                          (server/mark-discovered-for-tests!
                            (nrepl/make-conn 7002 "127.0.0.1"))
                          (js/Promise.resolve :ok))]
        (server/set-discovered-for-tests!
          {:conn conn :port 7001 :port-file explicit-pf
           :project-home "C:/repo/target/shadow-cljs"})
        (-> (server/ensure-connection! {} discover-fn)
            (.then (fn [_]
                     (is (true? @redisc?)
                         "a genuine vanished port-file STILL forces rediscovery (no false reuse)")))
            (.catch (fn [e]
                      (is false (str "rediscovery should succeed here: " (.-message e)))
                      nil))
            (.then (fn [_] (restore!) (done))))))))
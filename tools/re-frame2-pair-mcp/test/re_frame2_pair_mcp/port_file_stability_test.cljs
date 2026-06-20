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
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]
            ["fs" :as fs]))

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
                         "the cached port-file is the exact explicit path, unchanged")
                     (restore!)
                     (done)))
            (.catch (fn [e]
                      (is false (str "ensure-connection! must NOT reject: " (.-message e)))
                      (restore!)
                      (done))))))))

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
                         "the cached port-file is the winning candidate, not a derived one")
                     (restore!)
                     (done)))
            (.catch (fn [e]
                      (is false (str "ensure-connection! must NOT reject: " (.-message e)))
                      (restore!)
                      (done))))))))

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
                         "a genuine vanished port-file STILL forces rediscovery (no false reuse)")
                     (restore!)
                     (done)))
            (.catch (fn [e]
                      (is false (str "rediscovery should succeed here: " (.-message e)))
                      (restore!)
                      (done))))))))
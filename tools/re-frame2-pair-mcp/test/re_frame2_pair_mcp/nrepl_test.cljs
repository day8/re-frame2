(ns re-frame2-pair-mcp.nrepl-test
  "Unit tests for the nREPL transport: bencode framing, the socket handlers,
  `send-op!`, the port-discovery cascade and the single-flight `connect!`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [applied-science.js-interop :as j]
            ["bencode" :as bencode]
            ["fs" :as fs]
            ["net" :as net]
            ["path" :as node-path]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.shadow-discovery :as shadow-discovery]))

(deftest complete-frame-then-incomplete-tail-splits
  ;; One complete frame then the start of a second: the shape a busy socket delivers.
  (let [head             (js/Buffer.from "d3:baz" "utf8")
        [frames trailer] (nrepl/decode-all-frames
                           (js/Buffer.concat #js [(js/Buffer.from "d3:foo3:bare" "utf8") head]))]
    (is (= ["bar"] (map #(j/get % "foo") (array-seq frames))) "exactly the one complete frame decodes")
    (is (= (.-length head) (.-length trailer)) "the incomplete frame's bytes are kept as the trailer")))

;; ---------------------------------------------------------------------------
;; Port files. `fs.readFileSync` and `$SHADOW_CLJS_NREPL_PORT` are stubbed so
;; no real file is read.
;; ---------------------------------------------------------------------------

(def ^:private env-key "SHADOW_CLJS_NREPL_PORT")

(defn- set-env! [v]
  (if (nil? v)
    (js-delete (.-env js/process) env-key)
    (j/assoc-in! js/process [:env env-key] v)))

(defn- install-fs-stub!
  "Install `stub-fn` as `fs.readFileSync` and set the port env var to
  `env-val` (nil = unset). Returns a thunk that restores both."
  [env-val stub-fn]
  (let [orig-read (.-readFileSync fs)
        orig-env  (j/get-in js/process [:env env-key])]
    (set! (.-readFileSync fs) stub-fn)
    (set-env! env-val)
    (fn restore! []
      (set! (.-readFileSync fs) orig-read)
      (set-env! orig-env))))

(defn- throwing-read [_path]
  (throw (js/Error. "ENOENT")))

(defn- read-returning
  "A `readFileSync` stub returning `content` for paths matching `wanted-path`
  and throwing for every other path."
  [wanted-path content]
  (fn [^js path]
    (if (re-find (re-pattern wanted-path) (str path))
      content
      (throw (js/Error. "ENOENT")))))

(deftest read-port-file-parses-trims-and-fails-soft
  (doseq [[content expected] [["  6789  \n" 6789]
                              ["not-a-number" nil]]]
    (let [restore! (install-fs-stub! nil (read-returning "the/port" content))]
      (try
        (is (= expected (nrepl/read-port-file "the/port")) (pr-str content))
        (finally (restore!))))))

;; ---------------------------------------------------------------------------
;; `attach-handlers!`, driven through a fake socket that records its callbacks.
;; ---------------------------------------------------------------------------

(defn- fake-socket [cbs*]
  (j/lit {:on (fn [event cb] (swap! cbs* assoc event cb))}))

(defn- emit-data! [cbs* ^js chunk]
  ((get @cbs* "data") chunk))

(defn- frame-buf [m]
  (bencode/encode (clj->js m)))

(deftest data-handler-buffers-partial-frame
  (let [cbs* (atom {})
        conn (nrepl/make-conn 0 "127.0.0.1")
        got* (atom nil)
        full (frame-buf {"id" "id-2" "value" "7"})
        mid  (js/Math.floor (/ (.-length full) 2))]
    (swap! conn assoc :pending {"id-2" #(reset! got* %)})
    (nrepl/attach-handlers! conn (fake-socket cbs*))
    (emit-data! cbs* (.slice full 0 mid))
    (is (nil? @got*) "a partial frame does not dispatch")
    (emit-data! cbs* (.slice full mid))
    (is (= "7" (j/get @got* "value")) "the completing chunk dispatches the frame")
    (is (zero? (.-length (:buf @conn))) "and leaves no trailer")))

(deftest data-handler-splits-two-frames-in-one-chunk
  (let [cbs* (atom {})
        conn (nrepl/make-conn 0 "127.0.0.1")
        got* (atom {})]
    (swap! conn assoc :pending {"a" #(swap! got* assoc "a" (j/get % "value"))
                                "b" #(swap! got* assoc "b" (j/get % "value"))})
    (nrepl/attach-handlers! conn (fake-socket cbs*))
    (emit-data! cbs* (js/Buffer.concat #js [(frame-buf {"id" "a" "value" "1"})
                                            (frame-buf {"id" "b" "value" "2"})]))
    (is (= {"a" "1" "b" "2"} @got*) "each frame of one chunk reaches its own pending handler")))

(deftest data-handler-ignores-unknown-id
  ;; A late frame for a timed-out id must not throw inside the socket's data handler.
  (let [cbs* (atom {})
        conn (nrepl/make-conn 0 "127.0.0.1")]
    (nrepl/attach-handlers! conn (fake-socket cbs*))
    (emit-data! cbs* (frame-buf {"id" "ghost" "value" "x"}))
    (is (zero? (.-length (:buf @conn))) "the frame is consumed and dropped")))

(deftest error-handler-marks-conn-closed
  (let [cbs*     (atom {})
        conn     (nrepl/make-conn 0 "127.0.0.1")
        orig-err (.-error js/console)]
    (swap! conn assoc :closed? false)
    (nrepl/attach-handlers! conn (fake-socket cbs*))
    (set! (.-error js/console) (fn [& _] nil))   ; the handler logs to stderr
    (try
      ((get @cbs* "error") (js/Error. "boom"))
      (finally (set! (.-error js/console) orig-err)))
    (is (true? (:closed? @conn)) "a socket error marks the conn closed, so the next op reconnects")))

(deftest close!-resets-probe-cache-and-pending
  ;; Operator teardown clears every session cache, so a later connect never
  ;; carries a stale build into what may be a different shadow build.
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc
           :socket            #js {:end (fn [] nil)}
           :closed?           false
           :pending           {"id-1" identity}
           :probed-builds     #{:app}
           :resolved-build-id :app
           :build-alias       {:a :app})
    (nrepl/close! conn)
    (is (= {:socket nil :closed? true :pending {}
            :probed-builds #{} :resolved-build-id nil :build-alias {}}
           (select-keys @conn [:socket :closed? :pending
                               :probed-builds :resolved-build-id :build-alias])))))

;; ---------------------------------------------------------------------------
;; `send-op!`. Each test pre-seeds a live socket so `connect!` takes its fast
;; path, and reaches the op's frame accumulator through `:pending`.
;; ---------------------------------------------------------------------------

(deftest send-op!-nil-socket-rejects-structured-and-cleans-pending
  ;; The socket can drop between connect!'s resolve and the write.
  (async done
    (let [conn (nrepl/make-conn 0 "127.0.0.1")]
      (swap! conn assoc :socket #js {} :closed? false)
      (let [p (nrepl/send-op! conn {"op" "eval" "code" "(+ 1 1)"})]
        (swap! conn assoc :socket nil)   ; before the .then microtask writes
        (-> p
            (.then (fn [_]
                     (is false "send-op! must reject when the socket is nil at write time"))
                   (fn [err]
                     (is (= "nREPL socket dropped before write — retry to reconnect"
                            (.-message err)))
                     (is (= {} (:pending @conn)) "the just-registered id does not leak")))
            (.then (fn [_] (done))))))))

(deftest send-op!-assembles-frames-and-resolves-on-done
  (async done
    (let [conn (nrepl/make-conn 0 "127.0.0.1")]
      (swap! conn assoc :socket (j/lit {:write (fn [_] nil)}) :closed? false)
      (-> (nrepl/send-op! conn {"op" "eval" "code" "(+ 1 1)"})
          (.then (fn [res]
                   (is (= {:value "42" :out "hello" :err "oops" :ex "boom" :status #{"done"}} res)
                       ":out accretes across frames; :value, :err and :ex are kept")
                   (is (= {} (:pending @conn)) ":done clears the pending id")
                   (done))))
      (js/queueMicrotask
        (fn []
          (let [on-frame (-> @conn :pending vals first)]
            (doseq [frame [#js {"out" "hel"} #js {"out" "lo"} #js {"err" "oops"}
                           #js {"value" "42"} #js {"ex" "boom"} #js {"status" #js ["done"]}]]
              (on-frame frame))))))))

(deftest send-op!-timeout-rejects-and-cleans-pending
  (async done
    (let [conn (nrepl/make-conn 0 "127.0.0.1")]
      (swap! conn assoc :socket (j/lit {:write (fn [_] nil)}) :closed? false)
      (-> (nrepl/send-op! conn {"op" "eval" "code" "(loop [])"} {:timeout-ms 1})
          (.then (fn [_]
                   (is false "an op with no :done frame must time out, not resolve"))
                 (fn [err]
                   (is (re-find #"timed out after 1ms" (.-message err))
                       "the reject names the op's deadline")
                   (is (= {} (:pending @conn)) "the timed-out id does not leak")))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; `discover-port*` — explicit file > env > roots/list > shadow HTTP probe >
;; cwd scan, with the roots and HTTP probes injected as stubs.
;; ---------------------------------------------------------------------------

(defn- shadow-returns [home-path]
  (fn [_host _port] (js/Promise.resolve home-path)))

(def ^:private shadow-fails
  (fn [_host _port] (js/Promise.resolve nil)))

(def ^:private roots-unsupported
  (fn [] (js/Promise.resolve {:status :error
                              :error  {:reason :workspace-discovery-unsupported}})))

(defn- roots-one [project-home port]
  (fn [] (js/Promise.resolve {:status    :one
                              :candidate {:project-home project-home
                                          :port-file    (str project-home "/.shadow-cljs/nrepl.port")
                                          :port         port}})))

(defn- roots-many [candidates]
  (fn [] (js/Promise.resolve {:status :many :candidates candidates})))

(deftest discover-port-explicit-port-file-short-circuits-shadow-probe
  (async done
    (let [probed?  (atom false)
          probe-fn (fn [_h _p] (reset! probed? true) (js/Promise.resolve nil))
          roots-fn (fn [] (reset! probed? true) (roots-unsupported))
          restore! (install-fs-stub! nil (read-returning "explicit/nrepl\\.port" "9001"))]
      (-> (nrepl/discover-port* "explicit/nrepl.port" nil probe-fn roots-fn)
          (.then (fn [r]
                   (is (= {:port 9001 :project-home "explicit" :port-file "explicit/nrepl.port"} r)
                       "the explicit file wins, and its exact path is surfaced for the server to cache")
                   (is (false? @probed?) "neither the roots nor the HTTP probe fires")))
          (.finally (fn [] (restore!) (done)))))))

(deftest discover-port-explicit-port-file-unreadable-falls-through-to-roots
  (async done
    (let [probed?  (atom false)
          probe-fn (fn [_h _p] (reset! probed? true) (js/Promise.resolve nil))
          restore! (install-fs-stub! nil throwing-read)]
      (-> (nrepl/discover-port* "stale/nrepl.port" nil probe-fn (roots-one "/abs/proj" 8765))
          (.then (fn [r]
                   (is (= {:port 8765 :project-home "/abs/proj"
                           :port-file "/abs/proj/.shadow-cljs/nrepl.port"}
                          r)
                       "a stale explicit file falls through; the single roots candidate surfaces verbatim")
                   (is (false? @probed?) "the HTTP probe does not fire once roots resolves")))
          (.finally (fn [] (restore!) (done)))))))

(deftest discover-port-env-var-short-circuits-shadow-probe
  (async done
    (let [probed?  (atom false)
          probe-fn (fn [_h _p] (reset! probed? true) (js/Promise.resolve nil))
          roots-fn (fn [] (reset! probed? true) (roots-unsupported))
          restore! (install-fs-stub! "7777" throwing-read)]
      (-> (nrepl/discover-port* nil nil probe-fn roots-fn)
          (.then (fn [r]
                   (is (= {:port 7777} r) "the env port wins, with no file identity invented for it")
                   (is (false? @probed?) "neither the roots nor the HTTP probe fires")))
          (.finally (fn [] (restore!) (done)))))))

(deftest discover-port-roots-many-candidates-surfaces-ambiguous
  (async done
    (let [cs       [{:project-home "/abs/projA" :port-file "..." :port 1111}
                    {:project-home "/abs/projB" :port-file "..." :port 2222}]
          restore! (install-fs-stub! nil throwing-read)]
      (-> (nrepl/discover-port* nil nil shadow-fails (roots-many cs))
          (.then (fn [r]
                   (is (= {:port nil :ambiguous cs} r)
                       "no port is chosen; the candidates go to the caller's elicitation")))
          (.finally (fn [] (restore!) (done)))))))

(deftest discover-port-shadow-probe-prefers-target-then-dot-shadow
  (async done
    (let [restore! (install-fs-stub!
                     nil (fn [^js path]
                           (let [p (str path)]
                             (cond
                               (re-find #"target[\\/]shadow-cljs[\\/]nrepl\.port" p) "5550"
                               (re-find #"\.shadow-cljs[\\/]nrepl\.port" p)          "5551"
                               (re-find #"\.nrepl-port" p)                           "5552"
                               :else (throw (js/Error. "ENOENT"))))))]
      (-> (nrepl/discover-port* nil nil (shadow-returns "/abs/proj/root") roots-unsupported)
          (.then (fn [r]
                   (is (= {:port         5550
                           :project-home "/abs/proj/root"
                           :port-file    (node-path/join "/abs/proj/root" "target/shadow-cljs/nrepl.port")}
                          r)
                       "the first candidate under the shadow-supplied root wins, and its file is surfaced")))
          (.finally (fn [] (restore!) (done)))))))

(deftest discover-port-cwd-scan-surfaces-winning-port-file
  ;; The server caches :port-file to notice an nREPL restart, on this branch too.
  (async done
    (let [restore! (install-fs-stub!
                     nil (fn [^js path]
                           (if (re-find #"\.nrepl-port" (str path))
                             "5599"
                             (throw (js/Error. "ENOENT")))))]
      (-> (nrepl/discover-port* nil nil shadow-fails roots-unsupported)
          (.then (fn [r]
                   (is (= {:port 5599 :port-file (node-path/join (js/process.cwd) ".nrepl-port")} r)
                       "only the last candidate reads; its cwd-absolute path is surfaced, with no invented project-home")))
          (.finally (fn [] (restore!) (done)))))))

(deftest discover-port-shadow-down-and-no-files-yields-nil
  (async done
    (let [restore! (install-fs-stub! nil throwing-read)]
      ;; A nil roots fn is the boot-time shape, before an MCP client exists.
      (-> (nrepl/discover-port* nil nil shadow-fails nil)
          (.then (fn [r]
                   (is (= {:port nil} r) "every step misses, so boot degrades")))
          (.finally (fn [] (restore!) (done)))))))

(deftest discover-port-http-port-arg-threads-through
  (async done
    (let [seen     (atom [])
          probe-fn (fn [_host port] (swap! seen conj port) (js/Promise.resolve nil))
          restore! (install-fs-stub! nil throwing-read)]
      (-> (nrepl/discover-port* nil 7777 probe-fn roots-unsupported)
          (.then (fn [_] (nrepl/discover-port* nil nil probe-fn roots-unsupported)))
          (.then (fn [_]
                   (is (= [7777 shadow-discovery/default-http-port] @seen)
                       "--http-port reaches the probe; absent, the probe uses the default")))
          (.finally (fn [] (restore!) (done)))))))

;; ---------------------------------------------------------------------------
;; `connect!` — single-flight, generation-owned sockets. `net.createConnection`
;; is stubbed to build a distinct fake socket per call; each records its own
;; event callbacks and its write/end/destroy calls.
;; ---------------------------------------------------------------------------

(defn- make-concurrency-fake [cbs flags]
  (j/lit {:on      (fn [event cb] (swap! cbs assoc event cb) nil)
          :once    (fn [event cb] (swap! cbs assoc event cb) nil)
          :write   (fn [_] (swap! flags update :writes (fnil inc 0)) nil)
          :end     (fn [] (swap! flags assoc :ended? true) nil)
          :destroy (fn [] (swap! flags assoc :destroyed? true) nil)}))

(defn- with-multi-create-connection!
  "Stub `net.createConnection`, appending `{:cbs :flags :socket}` to
  `sockets*` per call. Returns a restore thunk."
  [sockets*]
  (let [orig (.-createConnection net)]
    (set! (.-createConnection net)
          (fn [_opts]
            (let [cbs   (atom {})
                  flags (atom {})
                  sock  (make-concurrency-fake cbs flags)]
              (swap! sockets* conj {:cbs cbs :flags flags :socket sock})
              sock)))
    (fn restore! [] (set! (.-createConnection net) orig))))

(defn- fire-cb! [rec event & args]
  (apply (get @(:cbs rec) event) args))

(deftest connect!-reopen-preserves-resolved-build-id-after-hiccup
  ;; A transient close reopens the SAME port, so the sticky build stays valid;
  ;; losing it would send a later no-:build call to :app.
  (async done
    (let [sockets* (atom [])
          restore! (with-multi-create-connection! sockets*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")
          caches   {:resolved-build-id :examples/step-deck
                    :build-alias       {:step-deck :examples/step-deck}
                    :probed-builds     #{:examples/step-deck}}
          open!    (fn []
                     (let [p (nrepl/connect! conn)]
                       (fire-cb! (peek @sockets*) "connect")
                       p))]
      (-> (open!)
          (.then (fn [_]
                   (swap! conn merge caches)
                   (fire-cb! (peek @sockets*) "close" nil)
                   (open!)))
          (.then (fn [_]
                   (is (= (assoc caches :closed? false)
                          (select-keys @conn [:closed? :resolved-build-id :build-alias :probed-builds])))))
          (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
          (.then (fn [_] (restore!) (done)))))))

(deftest connect!-single-flight-one-socket-for-concurrent-callers
  (async done
    (let [sockets* (atom [])
          restore! (with-multi-create-connection! sockets*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")
          p1       (nrepl/connect! conn)
          p2       (nrepl/connect! conn)]
      (is (= 1 (count @sockets*)) "two concurrent connect! callers open ONE socket")
      (is (identical? p1 p2) "and share ONE in-flight Promise")
      (fire-cb! (first @sockets*) "connect")
      (-> p1
          (.then (fn [_] (is (false? (:closed? @conn)) "the shared connect publishes a live conn")))
          (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
          (.then (fn [_] (restore!) (done)))))))

(deftest concurrent-send-ops-multiplex-over-one-socket
  (async done
    (let [sockets* (atom [])
          restore! (with-multi-create-connection! sockets*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")
          p1       (nrepl/send-op! conn {"op" "eval" "code" "1"})
          p2       (nrepl/send-op! conn {"op" "eval" "code" "2"})]
      (fire-cb! (first @sockets*) "connect")
      (-> (js/Promise.resolve nil)
          (.then (fn [_] nil))   ; flush send-op!'s connect continuations
          (.then (fn [_]
                   (is (= 2 (count (:pending @conn))) "two distinct pending ids")
                   (is (= 2 (:writes @(:flags (first @sockets*)))) "both ops write to the one socket")
                   (doseq [on-frame (vals (:pending @conn))]
                     (on-frame #js {"status" #js ["done"]}))
                   (js/Promise.all #js [p1 p2])))
          (.then (fn [_] (is (= {} (:pending @conn)) "both ops resolve and drain")))
          (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
          (.then (fn [_] (restore!) (done)))))))

(deftest superseded-socket-callbacks-cannot-mutate-current-generation
  ;; Bytes and events from a superseded socket must never reach the live
  ;; connection: a stale close would mark it closed, and a stale tail could
  ;; complete a frame against the fresh stream.
  (async done
    (let [sockets* (atom [])
          restore! (with-multi-create-connection! sockets*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")
          gen1     #(first @sockets*)
          full     (frame-buf {"id" "p1" "value" "7"})
          mid      (js/Math.floor (/ (.-length full) 2))
          p1       (nrepl/connect! conn)]
      (fire-cb! (gen1) "connect")
      (-> p1
          (.then (fn [_]
                   (fire-cb! (gen1) "data" (.slice full 0 mid))
                   (fire-cb! (gen1) "close" nil)
                   (is (true? (:closed? @conn)) "gen1's own close flips :closed?")
                   (let [p2 (nrepl/connect! conn)]
                     (fire-cb! (second @sockets*) "connect")
                     p2)))
          (.then (fn [_]
                   (fire-cb! (gen1) "close" nil)
                   (fire-cb! (gen1) "error" (js/Error. "late gen1 error"))
                   (fire-cb! (gen1) "data" (.slice full mid))
                   (is (= [false 0] [(:closed? @conn) (.-length (:buf @conn))])
                       "late gen1 close, error and data are inert, and gen2 starts from an empty buffer")))
          (.catch (fn [e] (is false (str "unexpected reject: " (.-message e))) nil))
          (.then (fn [_] (restore!) (done)))))))

(deftest connect!-rejection-clears-in-flight-slot-and-next-call-retries
  (async done
    (let [sockets* (atom [])
          restore! (with-multi-create-connection! sockets*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")
          p1       (nrepl/connect! conn)]
      (fire-cb! (first @sockets*) "error" (js/Error. "ECONNREFUSED"))
      (-> p1
          (.then (fn [_] (is false "connect must reject"))
                 (fn [e]
                   (is (= "ECONNREFUSED" (.-message e)) "the waiter receives the socket error")
                   (let [p2 (nrepl/connect! conn)]
                     (is (= 2 (count @sockets*)) "the next call retries on a fresh socket")
                     (fire-cb! (second @sockets*) "connect")
                     (.then p2 (fn [_] (is (false? (:closed? @conn)) "the retry connects"))))))
          (.catch (fn [e] (is false (str "retry rejected: " (.-message e))) nil))
          (.then (fn [_] (restore!) (done)))))))

(deftest close!-during-in-flight-connect-settles-waiter-and-orphans-candidate
  (async done
    (let [sockets* (atom [])
          restore! (with-multi-create-connection! sockets*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")
          p1       (nrepl/connect! conn)]
      (nrepl/close! conn)
      (is (nil? (:connecting @conn)) "close! clears the in-flight slot, so the next call reconnects")
      (is (true? (:destroyed? @(:flags (first @sockets*)))) "close! destroys the in-flight candidate")
      (-> p1
          (.then (fn [_] (is false "the waiter must reject after close!"))
                 (fn [_]
                   (fire-cb! (first @sockets*) "connect")
                   (is (= {:socket nil :closed? true} (select-keys @conn [:socket :closed?]))
                       "a late candidate connect cannot undo the close")))
          (.then (fn [_] (restore!) (done)))))))

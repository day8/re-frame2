(ns re-frame2-pair-mcp.nrepl-test
  "Unit tests for the bencode framing helpers in nrepl.cljs.

  The hard bug we fixed during the pilot — bencode@2 storing the
  post-decode cursor on `bencode.decode.position` rather than the
  module-level export — is the kind of regression that's easy to
  reintroduce, so the multi-frame walker gets a thorough test.

  Tests pin `decode-all-frames` directly from
  `re-frame2-pair-mcp.nrepl` — the source ns is the contract."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [applied-science.js-interop :as j]
            ["bencode" :as bencode]
            ["fs" :as fs]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.shadow-discovery :as shadow-discovery]))

(defn- decode-all
  "Wrap `nrepl/decode-all-frames` returning `[clj-vec rest-buf]`.
  Source returns `[js-array rest-buf]` for hot-path perf; tests want a
  CLJS vector to walk."
  [^js buf]
  (let [[js-frames rest] (nrepl/decode-all-frames buf)]
    [(vec (array-seq js-frames)) rest]))

(deftest single-frame-decodes
  (let [buf      (js/Buffer.from "d3:foo3:bare" "utf8")
        [fs rst] (decode-all buf)]
    (is (= 1 (count fs)))
    (is (= "bar" (j/get (first fs) "foo")))
    (is (zero? (.-length rst)))))

(deftest two-concatenated-frames-decode
  ;; This is the case the bash-shim chain never had to handle (each
  ;; call opened a fresh socket), and the one that bit us in the pilot.
  (let [buf      (js/Buffer.from "d3:foo3:bared3:baz3:quxe" "utf8")
        [fs rst] (decode-all buf)]
    (is (= 2 (count fs)))
    (is (= "bar" (j/get (nth fs 0) "foo")))
    (is (= "qux" (j/get (nth fs 1) "baz")))
    (is (zero? (.-length rst)))))

(deftest nrepl-status-response-decodes
  ;; A representative two-frame nREPL response: value then status.
  (let [buf      (js/Buffer.from
                   "d2:id1:15:value1:3ed2:id1:16:statusl4:doneee"
                   "utf8")
        [fs rst] (decode-all buf)]
    (is (= 2 (count fs)))
    (is (= "3" (j/get (nth fs 0) "value")))
    (let [status (j/get (nth fs 1) "status")]
      (is (some? status))
      (is (= "done" (aget status 0))))
    (is (zero? (.-length rst)))))

(deftest three-frames-decode
  (let [buf      (js/Buffer.from
                   "d1:ai1ee" "utf8")
        twice    (js/Buffer.concat #js [buf buf buf])
        [fs rst] (decode-all twice)]
    (is (= 3 (count fs)))
    (is (zero? (.-length rst)))))

(deftest incomplete-frame-retained-as-trailer
  ;; rf2-ynjts.19 — the partial-frame branch directly on the public
  ;; seam. A truncated bencode frame can't decode; `decode-all-frames`
  ;; MUST return zero frames and hand the partial bytes back as the
  ;; trailer so the next TCP chunk can complete them. This is the
  ;; contract `attach-handlers!` relies on for its partial-frame
  ;; buffering — pinned here directly on the walker.
  (let [full     (js/Buffer.from "d3:foo3:bare" "utf8")
        partial  (.slice full 0 (dec (.-length full)))   ; drop the closing 'e'
        [fs rst] (decode-all partial)]
    (is (zero? (count fs)) "a truncated frame yields no complete frames")
    (is (= (.-length partial) (.-length rst))
        "the partial bytes are retained verbatim as the trailer")))

(deftest complete-frame-then-incomplete-tail-splits
  ;; One complete frame followed by the start of a second. The first
  ;; dispatches; the incomplete tail is retained for the next chunk —
  ;; the exact multi-frame-chunk-with-partial-tail shape a busy socket
  ;; produces.
  (let [whole    (js/Buffer.from "d3:foo3:bare" "utf8")
        head     (js/Buffer.from "d3:baz" "utf8")          ; start of a 2nd frame
        chunk    (js/Buffer.concat #js [whole head])
        [fs rst] (decode-all chunk)]
    (is (= 1 (count fs)) "exactly the one complete frame is returned")
    (is (= "bar" (j/get (first fs) "foo")))
    (is (= (.-length head) (.-length rst))
        "the incomplete second frame's bytes are held as the trailer")))

(deftest empty-buffer-yields-no-frames
  ;; The loop's base case — a zero-length buffer returns no frames and
  ;; an empty trailer (never loops).
  (let [[fs rst] (decode-all (js/Buffer.alloc 0))]
    (is (zero? (count fs)))
    (is (zero? (.-length rst)))))

;; ===========================================================================
;; Port discovery — `read-port-from-fs` (rf2-wnrpi, finding G2).
;;
;; The fn has a four-way precedence: the `SHADOW_CLJS_NREPL_PORT` env var
;; wins; failing that, three port-file candidates are tried in order; a
;; non-numeric value at any source is rejected via the `isNaN` guard; an
;; all-miss returns nil. None of this was pinned — only `decode-all-frames`
;; was. We stub `fs.readFileSync` + `process.env.SHADOW_CLJS_NREPL_PORT`
;; to exercise every branch without touching the real filesystem.
;; ===========================================================================

(def ^:private env-key "SHADOW_CLJS_NREPL_PORT")

(defn- set-env! [v]
  (if (nil? v)
    (js-delete (.-env js/process) env-key)
    (j/assoc-in! js/process [:env env-key] v)))

(defn- with-fs-stub!
  "Run `body` with `fs.readFileSync` replaced by `stub-fn` (path → string,
  or throw to simulate ENOENT) and `process.env.SHADOW_CLJS_NREPL_PORT`
  set to `env-val` (nil = unset). Restores both afterwards. Returns the
  value of `body`.

  Sync-only: the `finally` block restores the stub immediately after
  `body` returns. The new async cascade tests use [[with-async-fs-stub!]]
  which keeps the stub alive across Promise microtasks."
  [env-val stub-fn body]
  (let [orig-read (.-readFileSync fs)
        orig-env  (j/get-in js/process [:env env-key])]
    (set! (.-readFileSync fs) stub-fn)
    (set-env! env-val)
    (try
      (body)
      (finally
        (set! (.-readFileSync fs) orig-read)
        (set-env! orig-env)))))

(defn- install-fs-stub!
  "Install `stub-fn` as `fs.readFileSync` and override
  `process.env.SHADOW_CLJS_NREPL_PORT` to `env-val` (nil = unset).
  Returns a 0-arity restoration thunk the caller invokes once the async
  body has settled. Pairs with the test's own `(done)` so the stub
  lifetime spans every microtask the promise chain spawns — which the
  sync [[with-fs-stub!]] can't guarantee for async bodies (the
  `finally` clause fires before the `.then` callbacks).

  Always pair `install-fs-stub!` with its restoration call inside the
  final `.then` (BEFORE `(done)`) so the next test in the queue starts
  with a pristine fs.readFileSync (rf2-umoz2). The local convention
  here: bind the thunk in a `let`, do assertions in `.then`, call the
  thunk, then call done."
  [env-val stub-fn]
  (let [orig-read (.-readFileSync fs)
        orig-env  (j/get-in js/process [:env env-key])]
    (set! (.-readFileSync fs) stub-fn)
    (set-env! env-val)
    (fn restore! []
      (set! (.-readFileSync fs) orig-read)
      (set-env! orig-env))))

(defn- throwing-read
  "A `readFileSync` stub that always throws — simulates every candidate
  file being absent."
  [_path]
  (throw (js/Error. "ENOENT")))

(defn- read-returning
  "Build a `readFileSync` stub that returns `content` for `wanted-path`
  (a substring match) and throws for every other path."
  [wanted-path content]
  (fn [^js path]
    (if (re-find (re-pattern wanted-path) (str path))
      content
      (throw (js/Error. "ENOENT")))))

(deftest port-discovery-env-var-wins
  (testing "SHADOW_CLJS_NREPL_PORT takes precedence over any port file"
    ;; A port file would resolve to 1234, but the env (7777) wins — the
    ;; file stub must never be consulted on this path.
    (with-fs-stub! "7777"
      (read-returning "nrepl.port" "1234")
      (fn []
        (is (= 7777 (nrepl/read-port-from-fs)))))))

(deftest port-discovery-env-numeric-only
  (testing "a non-numeric env value is rejected by the isNaN guard, fall through to files"
    (with-fs-stub! "not-a-number"
      (read-returning "target/shadow-cljs/nrepl.port" "5555")
      (fn []
        (is (= 5555 (nrepl/read-port-from-fs))
            "non-numeric env must NOT short-circuit; the file fallback fires")))))

(deftest port-discovery-first-file-candidate
  (testing "no env → first candidate (target/shadow-cljs/nrepl.port) is read"
    (with-fs-stub! nil
      (read-returning "target/shadow-cljs/nrepl.port" "  6001  \n")
      (fn []
        (is (= 6001 (nrepl/read-port-from-fs))
            "leading/trailing whitespace is trimmed before parse")))))

(deftest port-discovery-fallback-ordering
  (testing "first candidate absent → second (.shadow-cljs/nrepl.port) wins over third"
    (with-fs-stub! nil
      ;; Only the .shadow-cljs candidate (and the .nrepl-port one) exist;
      ;; the .shadow-cljs one is earlier in the list so it must win.
      (fn [^js path]
        (let [p (str path)]
          (cond
            (re-find #"\.shadow-cljs[\\\\/]nrepl\.port" p) "6002"
            (re-find #"\.nrepl-port" p)                  "6003"
            :else (throw (js/Error. "ENOENT")))))
      (fn []
        (is (= 6002 (nrepl/read-port-from-fs))
            "earlier candidate in the list wins the ordering contract")))))

(deftest port-discovery-isnan-file-rejected
  (testing "a port file with non-numeric content is rejected, not returned as NaN"
    (with-fs-stub! nil
      (read-returning "nrepl.port" "garbage")
      (fn []
        (is (nil? (nrepl/read-port-from-fs))
            "isNaN guard must reject; never surface a NaN port")))))

(deftest port-discovery-all-miss-is-nil
  (testing "no env + every candidate absent → nil"
    (with-fs-stub! nil throwing-read
      (fn []
        (is (nil? (nrepl/read-port-from-fs)))))))

;; ---------------------------------------------------------------------------
;; --port-file explicit override (rf2-3dbwh). The 1-arity `read-port-from-fs`
;; takes an explicit, cwd-independent path that wins over BOTH the env var and
;; the relative file-scan candidates. nil/blank ⇒ falls through to the normal
;; precedence chain.
;; ---------------------------------------------------------------------------

(deftest port-discovery-explicit-port-file-wins-over-env
  (testing "an explicit --port-file path beats SHADOW_CLJS_NREPL_PORT"
    ;; env says 7777, the explicit file says 9001 → explicit wins. The
    ;; env must NOT short-circuit ahead of the explicit path.
    (with-fs-stub! "7777"
      (read-returning "explicit/nrepl\\.port" "9001")
      (fn []
        (is (= 9001 (nrepl/read-port-from-fs "explicit/nrepl.port"))
            "explicit --port-file is highest precedence")))))

(deftest port-discovery-explicit-port-file-wins-over-file-scan
  (testing "an explicit --port-file path beats the cwd-relative candidates"
    (with-fs-stub! nil
      (fn [^js path]
        (let [p (str path)]
          (cond
            (re-find #"explicit[\\/]nrepl\.port" p) "9002"
            (re-find #"target/shadow-cljs/nrepl\.port" p) "6001"
            :else (throw (js/Error. "ENOENT")))))
      (fn []
        (is (= 9002 (nrepl/read-port-from-fs "explicit/nrepl.port"))
            "explicit path wins over the relative scan")))))

(deftest port-discovery-explicit-port-file-trimmed-and-parsed
  (testing "explicit-path content is trimmed + parsed like the candidates"
    (with-fs-stub! nil
      (read-returning "explicit/nrepl\\.port" "  9003  \n")
      (fn []
        (is (= 9003 (nrepl/read-port-from-fs "explicit/nrepl.port")))))))

(deftest port-discovery-explicit-port-file-missing-falls-through
  (testing "an absent explicit path falls through to env then file scan"
    ;; The explicit path throws ENOENT; env (7777) must then resolve.
    (with-fs-stub! "7777" throwing-read
      (fn []
        (is (= 7777 (nrepl/read-port-from-fs "nope/missing.port"))
            "missing --port-file ⇒ fall through to env")))))

(deftest port-discovery-explicit-port-file-nil-is-normal-chain
  (testing "nil explicit path behaves exactly like the 0-arity chain"
    (with-fs-stub! nil
      (read-returning "target/shadow-cljs/nrepl.port" "6001")
      (fn []
        (is (= 6001 (nrepl/read-port-from-fs nil))
            "nil explicit ⇒ normal env→file precedence")
        (is (= 6001 (nrepl/read-port-from-fs))
            "0-arity stays equivalent")))))

;; ===========================================================================
;; Transport data-handler — `attach-handlers!` (rf2-wnrpi, finding G3).
;;
;; The persistent-socket `data` handler folds each chunk into the conn's
;; `:buf` and splits off complete frames in a SINGLE swap! (the framing-
;; race fix), then dispatches every complete frame to its pending-id
;; handler. That logic carried heavy rationale comments but no automated
;; regression in the default gate (only the opt-in live-nrepl.js). It is
;; pure buffer logic over a fed chunk — unit-testable with a fake socket
;; that records its event callbacks rather than a real TCP connection.
;; ===========================================================================

(defn- fake-socket
  "A minimal stand-in for a `net.Socket`: `on` records each event's
  callback into `cbs*` keyed by event name. `emit-data!`/`emit!` below
  invoke a recorded callback the way Node's EventEmitter would."
  [cbs*]
  (j/lit {:on (fn [event cb] (swap! cbs* assoc event cb))}))

(defn- emit-data! [cbs* ^js chunk]
  ((get @cbs* "data") chunk))

(defn- frame-buf
  "bencode-encode a CLJS map into a Buffer the data-handler can fold."
  [m]
  (bencode/encode (clj->js m)))

(deftest data-handler-dispatches-complete-frame-to-pending
  (testing "a single complete frame resolves its pending-id handler"
    (let [cbs*    (atom {})
          conn    (nrepl/make-conn 0 "127.0.0.1")
          got*    (atom nil)]
      (swap! conn assoc :buf (js/Buffer.alloc 0) :pending {"id-1" #(reset! got* %)})
      (nrepl/attach-handlers! conn (fake-socket cbs*))
      (emit-data! cbs* (frame-buf {"id" "id-1" "value" "42"}))
      (is (some? @got*) "the pending handler fired")
      (is (= "42" (j/get @got* "value")))
      (is (zero? (.-length (:buf @conn))) "complete frame leaves no trailer"))))

(deftest data-handler-buffers-partial-frame
  (testing "a partial frame is held in :buf and dispatched once completed"
    (let [cbs*  (atom {})
          conn  (nrepl/make-conn 0 "127.0.0.1")
          got*  (atom nil)
          full  (frame-buf {"id" "id-2" "value" "7"})
          mid   (js/Math.floor (/ (.-length full) 2))
          head  (.slice full 0 mid)
          tail  (.slice full mid)]
      (swap! conn assoc :buf (js/Buffer.alloc 0) :pending {"id-2" #(reset! got* %)})
      (nrepl/attach-handlers! conn (fake-socket cbs*))
      ;; First chunk: incomplete — must NOT dispatch, must retain bytes.
      (emit-data! cbs* head)
      (is (nil? @got*) "partial frame must not dispatch")
      (is (pos? (.-length (:buf @conn))) "partial bytes retained in :buf")
      ;; Second chunk completes the frame.
      (emit-data! cbs* tail)
      (is (some? @got*) "completed frame dispatches")
      (is (= "7" (j/get @got* "value")))
      (is (zero? (.-length (:buf @conn)))))))

(deftest data-handler-splits-two-frames-in-one-chunk
  (testing "two concatenated frames in one chunk each reach their pending handler"
    (let [cbs*  (atom {})
          conn  (nrepl/make-conn 0 "127.0.0.1")
          a*    (atom nil)
          b*    (atom nil)
          chunk (js/Buffer.concat #js [(frame-buf {"id" "a" "value" "1"})
                                       (frame-buf {"id" "b" "value" "2"})])]
      (swap! conn assoc :buf (js/Buffer.alloc 0)
             :pending {"a" #(reset! a* %) "b" #(reset! b* %)})
      (nrepl/attach-handlers! conn (fake-socket cbs*))
      (emit-data! cbs* chunk)
      (is (= "1" (j/get @a* "value")) "first frame dispatched to id a")
      (is (= "2" (j/get @b* "value")) "second frame dispatched to id b")
      (is (zero? (.-length (:buf @conn)))))))

(deftest data-handler-ignores-unknown-id
  (testing "a frame for an id with no pending handler is dropped, not thrown"
    (let [cbs*  (atom {})
          conn  (nrepl/make-conn 0 "127.0.0.1")]
      (swap! conn assoc :buf (js/Buffer.alloc 0) :pending {})
      (nrepl/attach-handlers! conn (fake-socket cbs*))
      ;; Must not throw even though no pending entry matches.
      (emit-data! cbs* (frame-buf {"id" "ghost" "value" "x"}))
      (is (zero? (.-length (:buf @conn))) "frame consumed, no trailer left"))))

(deftest close-handler-marks-conn-closed
  (testing "the close event flips :closed?"
    (let [cbs* (atom {})
          conn (nrepl/make-conn 0 "127.0.0.1")]
      (swap! conn assoc :closed? false)
      (nrepl/attach-handlers! conn (fake-socket cbs*))
      ((get @cbs* "close") nil)
      (is (true? (:closed? @conn))))))

(deftest error-handler-marks-conn-closed
  (testing "a socket error flips :closed? (so the next call reconnects)"
    (let [cbs*      (atom {})
          conn      (nrepl/make-conn 0 "127.0.0.1")
          orig-err  (.-error js/console)]
      (swap! conn assoc :closed? false)
      (nrepl/attach-handlers! conn (fake-socket cbs*))
      ;; The error handler logs to stderr via `log!`; silence it so the
      ;; otherwise-quiet test run stays clean (the log is the SUT's
      ;; behaviour, not a test failure).
      (set! (.-error js/console) (fn [& _] nil))
      (try
        ((get @cbs* "error") (js/Error. "boom"))
        (finally
          (set! (.-error js/console) orig-err)))
      (is (true? (:closed? @conn))))))

;; ===========================================================================
;; `close!` — reconnect / probe-cache reset (rf2-wnrpi, finding G3).
;; ===========================================================================

(deftest close!-resets-probe-cache-and-pending
  (testing "close! drops :probed-builds, :pending, :socket and marks closed"
    (let [conn (nrepl/make-conn 0 "127.0.0.1")]
      (swap! conn assoc
             :socket #js {:end (fn [] nil)}
             :closed? false
             :pending {"id-1" identity}
             :probed-builds #{:app})
      (nrepl/close! conn)
      (is (true? (:closed? @conn)))
      (is (nil? (:socket @conn)))
      (is (= {} (:pending @conn)) "pending cleared so no stale resolvers")
      (is (= #{} (:probed-builds @conn))
          "probe cache cleared — a fresh connect must re-probe the preload"))))

;; ===========================================================================
;; `send-op!` connect→write race nil-guard (rf2-av5kl).
;;
;; `connect!`'s fast path resolves immediately when a live socket is present,
;; but the socket close/error handlers can fire in the window between that
;; resolve and the actual `.write`. If `:socket` got nilled by `close!` in
;; that window, a bare `(.write nil ...)` would throw an opaque native
;; "Cannot read .write of null" AND strand the just-registered id in
;; `:pending`. We simulate the race by nilling `:socket` synchronously after
;; the send-op! call but before the `.then` microtask runs.
;; ===========================================================================

(deftest send-op!-nil-socket-rejects-structured-and-cleans-pending
  (testing "socket dropped between connect-resolve and write → structured reject, no pending leak"
    (async done
      (let [writes (atom 0)
            ;; A fake live socket so connect!'s fast path resolves immediately.
            sock   (j/lit {:write (fn [_] (swap! writes inc) nil)})
            conn   (nrepl/make-conn 0 "127.0.0.1")]
        ;; Pre-seed a healthy connection so connect! returns Promise.resolve.
        (swap! conn assoc :socket sock :closed? false)
        (let [p (nrepl/send-op! conn {"op" "eval" "code" "(+ 1 1)"})]
          ;; Synchronously — before the .then microtask writes — drop the
          ;; socket exactly as a racing close! would.
          (swap! conn assoc :socket nil)
          (-> p
              (.then (fn [_]
                       (is false "send-op! must REJECT when the socket is nil at write time")
                       (done)))
              (.catch (fn [err]
                        (is (= "nREPL socket dropped before write — retry to reconnect"
                               (.-message err))
                            "structured retry-to-reconnect message, not the native NPE")
                        (is (zero? @writes) "no write attempted against a nil socket")
                        (is (= {} (:pending @conn))
                            "the just-registered id is dissoc'd — no pending leak")
                        (done)))))))))

(deftest send-op!-live-socket-writes-normally
  (testing "with a live socket present at write time, send-op! writes the encoded op"
    (async done
      (let [writes (atom [])
            sock   (j/lit {:write (fn [buf] (swap! writes conj buf) nil)})
            conn   (nrepl/make-conn 0 "127.0.0.1")]
        (swap! conn assoc :socket sock :closed? false)
        ;; Don't drop the socket — the write should happen, the op stays
        ;; pending (no :done frame is ever fed), and we just assert the write
        ;; landed then resolve the test.
        (nrepl/send-op! conn {"op" "eval" "code" "(+ 1 1)"})
        ;; Let the connect! fast-path microtask run before asserting.
        (js/queueMicrotask
          (fn []
            (is (= 1 (count @writes)) "exactly one write against the live socket")
            (is (= 1 (count (:pending @conn)))
                "the op is registered as pending awaiting its :done frame")
            (done)))))))

;; ===========================================================================
;; `discover-port*` async cascade (rf2-umoz2 + rf2-3grub).
;;
;; The five-step cascade — explicit > env > MCP roots/list > shadow HTTP probe
;; > cwd scan — promotes the cwd-bound file scan from highest-priority-after-
;; env to a last-resort fallback. Steps 3 and 4 are async I/O; the others are
;; sync.
;;
;; The cascade now returns a discovery-result map
;; `{:port :project-home :ambiguous :workspace-roots ...}` so callers can
;; drive elicitation when roots/list returns 2+ candidates. Tests pass
;; stubs for the probe and roots fns to exercise each cascade branch
;; without sockets or network. See `discover-port*` for the injection
;; rationale.
;; ===========================================================================

(defn- shadow-returns
  "Stub builder: a discover-project-home that resolves to `home-path`."
  [home-path]
  (fn [_host _port] (js/Promise.resolve home-path)))

(def ^:private shadow-fails
  "Stub: discover-project-home returns nil (shadow unreachable / parse failed)."
  (fn [_host _port] (js/Promise.resolve nil)))

(def ^:private roots-unsupported
  "Stub: roots-discovery returns the workspace-discovery-unsupported reason
  (the client doesn't expose roots/list; fall through to step 4)."
  (fn [] (js/Promise.resolve {:status :error
                              :error  {:reason :workspace-discovery-unsupported}})))

(defn- roots-one
  "Stub builder: roots-discovery returns a single candidate."
  [project-home port]
  (fn [] (js/Promise.resolve {:status    :one
                              :candidate {:project-home project-home
                                          :port-file    (str project-home "/.shadow-cljs/nrepl.port")
                                          :port         port}})))

(defn- roots-many
  "Stub builder: roots-discovery returns multiple candidates (ambiguous)."
  [candidates]
  (fn [] (js/Promise.resolve {:status :many :candidates candidates})))

(deftest discover-port-explicit-port-file-short-circuits-shadow-probe
  (testing "step 1 wins — shadow HTTP probe + roots discovery MUST NOT fire when --port-file resolves"
    (async done
      (let [probed?  (atom false)
            rooted?  (atom false)
            probe-fn (fn [_h _p]
                       (reset! probed? true)
                       (js/Promise.resolve "/should-not-be-used"))
            roots-fn (fn [] (reset! rooted? true) (roots-unsupported))
            restore! (install-fs-stub!
                       nil (read-returning "explicit/nrepl\\.port" "9001"))]
        (-> (nrepl/discover-port* "explicit/nrepl.port" nil probe-fn roots-fn)
            (.then (fn [r]
                     (is (= 9001 (:port r))
                         "explicit --port-file wins the cascade")
                     (is (false? @probed?)
                         "the HTTP probe must not be hit when step 1 resolves")
                     (is (false? @rooted?)
                         "roots discovery must not be hit when step 1 resolves")
                     (restore!)
                     (done))))))))

(deftest discover-port-env-var-short-circuits-shadow-probe
  (testing "step 2 wins — env-var override skips the HTTP probe and roots discovery"
    (async done
      (let [probed?  (atom false)
            rooted?  (atom false)
            probe-fn (fn [_h _p]
                       (reset! probed? true)
                       (js/Promise.resolve "/should-not-be-used"))
            roots-fn (fn [] (reset! rooted? true) (roots-unsupported))
            restore! (install-fs-stub! "7777" throwing-read)]
        (-> (nrepl/discover-port* nil nil probe-fn roots-fn)
            (.then (fn [r]
                     (is (= 7777 (:port r)))
                     (is (false? @probed?) "env override is sync — no HTTP probe needed")
                     (is (false? @rooted?) "env override is sync — no roots probe needed")
                     (restore!)
                     (done))))))))

(deftest discover-port-roots-single-candidate-wins
  (testing "step 3 (rf2-3grub) — roots/list returns one shadow project → attach silently"
    (async done
      (let [probed?  (atom false)
            probe-fn (fn [_h _p]
                       (reset! probed? true)
                       (js/Promise.resolve "/should-not-be-used"))
            restore! (install-fs-stub! nil throwing-read)]
        (-> (nrepl/discover-port* nil nil probe-fn (roots-one "/abs/proj" 8765))
            (.then (fn [r]
                     (is (= 8765 (:port r))
                         "roots single-candidate port surfaces")
                     (is (= "/abs/proj" (:project-home r))
                         "project-home flows through for per-tool-call re-read")
                     (is (false? @probed?)
                         "shadow HTTP probe must NOT fire when roots resolves")
                     (restore!)
                     (done))))))))

(deftest discover-port-roots-many-candidates-surfaces-ambiguous
  (testing "step 3 — 2+ shadow candidates surface as :ambiguous (caller drives elicitation)"
    (async done
      (let [cs       [{:project-home "/abs/projA" :port-file "..." :port 1111}
                      {:project-home "/abs/projB" :port-file "..." :port 2222}]
            restore! (install-fs-stub! nil throwing-read)]
        (-> (nrepl/discover-port* nil nil shadow-fails (roots-many cs))
            (.then (fn [r]
                     (is (nil? (:port r)) "no port chosen until elicitation resolves")
                     (is (= cs (:ambiguous r))
                         "ambiguous candidates flow through to server.cljs")
                     (restore!)
                     (done))))))))

(deftest discover-port-roots-unsupported-falls-through-to-shadow-probe
  (testing "step 3 → 4 — roots/list rejects → fall through to HTTP probe"
    (async done
      (let [stub-fn (fn [^js path]
                      (let [p (str path)]
                        (cond
                          (and (re-find #"abs[\\\\/]proj[\\\\/]root" p)
                               (re-find #"target[\\\\/]shadow-cljs[\\\\/]nrepl\.port" p))
                          "6789"
                          :else (throw (js/Error. "ENOENT")))))
            restore! (install-fs-stub! nil stub-fn)]
        (-> (nrepl/discover-port* nil nil (shadow-returns "/abs/proj/root") roots-unsupported)
            (.then (fn [r]
                     (is (= 6789 (:port r))
                         "step 4 caught the port after step 3 was unsupported")
                     (is (= "/abs/proj/root" (:project-home r))
                         "project-home flows through from shadow probe step")
                     (restore!)
                     (done))))))))

(deftest discover-port-shadow-probe-prefers-target-then-dot-shadow
  (testing "candidate ordering preserved against the shadow-supplied base"
    (async done
      (let [stub-fn (fn [^js path]
                      (let [p (str path)]
                        ;; All three candidates exist under the shadow root; the
                        ;; earlier one (target/shadow-cljs/nrepl.port) must win.
                        (cond
                          (re-find #"target[\\\\/]shadow-cljs[\\\\/]nrepl\.port" p) "5550"
                          (re-find #"\.shadow-cljs[\\\\/]nrepl\.port" p)          "5551"
                          (re-find #"\.nrepl-port" p)                             "5552"
                          :else (throw (js/Error. "ENOENT")))))
            restore! (install-fs-stub! nil stub-fn)]
        (-> (nrepl/discover-port* nil nil (shadow-returns "/abs/proj/root") roots-unsupported)
            (.then (fn [r]
                     (is (= 5550 (:port r))
                         "first candidate (target/shadow-cljs/nrepl.port) wins")
                     (restore!)
                     (done))))))))

(deftest discover-port-shadow-down-falls-through-to-cwd
  (testing "step 4 → 5 — shadow probe fails; cascade falls through to cwd scan"
    (async done
      (let [restore! (install-fs-stub!
                       nil (read-returning "target/shadow-cljs/nrepl.port" "3030"))]
        (-> (nrepl/discover-port* nil nil shadow-fails roots-unsupported)
            (.then (fn [r]
                     (is (= 3030 (:port r))
                         "shadow down → cwd-relative scan resolves the port")
                     (restore!)
                     (done))))))))

(deftest discover-port-shadow-down-and-no-files-yields-nil
  (testing "every step misses — cascade returns nil-port (degraded boot fires)"
    (async done
      (let [restore! (install-fs-stub! nil throwing-read)]
        (-> (nrepl/discover-port* nil nil shadow-fails roots-unsupported)
            (.then (fn [r]
                     (is (nil? (:port r))
                         "all five steps missed — degraded boot triggers from this")
                     (restore!)
                     (done))))))))

(deftest discover-port-shadow-returned-base-but-no-port-file-falls-through
  (testing "shadow returned a root but no port-file lives under it → cwd scan picks up the slack"
    (async done
      (let [stub-fn (fn [^js path]
                      (let [p (str path)]
                        (cond
                          ;; No port-file under the shadow root.
                          (re-find #"abs[\\\\/]proj[\\\\/]root" p)
                          (throw (js/Error. "ENOENT"))
                          ;; But a cwd-relative one does exist (manual nREPL boot
                          ;; outside shadow's purview, say). With no node-path/join
                          ;; prefix the candidate is the bare relative string.
                          (= "target/shadow-cljs/nrepl.port" p) "4040"
                          :else (throw (js/Error. "ENOENT")))))
            restore! (install-fs-stub! nil stub-fn)]
        (-> (nrepl/discover-port* nil nil (shadow-returns "/abs/proj/root") roots-unsupported)
            (.then (fn [r]
                     (is (= 4040 (:port r))
                         "shadow's base had no port-file → cwd scan wins")
                     (restore!)
                     (done))))))))

(deftest discover-port-http-port-arg-threads-through
  (testing "the --http-port override is passed to the shadow probe"
    (async done
      (let [seen-port (atom nil)
            probe-fn  (fn [_host port]
                        (reset! seen-port port)
                        (js/Promise.resolve nil))
            restore!  (install-fs-stub! nil throwing-read)]
        (-> (nrepl/discover-port* nil 7777 probe-fn roots-unsupported)
            (.then (fn [_]
                     (is (= 7777 @seen-port)
                         "custom --http-port reaches the probe")
                     (restore!)
                     (done))))))))

(deftest discover-port-http-port-defaults-to-9630
  (testing "no --http-port → the shadow probe uses the documented 9630 default"
    (async done
      (let [seen-port (atom nil)
            probe-fn  (fn [_host port]
                        (reset! seen-port port)
                        (js/Promise.resolve nil))
            restore!  (install-fs-stub! nil throwing-read)]
        (-> (nrepl/discover-port* nil nil probe-fn roots-unsupported)
            (.then (fn [_]
                     (is (= shadow-discovery/default-http-port @seen-port)
                         "absent override falls back to 9630")
                     (restore!)
                     (done))))))))

(deftest discover-port-nil-roots-fn-equivalent-to-unsupported
  (testing "passing nil roots-discovery-fn (boot-time, pre-MCP-init) acts as :workspace-discovery-unsupported"
    (async done
      (let [restore! (install-fs-stub!
                       nil (read-returning "target/shadow-cljs/nrepl.port" "5050"))]
        (-> (nrepl/discover-port* nil nil shadow-fails nil)
            (.then (fn [r]
                     (is (= 5050 (:port r))
                         "nil roots-fn falls through to the cwd scan via shadow-fails")
                     (restore!)
                     (done))))))))

(ns re-frame2-pair-mcp.nrepl-test
  "Unit tests for the bencode framing helpers in nrepl.cljs.

  bencode@2 stores the post-decode cursor on `bencode.decode.position`
  rather than a module-level export — an easy detail to get wrong, so the
  multi-frame walker gets a thorough test.

  Tests pin `decode-all-frames` directly from
  `re-frame2-pair-mcp.nrepl` — the source ns is the contract."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [applied-science.js-interop :as j]
            ["bencode" :as bencode]
            ["fs" :as fs]
            ["net" :as net]
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
  ;; The persistent-socket case: two complete frames arrive in a single
  ;; chunk and both must decode. (A fresh-socket-per-call design would
  ;; never see this shape.)
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
  ;; The partial-frame branch directly on the public seam. A truncated
  ;; bencode frame can't decode; `decode-all-frames`
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
;; Port discovery — `read-port-from-fs`.
;;
;; The fn has a four-way precedence: the `SHADOW_CLJS_NREPL_PORT` env var
;; wins; failing that, three port-file candidates are tried in order; a
;; non-numeric value at any source is rejected via the `isNaN` guard; an
;; all-miss returns nil. We stub `fs.readFileSync` +
;; `process.env.SHADOW_CLJS_NREPL_PORT` to exercise every branch without
;; touching the real filesystem.
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
  `body` returns. The async cascade tests use [[with-async-fs-stub!]]
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
  with a pristine fs.readFileSync. The local convention here: bind the
  thunk in a `let`, do assertions in `.then`, call the thunk, then call
  done."
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
;; --port-file explicit override. The 1-arity `read-port-from-fs` takes an
;; explicit, cwd-independent path that wins over BOTH the env var and the
;; relative file-scan candidates. nil/blank ⇒ falls through to the normal
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
;; Transport data-handler — `attach-handlers!`.
;;
;; The persistent-socket `data` handler folds each chunk into the conn's
;; `:buf` and splits off complete frames in a SINGLE swap! (so framing is
;; race-free), then dispatches every complete frame to its pending-id
;; handler. It is pure buffer logic over a fed chunk — unit-testable with a
;; fake socket that records its event callbacks rather than a real TCP
;; connection.
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
;; `close!` — reconnect / probe-cache reset.
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
;; `send-op!` connect→write race nil-guard.
;;
;; `connect!`'s fast path resolves immediately when a live socket is present,
;; but the socket close/error handlers can fire in the window between that
;; resolve and the actual `.write`. If `:socket` is nilled by `close!` in
;; that window, the guard rejects with a structured retry-to-reconnect
;; message rather than a bare `(.write nil ...)` (which would throw an opaque
;; native "Cannot read .write of null" AND strand the just-registered id in
;; `:pending`). We simulate the race by nilling `:socket` synchronously after
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
;; `send-op!` response assembly + resolution + timeout.
;;
;; Once the op is written, `attach-handlers!` routes each decoded nREPL frame
;; to the `on-frame` accumulator registered under `[:pending id]`. We reach
;; that accumulator through `(:pending @conn)` and feed frames directly — no
;; socket, no bencode round-trip — to cover the response-assembly + resolution
;; branch, then a short-deadline op with NO `:done` frame to cover the timeout
;; branch. Both branches were previously below the seam: the existing
;; `send-op!-live-socket-writes-normally` deliberately feeds no `:done` frame
;; and asserts only the write + pending registration.
;; ===========================================================================

(deftest send-op!-assembles-frames-and-resolves-on-done
  (testing "value/out/err/ex frames merge; status accretes; :done resolves + clears pending"
    (async done
      (let [sock (j/lit {:write (fn [_] nil)})
            conn (nrepl/make-conn 0 "127.0.0.1")]
        (swap! conn assoc :socket sock :closed? false)
        (let [p (nrepl/send-op! conn {"op" "eval" "code" "(+ 1 1)"})]
          (-> p
              (.then (fn [res]
                       (is (= "42" (:value res)) "last :value wins")
                       (is (= "hello" (:out res)) ":out accretes across frames")
                       (is (= "oops" (:err res)) ":err captured")
                       (is (= "boom" (:ex res)) ":ex captured")
                       (is (contains? (:status res) "done")
                           ":status is the union of every frame's status")
                       (is (= {} (:pending @conn))
                           "the id is dissoc'd from :pending once :done resolves")
                       (done))))
          ;; After connect!'s fast-path microtask the op is registered pending;
          ;; feed a realistic multi-frame nREPL response into its accumulator.
          (js/queueMicrotask
            (fn []
              (let [on-frame (-> @conn :pending vals first)]
                (on-frame #js {"out" "hel"})
                (on-frame #js {"out" "lo"})
                (on-frame #js {"err" "oops"})
                (on-frame #js {"value" "42"})
                (on-frame #js {"ex" "boom"})
                (on-frame #js {"status" #js ["done"]})))))))))

(deftest send-op!-timeout-rejects-and-cleans-pending
  (testing "no :done frame before the deadline → reject + pending dissoc'd"
    (async done
      (let [sock (j/lit {:write (fn [_] nil)})
            conn (nrepl/make-conn 0 "127.0.0.1")]
        (swap! conn assoc :socket sock :closed? false)
        ;; A 1ms deadline with no :done frame ever fed drives the setTimeout
        ;; reject path.
        (let [p (nrepl/send-op! conn {"op" "eval" "code" "(loop [])"}
                                {:timeout-ms 1})]
          (-> p
              (.then (fn [_]
                       (is false "an op with no :done frame must time out, not resolve")
                       (done))
                     (fn [err]
                       (is (re-find #"timed out after 1ms" (.-message err))
                           "reject message names the op-specific deadline")
                       (is (= {} (:pending @conn))
                           "the timed-out id is dissoc'd — no pending leak")
                       (done)))))))))

;; ===========================================================================
;; `discover-port*` async cascade.
;;
;; The five-step cascade — explicit > env > MCP roots/list > shadow HTTP probe
;; > cwd scan — runs the cwd-bound file scan as a last-resort fallback. Steps
;; 3 and 4 are async I/O; the others are sync.
;;
;; The cascade returns a discovery-result map
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
                     ;; The explicit branch returns the EXACT path the caller
                     ;; named, so the server caches it verbatim (no derived
                     ;; `.shadow-cljs/nrepl.port`).
                     (is (= "explicit/nrepl.port" (:port-file r))
                         "explicit --port-file returns the exact path it read")
                     (is (false? @probed?)
                         "the HTTP probe must not be hit when step 1 resolves")
                     (is (false? @rooted?)
                         "roots discovery must not be hit when step 1 resolves")
                     (restore!)
                     (done))))))))

(deftest discover-port-explicit-port-file-unreadable-falls-through-to-env
  ;; A stale / typo'd explicit --port-file must NOT short-circuit the
  ;; cascade to `{:port nil}`. When the explicit file is unreadable (ENOENT)
  ;; the cascade falls through to step 2 ($SHADOW_CLJS_NREPL_PORT) —
  ;; mirroring `read-port-from-fs`'s leading `or` — so a live env-provided
  ;; port is honoured rather than stranded.
  (testing "explicit --port-file unreadable → fall through to env var"
    (async done
      (let [restore! (install-fs-stub! "7788" throwing-read)]
        (-> (nrepl/discover-port* "stale/nrepl.port" nil shadow-fails roots-unsupported)
            (.then (fn [r]
                     (is (= 7788 (:port r))
                         "unreadable explicit file falls through to the env var (step 2)")))
            (.finally (fn [] (restore!) (done))))))))

(deftest discover-port-explicit-port-file-unreadable-falls-through-to-roots
  ;; With no env var, the unreadable explicit file falls all the way
  ;; through to the MCP roots/list step (step 3). The explicit-but-stale
  ;; path is fully transparent to the rest of the cascade.
  (testing "explicit --port-file unreadable + no env → fall through to roots discovery"
    (async done
      (let [restore! (install-fs-stub! nil throwing-read)]
        (-> (nrepl/discover-port* "stale/nrepl.port" nil shadow-fails (roots-one "/abs/proj" 8799))
            (.then (fn [r]
                     (is (= 8799 (:port r))
                         "unreadable explicit file falls through to roots single-candidate (step 3)")
                     (is (= "/abs/proj" (:project-home r))
                         "roots project-home flows through — the stale explicit path is ignored")))
            (.finally (fn [] (restore!) (done))))))))

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
                     ;; The HTTP-probe result surfaces the WINNING candidate
                     ;; file (here target/shadow-cljs/nrepl.port resolved
                     ;; against the shadow root) so the server caches the
                     ;; exact path that read.
                     (is (re-find #"target[\\/]shadow-cljs[\\/]nrepl\.port$"
                                  (str (:port-file r)))
                         "winning candidate file (target/shadow-cljs/nrepl.port) is surfaced")
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
                     ;; The winning candidate's path is surfaced
                     ;; (target/shadow-cljs/nrepl.port, the first candidate).
                     (is (re-find #"target[\\/]shadow-cljs[\\/]nrepl\.port$"
                                  (str (:port-file r)))
                         "winning candidate file surfaced as :port-file")
                     (restore!)
                     (done))))))))

;; Exhaustive port-file surfacing for the HTTP-probe path. For EACH
;; candidate order (only the .shadow-cljs file present, only the .nrepl-port
;; present), the SURFACED :port-file must be the candidate that actually
;; read — NOT a fixed derivation. The server caches whatever discovery
;; resolved, so the per-tool-call re-read checks the right file and doesn't
;; false-positive "file vanished".
(deftest discover-port-shadow-probe-surfaces-each-candidate-file
  (testing ".shadow-cljs/nrepl.port wins → that exact file is surfaced"
    (async done
      (let [stub-fn (fn [^js path]
                      (let [p (str path)]
                        (cond
                          ;; target candidate absent — falls to .shadow-cljs.
                          (re-find #"target[\\\\/]shadow-cljs[\\\\/]nrepl\.port" p)
                          (throw (js/Error. "ENOENT"))
                          (re-find #"\.shadow-cljs[\\\\/]nrepl\.port" p) "5561"
                          :else (throw (js/Error. "ENOENT")))))
            restore! (install-fs-stub! nil stub-fn)]
        (-> (nrepl/discover-port* nil nil (shadow-returns "/abs/proj/root") roots-unsupported)
            (.then (fn [r]
                     (is (= 5561 (:port r)) ".shadow-cljs candidate read the port")
                     (is (re-find #"\.shadow-cljs[\\/]nrepl\.port$" (str (:port-file r)))
                         "the .shadow-cljs/nrepl.port file is surfaced — not target/...")
                     (is (not (re-find #"target[\\/]shadow-cljs" (str (:port-file r))))
                         "the absent target candidate is NOT what's cached")
                     (restore!)
                     (done)))))))
  (testing ".nrepl-port wins (last candidate) → that exact file is surfaced"
    (async done
      (let [stub-fn (fn [^js path]
                      (let [p (str path)]
                        (cond
                          (re-find #"target[\\\\/]shadow-cljs[\\\\/]nrepl\.port" p)
                          (throw (js/Error. "ENOENT"))
                          (re-find #"\.shadow-cljs[\\\\/]nrepl\.port" p)
                          (throw (js/Error. "ENOENT"))
                          (re-find #"\.nrepl-port" p) "5562"
                          :else (throw (js/Error. "ENOENT")))))
            restore! (install-fs-stub! nil stub-fn)]
        (-> (nrepl/discover-port* nil nil (shadow-returns "/abs/proj/root") roots-unsupported)
            (.then (fn [r]
                     (is (= 5562 (:port r)) ".nrepl-port candidate read the port")
                     (is (re-find #"\.nrepl-port$" (str (:port-file r)))
                         "the .nrepl-port file is surfaced as the winning candidate")
                     (restore!)
                     (done))))))))

;; The roots/list single-candidate path threads the candidate's own
;; :port-file through (the `.shadow-cljs/nrepl.port` adjacent to the
;; discovered shadow-cljs.edn), so the server caches the discovered file.
(deftest discover-port-roots-single-candidate-surfaces-port-file
  (testing "step 3 single candidate → its :port-file flows through"
    (async done
      (let [restore! (install-fs-stub! nil throwing-read)]
        (-> (nrepl/discover-port* nil nil shadow-fails (roots-one "/abs/proj" 8765))
            (.then (fn [r]
                     (is (= 8765 (:port r)))
                     (is (= "/abs/proj/.shadow-cljs/nrepl.port" (:port-file r))
                         "roots candidate :port-file surfaces verbatim")
                     (restore!)
                     (done))))))))

;; The cwd-scan last resort (step 5) supplies NO :port-file: there is no
;; project-home anchor for a stable absolute path, so the server must fall
;; back to its derivation (or skip the per-call re-read).
(deftest discover-port-cwd-scan-surfaces-no-port-file
  (testing "step 5 (cwd scan) → :port-file is nil (no project-home anchor)"
    (async done
      ;; roots unsupported + shadow probe returns nil ⇒ fall to cwd scan.
      ;; The cwd candidate reads, but no project-home is known.
      (let [stub-fn (read-returning "\\.nrepl-port" "5599")
            restore! (install-fs-stub! nil stub-fn)]
        (-> (nrepl/discover-port* nil nil shadow-fails roots-unsupported)
            (.then (fn [r]
                     (is (= 5599 (:port r)) "cwd scan caught the port")
                     (is (nil? (:port-file r))
                         "cwd scan surfaces no port-file — server derivation is the backstop here")
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

;; ===========================================================================
;; `connect!` reopen preserves the session build-id caches.
;;
;; `connect!` is transport-only: it preserves `:probed-builds` /
;; `:resolved-build-id` / `:build-alias` across a same-port reopen.
;; `send-op!` calls `connect!` on every nREPL op, and the close/error
;; handlers flip `:closed? true` on a TRANSIENT socket hiccup without
;; changing the target build — so the next op's `connect!` reopens the SAME
;; port and must keep the valid same-session sticky build (otherwise a
;; no-`:build` call falls back to `:app`).
;;
;; The genuine "operator restarted shadow against a DIFFERENT build" reset
;; is enforced at the layers that observe the build identity changing:
;;   - `close!` (operator-initiated teardown) clears all three; and
;;   - `server.cljs/ensure-connection!` builds a FRESH conn (empty caches)
;;     on a shadow port change/vanish — which is how a different build
;;     almost always manifests (a restart grabs a new ephemeral port).
;;
;; These tests drive the ACTUAL `connect!` Promise with a stubbed
;; `net.createConnection`, firing the recorded `connect` / `close` / `error`
;; callbacks the way Node's EventEmitter would.
;; ===========================================================================

(defn- connect-fake-socket
  "A stand-in for the `net.Socket` `net/createConnection` returns. Records
  every `on`/`once` callback into `cbs*` keyed by event name so the test
  can fire `connect` / `close` / `error` synthetically. `write`/`end` are
  inert no-ops — `connect!` only wires handlers and resolves."
  [cbs*]
  (j/lit {:on    (fn [event cb] (swap! cbs* assoc event cb) nil)
          :once  (fn [event cb] (swap! cbs* assoc event cb) nil)
          :write (fn [_] nil)
          :end   (fn [] nil)}))

(defn- with-stubbed-create-connection!
  "Install a `net.createConnection` stub that records the freshly-built
  fake socket's event callbacks into `cbs*` and returns the fake socket.
  Returns a 0-arity restoration thunk. Mirrors the `install-fs-stub!`
  lifecycle pattern: the caller restores inside the final `.then` before
  `(done)` so the next test starts pristine."
  [cbs*]
  (let [orig (.-createConnection net)]
    (set! (.-createConnection net) (fn [_opts] (connect-fake-socket cbs*)))
    (fn restore! [] (set! (.-createConnection net) orig))))

(defn- fire! [cbs* event & args]
  (apply (get @cbs* event) args))

(defn- connect-then-fire!
  "Call `connect!` (which registers the `connect` handler synchronously
  in the Promise executor), then immediately fire the recorded `connect`
  callback the way Node would once the socket is up. Returns the
  `connect!` Promise so the caller can chain assertions off its resolve."
  [conn cbs*]
  (let [p (nrepl/connect! conn)]
    (fire! cbs* "connect")
    p))

(deftest connect!-reopen-preserves-resolved-build-id-after-hiccup
  ;; Core regression guard: a transient socket close+reopen of the SAME
  ;; port mid-session PRESERVES the sticky `:resolved-build-id` (and the
  ;; sibling `:probed-builds` / `:build-alias` caches), so a follow-up
  ;; no-`:build` op keeps targeting the right build rather than falling
  ;; back to `:app`.
  (async done
    (let [cbs*     (atom {})
          restore! (with-stubbed-create-connection! cbs*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")]
      ;; First connect — the socket comes up.
      (-> (connect-then-fire! conn cbs*)
          (.then
            (fn [_]
              ;; Operator discovered + stuck a build; a unique-suffix alias
              ;; got cached; the runtime was probed live on this generation.
              (swap! conn assoc
                     :resolved-build-id :examples/step-deck
                     :build-alias       {:step-deck :examples/step-deck}
                     :probed-builds     #{:examples/step-deck})
              ;; A transient socket hiccup: the close handler flips :closed?
              ;; WITHOUT touching the build — shadow is still on the same build.
              (fire! cbs* "close" nil)
              (is (true? (:closed? @conn)) "the hiccup marked the conn closed")
              ;; The NEXT op triggers a reopen of the SAME port.
              (connect-then-fire! conn cbs*)))
          (.then
            (fn [_]
              (is (false? (:closed? @conn)) "reopened")
              (is (= :examples/step-deck (:resolved-build-id @conn))
                  "the sticky build SURVIVES a same-port reopen — the rf2-c3dsr fix")
              (is (= {:step-deck :examples/step-deck} (:build-alias @conn))
                  "the forgiving-resolution alias survives the reopen too")
              (is (= #{:examples/step-deck} (:probed-builds @conn))
                  "the probe cache survives — the runtime marker outlives a socket hiccup")
              (restore!)
              (done)))
          (.catch (fn [e] (restore!) (is false (str "unexpected reject: " (.-message e))) (done)))))))

(deftest connect!-reopen-resets-framing-buffer-but-keeps-caches
  ;; The reopen still does its transport job: a stale partial frame left in
  ;; `:buf` from the dropped socket is cleared (a fresh socket starts a fresh
  ;; bencode stream), even while the build-id caches are preserved.
  (async done
    (let [cbs*     (atom {})
          restore! (with-stubbed-create-connection! cbs*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")]
      (-> (connect-then-fire! conn cbs*)
          (.then
            (fn [_]
              (swap! conn assoc
                     :resolved-build-id :examples/step-deck
                     :buf (js/Buffer.from "d3:foo" "utf8"))  ; a stale partial frame
              (fire! cbs* "close" nil)
              (connect-then-fire! conn cbs*)))
          (.then
            (fn [_]
              (is (zero? (.-length (:buf @conn)))
                  "the framing buffer is reset on reopen (fresh socket, fresh stream)")
              (is (= :examples/step-deck (:resolved-build-id @conn))
                  "the build-id cache is preserved alongside the buffer reset")
              (restore!)
              (done)))
          (.catch (fn [e] (restore!) (is false (str "unexpected reject: " (.-message e))) (done)))))))

(deftest connect!-fast-path-leaves-caches-untouched
  ;; When the socket is already open + healthy, `connect!` short-circuits
  ;; (no createConnection, no swap) — so the caches are trivially untouched.
  ;; Pins that the fast path didn't acquire a reset side effect.
  (async done
    (let [conn (nrepl/make-conn 6001 "127.0.0.1")]
      (swap! conn assoc :socket #js {} :closed? false
             :resolved-build-id :examples/step-deck
             :probed-builds #{:examples/step-deck})
      (-> (nrepl/connect! conn)
          (.then (fn [_]
                   (is (= :examples/step-deck (:resolved-build-id @conn))
                       "fast path preserves the sticky build")
                   (is (= #{:examples/step-deck} (:probed-builds @conn)))
                   (done)))))))

;; ===========================================================================
;; The different-build reset is enforced.
;;
;; The cache survives a transient hiccup, but a genuine different-build
;; reconnect must clear the stale cache. That guarantee lives in two places:
;;
;;   1. `close!` (operator-initiated teardown) clears all three caches —
;;      so a reopen after an explicit close starts clean.
;;   2. A different build almost always lands on a new shadow ephemeral
;;      port; `server.cljs/ensure-connection!` then builds a FRESH conn
;;      (via `make-conn`), which starts with empty caches by construction.
;;
;; Both are pinned here at the transport boundary.
;; ===========================================================================

(deftest different-build-reconnect-after-close-resets-caches
  ;; The operator-teardown path: `close!` clears the caches, so a SUBSEQUENT
  ;; reopen of the (possibly same) socket starts with no stale build —
  ;; the "restarted shadow against a different build" guard.
  (async done
    (let [cbs*     (atom {})
          restore! (with-stubbed-create-connection! cbs*)
          conn     (nrepl/make-conn 6001 "127.0.0.1")]
      (-> (connect-then-fire! conn cbs*)
          (.then
            (fn [_]
              (swap! conn assoc
                     :resolved-build-id :examples/old-build
                     :build-alias       {:old :examples/old-build}
                     :probed-builds     #{:examples/old-build})
              ;; Operator-initiated teardown (NOT a transient hiccup).
              (nrepl/close! conn)
              (is (nil? (:resolved-build-id @conn))
                  "close! cleared the sticky build (l9ixp operator-teardown reset)")
              (is (= {} (:build-alias @conn)) "close! cleared the alias cache")
              (is (= #{} (:probed-builds @conn)) "close! cleared the probe cache")
              ;; Reopen — starts clean; no stale build carried across.
              (connect-then-fire! conn cbs*)))
          (.then
            (fn [_]
              (is (nil? (:resolved-build-id @conn))
                  "post-close reopen carries NO stale build — l9ixp preserved")
              (restore!)
              (done)))
          (.catch (fn [e] (restore!) (is false (str "unexpected reject: " (.-message e))) (done)))))))

(deftest fresh-conn-for-new-port-starts-with-empty-caches
  ;; The port-change path (the common shape of a different-build restart):
  ;; `ensure-connection!` discards the old conn and builds a fresh one for
  ;; the new ephemeral port. A fresh conn has empty build-id caches by
  ;; construction — so the new build is re-discovered, never inheriting the
  ;; old build's sticky id (the different-build reset holds without relying
  ;; on connect!).
  (let [old-conn (nrepl/make-conn 6001 "127.0.0.1")]
    (swap! old-conn assoc
           :resolved-build-id :examples/old-build
           :build-alias       {:old :examples/old-build}
           :probed-builds     #{:examples/old-build})
    ;; Shadow restarted on a new port → a brand-new conn (what
    ;; new-conn-for-port / make-conn yields).
    (let [new-conn (nrepl/make-conn 6002 "127.0.0.1")]
      (is (= 6002 (:port @new-conn)) "the new conn targets the new port")
      (is (nil? (:resolved-build-id @new-conn))
          "a fresh conn for the new port carries NO sticky build from the old session")
      (is (= {} (:build-alias @new-conn)))
      (is (= #{} (:probed-builds @new-conn)))
      ;; And the old conn is untouched — they're independent atoms.
      (is (= :examples/old-build (:resolved-build-id @old-conn))
          "the discarded conn keeps its state — no cross-conn mutation"))))

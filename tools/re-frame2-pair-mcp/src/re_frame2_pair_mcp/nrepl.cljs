(ns re-frame2-pair-mcp.nrepl
  "Persistent nREPL client over a TCP socket.

  This is what the bash-shim chain replaces. Each shim previously paid:
    - bash startup       (~50ms on Windows, less elsewhere)
    - babashka startup   (~50-100ms)
    - fresh nREPL connect (~200-500ms cold)
    - bencode round-trip
    - process teardown

  The MCP server pays the cold connect once at boot; every subsequent
  call is just a bencode round-trip on the existing socket. Per-op
  latency drops from ~700ms to ~5-50ms.

  ## Reconnect protocol

  A full page reload in the browser destroys the shadow-cljs CLJS
  runtime but leaves the nREPL socket on the JVM side intact. So the
  socket usually stays usable — what we lose is the *runtime sentinel*
  (`re-frame2-pair.runtime/session-id` and its mirror at
  `js/globalThis.__re_frame2_pair_runtime`), which lives in the CLJS
  heap.

  shadow-cljs re-runs the consumer's `:devtools :preloads` as part of
  the next bundle load, so the runtime ns and its global marker
  reappear automatically. Every tool that needs the runtime probes
  the marker via `tools/ensure-runtime!`; missing marker surfaces a
  structured `:reason :runtime-not-preloaded` error. No cljs-eval
  inject fallback (rf2-7dvg).

  ## Concurrency

  We dispatch by nREPL `id` (UUID per request). The same socket can
  carry interleaved ops because nREPL's protocol multiplexes on `id` —
  but the MCP server today calls one tool at a time, so we serialise
  in-flight ops by `id` and resolve each Promise when its `:done`
  status arrives."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [cljs.reader :as edn]
            [re-frame2-pair-mcp.shadow-discovery :as shadow-discovery]
            ["bencode" :as bencode]
            ["net" :as net]
            ["path" :as node-path]
            ["fs" :as fs]))

;; ---------------------------------------------------------------------------
;; Port discovery — five-step cascade with the shadow HTTP probe in the middle.
;; ---------------------------------------------------------------------------

(def ^:private port-file-candidates
  "Standard locations shadow-cljs / generic-nREPL write the port file to,
  relative to the project root. The cascade resolves these against three
  different bases in three different steps — see `discover-port`."
  ["target/shadow-cljs/nrepl.port"
   ".shadow-cljs/nrepl.port"
   ".nrepl-port"])

(defn- read-port-file
  "Read + parse an nREPL port from a single file `path`. Returns an
  integer or nil (file absent / unreadable / non-numeric content)."
  [path]
  (try
    (let [content (str/trim (.toString (.readFileSync fs path)))
          n       (js/parseInt content 10)]
      (when-not (js/isNaN n) n))
    (catch :default _ nil)))

(defn- read-env-port
  "Read `$SHADOW_CLJS_NREPL_PORT` and parse as int. Returns nil when
  unset or non-numeric. Pulled out so the precedence cascade reads
  linearly."
  []
  (when-let [env (j/get-in js/process [:env :SHADOW_CLJS_NREPL_PORT])]
    (let [n (js/parseInt env 10)]
      (when-not (js/isNaN n) n))))

(defn- read-port-at-base
  "Resolve `port-file-candidates` against an absolute base directory and
  return the first hit's parsed port — or nil. Uses `node-path/join` so
  the candidates are platform-correct on Windows + POSIX without each
  call site building paths by string-concat."
  [base]
  (when (and base (seq base))
    (some (fn [rel] (read-port-file (node-path/join base rel)))
          port-file-candidates)))

(defn read-port-from-fs
  "Synchronous slice of the port-discovery cascade — the explicit
  override path + env var + cwd-relative file scan. Returns an integer
  or nil.

  Lives on as a pure-sync helper so unit tests can pin the file-system
  precedence without driving the async HTTP probe. Production boot
  prefers `discover-port` (async) which composes this with the shadow
  HTTP step.

  ## Precedence (highest first)

    1. `--port-file <path>`   explicit, cwd-independent escape hatch
                              (rf2-3dbwh). Passed as `explicit-port-file`.
    2. `$SHADOW_CLJS_NREPL_PORT` env var.
    3. `target/shadow-cljs/nrepl.port`  ┐
    4. `.shadow-cljs/nrepl.port`        ├ cwd-relative scan (steps 3-5).
    5. `.nrepl-port`                    ┘"
  ([] (read-port-from-fs nil))
  ([explicit-port-file]
   (or (when (and explicit-port-file (seq explicit-port-file))
         (read-port-file explicit-port-file))
       (read-env-port)
       (some read-port-file port-file-candidates))))

(defn discover-port*
  "Full nREPL port-discovery cascade with the shadow HTTP probe injected
  as `shadow-probe-fn` — a `(host port) → Promise<project-home-or-nil>`
  function. Production callers thread `shadow-discovery/discover-project-home`
  (see [[discover-port]]); tests pass stubs to exercise each cascade
  branch without standing up a real HTTP server.

  Returns a Promise resolving to an integer port or to nil.

  ## Precedence (highest first; rf2-umoz2)

    1. `--port-file <path>`   explicit, cwd-independent override
                              (rf2-3dbwh). Wins outright.
    2. `$SHADOW_CLJS_NREPL_PORT` env var.
    3. **Shadow HTTP probe** — `shadow-probe-fn` returns the consumer
       project's absolute `:project-home`; we then read
       `port-file-candidates` resolved against that root. This is the
       cwd-robust zero-config win for the agent-host launch pattern
       where `process.cwd()` is the host, not the project (rf2-umoz2).
    4. CWD-relative scan of `port-file-candidates` — legacy fallback
       for environments without the shadow HTTP API (older shadow, or
       a manual nREPL boot bypassing shadow entirely).

  ## Why steps 3 and 4 use the same candidate list

  shadow-cljs writes its nREPL port to one of three known relative paths
  inside the project root. Step 3 obtains the root from shadow itself;
  step 4 hopes the cwd already IS the root. Sharing the candidate list
  keeps the contract `(root, candidates) → port` symmetrical across the
  two steps.

  ## Why the HTTP probe is bounded

  See `shadow-discovery/probe-timeout-ms`. The probe never blocks boot
  for more than half a second — if shadow's web server is unreachable
  the cascade moves on. The `--port-file` and env-var escape hatches
  (steps 1 and 2) remain the deterministic overrides for exotic setups.

  ## Why injection rather than direct call

  The CLJS compiler resolves `shadow-discovery/discover-project-home` at
  callsite-emit time; CLJS's `with-redefs` swaps the *var binding* but
  the emitted JS for a direct multi-arity call dispatches through
  fn-object arity slots, not through the var. Injecting the probe makes
  the seam explicit and gives tests a clean stub-point — and the
  production wrapper below pays nothing for the indirection (single
  call, no allocation)."
  [explicit-port-file http-port shadow-probe-fn]
  (let [override (or (when (and explicit-port-file (seq explicit-port-file))
                       (read-port-file explicit-port-file))
                     (read-env-port))]
    (if override
      (js/Promise.resolve override)
      (-> (shadow-probe-fn
            shadow-discovery/default-host
            (or http-port shadow-discovery/default-http-port))
          (.then (fn [project-home]
                   (or (read-port-at-base project-home)
                       ;; Final fallback: cwd-relative scan.
                       (some read-port-file port-file-candidates))))))))

(defn discover-port
  "Production entry-point for the port-discovery cascade — see
  [[discover-port*]] for the precedence details and the design rationale.
  Returns a Promise resolving to an integer port or to nil."
  ([] (discover-port nil nil))
  ([explicit-port-file http-port]
   (discover-port* explicit-port-file http-port
                   shadow-discovery/discover-project-home)))

;; ---------------------------------------------------------------------------
;; bencode framing — handle concatenated frames in one TCP packet.
;; ---------------------------------------------------------------------------

;; `log!` is defined further down (alongside the connection state); the
;; stuck-cursor diagnostic in `decode-all-frames` needs it earlier.
(declare log!)

(defn decode-all-frames
  "Decode every complete bencode frame from `buf`. Returns
  `[js-array-of-frames trailing-buffer]`. Walks via the package's
  `position` after-decode marker so multi-frame TCP chunks parse fully.

  Public so `nrepl_test.cljs` can pin the contract directly rather than
  re-implementing a parallel walker."
  [^js buf]
  (let [frames (array)]
    (loop [^js b buf]
      (if (zero? (.-length b))
        [frames b]
        (let [decoded  (try (bencode/decode b "utf8") (catch :default _ nil))
              ;; bencode@2 stores byte cursor info on the decode fn,
              ;; not on the module exports. `decode.bytes` is the
              ;; cursor AFTER decoding the most recent frame.
              ;; bencode@2 exposes a per-decode cursor on the decode
              ;; fn itself. `position` is the byte offset AFTER the
              ;; just-decoded frame; `bytes` is unreliable — it gets
              ;; set to the total buffer length on some paths even
              ;; when only the first frame was returned. We prefer
              ;; `position`.
              consumed (or (j/get-in bencode [:decode :position])
                           (j/get-in bencode [:decode :bytes])
                           0)]
          (cond
            (nil? decoded)        [frames b]
            ;; Stuck-cursor guard: the bencode lib decoded a frame but
            ;; reports `consumed = 0`, so we can't advance `b`. We push
            ;; the one frame and stop, dropping whatever trailing bytes
            ;; remain in this chunk — the alternative (recur on the
            ;; un-advanced `b`) is an infinite loop. This is a defensive
            ;; branch, not an observed path (`position` is reliable for
            ;; the real frame shapes; see comment above). rf2-ee38b.18:
            ;; if a multi-frame chunk ever DID hit this, the trailing
            ;; complete frames would be silently lost and their pending
            ;; nREPL ids would hang until timeout — so we log to stderr
            ;; (never stdout — reserved for MCP) when the dropped trailer
            ;; is non-empty, making a real occurrence diagnosable rather
            ;; than mute.
            (zero? consumed)
            (do (.push frames decoded)
                (when (pos? (.-length b))
                  (log! "WARNING decode-all-frames: bencode reported consumed=0 after a"
                        "successful frame decode; dropping" (.-length b)
                        "trailing byte(s) in this chunk to avoid a stuck-cursor loop."
                        "Any complete frames in the dropped tail will hang until timeout."))
                [frames (js/Buffer.alloc 0)])
            :else
            (do (.push frames decoded)
                (recur (.slice b consumed)))))))))

;; ---------------------------------------------------------------------------
;; Connection state.
;; ---------------------------------------------------------------------------

(defn- log!
  "Stderr logger — stdout is reserved for MCP messages."
  [& parts]
  (.error js/console (str "[re-frame2-pair-mcp/nrepl] " (str/join " " (map str parts)))))

(defn make-conn
  "Build a fresh connection record. `:socket` is filled in by `connect!`.
  `:pending` is `{id resolve-fn}` for in-flight requests. `:closed?` is
  set when the socket terminates so subsequent calls re-open.

  `:probed-builds` is the set of build-ids for which the re-frame2-pair runtime
  preload has been confirmed live on this socket generation (rf2-sjpx0).
  Cleared on connect / reconnect — a full page reload destroys the CLJS
  heap (and thus the `__re_frame2_pair_runtime` marker); we re-probe
  on the first tool call after that boundary."
  [port host]
  (atom {:port          port
         :host          (or host "127.0.0.1")
         :socket        nil
         :buf           nil
         :pending       {}
         :closed?       true
         :session       nil
         :probed-builds #{}}))

(defn attach-handlers!
  "Wire up `data` / `error` / `close` on the freshly-connected socket.
  Resolves pending requests on matching `:done` status; logs errors;
  marks the conn closed on disconnect.

  The `data` handler folds the incoming chunk into the buffer AND
  splits off any complete frames in a single `swap!` — two-swap
  variants left a window where a second `data` callback (or a
  Buffer.concat racing) could observe the freshly-accumulated bytes
  but not yet the trimmed trailer, double-decoding the same frame.
  One atomic swap closes that window.

  Public (not `defn-`) so `nrepl_test.cljs` can drive the data-folding
  + pending-id dispatch with a fake socket — feeding synthetic chunks
  and asserting pending handlers fire — without opening a real TCP
  socket (rf2-wnrpi). The fake socket need only implement an `on`
  method that records the callback per event name; see the test's
  `fake-socket` helper. This mirrors the `decode-all-frames`
  public-for-test precedent above."
  [conn-atom ^js socket]
  (j/call socket :on "data"
    (fn [chunk]
      (let [frames* (atom nil)
            _       (swap! conn-atom
                           (fn [c]
                             (let [buf'           (js/Buffer.concat
                                                    #js [(or (:buf c) (js/Buffer.alloc 0))
                                                         chunk])
                                   [frames rest'] (decode-all-frames buf')]
                               (reset! frames* frames)
                               (assoc c :buf rest'))))]
        (doseq [frame (array-seq @frames*)]
          (let [id      (j/get frame "id")
                resolve (get (:pending @conn-atom) id)]
            (when resolve
              ;; Accumulate fields into the pending result. Each pending
              ;; entry holds {:result (atom result-map) :resolve fn}.
              ;; For brevity we store a single resolve fn that merges
              ;; via a per-call atom — see send-eval! below.
              (resolve frame)))))))
  (j/call socket :on "error"
    (fn [err]
      (log! "socket error:" (.-message err))
      (swap! conn-atom assoc :closed? true)))
  (j/call socket :on "close"
    (fn [_]
      (swap! conn-atom assoc :closed? true))))

(defn connect!
  "Open the persistent socket. Returns a Promise resolving to the
  connection atom once `connect` fires (or rejecting if it errors).
  Idempotent — if a socket is already open and healthy, resolves
  immediately."
  [conn-atom]
  (let [{:keys [socket closed?]} @conn-atom]
    (if (and socket (not closed?))
      (js/Promise.resolve conn-atom)
      (js/Promise.
        (fn [resolve reject]
          (let [{:keys [host port]} @conn-atom
                sock (net/createConnection #js {:host host :port port})]
            (j/call sock :on "connect"
              (fn []
                ;; Reset `:probed-builds` on (re)connect — a page reload
                ;; destroys the CLJS heap and the
                ;; `__re_frame2_pair_runtime` marker with it (rf2-sjpx0).
                (swap! conn-atom assoc :socket sock :closed? false
                       :buf (js/Buffer.alloc 0) :probed-builds #{})
                (attach-handlers! conn-atom sock)
                (resolve conn-atom)))
            (j/call sock :once "error"
              (fn [err]
                (swap! conn-atom assoc :closed? true)
                (reject err)))))))))

(defn close!
  "Close the persistent socket. Idempotent. Drops the per-socket probe
  cache (`:probed-builds`) so a fresh connect re-probes the preload."
  [conn-atom]
  (when-let [^js sock (:socket @conn-atom)]
    (try (.end sock) (catch :default _ nil)))
  (swap! conn-atom assoc :socket nil :closed? true :pending {} :probed-builds #{}))

;; ---------------------------------------------------------------------------
;; Op send / receive — multiplex by request id.
;; ---------------------------------------------------------------------------

(defn- new-id [] (str (random-uuid)))

(def ^:private default-timeout-ms
  "Per-op timeout when the caller doesn't override. 30s is generous
  for shadow-cljs cljs-eval round-trips; heavy forms (e.g. a
  trace-window walk over the full epoch ring under load) can pass
  `:timeout-ms` for longer."
  30000)

(defn send-op!
  "Send a single nREPL op over the persistent socket. `op-map` is a
  bencode-able map like `{\"op\" \"eval\" \"code\" \"...\"}`. Returns a
  Promise resolving to a combined response `{:value :out :err :status :ex}`
  once a frame with `\"status\":[\"done\"]` arrives for this id.

  Auto-(re)connects if the socket has dropped. Caller manages the
  reinjection sentinel — see `tools.cljs`.

  ## Options (rf2-ambfv)

  Optional second arg:

      :timeout-ms <ms>  per-op deadline. Defaults to
                        `default-timeout-ms` (30s). Heavy forms can
                        raise this rather than collapsing under the
                        generic ceiling."
  ([conn-atom op-map] (send-op! conn-atom op-map nil))
  ([conn-atom op-map {:keys [timeout-ms]}]
  (-> (connect! conn-atom)
      (.then
        (fn [_]
          (js/Promise.
            (fn [resolve reject]
              (let [id        (new-id)
                    state     (atom {:out "" :err "" :status #{} :value nil :ex nil})
                    deadline  (or timeout-ms default-timeout-ms)
                    timer     (js/setTimeout
                                (fn []
                                  (swap! conn-atom update :pending dissoc id)
                                  (reject (js/Error. (str "nREPL op " (j/get op-map "op")
                                                          " timed out after " deadline "ms"))))
                                deadline)
                    on-frame
                    (fn [^js frame]
                      (let [v   (j/get frame "value")
                            out (j/get frame "out")
                            err (j/get frame "err")
                            ex  (j/get frame "ex")
                            st  (or (j/get frame "status") #js [])]
                        (when v   (swap! state assoc :value v))
                        (when out (swap! state update :out str out))
                        (when err (swap! state update :err str err))
                        (when ex  (swap! state assoc :ex ex))
                        (let [st-set (set (js->clj st))]
                          (swap! state update :status into st-set)
                          (when (contains? st-set "done")
                            (js/clearTimeout timer)
                            (swap! conn-atom update :pending dissoc id)
                            (resolve @state)))))]
                (swap! conn-atom assoc-in [:pending id] on-frame)
                (let [op (j/assoc! (clj->js op-map) "id" id)
                      ;; rf2-av5kl: re-read `:socket` immediately before the
                      ;; write. `connect!` guarantees a live socket at resolve
                      ;; time, but the close/error handlers (attach-handlers!)
                      ;; can fire in the window between resolve and this write —
                      ;; `close!` nils `:socket`. A bare `(.write nil ...)` would
                      ;; throw an opaque native "Cannot read .write of null" in
                      ;; the Promise executor AND strand the just-registered id
                      ;; in `:pending`. Guard explicitly: clean up the pending
                      ;; entry + timer and reject with a structured,
                      ;; retry-to-reconnect message instead of the native NPE.
                      ^js sock (:socket @conn-atom)]
                  (if sock
                    (.write sock (bencode/encode op))
                    (do
                      (swap! conn-atom update :pending dissoc id)
                      (js/clearTimeout timer)
                      (reject (js/Error. "nREPL socket dropped before write — retry to reconnect"))))))))))
      (.catch (fn [err] (js/Promise.reject err))))))

;; ---------------------------------------------------------------------------
;; nREPL → CLJS eval bridge (mirrors ops.clj's cljs-eval / cljs-eval-value).
;; ---------------------------------------------------------------------------

(defn jvm-eval
  "Evaluate a Clojure form on the JVM side of nREPL. Returns a Promise
  resolving to a combined response map. Optional `opts` passes
  through to [[send-op!]] (e.g. `{:timeout-ms 60000}`)."
  ([conn-atom form-str] (jvm-eval conn-atom form-str nil))
  ([conn-atom form-str opts]
   (send-op! conn-atom {"op" "eval" "code" form-str} opts)))

(defn cljs-eval
  "Evaluate a ClojureScript form through shadow-cljs's `cljs-eval` API.
  Returns a Promise resolving to a combined response map. Optional
  `opts` (e.g. `{:timeout-ms 60000}`) tunes the per-op deadline."
  ([conn-atom build-id form-str] (cljs-eval conn-atom build-id form-str nil))
  ([conn-atom build-id form-str opts]
   (let [build-pr  (if (keyword? build-id) (str build-id) (str ":" (name (or build-id :app))))
         ;; pr-str CLJS form: use double-quote-escaped string literal.
         code-pr   (pr-str form-str)
         wrapped   (str "(shadow.cljs.devtools.api/cljs-eval "
                        build-pr " " code-pr " {})")]
     (jvm-eval conn-atom wrapped opts))))

(defn- read-edn-safe
  "Best-effort EDN read of the nREPL value string. On parse failure we
  return the raw string so the caller can decide what to do with the
  unparseable shape — but we log to stderr first because a silent
  drop-back hides genuine wire-shape regressions (a runtime that
  shipped malformed EDN gets surfaced by inspection of the dev
  console, not by a mute branch)."
  [s]
  (try
    (edn/read-string s)
    (catch :default e
      (log! "read-edn-safe: parse failed —" (.-message e))
      s)))

(defn cljs-eval-value
  "Like cljs-eval but unwraps to the actual CLJS value. shadow's
  cljs-eval returns a string-encoded EDN result like
  `{:results [\"...\"] :ns user}` — we pull the last `:results` entry
  and read it as EDN.

  Returns a Promise resolving to the unwrapped value (or rejecting
  with an Error if the eval threw or returned an error map). Optional
  `opts` (e.g. `{:timeout-ms 60000}`) tunes the per-op deadline —
  heavy forms (full-epoch-ring walks) can raise it past the 30s
  default."
  ([conn-atom build-id form-str] (cljs-eval-value conn-atom build-id form-str nil))
  ([conn-atom build-id form-str opts]
  (-> (cljs-eval conn-atom build-id form-str opts)
      (.then
        (fn [resp]
          (cond
            (some? (:ex resp))
            (js/Promise.reject (js/Error. (str "nREPL eval error: " (:ex resp)
                                               (when-not (str/blank? (:err resp))
                                                 (str " — " (:err resp))))))

            (str/blank? (str (:value resp)))
            nil

            :else
            (let [outer (read-edn-safe (str (:value resp)))]
              (cond
                (and (map? outer) (vector? (:results outer)))
                (when-let [last-result (peek (:results outer))]
                  (read-edn-safe last-result))

                (and (map? outer) (:err outer))
                (js/Promise.reject
                  (js/Error. (str "cljs eval error: " (:err outer))))

                :else outer))))))))

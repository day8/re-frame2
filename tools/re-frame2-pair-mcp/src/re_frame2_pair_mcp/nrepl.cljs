(ns re-frame2-pair-mcp.nrepl
  "Persistent nREPL client over a TCP socket.

  One socket lives for the whole session. The MCP server pays the cold
  connect once at boot; every subsequent call is just a bencode
  round-trip on the existing socket — per-op latency is ~5-50ms (a
  per-call connect would instead pay process startup + a ~200-500ms
  cold nREPL connect + teardown each time, ~700ms total).

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
  structured `:reason :runtime-not-preloaded` error. There is no
  cljs-eval inject fallback — the preload is the single load path.

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
  return the first hit as `{:port <int> :port-file <abs-path>}` — or nil
  when no candidate reads. Uses `node-path/join` so the candidates are
  platform-correct on Windows + POSIX without each call site building
  paths by string-concat.

  The result carries the WINNING candidate path, not just the parsed
  port. The server caches that exact path so the per-tool-call re-read
  (`ensure-connection!`) checks the file that actually won the candidate
  scan, instead of deriving a fixed `.shadow-cljs/nrepl.port` that may
  not be the candidate that hit (which would then read as 'file
  disappeared' and force a needless reconnect)."
  [base]
  (when (and base (seq base))
    (some (fn [rel]
            (let [pf (node-path/join base rel)]
              (when-let [port (read-port-file pf)]
                {:port port :port-file pf})))
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

    1. `--port-file <path>`   explicit, cwd-independent escape hatch.
                              Passed as `explicit-port-file`.
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
  "Full nREPL port-discovery cascade with every async step injected so
  tests can drive each branch without sockets or network. Production
  callers use [[discover-port]] / [[discover-port-with-roots]] which
  thread the real probes in.

  Returns a Promise resolving to a map
  `{:port <int|nil> :project-home <abs|nil> :ambiguous <vec-of-candidates|nil>
    :workspace-roots <vec> :checked-edns <vec>}`. The orchestrator
  (`server.cljs`) consumes the richer shape to drive the elicitation
  branch on ambiguity.

  ## Precedence (highest first)

    1. `--port-file <path>`   explicit, cwd-independent override.
                              Wins outright WHEN READABLE;
                              `:project-home` is the dirname of the port
                              file and `:port-file` is the EXACT path the
                              caller named — so the server caches it
                              verbatim rather than deriving a
                              `.shadow-cljs/nrepl.port` that may not exist.
                              An explicit-but-unreadable / non-numeric file
                              falls through to steps 2-5 —
                              mirroring `read-port-from-fs` — so a stale
                              port file can't strand a live port reachable
                              via env / roots / HTTP / cwd.
    2. `$SHADOW_CLJS_NREPL_PORT` env var. `:project-home` left nil
                              (env-overridden discovery doesn't surface a
                              root); the per-tool-call port-file re-read
                              is skipped on this path.
    3. **MCP `roots/list` walk** — when `roots-discovery-fn`
                              is supplied (post-MCP-init, lazy on first
                              tool call), ask the client for its
                              workspace roots, walk each one for
                              `shadow-cljs.edn`, and pair the result with
                              the adjacent `.shadow-cljs/nrepl.port`. One
                              candidate → silent attach. Multiple →
                              `:ambiguous` (caller drives elicitation).
                              `:workspace-discovery-unsupported` →
                              proceed to step 4.
    4. **Shadow HTTP probe** — `shadow-probe-fn` returns the
                              consumer project's absolute `:project-home`;
                              we then read `port-file-candidates` resolved
                              against that root. The result surfaces the
                              WINNING candidate path as `:port-file`
                              so the server caches the exact
                              file that hit, not a derived one. Cwd-robust
                              fallback for clients without
                              `roots/list`.
    5. CWD-relative scan of `port-file-candidates` — final fallback for
                              environments without the shadow HTTP API. No
                              `:port-file` is surfaced (no project-home
                              anchor for a stable absolute path).

  ## Why steps 3, 4, and 5 share the same candidate list

  shadow-cljs writes its nREPL port to one of three known relative paths
  inside the project root. Step 3 obtains roots from the MCP client;
  step 4 obtains the root from shadow itself; step 5 hopes cwd already
  IS the root. Sharing the candidate list keeps the contract symmetric
  across the three steps.

  ## Why the probes are bounded

  See `shadow-discovery/probe-timeout-ms`. Each probe never blocks for
  more than half a second — if any source is unreachable the cascade
  moves on. The `--port-file` and env-var escape hatches (steps 1 and 2)
  remain the deterministic overrides for exotic setups.

  ## Why injection rather than direct call

  The CLJS compiler resolves `shadow-discovery/discover-project-home` at
  callsite-emit time; CLJS's `with-redefs` swaps the *var binding* but
  the emitted JS for a direct multi-arity call dispatches through
  fn-object arity slots, not through the var. Injecting each probe makes
  the seams explicit and gives tests clean stub-points."
  [explicit-port-file http-port shadow-probe-fn roots-discovery-fn]
  (cond
    ;; Step 1 — explicit --port-file flag. Wins ONLY when it actually
    ;; reads a port. An explicit-but-unreadable / non-numeric port file
    ;; (a stale leftover, a typo'd path) MUST fall through to env →
    ;; roots → HTTP → cwd — exactly the precedence `read-port-from-fs`
    ;; (the sync slice) implements via its leading `or`. A stale
    ;; `--port-file` must NOT short-circuit the whole cascade when the env
    ;; var / configured roots / shadow HTTP endpoint / cwd scan could
    ;; find a live port.
    (and explicit-port-file (seq explicit-port-file)
         (some? (read-port-file explicit-port-file)))
    ;; Return the EXACT explicit port-file path the caller named, so the
    ;; server caches it verbatim. Omitting `:port-file` would force
    ;; server.cljs to DERIVE `<project-home>/.shadow-cljs/nrepl.port`
    ;; (= dirname-of-explicit-file + .shadow-cljs/nrepl.port), which for an
    ;; explicit `target/shadow-cljs/nrepl.port` resolves to a non-existent
    ;; `target/shadow-cljs/.shadow-cljs/nrepl.port`; the next
    ;; ensure-connection! would read that derived path as missing and force
    ;; a needless reconnect + per-connection cache reset.
    (js/Promise.resolve {:port         (read-port-file explicit-port-file)
                         :project-home (node-path/dirname explicit-port-file)
                         :port-file    explicit-port-file})

    ;; Step 2 — $SHADOW_CLJS_NREPL_PORT.
    (some? (read-env-port))
    (js/Promise.resolve {:port (read-env-port)})

    :else
    ;; Steps 3 → 4 → 5 — chained async cascade.
    (-> (if roots-discovery-fn
          (roots-discovery-fn)
          (js/Promise.resolve {:status :error
                               :error  {:reason :workspace-discovery-unsupported}}))
        (.then
          (fn [r]
            (case (:status r)
              ;; The roots candidate carries its own `:port-file` (the
              ;; `.shadow-cljs/nrepl.port` adjacent to the discovered
              ;; `shadow-cljs.edn`); thread it through so the server caches
              ;; the exact discovered file rather than re-deriving one.
              :one  (let [c (:candidate r)]
                      {:port (:port c) :project-home (:project-home c)
                       :port-file (:port-file c)})
              :many {:port nil :ambiguous (:candidates r)}
              :error
              ;; Roots unavailable or no shadow in roots — try step 4 (HTTP probe).
              (-> (shadow-probe-fn
                    shadow-discovery/default-host
                    (or http-port shadow-discovery/default-http-port))
                  (.then (fn [project-home]
                           (if-let [{:keys [port port-file]} (read-port-at-base project-home)]
                             ;; Thread the WINNING candidate file through so
                             ;; the server caches the exact path that hit
                             ;; (target/shadow-cljs/nrepl.port vs
                             ;; .shadow-cljs/nrepl.port vs .nrepl-port), not
                             ;; a derived one.
                             {:port port :project-home project-home :port-file port-file}
                             ;; Step 5 — cwd-relative scan, last resort. No
                             ;; project-home to anchor a stable port-file, so
                             ;; none is surfaced (the cwd path can't supply an
                             ;; absolute, restart-stable file path).
                             {:port (some read-port-file port-file-candidates)}))))))))))

(defn discover-port
  "Production entry-point for the port-discovery cascade — see
  [[discover-port*]] for the precedence details and design rationale.

  Returns a Promise resolving to a discovery-result map (see
  `discover-port*`). The `roots-discovery-fn` is optional — boot-time
  callers without an initialized MCP client omit it; post-init callers
  thread it in so step 3 (roots-list) is reachable.

  The 0-/2-arity wrappers cover boot-time callers without an MCP client;
  the 3-arity takes the roots fn for post-init discovery."
  ([] (discover-port nil nil nil))
  ([explicit-port-file http-port]
   (discover-port explicit-port-file http-port nil))
  ([explicit-port-file http-port roots-discovery-fn]
   (discover-port* explicit-port-file http-port
                   shadow-discovery/discover-project-home
                   roots-discovery-fn)))

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
            ;; the real frame shapes; see comment above). If a
            ;; multi-frame chunk ever DID hit this, the trailing
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
  preload has been confirmed live on this socket generation.
  Cleared by `close!` (operator-initiated teardown) and reborn empty
  whenever `ensure-connection!` builds a fresh conn for a new port — but
  NOT on a same-port socket reopen (see `connect!`). The
  `__re_frame2_pair_runtime` marker lives in the browser CLJS heap, which
  a JVM-side socket hiccup doesn't destroy, so the cache stays valid
  across a reopen; a page reload that DID destroy the marker leaves the
  JVM nREPL socket up, so a negative probe re-runs downstream anyway.

  `:resolved-build-id` is the build-id `discover-app` last resolved.
  Subsequent tool calls without an explicit `:build` arg
  default to it instead of the `SHADOW_CLJS_BUILD_ID` env-var fallback —
  removing the pair-debug friction of re-passing the build on every call.
  Same invalidation lifecycle as `:probed-builds`: cleared by `close!`
  and by a fresh `ensure-connection!` conn, PRESERVED across a transient
  same-port reopen.

  `:build-alias` is the forgiving-resolution cache:
  `{requested-keyword canonical-keyword}`. A `:build` arg that names a
  running build by a unique SUFFIX (`:machine-epochs` ⇒
  `:examples/machine-epochs`) resolves to the canonical running id the
  first time it's seen, and the alias is remembered so every later
  `arg-build` read returns the same canonical id without re-fetching
  `active-builds`. Same invalidation lifecycle as `:probed-builds`:
  cleared by `close!` and a fresh conn, preserved across a same-port
  reopen — a genuine build restart (which changes the running set) lands
  on a new port (fresh conn) or an operator `close!`, so a stale alias
  never survives a real build change."
  [port host]
  (atom {:port              port
         :host              (or host "127.0.0.1")
         :socket            nil
         :buf               nil
         :pending           {}
         :closed?           true
         :session           nil
         :probed-builds     #{}
         :resolved-build-id nil
         :build-alias       {}}))

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
  socket. The fake socket need only implement an `on`
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
  immediately.

  ## Why the reopen path does NOT touch the build-id caches

  `connect!` is a pure *transport* concern: open the socket, reset the
  framing buffer, wire the handlers. It must NOT clear the session
  caches (`:probed-builds` / `:resolved-build-id` / `:build-alias`) on
  reopen, because `send-op!` calls `connect!` on EVERY op and the
  close/error handlers (`attach-handlers!`) flip `:closed?` true on a
  mere transient socket hiccup WITHOUT changing the target build. The
  next op's `connect!` would then reopen the SAME port and — if it also
  wiped the caches — silently drop a valid same-session sticky build,
  so a follow-up no-`:build` call would fall back to `:app` (e.g.
  `orient {}` mis-routing after a successful discover).

  The caches' invalidation lives with the layer that actually knows the
  build *identity* changed:

    - `close!` (operator-initiated teardown) clears all three.
    - `server.cljs/ensure-connection!` builds a FRESH conn (empty caches)
      when shadow's nREPL port vanishes or changes — the common shape of
      the \"operator restarted shadow against a different build\" case (a
      restart almost always grabs a new ephemeral port).

  A same-port reopen of the SAME conn within one session is, by
  construction, a reconnect to the SAME build — so preserving the caches
  across it is correct. `:probed-builds` in particular stays valid: the
  `__re_frame2_pair_runtime` marker lives in the browser's CLJS heap,
  which a JVM-side socket drop does not destroy (only a page reload
  does, and a page reload leaves the JVM nREPL socket — hence
  `:closed?` — untouched). A negative probe re-runs downstream anyway."
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
                ;; Transport-only: swap in the live socket, clear the
                ;; closed flag, and reset the framing buffer. The build-id
                ;; caches are deliberately PRESERVED across a same-port
                ;; reopen — see the fn docstring for why and for where the
                ;; genuine-different-build reset lives.
                (swap! conn-atom assoc :socket sock :closed? false
                       :buf (js/Buffer.alloc 0))
                (attach-handlers! conn-atom sock)
                (resolve conn-atom)))
            (j/call sock :once "error"
              (fn [err]
                (swap! conn-atom assoc :closed? true)
                (reject err)))))))))

(defn close!
  "Close the persistent socket. Idempotent. Drops the per-socket probe
  cache (`:probed-builds`) so a fresh connect re-probes the preload.
  Also drops `:resolved-build-id` — the cached default for
  tool calls without an explicit `:build` arg — and `:build-alias`,
  the forgiving suffix→canonical resolution cache — so a
  reconnect doesn't carry a stale build-id from the previous session."
  [conn-atom]
  (when-let [^js sock (:socket @conn-atom)]
    (try (.end sock) (catch :default _ nil)))
  (swap! conn-atom assoc :socket nil :closed? true :pending {}
         :probed-builds #{} :resolved-build-id nil :build-alias {}))

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

  Auto-(re)connects if the socket has dropped. The runtime ships via the
  shadow-cljs `:devtools :preloads` mechanism, so no per-op reinjection is
  needed; tools probe the preload marker via
  `tools.probe/ensure-runtime!`.

  ## Options

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
                      ;; Re-read `:socket` immediately before the
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
  default.

  ## Compile warnings/errors surface — never swallowed as nil

  shadow's `cljs-eval` returns its `{:results :err :out :ns}` map as the
  JVM eval value (the `outer` map below). A form with an UNRESOLVED
  symbol (`re-frame.core/frame-db` — a fn that doesn't exist) compiles
  to JS that references an undefined var, so it does NOT throw at
  runtime: shadow emits an `:eval-compile-warnings` REPL message, routes
  the analyzer warning text into the response `:err` field, and STILL
  pushes a `\"nil\"` into `:results` (see `repl_impl/do-repl` —
  `(repl-result repl-state nil)` runs after the warning is logged). A
  genuine compile error (`:eval-compile-error`) takes the same shape:
  `:err` carries the report, `:results` carries `\"nil\"`.

  A non-blank `:err` on the shadow outer map takes PRECEDENCE over the
  `:results` peek (which would otherwise read the `\"nil\"` shadow pushes
  and silently nil the form out as `{:ok? true :value nil}`, corrupting
  debugging — the agent reads the nil as a real value). It rejects with a
  structured `:rf.error/eval-cljs-compile-error` ex-info carrying the
  warning/error text, which `probe/err->result` surfaces verbatim as
  `{:ok? false :reason ... :err ...}`."
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
                ;; A populated :err on shadow's outer response is an
                ;; analyzer warning (unresolved symbol) or a compile
                ;; error. It MUST surface, even though shadow also pushed a
                ;; \"nil\" into :results: peeking :results first would swallow
                ;; the compile failure as a silent nil. Reject with a
                ;; structured reason so the caller's .catch projects a clear
                ;; `:ok? false` envelope instead of `:ok? true :value nil`.
                (and (map? outer) (not (str/blank? (str (:err outer)))))
                (js/Promise.reject
                  (ex-info (str "cljs eval compile error/warning: " (str/trim (str (:err outer))))
                           {:reason :rf.error/eval-cljs-compile-error
                            :err    (str/trim (str (:err outer)))
                            :hint   (str "the eval'd form failed to compile cleanly — most "
                                         "often an unresolved symbol (a misremembered fn / "
                                         "namespace) or a syntax/arity error. shadow surfaced "
                                         "the analyzer warning above; the form did NOT produce "
                                         "a real value. Fix the symbol/syntax and retry.")}))

                (and (map? outer) (vector? (:results outer)))
                (when-let [last-result (peek (:results outer))]
                  (read-edn-safe last-result))

                :else outer))))))))

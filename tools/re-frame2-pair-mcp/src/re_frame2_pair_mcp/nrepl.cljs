(ns re-frame2-pair-mcp.nrepl
  "Persistent nREPL client over a TCP socket.

  The server opens one socket lazily and reuses it across calls. A closed
  socket can reopen at the same endpoint; endpoint discovery replaces the
  connection when a cached port file changes. Normal calls therefore pay
  one bencode round-trip rather than reconnecting per operation.

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
  carry interleaved ops because nREPL multiplexes on `id`. Each pending
  request accumulates only its own response frames and resolves when its
  `:done` status arrives."
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

(defn read-port-file
  "Read + parse an nREPL port from a single file `path`. Returns an
  integer or nil (file absent / unreadable / non-numeric content).

  Public within the tool: this transport ns OWNS nREPL port-file parsing.
  The discovery cascade above uses it, and `server.cljs/ensure-connection!`
  calls it for the per-tool-call re-read of the cached port file (detecting
  a shadow restart that rewrote the port) — rather than carrying its own
  byte-for-byte copy. Connection lifecycle stays in server; the filesystem
  read/trim/parse primitive lives here."
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

(defn- read-ports-at-base
  "Resolve `port-file-candidates` against an absolute base directory and
  return every one that reads, in precedence order, as
  `{:port <int> :port-file <abs-path>}` — empty when none does, or when
  `base` is nil. Every readable file is kept because one a force-killed JVM
  left behind can precede the live one, so the cascade probes them in
  turn. Uses `node-path/join` so the candidates are platform-correct on
  Windows + POSIX without each call site building paths by string-concat.

  Each result carries its candidate path, not just the parsed port. The
  server caches the WINNING path so the per-tool-call re-read
  (`ensure-connection!`) checks the file that actually won the candidate
  scan, instead of deriving a fixed `.shadow-cljs/nrepl.port` that may
  not be the candidate that hit (which would then read as 'file
  disappeared' and force a needless reconnect)."
  [base]
  (if (seq base)
    (into []
          (keep (fn [rel]
                  (let [pf (node-path/join base rel)]
                    (when-let [port (read-port-file pf)]
                      {:port port :port-file pf}))))
          port-file-candidates)
    []))

(defn port-accepts?
  "Resolve true when a TCP connect to `port` on the host `connect!` uses
  succeeds within `shadow-discovery/probe-timeout-ms`; false on a refusal,
  any socket error, an out-of-range port or the timeout. Never rejects.

  Discovery asks this of every port file it reads, because a port file
  outlives a shadow-cljs JVM that ends without running its shutdown hook —
  a force kill or a crash — and a port that refuses the probe is one
  `connect!` cannot reach either."
  [port]
  (js/Promise.
    (fn [resolve _reject]
      (if-let [^js sock (try (net/createConnection
                               #js {:host shadow-discovery/default-host :port port})
                             (catch :default _ nil))]
        (let [settled? (atom false)
              timer    (atom nil)
              settle   (fn [live?]
                         (when (compare-and-set! settled? false true)
                           (js/clearTimeout @timer)
                           (.destroy sock)
                           (resolve live?)))]
          (reset! timer (js/setTimeout #(settle false) shadow-discovery/probe-timeout-ms))
          (j/call sock :once "connect" #(settle true))
          ;; Stays attached after settling: a reset that follows the destroy
          ;; must not surface as an unhandled socket error.
          (j/call sock :on "error" (fn [_] (settle false))))
        (resolve false)))))

(defn- first-live
  "Probe `candidates` (`{:port :port-file ...}` maps; nils are skipped) in
  order with `probe-fn`. Resolves `{:candidate <the first whose port
  accepts, or nil> :stale [<the :port-file of each refused one>]}`."
  [probe-fn candidates]
  (letfn [(step [[c & more] stale]
            (if (nil? c)
              (js/Promise.resolve {:candidate nil :stale stale})
              (-> (probe-fn (:port c))
                  (.then (fn [live?]
                           (if live?
                             {:candidate c :stale stale}
                             (step more (conj stale (:port-file c)))))))))]
    (step (remove nil? candidates) [])))

(defn- with-stale
  "Attach the port files discovery skipped as `:stale-port-files`, when it
  skipped any."
  [result stale]
  (if-let [s (seq (distinct stale))]
    (assoc result :stale-port-files (vec s))
    result))

(defn discover-port*
  "Full nREPL port-discovery cascade with every async step injected so
  tests can drive each branch without sockets or network. Production
  callers use [[discover-port]] / [[discover-port-with-roots]] which
  thread the real probes in.

  Returns a Promise resolving to a map
  `{:port <int|nil> :project-home <abs|nil> :port-file <abs|nil>
    :ambiguous <vec-of-candidates|nil> :stale-port-files <vec|absent>}`.
  The orchestrator (`server.cljs`) consumes the richer shape to drive the
  elicitation branch on ambiguity and to name the skipped files.

  ## Precedence (highest first)

    1. `--port-file <path>`   explicit, cwd-independent override.
                              Wins outright when it reads a port that
                              accepts a connect;
                              `:project-home` is the dirname of the port
                              file and `:port-file` is the EXACT path the
                              caller named — so the server caches it
                              verbatim rather than deriving a
                              `.shadow-cljs/nrepl.port` that may not exist.
                              An explicit file that is unreadable,
                              non-numeric or names a refused port falls
                              through to steps 2-5, so a stale port file
                              can't strand a live port reachable via
                              env / roots / HTTP / cwd.
    2. `$SHADOW_CLJS_NREPL_PORT` env var. `:project-home` left nil
                              (env-overridden discovery doesn't surface a
                              root); the per-tool-call port-file re-read
                              is skipped on this path.
    3. **MCP `roots/list` walk** — when `roots-discovery-fn`
                              is supplied (post-MCP-init, lazy on first
                              tool call), ask the client for its
                              workspace roots, walk each one for
                              `shadow-cljs.edn`, and test each standard
                              port-file candidate relative to that project.
                              One live candidate → silent attach. Multiple →
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
                              environments without the shadow HTTP API.
                              Candidates resolve against Node's process CWD
                              (an absolute base), so the WINNING candidate is
                              surfaced as an absolute `:port-file` just like
                              steps 1/3/4 — the per-tool-call re-read then
                              observes an ephemeral-port restart on this
                              branch too. `:project-home` stays nil (no root
                              is inferred; none is needed for restart
                              recovery).

  ## Why steps 3, 4, and 5 share the same candidate list

  shadow-cljs writes its nREPL port to one of three known relative paths
  inside the project root. Step 3 obtains roots from the MCP client;
  step 4 obtains the root from shadow itself; step 5 hopes cwd already
  IS the root. Sharing the candidate list keeps the contract symmetric
  across the three steps.

  ## Why every port file is probed

  shadow-cljs deletes its port files from a JVM shutdown hook, so a JVM
  that is force-killed or crashes leaves them naming a port nothing
  listens on. Every port file a step reads (1, 3, 4, 5) therefore counts
  only when `port-probe-fn` reports its port accepting a connect; a
  refused one is skipped, the cascade moves on, and the result names it
  in `:stale-port-files`. The env var (step 2) names no file and is taken
  as given.

  ## Why the probes are bounded

  See `shadow-discovery/probe-timeout-ms`. Each probe — the shadow HTTP
  probe and each port-file connect — never blocks for more than half a
  second; if any source is unreachable the cascade moves on. The
  `--port-file` and env-var escape hatches (steps 1 and 2) remain the
  deterministic overrides for exotic setups.

  ## Why injection rather than direct call

  The CLJS compiler resolves `shadow-discovery/discover-project-home` at
  callsite-emit time; CLJS's `with-redefs` swaps the *var binding* but
  the emitted JS for a direct multi-arity call dispatches through
  fn-object arity slots, not through the var. Injecting each probe makes
  the seams explicit and gives tests clean stub-points."
  [explicit-port-file http-port shadow-probe-fn roots-discovery-fn port-probe-fn]
  (let [;; Step 1 — explicit --port-file flag. It wins ONLY when it reads a
        ;; port that accepts a connect. An unreadable / non-numeric file (a
        ;; typo'd path) or one naming a refused port (left by a JVM that was
        ;; killed) falls through to env → roots → HTTP → cwd, so a stale
        ;; `--port-file` can't short-circuit a cascade that could find a
        ;; live port.
        ;;
        ;; The candidate carries the EXACT explicit path the caller named, so
        ;; the server caches it verbatim. Omitting `:port-file` would force
        ;; server.cljs to DERIVE `<project-home>/.shadow-cljs/nrepl.port`,
        ;; which for an explicit `target/shadow-cljs/nrepl.port` resolves to
        ;; a non-existent `target/shadow-cljs/.shadow-cljs/nrepl.port`; the
        ;; next ensure-connection! would read that derived path as missing
        ;; and force a needless reconnect + per-connection cache reset.
        explicit (when (seq explicit-port-file)
                   (when-let [port (read-port-file explicit-port-file)]
                     {:port         port
                      :project-home (node-path/dirname explicit-port-file)
                      :port-file    explicit-port-file}))]
    (-> (first-live port-probe-fn [explicit])
        (.then
          (fn [{:keys [candidate stale]}]
            (cond
              (some? candidate)
              candidate

              ;; Step 2 — $SHADOW_CLJS_NREPL_PORT.
              (some? (read-env-port))
              (with-stale {:port (read-env-port)} stale)

              :else
              ;; Steps 3 → 4 → 5 — chained async cascade.
              (-> (if roots-discovery-fn
                    (roots-discovery-fn)
                    (js/Promise.resolve {:status :error
                                         :error  {:reason :workspace-discovery-unsupported}}))
                  (.then
                    (fn [r]
                      (let [stale (into stale (:stale-port-files r))]
                        (case (:status r)
                          ;; The roots candidate carries whichever standard
                          ;; port file resolved under the discovered project.
                          ;; Thread that exact path through instead of
                          ;; deriving another candidate.
                          :one  (let [c (:candidate r)]
                                  (with-stale {:port (:port c) :project-home (:project-home c)
                                               :port-file (:port-file c)}
                                              stale))
                          :many (with-stale {:port nil :ambiguous (:candidates r)} stale)
                          :error
                          ;; Roots unavailable, or no live shadow in roots —
                          ;; try step 4 (HTTP probe), then step 5 (cwd scan).
                          (-> (shadow-probe-fn
                                shadow-discovery/default-host
                                (or http-port shadow-discovery/default-http-port))
                              (.then
                                (fn [project-home]
                                  (first-live
                                    port-probe-fn
                                    ;; Every readable file under each base, in
                                    ;; precedence order (target/shadow-cljs/nrepl.port,
                                    ;; .shadow-cljs/nrepl.port, .nrepl-port), so a
                                    ;; stale file cannot hide a live one behind it,
                                    ;; and the server caches the exact path that won.
                                    ;; Step 5's base is Node's process CWD, an
                                    ;; absolute base, so its :port-file is a
                                    ;; stable absolute path like steps 1/3/4 and
                                    ;; the per-tool-call re-read observes a
                                    ;; restart on this branch too; it surfaces no
                                    ;; :project-home (none is known, and none is
                                    ;; needed for restart recovery).
                                    (concat (map #(assoc % :project-home project-home)
                                                 (read-ports-at-base project-home))
                                            (read-ports-at-base (js/process.cwd))))))
                              (.then
                                (fn [found]
                                  (with-stale (or (:candidate found) {:port nil})
                                              (into stale (:stale found)))))))))))))))))

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
                   roots-discovery-fn
                   port-accepts?)))

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

  ## Single-flight connection ownership

  `:generation` is a monotonic counter bumped on every connect attempt (and
  by `close!`); it is the *authority token* for the connection. Socket
  handlers (`attach-handlers!`) capture the generation they were wired for
  and mutate conn state only while they still own it — a socket superseded
  by a newer connect (or by `close!`) reads `owns?` false and can neither
  fold bytes into the shared framing buffer nor flip `:closed?` on the live
  connection.

  `:connecting` is the in-flight-connect record `{:promise :socket :gen
  :resolve :reject}` (or nil when no connect is in progress). It makes
  `connect!` single-flight: the first caller opens the socket; every
  concurrent caller awaits the same `:promise`, so exactly one
  `net/createConnection` runs and exactly one socket is published per
  transition.

  `:probed-builds` is the set of build-ids for which the re-frame2-pair runtime
  preload has been confirmed live on this socket generation.
  Cleared by `close!` (a changed or vanished port file, or shutdown) and reborn empty
  whenever `ensure-connection!` builds a fresh conn for a new port — but
  NOT on a same-port socket reopen (see `connect!`). The
  `__re_frame2_pair_runtime` marker lives in the browser CLJS heap, which
  a JVM-side socket hiccup doesn't destroy, so the cache stays valid
  across a reopen; a page reload that DID destroy the marker leaves the
  JVM nREPL socket up, so a negative probe re-runs downstream anyway.

  `:resolved-build-id` is the session's default build — the one
  `discover-app` last resolved, or the last explicit `:build` any call
  passed (`wire/stick-build!`). Subsequent tool calls without an explicit
  `:build` arg default to it instead of the `SHADOW_CLJS_BUILD_ID`
  env-var fallback — so a pair-debug session need not re-pass the build
  on every call. Same invalidation lifecycle as `:probed-builds`: cleared
  by `close!` and by a fresh `ensure-connection!` conn when the port
  file changes or vanishes; PRESERVED across a same-port reopen and a
  page reload, neither of which touches the JVM-side conn.

  `:build-alias` is the forgiving-resolution cache:
  `{requested-keyword canonical-keyword}`. A `:build` arg that names a
  running build by a unique SUFFIX (`:machine-epochs` ⇒
  `:examples/machine-epochs`) resolves to the canonical running id the
  first time it's seen, and the alias is remembered so every later
  `arg-build` read returns the same canonical id without re-fetching
  `active-builds`. Same invalidation lifecycle as `:probed-builds`:
  cleared by `close!` and a fresh conn, preserved across a same-port
  reopen — a genuine build restart (which changes the running set) lands
  on a new port (fresh conn) or a vanished port file (`close!`), so a stale alias
  never survives a real build change."
  [port host]
  (atom {:port              port
         :host              (or host "127.0.0.1")
         :socket            nil
         :buf               nil
         :pending           {}
         :closed?           true
         :generation        0
         :connecting        nil
         :probed-builds     #{}
         :resolved-build-id nil
         :build-alias       {}}))

(defn- owns?
  "True when `gen` is still the conn's current connection generation — i.e.
  the socket/handler tagged with `gen` is the authoritative one. A socket
  superseded by a newer connect (or by `close!`, which bumps the
  generation) reads false and MUST NOT mutate shared conn state
  (framing buffer, `:socket`, `:closed?`, `:pending`)."
  [conn-atom gen]
  (= gen (:generation @conn-atom)))

(defn- destroy-socket!
  "Abandon a superseded / orphaned socket so it can never feed the current
  connection. Prefers `.destroy` (drops the connection immediately); falls
  back to `.end` for minimal fakes. Best-effort — teardown of a half-open
  socket may throw, which we swallow."
  [^js sock]
  (try
    (cond
      (fn? (j/get sock :destroy)) (j/call sock :destroy)
      (fn? (j/get sock :end))     (j/call sock :end)
      :else                       nil)
    (catch :default _ nil)))

(defn- connection-closed-error
  "The rejection every op pending on a closed connection receives. The op
  may have run in the browser and lost only its reply, so the message
  calls its outcome unknown and sends a write to the epoch history before
  any retry. The discriminator rides in `:rf.error/id`, the slot both
  error relays carry unchanged (`server.cljs` `invoke-and-guard` replaces
  `:reason` with `:handler-threw`); `:reason` restates it."
  []
  (ex-info (str "nREPL connection closed while the op was in flight (shadow-cljs "
                "stopped or restarted), so its outcome is unknown. The next call "
                "reconnects: retry a read; for a write, read the epoch history "
                "(trace-window) before retrying it"
                " [" :rf.error/pair-mcp-nrepl-connection-closed "]")
           {:rf.error/id :rf.error/pair-mcp-nrepl-connection-closed
            :reason      :rf.error/pair-mcp-nrepl-connection-closed}))

(defn- fail-pending!
  "Reject every op still waiting on a reply on this conn. A closed socket
  answers none of them, so each would otherwise wait out its own deadline
  and report a timeout instead of the closed connection."
  [conn-atom]
  (let [pending (:pending @conn-atom)]
    (swap! conn-atom assoc :pending {})
    (doseq [{:keys [fail!]} (vals pending)]
      (fail! (connection-closed-error)))))

(defn attach-handlers!
  "Wire up `data` / `error` / `close` on the freshly-connected socket for
  connection generation `gen`. Resolves pending requests on matching
  `:done` status; logs errors; marks the conn closed on disconnect.

  ## Ownership-aware — only the authoritative generation mutates state

  Every callback first checks `owns?`: a socket superseded by a newer
  connect (or torn down by `close!`, which bumps the generation) reads
  false and does nothing. This is what makes the connection single-flight
  safe end-to-end — a losing/stale socket can neither fold its bytes into
  the shared framing buffer (so frames from two socket generations are
  never concatenated) nor flip `:closed?` on the live connection.

  The `data` handler folds the incoming chunk into the buffer AND
  splits off any complete frames in a single `swap!` — two swaps
  would leave a window where a second `data` callback (or a
  Buffer.concat racing) could observe the freshly-accumulated bytes
  but not yet the trimmed trailer, double-decoding the same frame.
  One atomic swap leaves no such window.

  The 2-arity defaults `gen` to the conn's CURRENT generation — the shape
  the transport tests attach with (no reconnect in play). Production
  `connect!` passes the generation it captured for the socket.

  Public (not `defn-`) so `nrepl_test.cljs` can drive the data-folding
  + pending-id dispatch with a fake socket — feeding synthetic chunks
  and asserting pending handlers fire — without opening a real TCP
  socket. The fake socket need only implement an `on`
  method that records the callback per event name; see the test's
  `fake-socket` helper. This mirrors the `decode-all-frames`
  public-for-test precedent above."
  ([conn-atom socket] (attach-handlers! conn-atom socket (:generation @conn-atom)))
  ([conn-atom ^js socket gen]
   (j/call socket :on "data"
     (fn [chunk]
       ;; A superseded generation's late `data` must NOT touch the shared
       ;; framing buffer — otherwise bytes from two TCP streams concatenate
       ;; into one bencode buffer and corrupt frame assembly.
       (when (owns? conn-atom gen)
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
             ;; Each pending entry is `send-op!`'s `{:on-frame :fail!}`;
             ;; `:on-frame` folds the frame into that op's result.
             (when-let [{:keys [on-frame]} (get (:pending @conn-atom) (j/get frame "id"))]
               (on-frame frame)))))))
   (j/call socket :on "error"
     (fn [err]
       ;; A stale socket's error must not mark the LIVE connection closed.
       (when (owns? conn-atom gen)
         (log! "socket error:" (.-message err))
         (swap! conn-atom assoc :closed? true)
         (fail-pending! conn-atom))))
   (j/call socket :on "close"
     (fn [_]
       ;; Likewise a stale socket's late close must not flip the live conn.
       (when (owns? conn-atom gen)
         (swap! conn-atom assoc :closed? true)
         (fail-pending! conn-atom))))))

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

    - `close!` (a changed or vanished port file, or shutdown) clears all three.
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
  `:closed?` — untouched). A negative probe re-runs downstream anyway.

  ## Single-flight

  Concurrent callers (two simultaneous first ops, say) must NOT each open a
  socket. The first caller into the `:else` branch claims the `:connecting`
  slot and bumps `:generation`; every concurrent caller returns that same
  in-flight Promise. So exactly one `net/createConnection` runs and exactly
  one socket is published per transition; nREPL then multiplexes the
  concurrent ops by id over that one socket. A candidate that comes up after
  it has been superseded (a newer connect, or a `close!`) is destroyed
  rather than published."
  [conn-atom]
  (let [{:keys [socket closed? connecting]} @conn-atom]
    (cond
      ;; Fast path — a healthy socket is already open.
      (and socket (not closed?))
      (js/Promise.resolve conn-atom)

      ;; Single-flight — a connect for THIS conn is already in progress;
      ;; await the same Promise instead of opening a second socket.
      (some? connecting)
      (:promise connecting)

      :else
      (let [gen  (inc (:generation @conn-atom))
            d    #js {}
            p    (js/Promise. (fn [resolve reject]
                                (set! (.-resolve d) resolve)
                                (set! (.-reject d) reject)))
            {:keys [host port]} @conn-atom
            sock (net/createConnection #js {:host host :port port})]
        ;; Claim the in-flight slot + bump the generation BEFORE wiring the
        ;; socket callbacks, so a late `connect`/`error` can tell whether it
        ;; is still the authoritative generation. Held synchronously, so a
        ;; concurrent `connect!` in the same tick sees `:connecting` and
        ;; awaits `p` rather than opening its own socket.
        (swap! conn-atom assoc :generation gen
               :connecting {:promise p :socket sock :gen gen
                            :resolve (.-resolve d) :reject (.-reject d)})
        (j/call sock :on "connect"
          (fn []
            (if (owns? conn-atom gen)
              (do
                ;; Transport-only publish: swap in the live socket, clear the
                ;; closed flag + in-flight slot, and reset the framing buffer.
                ;; The build-id caches are deliberately PRESERVED across a
                ;; same-port reopen — see the fn docstring for why and for
                ;; where the genuine-different-build reset lives.
                (swap! conn-atom assoc :socket sock :closed? false
                       :buf (js/Buffer.alloc 0) :connecting nil)
                (attach-handlers! conn-atom sock gen)
                ((.-resolve d) conn-atom))
              ;; Superseded before we came up (a newer connect or a `close!`
              ;; bumped the generation). Abandon this socket so it never
              ;; feeds the current framing buffer, and settle our waiter onto
              ;; the authoritative conn (send-op! re-reads `:socket`, so a
              ;; nil-socket after close! reconnects/errors cleanly).
              (do (destroy-socket! sock)
                  ((.-resolve d) conn-atom)))))
        (j/call sock :once "error"
          (fn [err]
            (when (owns? conn-atom gen)
              (swap! conn-atom assoc :closed? true :connecting nil))
            ((.-reject d) err)))
        p))))

(defn close!
  "Close the persistent socket. Idempotent. Drops the per-socket probe
  cache (`:probed-builds`) so a fresh connect re-probes the preload.
  Also drops `:resolved-build-id` — the cached default for
  tool calls without an explicit `:build` arg — and `:build-alias`,
  the forgiving suffix→canonical resolution cache — so a
  reconnect doesn't carry a stale build-id from the previous session.

  ## Teardown cannot be undone by a late candidate

  `close!` bumps `:generation`, so any still-attached socket handlers AND
  any in-flight connect's late `connect` callback read as superseded and
  refuse to mutate conn state — a candidate that comes up after teardown is
  destroyed, never published. It also tears down an in-flight candidate
  socket and rejects its waiter so a caller blocked on the connect Promise
  settles promptly rather than hanging."
  [conn-atom]
  (let [{:keys [socket connecting]} @conn-atom]
    (fail-pending! conn-atom)
    (when socket
      (try (.end ^js socket) (catch :default _ nil)))
    ;; Tear down an in-flight candidate so it can't come up behind us.
    (when-let [^js insock (:socket connecting)]
      (destroy-socket! insock))
    (swap! conn-atom (fn [c]
                       (-> c
                           (assoc :socket nil :closed? true :pending {}
                                  :connecting nil
                                  :probed-builds #{} :resolved-build-id nil
                                  :build-alias {})
                           (update :generation (fnil inc 0)))))
    ;; Settle any waiter blocked on the in-flight connect (it will re-read a
    ;; nil `:socket` and reconnect on the next call).
    (when-let [rej (:reject connecting)]
      (rej (js/Error. "nREPL connection closed during connect — retry to reconnect")))))

;; ---------------------------------------------------------------------------
;; Op send / receive — multiplex by request id.
;; ---------------------------------------------------------------------------

(defn- new-id [] (str (random-uuid)))

(def default-timeout-ms
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
                (swap! conn-atom assoc-in [:pending id]
                       {:on-frame on-frame
                        :fail!    (fn [err] (js/clearTimeout timer) (reject err))})
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
;; nREPL → CLJS eval bridge.
;; ---------------------------------------------------------------------------

(defn jvm-eval
  "Evaluate a Clojure form on the JVM side of nREPL. Returns a Promise
  resolving to a combined response map. Optional `opts` passes
  through to [[send-op!]] (e.g. `{:timeout-ms 60000}`)."
  ([conn-atom form-str] (jvm-eval conn-atom form-str nil))
  ([conn-atom form-str opts]
   (send-op! conn-atom {"op" "eval" "code" form-str} opts)))

(defn build-id-literal
  "Render `build-id` as the keyword literal spliced into a JVM-side form
  (`cljs-eval` below, the freshness read in `tools/freshness`), or nil
  when that literal would not read back as exactly one keyword.

  The belt at the JVM sink. The JVM form is evaluated as
  Clojure on the developer's shadow-cljs process, so a build id that
  PRINTS as more than one token — a keyword minted from `\"app (do (evil))
  #_\"` prints exactly that — is arbitrary JVM code. The id grammar at the
  argument boundary (`tools.args/->id-keyword`) keeps such ids from being
  minted; this check holds regardless of how an id arrived: the literal
  must read back as a keyword that prints as the literal itself."
  [build-id]
  (let [lit (if (keyword? build-id) (str build-id) (str ":" (name (or build-id :app))))
        v   (try (edn/read-string lit) (catch :default _ nil))]
    (when (and (keyword? v) (= lit (str v)))
      lit)))

(defn cljs-eval
  "Evaluate a ClojureScript form through shadow-cljs's `cljs-eval` API.
  Returns a Promise resolving to a combined response map. Optional
  `opts` (e.g. `{:timeout-ms 60000}`) tunes the per-op deadline.

  Rejects — without sending anything — when `build-id` does not render as
  a single keyword literal (`build-id-literal`)."
  ([conn-atom build-id form-str] (cljs-eval conn-atom build-id form-str nil))
  ([conn-atom build-id form-str opts]
   (if-let [build-pr (build-id-literal build-id)]
     (let [;; pr-str CLJS form: use double-quote-escaped string literal.
           code-pr   (pr-str form-str)
           wrapped   (str "(shadow.cljs.devtools.api/cljs-eval "
                          build-pr " " code-pr " {})")]
       (jvm-eval conn-atom wrapped opts))
     (js/Promise.reject
       ;; Canonical thrown-error shape (Spec 009 §The thrown-error shape):
       ;; a human sentence plus the trailing `[:rf.error/<id>]` token.
       (ex-info (str "Refusing to evaluate against build id " (pr-str build-id)
                     ": it does not print as a single keyword, so splicing it"
                     " into the JVM form would run it as code."
                     " [" :rf.error/pair-mcp-malformed-build-id "]")
                {:rf.error/id :rf.error/pair-mcp-malformed-build-id
                 :reason      :rf.error/pair-mcp-malformed-build-id
                 :build       (pr-str build-id)})))))

(defn- inert-tag
  "Reader `:default` for a tag this process has no reader for: keep the
  value as an inert `tagged-literal`, tag and form intact, so it
  re-prints as the EDN it arrived as. An app prints values under tags
  only its own reader registry knows (a date library's `#instant`, say),
  and this Node process is not that app; failing the read would discard
  the whole reply around the one value. The reader-eval tag `#=` is
  refused rather than kept."
  [tag form]
  (when (= '= tag)
    (throw (js/Error. "Reader eval (#=) is refused.")))
  (tagged-literal tag form))

(defn read-edn-safe
  "Best-effort EDN read of the nREPL value string. The standard readers
  apply; an unknown tag decodes as inert tagged data (`inert-tag`, per
  read, so the global reader registry is untouched). On parse failure we
  return the raw string so the caller can decide what to do with the
  unparseable shape — but we log to stderr first because a silent
  drop-back hides genuine wire-shape regressions (a runtime that
  shipped malformed EDN gets surfaced by inspection of the dev
  console, not by a mute branch)."
  [s]
  (try
    (edn/read-string {:default inert-tag} s)
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

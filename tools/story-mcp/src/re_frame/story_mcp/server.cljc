(ns re-frame.story-mcp.server
  "MCP server entry-point. Runs the JSON-RPC dispatch loop over stdin /
  stdout per the MCP stdio transport spec (newline-delimited JSON,
  UTF-8, no embedded newlines, stderr free-form for logging).

  ## Lifecycle (per spec/2025-06-18/basic/lifecycle)

  1. Server starts; reads from stdin in a loop.
  2. First message must be `initialize` — we respond with our protocol
     version + capabilities + serverInfo. The session is marked
     `initialized` the moment that response is built (see the
     `:initialized?` relaxation below).
  3. Client sends `notifications/initialized` (no response). Accepted
     but NOT required — see the relaxation note.
  4. Client sends `tools/list`, `tools/call`, etc.; we dispatch.
  5. Shutdown: client closes stdin → readLine returns nil → we exit.

  ## Pre-initialize state enforcement

  The lifecycle prose above is a CONTRACT, not just documentation. The
  dispatcher is stateful: each session carries a `lifecycle-state` atom
  (created per `run-loop!` invocation) whose `:initialized?` flag gates
  the method surface.

  BEFORE a successful `initialize`, the ONLY messages accepted are:

  - `initialize`  — the handshake itself.
  - `ping`        — a protocol-level liveness probe the MCP spec lists
                    under Utilities and which carries no session state;
                    answering it pre-init is safe and lets a host health-
                    check a freshly-spawned server.
  - any NOTIFICATION (no `:id`) — silently ignored per JSON-RPC, exactly
                    as in the initialized state. (`notifications/initialized`
                    is the canonical one.)

  Any OTHER request before initialization (`tools/list`, `tools/call`,
  `shutdown`, an unknown method) returns `-32600 invalid-request` with a
  message naming the violation — a malformed or hostile client cannot
  enumerate or invoke tools before completing the handshake.

  ## `notifications/initialized` relaxation (deliberate, tested)

  The MCP lifecycle has the client send `notifications/initialized`
  AFTER the `initialize` response to confirm the handshake. We do NOT
  gate the tool surface on receiving it: the session flips to
  `:initialized? true` the moment the `initialize` response is built.
  This matches the reference SDK posture (the MCP Python SDK aligned to
  the TypeScript SDK precisely to mark the server initialized on the
  `initialize` RESPONSE rather than waiting for the notification —
  otherwise a client that pipelines `initialize` + `tools/list` races a
  warning/refusal). The notification is still accepted as a no-op so a
  well-behaved client sees no error. This relaxation is pinned by
  `dispatch-allows-tools-immediately-after-initialize` +
  `run-loop-rejects-pre-initialize-tool-calls` in `tools_test.clj`.

  ## Dispatch table

  Implemented methods:

  - `initialize`           — handshake
  - `notifications/initialized` — accepted; no response (notification)
  - `tools/list`           — return the tool registry descriptors
  - `tools/call`           — invoke a named tool
  - `ping`                 — protocol-level liveness probe; empty result
  - `shutdown`             — graceful exit hint (not in 2025-06-18 spec
                             but several agent hosts emit it; we accept
                             and respond before letting the stdin EOF
                             close us)

  Unknown methods → `-32601 method-not-found`.

  ## Why a separate dispatch ns

  Keeps the wire framing (`protocol.cljc`) isolated from the method
  semantics here, and the method semantics isolated from the tool
  implementations (`tools/`). The split lets tests cover the wire
  layer without booting the full Story registrar (see
  `protocol_test.clj`)."
  (:gen-class)
  (:require [clojure.string :as str]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.protocol :as proto]
            [re-frame.story-mcp.tools.wire-pipeline :as wire-pipeline]
            [re-frame.story-mcp.tools.registry :as registry]))

;; ---- logging --------------------------------------------------------------
;;
;; Per MCP stdio transport: stderr is free-form for logging. We use a
;; tiny `log!` fn instead of taking on a logging dep — keeps the
;; classpath lean. The format is deliberately simple so an agent host
;; that captures stderr sees one log line per call.

(defn log!
  "Write a log line to stderr. Stays out of stdout — per MCP stdio rules,
  stdout is reserved for valid MCP messages only."
  [& parts]
  (binding [*out* *err*]
    (println (str "[re-frame2-story-mcp] " (str/join " " (map str parts))))
    (flush)))

;; ---- lifecycle state ------------------------------------------------------
;;
;; The dispatcher is stateful: each session (one `run-loop!` invocation)
;; owns a lifecycle-state atom; the `:initialized?` flag gates the method
;; surface so a client cannot enumerate/call tools before completing the
;; `initialize` handshake.

(defn new-lifecycle-state
  "Fresh per-session lifecycle state for the stateful dispatcher. The
  `:initialized?` flag starts `false`; `handle-initialize` flips it true
  on a successful handshake. Public for tests that want to drive
  `dispatch` against an explicit lifecycle posture."
  []
  (atom {:initialized? false}))

(def ^:private lifecycle-open-methods
  "The two REQUEST methods accepted before `initialize` succeeds:
  `initialize` (the handshake) + `ping` (a stateless liveness probe per
  MCP §Utilities). Notifications are handled separately (always accepted,
  always no-response) — they are not in this set because the gate runs
  only on requests."
  #{"initialize" "ping"})

;; ---- handshake ------------------------------------------------------------

(defn- handle-initialize
  "Build the `initialize` response and mark the session initialized.
  Unconditionally advertises the server's pinned protocol-version
  (`config/protocol-version`) and ignores the client's requested
  `protocolVersion` entirely — `_params` is never read. This is
  spec-acceptable: per the MCP lifecycle version-negotiation rule the
  server replies with a version it supports and the client decides
  whether to disconnect on a mismatch (we never inspect or echo the
  client's version).

  Flips `state`'s `:initialized?` to true as a side effect —
  the session is ready the moment the `initialize` response is built (the
  reference-SDK relaxation; we do NOT wait for
  `notifications/initialized`). A repeated `initialize` is idempotent on
  the flag."
  [state id _params]
  (swap! state assoc :initialized? true)
  (proto/response id
                  {:protocolVersion config/protocol-version
                   :capabilities    {:tools {:listChanged false}}
                   :serverInfo      {:name    config/server-name
                                     :version (config/read-version)}
                   :instructions    (str "Story MCP agent surface. Call `tools/list` for the registry, "
                                         "then `tools/call` with the named tool + arguments. "
                                         "See `get-story-instructions` for Story's authoring conventions.")}))

;; ---- tools/list -----------------------------------------------------------

(defn- handle-tools-list
  "Return the tool registry descriptors. We don't paginate — the
  registry is small enough (twenty tools at Stage 7) that a single
  response is fine."
  [id _params]
  (proto/response id {:tools (registry/tool-descriptors)}))

;; ---- tools/call -----------------------------------------------------------

(defn- handle-tools-call
  "Invoke a tool. `params` shape: `{:name <string> :arguments <map>}`.

  `arguments` is the JSON-RPC params container; per MCP it
  MUST be a structured object (absent ⇒ no args). A scalar / array /
  string `arguments` is a params-SHAPE failure and surfaces as
  `-32602 invalid-params` here, at the wire boundary — NOT as a
  `-32603 internal-error`. This guard keeps a non-map `arguments` from
  reaching `normalize-frame` (which only normalises a map) and
  `invoke-tool`'s per-tool arg-key check (`(keys args)`), which would
  throw on the non-map and surface as a misleading `-32603` server fault.
  Per-ARGUMENT validation (a wrong-typed / missing field WITHIN a
  well-formed arguments map) stays tool-level (`isError: true`); this
  guard is only about the container's shape."
  [id params]
  (let [tool-name (:name params)
        arguments (:arguments params)]
    (cond
      (not (string? tool-name))
      (proto/invalid-params id "tools/call requires `name` (string)")

      (not (or (nil? arguments) (map? arguments)))
      (proto/invalid-params
        id (str "tools/call `arguments` must be an object (or omitted); got "
                (cond (string? arguments)     "a string"
                      (sequential? arguments) "an array"
                      (number? arguments)     "a number"
                      (boolean? arguments)    "a boolean"
                      :else                   "a non-object scalar")))

      :else
      (if-let [result (wire-pipeline/invoke-tool tool-name arguments)]
        (proto/response id result)
        (proto/method-not-found id (str "tools/call name=" tool-name))))))

;; ---- ping / shutdown ------------------------------------------------------

(defn- handle-ping
  "Empty success result. Per MCP §Utilities/ping."
  [id _params]
  (proto/response id {}))

(defn- handle-shutdown
  "Some agent hosts send `shutdown` before closing stdin. The
  2025-06-18 spec relies on stream close instead, but we accept and
  respond so well-behaved clients don't see a timeout."
  [id _params]
  (proto/response id {}))

;; ---- dispatcher -----------------------------------------------------------

(defn dispatch
  "Dispatch a parsed JSON-RPC message to the appropriate handler.
  Returns a response envelope, or nil if the input is a notification
  (notifications get no response per JSON-RPC 2.0 §4.1).

  Two arities:

  - `(dispatch state message)` — the stateful, lifecycle-aware form
    `run-loop!` drives. `state` is a `new-lifecycle-state` atom; before
    a successful `initialize` only `initialize` / `ping` requests (and
    any notification) are accepted, everything else returns
    `-32600 invalid-request`.

  - `(dispatch message)` — backward-compatible convenience that runs
    the message against a FRESH, ALREADY-INITIALIZED session. Method-
    semantics tests use this form: they exercise what a handler returns
    GIVEN a completed handshake, not the lifecycle gate (which the
    dedicated pre-initialize deftests drive via the 2-arity form).

  Public for tests."
  ([message]
   (dispatch (atom {:initialized? true}) message))
  ([state message]
   (cond
     (not (proto/valid-envelope? message))
     (proto/invalid-request (:id message) "Invalid JSON-RPC envelope")

     ;; Notifications — no response. Per the spec a notification is a
     ;; request without an `id`. We accept the canonical handshake-
     ;; completion notification (`notifications/initialized`) and any
     ;; other notification silently — the absence of `:id` is the
     ;; complete dispatch rule (`proto/notification?` is its single
     ;; home). No defensive arm needed in the method
     ;; `case` below. Notifications are accepted in EVERY lifecycle
     ;; posture (the gate below runs only on requests).
     (proto/notification? message)
     nil

     ;; Pre-initialize gate. Before the handshake
     ;; completes, only `initialize` + `ping` requests are legal; any
     ;; other request (tools/list, tools/call, shutdown, unknown) is a
     ;; protocol violation — a hostile/malformed client must not be able
     ;; to enumerate or invoke tools before initializing.
     (and (not (:initialized? @state))
          (not (contains? lifecycle-open-methods (:method message))))
     (proto/invalid-request
       (:id message)
       (str "Method '" (:method message) "' is not allowed before the "
            "`initialize` handshake completes (MCP lifecycle). Send "
            "`initialize` first."))

     :else
     (let [{:keys [id method params]} message]
       (case method
         "initialize"                     (handle-initialize state id params)
         "tools/list"                     (handle-tools-list id params)
         "tools/call"                     (handle-tools-call id params)
         "ping"                           (handle-ping id params)
         "shutdown"                       (handle-shutdown id params)
         (proto/method-not-found id method))))))

(defn- handle-frame!
  "Process one parsed frame: dispatch (against the session `state`),
  write the response (if any) to the writer. Catches handler-side throws
  and converts them to internal-error responses so the loop survives.

  Public for tests."
  [state ^java.io.Writer writer message]
  (try
    (when-let [resp (dispatch state message)]
      (proto/write-frame! writer resp))
    (catch Throwable e
      (log! "handler threw:" (ex-message e))
      (try
        (proto/write-frame! writer
                            (proto/internal-error (:id message)
                                                  (str "Server fault: " (ex-message e))
                                                  {:exception (.getName (class e))}))
        (catch Throwable e2
          (log! "write also threw:" (ex-message e2)))))))

;; ---- main loop ------------------------------------------------------------

(defn run-loop!
  "Drive the read → dispatch → write loop against `reader` / `writer`.
  Returns when the reader hits EOF (stdin closed). Exposed for tests
  that wire an in-memory pipe instead of stdin/stdout.

  Each iteration:
    1. Read one newline-delimited frame.
    2. Parse JSON. Parse failures yield a `-32700` error response with
       `id: null` (we have no parsed id to echo).
    3. Dispatch. Method-level errors are responses; tool-level errors
       are wrapped in the `tools/call` result with `isError: true`."
  [^java.io.BufferedReader reader ^java.io.Writer writer]
  ;; One lifecycle-state atom per session. The pre-initialize gate in
  ;; `dispatch` reads it; `handle-initialize` flips it. A new loop =
  ;; a fresh handshake.
  (let [state (new-lifecycle-state)]
    (loop []
      (let [frame (try
                    (proto/read-frame reader)
                    (catch Throwable e
                      (log! "parse error:" (ex-message e))
                      (try
                        (proto/write-frame! writer (proto/parse-error))
                        (catch Throwable e2
                          (log! "write of parse-error response failed:" (ex-message e2))))
                      ::recover))]
        (cond
          ;; `proto/read-frame` returns `proto/eof-sentinel` when stdin
          ;; closes. The loop exits.
          (= proto/eof-sentinel frame) :eof
          ;; Recovery from a parse-error: we already wrote the error
          ;; response; loop to the next frame.
          (= ::recover frame)          (recur)
          :else
          (do
            (handle-frame! state writer frame)
            (recur)))))))

;; ---- -main ----------------------------------------------------------------

(defn- parse-args
  "Parse CLI args. Minimal — no third-party CLI lib required at Stage
  7. Supported flags:

  - `--allow-writes` — presence opens the write surface. There is no
    `=true` / `=false` variant; a flag is either present or absent. A
    presence-only flag avoids the footgun of a `=false` form, where an
    agent host scripting it would expect the gate to close but the
    parser would record `false` and propagate it through
    `apply-config!`.
  - `--allow-sensitive-reads` — presence opens the sensitive-read gate.
    Symmetric with `--allow-writes`: operator-only opt-in,
    no `=value` variant. Default off; when off, the wire-egress
    scrubbers silently ignore any `:include-sensitive true` per-call
    arg and `tools/list` omits the slot from advertised input schemas.

  Unknown flags are logged and ignored — the MCP spec doesn't define
  CLI conventions, so being permissive here is correct.

  Returns a config map."
  [argv]
  (loop [args argv
         cfg  {}]
    (if-let [a (first args)]
      (case a
        "--allow-writes"          (recur (rest args) (assoc cfg :allow-writes? true))
        "--allow-sensitive-reads" (recur (rest args) (assoc cfg :allow-sensitive-reads? true))
        (do (log! "unknown CLI flag:" a)
            (recur (rest args) cfg)))
      cfg)))

(defn boot!
  "Apply boot configuration + install Story's canonical vocabulary.
  Idempotent; exposed for tests that want to set up the server without
  taking over stdin/stdout."
  ([] (boot! {}))
  ([cli-cfg]
   (let [cfg (merge (config/read-boot-config) cli-cfg)]
     (config/apply-config! cfg)
     (story/install-canonical-vocabulary!)
     (log! "booted; allow-writes?=" (config/writes-allowed?)
           " protocol=" config/protocol-version
           " server=" config/server-name)
     ;; One-line boot signal mirroring re-frame2-pair-mcp's
     ;; `eval-cljs:` line. Operators inspecting the launch stderr can
     ;; confirm the posture at a glance.
     (log! "Sensitive reads:"
           (if (config/sensitive-reads-allowed?)
             "allowed (--allow-sensitive-reads)"
             "gated (default; pass --allow-sensitive-reads to opt in)"))
     cfg)))

(defn run-stdio!
  "Wire stdin / stdout to `run-loop!` and drive until EOF. Extracted
  from `-main` so a test can exercise the full stdio assembly without
  reflecting on private internals — but tests SHOULD prefer
  `run-loop!` with in-memory streams; this helper is exposed for the
  one assertion that wants to verify the stdin-as-default-reader
  contract."
  []
  (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))
        writer (java.io.OutputStreamWriter. System/out "UTF-8")]
    (run-loop! reader writer)
    (log! "stdin closed; exiting")))

(defn -main
  "Entry point. Boots, then runs the stdio JSON-RPC loop until stdin
  closes. Per IMPL-SPEC §7.1 the agent host (Claude Code / Cursor /
  Copilot) launches this as a subprocess and terminates it by closing
  stdin (or SIGTERM after a timeout)."
  [& argv]
  (boot! (parse-args argv))
  (run-stdio!))

(ns re-frame.story-mcp.server
  "MCP server entry-point. Runs the JSON-RPC dispatch loop over stdin /
  stdout per the MCP stdio transport spec (newline-delimited JSON,
  UTF-8, no embedded newlines, stderr free-form for logging).

  ## Lifecycle (per spec/2025-06-18/basic/lifecycle)

  1. Server starts; reads from stdin in a loop.
  2. First message must be `initialize` — we respond with our protocol
     version + capabilities + serverInfo.
  3. Client sends `notifications/initialized` (no response).
  4. Client sends `tools/list`, `tools/call`, etc.; we dispatch.
  5. Shutdown: client closes stdin → readLine returns nil → we exit.

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
            [re-frame.story-mcp.tools.cap :as cap]
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

;; ---- handshake ------------------------------------------------------------

(defn- handle-initialize
  "Build the `initialize` response. Echoes the client's `protocolVersion`
  when we support it (only `2025-06-18` at Stage 7 land); otherwise we
  reply with our supported version and let the client decide whether to
  disconnect per the spec's version-negotiation rule."
  [id _params]
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
  registry is small enough (sixteen tools at Stage 7) that a single
  response is fine."
  [id _params]
  (proto/response id {:tools (registry/tool-descriptors)}))

;; ---- tools/call -----------------------------------------------------------

(defn- handle-tools-call
  "Invoke a tool. `params` shape: `{:name <string> :arguments <map>}`."
  [id params]
  (let [tool-name (:name params)
        arguments (:arguments params)]
    (cond
      (not (string? tool-name))
      (proto/invalid-params id "tools/call requires `name` (string)")

      :else
      (if-let [result (cap/invoke-tool tool-name arguments)]
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

  Public for tests."
  [message]
  (cond
    (not (proto/valid-envelope? message))
    (proto/invalid-request (:id message) "Invalid JSON-RPC envelope")

    ;; Notifications — no response. Per the spec a notification is a
    ;; request without an `id`. We accept the canonical handshake-
    ;; completion notification (`notifications/initialized`) and any
    ;; other notification silently — the absence of `:id` is the
    ;; complete dispatch rule. No defensive arm needed in the
    ;; method `case` below.
    (not (contains? message :id))
    nil

    :else
    (let [{:keys [id method params]} message]
      (case method
        "initialize"                     (handle-initialize id params)
        "tools/list"                     (handle-tools-list id params)
        "tools/call"                     (handle-tools-call id params)
        "ping"                           (handle-ping id params)
        "shutdown"                       (handle-shutdown id params)
        (proto/method-not-found id method)))))

(defn- handle-frame!
  "Process one parsed frame: dispatch, write the response (if any) to
  the writer. Catches handler-side throws and converts them to
  internal-error responses so the loop survives.

  Public for tests."
  [^java.io.Writer writer message]
  (try
    (when-let [resp (dispatch message)]
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
          (handle-frame! writer frame)
          (recur))))))

;; ---- -main ----------------------------------------------------------------

(defn- parse-args
  "Parse CLI args. Minimal — no third-party CLI lib required at Stage
  7. Supported flags:

  - `--allow-writes` — presence opens the write surface. There is no
    `=true` / `=false` variant; a flag is either present or absent.
    The earlier `--allow-writes=false` form was a footgun (an agent
    host scripting it would expect the gate to close, but the parser
    accepted-and-recorded `false` which then propagated through
    `apply-config!` only when the absent default was already
    `false`).

  Unknown flags are logged and ignored — the MCP spec doesn't define
  CLI conventions, so being permissive here is correct.

  Returns a config map."
  [argv]
  (loop [args argv
         cfg  {}]
    (if-let [a (first args)]
      (case a
        "--allow-writes" (recur (rest args) (assoc cfg :allow-writes? true))
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

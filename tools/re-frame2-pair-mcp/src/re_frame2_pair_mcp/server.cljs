(ns re-frame2-pair-mcp.server
  "MCP server entry-point. Wires the npm `@modelcontextprotocol/sdk`
  stdio transport to the tool dispatcher in `tools.cljs`, against a
  single persistent nREPL connection (`nrepl.cljs`).

  ## Lifecycle

  1. boot: read nREPL port from the `--port-file <path>` flag, the
     `SHADOW_CLJS_NREPL_PORT` env var, or the standard cwd-relative
     port-files (in that precedence — see `nrepl/read-port-from-fs`).
     Open the persistent socket lazily on first tool call (so the
     server starts cleanly even before shadow-cljs is running — the
     first tool that needs the socket gets a structured error).
  2. `initialize`: standard MCP handshake.
  3. `tools/list`: returns the twelve tool descriptors (the seven
     bash-shim-overlap ops `discover-app` / `eval-cljs` / `dispatch` /
     `trace-window` / `watch-epochs` / `tail-build` / `snapshot`, plus
     `get-path` direct-read, the streaming triad `subscribe` /
     `unsubscribe` / `list-subscriptions`, and the
     `get-re-frame2-pair-instructions` agent-onboarding tool).
  4. `tools/call`: dispatch to `tools.cljs`. Each call ensures the
     in-browser runtime is injected via the sentinel probe.
  5. stdin EOF: shut down cleanly.

  ## Why low-level Server, not McpServer

  The SDK's high-level `McpServer` registers tools at construction time
  with a schema-validation layer. We want explicit control over the
  request handlers (parallel to the JVM port at `tools/story-mcp/`),
  so we use the low-level `Server` + `setRequestHandler` API."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.resource-controls :as resource]
            ["@modelcontextprotocol/sdk/server/index.js" :as mcp-server]
            ["@modelcontextprotocol/sdk/server/stdio.js" :as mcp-stdio]
            ["@modelcontextprotocol/sdk/types.js" :as mcp-types]))

(def ^:const server-name    "re-frame2-pair-mcp")
(def ^:const server-version "0.1.0")

(defn log!
  "Stderr logger — stdout is reserved for MCP messages."
  [& parts]
  (.error js/console (str "[re-frame2-pair-mcp] " (str/join " " (map str parts)))))

;; ---------------------------------------------------------------------------
;; Shared connection state.
;; ---------------------------------------------------------------------------

(defn- resolve-port
  "Look up the nREPL port. Returns an integer or throws with a hint.

  `explicit-port-file` (from the `--port-file <path>` launch flag) takes
  precedence over the env var and the cwd-relative file scan — see
  `nrepl/read-port-from-fs` and the tool README (rf2-3dbwh)."
  [explicit-port-file]
  (or (nrepl/read-port-from-fs explicit-port-file)
      (throw (ex-info ":rf.error/pair-mcp-nrepl-port-not-found"
                      {:rf.error/id :rf.error/pair-mcp-nrepl-port-not-found
                       :where    'pair-mcp/resolve-port
                       :recovery :no-recovery
                       :reason   (str "nREPL port not found. Start your shadow-cljs dev build "
                                      "(`shadow-cljs watch <build>`), or set "
                                      "SHADOW_CLJS_NREPL_PORT explicitly, or pass "
                                      "`--port-file <absolute-path-to-nrepl.port>` "
                                      "(the cwd-independent escape hatch).")}))))

(defn- new-conn [explicit-port-file]
  (let [port (resolve-port explicit-port-file)]
    (log! "nREPL port =" port)
    (nrepl/make-conn port "127.0.0.1")))

;; ---------------------------------------------------------------------------
;; MCP request handlers.
;; ---------------------------------------------------------------------------

(defn- handle-list [_req]
  (js/Promise.resolve #js {:tools (tools/tool-descriptors-js)}))

(defn- handle-call [conn req extra]
  (let [params (j/get req :params)
        name   (j/get params :name)
        args   (or (j/get params :arguments) #js {})]
    (-> (tools/invoke conn name args extra)
        (.catch (fn [err]
                  (log! "handler threw for" name "—" (.-message err))
                  (let [payload {:ok?     false
                                 :reason  :handler-threw
                                 :message (.-message err)}]
                    #js {:isError          true
                         :content          #js [#js {:type "text"
                                                     :text (pr-str payload)}]
                         :structuredContent (clj->js payload)}))))))

;; ---------------------------------------------------------------------------
;; Server boot.
;; ---------------------------------------------------------------------------

(defn build-server
  "Build an MCP `Server` instance with `tools/list` wired to the static
  descriptors and `tools/call` routed to `call-handler` (a `(req,
  extra) → Promise<result>` fn). Shared by the success-path boot and
  the degraded-mode boot (rf2-ambfv) so the two share one Server
  shape — a future descriptor / capability change lands once."
  [call-handler]
  (let [Server          (j/get mcp-server :Server)
        ListToolsSchema (j/get mcp-types :ListToolsRequestSchema)
        CallToolSchema  (j/get mcp-types :CallToolRequestSchema)
        server          (Server.
                          #js {:name server-name :version server-version}
                          #js {:capabilities #js {:tools #js {}}})]
    (j/call server :setRequestHandler ListToolsSchema handle-list)
    (j/call server :setRequestHandler CallToolSchema call-handler)
    server))

(defn boot!
  "Build the MCP server, register handlers, and return it. Exposed for
  tests so they can drive the dispatcher without taking over stdin/out."
  [conn]
  (build-server (fn [req extra] (handle-call conn req extra))))

(defn- connect-transport!
  "Connect `server` to a fresh stdio transport. Logs `ready-msg` on
  success; logs and exits on transport-connect failure."
  [server ready-msg]
  (let [StdioTransport (j/get mcp-stdio :StdioServerTransport)]
    (-> (j/call server :connect (StdioTransport.))
        (.then (fn [_] (log! ready-msg)))
        (.catch (fn [err]
                  (log! "transport.connect failed:" (.-message err))
                  (js/process.exit 1))))))

(defn- degraded-handler
  "Build a `tools/call` handler that surfaces the boot-error structurally
  on every call. Used when the nREPL port couldn't be resolved — the
  MCP client can still discover tools and gets a typed error on first
  invocation rather than a transport-level failure."
  [boot-error]
  (fn [_req]
    (js/Promise.resolve
      (let [payload {:ok?    false
                     :reason :nrepl-port-not-found
                     :hint   (-> boot-error ex-data :hint)}]
        #js {:isError          true
             :content          #js [#js {:type "text"
                                         :text (pr-str payload)}]
             :structuredContent (clj->js payload)}))))

(defn- parse-port-file-flag
  "Pluck the value of the `--port-file` launch flag out of `argv`.
  Accepts both the space form `--port-file <path>` and the equals form
  `--port-file=<path>`. Returns the path string, or nil if absent /
  given without a value. Last occurrence wins (consistent with argv
  override semantics). Public-ish (private) — exercised via
  `parse-launch-flags` in tests."
  [argv]
  (loop [items argv
         found nil]
    (if-let [item (first items)]
      (cond
        ;; --port-file=<path>
        (str/starts-with? item "--port-file=")
        (recur (rest items) (subs item (count "--port-file=")))

        ;; --port-file <path>  (value is the next argv element)
        (= item "--port-file")
        (let [v (second items)]
          (if (and v (not (str/starts-with? v "--")))
            (recur (drop 2 items) v)
            (recur (rest items) found)))

        :else
        (recur (rest items) found))
      found)))

(defn parse-launch-flags
  "Pluck the named launch flags out of the raw process argv. Flags today:

    --allow-eval             — opt-in to the `eval-cljs` tool (rf2-cxx5s
                               cascade from rf2-czv3p). Default OFF.
    --allow-sensitive-reads  — opt-in to raw state on snapshot / get-path /
                               subscribe AND raw-value `tap>` emissions from
                               the preload's `app-db-reset!` (rf2-c2dtu).
                               Default OFF. Canonical cross-MCP name
                               (rf2-2x3ql) — matches story-mcp's identically
                               named gate (rf2-g9fje / rf2-uaymx).
    --port-file <path>       — explicit, cwd-independent nREPL port-file
                               path (rf2-3dbwh). Highest precedence in the
                               port-discovery chain — see
                               `nrepl/read-port-from-fs`. Accepts both
                               `--port-file <path>` and `--port-file=<path>`.

  Returns `{:allow-eval? bool :allow-raw-state? bool :port-file str-or-nil}`.
  The internal keyword `:allow-raw-state?` is the pair-mcp
  implementation-side identifier for the gate's state; the CLI flag is the
  operator-facing name. Unknown flags are ignored — node's shadow-cljs
  entry passes its own argv prelude (script path), and future flags can
  land here without breaking older invocations."
  [argv]
  {:allow-eval?      (boolean (some #{"--allow-eval"} argv))
   :allow-raw-state? (boolean (some #{"--allow-sensitive-reads"} argv))
   :port-file        (parse-port-file-flag argv)})

(defn- apply-launch-flags!
  "Wire launch-flag state into the relevant tool gates. Called once
  before the dispatcher accepts requests."
  [{:keys [allow-eval? allow-raw-state?]}]
  (eval-cljs/set-allow-eval! allow-eval?)
  (raw-state/set-allow-raw-state! allow-raw-state?)
  (log! "eval-cljs:" (if allow-eval? "ENABLED (--allow-eval)" "disabled (default; pass --allow-eval to opt in)"))
  ;; Symmetric with rf2-zyoj2 `--allow-eval` boot-gate logging. The
  ;; "allowed" / "gated" wording matches the rf2-uaymx (b) story-mcp
  ;; `--allow-sensitive-reads` shape (rf2-g9fje); rf2-2x3ql aligns
  ;; pair-mcp on the same canonical CLI-flag name so operators reading
  ;; multi-MCP logs see one vocabulary.
  (log! "Sensitive reads:" (if allow-raw-state? "allowed (--allow-sensitive-reads)" "gated (default; pass --allow-sensitive-reads to opt in)")))

(defn- apply-resource-controls!
  "Read resource-control config from env + CLI flags and push it into
  the resource-controls atoms (rf2-3ijbl). Logs the effective values
  so operators can confirm at startup which caps are in force."
  [argv]
  (let [env-cfg  (resource/read-resource-env)
        flag-cfg (resource/parse-resource-flags argv)
        merged   (resource/apply-resource-config! env-cfg flag-cfg)]
    (log! (str "Resource controls:"
               " max-concurrent-streams="    (:max-concurrent-streams merged)
               " max-events-per-sec="        (:max-events-per-sec merged)
               " abuse-overflow-threshold="  (:abuse-overflow-threshold merged)
               " abuse-window-ms="           (:abuse-window-ms merged)))))

(defn main [& args]
  (let [argv         (vec args)
        launch-flags (parse-launch-flags argv)]
    (apply-launch-flags! launch-flags)
    (apply-resource-controls! argv)
    (when-let [pf (:port-file launch-flags)]
      (log! "nREPL port-file (--port-file):" pf))
    (try
      (let [conn   (new-conn (:port-file launch-flags))
            server (boot! conn)]
        (log! "starting stdio transport")
        (connect-transport! server "ready — awaiting MCP frames on stdin"))
      (catch :default e
        (log! "boot failed:" (.-message e))
        ;; Even on boot failure (e.g. nREPL port missing) we keep the
        ;; process alive so the MCP client can talk to us, list tools,
        ;; and surface a structured error from the first tool call. The
        ;; bash-shim chain had the same semantics — the error came back
        ;; as `{:ok? false :reason :nrepl-port-not-found}`.
        (let [server (build-server (degraded-handler e))]
          (connect-transport! server "ready (degraded — no nREPL port)"))))))

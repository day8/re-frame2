(ns re-frame-pair2-mcp.server
  "MCP server entry-point. Wires the npm `@modelcontextprotocol/sdk`
  stdio transport to the tool dispatcher in `tools.cljs`, against a
  single persistent nREPL connection (`nrepl.cljs`).

  ## Lifecycle

  1. boot: read nREPL port from `SHADOW_CLJS_NREPL_PORT` env or the
     standard port-files. Open the persistent socket lazily on first
     tool call (so the server starts cleanly even before shadow-cljs
     is running — the first tool that needs the socket gets a
     structured error).
  2. `initialize`: standard MCP handshake.
  3. `tools/list`: returns the twelve tool descriptors (the seven
     bash-shim-overlap ops `discover-app` / `eval-cljs` / `dispatch` /
     `trace-window` / `watch-epochs` / `tail-build` / `snapshot`, plus
     `get-path` direct-read, the streaming triad `subscribe` /
     `unsubscribe` / `subscription-info`, and the
     `get-pair2-instructions` agent-onboarding tool).
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
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools :as tools]
            [re-frame-pair2-mcp.tools.eval-cljs :as eval-cljs]
            ["@modelcontextprotocol/sdk/server/index.js" :as mcp-server]
            ["@modelcontextprotocol/sdk/server/stdio.js" :as mcp-stdio]
            ["@modelcontextprotocol/sdk/types.js" :as mcp-types]))

(def ^:const server-name    "re-frame-pair2-mcp")
(def ^:const server-version "0.1.0")

(defn log!
  "Stderr logger — stdout is reserved for MCP messages."
  [& parts]
  (.error js/console (str "[pair2-mcp] " (str/join " " (map str parts)))))

;; ---------------------------------------------------------------------------
;; Shared connection state.
;; ---------------------------------------------------------------------------

(defn- resolve-port
  "Look up the nREPL port. Returns an integer or throws with a hint."
  []
  (or (nrepl/read-port-from-fs)
      (throw (ex-info "nREPL port not found"
                      {:hint (str "Start your shadow-cljs dev build "
                                  "(`shadow-cljs watch <build>`), or set "
                                  "SHADOW_CLJS_NREPL_PORT explicitly.")}))))

(defn- new-conn []
  (let [port (resolve-port)]
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
                  #js {:isError true
                       :content #js [#js {:type "text"
                                          :text (str "{:ok? false :reason :handler-threw "
                                                     ":message " (pr-str (.-message err)) "}")}]})))))

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
      #js {:isError true
           :content #js [#js {:type "text"
                              :text (pr-str {:ok? false
                                             :reason :nrepl-port-not-found
                                             :hint   (-> boot-error ex-data :hint)})}]})))

(defn parse-launch-flags
  "Pluck the named boolean launch flags out of the raw process argv. Today's
  surface is one flag — `--allow-eval` — which opts in to the `eval-cljs`
  tool per the rf2-cxx5s gate (cascade from rf2-czv3p). Default OFF in
  published builds.

  Returns `{:allow-eval? bool}`. Unknown flags are ignored — node's
  shadow-cljs entry passes its own argv prelude (script path), and
  future flags can land here without breaking older invocations."
  [argv]
  {:allow-eval? (boolean (some #{"--allow-eval"} argv))})

(defn- apply-launch-flags!
  "Wire launch-flag state into the relevant tool gates. Called once
  before the dispatcher accepts requests."
  [{:keys [allow-eval?]}]
  (eval-cljs/set-allow-eval! allow-eval?)
  (log! "eval-cljs:" (if allow-eval? "ENABLED (--allow-eval)" "disabled (default; pass --allow-eval to opt in)")))

(defn main [& args]
  (apply-launch-flags! (parse-launch-flags (vec args)))
  (try
    (let [conn   (new-conn)
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
        (connect-transport! server "ready (degraded — no nREPL port)")))))

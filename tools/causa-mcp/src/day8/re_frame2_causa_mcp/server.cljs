(ns day8.re-frame2-causa-mcp.server
  "MCP server entry-point. Wires the npm `@modelcontextprotocol/sdk`
  stdio transport to a JSON-RPC dispatcher exposing `tools/list` and
  `tools/call` envelopes, against a single persistent nREPL connection
  (lands in a later F-tranche).

  ## Lifecycle

  1. boot: read nREPL port from `SHADOW_CLJS_NREPL_PORT` env or the
     standard port-files. If absent, the server still boots — the
     persistent socket is opened lazily by later F-tranches; until
     then every `tools/call` returns a structured
     `{:ok? false :reason :nrepl-port-not-found}` envelope with a
     setup hint (the documented degraded mode; MUST-inventory I4).
  2. `initialize`: standard MCP handshake (handled by the SDK).
  3. `tools/list`: returns the Causa-shaped tool descriptors
     (catalogue empty at F-2; populated by later F-tranches).
  4. `tools/call`: dispatch to the tools surface. Stubbed at F-2 to
     surface a structured `:reason :not-implemented` envelope until
     the dispatcher lands.
  5. stdin EOF: shut down cleanly.

  ## Why low-level Server, not McpServer

  The SDK's high-level `McpServer` registers tools at construction
  time with a schema-validation layer. We want explicit control over
  the request handlers (parallel to the pair2-mcp sibling and the
  JVM port at `tools/story-mcp/`), so we use the low-level `Server`
  + `setRequestHandler` API.

  ## Why this server.cljs mirrors `tools/pair2-mcp/src/re_frame_pair2_mcp/server.cljs`

  Same transport, same nREPL bridge (lands later), same wire-protocol
  envelopes. Different tool catalogue, different `:origin` tag
  (Lock #6 + Lock #11 of `tools/causa-mcp/spec/DESIGN-RATIONALE.md`).
  The F-2 port keeps the structural bones identical so a later
  F-tranche dropping in the tool catalogue / nREPL bridge / launch
  flags lands by extension rather than rewrite."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.tools :as tools]
            ["@modelcontextprotocol/sdk/server/index.js" :as mcp-server]
            ["@modelcontextprotocol/sdk/server/stdio.js" :as mcp-stdio]
            ["@modelcontextprotocol/sdk/types.js" :as mcp-types]))

(def ^:const server-name    "re-frame2-causa-mcp")
(def ^:const server-version "0.1.0")

(defn log!
  "Stderr logger — stdout is reserved for MCP messages."
  [& parts]
  (.error js/console (str "[causa-mcp] " (str/join " " (map str parts)))))

;; ---------------------------------------------------------------------------
;; nREPL port discovery.
;;
;; Lifted in F-3 (rf2-8xzoe.3) to `day8.re-frame2-causa-mcp.nrepl`
;; alongside the socket + bencode surface. The local `read-port-from-fs`
;; alias keeps the server-public contract (the server-test resolves
;; `server/read-port-from-fs`) stable across the lift.
;; ---------------------------------------------------------------------------

(def read-port-from-fs
  "Read the nREPL port from `SHADOW_CLJS_NREPL_PORT` or the standard
  shadow-cljs / nrepl port-file locations. Returns an integer or nil.

  Thin alias to `nrepl/read-port-from-fs` — the real implementation
  now lives with the transport. Public so the server test can pin the
  contract; downstream code can call either ns equivalently."
  nrepl/read-port-from-fs)

(defn- resolve-port
  "Look up the nREPL port. Returns an integer or throws with a hint."
  []
  (or (read-port-from-fs)
      (throw (ex-info "nREPL port not found"
                      {:hint (str "Start your shadow-cljs dev build "
                                  "(`shadow-cljs watch <build>`), or set "
                                  "SHADOW_CLJS_NREPL_PORT explicitly.")}))))

;; ---------------------------------------------------------------------------
;; MCP request handlers.
;; ---------------------------------------------------------------------------

(defn tool-descriptors-js
  "The Causa-shaped tool catalogue, as a JS array of descriptor maps.
  Delegates to `tools/tool-descriptors-js` — the per-tool
  `register-tool!` side-effect populates the registry on load, and the
  façade builds the JS-shape array from that registry. Public so tests
  pin the catalogue shape."
  []
  (tools/tool-descriptors-js))

(defn- handle-list [_req]
  (js/Promise.resolve #js {:tools (tool-descriptors-js)}))

(defn- handle-call-success
  "Success-path `tools/call` handler. Routes the JSON-RPC request
  through `tools/invoke`, which dispatches to the per-tool registry
  and then wraps the result through the W-1 token-cap boundary step.
  Each per-tool body internally applies the B-1 privacy + W-6 elision
  gates before returning."
  [conn req extra]
  (let [params (j/get req :params)
        name   (j/get params :name)
        args   (j/get params :arguments)]
    (tools/invoke conn name args extra)))

;; ---------------------------------------------------------------------------
;; Server boot.
;; ---------------------------------------------------------------------------

(defn build-server
  "Build an MCP `Server` instance with `tools/list` wired to the
  static descriptors and `tools/call` routed to `call-handler` (a
  `(req, extra) → Promise<result>` fn). Shared by the success-path
  boot and the degraded-mode boot so the two share one Server shape
  — a future descriptor / capability change lands once."
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
  (build-server (fn [req extra] (handle-call-success conn req extra))))

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

(defn degraded-handler
  "Build a `tools/call` handler that surfaces the boot-error structurally
  on every call. Used when the nREPL port couldn't be resolved — the
  MCP client can still discover tools (empty at F-2) and gets a typed
  error on first invocation rather than a transport-level failure.

  Public so the server test can pin the envelope shape."
  [boot-error]
  (fn [_req]
    (js/Promise.resolve
      #js {:isError true
           :content #js [#js {:type "text"
                              :text (pr-str {:ok?    false
                                             :reason :nrepl-port-not-found
                                             :hint   (-> boot-error ex-data :hint)})}]})))

(defn main
  "Entry-point. Resolves the nREPL port and boots the success-path
  server; on resolve failure (e.g. shadow-cljs not running) boots the
  degraded-mode server so the MCP client still gets a clean
  handshake and a typed error from the first `tools/call`."
  [& _args]
  (try
    (let [port   (resolve-port)
          _      (log! "nREPL port =" port)
          ;; F-2 carries no live nREPL connection yet — the port is
          ;; resolved (proving the success path) and held on the
          ;; conn slot as a placeholder map. A later F-tranche opens
          ;; the persistent socket here and replaces this with the
          ;; nrepl/make-conn call (parallel to pair2-mcp).
          conn   {:port port}
          server (boot! conn)]
      (log! "starting stdio transport")
      (connect-transport! server "ready — awaiting MCP frames on stdin"))
    (catch :default e
      (log! "boot failed:" (.-message e))
      ;; Even on boot failure (e.g. nREPL port missing) we keep the
      ;; process alive so the MCP client can talk to us, list tools,
      ;; and surface a structured error from the first tool call —
      ;; the documented degraded mode (MUST-inventory I4). The
      ;; bash-shim chain had the same semantics; the error came back
      ;; as `{:ok? false :reason :nrepl-port-not-found}`.
      (let [server (build-server (degraded-handler e))]
        (connect-transport! server "ready (degraded — no nREPL port)")))))

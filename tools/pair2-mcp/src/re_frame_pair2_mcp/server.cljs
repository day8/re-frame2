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
  3. `tools/list`: returns the seven tool descriptors (mirror of the
     bash-shim catalogue).
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

(defn boot!
  "Build the MCP server, register handlers, and return it. Exposed for
  tests so they can drive the dispatcher without taking over stdin/out."
  [conn]
  (let [Server          (j/get mcp-server :Server)
        ListToolsSchema (j/get mcp-types :ListToolsRequestSchema)
        CallToolSchema  (j/get mcp-types :CallToolRequestSchema)
        server (Server.
                 #js {:name server-name :version server-version}
                 #js {:capabilities #js {:tools #js {}}})]
    (j/call server :setRequestHandler ListToolsSchema handle-list)
    (j/call server :setRequestHandler CallToolSchema
            (fn [req extra] (handle-call conn req extra)))
    server))

(defn main [& _args]
  (try
    (let [conn   (new-conn)
          server (boot! conn)
          StdioTransport (j/get mcp-stdio :StdioServerTransport)]
      (log! "starting stdio transport")
      (-> (j/call server :connect (StdioTransport.))
          (.then (fn [_] (log! "ready — awaiting MCP frames on stdin")))
          (.catch (fn [err]
                    (log! "transport.connect failed:" (.-message err))
                    (js/process.exit 1)))))
    (catch :default e
      (log! "boot failed:" (.-message e))
      ;; Even on boot failure (e.g. nREPL port missing) we keep the
      ;; process alive so the MCP client can talk to us, list tools,
      ;; and surface a structured error from the first tool call. The
      ;; bash-shim chain had the same semantics — the error came back
      ;; as `{:ok? false :reason :nrepl-port-not-found}`.
      (let [Server          (j/get mcp-server :Server)
            ListToolsSchema (j/get mcp-types :ListToolsRequestSchema)
            CallToolSchema  (j/get mcp-types :CallToolRequestSchema)
            StdioTransport  (j/get mcp-stdio :StdioServerTransport)
            server (Server. #js {:name server-name :version server-version}
                            #js {:capabilities #js {:tools #js {}}})]
        (j/call server :setRequestHandler ListToolsSchema handle-list)
        (j/call server :setRequestHandler CallToolSchema
          (fn [_req]
            (js/Promise.resolve
              #js {:isError true
                   :content #js [#js {:type "text"
                                      :text (pr-str {:ok? false
                                                     :reason :nrepl-port-not-found
                                                     :hint (-> e ex-data :hint)})}]})))
        (-> (j/call server :connect (StdioTransport.))
            (.then (fn [_] (log! "ready (degraded — no nREPL port)")))
            (.catch (fn [err]
                      (log! "transport.connect failed:" (.-message err))
                      (js/process.exit 1))))))))

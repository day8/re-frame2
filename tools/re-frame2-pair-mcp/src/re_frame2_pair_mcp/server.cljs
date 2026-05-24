(ns re-frame2-pair-mcp.server
  "MCP server entry-point. Wires the npm `@modelcontextprotocol/sdk`
  stdio transport to the tool dispatcher in `tools.cljs`, against a
  single persistent nREPL connection (`nrepl.cljs`).

  ## Lifecycle

  1. boot: discover the nREPL port via the four-step cascade — the
     `--port-file <path>` flag, the `SHADOW_CLJS_NREPL_PORT` env var,
     a probe of shadow's HTTP API at port 9630 (which yields the
     consumer project root → port-file lookup; rf2-umoz2), and finally
     a cwd-relative port-file scan. See `nrepl/discover-port`. Open
     the persistent socket lazily on first tool call (so the server
     starts cleanly even before shadow-cljs is running — the first
     tool that needs the socket gets a structured error).
  2. `initialize`: standard MCP handshake.
  3. `tools/list`: returns the full tool catalogue — see
     `tools.registry/tools` for the authoritative list (the single
     source of truth). It spans the bash-shim-overlap ops, the
     direct-read primitives, the write tools (`restore-epoch` /
     `reset-frame-db`, gated behind `--allow-writes`), the streaming
     triad, the registrar-introspection pair, and the agent-onboarding
     tool. The count is derived, not hand-maintained here.
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
            [re-frame2-pair-mcp.tools.writes :as writes]
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

(def ^:private port-not-found-hint
  "Boot-failure prose for the operator. Threaded into the boot-error
  payload and the degraded-mode hint so the same surface appears
  whether discovery returned nil or the cascade threw."
  (str "nREPL port not found. Start your shadow-cljs dev build "
       "(`shadow-cljs watch <build>`), or set "
       "SHADOW_CLJS_NREPL_PORT explicitly, or pass "
       "`--port-file <absolute-path-to-nrepl.port>` "
       "(the cwd-independent escape hatch)."))

(defn- resolve-port
  "Run the async port-discovery cascade — see `nrepl/discover-port` for
  the four-step precedence (rf2-umoz2 added the shadow HTTP probe as
  step 3). Returns a Promise resolving to an integer port or rejecting
  with a structured `:rf.error/pair-mcp-nrepl-port-not-found` ex-info."
  [explicit-port-file http-port]
  (-> (nrepl/discover-port explicit-port-file http-port)
      (.then (fn [port]
               (if port
                 port
                 (throw (ex-info ":rf.error/pair-mcp-nrepl-port-not-found"
                                 {:rf.error/id :rf.error/pair-mcp-nrepl-port-not-found
                                  :where    'pair-mcp/resolve-port
                                  :recovery :no-recovery
                                  :reason   port-not-found-hint
                                  :hint     port-not-found-hint})))))))

(defn- new-conn-for-port [port]
  (log! "nREPL port =" port)
  (nrepl/make-conn port "127.0.0.1"))

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

(defn- parse-string-value-flag
  "Generic launch-flag pluck: returns the trailing value of `--name <v>`
  or the inline value of `--name=<v>` for the named flag in `argv`.
  Last occurrence wins (consistent with argv override semantics).
  Returns nil when the flag is absent or given without a value.

  Used by both `--port-file` (rf2-3dbwh) and `--http-port` (rf2-umoz2);
  one parser, one shape — so a future string-valued flag lands here
  without growing a per-flag micro-parser."
  [flag-name argv]
  (let [equals-prefix (str flag-name "=")]
    (loop [items argv
           found nil]
      (if-let [item (first items)]
        (cond
          (str/starts-with? item equals-prefix)
          (recur (rest items) (subs item (count equals-prefix)))

          (= item flag-name)
          (let [v (second items)]
            (if (and v (not (str/starts-with? v "--")))
              (recur (drop 2 items) v)
              (recur (rest items) found)))

          :else
          (recur (rest items) found))
        found))))

(defn- parse-port-file-flag
  "Pluck the value of the `--port-file` launch flag out of `argv`.
  Accepts both the space form `--port-file <path>` and the equals form
  `--port-file=<path>`. Returns the path string, or nil if absent /
  given without a value. Last occurrence wins (consistent with argv
  override semantics). Public-ish (private) — exercised via
  `parse-launch-flags` in tests."
  [argv]
  (parse-string-value-flag "--port-file" argv))

(defn- parse-http-port-flag
  "Pluck the value of the `--http-port` launch flag (rf2-umoz2) and
  coerce to an int. Returns nil when absent / malformed.

  Shadow's web server is fixed by its own `:http :port` config; the
  default (9630) covers ~all consumers. Operators who pinned shadow's
  HTTP port to something else surface that here without forcing a
  re-implementation of the shadow-cljs.edn parser."
  [argv]
  (when-let [raw (parse-string-value-flag "--http-port" argv)]
    (let [n (js/parseInt raw 10)]
      (when-not (js/isNaN n) n))))

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
    --allow-writes           — opt-in to the state-mutating tools
                               `restore-epoch` (time-travel undo) and
                               `reset-frame-db` (state injection), rf2-ee38b.18.
                               Default OFF. Without the flag both return
                               `{:ok? false :reason :rf.error/writes-disabled}`
                               without touching the nREPL socket. `dispatch`
                               (which drives the app's own handlers) is
                               unaffected.
    --port-file <path>       — explicit, cwd-independent nREPL port-file
                               path (rf2-3dbwh). Highest precedence in the
                               port-discovery chain — see
                               `nrepl/discover-port`. Accepts both
                               `--port-file <path>` and `--port-file=<path>`.
    --http-port <n>          — override shadow-cljs's HTTP server port
                               for the auto-discovery probe (rf2-umoz2).
                               Defaults to 9630 (shadow's standard).
                               Only used when steps 1-2 of the cascade
                               miss; setting it has no effect when
                               --port-file or SHADOW_CLJS_NREPL_PORT is
                               present.

  Returns `{:allow-eval? bool :allow-raw-state? bool :allow-writes? bool
  :port-file str-or-nil :http-port int-or-nil}`.
  The internal keyword `:allow-raw-state?` is the pair-mcp
  implementation-side identifier for the gate's state; the CLI flag is the
  operator-facing name. Unknown flags are ignored — node's shadow-cljs
  entry passes its own argv prelude (script path), and future flags can
  land here without breaking older invocations."
  [argv]
  {:allow-eval?      (boolean (some #{"--allow-eval"} argv))
   :allow-raw-state? (boolean (some #{"--allow-sensitive-reads"} argv))
   :allow-writes?    (boolean (some #{"--allow-writes"} argv))
   :port-file        (parse-port-file-flag argv)
   :http-port        (parse-http-port-flag argv)})

(defn- apply-launch-flags!
  "Wire launch-flag state into the relevant tool gates. Called once
  before the dispatcher accepts requests."
  [{:keys [allow-eval? allow-raw-state? allow-writes?]}]
  (eval-cljs/set-allow-eval! allow-eval?)
  (raw-state/set-allow-raw-state! allow-raw-state?)
  (writes/set-allow-writes! allow-writes?)
  (log! "eval-cljs:" (if allow-eval? "ENABLED (--allow-eval)" "disabled (default; pass --allow-eval to opt in)"))
  ;; Symmetric with rf2-zyoj2 `--allow-eval` boot-gate logging. The
  ;; "allowed" / "gated" wording matches the rf2-uaymx (b) story-mcp
  ;; `--allow-sensitive-reads` shape (rf2-g9fje); rf2-2x3ql aligns
  ;; pair-mcp on the same canonical CLI-flag name so operators reading
  ;; multi-MCP logs see one vocabulary.
  (log! "Sensitive reads:" (if allow-raw-state? "allowed (--allow-sensitive-reads)" "gated (default; pass --allow-sensitive-reads to opt in)"))
  ;; rf2-ee38b.18 — the state-mutating tool gate. Same default-OFF
  ;; posture as --allow-eval; restore-epoch / reset-frame-db are
  ;; qualitatively more powerful than dispatch (they replace app-db
  ;; wholesale).
  (log! "Writes:" (if allow-writes? "ENABLED (--allow-writes)" "disabled (default; pass --allow-writes to opt in)")))

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
    (when-let [hp (:http-port launch-flags)]
      (log! "shadow HTTP port (--http-port):" hp))
    ;; Port discovery is async (the shadow HTTP probe in step 3 of
    ;; `nrepl/discover-port` is a real network call; rf2-umoz2). The
    ;; rest of boot — stdio transport, dispatcher wiring — sequences off
    ;; the resolved port via .then. Degraded boot fires from the .catch
    ;; branch so a missing nREPL still yields a tools/list-discoverable
    ;; server that surfaces a structured error on first call. Same
    ;; observable shape as the pre-rf2-umoz2 sync boot — the await is an
    ;; implementation detail.
    (-> (resolve-port (:port-file launch-flags) (:http-port launch-flags))
        (.then (fn [port]
                 (let [conn   (new-conn-for-port port)
                       server (boot! conn)]
                   (log! "starting stdio transport")
                   (connect-transport! server "ready — awaiting MCP frames on stdin"))))
        (.catch (fn [e]
                  (log! "boot failed:" (.-message e))
                  ;; Even on boot failure (e.g. nREPL port missing) we keep the
                  ;; process alive so the MCP client can talk to us, list tools,
                  ;; and surface a structured error from the first tool call. The
                  ;; bash-shim chain had the same semantics — the error came back
                  ;; as `{:ok? false :reason :nrepl-port-not-found}`.
                  (let [server (build-server (degraded-handler e))]
                    (connect-transport! server "ready (degraded — no nREPL port)")))))))

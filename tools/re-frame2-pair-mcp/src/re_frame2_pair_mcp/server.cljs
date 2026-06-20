(ns re-frame2-pair-mcp.server
  "MCP server entry-point. Wires the npm `@modelcontextprotocol/sdk`
  stdio transport to the tool dispatcher in `tools.cljs`, against a
  single persistent nREPL connection (`nrepl.cljs`).

  ## Lifecycle

  1. boot: parse launch flags, build the MCP `Server`, connect the
     stdio transport. The persistent nREPL socket is NOT opened
     here — discovery is lazy so it can ask the MCP client for
     workspace roots after initialization completes.
  2. `initialize`: standard MCP handshake. Server learns the client's
     capabilities (`roots`, `elicitation`).
  3. `tools/list`: returns the full tool catalogue — see
     `tools.registry/tools` for the authoritative list (the single
     source of truth).
  4. `tools/call` (first time): runs the port-discovery cascade —
     the five-step precedence:

        1. `--port-file <path>` explicit override
        2. `$SHADOW_CLJS_NREPL_PORT` env var
        3. MCP `roots/list` → walk for `shadow-cljs.edn` → port-file
        4. Shadow HTTP probe at `:9630` → `/api/project-info`
        5. CWD-relative scan (final fallback)

     Step 3 is the primary path — generic, zero-config, no
     port-range guessing. On ambiguity (2+ shadow builds in the
     workspace), the server drives `elicitation/create` to ask the user
     which project. The result (port + project-home) is cached for the
     session.

  5. `tools/call` (subsequent): re-reads the port file at the cached
     `project-home` before each call — a shadow restart writes a new
     port but the file location is stable, so the cache stays valid
     across restarts. If the port changed, close the stale socket and
     reconnect transparently.

  6. stdin EOF: shut down cleanly.

  ## Why low-level Server, not McpServer

  The SDK's high-level `McpServer` registers tools at construction time
  with a schema-validation layer. We want explicit control over the
  request handlers (parallel to the JVM port at `tools/story-mcp/`),
  so we use the low-level `Server` + `setRequestHandler` API.

  ## Server reference for roots/elicitation

  The Server instance carries `listRoots()` and `elicitInput()` methods
  (SDK 1.29+); these are the primitives the discovery cascade uses.
  The server reference is captured in `server-instance` so the
  lazy-discovery flow can reach it without threading it through every
  tool handler signature."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.roots-discovery :as roots-discovery]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.resource-controls :as resource]
            ["@modelcontextprotocol/sdk/server/index.js" :as mcp-server]
            ["@modelcontextprotocol/sdk/server/stdio.js" :as mcp-stdio]
            ["@modelcontextprotocol/sdk/types.js" :as mcp-types]
            ["fs" :as fs]
            ["path" :as node-path]))

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

;; ---------------------------------------------------------------------------
;; Session-cache for the lazy-discovered project.
;; ---------------------------------------------------------------------------

(def ^:private session-state
  "Per-server-instance cache for the resolved nREPL endpoint. Lazy on
  first tool call (`ensure-connection!`); invalidated when the cached
  port-file disappears or content changes on a per-tool-call re-read.

  Shape:

      {:project-home   <abs-dir | nil>     ;; nil when env-var or cwd-scan won
       :port-file      <abs-file | nil>    ;; the specific port file we cached
       :port           <int | nil>         ;; the port last seen
       :conn           <atom | nil>        ;; the persistent nREPL conn
       :discovered?    <bool>              ;; have we run discovery yet?
       :discovery-error <ex-info | nil>}   ;; LAST failure, diagnostic only

  Resetting `:discovered? false` triggers a full re-discovery on the
  next tool call — used by the operator-initiated re-attach branch.

  `:discovery-error` records the most recent failed-discovery rejection
  for diagnostics; it does NOT gate. While `:discovered?` is
  false, every tool call re-runs the cascade, so a recoverable failure
  (shadow not up at the first call) self-heals on a later call once the
  operator starts the build."
  (atom {:project-home    nil
         :port-file       nil
         :port            nil
         :conn            nil
         :discovered?     false
         :discovery-error nil}))

(defn session-state-snapshot
  "Deref the private `session-state` for tests — read-only window onto
  the resolved-endpoint cache (exercises the lazy-discovery retry
  contract)."
  []
  @session-state)

(defn reset-session-state-for-tests!
  "Reset `session-state` to the pristine pre-discovery shape. Exposed for
  tests so the lazy-discovery retry contract can be exercised from a
  known slate."
  []
  (reset! session-state {:project-home    nil
                         :port-file       nil
                         :port            nil
                         :conn            nil
                         :discovered?     false
                         :discovery-error nil}))

(defn mark-discovered-for-tests!
  "Stand in for `discover-and-cache!`'s success side effect — record a
  resolved conn (no port-file, so the per-call re-read fast-path returns
  it verbatim) and flip `:discovered?`. Exposed so a stub discovery thunk
  can mimic a successful attach without the npm SDK."
  [conn]
  (swap! session-state assoc :conn conn :discovered? true :discovery-error nil))

(defn set-discovered-for-tests!
  "Record a FULLY-discovered session (conn + port + the exact cached
  port-file) and flip `:discovered?`. Exposed so the port-file-stability
  contract can be exercised: a second `ensure-connection!`
  call must stay on the cached conn when the cached port-file still reads
  the same port — without re-deriving a `.shadow-cljs/nrepl.port` that
  may not be the file discovery actually resolved."
  [{:keys [conn port port-file project-home]}]
  (swap! session-state assoc
         :conn conn :port port :port-file port-file
         :project-home project-home
         :discovered? true :discovery-error nil))

;; The Server instance is captured here when `build-server` returns it,
;; so the discovery flow can reach `listRoots()` and `elicitInput()`
;; without threading the server through every handler signature.
(def ^:private server-instance (atom nil))

(defn- list-roots-fn
  "Closure over the captured Server instance that calls SDK
  `server.listRoots()`. Returns a Promise resolving to
  `{:roots [{:uri ...} ...]}` shape. Rejects when the client doesn't
  expose `roots` (older clients) — `roots-discovery/discover-via-roots*`
  catches that rejection and surfaces `:workspace-discovery-unsupported`,
  which the cascade interprets as 'fall through to step 4'."
  []
  (if-let [^js server @server-instance]
    (j/call server :listRoots)
    (js/Promise.reject (js/Error. "Server not yet initialized"))))

(defn- elicit-choice!
  "Drive `elicitation/create` to ask the user which shadow project to
  attach to. `candidates` is a vector of `{:project-home :port-file :port}`
  maps. Returns a Promise resolving to the chosen candidate, or rejecting
  when the user cancels/declines or the client doesn't support
  `elicitation/create`.

  The MCP elicitation form is keyed by integer index — the user picks a
  numbered project; we map back to the candidate. A simpler enum field
  works because shadow-project absolute paths can be long and unwieldy
  inside a select widget."
  [candidates]
  (let [labels   (mapv (fn [c] (str (:project-home c) " (port " (:port c) ")"))
                       candidates)
        ^js server @server-instance]
    (-> (j/call server :elicitInput
                #js {:message "Multiple running shadow-cljs builds found in the open workspace. Which project should re-frame2-pair attach to?"
                     :requestedSchema
                     #js {:type "object"
                          :properties
                          #js {:project
                               #js {:type        "string"
                                    :enum        (clj->js labels)
                                    :description "Choose the project whose live shadow build should drive this pair-mcp session."}}
                          :required #js ["project"]}})
        (.then (fn [^js result]
                 (let [action (j/get result :action)]
                   (case action
                     "accept"
                     (let [picked   (j/get-in result [:content :project])
                           label-ix (->> labels
                                         (map-indexed vector)
                                         (some (fn [[i lbl]] (when (= picked lbl) i))))]
                       (if-let [c (when label-ix (nth candidates label-ix))]
                         c
                         ;; The user accepted but the answer doesn't map back
                         ;; — defensive guard against a hand-edited form.
                         (js/Promise.reject
                           (js/Error.
                             (str "elicitation accepted but unknown project label: " picked)))))

                     ("cancel" "decline")
                     (js/Promise.reject
                       (js/Error. (str "elicitation " action " — no project chosen")))

                     (js/Promise.reject
                       (js/Error. (str "elicitation returned unexpected action: " action))))))))))

(defn- read-port-file*
  "Read the port file at `path`. Returns an int or nil (missing /
  unreadable / non-numeric content). Mirrors `nrepl/read-port-file` but
  kept local so a future test seam doesn't have to thread through the
  transport ns."
  [path]
  (try
    (let [content (str/trim (.toString (.readFileSync fs path)))
          n       (js/parseInt content 10)]
      (when-not (js/isNaN n) n))
    (catch :default _ nil)))

(defn- new-conn-for-port [port]
  (log! "nREPL port =" port)
  (nrepl/make-conn port "127.0.0.1"))

(defn- discover-and-cache!
  "First-tool-call discovery. Run the five-step cascade in
  `nrepl/discover-port` with the captured Server's `listRoots` as the
  injected roots-discovery fn. On `:ambiguous` (2+ shadow candidates),
  drive `elicitation/create` to ask the user. Cache the chosen port +
  project-home in `session-state`.

  Returns a Promise resolving to the session-state map on success, or
  rejecting with a structured ex-info on failure."
  [launch-flags]
  (let [{:keys [port-file http-port]} launch-flags
        roots-fn (fn [] (roots-discovery/discover-via-roots list-roots-fn))]
    (-> (nrepl/discover-port port-file http-port roots-fn)
        (.then
          (fn [result]
            (cond
              ;; Single shadow build in workspace, or fallback succeeded.
              (and (:port result) (nil? (:ambiguous result)))
              (let [port (:port result)
                    ph   (:project-home result)
                    conn (new-conn-for-port port)]
                (swap! session-state assoc
                       :project-home ph
                       ;; PREFER the exact port-file discovery resolved
                       ;; (explicit `--port-file`, roots candidate, or the
                       ;; winning HTTP-probe candidate). Every discovery mode
                       ;; that yields a `:project-home` also yields the
                       ;; `:port-file` that actually read, so the derived
                       ;; `.shadow-cljs/nrepl.port` below is a pure defensive
                       ;; backstop — never reached for those modes. Deriving
                       ;; unconditionally from `ph` would produce e.g.
                       ;; `target/shadow-cljs/.shadow-cljs/nrepl.port` for an
                       ;; explicit `target/shadow-cljs/nrepl.port`, which the
                       ;; next `ensure-connection!` would see as missing and
                       ;; force a needless reconnect + per-conn cache reset.
                       :port-file    (or (:port-file result)
                                         (when ph
                                           (node-path/join ph ".shadow-cljs/nrepl.port")))
                       :port         port
                       :conn         conn
                       :discovered?  true)
                @session-state)

              ;; Ambiguous — drive elicitation/create.
              (:ambiguous result)
              (-> (elicit-choice! (:ambiguous result))
                  (.then (fn [chosen]
                           (let [port (:port chosen)
                                 ph   (:project-home chosen)
                                 conn (new-conn-for-port port)]
                             (swap! session-state assoc
                                    :project-home ph
                                    :port-file    (:port-file chosen)
                                    :port         port
                                    :conn         conn
                                    :discovered?  true)
                             @session-state)))
                  (.catch
                    (fn [_err]
                      ;; Elicitation unsupported or user cancelled — surface
                      ;; the ambiguous result as a structured boot error so
                      ;; the agent can ask in chat and retry with --port-file.
                      (let [payload (roots-discovery/ambiguous-result-payload
                                      (:ambiguous result))]
                        (throw (ex-info ":rf.error/pair-mcp-ambiguous-shadow"
                                        {:rf.error/id :rf.error/pair-mcp-ambiguous-shadow
                                         :where    're-frame2-pair-mcp/discover-and-cache!
                                         :recovery :pick-via-port-file
                                         :reason   (:reason payload)
                                         :candidates (:candidates payload)
                                         :hint     (:hint payload)}))))))

              ;; Nothing resolved.
              :else
              (throw (ex-info ":rf.error/pair-mcp-nrepl-port-not-found"
                              {:rf.error/id :rf.error/pair-mcp-nrepl-port-not-found
                               :where    're-frame2-pair-mcp/discover-and-cache!
                               :recovery :no-recovery
                               :reason   port-not-found-hint
                               :hint     port-not-found-hint}))))))))

(defn ensure-connection!
  "Lazy-discovery entry: called before every tool dispatch. Three paths:

  1. First call (or after invalidation) — runs `discover-and-cache!`.
  2. Cached + port-file still resolves to the same port — fast path,
     returns the cached conn atom.
  3. Cached + port-file content changed (or vanished) — shadow was
     restarted; close the stale socket, swap in a NEW conn for the new
     port (or force re-discovery).

  Path 3 is where the genuine \"operator restarted shadow against a
  different build\" reset happens: a restart almost always grabs a new
  ephemeral port, so the new conn from `new-conn-for-port` starts with
  EMPTY build-id caches (`:probed-builds` / `:resolved-build-id` /
  `:build-alias`). The transport-layer `nrepl/connect!` deliberately
  does NOT clear those caches on a same-port reopen — that would wipe a
  valid sticky build on a transient socket hiccup — so this layer (which
  actually observes the port change) owns the invalidation, alongside
  operator `close!`.

  ## Discovery failure is RETRIED, never sticky

  A failed discovery (`discover-and-cache!` rejected: no nREPL port yet,
  shadow not started, ambiguous-and-declined) leaves `:discovered? false`
  and records `:discovery-error` for diagnostics — but it does NOT wedge
  the session. Each subsequent tool call RE-RUNS the cascade, so a
  recoverable failure (e.g. a tool call landed before `shadow-cljs watch`
  was up) self-heals once the operator starts the build — no MCP-server
  restart needed. The cascade is bounded (sync env/flag steps; the async
  roots/HTTP probes cap at `shadow-discovery/probe-timeout-ms`), so
  re-running it per call on the failure path is cheap and is the only
  thing that lets a session self-heal when the operator fixes the
  underlying cause. `:discovery-error` is kept purely as a diagnostic
  breadcrumb of the LAST failure; it does not gate.

  Returns a Promise resolving to the live conn atom, or rejecting with a
  fresh structured discovery error when the cascade still can't resolve.

  The 2-arity injects the discovery thunk so the retry contract can be
  unit-tested without the npm SDK / a live shadow; production
  uses the 1-arity, which threads in `discover-and-cache!`."
  ([launch-flags] (ensure-connection! launch-flags discover-and-cache!))
  ([launch-flags discover-fn]
  (let [{:keys [discovered? port-file port conn]} @session-state]
    (cond
      (not discovered?)
      (-> (discover-fn launch-flags)
          (.then (fn [_] (:conn @session-state)))
          (.catch (fn [e]
                    ;; Record the last failure for diagnostics only — the
                    ;; next tool call re-runs discovery; a recoverable
                    ;; failure (shadow not up yet) must not permanently
                    ;; wedge the session.
                    (swap! session-state assoc :discovery-error e)
                    (js/Promise.reject e))))

      :else
      (let [current-port (when port-file (read-port-file* port-file))]
        (cond
          ;; The cached file vanished — shadow shut down. Force re-discovery.
          (and port-file (nil? current-port))
          (do (log! "cached port file disappeared at" port-file "— re-discovering")
              (nrepl/close! conn)
              (swap! session-state assoc :discovered? false :conn nil
                     :discovery-error nil)
              (ensure-connection! launch-flags discover-fn))

          ;; Port changed — shadow restarted on a new ephemeral port.
          (and current-port (not= current-port port))
          (do (log! "nREPL port changed:" port "→" current-port "— reconnecting")
              (nrepl/close! conn)
              (let [conn' (new-conn-for-port current-port)]
                (swap! session-state assoc :port current-port :conn conn')
                (js/Promise.resolve conn')))

          ;; Same port (or env/cwd path without project-home) — fast path.
          :else
          (js/Promise.resolve conn)))))))

;; ---------------------------------------------------------------------------
;; MCP request handlers.
;; ---------------------------------------------------------------------------

(defn- handle-list [_req]
  (js/Promise.resolve #js {:tools (tools/tool-descriptors-js)}))

(declare handle-call*)

(defn- handle-call [launch-flags req extra]
  (let [params (j/get req :params)
        name   (j/get params :name)
        args   (or (j/get params :arguments) #js {})]
    (if-let [unknown (tools/refuse-unknown-tool name)]
      ;; A name absent from the registry (a typo, or an alias the registry
      ;; doesn't carry such as `registry-list`) is rejected HERE, before
      ;; `ensure-connection!`. Otherwise a stock install with no nREPL port runs
      ;; discovery, REJECTS with `:nrepl-port-not-found`, and the request
      ;; never reaches `dispatch-tool*` — so the `:unknown-tool` recovery
      ;; affordances (`:hint` → tools/list, `:available-tools`,
      ;; `:did-you-mean`) are masked behind a confusing transport error.
      ;; "Does this tool exist?" must be diagnosable without a live app.
      ;; The dispatcher's own `:unknown-tool` branch stays as defence in
      ;; depth (direct `tools/invoke` callers + the conformance corpus).
      (js/Promise.resolve unknown)
      (if-let [refusal (writes/refuse-pre-connection name)]
        ;; A gated write tool (restore-epoch / replace-app-db) with
        ;; `--allow-writes` OFF is refused HERE, before `ensure-
        ;; connection!`. Otherwise a stock install with no nREPL port returns
        ;; a misleading `:nrepl-port-not-found` (or runs discovery /
        ;; elicitation) for a request that should be refused locally — the
        ;; default-safe write posture would be observably false at the real
        ;; MCP boundary. The tool body's own gate stays as defence in depth.
        (js/Promise.resolve refusal)
        (handle-call* launch-flags name args extra)))))

(defn handle-call-for-tests
  "Test seam onto the private `handle-call`. Lets the
  server-boundary test drive a `tools/call` request through the SAME
  pre-dispatch path the SDK invokes — proving a disabled write tool is
  refused with `:rf.error/writes-disabled` BEFORE `ensure-connection!`
  (no discovery, no `:nrepl-port-not-found`). Builds the `req` shape the
  SDK hands `handle-call`."
  [launch-flags tool-name args extra]
  (handle-call launch-flags
               #js {:params #js {:name tool-name :arguments args}}
               extra))

(defn- handle-call*
  "Normal tool-dispatch path: ensure the nREPL connection, then route to
  the tool dispatcher. Reached for every tool EXCEPT a gated write
  refused at the pre-connection boundary."
  [launch-flags name args extra]
  (-> (ensure-connection! launch-flags)
      (.then (fn [conn]
               (-> (tools/invoke conn name args extra)
                   (.catch (fn [err]
                             (log! "handler threw for" name "—" (.-message err))
                             ;; Route the server-level error envelope through
                             ;; `wire/result` so the structuredContent
                             ;; projection preserves keyword namespaces (a raw
                             ;; `clj->js` here truncates `:rf.error/*` reason
                             ;; values).
                             (wire/result {:ok?     false
                                           :reason  :handler-threw
                                           :message (.-message err)}
                                          true))))))
      (.catch
        (fn [err]
          ;; Discovery failed — surface a structured tool-call error.
          (let [data    (or (ex-data err) {})
                payload {:ok?    false
                         :reason (or (:rf.error/id data) :nrepl-port-not-found)
                         :hint   (or (:hint data) port-not-found-hint)}
                payload (if-let [cs (:candidates data)]
                          (assoc payload :candidates cs)
                          payload)]
            ;; Same namespace-preserving projection as the handler-threw
            ;; path; a raw `clj->js` drops the namespace on `:rf.error/*`
            ;; reason values.
            (wire/result payload true))))))

;; ---------------------------------------------------------------------------
;; Server boot.
;; ---------------------------------------------------------------------------

(defn build-server
  "Build an MCP `Server` instance with `tools/list` wired to the static
  descriptors and `tools/call` routed to `call-handler` (a `(req,
  extra) → Promise<result>` fn).

  Capabilities declared at construction:

  - `:tools`   — the tool catalogue (this is what we serve).

  Note: SDK 1.29's `listRoots()` / `elicitInput()` aren't gated by
  server-side capability declarations — the server can issue those
  requests at any time and the SDK delivers them to the client. The
  client's `initialize` response surfaces its OWN `roots` /
  `elicitation` capabilities; we observe those via
  `server.getClientCapabilities()` post-init, but the `roots-discovery`
  flow tries the request optimistically and treats rejection as
  graceful degradation."
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
  "Build the MCP server, register handlers, capture the server reference
  for the lazy-discovery flow, and return it. Exposed for tests so they
  can drive the dispatcher without taking over stdin/out.

  `launch-flags` is the parsed `parse-launch-flags` map — the discovery
  cascade reads `:port-file` and `:http-port` from it on first tool call."
  [launch-flags]
  (let [server (build-server (fn [req extra] (handle-call launch-flags req extra)))]
    (reset! server-instance server)
    server))

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

(defn- parse-string-value-flag
  "Generic launch-flag pluck: returns the trailing value of `--name <v>`
  or the inline value of `--name=<v>` for the named flag in `argv`.
  Last occurrence wins (consistent with argv override semantics).
  Returns nil when the flag is absent or given without a value.

  Used by both `--port-file` and `--http-port`; one parser, one shape —
  so a future string-valued flag lands here without growing a per-flag
  micro-parser.

  ## DRY-on-5 trigger

  Two string-valued flags ride through this helper. If a fifth lands,
  switch to a declared schema (vector of `{:flag :type :env}` maps
  reduced over) — more elegant than five hand-rolled `parse-X-flag`
  helpers. Not needed at 2; the 3rd and 4th can ride through unchanged."
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
  "Pluck the value of the `--http-port` launch flag and
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

    --no-eval                — opt OUT of the `eval-cljs` tool. Default
                               is eval-cljs ENABLED — it is the REPL primitive
                               of a pair-debug session. The threat-model
                               rationale (eval expresses every write the
                               --allow-writes gate would block, so the two
                               gates are not independent protections) lives
                               in `tools/eval_cljs.cljs`.
    --allow-sensitive-reads  — opt-in to raw state on snapshot / get-path /
                               subscribe AND raw-value `tap>` emissions from
                               the preload's `app-db-reset!`.
                               Default OFF. Canonical cross-MCP name —
                               matches story-mcp's identically named gate.
    --allow-writes           — opt-in to the state-mutating tools
                               `restore-epoch` (time-travel undo) and
                               `replace-app-db` (state injection).
                               Default OFF. Without the flag both return
                               `{:ok? false :reason :rf.error/writes-disabled}`
                               without touching the nREPL socket. `dispatch`
                               (which drives the app's own handlers) is
                               unaffected. NOTE: this gate protects the audit
                               trail of named writes; it is NOT a defence
                               against eval-driven writes (eval-cljs can
                               express the same writes).
    --port-file <path>       — explicit, cwd-independent nREPL port-file
                               path. Highest precedence in the
                               port-discovery chain — see
                               `nrepl/discover-port`. Accepts both
                               `--port-file <path>` and `--port-file=<path>`.
    --http-port <n>          — override shadow-cljs's HTTP server port
                               for the auto-discovery probe.
                               Defaults to 9630 (shadow's standard).
                               Only used when steps 1-2 of the cascade
                               miss; setting it has no effect when
                               --port-file or SHADOW_CLJS_NREPL_PORT is
                               present.

  Returns `{:eval-allowed? bool :allow-raw-state? bool :allow-writes? bool
  :port-file str-or-nil :http-port int-or-nil}`.
  `:eval-allowed?` is true by default; passing `--no-eval` flips it
  false. Unknown flags are ignored —
  node's shadow-cljs entry passes its own argv prelude (script path),
  and future flags can land here without breaking older invocations."
  [argv]
  {:eval-allowed?    (not (boolean (some #{"--no-eval"} argv)))
   :allow-raw-state? (boolean (some #{"--allow-sensitive-reads"} argv))
   :allow-writes?    (boolean (some #{"--allow-writes"} argv))
   :port-file        (parse-port-file-flag argv)
   :http-port        (parse-http-port-flag argv)})

;; ---------------------------------------------------------------------------
;; Launch-config diagnostics.
;;
;; The parsers above are deliberately PERMISSIVE — they pluck the flags
;; they understand and silently ignore everything else (a typo, a stale
;; legacy name, a malformed value). Permissive parsing is the right
;; default for the wrapper-argv prelude (node passes the script path,
;; shadow passes its own args), but for an MCP server configured through
;; agent-host JSON it is a footgun: a one-character typo in a safety
;; flag (`--no-eavl`) silently leaves the gate at its default, a
;; misspelled `--port-file` falls through to discovery, and an invalid
;; resource cap quietly reverts to the default. The operator believes
;; they requested one posture; the server starts in another.
;;
;; `launch-diagnostics` closes that gap. It runs over the SAME argv the
;; parsers consume and returns a vector of structured diagnostic maps —
;; one per rejected / suspicious token — naming the offending input and
;; the effective fallback. `main` logs each to stderr at boot, BEFORE
;; the transport announces readiness, so the mismatch is visible. We
;; warn rather than hard-fail: a hard boot-fail makes the server vanish
;; from the agent host (no diagnostic reaches the operator at all),
;; whereas an explicit stderr line names the problem AND lets a working
;; (default-posture) server still come up.
;; ---------------------------------------------------------------------------

(def ^:private known-boolean-flags
  "The recognised value-less boolean launch flags. Membership here is
  what distinguishes a real flag from an unknown `--*` token."
  #{"--no-eval" "--allow-sensitive-reads" "--allow-writes"})

(def ^:private known-valued-flags
  "The recognised valued launch flags (`--name <v>` or `--name=<v>`).
  These plus `known-boolean-flags` and `resource/flag->key` form the
  full set of `--*` tokens the server understands."
  #{"--port-file" "--http-port"})

(def ^:private removed-launch-flags
  "Flag names this server does not accept, mapped to a diagnostic
  naming the replacement (pre-alpha, no back-compat shim). An operator
  carrying a stale `~/.claude.json` gets an INTENTIONAL diagnostic
  naming the replacement rather than a silent no-op that leaves the
  server in an unexpected posture. `--allow-raw-state` maps to
  `--allow-sensitive-reads`; `--allow-eval` has no replacement — eval-cljs
  defaults ON, so pass `--no-eval` to opt OUT."
  {"--allow-raw-state" "renamed to --allow-sensitive-reads (rf2-2x3ql)"
   "--allow-eval"      "removed — eval-cljs now defaults ENABLED; pass --no-eval to opt OUT (rf2-a0z0h)"})

(defn- flag-prefix
  "The bare flag name of an argv token: the part before `=` for the
  equals form, the whole token otherwise. `--http-port=9700` → `--http-port`."
  [token]
  (first (str/split token #"=" 2)))

(defn- known-flag?
  "Is `prefix` a flag this server recognises (boolean, valued, or a
  resource-control flag)? Used to separate genuine unknown flags from
  the recognised set when scanning for typos."
  [prefix]
  (or (contains? known-boolean-flags prefix)
      (contains? known-valued-flags prefix)
      (contains? resource/flag->key prefix)))

(defn launch-diagnostics
  "Scan `argv` for misconfigured launch input and return a vector of
  structured diagnostic maps (empty when the config is clean). Each
  entry is `{:rf.config/severity :warn :rf.config/input <token>
  :rf.config/issue <kw> :rf.config/effect <string>}` — the rejected
  input, what's wrong, and the effective fallback the operator gets.

  Detects, against the declared flag schema:

    - `:removed-flag`        — a renamed / removed legacy flag (names the
                               replacement; pre-alpha, no silent no-op).
    - `:unknown-flag`        — a `--*` token matching no known flag.
    - `:missing-value`       — a valued flag (`--port-file` / `--http-port`)
                               present with no value (trailing, or
                               immediately followed by another flag).
    - `:malformed-value`     — `--http-port` with a non-numeric value,
                               or a resource-control flag whose value
                               isn't a positive integer.

  Resource ENV vars are validated separately by
  `resource-env-diagnostics` (they share the same diagnostic shape).

  `argv` here is the launch argv AFTER node/shadow strip their own
  prelude — i.e. the same vector `parse-launch-flags` sees. Tokens that
  don't start with `--` (positional prelude residue) are ignored: the
  schema governs flag tokens only."
  [argv]
  (let [argv (vec argv)]
    (->> (map-indexed vector argv)
         (keep
           (fn [[i token]]
             (when (str/starts-with? token "--")
               (let [prefix       (flag-prefix token)
                     has-inline?  (str/includes? token "=")
                     inline-val   (when has-inline? (second (str/split token #"=" 2)))
                     next-token   (get argv (inc i))
                     space-val    (when (and (not has-inline?)
                                             next-token
                                             (not (str/starts-with? next-token "--")))
                                    next-token)]
                 (cond
                   ;; Removed / renamed legacy name — intentional diagnostic.
                   (contains? removed-launch-flags prefix)
                   {:rf.config/severity :warn
                    :rf.config/input    token
                    :rf.config/issue    :removed-flag
                    :rf.config/effect   (str "ignored — " (get removed-launch-flags prefix))}

                   ;; Unknown --flag: a typo or a flag from another tool.
                   (not (known-flag? prefix))
                   {:rf.config/severity :warn
                    :rf.config/input    token
                    :rf.config/issue    :unknown-flag
                    :rf.config/effect   "ignored — not a recognised re-frame2-pair-mcp launch flag"}

                   ;; Valued flag present with no value.
                   (and (contains? known-valued-flags prefix)
                        (nil? inline-val)
                        (nil? space-val))
                   {:rf.config/severity :warn
                    :rf.config/input    token
                    :rf.config/issue    :missing-value
                    :rf.config/effect   (str prefix " supplied with no value — falling back to the default discovery / behaviour")}

                   ;; --http-port with a non-numeric value.
                   (and (= prefix "--http-port")
                        (let [raw (or inline-val space-val)]
                          (and (some? raw)
                               (js/isNaN (js/parseInt raw 10)))))
                   {:rf.config/severity :warn
                    :rf.config/input    token
                    :rf.config/issue    :malformed-value
                    :rf.config/effect   "non-numeric --http-port — falling back to the default shadow HTTP port (9630)"}

                   ;; Resource-control flag in the SPACE form. The resource
                   ;; parser only accepts `--name=N`, so a
                   ;; space-form `--max-concurrent-streams 20` is silently
                   ;; dropped — name it as a malformed usage.
                   (and (contains? resource/flag->key prefix)
                        (not has-inline?))
                   {:rf.config/severity :warn
                    :rf.config/input    token
                    :rf.config/issue    :malformed-value
                    :rf.config/effect   (str prefix " requires the equals form (" prefix "=N) — falling back to the documented default")}

                   ;; Resource-control flag whose value isn't a positive int.
                   (and (contains? resource/flag->key prefix)
                        has-inline?
                        (let [n (parse-long (or inline-val ""))]
                          (not (and n (pos? n)))))
                   {:rf.config/severity :warn
                    :rf.config/input    token
                    :rf.config/issue    :malformed-value
                    :rf.config/effect   (str prefix " value must be a positive integer — falling back to the documented default")}

                   :else nil)))))
         (vec))))

(defn resource-env-diagnostics
  "Scan the resource-control ENV vars for set-but-invalid values and
  return a vector of diagnostic maps (same shape as `launch-diagnostics`).
  A blank, non-numeric, or non-positive env var is currently a SILENT
  skip (`resource/read-resource-env`); this names it so an operator who
  exported `RE_FRAME2_PAIR_MCP_MAX_STREAMS=0` learns their cap reverted
  to the default rather than discovering it the hard way.

  Takes the env object (`process.env`-shaped) so tests can stub it;
  the 0-arity reads `process.env`."
  ([] (resource-env-diagnostics (j/get js/process :env)))
  ([env-obj]
   (->> resource/env->key
        (keep
          (fn [[var-name _k]]
            (let [raw (some-> env-obj (j/get var-name))]
              (when (and (string? raw) (seq raw))
                (let [n (parse-long raw)]
                  (when-not (and n (pos? n))
                    {:rf.config/severity :warn
                     :rf.config/input    (str var-name "=" raw)
                     :rf.config/issue    :malformed-env
                     :rf.config/effect   (str var-name " must be a positive integer — falling back to the documented default")}))))))
        (vec))))

(defn- log-launch-diagnostics!
  "Emit each launch-config diagnostic to stderr at boot, BEFORE the
  transport announces readiness. No-op when the config is clean."
  [argv]
  (doseq [{:rf.config/keys [input issue effect]}
          (into (launch-diagnostics argv) (resource-env-diagnostics))]
    (log! (str "launch-config WARNING [" (name issue) "]: " input " — " effect))))

(defn- apply-launch-flags!
  "Wire launch-flag state into the relevant tool gates. Called once
  before the dispatcher accepts requests."
  [{:keys [eval-allowed? allow-raw-state? allow-writes?]}]
  (eval-cljs/set-eval-allowed! eval-allowed?)
  (raw-state/set-allow-raw-state! allow-raw-state?)
  (writes/set-allow-writes! allow-writes?)
  (log! "eval-cljs:" (if eval-allowed? "enabled (default; pass --no-eval to opt out)" "DISABLED (--no-eval)"))
  ;; The "allowed" / "gated" wording matches story-mcp's
  ;; `--allow-sensitive-reads` shape — pair-mcp uses the same canonical
  ;; CLI-flag name so operators reading multi-MCP logs see one
  ;; vocabulary.
  (log! "Sensitive reads:" (if allow-raw-state? "allowed (--allow-sensitive-reads)" "gated (default; pass --allow-sensitive-reads to opt in)"))
  ;; The state-mutating tool gate. Default OFF; restore-epoch /
  ;; replace-app-db replace app-db wholesale and the gate keeps the
  ;; named-write audit trail clean. Note: this gate does NOT defend
  ;; against eval-driven writes (eval-cljs can express the same
  ;; writes); see `tools/eval_cljs.cljs` for the rationale.
  (log! "Writes:" (if allow-writes? "ENABLED (--allow-writes)" "disabled (default; pass --allow-writes to opt in)")))

(defn- apply-resource-controls!
  "Read resource-control config from env + CLI flags and push it into
  the resource-controls atoms. Logs the effective values
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
    ;; Name any misconfigured launch input (typo'd flags, unaccepted
    ;; legacy names, malformed values, invalid env vars) BEFORE the gates
    ;; are applied and the transport announces readiness, so a silent
    ;; posture-mismatch surfaces in the boot log.
    (log-launch-diagnostics! argv)
    (apply-launch-flags! launch-flags)
    (apply-resource-controls! argv)
    (when-let [pf (:port-file launch-flags)]
      (log! "nREPL port-file (--port-file):" pf))
    (when-let [hp (:http-port launch-flags)]
      (log! "shadow HTTP port (--http-port):" hp))
    ;; Port discovery is LAZY (first tool call). We boot the
    ;; transport with the discovery cascade ready to fire on demand. This
    ;; matters because the MCP `roots/list` request can only succeed
    ;; AFTER the client's `initialize` handshake completes — i.e. after
    ;; transport.connect resolves. Driving discovery from `main` would
    ;; race the handshake. Instead we capture the launch-flags in a
    ;; closure that `ensure-connection!` consults on each tool call.
    (let [server (boot! launch-flags)]
      (log! "starting stdio transport")
      (connect-transport! server "ready — awaiting MCP frames on stdin (nREPL discovery deferred to first tool call)"))))

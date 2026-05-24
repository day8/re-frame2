(ns re-frame2-pair-mcp.server
  "MCP server entry-point. Wires the npm `@modelcontextprotocol/sdk`
  stdio transport to the tool dispatcher in `tools.cljs`, against a
  single persistent nREPL connection (`nrepl.cljs`).

  ## Lifecycle

  1. boot: parse launch flags, build the MCP `Server`, connect the
     stdio transport. The persistent nREPL socket is NOT opened
     here — discovery is lazy (rf2-3grub) so it can ask the MCP
     client for workspace roots after initialization completes.
  2. `initialize`: standard MCP handshake. Server learns the client's
     capabilities (`roots`, `elicitation`).
  3. `tools/list`: returns the full tool catalogue — see
     `tools.registry/tools` for the authoritative list (the single
     source of truth).
  4. `tools/call` (first time): runs the port-discovery cascade
     (rf2-3grub) — the five-step precedence:

        1. `--port-file <path>` explicit override (rf2-3dbwh)
        2. `$SHADOW_CLJS_NREPL_PORT` env var
        3. MCP `roots/list` → walk for `shadow-cljs.edn` → port-file
        4. Shadow HTTP probe at `:9630` → `/api/project-info` (rf2-umoz2)
        5. CWD-relative scan (legacy fallback)

     Step 3 is the rf2-3grub primary path — generic, zero-config, no
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
;; Session-cache for the lazy-discovered project (rf2-3grub).
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
       :discovery-error <ex-info | nil>}   ;; sticky error from prior attempt

  Resetting `:discovered? false` triggers a full re-discovery on the
  next tool call — used by the operator-initiated re-attach branch
  (deferred to a follow-up bead)."
  (atom {:project-home    nil
         :port-file       nil
         :port            nil
         :conn            nil
         :discovered?     false
         :discovery-error nil}))

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
  "First-tool-call discovery (rf2-3grub). Run the five-step cascade in
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
                                         :where    'pair-mcp/discover-and-cache!
                                         :recovery :pick-via-port-file
                                         :reason   (:reason payload)
                                         :candidates (:candidates payload)
                                         :hint     (:hint payload)}))))))

              ;; Nothing resolved.
              :else
              (throw (ex-info ":rf.error/pair-mcp-nrepl-port-not-found"
                              {:rf.error/id :rf.error/pair-mcp-nrepl-port-not-found
                               :where    'pair-mcp/discover-and-cache!
                               :recovery :no-recovery
                               :reason   port-not-found-hint
                               :hint     port-not-found-hint}))))))))

(defn- ensure-connection!
  "Lazy-discovery entry: called before every tool dispatch. Three paths:

  1. First call (or after invalidation) — runs `discover-and-cache!`.
  2. Cached + port-file still resolves to the same port — fast path,
     returns the cached conn atom.
  3. Cached + port-file content changed — shadow was restarted; close
     the stale socket, swap in a new conn for the new port.

  Returns a Promise resolving to the live conn atom, or rejecting with
  the cached/structured discovery error."
  [launch-flags]
  (let [{:keys [discovered? discovery-error port-file port conn]} @session-state]
    (cond
      (and (not discovered?) (some? discovery-error))
      (js/Promise.reject discovery-error)

      (not discovered?)
      (-> (discover-and-cache! launch-flags)
          (.then (fn [_] (:conn @session-state)))
          (.catch (fn [e]
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
              (ensure-connection! launch-flags))

          ;; Port changed — shadow restarted on a new ephemeral port.
          (and current-port (not= current-port port))
          (do (log! "nREPL port changed:" port "→" current-port "— reconnecting")
              (nrepl/close! conn)
              (let [conn' (new-conn-for-port current-port)]
                (swap! session-state assoc :port current-port :conn conn')
                (js/Promise.resolve conn')))

          ;; Same port (or env/cwd path without project-home) — fast path.
          :else
          (js/Promise.resolve conn))))))

;; ---------------------------------------------------------------------------
;; MCP request handlers.
;; ---------------------------------------------------------------------------

(defn- handle-list [_req]
  (js/Promise.resolve #js {:tools (tools/tool-descriptors-js)}))

(defn- handle-call [launch-flags req extra]
  (let [params (j/get req :params)
        name   (j/get params :name)
        args   (or (j/get params :arguments) #js {})]
    (-> (ensure-connection! launch-flags)
        (.then (fn [conn]
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
  extra) → Promise<result>` fn).

  Capabilities declared at construction (rf2-3grub):

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
    ;; rf2-3grub — port discovery is LAZY (first tool call). We boot the
    ;; transport with the discovery cascade ready to fire on demand. This
    ;; matters because the MCP `roots/list` request can only succeed
    ;; AFTER the client's `initialize` handshake completes — i.e. after
    ;; transport.connect resolves. Driving discovery from `main` would
    ;; race the handshake. Instead we capture the launch-flags in a
    ;; closure that `ensure-connection!` consults on each tool call.
    (let [server (boot! launch-flags)]
      (log! "starting stdio transport")
      (connect-transport! server "ready — awaiting MCP frames on stdin (nREPL discovery deferred to first tool call)"))))

(ns re-frame.story-mcp.config
  "Compile + runtime configuration for the Story-MCP server.

  Per IMPL-SPEC §7.3 the write surface (`register-variant`,
  `unregister-variant`) is gated behind `allow-writes?`. The default is
  `false`: writes are dev-only and must be enabled explicitly. CI runs
  should always boot with the gate closed; an agent host that wants the
  self-healing loop (write story → run → read failures → fix) opts in.

  ## Surface

  - `allow-writes?` — atom holding the boolean flag. Read by the
    write-surface tools (`re-frame.story-mcp.tools/register-variant` and
    friends); a `false` value causes the tool to return an MCP error
    result (`isError: true`) rather than a protocol-level error per the
    spec/2025-06-18 tools §Error Handling guidance.
  - `set-allow-writes!` — write helper. Called from `-main` (`--allow-writes`
    CLI flag), from the JVM property `-Drf.story-mcp.allow-writes=true`,
    or programmatically (tests).
  - `protocol-version` — the MCP protocol version this server advertises.

  ## Why a separate ns

  Symmetry with `re-frame.story.config` — keep the boolean knobs together
  so an agent host or test fixture can adjust them without reaching into
  the server / tools namespaces. Per IMPL-SPEC §13.2 #6 the
  protocol-version target is a documented decision the implementation
  bead (rf2-tgci) lands."
  (:require [clojure.string :as str]))

;; ---- MCP protocol version --------------------------------------------------

(def ^:const protocol-version
  "The MCP protocol version this server advertises in the `initialize`
  handshake. Pinned to `2025-06-18` — the latest spec at Stage 7 land.

  The spec's version-negotiation rule: if the client requests a version
  we don't recognise, we still respond with the one we support; the
  client decides whether to disconnect. Per
  https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle
  §Version Negotiation."
  "2025-06-18")

(def ^:const server-name
  "Server `name` advertised in the `initialize` response's `serverInfo`."
  "re-frame2-story-mcp")

;; ---- write-surface gate ---------------------------------------------------

(defonce
  ^{:doc "Atom holding the write-surface gate. Defaults to `false`. Per
         IMPL-SPEC §7.3 the write surface (`register-variant` /
         `unregister-variant`) is gated; CI runs leave this `false` and
         dev hosts opt in.

         The flag is a runtime atom (not a `def`) so tests can flip it
         per-fixture without reloading the namespace. The CLI flag
         `--allow-writes` and the JVM property `-Drf.story-mcp.allow-writes`
         seed it at server boot."}
  allow-writes?
  (atom false))

(defn set-allow-writes!
  "Set the write-surface gate. Idempotent. Returns the new value."
  [v]
  (let [coerced (boolean v)]
    (reset! allow-writes? coerced)
    coerced))

(defn writes-allowed?
  "True iff the write surface is currently enabled."
  []
  (boolean @allow-writes?))

;; ---- boot config from env / sysprop ---------------------------------------

(defn read-boot-config
  "Read boot-time configuration from JVM sysprops + env vars.

  Recognised sources (later wins):

  - JVM sysprop `rf.story-mcp.allow-writes` — `\"true\"` / `\"1\"` enables.
  - Env var `RF_STORY_MCP_ALLOW_WRITES` — same.

  Returns a map `{:allow-writes? boolean}`. The caller (`-main`) merges
  in CLI overrides before calling `apply-config!`."
  []
  (let [sysprop (some-> (System/getProperty "rf.story-mcp.allow-writes")
                        str/trim
                        str/lower-case)
        envv    (some-> (System/getenv "RF_STORY_MCP_ALLOW_WRITES")
                        str/trim
                        str/lower-case)
        truthy? #(contains? #{"true" "1" "yes" "y" "on"} %)]
    {:allow-writes? (boolean (or (truthy? sysprop)
                                 (truthy? envv)))}))

(defn apply-config!
  "Apply a boot-config map to the runtime atoms. Returns the applied map."
  [{:keys [allow-writes?] :as cfg}]
  (when (some? allow-writes?)
    (set-allow-writes! allow-writes?))
  cfg)

;; ---- stage marker --------------------------------------------------------

(def stage
  "Sentinel naming which Stage's surface is loaded. Matches the marker
  pattern from `re-frame.story/stage` — Story Stage 7 lands the MCP
  agent surface in a separate jar."
  :mcp)

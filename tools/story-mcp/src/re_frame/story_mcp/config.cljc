(ns re-frame.story-mcp.config
  "Compile + runtime configuration for the Story-MCP server.

  Per IMPL-SPEC §7.3 the write surface (`register-variant`,
  `unregister-variant`) is gated behind `allow-writes?`. The default is
  `false`: writes are dev-only and must be enabled explicitly. CI runs
  should always boot with the gate closed; an agent host that wants the
  self-healing loop (write story → run → read failures → fix) opts in.

  ## Surface

  - `allow-writes?` — atom holding the boolean flag. Read by the
    write-surface tools (`re-frame.story-mcp.tools.write/register-variant`
    and friends); a `false` value causes the tool to return an MCP error
    result (`isError: true`) rather than a protocol-level error per the
    spec/2025-06-18 tools §Error Handling guidance.
  - `set-allow-writes!` — write helper. Called from `-main` (`--allow-writes`
    CLI flag), from the JVM property `-Drf.story-mcp.allow-writes=true`,
    or programmatically (tests).
  - `allow-sensitive-reads?` — atom holding the sensitive-read gate. Read
    by the wire-egress scrubbers (`args/include-sensitive?`); a `false`
    value forces redaction regardless of any per-call `:include-sensitive`
    arg. The default-OFF posture matches the cross-MCP convention for
    privacy gates: an operator who genuinely needs raw sensitive state
    opts in explicitly. (Note: re-frame2-pair-mcp's eval-cljs gate took
    the opposite path in rf2-a0z0h — default ON with `--no-eval` as the
    opt-out — because eval is the REPL primitive of a pair-debug
    session and a default-OFF eval gate did not add a protection
    separable from `--allow-writes`. Sensitive-reads here are NOT in
    that position: raw reads can pour the entire app-db into a wire log
    without the operator ever typing the secret, so the privacy gate
    stays default-OFF.)
  - `set-allow-sensitive-reads!` — write helper. Same three input paths
    as `allow-writes?`: `--allow-sensitive-reads` CLI flag, JVM property
    `-Drf.story-mcp.allow-sensitive-reads=true`, or env var
    `RF_STORY_MCP_ALLOW_SENSITIVE_READS=true`.
  - `protocol-version` — the MCP protocol version this server advertises.

  ## Why a separate ns

  Symmetry with `re-frame.story.config` — keep the boolean knobs together
  so an agent host or test fixture can adjust them without reaching into
  the server / tools namespaces. Per IMPL-SPEC §13.2 #6 the
  protocol-version target is a documented decision the implementation
  bead (rf2-tgci) lands."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.mcp-base.args :as args]))

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

(defn read-version
  "Read the project's VERSION file from the classpath. Best-effort: when
  the resource isn't on the classpath (uberjar deploys, REPL hosts that
  haven't added the project root), falls back to `\"dev\"`. Returns a
  trimmed string. Used by the `initialize` handshake's
  `:serverInfo :version` slot."
  []
  (try
    (or (some-> (io/resource "VERSION") slurp str/trim)
        "dev")
    (catch Throwable _ "dev")))

;; ---- origin tag -----------------------------------------------------------

(def ^:const origin
  "The `:origin` value every story-mcp write carries per
  [`spec/Cross-Cutting-Designs.md` §5 Origin tagging](../../../spec/Cross-Cutting-Designs.md).

  The convention: every dispatching / writing surface picks one keyword
  so post-mortem queries can answer 'who wrote / dispatched this?'.
  Story-mcp doesn't ship `dispatch` (its surface is JVM-side, no event
  bus access), but the `register-variant` and `record-as-variant`
  writes stamp this value onto the registered variant body so
  inspection of `(story/variant->edn vk)` reveals which variants came
  from the MCP write surface.

  Pinned here as `:const` so the single source of truth lives next to
  the other server identity constants (`server-name`,
  `protocol-version`)."
  :story-mcp)

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
  "Set the write-surface gate. Idempotent. Returns the new value.
  Accepts booleans, nil, or string-form booleans (`\"true\"`/`\"1\"`/
  `\"yes\"`/`\"on\"`, case-insensitive) via the cross-MCP
  `args/parse-boolean` parser — so the CLI flag, sysprop, and env-var
  surfaces share one coercion."
  [v]
  (let [coerced (args/parse-boolean v false)]
    (reset! allow-writes? coerced)
    coerced))

(defn writes-allowed?
  "True iff the write surface is currently enabled."
  []
  (boolean @allow-writes?))

;; ---- sensitive-read gate (rf2-g9fje) --------------------------------------
;;
;; Per the rf2-uaymx (b) decision, raw sensitive-state reads are an
;; operator-only opt-in. The wire-egress helpers
;; (`args/include-sensitive?`) defer to this atom — when it is
;; `false` the per-call `:include-sensitive` arg is silently treated as
;; `false`, so a hostile or careless caller cannot exfiltrate declared-
;; sensitive `:app-db` slots / assertion records by flipping a JSON
;; boolean. Closed-by-default; opened by `--allow-sensitive-reads` CLI
;; flag, JVM sysprop `rf.story-mcp.allow-sensitive-reads=true`, or env
;; var `RF_STORY_MCP_ALLOW_SENSITIVE_READS=true`.
;;
;; (Cross-MCP note: re-frame2-pair-mcp's eval-cljs gate flipped to
;; default-ON in rf2-a0z0h — eval is the REPL primitive of a pair-debug
;; session and a default-OFF eval gate did not add a protection
;; separable from `--allow-writes`. The sensitive-reads gate here is
;; NOT in that position: raw reads can pour the entire app-db into a
;; wire log without the operator ever typing the secret, so this gate
;; keeps the default-OFF posture.)

(defonce
  ^{:doc "Atom holding the sensitive-read gate. Defaults to `false`. Per
         the rf2-uaymx (b) decision the per-call `:include-sensitive`
         arg is honoured ONLY when this atom is also `true`."}
  allow-sensitive-reads?
  (atom false))

(defn set-allow-sensitive-reads!
  "Set the sensitive-read gate. Idempotent. Returns the new value.
  Accepts the same string-form booleans as `set-allow-writes!`."
  [v]
  (let [coerced (args/parse-boolean v false)]
    (reset! allow-sensitive-reads? coerced)
    coerced))

(defn sensitive-reads-allowed?
  "True iff raw sensitive-read egress is currently enabled. The wire-
  egress helpers AND each per-call `:include-sensitive` arg must both
  be `true` for raw values to cross the wire."
  []
  (boolean @allow-sensitive-reads?))

;; ---- boot config from env / sysprop ---------------------------------------

(defn read-boot-config
  "Read boot-time configuration from JVM sysprops + env vars.

  Recognised sources (later wins):

  - JVM sysprop `rf.story-mcp.allow-writes` — `\"true\"` / `\"1\"` enables.
  - Env var `RF_STORY_MCP_ALLOW_WRITES` — same.
  - JVM sysprop `rf.story-mcp.allow-sensitive-reads` — same shape.
  - Env var `RF_STORY_MCP_ALLOW_SENSITIVE_READS` — same shape.

  All sources are parsed via the cross-MCP `args/parse-boolean`
  primitive (rf2-vw4sq) so the truthy-string vocabulary
  (`true`/`1`/`yes`/`y`/`on`, case-insensitive) is the same one an
  agent learns once.

  Returns a map `{:allow-writes? boolean :allow-sensitive-reads? boolean}`.
  The caller (`-main`) merges in CLI overrides before calling
  `apply-config!`."
  []
  (let [writes-sysprop    (System/getProperty "rf.story-mcp.allow-writes")
        writes-env        (System/getenv "RF_STORY_MCP_ALLOW_WRITES")
        sensitive-sysprop (System/getProperty "rf.story-mcp.allow-sensitive-reads")
        sensitive-env     (System/getenv "RF_STORY_MCP_ALLOW_SENSITIVE_READS")]
    {:allow-writes?          (or (args/parse-boolean writes-sysprop false)
                                 (args/parse-boolean writes-env false))
     :allow-sensitive-reads? (or (args/parse-boolean sensitive-sysprop false)
                                 (args/parse-boolean sensitive-env false))}))

(defn apply-config!
  "Apply a boot-config map to the runtime atoms. Returns the applied map."
  [{:keys [allow-writes? allow-sensitive-reads?] :as cfg}]
  (when (some? allow-writes?)
    (set-allow-writes! allow-writes?))
  (when (some? allow-sensitive-reads?)
    (set-allow-sensitive-reads! allow-sensitive-reads?))
  cfg)

;; ---- stage marker --------------------------------------------------------

(def stage
  "Sentinel naming which Stage's surface is loaded. Matches the marker
  pattern from `re-frame.story/stage` — Story Stage 7 lands the MCP
  agent surface in a separate jar."
  :mcp)

(ns re-frame.story-mcp.tools.write
  "Write-category tool handlers (v1.1, dev-only — gated). Per
  IMPL-SPEC §7.3 the write surface is dev-only; CI runs leave the gate
  off (`config/writes-allowed?` defaults false). Every handler here
  short-circuits with a gate-denial error result when the flag is off.

  - `register-variant`   — register a variant programmatically.
  - `unregister-variant` — symmetric removal.

  The third write-surface tool, `record-as-variant` (the recorder
  bridge, rf2-luhdu), lives in `re-frame.story-mcp.tools.recorder` —
  it has enough body to warrant its own ns under the rf2-zkca8
  leaf-size ceiling. That ns exposes its own `descriptors` vec which
  `tools.registry/tool-registry` concatenates after `write/descriptors`
  so IMPL-SPEC §7.3 order survives the file split."
  (:require [clojure.edn :as edn]
            [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.tools.helpers :as h]
            [re-frame.story-mcp.tools.schemas :as s]))

(defn assert-writes-allowed
  "Returns nil when writes are allowed; an error-result otherwise. The
  caller short-circuits on the non-nil branch. Threads `tool-name` (the
  caller's MCP tool name, sans namespace) through to the error's
  `:structuredContent :tool` slot so agents inspecting a gated-error
  payload see the tool that actually tripped the gate — not a hardcoded
  string. Three callers today: `register-variant`, `unregister-variant`,
  and `record-as-variant` (via the recorder ns)."
  [tool-name]
  (when-not (config/writes-allowed?)
    (h/error-result
      (str "Write surface disabled. Set `:rf.story-mcp/allow-writes?` "
           "(or `--allow-writes` CLI flag, or "
           "`-Drf.story-mcp.allow-writes=true` JVM property) to enable. "
           "Per IMPL-SPEC §7.3 the write surface is dev-only; CI runs "
           "should leave it off.")
      {:gated true :tool tool-name})))

(defn- coerce-body
  "Normalise the `:body` arg to a Clojure map, or return one of two
  sentinel keywords on parse / shape failure:

    `::edn-error` — `:body` was a string but `edn/read-string` threw.
    `::not-a-map` — `:body` was not a map nor a map-string."
  [body]
  (cond
    (map? body)    body
    (string? body) (try (let [v (edn/read-string body)]
                          (if (map? v) v ::not-a-map))
                        (catch Throwable _ ::edn-error))
    :else          ::not-a-map))

(defn- register-or-error
  "Invoke `reg-variant*` with `:origin` stamped. Returns the success
  result, or an `error-result` wrapping the registrar's throw."
  [vk body-v]
  (try
    ;; Stamp `:origin :story-mcp` per spec/Cross-Cutting-Designs.md §5
    ;; — every write surface tags its writes so post-mortem queries
    ;; can identify which actor produced the registration.
    (let [id      (story/reg-variant* vk (assoc body-v :origin config/origin))
          payload {:variant-id id :registered? true}]
      (h/text-result (h/pr-edn payload) payload))
    (catch Throwable e
      (h/error-result (str "Registration failed: " (ex-message e))
                      (merge {:variant-id vk}
                             (select-keys (ex-data e) [:rf.error :explain]))))))

(defn tool-register-variant
  "Write: programmatically register a variant. Gated behind
  `allow-writes?` per IMPL-SPEC §7.3.

  Args:
    :variant-id  required — `:story.<path>/<variant>` keyword id.
    :body        required — the variant body (a map; will be read as
                 EDN via `clojure.edn/read-string` if a string is sent
                 over the wire).

  Pipeline (short-circuits at the first error-result):

    gate → required-arg :variant-id → required-arg :body →
    coerce-body → reg-variant* + stamp :origin."
  [arguments]
  (or (assert-writes-allowed "register-variant")
      (let [[vid err-vid]   (h/required-arg arguments :variant-id)
            [body err-body] (when-not err-vid (h/required-arg arguments :body))]
        (or err-vid err-body
            (let [body-v (coerce-body body)]
              (case body-v
                ::edn-error
                (h/error-result ":body must be a map or a valid EDN string.")

                ::not-a-map
                (h/error-result (str ":body must be a map; got " (some-> body class .getName)))

                (register-or-error (args/parse-keyword vid) body-v)))))))

(defn tool-unregister-variant
  "Write: programmatically unregister a variant. Gated behind
  `allow-writes?`."
  [arguments]
  (or (assert-writes-allowed "unregister-variant")
      (h/with-variant-id arguments
        (fn [vk]
          (let [had? (some? (story/variant->edn vk))]
            (story/unregister! :variant vk)
            (let [payload {:variant-id vk :unregistered? had?}]
              (h/text-result (h/pr-edn payload) payload)))))))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Write-category descriptors for the in-ns tools (register /
  unregister), in IMPL-SPEC §7.3 order.

  `record-as-variant` lives in `re-frame.story-mcp.tools.recorder` and
  is concatenated by `tools.registry/tool-registry` after this vec —
  not by this ns — to avoid a load-time cycle (recorder requires
  write for the gate fn)."
  [{:name           "register-variant"
    :category       :write
    :description    "Register a variant programmatically. GATED behind `:rf.story-mcp/allow-writes?` (default false). Enables the self-healing loop: write story → run → read failures → fix."
    :typicalTokens  100
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                {:variant-id s/kw-or-string
                                 :body {:oneOf [{:type "object"}
                                                {:type "string"
                                                 :description "EDN-encoded variant body, parsed via clojure.edn/read-string."}]}})
                  :required ["variant-id" "body"]
                  :additionalProperties false}
    :handler     tool-register-variant}

   {:name           "unregister-variant"
    :category       :write
    :description    "Unregister a variant. GATED behind `:rf.story-mcp/allow-writes?` (default false). Symmetric to `register-variant`."
    :typicalTokens  100
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-unregister-variant}])

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

;; ---- EDN reader hardening (rf2-g9fje, fix 2/3) ----------------------------
;;
;; `:body` arrives as either a JSON object (preferred — round-trippable map)
;; or an EDN string (legacy; needed because JSON has no native keyword type
;; and the registrar's variant schema demands `:tags #{:dev :docs}` and the
;; like). The string path is retained because the mcp-conformance probe
;; (`tools/mcp-conformance/test/end-to-end-story.js`) registers its fixture
;; variant via the EDN-string path — JSON's keyword-blind surface would
;; otherwise force a coercion pass the registrar isn't shaped for.
;;
;; The hardening matches the rf2-uaymx audit's "if EDN strings must stay"
;; remediation:
;;
;;   1. Reject any tagged literal (`#<reader-tag> ...`) by setting both
;;      `:readers {}` (no custom tags admitted) AND `:default` to a
;;      throwing handler — `edn/read-string`'s default behaviour is to
;;      silently swallow unknown tags via `*default-data-reader-fn*`,
;;      which is a footgun on agent-supplied input.
;;
;;   2. Cap payload size at 64KB. A 64KB EDN map is generous for a
;;      variant body (`:doc` + `:args` + a few `:tags`); abusive payloads
;;      get a clean reject rather than allocating a megabyte.
;;
;;   3. Cap nesting depth at 64 levels. The registrar's body shape is at
;;      most 3-4 levels deep (variant → `:args` → user-supplied map →
;;      values); 64 is unreachable from a legitimate caller and short-
;;      circuits a deeply-nested payload before `edn/read-string` walks it.

(def ^:const ^:private max-body-bytes
  "Hard ceiling on `:body` EDN payload size (UTF-8 bytes). 64KB. A
  legitimate variant body — `:doc`, `:args`, a handful of `:tags` — is
  well under 1KB; this ceiling rejects abusive payloads cleanly without
  letting `edn/read-string` walk a megabyte string."
  (* 64 1024))

(def ^:const ^:private max-edn-depth
  "Hard ceiling on nesting depth of the parsed EDN body. The registrar's
  variant shape tops out at 3-4 levels; 64 is far past any legitimate
  use and short-circuits pathological inputs before they pressure
  `edn/read-string`'s walker."
  64)

(defn- value-depth
  "Recursive max depth of `v`. Maps and sequences count one level per
  layer; scalars are depth 0. Used to reject pathologically nested
  inputs before they reach the registrar."
  [v]
  (cond
    (map? v)
    (if (empty? v)
      0
      (inc (reduce max 0 (concat (map value-depth (keys v))
                                 (map value-depth (vals v))))))

    (or (vector? v) (list? v) (set? v) (seq? v))
    (if (empty? v)
      0
      (inc (reduce max 0 (map value-depth v))))

    :else 0))

(defn- read-edn-body
  "Parse an agent-supplied EDN string into a Clojure value with the
  rf2-g9fje hardening posture. Returns the parsed value on success, or
  one of two sentinels on failure (caller maps these to
  `error-result`s):

    `::edn-error` — `edn/read-string` threw (malformed EDN, tagged
                    literal, oversize / over-deep payload).

  The parser is `clojure.edn/read-string` with NO custom readers
  (`:readers {}`) and a throwing `:default` handler — any
  `#<tag> ...` form in the input lands in the `:default` handler,
  which throws, which the outer try/catch maps to `::edn-error`.
  Size and depth are checked OUT-OF-BAND (before / after the read)
  so the reader sees only sanitised inputs."
  [^String body]
  (try
    (let [byte-count #?(:clj  (count (.getBytes body "UTF-8"))
                        :cljs (count body))]
      (if (> byte-count max-body-bytes)
        ::edn-error
        (let [parsed (edn/read-string
                       {:readers {}
                        :default (fn [tag _]
                                   (throw (ex-info "tagged literals not permitted"
                                                   {:rf.error :rf.story-mcp/edn-tagged-literal
                                                    :tag      tag})))}
                       body)]
          (if (> (value-depth parsed) max-edn-depth)
            ::edn-error
            parsed))))
    (catch Throwable _ ::edn-error)))

(defn- coerce-body
  "Normalise the `:body` arg to a Clojure map, or return one of two
  sentinel keywords on parse / shape failure:

    `::edn-error` — `:body` was a string but `read-edn-body` threw or
                    the parsed value violated the size / depth /
                    tagged-literal hardening (rf2-g9fje).
    `::not-a-map` — `:body` parsed (or was passed) but is not a map."
  [body]
  (cond
    (map? body)    body
    (string? body) (let [v (read-edn-body body)]
                     (cond
                       (= v ::edn-error) ::edn-error
                       (map? v)          v
                       :else             ::not-a-map))
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
                                                 :description (str "EDN-encoded variant body. Parsed under the rf2-g9fje "
                                                                   "hardening: tagged literals (#<tag> ...) rejected, "
                                                                   "no custom readers, "
                                                                   max-body-bytes "-byte payload ceiling, "
                                                                   max-edn-depth "-level depth ceiling. "
                                                                   "Prefer the JSON-object form when keywords are not "
                                                                   "needed in the body shape.")}]}})
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

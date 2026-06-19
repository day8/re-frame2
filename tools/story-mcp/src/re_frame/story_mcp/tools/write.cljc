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
            [re-frame.error :as error]
            [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.tools.args :as targs]
            [re-frame.story-mcp.tools.result :as result]
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
    (result/error-result
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
;; (`tools/mcp-conformance/test/end-to-end-story.cjs`) registers its fixture
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

(def ^:const ^:private max-body-string-keys
  "Hard ceiling on the TOTAL number of STRING keys across an object-form
  `:body` tree, checked BEFORE `keywordize-body-keys` interns any of them
  (rf2-tag30h). The depth cap alone bounds NESTING but not WIDTH: a single
  shallow object with thousands of distinct unknown string keys would
  intern a fresh JVM keyword per key before the registrar's schema
  rejects the body. A legitimate variant body has a handful of top-level
  slots (`:doc` / `:component` / `:args` / `:tags` / `:script` / …) plus a
  bounded `:args` / `:db-seed` map; 1024 total string keys is far past any
  real body and caps the per-call intern cost. Already-keyword keys (the
  direct-invoke test path) are NOT counted — they do not intern."
  1024)

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

(defn- count-string-keys
  "Total count of STRING keys across the whole `v` tree — the keys
  `keywordize-body-keys` would intern (rf2-tag30h). Walks maps (counting
  string keys and recursing into both keys and vals), vectors / lists /
  sets / seqs (recursing into elements). Already-keyword keys are not
  counted — they do not intern. Used to width-cap an object-form body
  BEFORE any interning runs."
  [v]
  (cond
    (map? v)
    (reduce-kv (fn [acc k val]
                 (+ acc
                    (if (string? k) 1 0)
                    (count-string-keys k)
                    (count-string-keys val)))
               0 v)

    (or (vector? v) (list? v) (set? v) (seq? v))
    (reduce + 0 (map count-string-keys v))

    :else 0))

(defn- read-edn-body
  "Parse an agent-supplied EDN string into a Clojure value with the
  rf2-g9fje hardening posture. Returns the parsed value on success, or
  the `::edn-error` sentinel on failure (`coerce-body` maps it to an
  `error-result`):

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
                                   (error/throw-error!
                                     :rf.error/story-mcp-tagged-literal-rejected
                                     'story-mcp/parse-edn-body
                                     (str "tagged literals are not permitted in EDN "
                                          "write bodies; remove the #" tag " tagged "
                                          "literal and send plain EDN.")
                                     {:recovery :remove-the-tagged-literal
                                      :extra    {:tag tag}}))}
                       body)]
          (if (> (value-depth parsed) max-edn-depth)
            ::edn-error
            parsed))))
    (catch Throwable _ ::edn-error)))

(defn- keywordize-body-keys
  "Recursively convert the STRING keys of an object-form `:body` (and
  its nested maps) into keywords, INTERNING as it goes. This is the
  operator-gated write-path counterpart to the rf2-3luf3 no-intern
  ingress: `protocol/parse-json` now parses the whole frame with string
  keys, so the JSON object-form body arrives string-keyed. The
  registrar's variant schema demands keyword slots (`:doc` / `:args` /
  `:tags` / …), so this path re-keywordises the body — but the intern is
  BOUNDED:

    - the handler reaches here only after the `--allow-writes`
      operator gate (`assert-writes-allowed`), and
    - the body was already depth-capped at `max-edn-depth` by the
      caller (`coerce-body` runs `value-depth` before this), and
      size-capped on the EDN-string arm,

  so a hostile caller cannot mint unbounded keywords through it. Mirrors
  the `fresh-keyword` policy (`tools/mcp-base/spec/args.md` §Keyword-
  interning safety): interning is permitted only behind a gate that
  bounds the allocation cost. Non-string keys (already-keyword keys from
  the direct-invoke test path) pass through unchanged; non-collection
  values are returned as-is."
  [v]
  (cond
    (map? v)
    (persistent!
     (reduce-kv
      (fn [acc k val]
        (let [k' (cond-> k (string? k) keyword)]
          (assoc! acc k' (keywordize-body-keys val))))
      (transient {})
      v))

    (vector? v) (mapv keywordize-body-keys v)
    (set? v)    (into #{} (map keywordize-body-keys) v)
    (seq? v)    (map keywordize-body-keys v)
    :else       v))

(defn- coerce-body
  "Normalise the `:body` arg to a keyword-keyed Clojure map, or return
  one of two sentinel keywords on parse / shape failure:

    `::edn-error` — `:body` was a string but `read-edn-body` threw or
                    the parsed value violated the size / depth /
                    tagged-literal hardening (rf2-g9fje).
    `::not-a-map` — `:body` parsed (or was passed) but is not a map.
    `::too-wide`  — an object-form body carries more than
                    `max-body-string-keys` total STRING keys (rf2-tag30h);
                    rejected BEFORE `keywordize-body-keys` interns any of
                    them so a wide unknown-key body cannot grow the JVM
                    keyword table.

  Two input arms:

    - **Object-form body** (a JSON object) arrives STRING-keyed from
      `protocol/parse-json` (rf2-3luf3 no-intern ingress). Its depth is
      checked against `max-edn-depth`, then its keys are recursively
      keywordised via `keywordize-body-keys` — a bounded intern gated by
      `--allow-writes` (the handler reached this fn only past the
      write gate). A direct-invoke caller passing an already keyword-
      keyed map is a no-op on the keys (`keywordize-body-keys` leaves
      keyword keys alone).
    - **EDN-string body** is parsed by the hardened `read-edn-body`
      (no custom readers, tagged-literal reject, size/depth caps),
      which yields keyword-keyed data directly."
  [body]
  (cond
    (map? body)    (cond
                     (> (value-depth body) max-edn-depth)
                     ::edn-error
                     ;; rf2-tag30h: width cap BEFORE interning. The depth
                     ;; cap bounds nesting but not the number of distinct
                     ;; string keys a shallow object can carry; a wide body
                     ;; would intern a fresh keyword per key here.
                     (> (count-string-keys body) max-body-string-keys)
                     ::too-wide
                     :else
                     (keywordize-body-keys body))
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
    (let [id (story/reg-variant* vk (assoc body-v :origin config/origin))]
      (result/edn-result {:variant-id id :registered? true}))
    (catch Throwable e
      (result/error-result (str "Registration failed: " (ex-message e))
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
      (let [[vid err-vid]   (targs/required-arg arguments :variant-id)
            [body err-body] (when-not err-vid (targs/required-arg arguments :body))]
        (or err-vid err-body
            (let [body-v (coerce-body body)]
              (case body-v
                ::edn-error
                (result/error-result ":body must be a map or a valid EDN string.")

                ::not-a-map
                (result/error-result (str ":body must be a map; got " (some-> body class .getName)))

                ::too-wide
                (result/error-result
                  (str ":body carries too many keys (over " max-body-string-keys
                       "). A variant body has a handful of slots; reject "
                       "abusive wide objects before keywordising.")
                  {:rf.error :rf.story-mcp/body-too-wide})

                ;; rf2-lqjbk write-side exception: `register-variant`
                ;; by definition extends the registry with a fresh
                ;; keyword id, so `safe-keyword` against an existing
                ;; bounded set would always reject. The intern here is
                ;; gate-bounded by `--allow-writes` (operator-only opt-in).
                ;;
                ;; rf2-tag30h: validate the `:story.<path>/<name>` grammar
                ;; on the STRING shape via `fresh-keyword-checked` BEFORE
                ;; interning. `fresh-keyword` interned first and let the
                ;; registrar's downstream `assert-id!` reject on grammar —
                ;; but that reject happened AFTER the intern, so an invalid
                ;; id (which correctly returns an error) still permanently
                ;; grew the JVM keyword table. The pre-intern shape check
                ;; (`story/valid-variant-id?`, single-sourced with the
                ;; registrar's keyword-level `variant-id?`) fails closed with
                ;; no intern.
                (if-let [vk (args/fresh-keyword-checked vid story/valid-variant-id?)]
                  (register-or-error vk body-v)
                  (result/error-result
                    (str ":variant-id " (pr-str vid) " does not match the canonical "
                         "variant-id grammar :story.<path>/<name>.")
                    {:rf.error   :rf.error/variant-id-shape
                     :variant-id vid}))))))))

(defn tool-unregister-variant
  "Write: programmatically unregister a variant. Gated behind
  `allow-writes?`.

  `:unregistered?` is always `true` on the success path: `with-variant-id`
  resolves `:variant-id` via `safe-keyword` against the LIVE
  registered-variant set (rf2-lqjbk), so an unregistered id never reaches
  this body — it short-circuits to a `Variant not found` error result
  upstream. The id therefore always names a currently-registered variant,
  and the subsequent `unregister!` always removes it. This matches the
  spec (`spec/API.md` §unregister-variant): `{:unregistered? true …}` on
  success, `isError: true` when the variant is not registered."
  [arguments]
  (or (assert-writes-allowed "unregister-variant")
      (targs/with-variant-id arguments
        (fn [vk]
          (story/unregister! :variant vk)
          (result/edn-result {:variant-id vk :unregistered? true})))))

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
    :description    (str "Register a variant programmatically. GATED behind `:rf.story-mcp/allow-writes?` (default false). Enables the self-healing loop: write story → run → read failures → fix. "
                         "Examples: "
                         "1. Object body (preferred): {:variant-id \":story.cart/probe\" :body {:doc \"...\" :args {:label \"OK\"}}} -> {:variant-id :story.cart/probe :registered? true}. "
                         "2. EDN-string body (needed for keyword payloads): {:variant-id \":story.cart/probe\" :body \"{:doc \\\"...\\\" :tags #{:dev}}\"} -> {:variant-id :story.cart/probe :registered? true}. "
                         "3. Gate closed: any args -> {:isError true :content [{:text \"Write surface disabled. Set :rf.story-mcp/allow-writes? ...\"}] :structuredContent {:gated true :tool \"register-variant\"}}.")
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
    :outputSchema s/write-gated-output-schema
    :annotations  s/destructive-write-annotations
    :handler     tool-register-variant}

   {:name           "unregister-variant"
    :category       :write
    :description    (str "Unregister a registered variant. GATED behind `:rf.story-mcp/allow-writes?` (default false). Symmetric to `register-variant`. "
                         "Examples: "
                         "1. Registered: {:variant-id \":story.cart/probe\"} -> {:variant-id :story.cart/probe :unregistered? true}. "
                         "2. Unknown variant: {:variant-id \":story.no/such\"} -> {:isError true :content [{:text \"Variant not found: :story.no/such\"}]} — an unregistered id is an error, not a no-op (the id is resolved against the live registered-variant set). "
                         "3. Gate closed: any args -> {:isError true :content [{:text \"Write surface disabled...\"}] :structuredContent {:gated true :tool \"unregister-variant\"}}.")
    :typicalTokens  100
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/write-gated-output-schema
    :annotations  s/destructive-write-annotations
    :handler     tool-unregister-variant}])

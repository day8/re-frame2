(ns re-frame.story-mcp.tools
  "MCP tool implementations. Each tool reads from / writes to
  `re-frame.story`'s public surface; nothing here registers new
  framework primitives or touches Story's internals.

  Per IMPL-SPEC §7.2 the tool surface splits into three categories
  matching Storybook MCP's Dev / Docs / Testing shape, plus a gated
  v1.1 write surface from §7.3.

  ## Tool registry

  Each tool is a map:

      {:name        \"<dash-separated-name>\"
       :description \"<one-line semantics>\"
       :category    :dev | :docs | :testing | :write
       :inputSchema { ... JSON schema ... }
       :handler     (fn [args] result-map-or-error)}

  The handler returns either:

  - A success map `{:content [{:type \"text\" :text \"...\"}] :structuredContent {...}}`
    — wrapped by `tools/call` into a JSON-RPC result.
  - An error map `{:content [{:type \"text\" :text \"error msg\"}] :isError true}`
    — for tool-execution errors (the tool ran but failed).

  Protocol-level errors (unknown tool name, malformed `arguments`) live
  on the dispatcher in `re-frame.story-mcp.server`.

  ## Why not call into Story's internals directly?

  Story's `re-frame.story` ns is the contract. Per IMPL-SPEC §7.4
  Story's core jar exposes `handlers`, `handler-meta`, `run-variant`,
  `variant->edn`, `snapshot-identity`, `list-tags`, `list-modes`, the
  Stage 5 assertion helpers, and `variant-share-url`. Nothing here
  reaches past that public surface."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [re-frame.story :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async :as async]
            [re-frame.story-mcp.config :as config]))

;; ---------------------------------------------------------------------------
;; Result-builder helpers
;; ---------------------------------------------------------------------------

(defn- pr-edn
  "Serialise a value to a stable, EDN-round-trippable string. Used to
  embed Story data inside an MCP text content item. Uses `pr-str` —
  keywords stay keywords, sets stay sets, no JSON lossiness."
  [v]
  (binding [*print-readably* true]
    (pr-str v)))

(defn- text-result
  "Build a success result with a single text content item. `structured`
  (optional) lands on the `structuredContent` slot per the spec/2025-06-18
  tools §Structured content guidance — agent clients that prefer JSON
  data over text can read it directly without re-parsing."
  ([text]
   {:content [{:type "text" :text text}]})
  ([text structured]
   (cond-> {:content [{:type "text" :text text}]}
     (some? structured) (assoc :structuredContent structured))))

(defn- error-result
  "Build a tool-execution error result. Per MCP §Error Handling these
  use `isError: true` rather than a protocol-level JSON-RPC error so
  the agent client can surface the failure to the LLM without aborting
  the conversation."
  ([msg]
   (error-result msg nil))
  ([msg structured]
   (cond-> {:content [{:type "text" :text msg}]
            :isError true}
     (some? structured) (assoc :structuredContent structured))))

;; ---------------------------------------------------------------------------
;; Args helpers
;; ---------------------------------------------------------------------------

(defn- read-keyword
  "Read a keyword from agent-supplied arguments. MCP arguments arrive as
  JSON, so keyword-typed Story ids come in as strings. We strip a
  leading `\":\"` if present (some agents may serialise EDN-ish), and
  use `read-string` only for namespaced keywords; otherwise plain
  `keyword`.

  Returns nil if the input is nil or empty."
  [v]
  (cond
    (keyword? v) v
    (nil? v)     nil
    (string? v)  (let [s (if (and (> (count v) 0) (= \: (.charAt ^String v 0)))
                           (subs v 1)
                           v)]
                   (cond
                     (str/blank? s)           nil
                     (str/includes? s "/")    (let [[ns nm] (str/split s #"/" 2)]
                                                (keyword ns nm))
                     :else                    (keyword s)))
    :else        nil))

(defn- required-arg
  "Read a required argument. Returns `[value nil]` on success, or
  `[nil error-result]` on miss."
  [args k]
  (let [v (get args k)]
    (if (or (nil? v) (and (string? v) (str/blank? v)))
      [nil (error-result (str "Missing required argument: " (name k)))]
      [v nil])))

;; ---------------------------------------------------------------------------
;; Dev tools (instructions + preview)
;; ---------------------------------------------------------------------------

(def ^:private story-instructions-text
  "The agent-onboarding text returned by `get-story-instructions`. Kept
  inline as a single string to keep this jar self-contained — no
  external resource read needed at boot."
  (str
    "re-frame2-story authoring conventions (agent quick reference).\n"
    "Full spec: spec/007-Stories.md + tools/story/IMPL-SPEC.md.\n"
    "\n"
    "Registration: seven `reg-*` macros under re-frame.story/. Each\n"
    "expansion threads `(when re-frame.story.config/enabled? ...)` so\n"
    "production CLJS builds elide via Closure DCE.\n"
    "\n"
    "  (reg-story   :story.<path>              { :doc :component :decorators :args :argtypes\n"
    "                                            :tags :modes :substrates :platforms :variants })\n"
    "  (reg-variant :story.<path>/<variant>    { :doc :extends :events :play :args :argtypes\n"
    "                                            :tags :decorators :loaders :loaders-complete-when\n"
    "                                            :args->events :platforms :substrates :modes })\n"
    "  (reg-workspace :Workspace.<path>/<name> { :doc :layout :variants :content :render :modes })\n"
    "  (reg-mode     :mode/<name>              { :doc :args })\n"
    "  (reg-story-panel :<panel-id>            { :doc :title :placement :render :for })\n"
    "  (reg-decorator :<dec-id>                { :kind :wrap | :init :app-db-patch | :fx-id :response })\n"
    "  (reg-tag      :<tag-id>                 { :doc })\n"
    "\n"
    "Hard rules:\n"
    "  - Variant bodies are 100% EDN-round-trippable. NO closures, NO\n"
    "    fns. The single legal closure-bearing slot is a `:hiccup`-kind\n"
    "    decorator's `:wrap`, at the decorator's registration site.\n"
    "  - Variants reference decorators by id: `:decorators [[:dec-id args]]`.\n"
    "  - Inclusion tags must be registered before a variant references\n"
    "    them. Seven canonical tags ship pre-registered:\n"
    "      :dev :docs :test :screenshot :experimental :internal :agent.\n"
    "    `:!tag` syntax removes an inherited tag (`:extends` chain).\n"
    "  - Assertions are events under the `:rf.assert/*` namespace. Seven\n"
    "    canonical assertions: path-equals, path-matches, sub-equals,\n"
    "    dispatched?, state-is, no-warnings, effect-emitted.\n"
    "\n"
    "Lifecycle (`run-variant`): four phases — loaders → events → render\n"
    "→ play. `:rf.assert/*` events in `:play` accumulate records on the\n"
    "frame; they do NOT throw on failure. `assertions-passing?` is the\n"
    "vacuous-pass predicate (empty assertions vector is green).\n"
    "\n"
    "Snapshots: `snapshot-identity` hashes the canonical (variant ×\n"
    "args × decorators × loaders × substrate × modes) tuple. Stable\n"
    "across hosts; use for visual-regression keying.\n"))

(defn- tool-get-story-instructions
  "Dev: return the Story authoring conventions in agent-friendly form."
  [_args]
  (text-result story-instructions-text))

(defn- tool-preview-variant
  "Dev: given a variant id, return the canvas state + share URL.

  Per IMPL-SPEC §7.2 'returns rendered hiccup for a variant + the
  assertions list'. We invoke `run-variant`, deref the promise (JVM
  side has `async/deref-blocking`), and serialise the result map.

  Also includes the variant share URL (per IMPL-SPEC §2.8.5 + Stage 6
  `story/variant-share-url`) so the agent can hand the cell to a
  human collaborator."
  [args]
  (let [[vid err] (required-arg args :variant-id)]
    (if err err
      (let [vk        (read-keyword vid)
            body      (story/variant->edn vk)]
        (if (nil? body)
          (error-result (str "Variant not found: " (pr-str vk)))
          (let [opts       (cond-> {}
                             (some? (:substrate args))    (assoc :substrate (read-keyword (:substrate args)))
                             (some? (:active-modes args)) (assoc :active-modes
                                                                 (mapv read-keyword (:active-modes args)))
                             (some? (:cell-overrides args)) (assoc :cell-overrides
                                                                   (:cell-overrides args)))
                base-url   (or (:base-url args) "")
                share-url  (story/variant-share-url vk base-url opts)
                result     (try
                             (async/deref-blocking (story/run-variant vk opts)
                                                   ;; Default 5s — preview is a snapshot,
                                                   ;; not a long-running load.
                                                   5000)
                             (catch Throwable e
                               {:lifecycle :error
                                :assertions [{:assertion :rf.error/run-failed
                                              :passed? false
                                              :reason (ex-message e)}]}))
                payload    {:variant-id   vk
                            :share-url    share-url
                            :lifecycle    (:lifecycle result)
                            :elapsed-ms   (:elapsed-ms result)
                            :app-db       (:app-db result)
                            :assertions   (:assertions result)
                            :rendered-hiccup (:rendered-hiccup result)
                            :snapshot     (:snapshot result)
                            :effective-args (:effective-args result)}]
            (text-result (pr-edn payload) payload)))))))

(defn- tool-list-substrates
  "Dev: what substrates can be used. Reads the registered substrate set
  via the Story-public surface.

  Per IMPL-SPEC §2.2: substrates are registered via
  `register-substrate!` on CLJS (the actual render-fn is CLJS-only).
  On the JVM (where the MCP server lives by default) the registered
  set is the CLJS-side one ONLY when the server is co-hosted with the
  CLJS runtime (a shared-process deploy via nREPL). The JVM-standalone
  deploy reads an empty set — that's a correct answer for that deploy
  (no CLJS substrates are runnable from a JVM-only host)."
  [_args]
  (let [subs (try
               ;; `story/registered-substrates` is CLJS-only (`#?(:cljs ...)`).
               ;; On JVM there's no `def`, so we use `resolve` to detect that
               ;; cleanly and return an empty set rather than blowing up.
               (let [f (resolve 'story/registered-substrates)]
                 (if f (sort (vec (f))) []))
               (catch Throwable _ []))
        payload {:substrates (vec subs)}]
    (text-result (pr-edn payload) payload)))

;; ---------------------------------------------------------------------------
;; Docs tools (introspection)
;; ---------------------------------------------------------------------------

(defn- tool-list-stories
  "Docs: all registered stories (with optional tag filters).

  `args`:
    :tags — vector of tag ids (strings or `:keyword` forms); narrows
            the result to stories whose `:tags` set intersects this."
  [args]
  (let [stories (story/handlers :story)
        tags    (when-let [ts (:tags args)]
                  (set (map read-keyword ts)))
        filtered (if (seq tags)
                   (into {}
                         (filter (fn [[_id body]]
                                   (some tags (:tags body #{}))))
                         stories)
                   stories)
        payload  {:stories (vec (for [[sid body] filtered]
                                  {:id   sid
                                   :doc  (:doc body)
                                   :tags (vec (:tags body))
                                   :variants (sort (story/variants-of sid))}))}]
    (text-result (pr-edn payload) payload)))

(defn- tool-get-story
  "Docs: one story's full body."
  [args]
  (let [[sid err] (required-arg args :story-id)]
    (if err err
      (let [sk   (read-keyword sid)
            body (story/handler-meta :story sk)]
        (if (nil? body)
          (error-result (str "Story not found: " (pr-str sk)))
          (let [payload {:id sk :body body :variants (sort (story/variants-of sk))}]
            (text-result (pr-edn payload) payload)))))))

(defn- tool-get-variant
  "Docs: one variant's full body (`handler-meta :variant id`)."
  [args]
  (let [[vid err] (required-arg args :variant-id)]
    (if err err
      (let [vk   (read-keyword vid)
            body (story/variant->edn vk)]
        (if (nil? body)
          (error-result (str "Variant not found: " (pr-str vk)))
          (let [payload {:id vk :body body}]
            (text-result (pr-edn payload) payload)))))))

(defn- tool-list-tags
  "Docs: canonical tags + custom tags."
  [_args]
  (let [registered (story/list-tags)
        canonical  story/canonical-tags
        custom     (set/difference (set registered) (set canonical))
        payload    {:canonical (vec (sort-by str canonical))
                    :custom    (vec (sort-by str custom))
                    :all       (vec (sort-by str registered))}]
    (text-result (pr-edn payload) payload)))

(defn- tool-list-modes
  "Docs: registered modes (from `reg-mode`). Returns each mode's id +
  body so agents can see the `:args` saved tuple."
  [_args]
  (let [modes   (story/handlers :mode)
        payload {:modes (vec (for [[mid body] modes]
                               {:id mid :doc (:doc body) :args (:args body)}))}]
    (text-result (pr-edn payload) payload)))

(def ^:private canonical-assertion-docs
  "Per spec/007 line 304 + IMPL-SPEC §3.5 the canonical seven
  assertions' arities."
  [{:id :rf.assert/path-equals
    :payload "[path expected]"
    :semantics "(= (get-in @app-db path) expected)"}
   {:id :rf.assert/path-matches
    :payload "[path malli-schema]"
    :semantics "Malli validate at path"}
   {:id :rf.assert/sub-equals
    :payload "[sub-vec expected]"
    :semantics "(= @(subscribe sub-vec) expected)"}
   {:id :rf.assert/dispatched?
    :payload "[event-or-pred]"
    :semantics "Was this event dispatched during play?"}
   {:id :rf.assert/state-is
    :payload "[machine-id state]"
    :semantics "Machine in state?"}
   {:id :rf.assert/no-warnings
    :payload "[]"
    :semantics "No :warning trace events since play start"}
   {:id :rf.assert/effect-emitted
    :payload "[fx-id (optional pred)]"
    :semantics "fx-id emitted during play?"}])

(defn- tool-list-assertions
  "Docs: the `:rf.assert/*` canonical vocabulary + arity docs."
  [_args]
  (let [registered (story/canonical-assertion-ids)
        payload    {:canonical canonical-assertion-docs
                    :registered (vec (sort-by str registered))}]
    (text-result (pr-edn payload) payload)))

(defn- tool-variant->edn
  "Docs: round-trippable EDN of a registered variant. Identical payload
  to `get-variant` but framed as EDN-only — the `structuredContent`
  slot is omitted so agents that want strict EDN parse the text."
  [args]
  (let [[vid err] (required-arg args :variant-id)]
    (if err err
      (let [vk   (read-keyword vid)
            body (story/variant->edn vk)]
        (if (nil? body)
          (error-result (str "Variant not found: " (pr-str vk)))
          (text-result (pr-edn body)))))))

;; ---------------------------------------------------------------------------
;; Testing tools (execution)
;; ---------------------------------------------------------------------------

(defn- tool-run-variant
  "Testing: execute a variant, return the run-variant result map.

  Per IMPL-SPEC §3.2 + §3.5 the result shape is
  `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms ...}`.

  Args:
    :variant-id     required
    :substrate      optional — keyword or string
    :active-modes   optional — coll of mode ids
    :cell-overrides optional — map of arg overrides
    :timeout-ms     optional — JVM blocking timeout; default 10000"
  [args]
  (let [[vid err] (required-arg args :variant-id)]
    (if err err
      (let [vk   (read-keyword vid)]
        (if (nil? (story/variant->edn vk))
          (error-result (str "Variant not found: " (pr-str vk)))
          (let [opts     (cond-> {}
                           (some? (:substrate args))     (assoc :substrate (read-keyword (:substrate args)))
                           (some? (:active-modes args))  (assoc :active-modes
                                                                (mapv read-keyword (:active-modes args)))
                           (some? (:cell-overrides args)) (assoc :cell-overrides
                                                                 (:cell-overrides args)))
                timeout  (or (:timeout-ms args) 10000)
                result   (try
                           (async/deref-blocking (story/run-variant vk opts) timeout)
                           (catch Throwable e
                             {:lifecycle :error
                              :variant-id vk
                              :assertions [{:assertion :rf.error/run-failed
                                            :passed? false
                                            :reason (ex-message e)}]}))
                payload  {:frame           (:frame result vk)
                          :app-db          (:app-db result)
                          :assertions      (:assertions result)
                          :rendered-hiccup (:rendered-hiccup result)
                          :elapsed-ms      (:elapsed-ms result)
                          :snapshot        (:snapshot result)
                          :lifecycle       (:lifecycle result)
                          :passing?        (story/assertions-passing? result)}]
            (text-result (pr-edn payload) payload)))))))

(defn- tool-snapshot-identity
  "Testing: content-hash of the canonicalised variant (for external
  visual-regression). Returns
  `{:variant-id :active-modes :substrate :content-hash}`."
  [args]
  (let [[vid err] (required-arg args :variant-id)]
    (if err err
      (let [vk   (read-keyword vid)]
        (if (nil? (story/variant->edn vk))
          (error-result (str "Variant not found: " (pr-str vk)))
          (let [opts    (cond-> {}
                          (some? (:substrate args))    (assoc :substrate (read-keyword (:substrate args)))
                          (some? (:active-modes args)) (assoc :active-modes
                                                              (mapv read-keyword (:active-modes args))))
                payload (story/snapshot-identity vk opts)]
            (text-result (pr-edn payload) payload)))))))

(defn- tool-run-a11y
  "Testing: run axe-core against a variant, return violations.

  Per IMPL-SPEC §7.2 the implementation delegates to a11y panel data
  (`a11y/violations-by-frame` per Stage 6's hot-zone-hooks report).
  The actual axe-core run is CLJS-only (it loads an in-browser
  `<script>`); from the JVM-side MCP server we can only READ the
  violations atom that the CLJS canvas has accumulated.

  The canonical agent workflow is:
    1. Open the Story shell in the browser; navigate to the variant.
    2. Click the a11y panel's 'Run' button (or the panel auto-runs on
       canvas mount per Stage 6).
    3. Call this MCP tool to read the violations the panel stored.

  When the server is JVM-standalone (no co-hosted CLJS runtime) this
  returns an empty result with a hint."
  [args]
  (let [[vid err] (required-arg args :variant-id)]
    (if err err
      (let [vk    (read-keyword vid)
            atomv (try
                    (let [v (resolve 're-frame.story.ui.a11y/violations-by-frame)]
                      (when v (deref @v)))
                    (catch Throwable _ nil))
            violations (when atomv (get atomv vk))
            payload {:variant-id vk
                     :violations (vec (or violations []))
                     :note       (when (nil? atomv)
                                   "a11y is CLJS-only; this JVM-standalone deploy can't run axe-core. Run the panel in-browser; the violations atom is read by this tool.")}]
        (text-result (pr-edn payload) payload)))))

(defn- tool-read-failures
  "Testing: accumulated assertion failures across recent `run-variant`
  calls. Reads the variant frame's `:rf.story/assertions` accumulator
  via `re-frame.story/read-assertions`.

  Useful for an agent that ran a variant a moment ago and wants to
  inspect failures without re-running."
  [args]
  (let [[vid err] (required-arg args :variant-id)]
    (if err err
      (let [vk         (read-keyword vid)
            all        (assertions/read-assertions vk)
            failures   (filterv (complement :passed?) all)
            payload    {:variant-id vk
                        :total      (count all)
                        :failures   (vec failures)
                        :passing?   (story/assertions-passing? all)}]
        (text-result (pr-edn payload) payload)))))

;; ---------------------------------------------------------------------------
;; Write surface (v1.1, dev-only — gated)
;; ---------------------------------------------------------------------------

(defn- assert-writes-allowed
  "Returns nil when writes are allowed; an error-result otherwise. The
  caller short-circuits on the non-nil branch."
  []
  (when-not (config/writes-allowed?)
    (error-result
      (str "Write surface disabled. Set `:rf.story-mcp/allow-writes?` "
           "(or `--allow-writes` CLI flag, or "
           "`-Drf.story-mcp.allow-writes=true` JVM property) to enable. "
           "Per IMPL-SPEC §7.3 the write surface is dev-only; CI runs "
           "should leave it off.")
      {:gated true :tool "register-variant"})))

(defn- tool-register-variant
  "Write: programmatically register a variant. Gated behind
  `allow-writes?` per IMPL-SPEC §7.3.

  Args:
    :variant-id  required — `:story.<path>/<variant>` keyword id.
    :body        required — the variant body (a map; will be read as
                 EDN via `clojure.edn/read-string` if a string is sent
                 over the wire)."
  [args]
  (or (assert-writes-allowed)
      (let [[vid e1] (required-arg args :variant-id)
            [body e2] (required-arg args :body)]
        (cond
          e1 e1
          e2 e2
          :else
          (let [vk      (read-keyword vid)
                body-v  (cond
                          (map? body)    body
                          (string? body) (try
                                           (edn/read-string body)
                                           (catch Throwable _
                                             ::edn-error))
                          :else          nil)]
            (cond
              (= body-v ::edn-error)
              (error-result (str "Could not parse :body as EDN. Send a map or a valid EDN string."))

              (not (map? body-v))
              (error-result (str ":body must be a map; got " (some-> body-v class .getName)))

              :else
              (try
                (let [id (story/reg-variant* vk body-v)]
                  (let [payload {:variant-id id :registered? true}]
                    (text-result (pr-edn payload) payload)))
                (catch Throwable e
                  (error-result (str "Registration failed: " (ex-message e))
                                (merge {:variant-id vk}
                                       (select-keys (ex-data e) [:rf.error :explain])))))))))))

(defn- tool-unregister-variant
  "Write: programmatically unregister a variant. Gated behind
  `allow-writes?`."
  [args]
  (or (assert-writes-allowed)
      (let [[vid err] (required-arg args :variant-id)]
        (if err err
          (let [vk    (read-keyword vid)
                had?  (some? (story/variant->edn vk))]
            (story/unregister! :variant vk)
            (let [payload {:variant-id vk :unregistered? had?}]
              (text-result (pr-edn payload) payload)))))))

;; ---------------------------------------------------------------------------
;; Tool registry
;; ---------------------------------------------------------------------------

(def ^:private kw-or-string
  "Recurring JSON-schema fragment — accept either string-form keywords
  (`\":story.foo/bar\"`) or the bare-name form (`\"story.foo/bar\"`)."
  {:type "string"
   :description "A Clojure keyword id, as a string. Either `:story.foo/bar` or `story.foo/bar` is accepted."})

(def tool-registry
  "The full tool registry. Order matters for `tools/list` output —
  agents tend to scan top-to-bottom — so we list categories in the
  documented IMPL-SPEC §7.2 order: dev, docs, testing, then write."
  [;; ---- Dev -------------------------------------------------------------
   {:name        "get-story-instructions"
    :category    :dev
    :description "Return Story's authoring conventions in agent-friendly form (the seven reg-* macros, hard rules, lifecycle, snapshots)."
    :inputSchema {:type "object" :properties {} :additionalProperties false}
    :handler     tool-get-story-instructions}

   {:name        "preview-variant"
    :category    :dev
    :description "Given a variant id, return the canvas state (app-db, assertions, rendered-hiccup, elapsed) + a sharable URL."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string
                               :substrate kw-or-string
                               :active-modes {:type "array" :items kw-or-string}
                               :cell-overrides {:type "object"}
                               :base-url {:type "string"
                                          :description "Optional base URL for the share link (no default)."}}
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-preview-variant}

   {:name        "list-substrates"
    :category    :dev
    :description "What substrates can be used. Returns the set registered via `register-substrate!` (Reagent is canonical; UIx / Helix opt-in per host)."
    :inputSchema {:type "object" :properties {} :additionalProperties false}
    :handler     tool-list-substrates}

   ;; ---- Docs ------------------------------------------------------------
   {:name        "list-stories"
    :category    :docs
    :description "All registered stories, optionally filtered by tags. Each entry carries id, doc, tags, and child variant ids."
    :inputSchema {:type "object"
                  :properties {:tags {:type "array" :items kw-or-string
                                      :description "Optional tag filter; story `:tags` set must intersect."}}
                  :additionalProperties false}
    :handler     tool-list-stories}

   {:name        "get-story"
    :category    :docs
    :description "Return one story's full body (`:doc`, `:component`, `:decorators`, `:args`, ... + its variant ids)."
    :inputSchema {:type "object"
                  :properties {:story-id kw-or-string}
                  :required ["story-id"]
                  :additionalProperties false}
    :handler     tool-get-story}

   {:name        "get-variant"
    :category    :docs
    :description "Return one variant's full body (the resolved EDN, with `:extends` already applied at registration time)."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string}
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-get-variant}

   {:name        "list-tags"
    :category    :docs
    :description "Canonical tags (`:dev :docs :test :screenshot :experimental :internal :agent`) + any custom tags registered by the project."
    :inputSchema {:type "object" :properties {} :additionalProperties false}
    :handler     tool-list-tags}

   {:name        "list-modes"
    :category    :docs
    :description "Registered modes (Chromatic-style saved tuples of args). Each entry is `{:id :doc :args}`."
    :inputSchema {:type "object" :properties {} :additionalProperties false}
    :handler     tool-list-modes}

   {:name        "list-assertions"
    :category    :docs
    :description "The seven canonical `:rf.assert/*` events with payload arity + semantics, plus any project-registered assertion ids."
    :inputSchema {:type "object" :properties {} :additionalProperties false}
    :handler     tool-list-assertions}

   {:name        "variant->edn"
    :category    :docs
    :description "Round-trippable EDN of a registered variant. Text result only (no structuredContent); use this when you want byte-stable EDN."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string}
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-variant->edn}

   ;; ---- Testing ---------------------------------------------------------
   {:name        "run-variant"
    :category    :testing
    :description "Execute a variant's four-phase lifecycle (loaders → events → render → play); return the result map (`:frame :app-db :assertions :rendered-hiccup :elapsed-ms :passing?`)."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string
                               :substrate kw-or-string
                               :active-modes {:type "array" :items kw-or-string}
                               :cell-overrides {:type "object"}
                               :timeout-ms {:type "integer" :minimum 1
                                            :description "JVM blocking timeout. Default 10000."}}
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-run-variant}

   {:name        "snapshot-identity"
    :category    :testing
    :description "Content-hash of (variant × resolved args × decorators × loaders × substrate × modes). Stable across hosts; key for visual-regression."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string
                               :substrate kw-or-string
                               :active-modes {:type "array" :items kw-or-string}}
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-snapshot-identity}

   {:name        "run-a11y"
    :category    :testing
    :description "Read axe-core violations for a variant from `re-frame.story.ui.a11y/violations-by-frame`. The actual axe-core run is CLJS-only; this tool returns whatever the in-browser panel has accumulated."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string}
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-run-a11y}

   {:name        "read-failures"
    :category    :testing
    :description "Accumulated assertion failures for a variant frame (since the most recent `run-variant`). Returns `{:total :failures :passing?}`."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string}
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-read-failures}

   ;; ---- Write (v1.1, gated) --------------------------------------------
   {:name        "register-variant"
    :category    :write
    :description "Register a variant programmatically. GATED behind `:rf.story-mcp/allow-writes?` (default false). Enables the self-healing loop: write story → run → read failures → fix."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string
                               :body {:oneOf [{:type "object"}
                                              {:type "string"
                                               :description "EDN-encoded variant body, parsed via clojure.edn/read-string."}]}}
                  :required ["variant-id" "body"]
                  :additionalProperties false}
    :handler     tool-register-variant}

   {:name        "unregister-variant"
    :category    :write
    :description "Unregister a variant. GATED behind `:rf.story-mcp/allow-writes?` (default false). Symmetric to `register-variant`."
    :inputSchema {:type "object"
                  :properties {:variant-id kw-or-string}
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-unregister-variant}])

(defn tool-descriptors
  "Build the `tools/list` response payload: each tool's name +
  description + inputSchema, in registry order. The MCP spec also
  allows a `title` field; we omit it (the names are already
  human-readable dash-separated forms)."
  []
  (mapv (fn [{:keys [name description inputSchema]}]
          {:name        name
           :description description
           :inputSchema inputSchema})
        tool-registry))

(defn tool-by-name
  "Look up a tool's registry entry by string name. Returns nil if no
  such tool — the caller (server dispatcher) turns that into a
  protocol-level method-not-found error."
  [tool-name]
  (some (fn [t] (when (= tool-name (:name t)) t)) tool-registry))

(defn invoke-tool
  "Invoke `tool-name` with `arguments` (a map of keyword-keyed args).
  Returns the tool's result map, or nil if no such tool. The caller
  serialises the result into a `tools/call` JSON-RPC response.

  Catches any throw from the handler and returns it as a tool-execution
  error (`isError: true`) per MCP §Error Handling — handlers SHOULD
  return error-results themselves, but this is the belt-and-braces
  catch."
  [tool-name arguments]
  (when-let [t (tool-by-name tool-name)]
    (try
      ((:handler t) (or arguments {}))
      (catch Throwable e
        (error-result (str "Tool handler threw: " (ex-message e))
                      {:tool      tool-name
                       :exception (.getName (class e))
                       :data      (ex-data e)})))))

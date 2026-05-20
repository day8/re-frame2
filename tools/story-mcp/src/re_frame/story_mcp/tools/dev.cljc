(ns re-frame.story-mcp.tools.dev
  "Dev-category tool handlers — `get-story-instructions`,
  `preview-variant`, `list-substrates`. Per IMPL-SPEC §7.2 these are
  the read-only agent-onboarding + canvas-state surfaces.

  `story-instructions-text` ships inline as a single string to keep
  this jar self-contained — no external resource read needed at boot."
  (:require [re-frame.story :as story]
            [re-frame.story.async :as async]
            [re-frame.story-mcp.tools.helpers :as h]
            [re-frame.story-mcp.tools.schemas :as s]))

(def story-instructions-text
  "The agent-onboarding text returned by `get-story-instructions`. Kept
  inline as a single string to keep this jar self-contained — no
  external resource read needed at boot."
  (str
    "re-frame2-story authoring conventions (agent quick reference).\n"
    "Full spec: spec/007-Stories.md + tools/story/spec/.\n"
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

(defn tool-get-story-instructions
  "Dev: return the Story authoring conventions in agent-friendly form."
  [_args]
  (h/text-result story-instructions-text))

(defn tool-preview-variant
  "Dev: given a variant id, return the canvas state + share URL.

  Per IMPL-SPEC §7.2 'returns rendered hiccup for a variant + the
  assertions list'. We invoke `run-variant`, deref the promise (JVM
  side has `async/deref-blocking`), and serialise the result map.

  Also includes the variant share URL (per IMPL-SPEC §2.8.5 + Stage 6
  `story/variant-share-url`) so the agent can hand the cell to a
  human collaborator.

  Wire-egress posture (rf2-73wuj): the `:app-db` slot is routed
  through `re-frame.core/elide-wire-value`; the `:assertions` vec is
  filtered through `strip-sensitive`. Off-box defaults apply unless
  the caller passes `:include-sensitive true`."
  [args]
  (h/with-variant args
    (fn [vk _body]
      (let [opts       (h/read-run-opts args)
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
            incl?      (h/include-sensitive? args)
            payload    {:variant-id   vk
                        :share-url    share-url
                        :lifecycle    (:lifecycle result)
                        :elapsed-ms   (:elapsed-ms result)
                        :app-db       (h/elide-app-db (:app-db result) vk incl?)
                        :assertions   (h/scrub-assertions (:assertions result) incl?)
                        :rendered-hiccup (:rendered-hiccup result)
                        :snapshot     (:snapshot result)
                        :effective-args (:effective-args result)}]
        (h/text-result (h/pr-edn payload) payload)))))

;; `story/registered-substrates` is CLJS-only — resolved once at ns-load
;; via `helpers/resolve-cljs-var`. The JVM-standalone deploy reads nil
;; and returns an empty set; the shared-process (nREPL-attached CLJS)
;; deploy reads the var.
(defonce ^:private registered-substrates-var
  (h/resolve-cljs-var 'story/registered-substrates))

(defn tool-list-substrates
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
               (if registered-substrates-var
                 (sort (vec (registered-substrates-var)))
                 [])
               (catch Throwable _ []))
        payload {:substrates (vec subs)}]
    (h/text-result (h/pr-edn payload) payload)))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Dev-category descriptors, in IMPL-SPEC §7.2 order."
  [{:name           "get-story-instructions"
    :category       :dev
    :description    (str "Return Story's authoring conventions in agent-friendly form (the seven reg-* macros, hard rules, lifecycle, snapshots). "
                         "Examples: "
                         "1. Session bootstrap: {} -> text content with the conventions prose. "
                         "2. With budget override: {:max-tokens 0} -> same text, no cap. "
                         "3. Discovery (paired with list-substrates + list-tags): call this first, then list-* tools to enumerate the registry surface.")
    :typicalTokens  1500
    :inputSchema    {:type "object" :properties (s/with-max-tokens {}) :additionalProperties false}
    :outputSchema   s/default-output-schema
    :annotations    s/read-only-annotations
    :handler        tool-get-story-instructions}

   {:name           "preview-variant"
    :category       :dev
    :description    (str "Given a variant id, return the canvas state (app-db, assertions, rendered-hiccup, elapsed) + a sharable URL. The `:app-db` slot is routed through `re-frame.core/elide-wire-value` against the variant frame's `[:rf/elision]` registry — declared-sensitive paths return `:rf/redacted` and oversize slots return the `:rf.size/large-elided` marker by default. Pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture). "
                         "Examples: "
                         "1. Default substrate: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :share-url \"...\" :lifecycle :ok :app-db {...} :assertions [] :rendered-hiccup [...]}. "
                         "2. UIx substrate + a mode: {:variant-id \":story.cart/full\" :substrate \":uix\" :active-modes [\":mode/dark\"]} -> same shape, rendered under uix + dark mode. "
                         "3. Not registered: {:variant-id \":story.no/such\"} -> {:isError true :content [{:text \"Variant not found: :story.no/such\"}]}.")
    :typicalTokens  2000
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-include-sensitive
                                  {:variant-id s/kw-or-string
                                   :substrate s/kw-or-string
                                   :active-modes {:type "array" :items s/kw-or-string}
                                   :cell-overrides {:type "object"}
                                   :base-url {:type "string"
                                              :description "Optional base URL for the share link (no default)."}}))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    ;; rf2-8h778 — `preview-variant` invokes the same `story/run-variant`
    ;; pipeline as `run-variant`: it dispatches events into the variant's
    ;; frame, accumulates assertions, and mutates the runtime. The audit
    ;; (rf2-3pn6c Finding #2) caught the asymmetry — `read-only-annotations`
    ;; here would have allowed agent hosts to auto-approve a call that
    ;; mutates the frame. The semantic distinction between the two tools
    ;; (`preview-variant` returns the share URL too; `run-variant` returns
    ;; the `:passing?` boolean) is real but doesn't change the destructive
    ;; nature of the underlying lifecycle run.
    :annotations  s/run-variant-annotations
    :handler     tool-preview-variant}

   {:name           "list-substrates"
    :category       :dev
    :description    (str "What substrates can be used. Returns the set registered via `register-substrate!` (Reagent is canonical; UIx / Helix opt-in per host). "
                         "Examples: "
                         "1. Shared-process deploy: {} -> {:substrates [:helix :reagent :uix]}. "
                         "2. JVM-standalone deploy: {} -> {:substrates []} — the CLJS-side registry isn't reachable. "
                         "3. With budget override: {:max-tokens 1000} -> same shape, smaller cap.")
    :typicalTokens  100
    :inputSchema    {:type "object" :properties (s/with-max-tokens {}) :additionalProperties false}
    :outputSchema   s/default-output-schema
    :annotations    s/read-only-annotations
    :handler        tool-list-substrates}])

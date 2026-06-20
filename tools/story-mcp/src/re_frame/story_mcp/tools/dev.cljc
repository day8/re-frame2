(ns re-frame.story-mcp.tools.dev
  "Dev-category tool handlers — `get-story-instructions`,
  `preview-variant`, `list-substrates`. Per IMPL-SPEC §7.2 these are
  the read-only agent-onboarding + canvas-state surfaces.

  `story-instructions-text` ships inline as a single string so the
  artefact is self-contained — no resource read at boot, one MCP
  frame, zero classpath / IO dependencies. The structural peer in
  pair-mcp (`get-re-frame2-pair-instructions`) uses the same inline-
  `(str ...)` shape, kept aligned per rf2-93cew so AI pairs reading
  both servers see one answer to the onboarding-text question."
  (:require [re-frame.story :as story]
            [re-frame.story.async :as async]
            [re-frame.story-mcp.tools.args :as targs]
            [re-frame.story-mcp.tools.cljs-resolve :as cljs-resolve]
            [re-frame.story-mcp.tools.egress :as egress]
            [re-frame.story-mcp.tools.result :as result]
            [re-frame.story-mcp.tools.schemas :as s]))

(def story-instructions-text
  "The agent-onboarding text returned by `get-story-instructions`.
  Inline `(str ...)` of `\\n`-glued lines — see the ns docstring for
  the rationale (mirrors pair-mcp per rf2-93cew). Edit this string
  when the catalogue changes."
  (str
    "re-frame2-story authoring conventions (agent quick reference).\n"
    "Full spec: spec/007-Stories.md + tools/story/spec/.\n"
    "\n"
    "Registration: nine `reg-*` macros under re-frame.story/. Each\n"
    "expansion threads `(when re-frame.story.config/enabled? ...)` so\n"
    "production CLJS builds elide via Closure DCE.\n"
    "\n"
    "  (reg-story   :story.<path>              { :doc :component :decorators :args :argtypes\n"
    "                                            :tags :modes :substrates :platforms :variants })\n"
    "  (reg-variant :story.<path>/<variant>    { :doc :extends :compose :setup :script :expect :args :argtypes\n"
    "                                            :tags :decorators :loaders :loaders-complete-when\n"
    "                                            :args->events :platforms :substrates :modes })\n"
    "  (reg-fragment :fragment.<path>/<name>   { :doc :args :setup :script :network :fx-overrides\n"
    "                                            :interceptor-overrides :loaders :decorators })\n"
    "  (reg-check   :check/<name>              { :doc :assertions })\n"
    "  (reg-workspace :Workspace.<path>/<name> { :doc :layout :variants :content :render :modes })\n"
    "  (reg-mode     :mode/<name>              { :doc :args })\n"
    "  (reg-story-panel :<panel-id>            { :doc :title :placement :render :for })\n"
    "  (reg-decorator :<dec-id>                { :kind :wrap | :init :app-db-patch | :fx-id :response })\n"
    "  (reg-tag      :<tag-id>                 { :doc })\n"
    "\n"
    "Composition (`:compose`): a variant pulls in reusable mixins by id —\n"
    "`reg-fragment` carries world/behaviour (setup/script/network/overrides,\n"
    "must be flat: NO :compose/:extends, NO assertions); `reg-check` carries\n"
    "a named, inheritable assertion pack. Strict composition: when composed\n"
    "fragments disagree on a strict-conflict field (:fx-overrides /\n"
    ":interceptor-overrides) and the variant is silent, plan construction\n"
    "fails — the variant owns its end-state. See tools/story/spec/017.\n"
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
    "    dispatched?, state-is, no-warnings, effect-emitted. One more is\n"
    "    tape-evaluated, not dispatched: schema-error declares an EXPECTED\n"
    "    schema violation — a run FAILS on any schema violation unless it\n"
    "    is exactly expected+consumed by a `:rf.assert/schema-error`\n"
    "    (there is NO `no-schema-errors` knob; schema-clean is the floor).\n"
    "\n"
    "Lifecycle (`run-variant`): four phases — loaders → setup → render\n"
    "→ script. `:rf.assert/*` steps in `:script` accumulate records on\n"
    "the frame; they do NOT throw on failure. `run-variant` returns the\n"
    "unified run-result: read the top-level `:status` ∈ {:pass :fail\n"
    ":cannot-run :error} for the verdict (a zero-assertion run is\n"
    "vacuously `:pass`). `:cannot-run` means the runner could not attempt\n"
    "the plan — handle it as 'not runnable here', not as a fail.\n"
    "\n"
    "Snapshots: `snapshot-identity` hashes the canonical (variant ×\n"
    "args × decorators × loaders × substrate × modes) tuple. Stable\n"
    "across hosts; use for visual-regression keying.\n"))

(defn tool-get-story-instructions
  "Dev: return the Story authoring conventions in agent-friendly form.

  Emits BOTH the `:content` text slot AND a matching
  `:structuredContent` map (rf2-vyacl). The descriptor declares an
  `:outputSchema` (`s/default-output-schema`); the official MCP SDK's
  high-level `callTool` REJECTS a tool that declares an output schema
  but returns no `:structuredContent` with JSON-RPC -32600. Mirroring
  re-frame2-pair-mcp's sibling `get-re-frame2-pair-instructions` (which
  routes through `wire/ok-text` and always emits structuredContent), we
  carry the prose under `:instructions` so the structured slot satisfies
  the permissive `additionalProperties: true` envelope schema."
  [_args]
  (let [payload {:instructions story-instructions-text}]
    (result/text-result story-instructions-text payload)))

(defn tool-preview-variant
  "Dev: given a variant id, return the canvas state + share URL.

  Per IMPL-SPEC §7.2 'returns rendered hiccup for a variant + the
  assertions list'. We invoke `run-variant`, deref the promise (JVM
  side has `async/deref-blocking`), and serialise the result map.

  `preview-variant` runs the SAME `story/run-variant` lifecycle as
  `run-variant`, so it speaks the SAME unified run-result vocabulary
  (rf2-ba86n.17) — it does NOT ship a third result dialect. It surfaces
  the unified `:status` verdict + the unified `:assertions` records (each
  with a derived `:status`) + `:checks`, and ADDS the preview-specific
  slots: the `:share-url` (per IMPL-SPEC §2.8.5 + Stage 6
  `story/variant-share-url`) so the agent can hand the cell to a human
  collaborator, plus `:rendered-hiccup` / `:effective-args`. `:lifecycle`
  here is the loader-lifecycle STATE (`:ready` / `:error`), not the run
  verdict — the verdict is `:status` (the old `:passing?` boolean was
  removed in the clean break).

  Blocking-timeout posture (rf2-ovmc5e): because preview and `run-variant`
  block on the SAME lifecycle, they share the SAME `:timeout-ms` knob +
  ceiling via `targs/resolve-timeout-ms` (default 10 s, hard ceiling
  30 s, caller values clamp DOWN). The MCP request loop is single-threaded
  so an unbounded blocking deref would park unrelated calls; the shared
  helper means the two tools cannot drift by copy-paste, and an agent can
  discover + tune the ceiling from `tools/list` on either tool.

  Wire-egress posture (rf2-73wuj): the `:app-db` slot is routed
  through `re-frame.core/elide-wire-value`; the `:assertions` vec is
  filtered through `strip-sensitive`. Off-box defaults apply unless
  the caller passes `:include-sensitive true`."
  [arguments]
  (targs/with-variant arguments
    (fn [vk _body]
      (let [opts       (targs/read-run-opts vk arguments)
            base-url   (or (:base-url arguments) "")
            share-url  (story/variant-share-url vk base-url opts)
            outcome    (try
                         (async/deref-blocking (story/run-variant vk opts)
                                               ;; rf2-ovmc5e — shared lifecycle
                                               ;; ceiling: tunable `:timeout-ms`
                                               ;; (default 10s, clamped to 30s),
                                               ;; the SAME knob `run-variant` uses
                                               ;; so the two cannot drift.
                                               (targs/resolve-timeout-ms arguments))
                         (catch Throwable e
                           ;; A throw never produced a unified result; mint the
                           ;; :error verdict directly so preview speaks the SAME
                           ;; unified shape a settled run emits.
                           {:status     :error
                            :lifecycle  :error
                            :assertions [(story/assertion-record
                                           {:assertion :rf.error/run-failed
                                            :passed?   false
                                            :error     true
                                            :reason    (ex-message e)})]
                            :checks     []}))
            incl?      (targs/include-sensitive? arguments)
            raw-db     (:app-db outcome)
            [assertions dropped] (egress/scrub-assertions+count (:assertions outcome) incl?)
            payload    {:variant-id   vk
                        :share-url    share-url
                        :status       (:status outcome)
                        :lifecycle    (:lifecycle outcome)
                        :elapsed-ms   (:elapsed-ms outcome)
                        :app-db       (egress/elide-app-db raw-db vk incl?)
                        :assertions   assertions
                        :checks       (vec (:checks outcome))
                        ;; Derived trees re-key the same sensitive value at a
                        ;; non-app-db path, so the path-based walker can't
                        ;; reach them — value-redact instead (rf2-ee38b.17).
                        :rendered-hiccup (egress/scrub-rendered (:rendered-hiccup outcome) raw-db vk incl?)
                        :snapshot     (egress/scrub-rendered (:snapshot outcome) raw-db vk incl?)
                        :effective-args (egress/scrub-rendered (:effective-args outcome) raw-db vk incl?)}]
        ;; Surface the MUST-level egress indicator counts (rf2-koq5m):
        ;; how many sensitive assertion records were dropped + how many
        ;; over-threshold leaves were elided across the payload. Omitted
        ;; when zero (Conventions §Cross-MCP indicator-field vocabulary).
        (egress/result-with-indicators payload dropped)))))

(defn tool-list-substrates
  "Dev: what substrates can be used. Reads the registered substrate set
  via the Story-public surface.

  Per IMPL-SPEC §2.2: substrates are registered via
  `register-substrate!` on CLJS (the actual render-fn is CLJS-only).
  On the JVM (where the MCP server lives by default) the registered
  set is the CLJS-side one ONLY when the server is co-hosted with the
  CLJS runtime (a shared-process deploy via nREPL). The JVM-standalone
  deploy reads an empty set — that's a correct answer for that deploy
  (no CLJS substrates are runnable from a JVM-only host).

  The CLJS var is resolved once, in `cljs-resolve` —
  `cljs-resolve/registered-substrates` is the single accessor
  (rf2-ee38b.17 removed the duplicate `defonce` that used to live here)."
  [_args]
  (result/edn-result {:substrates (vec (cljs-resolve/registered-substrates))}))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Dev-category descriptors, in IMPL-SPEC §7.2 order."
  [{:name           "get-story-instructions"
    :category       :dev
    :description    (str "Return Story's authoring conventions in agent-friendly form (the nine reg-* macros including the reg-fragment/reg-check composition surface, hard rules, lifecycle, snapshots). "
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
    :description    (str "Given a variant id, return the canvas state (app-db, assertions, rendered-hiccup, elapsed) + a sharable URL. Runs the SAME `story/run-variant` lifecycle as `run-variant`, so it accepts the SAME tunable `:timeout-ms` blocking knob (default 10000ms, hard ceiling 30000ms; caller values clamp DOWN). The `:app-db` slot is routed through `re-frame.core/elide-wire-value` against the variant frame's `[:rf.runtime/elision]` runtime-db registry — declared-sensitive paths return `:rf/redacted` and oversize slots return the `:rf.size/large-elided` marker by default. The derived `:rendered-hiccup` / `:effective-args` / `:snapshot` trees are value-scrubbed on BOTH egress axes against the same frame declarations: a leaf equal to a declared-`:sensitive?` value becomes `:rf/redacted`, and a leaf equal to a declared-`:large` value becomes the `:rf.size/large-elided` marker (the value reappears there at a non-app-db path the path walker can't reach; sensitive wins where both apply). Pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture). "
                         "Examples: "
                         "1. Default substrate: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :share-url \"...\" :status :pass :lifecycle :ready :app-db {...} :assertions [] :checks [] :rendered-hiccup [...]}. "
                         "2. UIx substrate + a mode: {:variant-id \":story.cart/full\" :substrate \":uix\" :active-modes [\":mode/dark\"]} -> same shape, rendered under uix + dark mode. "
                         "3. Slow variant with an explicit timeout: {:variant-id \":story.slow/loader\" :timeout-ms 20000} -> runs against the 20s ceiling (clamped to 30s max); on overrun returns {:status :error :lifecycle :error :assertions [{:assertion :rf.error/run-failed :status :error ...}]}. "
                         "4. Not registered: {:variant-id \":story.no/such\"} -> {:isError true :content [{:text \"Variant not found: :story.no/such\"}]}.")
    :typicalTokens  2000
    ;; rf2-90eft — `preview-variant` ships the variant's `:app-db`
    ;; re-keyed into `:rendered-hiccup` / `:effective-args` /
    ;; `:snapshot`; structural dedup collapses those four references
    ;; into one cache slot at the wire boundary. Mirrors pair-mcp's
    ;; selective `:dedup` knob on `snapshot` (descriptors_data.cljs).
    :dedup-eligible? true
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-dedup
                                  (s/with-include-sensitive
                                    (s/with-timeout-ms
                                      {:variant-id s/kw-or-string
                                       :substrate s/kw-or-string
                                       :active-modes {:type "array" :items s/kw-or-string}
                                       :cell-overrides {:type "object"}
                                       :base-url {:type "string"
                                                  :description "Optional base URL for the share link (no default)."}}))))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    ;; rf2-8h778 — `preview-variant` invokes the same `story/run-variant`
    ;; pipeline as `run-variant`: it dispatches events into the variant's
    ;; frame, accumulates assertions, and mutates the runtime. The audit
    ;; (rf2-3pn6c Finding #2) caught the asymmetry — `read-only-annotations`
    ;; here would have allowed agent hosts to auto-approve a call that
    ;; mutates the frame. The semantic distinction between the two tools
    ;; (`preview-variant` adds the share URL + rendered view; `run-variant`
    ;; is the headline run/verdict call — both return the same unified
    ;; run-result `:status`) is real but doesn't change the destructive
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

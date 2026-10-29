(ns re-frame.story-mcp.tools.dev
  "Dev-category tool handlers — `get-story-instructions`,
  `preview-variant`, `list-substrates`. These are the agent-onboarding
  and canvas-state surfaces described in spec/002-Tool-Registry.md.

  `story-instructions-text` ships inline as a single string so the
  artefact is self-contained — no resource read at boot, one MCP
  frame, zero classpath / IO dependencies. The structural peer in
  pair-mcp (`get-re-frame2-pair-instructions`) uses the same inline-
  `(str ...)` shape, kept aligned so AI pairs reading
  both servers see one answer to the onboarding-text question."
  (:require [re-frame.story :as rf.story]
            [re-frame.story-mcp.tools.args :as rf.story-mcp.tools.args]
            [re-frame.story-mcp.tools.cljs-resolve :as rf.story-mcp.tools.cljs-resolve]
            [re-frame.story-mcp.tools.egress :as rf.story-mcp.tools.egress]
            [re-frame.story-mcp.tools.lifecycle :as rf.story-mcp.tools.lifecycle]
            [re-frame.story-mcp.tools.result :as rf.story-mcp.tools.result]
            [re-frame.story-mcp.tools.schemas :as rf.story-mcp.tools.schemas]))

(def story-instructions-text
  "The agent-onboarding text returned by `get-story-instructions`.
  Inline `(str ...)` of `\\n`-glued lines — see the ns docstring for
  the rationale (mirrors pair-mcp). Edit this string
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
    "  (reg-variant :story.<path>/<variant>    { :doc :extends :compose :setup :script :checks :assertions :args :argtypes\n"
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
    "  - Variant bodies are EDN data: no closures, with one exception —\n"
    "    a predicate step (`[:assert-db path :pred f]`, `[:wait-until\n"
    "    [:db path :pred f]]`) takes a fn or a symbol, and a body carrying\n"
    "    a fn no longer EDN-round-trips. Outside the body, a `:hiccup`-kind\n"
    "    decorator's `:wrap` closure lives at the decorator's registration site.\n"
    "  - Variants reference decorators by id: `:decorators [[:dec-id args]]`.\n"
    "  - Inclusion tags must be registered before a variant references\n"
    "    them. Twelve canonical tags ship pre-registered — the seven\n"
    "    inclusion tags plus the five `:state/*` magnitude tags:\n"
    "      :dev :docs :test :screenshot :experimental :internal :agent\n"
    "      :state/empty :state/small :state/medium :state/large :state/special.\n"
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
  `:structuredContent` map. The descriptor declares an
  `:outputSchema` (`rf.story-mcp.tools.schemas/default-output-schema`); the official MCP SDK's
  high-level `callTool` REJECTS a tool that declares an output schema
  but returns no `:structuredContent` with JSON-RPC -32600. Mirroring
  re-frame2-pair-mcp's sibling `get-re-frame2-pair-instructions` (which
  routes through `wire/ok-text` and always emits structuredContent), we
  carry the prose under `:instructions` so the structured slot satisfies
  the permissive `additionalProperties: true` envelope schema."
  [_args]
  (let [payload {:instructions story-instructions-text}]
    (rf.story-mcp.tools.result/text-result story-instructions-text payload)))

(defn tool-preview-variant
  "Dev: given a variant id, return the canvas state + share URL.

  Returns the variant's run verdict, app-db, assertions, checks,
  snapshot and effective args; rendered output is not part of the
  result (`rf.story/render-variant` owns rendering). We invoke the
  shared `tools.lifecycle` execution owner (blocking `run-variant`
  deref + canonical exception normalization), and serialise the result
  map.

  `preview-variant` runs the SAME `rf.story/run-variant` lifecycle as
  `run-variant`, so it speaks the SAME unified run-result vocabulary —
  it does NOT ship a third result dialect. It surfaces
  the unified `:status` verdict + the unified `:assertions` records (each
  with a derived `:status`) + `:checks`, and ADDS the preview-specific
  slots: the `:share-url` from `rf.story/variant-share-url`, so the agent
  can hand the cell to a human
  collaborator, plus `:effective-args`. `:lifecycle`
  here is the loader-lifecycle STATE (`:ready` / `:error`), not the run
  verdict — the verdict is `:status`.

  Blocking-timeout posture: because preview and `run-variant`
  block on the SAME lifecycle, they share the SAME `:timeout-ms` knob +
  ceiling via `rf.story-mcp.tools.args/resolve-timeout-ms` (default 10 s, hard ceiling
  30 s, caller values clamp DOWN). The MCP request loop is single-threaded
  so an unbounded blocking deref would park unrelated calls; the shared
  helper means the two tools cannot drift by copy-paste, and an agent can
  discover + tune the ceiling from `tools/list` on either tool.

  Wire-egress posture: the `:app-db` slot is routed
  through `re-frame.core/project-egress`; the `:assertions` vec is
  filtered through `strip-sensitive`. Off-box defaults apply unless
  the caller passes `:include-sensitive true`."
  [arguments]
  (rf.story-mcp.tools.args/with-variant arguments
    (fn [vk _body]
      (or (rf.story-mcp.tools.args/run-opts-shape-error arguments)
          (rf.story-mcp.tools.args/substrate-arg-error arguments "preview-variant")
          ;; SEMANTIC run-option guard, after the shape +
          ;; substrate guards and before any lifecycle / share work: an
          ;; unknown `:active-modes` id or `:cell-overrides` key is
          ;; REFUSED rather than silently dropped by `read-run-opts`'s
          ;; bounded-allowlist coercion. Preview builds the share URL
          ;; from the same tuple, so a dropped identifier would hand the
          ;; agent a link to a scenario it did not request.
          (rf.story-mcp.tools.args/run-opts-semantic-error arguments vk)
          ;; Same host PREREQUISITE `run-variant` applies, through the same
          ;; lifecycle owner so the two tools cannot drift: preview runs the
          ;; SAME `rf.story/run-variant` lifecycle, so with no adapter installed it
          ;; would ship the same success-shaped non-run.
          (rf.story-mcp.tools.lifecycle/no-adapter-error "preview-variant")
          (rf.story-mcp.tools.lifecycle/abandoned-run-error "preview-variant" vk)
          (let [opts       (rf.story-mcp.tools.args/read-run-opts vk arguments)
                base-url   (or (:base-url arguments) "")
                share-url  (rf.story/variant-share-url vk base-url opts)
                ;; Blocking invocation + timeout + canonical exception
                ;; normalization are owned by `tools.lifecycle` — the ONE
                ;; execution owner `run-variant` shares (same tunable ceiling
                ;; via `rf.story-mcp.tools.args/resolve-timeout-ms`, default 10s / 30s hard cap).
                ;; A throw / timeout is normalized through `rf.story/run-result`
                ;; with `:lifecycle :error` stamped, so preview keeps the
                ;; loader-state slot it reads AND speaks the SAME unified
                ;; run-result vocabulary a settled run emits. The
                ;; preview-specific projection / egress / wire shaping stays
                ;; below.
                outcome    (rf.story-mcp.tools.lifecycle/run-variant-blocking
                             vk opts (rf.story-mcp.tools.args/resolve-timeout-ms arguments))
                incl?      (rf.story-mcp.tools.args/include-sensitive? arguments)
                raw-db     (:app-db outcome)
                [assertions dropped] (rf.story-mcp.tools.egress/scrub-assertions+count (:assertions outcome) incl?)
                payload    {:variant-id   vk
                            :share-url    share-url
                            :status       (:status outcome)
                            :lifecycle    (:lifecycle outcome)
                            :elapsed-ms   (:elapsed-ms outcome)
                            :app-db       (rf.story-mcp.tools.egress/elide-app-db raw-db vk incl?)
                            :assertions   assertions
                            ;; Same filter as `:assertions` — the check groups
                            ;; carry the same records.
                            :checks       (rf.story-mcp.tools.egress/scrub-checks (:checks outcome) incl?)
                            ;; Derived trees are PATH-projected through scrub-rendered:
                            ;; a value AT a classified path redacts, a re-keyed copy
                            ;; ships raw (EP-0025 fail-open). `run-variant` produces
                            ;; no rendered output — rendering is
                            ;; `rf.story/render-variant`'s.
                            :snapshot     (rf.story-mcp.tools.egress/scrub-rendered (:snapshot outcome) raw-db vk incl?)
                            :effective-args (rf.story-mcp.tools.egress/scrub-rendered (:effective-args outcome) raw-db vk incl?)}]
            ;; Surface the MUST-level egress indicator counts:
            ;; how many sensitive assertion records were dropped + how many
            ;; over-threshold leaves were elided across the payload. Omitted
            ;; when zero (Conventions §Cross-MCP indicator-field vocabulary).
            (rf.story-mcp.tools.egress/result-with-indicators payload dropped))))))

(defn tool-list-substrates
  "Dev: what substrates can be used. Reads the registered substrate set
  via the Story-public surface.

  Substrates are registered through the CLJS-only `register-substrate!`
  surface. When the substrate-registry provider is UNREACHABLE (the JVM
  stdio server has no bridge to that browser registry), this returns a
  machine-readable capability-unavailable error — NOT a false-empty
  `{:substrates []}` success that an agent could mistake for 'no
  substrates registered'. Ordinary success with a
  (possibly empty) `:substrates` vec is reserved for a REACHED provider —
  a browser-local Story host whose registry actually answered.

  Availability is checked through the `cljs-resolve` host-capability
  boundary; `rf.story-mcp.tools.cljs-resolve/registered-substrates` reads the reached
  provider's set."
  [_args]
  (if (rf.story-mcp.tools.cljs-resolve/substrate-provider-available?)
    (rf.story-mcp.tools.result/edn-result {:substrates (vec (rf.story-mcp.tools.cljs-resolve/registered-substrates))})
    (rf.story-mcp.tools.result/capability-unavailable-result
      {:tool       "list-substrates"
       :capability "substrate-registry"
       :detail     "Substrate registration is CLJS-only (`register-substrate!`)."})))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Dev-category descriptors, in spec/002-Tool-Registry.md order."
  [{:name           "get-story-instructions"
    :description    (str "Return Story's authoring conventions as text: the reg-* macros (reg-fragment and reg-check "
                         "included), the hard rules, the variant lifecycle and snapshots. Call it first in a session, then"
                         " the list-* tools to enumerate the registry. Example: {} -> text content with the conventions "
                         "prose.")
    :typicalTokens  1500
    :inputSchema    {:type "object" :properties (rf.story-mcp.tools.schemas/with-max-tokens {}) :additionalProperties false}
    :outputSchema   rf.story-mcp.tools.schemas/default-output-schema
    :annotations    rf.story-mcp.tools.schemas/read-only-annotations
    :handler        tool-get-story-instructions}

   {:name           "preview-variant"
    :description    (str "Run a variant headlessly and return its canvas state and a shareable URL: {:variant-id "
                         ":share-url :status :lifecycle :app-db :assertions :checks :effective-args :snapshot ...}. "
                         "`:status` (:pass :fail :cannot-run :error) is the verdict; `:lifecycle` (:ready / :error) is the"
                         " loader state. It runs the same lifecycle as run-variant and refuses the same way (isError): no "
                         "installed re-frame adapter gives `:rf.error/no-adapter-installed`; an unknown `:active-modes` id"
                         " or `:cell-overrides` key is named with the accepted set, never dropped. `:timeout-ms` defaults "
                         "to 10000 and clamps to 30000; an overrun returns `:status :error`. Values are projected through "
                         "the frame's classification: a sensitive path reads `:rf/redacted`, an oversize slot "
                         "`:rf.size/large-elided`. Rendered output is not included. Example: {:variant-id "
                         "\":story.cart/full\" :active-modes [\":mode/dark\"]} -> {:variant-id :story.cart/full :share-url "
                         "\"...\" :status :pass :lifecycle :ready :app-db {...} :assertions [] ...}.")
    :typicalTokens  2000
    ;; `preview-variant` ships the variant's `:app-db`
    ;; re-keyed into `:effective-args` / `:snapshot`; structural
    ;; dedup collapses those three references
    ;; into one cache slot at the wire boundary. Mirrors pair-mcp's
    ;; selective `:dedup` knob on `snapshot` (descriptors_data.cljs).
    :dedup-eligible? true
    :inputSchema {:type "object"
                  :properties (rf.story-mcp.tools.schemas/with-max-tokens
                                (rf.story-mcp.tools.schemas/with-dedup
                                  (rf.story-mcp.tools.schemas/with-include-sensitive
                                    (rf.story-mcp.tools.schemas/with-timeout-ms
                                      {:variant-id rf.story-mcp.tools.schemas/kw-or-string
                                       :substrate rf.story-mcp.tools.schemas/kw-or-string
                                       :active-modes {:type "array" :items rf.story-mcp.tools.schemas/kw-or-string}
                                       :cell-overrides {:type "object"}
                                       :base-url {:type "string"
                                                  :description "Optional base URL for the share link (no default)."}}))))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema rf.story-mcp.tools.schemas/default-output-schema
    ;; `preview-variant` invokes the same `rf.story/run-variant`
    ;; pipeline as `run-variant`: it dispatches events into the variant's
    ;; frame, accumulates assertions, and mutates the runtime. So it carries
    ;; the destructive run annotations, not `read-only-annotations` — the
    ;; latter would let agent hosts auto-approve a call that mutates the
    ;; frame. The semantic distinction between the two tools
    ;; (`preview-variant` adds the share URL + effective args; `run-variant`
    ;; is the headline run/verdict call — both return the same unified
    ;; run-result `:status`) is real but doesn't change the destructive
    ;; nature of the underlying lifecycle run.
    :annotations  rf.story-mcp.tools.schemas/run-variant-annotations
    :handler     tool-preview-variant}

   {:name           "list-substrates"
    :available?     rf.story-mcp.tools.cljs-resolve/substrate-provider-available?
    :description    (str "Report the render substrates registered with Story (`re-frame.story/register-substrate!`). "
                         "Listed only on a host that reaches the CLJS substrate registry. Example: {} -> {:substrates "
                         "[:reagent :uix]}, or {:substrates []} when the registry holds none.")
    :typicalTokens  100
    :inputSchema    {:type "object" :properties (rf.story-mcp.tools.schemas/with-max-tokens {}) :additionalProperties false}
    :outputSchema   rf.story-mcp.tools.schemas/default-output-schema
    :annotations    rf.story-mcp.tools.schemas/read-only-annotations
    :handler        tool-list-substrates}])

(ns re-frame2-pair-mcp.tools.descriptors-data
  "MCP `tools/list` descriptor data — one `def` per tool.

  Catalogue-shaped leaf: the descriptor maps live here as named
  vars so `tools/registry.cljs` can reference each by symbol (one
  registry entry per tool, descriptor included by name) without
  forcing the registry to inline ~280 lines of descriptor blobs.

  As a catalogue-shaped leaf, this file is permitted to exceed the
  standard ≤250-line ceiling — splitting it per-tool would force
  readers to chase across twelve files when comparing descriptor
  shapes.

  Universal knobs (`max-tokens`, `cache`) are spliced via
  `descriptors-knobs/with-budget-knob` and `with-cache-knob` inside
  `descriptors/tool-descriptors-js`, not at the def site. Per-tool
  knobs (limit, cursor, dedup, elision) reach in by name below.

  Each entry carries `:name`, `:description`, `:inputSchema`,
  `:outputSchema` (describes the `:structuredContent` payload shape;
  agent hosts use it to validate the result client-side), and
  `:typicalTokens` — an informational ballpark of the response-payload
  size in tokens that AI clients use to budget calls and pick
  size-conscious args (`max-tokens`, `cache`, `cursor`) without
  trial-and-error. Hint only; the real cap is enforced separately."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.tools.descriptors-knobs :as knobs]))

;; ---------------------------------------------------------------------------
;; Recurring outputSchema fragments.
;;
;; The `:structuredContent` payload is `clj->js` of the
;; EDN payload — keywords become string keys (`:ok?` → `"ok?"`). Tool
;; results split into two coarse families:
;;
;;   1. Envelope shape — `{:ok? bool ...}` with `:reason` keyword on
;;      the error path. Used by ~every tool. We declare the contract
;;      bones (`ok?`, optional `reason`) and accept tool-specific
;;      slots via `additionalProperties: true` rather than enumerating
;;      every per-tool slot (that's per-tool prose in the catalogue,
;;      not the schema's job).
;;
;;   2. Wire-bounded markers — `{:rf.mcp/cache-hit ...}` and
;;      `{:rf.mcp/overflow ...}`. These envelopes replace a tool's
;;      result when the cache step or cap step fires. The agent host
;;      pattern-matches on the marker keys.
;;
;; MCP-spec constraint: the official `ListToolsResultSchema` (Zod) pins
;; `outputSchema.type` to the literal string \"object\" and rejects any
;; other shape — notably top-level `oneOf` / `anyOf` are NOT valid MCP
;; outputSchemas. Per spec we ship one flat object envelope per tool,
;; describing the contract bones plus the marker keys as optional
;; top-level slots; tool-specific payload rides on
;; `additionalProperties: true`. The marker semantics (cache-hit /
;; overflow REPLACE the normal envelope) are documented in prose at
;; spec/003-Tool-Catalogue.md §Universal.
;; ---------------------------------------------------------------------------

(def ^:private envelope-or-marker
  "Default outputSchema — a flat object envelope describing the
  contract bones (`ok?`, `reason`) plus the wire-bounded marker keys
  (`rf.mcp/cache-hit`, `rf.mcp/overflow`) as optional top-level
  slots. Read tools get this shape; permissive
  (`additionalProperties: true`) so per-tool payload slots ride.

  The marker shapes REPLACE the normal envelope on the wire (cache
  hits and cap overflows ship a payload whose ONLY top-level key is
  the marker); agent hosts dispatch on presence of the marker key.
  We don't encode that as `oneOf` because the MCP outputSchema
  contract pins `type` to the literal \"object\"."
  {:type "object"
   :additionalProperties true
   :properties {"ok?"    {:type "boolean"
                           :description "True on success; false on error (with :reason)."}
                "reason" {:type "string"
                           :description "Error reason keyword (as a string). Present iff :ok? false."}
                "rf.mcp/cache-hit" {:type "object"
                                    :additionalProperties true
                                    :description (str "Wire-bounded marker. When present, REPLACES the "
                                                      "normal envelope; ships on a per-session cache hit.")}
                "rf.mcp/overflow"  {:type "object"
                                    :additionalProperties true
                                    :description (str "Wire-bounded marker. When present, REPLACES the "
                                                      "normal envelope; ships when the cap step fires.")}}
   :description (str "Result envelope: success / error map carrying :ok? + tool-specific slots. "
                     "Wire-bounded markers (:rf.mcp/cache-hit, :rf.mcp/overflow) replace the "
                     "tool's normal envelope when the cache or cap step fires; the agent host "
                     "pattern-matches on the marker key. See "
                     "spec/003-Tool-Catalogue.md §Universal: per-session response cache + "
                     "§Universal: wire-boundary token cap.")})

;; ---------------------------------------------------------------------------
;; Tool annotations.
;;
;; mcp_best_practices.md guidance: every tool descriptor SHOULD declare
;; annotation hints so agent hosts (Claude Code, Continue) can auto-
;; approve read-only operations and gate destructive ones with one
;; permission ceremony. Four slots:
;;
;;   :readOnlyHint     — non-destructive, no observable side-effect.
;;   :destructiveHint  — observable state change (dispatch, eval-cljs).
;;   :idempotentHint   — repeated calls with the same args produce the
;;                       same result (snapshot, get-path).
;;   :openWorldHint    — reaches external state (the browser runtime).
;;
;; Each tool's matrix pick is documented inline at its descriptor.
;; ---------------------------------------------------------------------------

(def ^:private read-only-annotations
  "Annotations for pure-read tools — no observable side-effect, safe
  to auto-approve. `:openWorldHint true` because every read reaches
  the browser runtime over nREPL (an external process)."
  {:readOnlyHint   true
   :openWorldHint  true})

(def ^:private idempotent-read-only-annotations
  "Read-only AND idempotent — `snapshot`, `get-path`, `discover-app`,
  `list-subscriptions`, `handler-meta`, `list-handlers`
  return the same answer for the same args + same runtime state.
  (`list-subscriptions` reads the live reactive sub-cache —
  idempotent across same-state calls just like `snapshot :sub-cache`.)"
  {:readOnlyHint   true
   :idempotentHint true
   :openWorldHint  true})

(def ^:private inline-read-only-annotations
  "Read-only AND inline (no runtime round-trip) — `get-re-frame2-pair-
  instructions` ships static text from the bundle. No external reach,
  so `:openWorldHint false`."
  {:readOnlyHint   true
   :idempotentHint true
   :openWorldHint  false})

(def ^:private destructive-annotations
  "Annotations for state-mutating tools — `dispatch`, `eval-cljs`, and
  `tail-build` (its `:probe` is arbitrary CLJS evaluated in the runtime,
  so it can express any mutation eval-cljs can).
  Mutations happen in the browser runtime; agent hosts should gate
  these behind explicit user confirmation by default."
  {:destructiveHint true
   :openWorldHint   true})

(def ^:private record-annotations
  "Annotations for `record` — installs a read-only observer
  on the runtime. `:readOnlyHint true` because the recorder never mutates
  app-db / the DOM (every signal sampler only reads), but NOT
  `:idempotentHint` (each call mints a fresh recording-id and starts a
  new background sampler) and `:openWorldHint true` (it reaches the
  browser runtime and has an observable side-effect on the recording
  registry). `read-recording` / `watch-until` are pure reads and ride the
  plain read-only annotation set."
  {:readOnlyHint  true
   :openWorldHint true})

(def ^:private session-pin-annotations
  "Annotations for the session-mutating operating-frame ops
  (`set-operating-frame`, `reset-operating-frame`). They
  mutate the per-session frame pin in the runtime preload — NOT app-db,
  NOT the DOM — so `:readOnlyHint false` (they write *session* state)
  but NOT `:destructiveHint` (no irreversible app effect; a wrong pin is
  corrected by another set / reset). `:idempotentHint true`: pinning the
  same frame twice — or resetting twice — yields the same state.
  `:openWorldHint true` because they reach the browser runtime over
  nREPL. `get-operating-frame` is a pure read and rides the plain
  idempotent-read-only set."
  {:idempotentHint true
   :openWorldHint  true})

;; ===========================================================================
;; Tool descriptors
;;
;; One def per tool, in catalogue order (per
;; spec/003-Tool-Catalogue.md). Each section is bounded by a comment
;; rule so a reader scrolling through the file can land on a tool
;; without grepping. The catalogue-shaped-leaf size exemption earns
;; the length; the section navigation earns the scannability.
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; discover-app
;; ---------------------------------------------------------------------------

(def discover-app
  {:name "discover-app"
   :description (str "Verify the shadow-cljs nREPL is reachable and the re-frame2-pair runtime preload landed, and "
                     "report a health summary. Run this first every session. Omit `build` when exactly one build "
                     "is running (it is auto-selected and noted), or pass `port` to resolve the build serving a "
                     "browser URL. Returns {:ok? true :build-id ... :frames [...] :app-frames [...] :freshness "
                     "{...}}. Check :freshness :liveness BEFORE trusting reads: :stale-build means reload the "
                     "page, :no-runtime means no tab is connected. A :runtime-count above 1 adds :warning "
                     ":several-runtimes — every op reaches one tab, and no argument picks another. A failed probe "
                     "names its cause with a :hint: :reason :nrepl-unreachable, :build-not-running, "
                     ":no-runtime-connected or :runtime-loaded-but-preload-missing. Example: {} -> {:ok? true "
                     ":build-id :app :auto-selected-build :app :frames [:rf/default] :freshness {:liveness :fresh "
                     "...}}.")
   :typicalTokens 200
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:build {:type "string"
                                      :description (str "shadow-cljs build id. Colon-tolerant: "
                                                        "\"examples/step-deck\" and \":examples/step-deck\" "
                                                        "resolve identically. "
                                                        "Omit it when exactly one build is running — discover-app "
                                                        "auto-selects it and notes the choice. "
                                                        "Falls back to app when none can be inferred.")}
                              :port {:type "integer"
                                     :description (str "Resolve the serving build from a browser URL's port via "
                                                       "the shadow-cljs :dev-http map. E.g. you opened "
                                                       "http://localhost:8031/counter -> pass {:port 8031} and "
                                                       "discover-app finds the build whose :output-dir is served "
                                                       "on that port — no manual grep of shadow-cljs.edn. A port "
                                                       "that maps to no build -> :reason :port-unresolved. Ignored "
                                                       "when :build is given.")}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; orient
;; ---------------------------------------------------------------------------

(def orient
  {:name "orient"
   :description (str "App-shape orientation in ONE round-trip — \"what is this app and what can I drive?\" — "
                     "composing what discover-app, snapshot, list-handlers and list-subscriptions would take "
                     "several calls to gather. Returns {:ok? true :liveness {...} :frames {:all [...] :app [...] "
                     ":operating <id>} :app-db-top-keys {<app-frame> [<key> ...]} :registry {:counts {<kind> N "
                     "...} :events [...] :subs [...] :fx [...]} :machines [...]}: registrar counts plus the first "
                     "20 ids of each list, never the full app-db, with reserved :rf/* tool frames split out. A "
                     "list cut short appears in :truncated, whose :next is the list-handlers call that returns "
                     "every id. Drill with list-handlers, handler-meta, get-path or read-sub. Example: {} -> {:ok? "
                     "true :frames {:all [:rf/default :rf/xray] :app [:rf/default] :operating :rf/default} "
                     ":app-db-top-keys {:rf/default [:cart :route :user]} :registry {:counts {:event 14 :sub 9 "
                     "...} :events [:cart/add :cart/checkout ...] ...} :machines [:checkout]}.")
   :typicalTokens 600
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:build {:type "string"
                                      :description "shadow-cljs build id. Defaults per the session-resolved build / SHADOW_CLJS_BUILD_ID / :app."}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; eval-cljs
;; ---------------------------------------------------------------------------

(def eval-cljs
  {:name "eval-cljs"
   :description (str "Evaluate a ClojureScript form in the connected browser runtime (shadow-cljs cljs-eval) and "
                     "return its EDN value as {:ok? true :value <v>}. Pass `frame` for any frame-scoped form "
                     "(subscribe, dispatch): without it the form carries no frame, and such an op raises "
                     ":rf.error/no-frame-context rather than falling back to :rf/default. The frame wrap is "
                     "synchronous-lexical only, so an async continuation must capture the frame itself. `await "
                     "true` awaits a returned Promise server-side (:rf.error/eval-cljs-rejected, or "
                     ":rf.error/eval-cljs-timeout after `timeout-ms`). Enabled by default; a server launched with "
                     "--no-eval returns :rf.error/eval-cljs-disabled. Example: {:form \"@(re-frame.core/subscribe "
                     "[:current-user])\" :frame \":rf/default\"} -> {:ok? true :value {:id 42 :name \"Ada\"} :frame "
                     ":rf/default}.")
   :typicalTokens 500
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:form       {:type "string" :description "The CLJS form to evaluate."}
                              :build      {:type "string" :description "shadow-cljs build id (default: app)"}
                              :frame      {:type "string"
                                           :description (str "Operating frame for the form's lexical scope. "
                                                             "When supplied, the form is wrapped in "
                                                             "(re-frame.core/with-frame <frame> <form>) server-side so "
                                                             "(rf/subscribe ...) / (rf/dispatch ...) / (rf/current-frame-id) "
                                                             "inside the form resolve against the named frame. Accepts "
                                                             "bare names (\"app/main\") or EDN-shaped strings "
                                                             "(\":app/main\"). EP-0002: when omitted the form "
                                                             "carries NO frame stamp — a frame-scoped op inside it raises "
                                                             ":rf.error/no-frame-context rather than running against a "
                                                             "synthesised :rf/default. Synchronous-lexical ONLY: an async "
                                                             "continuation (a .then after a Promise) is past the with-frame "
                                                             "extent and must capture the frame explicitly. Joins the family "
                                                             "of frame-aware ops (dispatch, snapshot, get-path, etc.).")}
                              :await      {:type "boolean"
                                           :description (str "When true, if the form returns a thenable (Promise / "
                                                             "any object with a .then method), await it server-side "
                                                             "and return the resolved value. Non-thenable returns "
                                                             "pass through unchanged. Default false (Promise objects "
                                                             "return as #object[Promise ...] strings, preserving "
                                                             "passthrough semantics for forms that hand a Promise to "
                                                             "other code). Rejections surface as :rf.error/eval-cljs-rejected; "
                                                             "exceeding :timeout-ms surfaces as :rf.error/eval-cljs-timeout. "
                                                             "Composes with :frame — the with-frame wrap is the outer-"
                                                             "most form, the await mailbox sentinel "
                                                             "rides through.")}
                              :timeout-ms {:type "integer"
                                           :description (str "Maximum ms to wait for a Promise to settle when "
                                                             ":await is true. Default 5000. Ignored when :await is "
                                                             "false. The caller picks the deadline because async "
                                                             "form costs vary widely (a 5ms Promise.resolve vs. a "
                                                             "multi-second layout computation).")}}
                 :required ["form"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; dispatch
;; ---------------------------------------------------------------------------

(def dispatch
  {:name "dispatch"
   :description (str "Fire a re-frame2 event, tagged :origin :pair. `event` is an EDN vector, parsed once and "
                     "echoed under :resolved; its id is validated against the live :event registrar, and an "
                     "unknown id returns :reason :unknown-id with :nearest matches and does NOT dispatch. "
                     "Non-vector or unreadable input returns :reason :not-an-event-vector or :invalid-event-edn, "
                     "and host source is refused (use eval-cljs). By default the dispatch runs synchronously and "
                     "returns its consequence — :epoch-id, :db-changed?, :changed-paths, :effects-fired, :no-op? "
                     "and a :cascade-summary (:event-id, :db-diff, :fx-fired, sub and render counts, :outcome) — "
                     "so a no-op is visible. Modes: `queued` returns the async ack (:settled? false until the "
                     "cascade drains; poll watch-epochs); `trace` adds the full :rf/epoch-record; `await-render` "
                     "resolves after the DOM paints (:rf.error/dispatch-await-render-timeout after `timeout-ms`); "
                     "`settle` flushes renders synchronously and returns the settled :epoch with its "
                     ":render-events, and wins over the others. PRIVACY: the epoch `trace` and `settle` return "
                     "crosses re-frame.core/project-egress, so declared-sensitive app-db slots read :rf/redacted; "
                     "`include-sensitive true` keeps them, honoured only when the server was launched with "
                     "--allow-sensitive-reads. `cofx` scripts recordable coeffects for a deterministic run, and "
                     "`replay true` re-presents them under the strict mint policy, where a missing fact fails "
                     ":rf.error/missing-required-cofx. To replay a retained epoch, replay-epoch re-supplies its "
                     "event, cofx and overrides from the id alone. Example: {:event \"[:cart/checkout]\"} -> {:ok? "
                     "true :mode :sync :epoch-id 7 :resolved [:cart/checkout] :db-changed? true :changed-paths "
                     "[[:cart]] :effects-fired [:dispatch] :no-op? false :cascade-summary {...}}.")
   :typicalTokens 300
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:event {:type "string" :description "The event vector as EDN, e.g. \"[:cart/checkout]\" or \"[:cart/add {:sku \\\"abc\\\"}]\". MUST be a vector — non-vector EDN and host-form source are rejected. Parsed once, echoed under :resolved, and the event-id validated against the live :event registrar (unknown -> :reason :unknown-id + :nearest, NOT dispatched)."}
                              :sync  {:type "boolean" :description "Force synchronous dispatch returning the consequence. Default mode already returns the consequence synchronously; this is the explicit form."}
                              :queued {:type "boolean" :description "Opt into the async transport-ack shape instead of the default consequence: returns :settled? false when the cascade hasn't drained, so the agent polls watch-epochs for the settlement."}
                              :trace {:type "boolean"
                                      :description "Return the full assembled :rf/epoch-record under :epoch, projected for egress (see include-sensitive). Default false."}
                              :await-render {:type "boolean"
                                             :description (str "Render-settle: when true, resolve only AFTER the "
                                                               "substrate has flushed the new state to the DOM and the next "
                                                               "paint is scheduled, so `dispatch -> observe` is one "
                                                               "deterministic step. The flush routes through the "
                                                               "substrate-agnostic `re-frame.interop/after-render` adapter hook "
                                                               "(Spec 006), then one requestAnimationFrame pins resolution to "
                                                               "the paint boundary. Forces synchronous dispatch (the cascade "
                                                               "must commit before the render can settle); merges :settled? true "
                                                               "into the result. Default false. A settle exceeding :timeout-ms "
                                                               "returns :rf.error/dispatch-await-render-timeout.")}
                              :settle {:type "boolean"
                                       :description (str "Dispatch-and-settle: when true, dispatch then "
                                                         "SYNCHRONOUSLY flush pending renders via the substrate "
                                                         "adapter's flush-render! (React flushSync / reagent.core/flush, "
                                                         "resolved from the INSTALLED adapter — no substrate hardcoding; "
                                                         "NOT rAF-scheduled, so it commits even headless / backgrounded), "
                                                         "then RE-READ and return the SETTLED epoch. The result carries "
                                                         "the full :epoch, a :render-events slot (the :rf.view/render + "
                                                         ":rf.view/unmounted entries), :settled? true, and a "
                                                         ":cascade-summary whose :renders count reflects the flush. ONE "
                                                         "call = dispatch → render → complete epoch. Wins over "
                                                         "await-render / trace / queued. SCOPE: settles the synchronous "
                                                         "cascade + render flush only; async fx stay observed via "
                                                         "watch-epochs. Default false.")}
                              :timeout-ms {:type "integer"
                                           :description (str "Maximum ms to wait for the render-settle promise when "
                                                             ":await-render is true. Default 5000. Ignored when "
                                                             ":await-render is false (and unused by :settle, which is "
                                                             "synchronous). Raise it for substrates with a slow "
                                                             "render path; lower it to fail fast when no root is mounted.")}
                              :frame {:type "string" :description "Operating frame (e.g. :stories)"}
                              :fx-overrides {:type "object"
                                             :description (str "Per-call fx redirects, e.g. {:http :stub-http}. With `replay true`, pass the "
                                                               "recorded epoch's own :fx-overrides; a recorded :rf/fn-override value "
                                                               "cannot be re-supplied and fails :rf.error/unreplayable-fx-override.")}
                              :interceptor-overrides {:type "object"
                                                       :description (str "Per-call interceptor substitution/removal by exact "
                                                                         "reference (Spec 002 §:interceptor-overrides), e.g. "
                                                                         "{\":auth/required\": \":story/skip-auth\", "
                                                                         "\":audit/record-event\": null}. Keys/values are each "
                                                                         "EITHER a colon-prefixed keyword id or a bracket-shaped "
                                                                         "\"[id arg]\" EDN string for a parameterized ref (e.g. "
                                                                         "\"[:rf.interceptor/path [:cart]]\"); null removes the "
                                                                         "matched interceptor. Tool-Pair §Replay: "
                                                                         "to replay a recorded epoch, pass its OWN "
                                                                         "`:interceptor-overrides` (read off the "
                                                                         ":rf/epoch-record) here alongside `replay true` — a "
                                                                         "recorded active override re-presents verbatim rather "
                                                                         "than silently replaying under a different chain.")}
                              :cofx {:type "string"
                                     :description (str "Reproducible dispatch (EP-0010 + EP-0017): an EDN MAP "
                                                       "of scripted recordable coeffects threaded into the dispatch "
                                                       "envelope as :rf.cofx, e.g. \"{:rf/time-ms 1781078400123}\" (and "
                                                       "optionally owner-qualified app facts like :counter/delta). The "
                                                       "router PRESERVES the supplied map verbatim (filling only "
                                                       ":rf/time-ms when absent), so the dispatched event carries the "
                                                       "EXACT wall-clock time / recorded fact and the resulting state is "
                                                       "DETERMINISTIC — an agent can replay a fixture and assert the "
                                                       "same output. :rf/time-ms must be an integer (epoch ms); a "
                                                       "non-map / non-integer-:rf/time-ms / unreadable value returns "
                                                       ":reason :invalid-cofx and does NOT dispatch. Omit it for the "
                                                       "ordinary live path (the runtime stamps :rf/time-ms). NB: `cofx` "
                                                       "alone is LIVE — pair it with `replay true` to re-present a "
                                                       "recorded token under the strict replay policy.")}
                              :replay {:type "boolean"
                                       :description (str "Strict replay (EP-0017 §6 / Tool-Pair §Replay): "
                                                         "when true, re-drive the event under :rf.cofx/mint-policy "
                                                         ":strict, re-presenting the recorded `cofx` token verbatim. A "
                                                         "recorded recordable fact MISSING from the token fails LOUDLY "
                                                         "(:rf.error/missing-required-cofx — no generator runs, no host "
                                                         "read) instead of silently re-minting a divergent value, so an "
                                                         "incomplete record cannot masquerade as a faithful replay. The "
                                                         "per-call strict opt wins over a :live frame config. Strict even "
                                                         "with no `cofx` (an empty record still halts on any declared "
                                                         "recordable fact). Default false: a `cofx`-only dispatch stays "
                                                         ":live (a declared-absent generator-backed fact is freshly "
                                                         "minted). Set true to ASSERT a fixture reproduces. `replay` "
                                                         "hard-wires the strict cofx policy ONLY — a faithful replay ALSO "
                                                         "needs the recorded envelope's own `fx-overrides` / "
                                                         "`interceptor-overrides` passed as those separate "
                                                         "args, read off the :rf/epoch-record, per Tool-Pair §Replay.")}
                              :include-sensitive {:type "boolean"
                                                  :description (str "Epoch-egress privacy opt-in for the :trace / :settle modes. "
                                                                    "Those modes return a RAW "
                                                                    ":rf/epoch-record (:db-before / :db-after / :trace-events; "
                                                                    ":settle also :render-events) that ALWAYS routes through the "
                                                                    "framework's off-box projection (re-frame.core/project-egress) "
                                                                    "before crossing the wire, exactly like trace-window / "
                                                                    "watch-epochs. Default false: declared-:sensitive? app-db slots "
                                                                    "in the projected epoch redact to :rf/redacted. Set true to pass "
                                                                    "them through verbatim — honoured ONLY when the server was "
                                                                    "launched with --allow-sensitive-reads; otherwise forced false. "
                                                                    "Governs the app-db sensitive axis ONLY: the "
                                                                    "orthogonal fx-args / runtime-db / large axes stay "
                                                                    "fail-closed regardless. Ignored by the default "
                                                                    "sync / queued / await-render consequence shapes (they carry no "
                                                                    "raw app-db).")}
                              :build {:type "string"}}
                 :required ["event"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; dispatch-dry-run
;; ---------------------------------------------------------------------------

(def dispatch-dry-run
  {:name "dispatch-dry-run"
   :description (str "Simulate one registered event and roll it back: the handler, interceptors and machine "
                     "transitions run, every declared fx (child dispatches included) is recorded and skipped, then "
                     "the pre-call frame-state is restored. Side effects a handler performs directly are NOT "
                     "undone, downstream events are not simulated, and the simulation and its rollback stay "
                     "visible in the epoch history. Trust the result only on :ok? true AND :rolled-back? true — "
                     ":rollback-failed means simulated state can still be live. With epoch recording off it "
                     "refuses before running, with :reason :no-epoch-recorded. Returns {:ok? true :dry-run? true "
                     ":rolled-back? true :cascade-summary {...} :would-fire-effects [{:fx-id ... :args ...}] "
                     ":db-state-after-simulation {...}}, the :cascade-summary in dispatch's shape. PRIVACY: "
                     ":db-state-after-simulation is elided and redacted like get-path — `elision false` is "
                     "honoured on every launch, `include-sensitive true` only under --allow-sensitive-reads. Every "
                     ":would-fire-effects :args reads :rf/redacted by default; `include-fx-args true` opts them "
                     "in, also only under --allow-sensitive-reads. Example: {:event \"[:cart/checkout]\"} -> {:ok? "
                     "true :dry-run? true :rolled-back? true :cascade-summary {:event-id :cart/checkout :db-diff "
                     "{...} ...} :would-fire-effects [{:fx-id :http :args :rf/redacted}] "
                     ":db-state-after-simulation {...}}.")
   :typicalTokens 800
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:event {:type "string"
                                      :description "The event vector as EDN, e.g. \"[:cart/checkout]\". MUST be a vector — same EDN-data posture as dispatch."}
                              :frame {:type "string" :description "Operating frame (e.g. \":checkout\"). Defaults to the operating frame."}
                              :cofx {:type "string"
                                     :description (str "Reproducible dry-run (EP-0017): an EDN MAP of scripted "
                                                       "recordable coeffects threaded into the simulated dispatch as :rf.cofx, "
                                                       "e.g. \"{:rf/time-ms 1781078400123}\" (and optionally owner-qualified app "
                                                       "facts like :counter/delta). Identical posture to dispatch's :cofx: the "
                                                       "router PRESERVES the supplied map verbatim (filling only :rf/time-ms when "
                                                       "absent), so a dry-run of a time-dependent or provided-cofx event runs "
                                                       "against the EXACT causal token you intend to test rather than a freshly "
                                                       "stamped :rf/time-ms or a :rf.error/missing-required-cofx for a provided "
                                                       "fact. :rf/time-ms must be an integer (epoch ms); a non-map / "
                                                       "non-integer-:rf/time-ms / unreadable value returns :reason :invalid-cofx "
                                                       "and does NOT simulate. Omit it for the ordinary live path (the runtime "
                                                       "stamps :rf/time-ms).")}
                              :elision {:type "boolean"
                                        :description (str "Size elision of the :db-state-after-simulation egress slot "
                                                          "(the app-db-rooted slot). Default true (markers emitted). This is the "
                                                          "SIZE axis only (EP-0015 §10): false overlays include-large? true on the "
                                                          ":rf.egress/off-box-tool profile floor so large slots ride verbatim, but "
                                                          "declared-:sensitive? slots STILL redact to :rf/redacted (use "
                                                          ":include-sensitive for those). Honoured on every launch — no launch flag "
                                                          "needed. Does NOT "
                                                          "govern :would-fire-effects[*].args — those fail closed independently "
                                                          "(see :include-fx-args).")}
                              :include-sensitive {:type "boolean"
                                                  :description (str "Pass declared-:sensitive? app-db slots through verbatim instead of "
                                                                    "redacting to :rf/redacted. Default false. Honoured ONLY when the "
                                                                    "server was launched with --allow-sensitive-reads; otherwise forced "
                                                                    "false.")}
                              :include-fx-args {:type "boolean"
                                                :description (str "Pass the RAW :would-fire-effects[*].args (fx-handler arguments — HTTP "
                                                                  "bodies, dispatched event vectors, payment maps) through verbatim instead "
                                                                  "of failing closed to :rf/redacted. These are NOT rooted at app-db so the "
                                                                  "elision walker cannot prove them safe; off-box egress fails closed by "
                                                                  "default (matching the epoch projector's :effects[*].args). "
                                                                  "Default false. Honoured ONLY when the server was launched with "
                                                                  "--allow-sensitive-reads; otherwise forced false. Orthogonal to "
                                                                  ":include-sensitive (a different keyspace).")}
                              :build {:type "string"}}
                 :required ["event"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; restore-epoch
;; ---------------------------------------------------------------------------

(def restore-epoch
  {:name "restore-epoch"
   :description (str "Time-travel undo: atomically rewind a frame's whole frame-state — BOTH the app-db and "
                     "runtime-db partitions — to a retained epoch's :frame-state-after, or with `to \"before\"` to "
                     "its :frame-state-before, which undoes that epoch (a replace-app-db injection included). Find "
                     "ids with trace-window, watch-epochs or snapshot's :epochs slice; `epoch-id` is EDN, so the "
                     "integer id 7 is passed as \"7\". Returns {:ok? true :restored? true :epoch-id 7 "
                     ":cascade-summary {... :restore? true} :unreplayable-effects [...]}, where "
                     ":unreplayable-effects lists the fx the original cascade fired that a restore cannot undo "
                     "(HTTP requests, navigation, persisted writes). A refused restore (an id not in the ring, a "
                     "drain in flight) returns :restored? false with the framework's :reason, e.g. "
                     ":rf.epoch/restore-unknown-epoch, and the state unchanged. GATED: without --allow-writes "
                     "(default off) it returns :reason :rf.error/writes-disabled and touches nothing. Example: "
                     "{:epoch-id \"7\"} -> {:ok? true :restored? true :epoch-id 7 :cascade-summary {:event-id "
                     ":cart/add :restore? true ...} :unreplayable-effects [{:fx-id :http}]}.")
   :typicalTokens 150
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:epoch-id {:type "string"
                                         :description "Target epoch-id as EDN, e.g. \"7\" (integer id) or \":my/epoch\". Required."}
                              :frame    {:type "string"
                                         :description "Operating frame (e.g. \":stories\"). Defaults to the operating frame."}
                              :to       {:type "string"
                                         :description "Which of the record's snapshots to install: \"after\" (default — its :frame-state-after) or \"before\" (its :frame-state-before)."
                                         :enum ["after" "before"]}
                              :build    {:type "string"}}
                 :required ["epoch-id"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; replay-epoch
;; ---------------------------------------------------------------------------

(def replay-epoch
  {:name "replay-epoch"
   :description (str "Strict replay of a retained epoch in ONE call: re-drive its recorded event through the app's "
                     "own handlers with its raw trigger event, its recorded :rf.cofx under the strict mint policy "
                     "and both recorded override maps, all resolved in-process from the ring — only the id crosses "
                     "the wire. It runs against the frame's CURRENT state and code and does NOT restore first "
                     "(restore-epoch, then replay, to rewind): external effects fire AGAIN, and the replay records "
                     "a NEW epoch tagged :origin :pair. A fact the current code needs that the record lacks fails "
                     ":rf.error/missing-required-cofx rather than being minted. Returns dispatch's consequence "
                     "shape for the new epoch, plus :replayed? true and :source-epoch-id. Refused before anything "
                     "dispatches: an unknown or aged-out id (:rf.epoch/replay-unknown-epoch), a drain in flight "
                     "(:rf.epoch/replay-during-drain), a halted, synthetic or incomplete record "
                     "(:rf.epoch/replay-non-replayable-record), a recorded fn-valued override "
                     "(:rf.epoch/replay-unreplayable-fx-override). Not --allow-writes-gated. `epoch-id` is EDN, so "
                     "pass \"7\". Example: {:epoch-id \"7\"} -> {:ok? true :replayed? true :source-epoch-id 7 "
                     ":epoch-id 12 :db-changed? true :effects-fired [:http] :cascade-summary {...}}.")
   :typicalTokens 200
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:epoch-id {:type "string"
                                         :description "The retained epoch to replay, as EDN — e.g. \"7\" (integer id) or \":my/epoch\". Required."}
                              :frame    {:type "string"
                                         :description "Operating frame (e.g. \":stories\"). Defaults to the operating frame. The record is read from, and the event dispatched into, this SAME frame."}
                              :build    {:type "string"}}
                 :required ["epoch-id"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; replace-app-db
;; ---------------------------------------------------------------------------

(def replace-app-db
  {:name "replace-app-db"
   :description (str "State injection: replace a frame's app-db with an arbitrary EDN value, such as a captured "
                     "bug-repro state. `db` is parsed as EDN data, never source. It bypasses the dispatch loop and "
                     "fires no fx, recording a synthetic :rf.epoch/db-replaced epoch, so restore-epoch on that "
                     "epoch with `to \"before\"` undoes the injection. Returns {:ok? true :frame <id> :epoch-id <id> "
                     ":cascade-summary {:event-id :rf.epoch/db-replaced :db-diff {...} :fx-fired []}}; no such "
                     "frame, a drain in flight or an app-schema mismatch returns :reason :reset-rejected with the "
                     "app-db unchanged. GATED: without --allow-writes (default off) it returns :reason "
                     ":rf.error/writes-disabled and touches nothing. Example: {:db \"{:cart {:items []} :user "
                     "nil}\"} -> {:ok? true :frame :rf/default :epoch-id 42 :cascade-summary {...}}.")
   :typicalTokens 150
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:db    {:type "string"
                                      :description "New app-db value as EDN, e.g. \"{:cart {:items []}}\". Parsed as data, not source. Required."}
                              :frame {:type "string"
                                      :description "Operating frame (e.g. \":stories\"). Defaults to the operating frame."}
                              :build {:type "string"}}
                 :required ["db"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; trace-window
;; ---------------------------------------------------------------------------

(def trace-window
  {:name "trace-window"
   :description (str "Return the :rf/epoch-records added in the last `ms` milliseconds (default 1000) for the "
                     "operating frame. Every record crosses re-frame.core/project-egress: declared-sensitive slots "
                     "read :rf/redacted (`include-sensitive true` lifts that, only under --allow-sensitive-reads), "
                     "and a record that held classified material ships labelled :rf.epoch/sensitive? true. "
                     ":db-after is diff-encoded against :db-before unless `epochs-mode \"full\"`, and the vector is "
                     "structurally deduped unless `dedup false`. An :rf.size/large-elided marker's :path addresses "
                     "the CURRENT app-db, so get-path on it returns today's value — read the past value with the "
                     "eval-cljs recipe in get-path's description. A page holds at most `limit` records (default "
                     "50), cut further to what fits `max-tokens` (:truncated-by :max-tokens); while :has-more? is "
                     "true, pass :next-cursor back as `cursor` (an aged-out cursor returns :reason "
                     ":rf.mcp/cursor-stale). A record too large for any page returns an empty page with :advisory "
                     "{:reason :record-exceeds-max-tokens :record-tokens N}: re-call with `max-tokens` 0 or at "
                     "least N. Example: {:ms 5000 :limit 20} -> {:ok? true :count 20 :epochs [...] :has-more? true "
                     ":next-cursor \"<b64>\"}.")
   :typicalTokens 4800
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:ms    {:type "integer" :description "Window size in milliseconds (default 1000). Sticky across pagination — encoded into the cursor on the first call."}
                              :frame {:type "string"
                                      :description "Frame-id (e.g. \":rf/default\"). Defaults to the operating frame; a multi-frame session with no selection returns :ambiguous-frame rather than an empty window."}
                              :limit knobs/limit-property
                              :cursor knobs/cursor-property
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire: \"diff\" (default, intra-record structural diff against :db-before) or \"full\" (complete :db-after snapshot — opt-in for time-travel restore, which needs the verbatim state)."
                                            :enum ["diff" "full"]}
                              :dedup knobs/dedup-property
                              :include-sensitive {:type "boolean"
                                                   :description "Lift the sensitive axis inside the off-box projection every egressed epoch takes through `re-frame.core/project-egress`: declared-sensitive app-db slots in `:db-before` / `:db-after` and the trace-tag allow-list ship verbatim instead of `:rf/redacted`. Default false; honoured only when the server was launched with --allow-sensitive-reads, otherwise forced false. Event and effect arguments, runtime-db and large slots stay fail-closed either way."}
                              :build {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; watch-epochs
;; ---------------------------------------------------------------------------

(def watch-epochs
  {:name "watch-epochs"
   :description (str "Pull-mode poll: the epochs matching `pred` that landed after `since-id`, the :head-id a "
                     "previous poll returned; call repeatedly to watch. `pred` keys: :event-id, :event-id-prefix, "
                     ":effects, :touches-path, :sub-ran, :render, :origin, :frame, and :timing-ms (a number "
                     "meaning >= N, or a comparison string: \">100\", \">=100\", \"<200\", \"<=50\", \"=42\"), all applied "
                     "server-side. Returns {:ok? true :matches [...] :count n :head-id <id>}. Egress, encoding and "
                     "paging match trace-window: records cross re-frame.core/project-egress (declared-sensitive "
                     "slots read :rf/redacted; `include-sensitive true` lifts that, only under "
                     "--allow-sensitive-reads), :db-after is diff-encoded unless `epochs-mode \"full\"`, and matches "
                     "dedup unless `dedup false`. An :rf.size/large-elided marker's :path addresses the CURRENT "
                     "app-db, so get-path returns today's value; get-path's description has the recipe for the "
                     "past one. A page holds at most `limit` matches (default 50), cut to `max-tokens`; while "
                     ":has-more? is true, pass :next-cursor back as `cursor`, which overrides `since-id` (aged "
                     "out: :reason :rf.mcp/cursor-stale). An empty page carrying :advisory {:reason "
                     ":record-exceeds-max-tokens :record-tokens N} names the budget one record needs. Example: "
                     "{:since-id \"42\" :pred {:effects :http}} -> {:ok? true :matches [...] :count 2 :head-id 47}.")
   :typicalTokens 4800
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:since-id {:type "string" :description "The last epoch id you've seen — the :head-id a previous poll returned (omit to start fresh). Parsed as EDN like restore-epoch's epoch-id, so the integer id 47 is passed as \"47\". Supplanted by :cursor when both are passed."}
                              :pred     {:type "object" :description "Filter map"}
                              :frame    {:type "string"
                                         :description "Frame-id (e.g. \":rf/default\"). Defaults to the operating frame; a multi-frame session with no selection returns :ambiguous-frame rather than an empty poll with :id-aged-out? true."}
                              :limit    knobs/limit-property
                              :cursor   knobs/cursor-property
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire: \"diff\" (default) or \"full\" (opt-in for time-travel restore)."
                                            :enum ["diff" "full"]}
                              :dedup    knobs/dedup-property
                              :include-sensitive {:type "boolean"
                                                   :description "Lift the sensitive axis inside the off-box projection every egressed epoch takes through `re-frame.core/project-egress`: declared-sensitive app-db slots in `:db-before` / `:db-after` and the trace-tag allow-list ship verbatim instead of `:rf/redacted`. Default false; honoured only when the server was launched with --allow-sensitive-reads, otherwise forced false. Event and effect arguments, runtime-db and large slots stay fail-closed either way."}
                              :build    {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; tail-build
;; ---------------------------------------------------------------------------

(def tail-build
  {:name "tail-build"
   :description (str "Wait for a hot reload to land by polling a probe form until its value differs from a "
                     "PRE-EDIT baseline. Order matters: evaluate the probe with eval-cljs BEFORE the source edit "
                     "and keep its printed value, make the edit, then call with the same `probe` and that value as "
                     "`baseline` (pr-str rendering). `baseline` is required with `probe` (:reason "
                     ":missing-baseline), because only a pre-edit value recognises a reload that landed before the "
                     "first sample. Pick a probe whose value your edit changes, such as a handler-meta :line. "
                     "Returns {:ok? true :soft? false :probe-values {:baseline ... :initial ... :final ...}}, or "
                     ":reason :timed-out after `wait-ms` (default 5000); with no probe it is a 300ms soft delay, "
                     "{:ok? true :soft? true}. The probe is evaluated as CLJS, so a server launched with --no-eval "
                     "refuses it with :rf.error/eval-cljs-disabled. Example: {:probe \"(my.app/build-marker)\" "
                     ":baseline \"41\"} -> {:ok? true :soft? false :probe-values {:baseline \"41\" :initial 42 :final "
                     "42}}.")
   :typicalTokens 100
   ;; `:probe` is arbitrary CLJS evaluated in the runtime
   ;; (repeatedly), so the tool is NOT read-only: the read-only set is the
   ;; one hosts auto-approve.
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:probe    {:type "string" :description "CLJS form whose value distinguishes old from new code; evaluate it BEFORE the edit to capture :baseline"}
                              :baseline {:type "string" :description "The probe's PRE-EDIT printed value (captured via eval-cljs before the edit; pr-str rendering). Required with :probe — success is a sample differing from this."}
                              :wait-ms  {:type "integer" :description "Max wait in ms (default 5000)"}
                              :build    {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; snapshot
;; ---------------------------------------------------------------------------

(def snapshot
  {:name "snapshot"
   :description (str "Coarse per-frame state read in one round-trip: a map keyed by frame-id carrying the "
                     "requested slices — :app-db, :sub-cache, :machines, :epochs, :traces (`include` narrows "
                     "them). By default every rich slice is an {:rf.mcp/summary {:type :keys :count :bytes}} "
                     "marker, so a discovery read fits the wire cap; expand with `mode \"full\"`, per-slice `modes`, "
                     "or for :app-db a `path` (an EDN key vector, returning that subtree). The default scope is "
                     "APP frames: reserved :rf/* tool frames are left out with a :note naming them — pass `frames "
                     "\"all\"`, or name one, to include it. PRIVACY: :app-db and :sub-cache cross "
                     "re-frame.core/project-egress under the :rf.egress/off-box-tool profile, so "
                     "declared-sensitive paths read :rf/redacted and large slots become :rf.size/large-elided "
                     "markers. `elision false` lifts only the size axis and is honoured on every launch; "
                     "`include-sensitive true` lifts the sensitive axis, honoured only when the server was "
                     "launched with --allow-sensitive-reads. The :machines slice is runtime-db state, classified "
                     "by each `reg-machine` definition, and FAILS CLOSED to :rf/redacted by default; "
                     "`include-sensitive true` under the same gate ships the live snapshots through the "
                     "projection. :epochs records and :traces events cross the epoch projector: ids, flags, counts "
                     "and timings ship, while event and effect arguments read :rf/redacted. :db-after is "
                     "diff-encoded unless `epochs-mode \"full\"`, and :epochs dedup unless `dedup false`. Example: "
                     "{:include [\"app-db\"] :path \"[:cart :items]\"} -> {:ok? true :mode :path-sliced :path [:cart "
                     ":items] :snapshot {:rf/default {:app-db [{:sku \"x\" :qty 2}]}}}.")
   :typicalTokens 3000
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:frames  {:description (str "Frames to snapshot. DEFAULT (omit the arg) = APP frames only — "
                                                          "reserved :rf/* TOOL frames (e.g. :rf/xray) are excluded so the "
                                                          "first read doesn't overflow on tool-frame state (:rf/default is "
                                                          "an app frame, kept). Pass \"all\" to include tool frames, or an "
                                                          "array of frame-id strings like [\":rf/default\", \":stories\"] "
                                                          "(name a tool frame to include it, e.g. [\":rf/xray\"]).")
                                        :oneOf [{:type "string"}
                                                {:type "array" :items {:type "string"}}]}
                              :include {:type "array"
                                        :description "Slices to include. Defaults to all five. Recognised: app-db, sub-cache, machines, epochs, traces."
                                        :items {:type "string"
                                                :enum ["app-db" "sub-cache" "machines" "epochs" "traces"]}}
                              :path    {:description (str "Path into the :app-db slice. EDN-encoded vector of keys "
                                                          "(e.g. \"[:cart :items 0]\") or a JSON array of segment "
                                                          "strings. When supplied, the :app-db slice in the result "
                                                          "is the subtree at the path. Out-of-range paths surface "
                                                          "as `:path-not-found` per-frame with deepest-valid-prefix "
                                                          "attached. Path-slicing supersedes the slice-level mode "
                                                          "for :app-db. When absent, the :app-db slice respects "
                                                          "the resolved mode (default :summary).")
                                        :oneOf [{:type "string"}
                                                {:type "array" :items {:type "string"}}]}
                              :mode    {:type "string"
                                        :description (str "Global lazy-summary mode. "
                                                          "\"summary\" (default) replaces every rich slice "
                                                          "(:app-db when no `path`, :sub-cache, :machines, :epochs, :traces) "
                                                          "with a `{:rf.mcp/summary ...}` marker — top-level keys, "
                                                          "count, and approximate bytes. \"full\" expands every "
                                                          "slice to its raw payload. "
                                                          "Per-slice override via `modes` takes precedence.")
                                        :enum ["summary" "full"]}
                              :modes   {:type "object"
                                        :description (str "Per-slice mode override — a map "
                                                          "{slice-name: \"summary\"|\"full\"}. Recognised slices: "
                                                          "app-db, sub-cache, machines, epochs, traces. Slices not "
                                                          "listed fall back to the global `mode` arg (default \"summary\"). "
                                                          "Example: `{\"app-db\": \"full\", \"epochs\": \"summary\"}` — "
                                                          "expand the live state, summarise the history.")
                                        :additionalProperties {:type "string"
                                                               :enum ["summary" "full"]}}
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire in the :epochs slice: \"diff\" (default, intra-record structural diff against :db-before) or \"full\" (complete :db-after snapshot — opt-in for time-travel restore, which needs the verbatim state)."
                                            :enum ["diff" "full"]}
                              :dedup    knobs/dedup-property
                              :elision  knobs/elision-property
                              :include-sensitive {:type "boolean"
                                                   :description (str "See the raw value at declared-sensitive app-db paths, in the "
                                                                     ":app-db / :sub-cache slices and inside the :epochs / "
                                                                     ":traces projection (the walker's "
                                                                     "`:rf.egress/include-sensitive?` opt; event and effect "
                                                                     "arguments stay redacted). Default false.")}
                              :build   {:type "string" :description "shadow-cljs build id (default: app)"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; get-path
;; ---------------------------------------------------------------------------

(def get-path
  {:name "get-path"
   :description (str "Read the value at `path` (an EDN key vector, e.g. \"[:cart :items 0]\") in a frame's app-db; "
                     "only that subtree crosses the wire. A path rooted at a :rf.runtime/* key, as a snapshot "
                     ":machines marker's :path is, reads the frame's runtime-db instead, which reads :rf/redacted "
                     "unless `include-sensitive true`. Returns {:ok? true :exists? true :path [...] :value "
                     "<v>}, or {:ok? false :reason :path-not-found :deepest-valid-prefix [...]} so you can re-aim. "
                     "`paths` reads several in one call, returning :results keyed by path; it excludes `path`. "
                     "PRIVACY: the value crosses re-frame.core/project-egress under the :rf.egress/off-box-tool "
                     "profile, so declared-sensitive paths read :rf/redacted and a value at or below a :large path "
                     "becomes {:rf.size/large-elided {:path [...] :handle ...}}. To fetch it raw, re-call on the "
                     "marker's :path — never the `:handle` vector, which get-path does not decode — with `elision "
                     "false`, the size override, honoured on every launch. Sensitive paths still redact unless "
                     "`include-sensitive true`, honoured only under --allow-sensitive-reads. A marker's :path "
                     "addresses the CURRENT app-db, so following one out of a past epoch (trace-window, "
                     "watch-epochs, snapshot :epochs, dispatch :trace / :settle) returns today's value. Read the "
                     "past value with eval-cljs: `(if-let [r (re-frame2-pair.runtime/epoch-by-id 7 :my/app)] "
                     "(get-in r [:db-after :doc :body]) {:epoch-unavailable 7})`, using :db-before when the marker "
                     "came from that side. Example: {:path \"[:cart :items 99]\"} -> {:ok? false :reason "
                     ":path-not-found :path [:cart :items 99] :deepest-valid-prefix [:cart :items]}.")
   :typicalTokens 500
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:path  {:description (str "Path into app-db. EDN-encoded vector of keys "
                                                        "(e.g. \"[:cart :items 0 :sku]\") or a JSON array "
                                                        "of segment strings (each parsed as EDN — bare "
                                                        "strings stay as map-key strings). Mutually "
                                                        "exclusive with `paths`.")
                                      :oneOf [{:type "string"}
                                              {:type "array" :items {:type "string"}}]}
                              :paths {:description (str "Batch read: a vector of paths, each itself "
                                                        "a path vector — reads N subtrees in one round-trip. "
                                                        "EDN-encoded (e.g. \"[[:cart :total] [:user :id]]\") or a "
                                                        "JSON array whose entries are EDN path strings / segment "
                                                        "arrays. Returns `:results` keyed by each path. Mutually "
                                                        "exclusive with `path`.")
                                      :oneOf [{:type "string"}
                                              {:type "array" :items {:oneOf [{:type "string"}
                                                                             {:type "array" :items {:type "string"}}]}}]}
                              :frame   {:type "string"
                                        :description "Frame-id (e.g. \":rf/default\"). Defaults to the operating frame."}
                              :elision knobs/elision-property
                              :include-sensitive {:type "boolean"
                                                   :description (str "Opt in to seeing the raw value at declared-sensitive "
                                                                     "paths (the walker's `:rf.egress/include-sensitive?` opt). "
                                                                     "Default false ⇒ sensitive paths return the `:rf/redacted` "
                                                                     "sentinel.")}
                              :build   {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; read-sub
;; ---------------------------------------------------------------------------

(def read-sub
  {:name "read-sub"
   :description (str "Read a subscription's current value, validated. `sub` is an EDN query vector (\"[:user/by-id "
                     "42]\"); its id is checked against the live :sub registrar, and an unknown id returns :reason "
                     ":unknown-id with :nearest matches and subscribes nothing, where a raw eval-cljs deref "
                     "returns a silent nil. It subscribes on demand, so it reads subs no view has mounted. A "
                     "throwing sub returns :reason :sub-error; non-vector input :not-a-sub-vector, unreadable "
                     "input :invalid-sub-edn. Omit `frame` for the operating frame; a multi-frame session with "
                     "none selected returns :ambiguous-frame. Returns {:ok? true :query-v [...] :frame <id> :value "
                     "<v>}. PRIVACY: the value crosses re-frame.core/project-egress, so declared-sensitive values "
                     "read :rf/redacted and large ones elide to :rf.size/large-elided; `elision false` (honoured "
                     "on every launch) shows a large value raw, `include-sensitive true` (only under "
                     "--allow-sensitive-reads) a sensitive one. Example: {:sub \"[:current-userr]\"} -> {:ok? false "
                     ":reason :unknown-id :kind :sub :id :current-userr :nearest [:current-user] :subscribed? "
                     "false :hint \"unknown :sub :current-userr; did you mean :current-user?\"}.")
   :typicalTokens 400
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:sub   {:type "string"
                                      :description (str "The subscription query vector as EDN, e.g. \"[:current-user]\" "
                                                        "or \"[:user/by-id 42]\". MUST be a vector — non-vector EDN and "
                                                        "host-form source are rejected. Parsed once, echoed under "
                                                        ":query-v, and the sub-id validated against the live :sub "
                                                        "registrar (unknown -> :reason :unknown-id + :nearest, NOT "
                                                        "subscribed).")}
                              :frame {:type "string"
                                      :description "Frame-id (e.g. \":rf/default\"). Defaults to the operating frame; a multi-frame session with no selection returns :ambiguous-frame."}
                              :elision knobs/elision-property
                              :include-sensitive {:type "boolean"
                                                   :description (str "Opt in to seeing the raw value when it derives from a "
                                                                     "declared-sensitive app-db slot (the walker's "
                                                                     "`:rf.egress/include-sensitive?` opt). Default false ⇒ "
                                                                     "sensitive values return the `:rf/redacted` sentinel. "
                                                                     "Honoured only when the server was launched with "
                                                                     "--allow-sensitive-reads.")}
                              :build {:type "string"}}
                 :required ["sub"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; read-dom
;; ---------------------------------------------------------------------------

(def read-dom
  {:name "read-dom"
   :description (str "Query the RENDERED DOM by CSS `selector` and return what is on screen: {:ok? true :count N "
                     ":truncated? bool :nodes [{:tag :text :attrs}]}. `sub-selector` narrows each match to its "
                     "inner parts; `attrs` picks the attributes (default: a structural set plus every data-* and "
                     "aria-* attribute). Bounded in the browser: at most `limit` nodes (default 50; :count is the "
                     "full tally) and `max-text` characters per node (default 2000; longer text becomes an "
                     "{:rf.size/large-elided {:type :dom-text ...}} marker). Read-only. PRIVACY: FAIL-OPEN — "
                     "rendered text and attributes are not matched against app-db secrets, so a secret a view "
                     "copied into the DOM ships raw. Classifying an app-db path never changes what a view renders, "
                     "so the app must omit or mask content it does not want rendered. "
                     "An ambiguous frame (several app frames, none pinned) fails closed with :reason "
                     ":ambiguous-frame. Example: {:selector \"#app .counter\"} -> {:ok? true :count 1 :truncated? "
                     "false :nodes [{:tag \"div\" :text \"Count: 3\" :attrs {\"class\" \"counter\" \"data-count\" \"3\"}}]}.")
   :typicalTokens 600
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:selector     {:type "string"
                                             :description "CSS selector for the matched nodes, e.g. \"#app .counter\". Required. A malformed selector returns :reason :rf.error/read-dom-bad-selector."}
                              :sub-selector {:type "string"
                                             :description (str "Optional CSS selector run RELATIVE to each matched node "
                                                               "(node.querySelectorAll). Narrows a coarse match (a card) "
                                                               "to its inner parts (the card's .title). When supplied the "
                                                               "result's :nodes are the sub-matches.")}
                              :limit        {:type "integer"
                                             :description "Max matched nodes to return (default 50). Excess nodes drop and :truncated? flips true; :count still reports the full tally."}
                              :max-text     {:type "integer"
                                             :description (str "Per-node textContent character cap (default 2000). Text longer than "
                                                               "this is replaced with a {:rf.size/large-elided {:type :dom-text "
                                                               ":chars N :preview \"...\"}} marker — same shape get-path / snapshot "
                                                               "emit for over-threshold app-db slots.")}
                              :attrs        {:type "array"
                                             :items {:type "string"}
                                             :description (str "Attribute names to include per node, e.g. [\"id\" \"class\" \"data-state\"]. "
                                                               "When omitted, a curated structural set (id / class / role / type / name / "
                                                               "value / href / title / placeholder / disabled / checked / selected / hidden) "
                                                               "rides PLUS a data-* / aria-* prefix sweep. With an explicit list the caller is "
                                                               "in control — no prefix sweep.")}
                              :frame        {:type "string"
                                             :description (str "Frame-id (e.g. \":rf/default\") naming the source frame for the "
                                                               "off-box gate. EP-0025 fail-open: rendered nodes are "
                                                               "NOT value-redacted against the frame's app-db secrets — a re-keyed "
                                                               "DOM secret ships raw. Defaults to the operating frame. Under the "
                                                               "off-box gate an ambiguous frame (multi-app, none pinned) fails "
                                                               "closed with :reason :ambiguous-frame.")}
                              :build        {:type "string"}}
                 :required ["selector"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; read-ui
;; ---------------------------------------------------------------------------

(def read-ui
  {:name "read-ui"
   :description (str "Return the rendered subtree AND the re-frame2 view that produced it, in one round-trip and "
                     "with no test ids: it reads the data-rf-view and data-rf2-source-coord attributes the adapter "
                     "stamps on each view root (Spec 006 §View tagging contract). Pass exactly one entry point — "
                     "`view-id`, `point` {x y} (the view under it) or `selector` — with that precedence. Returns "
                     "{:ok? true :via <entry> :entity {:view-id :source-coord {:ns :handler-id :line :col :file} "
                     ":render-key :subs-read [...]} :content {:tag :text :attrs}}, where :subs-read is the frame's "
                     "live sub query-vectors. A node with no tagged view root returns :entity {:view-id nil "
                     ":reason :no-tagged-view-root}; no entry point returns :reason :no-target-arg, nothing "
                     "matched :no-element. Read-only. PRIVACY: FAIL-OPEN like read-dom — :content is not matched "
                     "against app-db secrets, so a secret a view copied into the DOM ships raw, and classifying "
                     "its app-db path does not change the render: the app must omit or mask content it does not "
                     "want rendered. `max-text` (default 2000) elides long text, and an "
                     "ambiguous frame fails closed with :reason :ambiguous-frame. Example: {:view-id "
                     "\":my.app/counter\"} -> {:ok? true :via :view-id :entity {:view-id :my.app/counter "
                     ":source-coord {:ns \"my.app\" :handler-id \"counter\" :line 42 :col 3 :file \"/abs/my/app.cljs\"} "
                     ":render-key 8123 :subs-read [[:count]]} :content {:tag \"div\" :text \"Count: 3\" :attrs "
                     "{\"class\" \"counter\"}}}.")
   :typicalTokens 700
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:view-id  {:type "string"
                                         :description (str "Registry view id, e.g. \":my.app/header\". Resolves "
                                                           "[data-rf-view='<id>'] (Spec 006 §View tagging contract) — the "
                                                           "view<->DOM map the Xray hover-highlight rides. Wins over :point "
                                                           "and :selector when more than one is supplied.")}
                              :point    {:type "object"
                                         :description (str "Viewport point {x N y N} — elementFromPoint, then walk up to "
                                                           "the nearest data-rf-view ancestor (the producing view). 'What "
                                                           "view is under the cursor at (x,y)?' Used when :view-id is absent.")
                                         :properties {:x {:type "number"}
                                                      :y {:type "number"}}}
                              :selector {:type "string"
                                         :description (str "CSS selector — querySelector, then walk up to the producing "
                                                           "view root. A malformed selector returns :reason "
                                                           ":rf.error/ui-read-bad-selector. Used when :view-id and :point "
                                                           "are absent.")}
                              :max-text {:type "integer"
                                         :description (str "Per-node textContent character cap (default 2000). Text longer "
                                                           "than this collapses to a {:rf.size/large-elided {:type :dom-text "
                                                           ":chars N :preview \"...\"}} marker before the elision walker runs "
                                                           "— same shape get-path / snapshot emit.")}
                              :frame    {:type "string"
                                         :description (str "Operating frame (e.g. \":stories\") — scopes the :subs-read "
                                                           "slice and the classification the elision walker applies. Defaults to the operating frame.")}
                              :build    {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; The three re-frame.fresco.tool reads — the adapter-neutral evidence a
;; pairing agent reads from a running app. Each ships one self-describing form
;; that RESOLVES the door at runtime rather than referencing its vars — a
;; reference to a namespace the build never loaded would be rejected by shadow's
;; analyzer before the form runs, hiding :evidence-tier-unavailable;
;; every read answers inside the evidence envelope (:schema / :producer /
;; :read / :complete? / :loss) and egresses only bounded serializable data (no
;; cell / React handle, and no read VALUE at all). Absence is honest:
;; :evidence-tier-unavailable (the door is not loaded — nothing in the app, on
;; any adapter, pulled it in), :evidence-tier-inactive (a production
;; build; the door is dev-only).
;;
;; THREE reads, and no static per-view ones. Fresco mints no boundary identity
;; and keeps no view registry, so static questions about a view named by its
;; declared id (its manifest, its dependencies, its event sites) have no
;; counterpart here and are not shipped as tools that would answer them with a
;; fabricated emptiness. A dev build does
;; name the declared views that rendered each edge set (:views, with source
;; coordinates), so a row reads as app.views/todo-row beside its key.
;; ---------------------------------------------------------------------------

(def fresco-door-input
  {:type "object"
   :properties {:build {:type "string"}}
   :additionalProperties false})

(def read-mounted-boundaries
  {:name "read-mounted-boundaries"
   :description (str "List every Fresco boundary mounted right now (re-frame.fresco.tool/read-mounted-boundaries). "
                     "No args. Returns the evidence envelope {:ok? true :schema :re-frame.fresco.evidence/v3 "
                     ":complete? ... :loss ... :boundaries [...]} — validate :schema first — where each boundary "
                     "carries its :key, the :views that rendered it ({:view \"<ns>/<sym>\" :source {...}}, or "
                     ":unknown for an unnamed body), :instances, :frame, :read-orders and its :reads ({:sub-id "
                     ":query :frame-id :epoch}), never the values read. A boundary is keyed by its READ SET, the "
                     "only identity the runtime keeps, so :instances counts the boundaries sharing one. The census "
                     "is about subscription, not visibility: an empty roster means no boundary holds a live read "
                     "edge, and a row does not prove the boundary is on screen. Boundaries under a reserved :rf/* "
                     "tool frame are left out and reported under :excluded. Returns :reason "
                     ":evidence-tier-unavailable when the door is not loaded, :evidence-tier-inactive in a "
                     "production build. Example: {} -> {:ok? true :schema :re-frame.fresco.evidence/v3 :complete? "
                     "true :boundaries [{:boundary {:parent nil :key [[:app/main :todo [:todo 7]]]} :views [{:view "
                     "\"app.views/todo-row\" :source {...}}] :instances 3 :frame :app/main :reads [{:sub-id :todo "
                     ":query [:todo 7] :frame-id :app/main :epoch 4}]}]}.")
   :typicalTokens 600
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema fresco-door-input})

(def read-read-attribution
  {:name "read-read-attribution"
   :description (str "For each subscription Fresco boundaries hold, the boundaries reading it — the exact reverse "
                     "edge (re-frame.fresco.tool/read-read-attribution). No args. Returns the evidence envelope "
                     "with :edges [{:sub-id :query :frame-id :epoch :fan-out N :readers [{:parent :key :views}]}]; "
                     "each reader's :key is the one read-mounted-boundaries reports, so the two join directly. "
                     "Exact without qualification. Start here when you can name a subscription and want the "
                     "boundaries holding it, then take a :key to explain-render. A subscription nothing holds is "
                     "absent, not listed with zero readers. Tool-frame subscriptions are left out under :excluded; "
                     ":reason :evidence-tier-unavailable or :evidence-tier-inactive when the door is not loaded or "
                     "the build is production. Example: {} -> {:ok? true :schema :re-frame.fresco.evidence/v3 "
                     ":edges [{:sub-id :todo :query [:todo 7] :frame-id :app/main :epoch 4 :fan-out 3 :readers "
                     "[{:parent nil :key [[:app/main :todo [:todo 7]]] :views [...]}]}]}.")
   :typicalTokens 500
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema fresco-door-input})

(def explain-render
  {:name "explain-render"
   :description (str "Per mounted Fresco boundary, which of its reads moved most recently and which retained event "
                     "runs COULD have driven it (re-frame.fresco.tool/explain-render, folding Spec 009's retained "
                     "event window at read time). No args. Two halves, never blended. PROVEN: :latest-reads (the "
                     "reads standing at the boundary's :peak-epoch) and :snapshot (the exact number React "
                     "compares), each :unknown where a row folds boundaries that do not share one read set and one "
                     "number. LEADS: :candidates are retained runs that recomputed a subscription the boundary "
                     "reads; no run can be joined to a re-render, so never present a candidate as the cause. :loss "
                     "{:reason :uncorrelated} means the search ran and :candidates [] is an honest result; "
                     "{:reason :cap} means the boundary's frames had an empty window (retention is "
                     ":rf.trace/events-retained), so :candidates is :unknown. Whether the boundary then rendered "
                     "is React's to know. Tool-frame boundaries are left out under :excluded; :reason "
                     ":evidence-tier-unavailable or :evidence-tier-inactive when the door is not loaded or the "
                     "build is production. Example: {} -> {:ok? true :schema :re-frame.fresco.evidence/v3 "
                     ":explanations [{:boundary {...} :views [...] :frame :app/main :window {:frames [:app/main] "
                     ":retained-runs 12} :snapshot 9 :peak-epoch 5 :latest-reads [{:sub-id :todo :query [:todo 7] "
                     ":frame-id :app/main}] :loss {:reason :uncorrelated :dropped :unknown} :candidates "
                     "[{:dispatch-id 41 :event-id :todo/toggle :frame-id :app/main :sub-id :todo}]}]}.")
   :typicalTokens 600
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema fresco-door-input})

;; ---------------------------------------------------------------------------
;; record
;; ---------------------------------------------------------------------------

(def record
  {:name "record"
   :description (str "Start a background, read-only recorder over one or more `signals` and return at once with a "
                     ":recording-id; let the human interact, then read the change-log with read-recording. It "
                     "catches intermittent and human-in-the-loop bugs: each signal is sampled once per animation "
                     "frame and only CHANGES are logged, each with :t and a frame counter. Signals: {:app-db "
                     "[path]}, {:sub [query-v]}, {:dom \"sel\"} (optionally :attr \"name\"), {:focus true} "
                     "(document.activeElement). `stop` ends it at the first of :ms (default 30000), :changes "
                     "(entry count) or :pred, a data predicate over the sample map {<signal-index> <value>}: "
                     "{:signal 0 :equals v}, {:signal 0 :changed true}, {:signal 0 :path [...] :equals v} or "
                     "{:signal 0 :contains \"s\"}. PRIVACY: :app-db / :sub samples cross "
                     "re-frame.core/project-egress at sample time, so sensitive leaves redact and large slots "
                     "elide; `elision false` (honoured on every launch) records large values raw, "
                     "`include-sensitive true` (only under --allow-sensitive-reads) sensitive ones. :dom / :focus "
                     "samples are FAIL-OPEN: a secret a view copied into the DOM ships raw, and classifying its "
                     "app-db path does not change the render, so the app must omit or mask content it does not "
                     "want rendered. An :app-db / :sub signal with an ambiguous frame fails closed with :reason "
                     ":ambiguous-frame. Example: {:signals \"[{:focus true} {:dom \\\"#count\\\"}]\" :stop {:ms 15000}} "
                     "-> {:ok? true :recording-id \"rec-<uuid>\" :signals [{:focus true} {:dom \"#count\"}] :frame "
                     ":rf/default :stop {:ms 15000}}.")
   :typicalTokens 200
   :annotations record-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:signals {:description (str "Signal-set to observe — a vector of signal maps (or one bare "
                                                          "signal map). EDN-encoded string or JSON array. Each names one "
                                                          "observable: {:app-db [path]}, {:sub [query-v]}, {:dom \"sel\"} "
                                                          "(optionally :attr \"name\"), or {:focus true}. Required, non-empty.")
                                        :oneOf [{:type "string"}
                                                {:type "array" :items {:type "object"}}
                                                {:type "object"}]}
                              :stop    {:description (str "Stop condition (first key to trip wins; defaults to {:ms 30000} "
                                                          "when omitted). :ms (wall-clock ms), :changes (total change-entry "
                                                          "count), :pred (a DATA predicate map over the positional sample "
                                                          "map — see the description). JSON object or EDN string.")
                                        :oneOf [{:type "object"}
                                                {:type "string"}]}
                              :max-entries {:type "integer"
                                            :description "Drop-oldest ring cap on the change-log (default 2000). Bounds memory on a forgotten recording."}
                              :elision {:type "boolean"
                                        :description (str "Apply the size/sensitive elision walker "
                                                          "(`re-frame.core/project-egress`) to the "
                                                          ":app-db / :sub sample VALUES this recorder ships back through "
                                                          "read-recording — declared-`:large?` slots collapse to "
                                                          "`{:rf.size/large-elided ...}` markers, declared-`:sensitive?` "
                                                          "leaves redact to `:rf/redacted`, the SAME walker snapshot / "
                                                          "get-path / watch-until use. The values are walked at SAMPLE "
                                                          "time (before they enter the change-log read back by "
                                                          "read-recording). Default true. Pass false (the size "
                                                          "override, honoured on every launch) to record large values "
                                                          "raw — declared-`:sensitive?` leaves STILL redact unless "
                                                          "`:include-sensitive` is also honoured. (:dom / :focus "
                                                          "signals are content reads, not app-db-rooted, and ride unwalked.)")}
                              :include-sensitive {:type "boolean"
                                                  :description (str "Pass declared-`:sensitive?` :app-db / :sub sample leaves through "
                                                                    "verbatim instead of redacting to `:rf/redacted`. Default false. "
                                                                    "Honoured ONLY when the server was launched with "
                                                                    "--allow-sensitive-reads; otherwise forced false. "
                                                                    "Orthogonal to `:elision` (the size/large axis) — a gate-ON caller "
                                                                    "wanting fully-raw samples passes both `:elision false` and "
                                                                    "`:include-sensitive true`.")}
                              :frame   {:type "string"
                                        :description "Operating frame for :app-db / :sub signals (e.g. \":rf/default\"). Defaults to the operating frame; an :app-db / :sub signal in a multi-frame session with no resolvable frame returns :reason :ambiguous-frame."}
                              :build   {:type "string"}}
                 :required ["signals"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; read-recording
;; ---------------------------------------------------------------------------

(def read-recording
  {:name "read-recording"
   :description (str "Read back a recording's change-log, the companion to record. Returns {:ok? true "
                     ":recording-id <id> :status :recording|:stopped :stopped-reason <kw|nil> :frames-sampled n "
                     ":count n :entries [{:i <signal-index> :signal {...} :value v :t <ms> :frame <n>} ...]}: each "
                     "entry is one CHANGE, and two signals that changed on the same paint share a :frame. `drain "
                     "true` consumes the buffered entries so the next read sees only later ones, and the recording "
                     "keeps running; `stop true` reads and closes. An unknown id returns :reason "
                     ":no-such-recording. Example: {:recording-id \"rec-abc\" :stop true} -> {:ok? true "
                     ":recording-id \"rec-abc\" :status :stopped :stopped-reason :ms :count 3 :entries [...]}.")
   :typicalTokens 800
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:recording-id {:type "string"
                                             :description "The :recording-id returned by record. Required."}
                              :drain {:type "boolean"
                                      :description "When true, return the buffered change-entries AND clear them from the recording so the next read sees only subsequent changes (the poll→consume→repeat live-watch idiom). Default false. The recording keeps running either way."}
                              :stop  {:type "boolean"
                                      :description "When true, tear the recording down after reading (read-and-close in one round-trip). Default false."}
                              :build {:type "string"}}
                 :required ["recording-id"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; watch-until
;; ---------------------------------------------------------------------------

(def watch-until
  {:name "watch-until"
   :description (str "Block until a data predicate over `signals` holds, or `timeout-ms` (default 30000) elapses — "
                     "the blocking counterpart to record; the server polls a runtime read about every 100ms. "
                     "Signals use record's vocabulary, and `pred` is a data predicate over the sample map "
                     "{<signal-index> <value>}: {:signal 0 :equals v}, {:signal 0 :changed true}, {:signal 0 :path "
                     "[...] :equals v}, {:signal 0 :contains \"s\"} or {:signal 0} (any non-nil). Returns {:ok? true "
                     ":held? true :elapsed-ms n :sample {...} :t <ms>}, or on timeout {:ok? false :reason "
                     ":watch-timeout :timed-out? true :last-sample {...}}, showing how close it got. Read-only. "
                     "PRIVACY: :app-db / :sub samples cross re-frame.core/project-egress BEFORE the predicate "
                     "tests them, so a value at a classified path compares as its marker and cannot trip the watch "
                     "— aim at an unclassified value or lift the axis: `elision false` (honoured on every launch) "
                     "for large values, `include-sensitive true` (only under --allow-sensitive-reads) for "
                     "sensitive ones. :dom / :focus samples are FAIL-OPEN, as in record. Example: {:signals "
                     "\"[{:app-db [:upload :status]}]\" :pred {:signal 0 :equals :done}} -> {:ok? true :held? true "
                     ":elapsed-ms 4200 :sample {0 :done} :t 1712...}.")
   :typicalTokens 200
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:signals {:description (str "Signal-set to watch — same vocabulary as record. A vector of "
                                                          "signal maps (or one bare signal map). EDN string or JSON array. "
                                                          "Required, non-empty.")
                                        :oneOf [{:type "string"}
                                                {:type "array" :items {:type "object"}}
                                                {:type "object"}]}
                              :pred    {:description (str "DATA predicate map over the positional sample map "
                                                          "{<signal-index> <value>} — see the description for the shapes. "
                                                          "Required: without one the watch can only time out. JSON object "
                                                          "or EDN string.")
                                        :oneOf [{:type "object"}
                                                {:type "string"}]}
                              :timeout-ms {:type "integer"
                                           :description "Max ms to wait for the predicate to hold (default 30000). On expiry returns :reason :watch-timeout with the final :last-sample."}
                              :elision {:type "boolean"
                                        :description (str "Apply the size/sensitive elision walker "
                                                          "(`re-frame.core/project-egress`) to the :app-db / "
                                                          ":sub VALUES returned in the `:sample` (on hold) and "
                                                          "`:last-sample` (on timeout) slots — declared-`:large?` slots "
                                                          "collapse to `{:rf.size/large-elided ...}` markers, "
                                                          "declared-`:sensitive?` leaves redact to `:rf/redacted`, the SAME "
                                                          "walker snapshot / get-path / record use. Default true. Pass false "
                                                          "(the size override, honoured on every launch) to return large "
                                                          "values raw — declared-`:sensitive?` leaves STILL redact unless "
                                                          "`:include-sensitive` is also honoured. The predicate "
                                                          "tests the projected samples, so this also decides whether a "
                                                          "predicate on a large value can trip.")}
                              :include-sensitive {:type "boolean"
                                                  :description (str "Pass declared-`:sensitive?` :app-db / :sub sample leaves through "
                                                                    "verbatim in the `:sample` / `:last-sample` slots instead of "
                                                                    "redacting to `:rf/redacted`. Default false. Honoured ONLY when "
                                                                    "the server was launched with --allow-sensitive-reads; otherwise "
                                                                    "forced false. Orthogonal to `:elision` (the size "
                                                                    "axis) — a gate-ON caller wanting fully-raw samples passes both "
                                                                    "`:elision false` and `:include-sensitive true`.")}
                              :frame   {:type "string"
                                        :description "Operating frame for :app-db / :sub signals (e.g. \":rf/default\"). Defaults to the operating frame."}
                              :build   {:type "string"}}
                 :required ["signals" "pred"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; list-subscriptions
;; ---------------------------------------------------------------------------

(def list-subscriptions
  {:name "list-subscriptions"
   :description (str "List the subscriptions live in a frame's sub-cache right now, read from the same source as "
                     "snapshot's :sub-cache slice. The cache is ref-counted: an entry appears when a view "
                     "subscribes and disappears when its last consumer disposes it. Returns {:ok? true :frame <id> "
                     ":count N :subs [<query-v> ...]}, sorted. With `include-values true` each entry is {:query-v "
                     ":value :ref-count :input-kind <:db|:static|:parametric> :realized-inputs [<query-v> ...]}, "
                     "each :value elided and redacted as read-sub's is. Omit `frame` for the operating frame; a "
                     "multi-frame session with none selected returns :ambiguous-frame. Example: {:include-values "
                     "true} -> {:ok? true :frame :rf/default :count 1 :subs [{:query-v [:article/page :a1] :value "
                     "{...} :ref-count 1 :input-kind :parametric :realized-inputs [[:article/by-id :a1] "
                     "[:viewer/current]]}]}.")
   :typicalTokens 400
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:frame          {:type "string"
                                               :description "Frame to read. Accepts bare names (\"rf/default\") or EDN-shaped strings (\":rf/default\"). Defaults to the operating frame; a multi-frame session with no selection -> :reason :ambiguous-frame."}
                              :include-values {:type "boolean"
                                               :description "When true, each entry carries :value (current deref), :ref-count, :input-kind (:db/:static/:parametric), and :realized-inputs (the realized input query-vectors for the concrete query-v) alongside :query-v. Default false (query-vectors only — the cheap 'what's subscribed' read). Each :value is run through the size-elision walker server-side before egress: a value over a declared-sensitive app-db slot redacts to :rf/redacted (unless `include-sensitive true` under --allow-sensitive-reads), a declared-large value elides to :rf.size/large-elided (unless `elision false`, honoured on every launch). :realized-inputs are query-vectors (not values), so they ride raw."}
                              :elision        knobs/elision-property
                              :include-sensitive {:type "boolean"
                                                   :description "Opt declared-sensitive sub `:value`s back in to raw egress (the walker's `:rf.egress/include-sensitive?` opt). Default false; honoured only when the server was launched with --allow-sensitive-reads."}
                              :build          {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; The `kind` argument handler-meta and list-handlers share.
;; ---------------------------------------------------------------------------

(def ^:private kind-enum
  "The kinds both registrar tools accept, in the order their descriptors
  list them. `handler_meta_test.cljs` pins it to the tools' own kind set."
  ["event" "sub" "fx" "cofx" "interceptor" "view" "route" "head" "error-projector"
   "resource" "mutation" "resource-scope" "app-schema" "flow" "http-interceptor"
   "classification" "machine"])

(def ^:private kind-property
  {:type        "string"
   :description (str "Registrar kind. One of " (str/join ", " kind-enum) ". NOT frame — a "
                     "reserved-but-EMPTY registrar slot; read frames via rf/frame-ids.")
   :enum        kind-enum})

;; ---------------------------------------------------------------------------
;; handler-meta
;; ---------------------------------------------------------------------------

(def handler-meta
  {:name "handler-meta"
   :description (str "Return a registered handler's registration metadata: its source coord (:ns :file :line "
                     ":column, plus an :rf.mcp/source-uri jump-to-editor link), :doc, :tags and any custom slots "
                     "its reg-* macro emitted. Use it to find where an id is defined without an eval-cljs "
                     "round-trip. `kind` takes list-handlers' kinds; `id` is EDN (\":user/login\"; an app-schema id "
                     "is its path vector, \"[:user]\"). On kind event a dev build also returns :rf.handler/source, "
                     "the registered form as a string, so you can read the body here; it reflects the CURRENT "
                     "registration, not an older epoch's, and is absent in production builds and for other kinds. "
                     "`frame` resolves the id through what that frame runs and adds its provenance "
                     "(:rf.provenance/ns, :rf.image/coordinate); it is not valid with kind machine. Returns {:ok? "
                     "true :kind k :id i ...meta...}, or :reason :not-registered. Example: {:kind \"event\" :id "
                     "\":user/login\"} -> {:ok? true :kind :event :id :user/login :ns my.app.user :line 42 :file "
                     "\"src/my/app/user.cljs\" :rf.mcp/source-uri \"file://...\" :rf.handler/source \"(reg-event "
                     ":user/login ...)\"}.")
   :typicalTokens 400
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:kind kind-property
                              :id   {:type "string"
                                     :description (str "EDN-encoded id, e.g. \":user/login\". For "
                                                       "composite-key subs, pass the vector form "
                                                       "as a string, e.g. \"[:rf/composite :x]\".")}
                              :frame {:type "string"
                                      :description (str "OPTIONAL frame id keyword, e.g. \":main\". "
                                                        "The EP-0023 forward direction: "
                                                        "resolves the (kind, id) through what THAT frame "
                                                        "runs (its image generation, or the live store "
                                                        "for a frame made without :images) rather than the "
                                                        "process-global registrar — the same (kind, id) "
                                                        "can resolve differently per frame. The result "
                                                        "carries the resolved descriptor's "
                                                        ":rf.provenance/ns + inline/image + :standard "
                                                        "facts plus an :rf.image/coordinate rollup "
                                                        "naming WHICH source won. Omit for the "
                                                        "process-global registrar (byte-identical). Not "
                                                        "valid with kind=machine.")}
                              :build {:type "string"}}
                 :required ["kind" "id"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; list-handlers
;; ---------------------------------------------------------------------------

(def list-handlers
  {:name "list-handlers"
   :description (str "Return every id registered under one `kind` — the discovery surface; drill into one with "
                     "handler-meta. Supported kinds: " (str/join ", " kind-enum) " — frame is "
                     "not a kind (it returns :invalid-kind; read frames with rf/frame-ids). machine lists the "
                     "event handlers flagged :rf/machine? true, and app-schema ids are app-db path vectors. "
                     "`frame` lists only the ids that frame runs rather than the whole registrar; it is not valid "
                     "with kind machine. Returns {:ok? true :kind k :ids [...] :count n}, the ids sorted so the "
                     "list is stable across calls. Example: {:kind \"event\"} -> {:ok? true :kind :event :ids "
                     "[:cart/add :cart/checkout :user/login ...] :count 47}.")
   :typicalTokens 800
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:kind kind-property
                              :frame {:type "string"
                                      :description (str "OPTIONAL frame id keyword, e.g. \":main\". "
                                                        "The EP-0023 forward direction: "
                                                        "enumerates only the ids THAT frame resolves "
                                                        "for the kind (from its image generation, or the "
                                                        "live store for a frame made without :images) "
                                                        "rather than the process-global registrar — the "
                                                        "SELECTED universe that frame runs, not the "
                                                        "namespace-union the flat registrar holds. Omit "
                                                        "for the process-global registrar (byte-"
                                                        "identical). Not valid with kind=machine.")}
                              :build {:type "string"}}
                 :required ["kind"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; describe-image (EP-0023 Use-Case 7 / Ref-Plan item 17)
;; ---------------------------------------------------------------------------

(def describe-image
  {:name "describe-image"
   :description (str "Describe the image generation a frame runs — which registrations it selected and where each "
                     "came from (EP-0023) — since the same (kind, id) can resolve differently per frame. Returns "
                     "{:ok? true :frame <id> :images [...] :kinds [...] :counts {<kind> N ...}}. A frame made "
                     "without :images returns :live-store? true, :images [] and :ambiguous [{:kind k :id id "
                     ":namespaces [...]} ...], one per id more than one namespace registers, left out of :counts. "
                     "`include-ns true` adds :registrations {[kind id] <coordinate>}, naming the source that won "
                     "each. Omit `frame` for the operating frame; a multi-frame session with none selected returns "
                     ":ambiguous-frame. Drill one id with handler-meta {:frame ... :kind ... :id ...}. Example: "
                     "{:frame \":main\"} -> {:ok? true :frame :main :images [:app/img] :kinds [:event :sub] :counts "
                     "{:event 12 :sub 8}}.")
   :typicalTokens 600
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:frame {:type "string"
                                      :description (str "OPTIONAL frame id keyword, e.g. \":main\". "
                                                        "Omit to use the operating frame; a multi-frame "
                                                        "session with no selection returns "
                                                        ":ambiguous-frame.")}
                              :include-ns {:type "boolean"
                                           :description (str "When true, include :registrations — every "
                                                             "selected (kind, id) with its provenance/"
                                                             "standard coordinate. Default false (the "
                                                             "full resolver can be large; counts + the "
                                                             "per-kind list-handlers {:frame ...} drill "
                                                             "cover the common case).")}
                              :build {:type "string"}}
                 :required []
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; set-operating-frame / reset-operating-frame / get-operating-frame
;; (the three Tool-Pair §Tool-surface-obligations ops)
;; ---------------------------------------------------------------------------

(def set-operating-frame
  {:name "set-operating-frame"
   :description (str "Pin the session's operating frame: the frame that frame-targeted ops (dispatch, snapshot, "
                     "get-path, read-sub, …) use when no per-call `frame` is passed. With two or more app frames "
                     "and no pin those ops refuse with :ambiguous-frame; pin once and they resolve here until "
                     "reset-operating-frame, or a runtime reload, clears it. `frame` must name a registered frame "
                     "(bare \"stories\" or \":stories\"). Returns {:ok? true :frames [...] :app-frames [...] "
                     ":selected <pinned> :operating <resolved>}, so you can confirm the pin took. "
                     "Examples: "
                     "1. Pin a frame: {:frame \":stories\"} -> {:ok? true :frames [:rf/default :stories] :selected :stories :operating :stories ...}. "
                     "2. Unknown frame: {:frame \":nope\"} -> {:ok? false :reason :no-such-frame :frame :nope :frames [:rf/default :stories]}. "
                     "3. Missing frame: {} -> {:ok? false :reason :missing-frame}.")
   ;; Examples 2 and 3 are the refusal envelopes tools/mcp-conformance's
   ;; `operating_frame_address_test` holds its failure fixtures to, reading
   ;; each as one numbered string chunk on one line.
   :typicalTokens 200
   :annotations session-pin-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:frame {:type "string"
                                      :description (str "Frame-id to pin as the session operating frame — the public address "
                                                        "(EP-0023). Accepts bare names (\"stories\") or EDN-shaped strings "
                                                        "(\":stories\"). MUST name a currently-registered frame — call "
                                                        "get-operating-frame to list them.")}
                              :build {:type "string"}}
                 :required ["frame"]
                 :additionalProperties false}})

(def reset-operating-frame
  {:name "reset-operating-frame"
   :description (str "Clear the operating-frame pin set by set-operating-frame. Frame-targeted ops then resolve to "
                     "the sole app frame, or refuse with :ambiguous-frame when there are two or more and no "
                     "per-call `frame`. Idempotent. Returns {:ok? true :frames [...] :selected nil :operating "
                     "<resolved-or-nil>}. Example: {} -> {:ok? true :frames [:rf/default :stories] :selected nil "
                     ":operating nil}.")
   :typicalTokens 150
   :annotations session-pin-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:build {:type "string"}}
                 :additionalProperties false}})

(def get-operating-frame
  {:name "get-operating-frame"
   :description (str "Report the session's operating-frame state: :frames (every registered frame-id), :app-frames "
                     "(the app frames, reserved :rf/* tool frames left out), :selected (the pin "
                     "set-operating-frame made, or nil) and :operating (what frame-targeted ops resolve to; nil "
                     "means AMBIGUOUS — two or more app frames and no pin, so an op without `frame` refuses). "
                     "Read-only. Example: {} -> {:ok? true :frames [:rf/default :stories] :selected nil :operating "
                     "nil}.")
   :typicalTokens 150
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:build {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; get-re-frame2-pair-instructions
;; ---------------------------------------------------------------------------

(def get-re-frame2-pair-instructions
  {:name "get-re-frame2-pair-instructions"
   :description (str "Return the agent-onboarding text: routing rules for which tool to reach for, the EDN "
                     "posture, the :origin :pair tagging convention and the wire-boundary pipeline. Call it at "
                     "session start; it is inline, with no nREPL round-trip. It does not list the tools, which "
                     "tools/list already carries. Returns {:ok? true :tool \"get-re-frame2-pair-instructions\" "
                     ":tool-contract <string> :built-from <string> :text <string>}. :tool-contract fingerprints "
                     "this server's tool names and argument keys: a value other than the one the re-frame2-pair "
                     "skill states means a stale server, so rebuild and restart it before reading a missing tool "
                     "as an app finding. :built-from is the git commit it was built from (\"unknown\" when the build "
                     "recorded none); one that does not contain the newest server-source commit is stale too. "
                     "Example: {} -> {:ok? true :tool \"get-re-frame2-pair-instructions\" :tool-contract "
                     "\"30-1a2b3c4d\" :built-from \"<commit>\" :text \"re-frame2-pair-mcp agent quick reference...\"}.")
   ;; Measured, not estimated. The response is a fixed inline string with no
   ;; narrowing args, so its cost is knowable exactly;
   ;; `instructions_budget_test`'s `instructions-response-advertises-its-real-
   ;; size` holds this figure to `cap/sum-payload-tokens` of the real result
   ;; (within 10%) and prints the measurement when it drifts. Edit
   ;; `instructions-text` and expect that red — copy the figure it names.
   :typicalTokens 2000
   :annotations inline-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {}
                 :additionalProperties false}})

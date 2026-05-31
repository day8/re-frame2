(ns re-frame2-pair-mcp.tools.descriptors-data
  "MCP `tools/list` descriptor data — one `def` per tool.

  Catalogue-shaped leaf: the descriptor maps live here as named
  vars so `tools/registry.cljs` can reference each by symbol (one
  registry entry per tool, descriptor included by name) without
  forcing the registry to inline ~280 lines of descriptor blobs.

  Per rf2-zkca8's carve-out for catalogue-shaped leaves, this file
  is permitted to exceed the standard ≤250-line ceiling — splitting
  it per-tool would force readers to chase across twelve files when
  comparing descriptor shapes.

  Universal knobs (`max-tokens`, `cache`) are spliced via
  `descriptors-knobs/with-budget-knob` and `with-cache-knob` inside
  `descriptors/tool-descriptors-js`, not at the def site. Per-tool
  knobs (limit, cursor, dedup, elision) reach in by name below.

  Each entry carries `:name`, `:description`, `:inputSchema`,
  `:outputSchema` (rf2-3l3be — describes the `:structuredContent`
  payload shape; agent hosts use it to validate the result client-
  side), and `:typicalTokens` (rf2-6sddv) — an informational
  ballpark of the response-payload size in tokens that AI clients
  use to budget calls and pick size-conscious args (`max-tokens`,
  `cache`, `cursor`) without trial-and-error. Hint only; the real
  cap is enforced separately."
  (:require [re-frame2-pair-mcp.tools.descriptors-knobs :as knobs]))

;; ---------------------------------------------------------------------------
;; Recurring outputSchema fragments (rf2-3l3be).
;;
;; The `:structuredContent` payload (rf2-hj3pi) is `clj->js` of the
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
                                    :description (str "Wire-bounded marker (rf2-3rt1f). When present, REPLACES the "
                                                      "normal envelope; ships on a per-session cache hit.")}
                "rf.mcp/overflow"  {:type "object"
                                    :additionalProperties true
                                    :description (str "Wire-bounded marker (rf2-rvyzy). When present, REPLACES the "
                                                      "normal envelope; ships when the cap step fires.")}}
   :description (str "Result envelope: success / error map carrying :ok? + tool-specific slots. "
                     "Wire-bounded markers (:rf.mcp/cache-hit, :rf.mcp/overflow) replace the "
                     "tool's normal envelope when the cache or cap step fires; the agent host "
                     "pattern-matches on the marker key. See "
                     "spec/003-Tool-Catalogue.md §Universal: per-session response cache + "
                     "§Universal: wire-boundary token cap.")})

;; ---------------------------------------------------------------------------
;; Tool annotations (rf2-94p8q).
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
;;   :openWorldHint    — reaches external state (the browser runtime,
;;                       a streaming bus subscription, ...).
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
  `tail-build`, `list-subscriptions`, `handler-meta`, `list-handlers`
  return the same answer for the same args + same runtime state.
  (`list-subscriptions` reads the live reactive sub-cache, rf2-qicji —
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
  "Annotations for state-mutating tools — `dispatch`, `eval-cljs`.
  Mutations happen in the browser runtime; agent hosts should gate
  these behind explicit user confirmation by default."
  {:destructiveHint true
   :openWorldHint   true})

(def ^:private streaming-annotations
  "Annotations for streaming tools — `subscribe` opens a sub on the
  runtime's trace/epoch bus, `unsubscribe` closes one. Neither
  mutates app state, but both reach external state and have side-
  effects on the streaming registry."
  {:openWorldHint true})

(def ^:private streaming-unsubscribe-annotations
  "Annotations for `unsubscribe` — closes a subscription. Idempotent
  (a second close is a no-op `:existed? false`). Touches the
  streaming registry only (the per-call result is metadata)."
  {:idempotentHint true
   :openWorldHint  true})

(def ^:private streaming-subscribe-annotations
  "Annotations for `subscribe` — opens a streaming subscription on
  the runtime bus. Reaches external state (`:openWorldHint`); not
  idempotent (repeated calls create new subscriptions)."
  {:openWorldHint true})

(def ^:private streaming-summary-annotations
  "Annotations for the subscribe tool's final summary. Same as
  `streaming-subscribe-annotations` since the tool is the same."
  streaming-subscribe-annotations)

(def ^:private streaming-info-annotations
  "Annotations for `list-streams` — pure read over the streaming-tap
  subscription registry, idempotent across same-state calls."
  {:readOnlyHint   true
   :idempotentHint true
   :openWorldHint  true})

(def ^:private streaming-final-summary
  "Streaming-tool final-summary envelope (subscribe). The progress
  notifications it emits along the way carry their own shape
  documented in spec/003-Tool-Catalogue.md §subscribe; the
  `tools/call` result itself is just the termination summary."
  {:type "object"
   :additionalProperties true
   :properties {"ok?"             {:type "boolean"}
                "sub-id"          {:type "string"
                                    :description "uuid of the closed subscription"}
                "delivered"       {:type "integer"}
                "dropped-events"  {:type "integer"}
                "dropped-bytes"   {:type "integer"}
                "ticks"           {:type "integer"}
                "reason"          {:type "string"
                                    :description "termination reason keyword (as a string)"}}})

;; ===========================================================================
;; Tool descriptors
;;
;; One def per tool, in catalogue order (per
;; spec/003-Tool-Catalogue.md). Each section is bounded by a comment
;; rule so a reader scrolling through the file can land on a tool
;; without grepping. The file's leaf-size exemption (rf2-zkca8 —
;; catalogue-shaped leaves) earns the length; the section navigation
;; (rf2-rk2c7) earns the scannability.
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; discover-app
;; ---------------------------------------------------------------------------

(def discover-app
  {:name "discover-app"
   :description (str "Verify the shadow-cljs nREPL is reachable, confirm the re-frame2-pair runtime preload landed, and report a health summary. Run this first every session. Returns :reason :runtime-not-preloaded when the preload entry is missing. "
                     "Examples: "
                     "1. No arg, exactly one build running: {} -> auto-selects it: {:ok? true :debug-enabled? true :frames [:rf/default] :coord-annotation-enabled? true :build-id :examples/step-deck :auto-selected-build :examples/step-deck :note \"...auto-selected it.\"}. "
                     "2. Named build: {:build \"app\"} -> {:ok? true ... :build-id :app}, no auto-selection (explicit build honoured verbatim). "
                     "3. From a browser URL's port: {:port 8031} -> resolves the build serving that port via the shadow-cljs :dev-http map: {:ok? true ... :build-id :examples/step-deck}. {:port <unmapped>} -> {:ok? false :reason :port-unresolved}. "
                     "4. Preload missing: {} -> {:ok? false :reason :runtime-not-preloaded :hint \"...\"}.")
   :typicalTokens 200
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:build {:type "string"
                                      :description (str "shadow-cljs build id. Colon-tolerant: "
                                                        "\"examples/step-deck\" and \":examples/step-deck\" "
                                                        "resolve identically (rf2-8ohwv). "
                                                        "Omit it when exactly one build is running — discover-app "
                                                        "auto-selects it and notes the choice (rf2-v70kv). "
                                                        "Falls back to app when none can be inferred.")}
                              :port {:type "integer"
                                     :description (str "Resolve the serving build from a browser URL's port via "
                                                       "the shadow-cljs :dev-http map (rf2-fyf0h). E.g. you opened "
                                                       "http://localhost:8031/counter -> pass {:port 8031} and "
                                                       "discover-app finds the build whose :output-dir is served "
                                                       "on that port — no manual grep of shadow-cljs.edn. A port "
                                                       "that maps to no build -> :reason :port-unresolved. Ignored "
                                                       "when :build is given.")}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; eval-cljs
;; ---------------------------------------------------------------------------

(def eval-cljs
  {:name "eval-cljs"
   :description (str "Evaluate a ClojureScript form in the connected browser runtime via shadow-cljs's cljs-eval. Returns the EDN value. "
                     "Enabled by default (rf2-a0z0h); the operator opts OUT via --no-eval at server launch. "
                     "Opt-in :await (rf2-xn4f9) awaits Promise-returning forms server-side — single call, no js/window mailbox dance. "
                     "Frame targeting (rf2-ntuzf): optional :frame arg wraps the form in `(re-frame.core/with-frame <frame> <form>)` so "
                     "`(rf/subscribe ...)` / `(rf/dispatch ...)` inside the form resolve against the named frame. Without :frame the form "
                     "runs in the MCP server's ambient context (`:rf/default`) — silent wrong-frame reads in multi-frame apps. "
                     "Examples: "
                     "1. Read a sub: {:form \"@(re-frame.core/subscribe [:current-user])\"} -> {:ok? true :value {:id 42 :name \"Ada\"}}. "
                     "2. Inspect a global: {:form \"(keys js/window)\"} -> {:ok? true :value [\"document\" ...]}. "
                     "3. Await a Promise: {:form \"(-> (.layout instance input) (.then transform))\" :await true :timeout-ms 5000} -> {:ok? true :value <resolved>}. "
                     "4. Await rejection: {:form \"(js/Promise.reject (ex-info \\\"nope\\\" {}))\" :await true} -> {:ok? false :reason :rf.error/eval-cljs-rejected :rejection \"...\"}. "
                     "5. Await timeout: {:form \"(js/Promise. (fn [_ _]))\" :await true :timeout-ms 500} -> {:ok? false :reason :rf.error/eval-cljs-timeout :timeout-ms 500}. "
                     "6. Frame-targeted: {:form \"@(re-frame.core/subscribe [:state])\" :frame \":rf/xray\"} -> {:ok? true :value <:rf/xray's state> :frame :rf/xray}. "
                     "7. Gate closed: any args -> {:ok? false :reason :rf.error/eval-cljs-disabled} when launched with --no-eval.")
   :typicalTokens 500
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:form       {:type "string" :description "The CLJS form to evaluate."}
                              :build      {:type "string" :description "shadow-cljs build id (default: app)"}
                              :frame      {:type "string"
                                           :description (str "Operating frame for the form's lexical scope (rf2-ntuzf). "
                                                             "When supplied, the form is wrapped in "
                                                             "(re-frame.core/with-frame <frame> <form>) server-side so "
                                                             "(rf/subscribe ...) / (rf/dispatch ...) / (rf/current-frame) "
                                                             "inside the form resolve against the named frame. Accepts "
                                                             "bare names (\"rf/default\") or EDN-shaped strings "
                                                             "(\":rf/default\"). When omitted, the form runs in the MCP "
                                                             "server's ambient context (:rf/default) — silently the "
                                                             "wrong frame in multi-frame apps. Joins the family of "
                                                             "frame-aware ops (dispatch, snapshot, get-path, etc.).")}
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
                                                             "most form, the await mailbox sentinel rides through. "
                                                             "Added by rf2-xn4f9.")}
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
   :description (str "Fire a re-frame2 event tagged with :origin :pair. Default mode is queued dispatch. "
                     "Set `sync` for dispatch-sync, `trace` for synchronous dispatch returning the "
                     "assembled :rf/epoch-record. The `event` arg is parsed as EDN server-side (rf2-vflrg) — "
                     "MUST be a vector (e.g. `[:cart/checkout {:reason :user}]`). Non-vector EDN returns "
                     "`:reason :not-an-event-vector`; unreadable input returns `:reason :invalid-event-edn`. "
                     "Host-form source (e.g. `(println :x)`) is rejected — use `eval-cljs` for arbitrary "
                     "evaluation. "
                     "Cascade summary (rf2-6yqdl): every successful dispatch surfaces a `:cascade-summary` "
                     "slot projecting the resulting :rf/epoch-record — `:epoch-id`, `:event-id`, "
                     "`:event-vector`, `:frame`, `:outcome` (:ok / :blocked / :error), `:db-diff` "
                     "(depth-1 path summary), `:fx-fired` (vector of distinct fx-ids), `:subs-recomputed` / "
                     "`:renders` (counts), `:machine-transitions`, `:elapsed-ms`. Queued mode (the default) "
                     "additionally carries `:cascade-summary-pending? true` when the cascade hasn't drained "
                     "yet — poll `watch-epochs` for the eventual settlement. See the §Cascade Summary "
                     "subsection in spec/003-Tool-Catalogue.md for the full shape. "
                     "Render-settle (rf2-gfu33): set `await-render true` and the tool resolves only AFTER the "
                     "substrate has flushed the new state to the DOM and the next paint is scheduled — `dispatch -> "
                     "observe the DOM` becomes one deterministic step (no manual requestAnimationFrame dance in "
                     "eval-cljs). The flush is substrate-agnostic: it routes through the framework's "
                     "`re-frame.interop/after-render` (the `:adapter/after-render` adapter hook, Spec 006) — Reagent's "
                     "post-commit `r/after-render`, the UIx/Helix spine's React.useLayoutEffect drain, etc. "
                     "`await-render` forces synchronous dispatch (the cascade must commit before the render can "
                     "settle) and merges `:settled? true` into the result. A settle that doesn't complete within "
                     "`timeout-ms` (default 5000) returns `:reason :rf.error/dispatch-await-render-timeout`. "
                     "Examples: "
                     "1. Fire-and-forget (drained synchronously): {:event \"[:cart/checkout]\"} -> {:ok? true :mode :queued :epoch-id 7 :cascade-summary {:event-id :cart/checkout :db-diff {:changed-paths [[:cart]] :added-paths [] :removed-paths []} :fx-fired [:dispatch] :subs-recomputed 3 :renders 1 :outcome :ok :elapsed-ms 4}}. "
                     "2. Trace mode (get the assembled epoch back): {:event \"[:cart/add {:sku \\\"x\\\"}]\" :trace true} -> {:ok? true :mode :trace :epoch {...} :cascade-summary {...}}. "
                     "3. Render-settle then observe: {:event \"[:counter/inc]\" :await-render true} -> {:ok? true :mode :sync :settled? true :epoch-id 9 :cascade-summary {:renders 1 ...}} — the DOM now reflects the new state; follow with eval-cljs / a DOM read. "
                     "4. Bad event shape: {:event \"42\"} -> {:ok? false :reason :not-an-event-vector :event-edn 42}.")
   :typicalTokens 300
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:event {:type "string" :description "The event vector as EDN, e.g. \"[:cart/checkout]\" or \"[:cart/add {:sku \\\"abc\\\"}]\". MUST be a vector — non-vector EDN and host-form source are rejected."}
                              :sync  {:type "boolean"}
                              :trace {:type "boolean"}
                              :await-render {:type "boolean"
                                             :description (str "Render-settle (rf2-gfu33): when true, resolve only AFTER the "
                                                               "substrate has flushed the new state to the DOM and the next "
                                                               "paint is scheduled, so `dispatch -> observe` is one "
                                                               "deterministic step. The flush routes through the "
                                                               "substrate-agnostic `re-frame.interop/after-render` adapter hook "
                                                               "(Spec 006), then one requestAnimationFrame pins resolution to "
                                                               "the paint boundary. Forces synchronous dispatch (the cascade "
                                                               "must commit before the render can settle); merges :settled? true "
                                                               "into the result. Default false. A settle exceeding :timeout-ms "
                                                               "returns :rf.error/dispatch-await-render-timeout.")}
                              :timeout-ms {:type "integer"
                                           :description (str "Maximum ms to wait for the render-settle promise when "
                                                             ":await-render is true. Default 5000. Ignored when "
                                                             ":await-render is false. Raise it for substrates with a slow "
                                                             "render path; lower it to fail fast when no root is mounted.")}
                              :frame {:type "string" :description "Operating frame (e.g. :stories)"}
                              :fx-overrides {:type "object"
                                             :description "Per-call fx redirects, e.g. {:http :stub-http}"}
                              :build {:type "string"}}
                 :required ["event"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; dispatch-dry-run
;; ---------------------------------------------------------------------------

(def dispatch-dry-run
  {:name "dispatch-dry-run"
   :description (str "Simulate a re-frame2 cascade WITHOUT committing (rf2-17hvp). Full reducer + interceptor "
                     "chain runs, schema validation fires, machine transitions simulate, sub-runs and renders "
                     "are recorded — but NO fx execute (every registered fx is redirected to a recording "
                     "stub via :fx-overrides) and the framework rolls back the app-db via restore-epoch. "
                     "True 'experiment without consequences' — every fx that WOULD have fired is enumerated "
                     "in :would-fire-effects (with args), so the operator reasons about real-world impact "
                     "without paying it. NOT --allow-writes-gated: dry-run's contract IS 'no observable "
                     "effect'. "
                     "Returns the same :cascade-summary shape as dispatch (rf2-6yqdl) — operators read one "
                     "vocabulary for both. Composes with :fx-overrides — user-supplied overrides win on "
                     "conflict so an experimenter can compose realistic conditions (a canned http stub "
                     "response, say) without losing the rollback guarantee. "
                     "Examples: "
                     "1. Probe a write: {:event \"[:cart/checkout]\"} -> {:ok? true :dry-run? true :rolled-back? true :cascade-summary {:event-id :cart/checkout :db-diff {:changed-paths [[:cart] [:order]]} :fx-fired [:dispatch :http :navigate] ...} :would-fire-effects [{:fx-id :http :args {:url ...}} {:fx-id :navigate :args [:order-confirmation]}] :db-state-after-simulation {...}}. "
                     "2. Frame-targeted: {:event \"[:state/transition :paying]\" :frame \":checkout\"} -> dry-run against :checkout's app-db. "
                     "3. Schema violation: {:event \"[:cart/add {:bad :shape}]\"} -> {:ok? true :dry-run? true :cascade-summary {:outcome :error ...}} — the violation surfaces in cascade-summary; the rollback still fires. "
                     "4. No-op event: {:event \"[:noop]\"} -> {:ok? false :reason :no-new-epoch :hint \"...\"}.")
   :typicalTokens 800
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:event {:type "string"
                                      :description "The event vector as EDN, e.g. \"[:cart/checkout]\". MUST be a vector — same EDN-data posture as dispatch (rf2-vflrg)."}
                              :frame {:type "string" :description "Operating frame (e.g. \":checkout\"). Defaults to the operating frame."}
                              :fx-overrides {:type "object"
                                             :description (str "Optional pre-stub map (Spec 002 §:fx-overrides). User-supplied "
                                                               "overrides win on conflict — the dry-run recorder fires only for "
                                                               "fx the caller did NOT pre-stub. Use this to compose realistic "
                                                               "conditions (canned http stub, navigation redirect, etc.) without "
                                                               "losing the dry-run's roll-back guarantee.")}
                              :build {:type "string"}}
                 :required ["event"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; restore-epoch
;; ---------------------------------------------------------------------------

(def restore-epoch
  {:name "restore-epoch"
   :description (str "Time-travel undo (rf2-ee38b.18): rewind a frame's app-db to a recorded prior epoch. "
                     "The canonical pair-tool undo gesture per spec/Tool-Pair.md §Time-travel — wraps the "
                     "`restore-epoch` Tool-Pair write primitive. Walk the ring with `trace-window` / `snapshot` "
                     "(`:epochs` slice) to find a target epoch-id, then rewind to it. "
                     "The `epoch-id` is parsed as EDN — epoch-ids are `:any` (the reference runtime emits "
                     "INTEGERS), so pass an integer id as \"7\" and it reads as the number 7. "
                     "Returns false (`:reason :restore-rejected`) when the id is not in the ring or a drain is "
                     "in flight (the documented `:rf.epoch/*` failure modes); the app-db is unchanged on failure. "
                     "Cascade summary (rf2-6yqdl): a successful restore surfaces `:cascade-summary` projecting "
                     "the TARGET epoch (`:event-id`, `:event-vector`, `:db-diff` from the pre-restore live db "
                     "to the target's `:db-after`, `:fx-fired` from the original cascade, `:restore? true`). "
                     "Additionally `:unreplayable-effects` enumerates fx the original cascade fired that the "
                     "restore CANNOT undo (http requests, navigation, persisted writes). See the §Cascade "
                     "Summary subsection in spec/003-Tool-Catalogue.md. "
                     "GATED behind `--allow-writes` (default OFF) — without the flag returns "
                     "`{:ok? false :reason :rf.error/writes-disabled}` without touching the runtime. "
                     "Examples: "
                     "1. Rewind to epoch 7: {:epoch-id \"7\"} -> {:ok? true :restored? true :epoch-id 7 :cascade-summary {:event-id :cart/add :db-diff {...} :fx-fired [:http] :restore? true} :unreplayable-effects [{:fx-id :http}]}. "
                     "2. Named frame: {:epoch-id \"12\" :frame \":stories\"} -> {:ok? true :restored? true :epoch-id 12 :frame :stories :cascade-summary {...}}. "
                     "3. Aged-out id: {:epoch-id \"999\"} -> {:ok? false :restored? false :reason :restore-rejected}.")
   :typicalTokens 150
   :annotations destructive-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:epoch-id {:type "string"
                                         :description "Target epoch-id as EDN, e.g. \"7\" (integer id) or \":my/epoch\". Required."}
                              :frame    {:type "string"
                                         :description "Operating frame (e.g. \":stories\"). Defaults to the operating frame."}
                              :build    {:type "string"}}
                 :required ["epoch-id"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; reset-frame-db
;; ---------------------------------------------------------------------------

(def reset-frame-db
  {:name "reset-frame-db"
   :description (str "State injection (rf2-ee38b.18): replace a frame's app-db with an arbitrary EDN value the "
                     "runtime never recorded — the JSON-loaded-bug-repro case per spec/Tool-Pair.md §Pair-tool "
                     "writes. Wraps the `reset-frame-db!` Tool-Pair write primitive: bypasses the dispatch loop, "
                     "replaces the container directly, and records a synthetic `:rf/epoch-record` "
                     "(`:event-id :rf.epoch/db-replaced`) so a later `restore-epoch` can rewind past the injection. "
                     "The `db` arg is parsed as EDN DATA (not host source — same injection-closing posture as "
                     "`dispatch`, rf2-vflrg). Fails (`:reason :reset-rejected`) on no-such-frame, drain-in-flight, "
                     "or app-schema mismatch; the app-db is unchanged on failure. "
                     "Cascade summary (rf2-6yqdl): a successful reset surfaces `:cascade-summary` projecting "
                     "the synthetic `:rf.epoch/db-replaced` epoch — `:event-id :rf.epoch/db-replaced`, `:db-diff` "
                     "summarising before-vs-after at depth 1, `:fx-fired []` (state injection bypasses fx). "
                     "See the §Cascade Summary subsection in spec/003-Tool-Catalogue.md. "
                     "GATED behind `--allow-writes` (default OFF) — without the flag returns "
                     "`{:ok? false :reason :rf.error/writes-disabled}` without touching the runtime. "
                     "Examples: "
                     "1. Inject a repro state: {:db \"{:cart {:items []} :user nil}\"} -> {:ok? true :frame :rf/default :epoch-id 42 :cascade-summary {:event-id :rf.epoch/db-replaced :db-diff {:added-paths [[:cart] [:user]] :removed-paths [] :changed-paths []} :fx-fired [] :subs-recomputed 0 :renders 0 :outcome :ok}}. "
                     "2. Named frame: {:db \"{:count 0}\" :frame \":stories\"} -> {:ok? true :frame :stories :cascade-summary {...}}. "
                     "3. Schema mismatch: {:db \"{:bad :shape}\"} -> {:ok? false :reason :reset-rejected :frame :rf/default}.")
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
   :description (str "Return the :rf/epoch-records added in the last N ms for the operating frame. "
                     "Per spec/009 §Privacy this forwarder default-drops items carrying `:sensitive? true` "
                     "at the top level; opt back in with `include-sensitive true`. Dropped count surfaces "
                     "as `:dropped-sensitive` on the result when non-zero. "
                     "Each epoch's :db-after is diff-encoded against its own :db-before by default (rf2-1wdzp) "
                     "— pass `epochs-mode \"full\"` for the full-pair shape (the time-travel-restore mode) (needed for time-travel restore). "
                     "The epoch vector is structurally deduped by default (rf2-obpa9) — repeated subtrees "
                     "(notably the per-record `:db-before` reference) collapse to a `{:rf.mcp/dedup-table ...}` "
                     "wrapper; the agent host calls `(de-dupe.core/expand cache)` to reconstruct. Pass `dedup false` to skip. "
                     "Cursor pagination (rf2-kbqq3): the response is bounded at `:limit` records (default 50). "
                     "When more remain, `:next-cursor` carries an opaque continuation token and `:has-more? true`; "
                     "pass `cursor` back on the next call to resume. The window's upper bound is sticky across "
                     "pages — fresh epochs landing during pagination don't sneak in mid-iteration. A cursor "
                     "whose epoch-id has aged out of the runtime ring surfaces as `:reason :rf.mcp/cursor-stale`. "
                     "Examples: "
                     "1. Last second on default frame: {} -> {:ok? true :window-ms 1000 :count 3 :epochs [...] :has-more? false}. "
                     "2. Larger window, paginated: {:ms 5000 :limit 20} -> {:ok? true :count 20 :has-more? true :next-cursor \"<b64>\"}; pass back as {:cursor \"<b64>\"} to get the next page. "
                     "3. Stale cursor: {:cursor \"<aged-out-b64>\"} -> {:ok? false :reason :rf.mcp/cursor-stale :head-id ... :hint \"drop cursor and restart\"}.")
   :typicalTokens 2000
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:ms    {:type "integer" :description "Window size in milliseconds (default 1000). Sticky across pagination — encoded into the cursor on the first call."}
                              :frame {:type "string"}
                              :limit knobs/limit-property
                              :cursor knobs/cursor-property
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire: \"diff\" (default, intra-record structural diff against :db-before) or \"full\" (complete :db-after snapshot — opt-in for time-travel restore, which needs the verbatim state)."
                                            :enum ["diff" "full"]}
                              :dedup knobs/dedup-property
                              :include-sensitive {:type "boolean"
                                                   :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                              :build {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; watch-epochs
;; ---------------------------------------------------------------------------

(def watch-epochs
  {:name "watch-epochs"
   :description (str "Pull-mode poll: returns the epochs matching `pred` that landed after `since-id`. "
                     "Call repeatedly to live-watch. Predicate keys: :event-id, :event-id-prefix, :effects, "
                     ":touches-path, :sub-ran, :render, :origin, :frame, :timing-ms. "
                     ":timing-ms (rf2-r3azh) is a server-side wall-clock filter — accepts a number (sugar for "
                     "`>= N`) or a comparison string (`\">100\"`, `\"<=50\"`, `\">=100\"`, `\"<200\"`, `\"=42\"`). "
                     "Compares against the cascade's elapsed-ms derived from the `:rf.event/run-start` / "
                     "`:rf.event/run-end` trace pair. The filter rides server-side so non-matching epochs "
                     "never cross the wire — typicalTokens above is the worst case, narrowing on :timing-ms "
                     "(e.g. only :>100ms epochs) shrinks the payload roughly proportional to the match rate. "
                     "Per spec/009 §Privacy this forwarder "
                     "default-drops items carrying `:sensitive? true`; opt back in with `include-sensitive true`. "
                     "Each epoch's :db-after is diff-encoded against its own :db-before by default (rf2-1wdzp) "
                     "— pass `epochs-mode \"full\"` for the full-pair shape (the time-travel-restore mode). "
                     "The matches vector is structurally deduped by default (rf2-obpa9); pass `dedup false` to skip. "
                     "Cursor pagination (rf2-kbqq3): the matches vector is bounded at `:limit` (default 50). "
                     "When more matches remain, `:next-cursor` is non-nil and `:has-more? true`; pass `cursor` "
                     "back to consume the next page. The `:cursor` arg overrides `:since-id` when both are "
                     "supplied. A cursor whose epoch-id has aged out of the ring surfaces as "
                     "`:reason :rf.mcp/cursor-stale`. "
                     "Examples: "
                     "1. First poll for a specific event: {:pred {:event-id :cart/checkout}} -> {:ok? true :matches [...] :count 1 :head-id \"...\"}. "
                     "2. Resume after last seen id: {:since-id \"epoch-42\" :pred {:effects :http}} -> {:ok? true :matches [...] :head-id \"epoch-47\"}. "
                     "3. Slow-cascade probe: {:pred {:timing-ms \">100\"}} -> {:ok? true :matches [{:rf/epoch-id ... :elapsed-ms 142}]}.")
   :typicalTokens 2000
   :annotations read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:since-id {:type "string" :description "The last epoch id you've seen (omit to start fresh). Supplanted by :cursor when both are passed."}
                              :pred     {:type "object" :description "Filter map"}
                              :frame    {:type "string"}
                              :limit    knobs/limit-property
                              :cursor   knobs/cursor-property
                              :epochs-mode {:type "string"
                                            :description "How :db-after rides the wire: \"diff\" (default) or \"full\" (legacy, opt-in for time-travel restore)."
                                            :enum ["diff" "full"]}
                              :dedup    knobs/dedup-property
                              :include-sensitive {:type "boolean"
                                                   :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                              :build    {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; tail-build
;; ---------------------------------------------------------------------------

(def tail-build
  {:name "tail-build"
   :description (str "Wait for a hot-reload to land by polling a probe form until its value changes. Returns once changed, or times out. "
                     "Examples: "
                     "1. Default 300ms soft delay: {} -> {:ok? true :t 312 :soft? true}. "
                     "2. Probe for a recompile signal: {:probe \"(rand)\" :wait-ms 10000} -> {:ok? true :t 1240 :soft? false}. "
                     "3. Timed out: {:probe \"my.app/build-marker\" :wait-ms 500} -> {:ok? false :reason :timed-out}.")
   :typicalTokens 100
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:probe   {:type "string" :description "CLJS form whose value should change after the reload"}
                              :wait-ms {:type "integer" :description "Max wait in ms (default 5000)"}
                              :build   {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; snapshot
;; ---------------------------------------------------------------------------

(def snapshot
  {:name "snapshot"
   :description (str "Coarse-grained per-frame state read in one round-trip — the mega-op for investigate-X workflows. "
                     "Returns a map keyed by frame-id whose values carry the requested slices: "
                     ":app-db, :sub-cache, :machines, :epochs, :traces. "
                     "Server-side composition over the existing per-slice runtime readers. "
                     "Prefer this over chaining 5-10 individual reads. "
                     "Lazy-summary default (rf2-u2029): each rich slice in the response is replaced with a "
                     "`{:rf.mcp/summary {:type :map|:vector :keys [...] :count N :bytes ~B}}` marker by "
                     "default — keeps a discovery snapshot under the wire cap by construction. Agents drill "
                     "into the slice they actually need via `mode \"full\"` (every slice expands), per-slice "
                     "`modes {\"app-db\": \"full\"}` (one slice expands), or — for the :app-db slice only — "
                     "the `path` arg (rf2-tygdv: returns the addressed subtree). "
                     "Path slicing (rf2-tygdv): the `:app-db` slice supports a `path` arg (an EDN-encoded "
                     "vector of keys, e.g. \"[:cart :items 0]\"). With `path`, returns the addressed subtree. "
                     "Path-slicing supersedes the slice-level mode for `:app-db`. "
                     "Diff-encoded epochs (rf2-1wdzp): each epoch in the `:epochs` slice has its `:db-after` "
                     "replaced with a structural diff against its own `:db-before` by default — pass "
                     "`epochs-mode \"full\"` for full-pair shape (the time-travel-restore mode, which needs verbatim state). "
                     "Diff-encode runs before the lazy-summary so `bytes` hints reflect post-shrink cost. "
                     "Per spec/009 §Privacy the `:traces` and `:epochs` slices default-drop items carrying "
                     "`:sensitive? true`; opt back in with `include-sensitive true`. "
                     "Per Tool-Pair §Direct-read privacy posture (rf2-vflrg) the `:app-db` and `:sub-cache` "
                     "slices are routed through `re-frame.core/elide-wire-value` with off-box defaults — "
                     "declared-sensitive paths return the `:rf/redacted` sentinel and large slots return "
                     "the `:rf.size/large-elided` marker; the same `include-sensitive true` flag opts back "
                     "in to seeing the raw value at sensitive paths. The `:machines` slice passes through "
                     "unchanged — payload redaction there is the `redact-interceptor` interceptor's job. "
                     "Each frame's `:epochs` slice is structurally deduped (rf2-obpa9) after diff-encoding — "
                     "repeated subtrees (notably the per-record `:db-before` reference) collapse to a "
                     "`{:rf.mcp/dedup-table ...}` wrapper; agent host reconstructs via `de-dupe.core/expand`. "
                     "Pass `dedup false` to skip. "
                     "Size-elision (rf2-urjnc): each frame's `:app-db` slice is run through "
                     "`re-frame.core/elide-wire-value` server-side before crossing the wire — declared / "
                     "schema-`:large?` paths and over-threshold leaves are substituted with a "
                     "`{:rf.size/large-elided {:path [...] :handle [:rf.elision/at <path>] ...}}` marker. "
                     "Agent drills into the handle via `get-path` (or `snapshot {:path ...}` with a "
                     "non-elided sibling subpath). Pass `elision false` to bypass the walk and receive "
                     "the raw value. "
                     "Examples: "
                     "1. Discovery snapshot (summaries only): {:frames \"all\"} -> {:ok? true :mode :summary :snapshot {:rf/default {:app-db {:rf.mcp/summary {:type :map :keys [...] :count N :bytes ~B}} :sub-cache {...} :machines {...} :epochs {...} :traces {...}}}}. "
                     "2. Drill into one slice: {:frames [\":rf/default\"] :include [\"app-db\"] :path \"[:cart :items]\"} -> {:ok? true :mode :path-sliced :path [:cart :items] :snapshot {:rf/default {:app-db [{:sku \"x\" :qty 2}]}}}. "
                     "3. Full expansion (legacy shape): {:frames \"all\" :mode \"full\"} -> {:ok? true :mode :full :snapshot {:rf/default {:app-db {...} :epochs [...]}}}.")
   :typicalTokens 3000
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:frames  {:description "Frames to snapshot. Pass \"all\" (default) or an array of frame-id strings like [\":rf/default\", \":stories\"]."
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
                                        :description (str "Global lazy-summary mode (rf2-u2029). "
                                                          "\"summary\" (default) replaces every rich slice "
                                                          "(:app-db when no `path`, :sub-cache, :machines, :epochs, :traces) "
                                                          "with a `{:rf.mcp/summary ...}` marker — top-level keys, "
                                                          "count, and approximate bytes. \"full\" expands every "
                                                          "slice to its raw payload (legacy pre-rf2-u2029 behaviour). "
                                                          "Per-slice override via `modes` takes precedence.")
                                        :enum ["summary" "full"]}
                              :modes   {:type "object"
                                        :description (str "Per-slice mode override (rf2-u2029) — a map "
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
                                                   :description (str "Opt back in to BOTH (a) forwarding `:sensitive? true` "
                                                                     "items in the :traces / :epochs slices AND (b) seeing "
                                                                     "the raw value at declared-sensitive paths in the "
                                                                     ":app-db / :sub-cache slices (the walker's "
                                                                     "`:rf.size/include-sensitive?` opt). Default false.")}
                              :build   {:type "string" :description "shadow-cljs build id (default: app)"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; get-path
;; ---------------------------------------------------------------------------

(def get-path
  {:name "get-path"
   :description (str "Read a single value at `path` from a frame's app-db. Minimal primitive for "
                     "targeted reads — the agent already knows the path. Server-side `(get-in db path)`; "
                     "only the addressed subtree crosses the wire. Returns "
                     "`{:ok? true :exists? true :path [...] :value <subtree>}` on success or "
                     "`{:ok? false :reason :path-not-found :path [...] :deepest-valid-prefix [...]}` "
                     "when the path doesn't resolve. The deepest-valid-prefix lets the agent re-aim "
                     "without a binary search. Use this when `snapshot`'s summary mode (default) "
                     "tells you which key carries the answer. "
                     "Size-elision (rf2-urjnc): the resolved value is run through "
                     "`re-frame.core/elide-wire-value` server-side — a declared / schema-`:large?` "
                     "slot or an over-threshold leaf returns a `{:rf.size/large-elided ...}` marker "
                     "with a `:handle [:rf.elision/at <path>]` fetch handle, not the raw bytes. Drill "
                     "into a non-elided child by re-calling with a deeper `path`. Pass `elision false` "
                     "to bypass the walk and receive the raw value. "
                     "Privacy (rf2-vflrg, per Tool-Pair §Direct-read privacy posture): declared-sensitive "
                     "paths return the `:rf/redacted` sentinel by default — opt in to seeing the raw "
                     "value at sensitive paths via `include-sensitive true`. "
                     "Examples: "
                     "1. Hit: {:path \"[:cart :items 0 :sku]\"} -> {:ok? true :exists? true :path [:cart :items 0 :sku] :value \"sku-abc\"}. "
                     "2. Path-not-found: {:path \"[:cart :items 99]\"} -> {:ok? false :reason :path-not-found :path [:cart :items 99] :deepest-valid-prefix [:cart :items]}. "
                     "3. Large slot elided: {:path \"[:big :payload]\"} -> {:ok? true :exists? true :value {:rf.size/large-elided {:path [:big :payload] :bytes 12345 :type :map :reason :schema :handle [:rf.elision/at [:big :payload]]}}}.")
   :typicalTokens 500
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:path  {:description (str "Path into app-db. EDN-encoded vector of keys "
                                                        "(e.g. \"[:cart :items 0 :sku]\") or a JSON array "
                                                        "of segment strings (each parsed as EDN — bare "
                                                        "strings stay as map-key strings).")
                                      :oneOf [{:type "string"}
                                              {:type "array" :items {:type "string"}}]}
                              :frame   {:type "string"
                                        :description "Frame-id (e.g. \":rf/default\"). Defaults to the operating frame."}
                              :elision knobs/elision-property
                              :include-sensitive {:type "boolean"
                                                   :description (str "Opt in to seeing the raw value at declared-sensitive "
                                                                     "paths (the walker's `:rf.size/include-sensitive?` opt). "
                                                                     "Default false ⇒ sensitive paths return the `:rf/redacted` "
                                                                     "sentinel.")}
                              :build   {:type "string"}}
                 :required ["path"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; subscribe
;; ---------------------------------------------------------------------------

(def subscribe
  {:name "subscribe"
   :description (str "Open a streaming subscription on the trace or epoch bus. Push-mode replacement for watch-epochs. "
                     "Long-running tools/call — emits each batch of matching events as a notifications/progress notification "
                     "(correlated via the call's progressToken), and resolves with a summary when the client cancels or an "
                     "unsubscribe op fires. Topics: 'trace' (raw trace stream), 'epoch' (assembled :rf/epoch-records), "
                     "'fx' (trace stream filtered to :op-type :rf.fx), 'error' (trace stream filtered to :op-type :error), "
                     "'frameless' (trace events with no :rf.trace/dispatch-id — registration emits, REPL evals, lifecycle "
                     "outside any cascade; per Tool-Pair §Frameless trace events). "
                     "Cascade-bundle delivery (rf2-mscih): :trace / :fx / :error ship each tick's matched events grouped "
                     "by :rf.trace/dispatch-id into cascade bundles matching `(rf/trace-buffer frame-id)` shape "
                     "(:dispatch-id :frame :event :dispatched :handler :fx :effects :subs :renders :other :trace-events "
                     ":parent-dispatch-id). The progress payload's load slot is `:cascades` on these topics. Frameless "
                     "events NEVER ride these topics — opt into the `:frameless` topic explicitly. :epoch and :frameless "
                     "ship as `:events` (flat). "
                     "Filter vocab depends on topic — :trace/:fx/:error/:frameless accept the (rf/trace-buffer) filter map "
                     "(:operation :op-type :frame :severity :event-id :handler-id :source :origin :dispatch-id :since-ms :between); "
                     ":epoch accepts the epoch-matches? predicate map (:event-id :event-id-prefix :effects :touches-path "
                     ":sub-ran :render :origin :frame :timing-ms). :timing-ms (rf2-r3azh) is a server-side wall-clock "
                     "filter — number (sugar for `>= N`) or comparison string (`\">100\"`, `\"<=50\"`, …). "
                     "Pass `filter` either as a JSON object or as an EDN-encoded string. "
                     "Per spec/009 §Privacy this forwarder default-drops events carrying `:sensitive? true` at the top "
                     "level; opt back in with `include-sensitive true`. Dropped count surfaces as `:dropped-sensitive` "
                     "on each progress payload (when non-zero) and the final summary. "
                     "Each progress payload's payload-slot is structurally deduped by default (rf2-obpa9) — "
                     "shared subtrees across the tick collapse to a `{:rf.mcp/dedup-table ...}` wrapper; "
                     "agent host reconstructs via `(de-dupe.core/expand cache-map)`. Dedup is per-tick, not "
                     "per-stream — each notifications/progress frame carries its own cache, no cross-tick "
                     "references. Pass `dedup false` to skip. "
                     "Examples: "
                     "1. Stream every epoch: {:topic \"epoch\"} -> progress ticks {:sub-id \"<uuid>\" :events [...]} until cancel; final summary {:ok? true :sub-id \"<uuid>\" :delivered N :ticks K :reason :aborted}. "
                     "2. Filtered fx-cascade stream: {:topic \"fx\" :filter {:event-id :cart/checkout}} -> ticks {:sub-id \"...\" :cascades [{:dispatch-id ... :event ... :fx ... :effects [...] :trace-events [...]}]}; ends with {:ok? true :delivered N :reason :aborted}. "
                     "3. Time-bounded error probe: {:topic \"error\" :max-ms 30000 :max-events 100} -> closes after 30s or 100 errors; cascade bundles only. "
                     "4. Frameless live channel: {:topic \"frameless\"} -> ticks {:sub-id \"...\" :events [<registration / REPL emits>]}.")
   :typicalTokens 1000
   :annotations streaming-subscribe-annotations
   :outputSchema streaming-final-summary
   :inputSchema {:type "object"
                 :properties {:topic   {:type "string"
                                        :description "Topic name. Required."
                                        :enum ["trace" "epoch" "fx" "error" "frameless"]}
                              :filter  {:description "Filter map (JSON object) or EDN string. Vocab depends on topic."
                                        :oneOf [{:type "object"}
                                                {:type "string"}]}
                              :max-buffered-events {:type "integer"
                                                    :description "Runtime-side queue cap in EVENTS. Default 500. On overflow the OLDEST events are evicted (drop-oldest FIFO) and reported as `:dropped-events` / `:overflow-reason :max-buffered-events` on the next progress tick. OR-combined with :max-buffered-bytes — whichever trips first evicts."}
                              :max-buffered-bytes  {:type "integer"
                                                    :description "Runtime-side queue cap in BYTES (pr-str char count). Default 5_000_000 (~5 MB). Same drop-oldest policy; reports `:dropped-bytes` / `:overflow-reason :max-buffered-bytes`. Sized to fit the 5,000-token wire-cap posture across a normal poll cadence."}
                              :poll-ms {:type "integer"
                                        :description "Server poll cadence in ms. Default 100."}
                              :max-ms  {:type "integer"
                                        :description "Hard upper-bound on how long the subscription stays open, ms. 0 = unbounded (close on cancel only). Default 0."}
                              :max-events {:type "integer"
                                           :description "Terminate after this many events have been delivered. 0 = unbounded. Default 0."}
                              :dedup    knobs/dedup-property
                              :include-sensitive {:type "boolean"
                                                   :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                              :build   {:type "string"}}
                 :required ["topic"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; unsubscribe
;; ---------------------------------------------------------------------------

(def unsubscribe
  {:name "unsubscribe"
   :description (str "Close the subscription with the given sub-id. Idempotent — closing an unknown sub-id returns :existed? false. "
                     "Examples: "
                     "1. Live close: {:sub-id \"abc-123\"} -> {:ok? true :sub-id \"abc-123\" :existed? true}. "
                     "2. Already-closed (idempotent): {:sub-id \"abc-123\"} -> {:ok? true :sub-id \"abc-123\" :existed? false}. "
                     "3. Unknown id: {:sub-id \"never-was\"} -> {:ok? true :sub-id \"never-was\" :existed? false}.")
   :typicalTokens 50
   :annotations streaming-unsubscribe-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:sub-id {:type "string"
                                       :description "The uuid returned by `subscribe`."}
                              :build  {:type "string"}}
                 :required ["sub-id"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; list-subscriptions
;; ---------------------------------------------------------------------------

(def list-subscriptions
  {:name "list-subscriptions"
   :description (str "List the LIVE reactive subscriptions materialised in a frame's per-frame sub-cache — the answer to "
                     "'what subscriptions are currently active?'. Reads the SAME source `snapshot`'s :sub-cache slice reads "
                     "(re-frame.subs.tooling/sub-cache-snapshot, via the runtime's `sub-cache` fn), so the two never disagree (rf2-qicji). "
                     "The reactive cache is ref-counted and live: an entry appears the moment a view subscribes and DISAPPEARS "
                     "when the last consumer disposes the reaction — so a disposed sub no longer shows up here. "
                     "NOT the streaming-tap registry — for 'what trace/epoch/fx/error streams are open?' use `list-streams`. "
                     "Returns `{:ok? true :frame <id> :count N :subs [<query-v> ...]}` (query-vectors only, sorted, stable across calls); "
                     "with `:include-values true` each entry becomes `{:query-v <v> :value <current-deref> :ref-count <n>}`. "
                     "Empty `:subs` vector when nothing is subscribed in the frame. "
                     "Frame resolution mirrors snapshot/get-path: omit `:frame` to use the operating frame; a multi-frame session "
                     "with no selection returns {:ok? false :reason :ambiguous-frame}. "
                     "Examples: "
                     "1. Default frame: {} -> {:ok? true :frame :rf/default :count 1 :subs [[\"mounted?\"]]}. "
                     "2. With values: {:frame \":rf/xray\" :include-values true} -> {:ok? true :frame :rf/xray :count 2 :subs [{:query-v [\"active-filters\"] :value #{} :ref-count 1} {:query-v [\"for-table\" \"views\"] :value [...] :ref-count 1}]}. "
                     "3. Nothing subscribed: {:frame \":rf/default\"} -> {:ok? true :frame :rf/default :count 0 :subs []}.")
   :typicalTokens 400
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:frame          {:type "string"
                                               :description "Frame to read. Accepts bare names (\"rf/default\") or EDN-shaped strings (\":rf/default\"). Defaults to the operating frame; a multi-frame session with no selection -> :reason :ambiguous-frame."}
                              :include-values {:type "boolean"
                                               :description "When true, each entry carries :value (current deref) and :ref-count alongside :query-v. Default false (query-vectors only — the cheap 'what's subscribed' read)."}
                              :build          {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; list-streams
;; ---------------------------------------------------------------------------

(def list-streams
  {:name "list-streams"
   :description (str "List active streaming-tap subscriptions opened via `subscribe`, with per-sub queue depth, "
                     "drop counts, and overflow-reason — without draining any queues. Diagnostic for "
                     "'what streams are currently open?' and 'is my probe still alive?'. Wraps the "
                     "`re-frame2-pair.runtime/subscription-info` runtime fn directly (no eval-cljs round-trip). "
                     "NOT the reactive sub-cache — for 'what reactive subscriptions are active?' use `list-subscriptions` (rf2-qicji). "
                     "Returns `{:ok? true :subs [{:id :topic :filter :queue-depth :queue-bytes "
                     ":dropped-events :dropped-bytes :overflow-reason :created-at}]}` — one entry per "
                     "currently-registered streaming subscription. Empty `:subs` vector when no streams are open. "
                     "Optional filters: `topic` (one of `:trace` / `:epoch` / `:fx` / `:error` / `:frameless`) narrows to "
                     "a single topic; `sub-id` returns only the matching sub. A non-nil `:overflow-reason` "
                     "indicates the queue has been evicting older events to stay inside its budget — tune "
                     "`max-buffered-events` / `max-buffered-bytes` on the next `subscribe` call. "
                     "Examples: "
                     "1. No streams open: {} -> {:ok? true :subs []}. "
                     "2. Healthy probe alive: {:sub-id \"abc-123\"} -> {:ok? true :subs [{:id \"abc-123\" :topic :epoch :filter {} :queue-depth 0 :queue-bytes 0 :dropped-events 0 :overflow-reason nil :created-at 1234567890}]}. "
                     "3. Pressured queue: {:topic \"trace\"} -> {:ok? true :subs [{:id \"xyz-789\" :topic :trace :queue-depth 487 :queue-bytes 2400000 :dropped-events 12 :overflow-reason :max-buffered-bytes}]}.")
   :typicalTokens 500
   :annotations streaming-info-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:topic  {:type "string"
                                       :description "Optional filter — only return subs on this topic. One of trace, epoch, fx, error, frameless."
                                       :enum ["trace" "epoch" "fx" "error" "frameless"]}
                              :sub-id {:type "string"
                                       :description "Optional filter — only return the sub with this uuid."}
                              :build  {:type "string"}}
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; handler-meta
;; ---------------------------------------------------------------------------

(def handler-meta
  {:name "handler-meta"
   :description (str "Return the registration-metadata map for a registered "
                     "handler — source-coord (file/line/column/ns), :doc, :tags, "
                     "and any custom slots emitted by the reg-* macro. The wire "
                     "pipeline (rf2-cibp8) decorates the :source-coord map with "
                     "an :rf.source/uri string the AI host renders as a "
                     "clickable jump-to-editor link. "
                     "Use this when you know an id and want to find its "
                     "definition without a wide-authority eval-cljs round-trip "
                     "— `where is :user/login registered?`, `what does sub "
                     ":current-user look like?`, `which file owns the :navigate "
                     "fx?`. Supported kinds: event, sub, fx, cofx, view, frame, "
                     "route, flow, head, error-projector, machine — the closed "
                     "v1 registrar set (per Spec 001 §Registry model). App-db "
                     "schemas are NOT a registrar kind (rf2-cq1ak) — their "
                     "metadata lives in the schemas artefact's per-frame side-"
                     "table, queried via `rf/app-schemas` /  "
                     "`rf/app-schema-meta-at` instead. The `machine` kind "
                     "routes through (rf/machine-meta id) (Spec 005 §Querying "
                     "machines); the other kinds route through "
                     "(rf/handler-meta kind id). Returns `{:ok? true :kind k "
                     ":id i ...meta...}` on a hit or `{:ok? false :reason "
                     ":not-registered :kind k :id i}` when no slot matches. "
                     "Examples: "
                     "1. Find an event: {:kind \"event\" :id \":user/login\"} -> {:ok? true :kind :event :id :user/login :ns my.app.user :line 42 :file \"src/my/app/user.cljs\" :rf.source/uri \"file://...\" :doc \"...\"}. "
                     "2. Subscription on a composite key: {:kind \"sub\" :id \"[:rf/composite [:items :by-id 42]]\"} -> {:ok? true :kind :sub :id [:rf/composite [:items :by-id 42]] :ns my.app.subs :line 18}. "
                     "3. Miss: {:kind \"fx\" :id \":missing/fx\"} -> {:ok? false :reason :not-registered :kind :fx :id :missing/fx}.")
   :typicalTokens 400
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:kind {:type "string"
                                     :description "Registrar kind. One of event, sub, fx, cofx, view, frame, route, flow, head, error-projector, machine."
                                     :enum ["event" "sub" "fx" "cofx" "view" "frame" "route" "flow" "head" "error-projector" "machine"]}
                              :id   {:type "string"
                                     :description (str "EDN-encoded id, e.g. \":user/login\". For "
                                                       "composite-key subs, pass the vector form "
                                                       "as a string, e.g. \"[:rf/composite :x]\".")}
                              :build {:type "string"}}
                 :required ["kind" "id"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; list-handlers
;; ---------------------------------------------------------------------------

(def list-handlers
  {:name "list-handlers"
   :description (str "Return every registered id under a kind. The discovery "
                     "surface — agents call this first to find out what's "
                     "registered, then `handler-meta` to drill into a specific "
                     "id. Supported kinds: event, sub, fx, cofx, view, frame, "
                     "route, flow, head, error-projector, machine — the closed "
                     "v1 registrar set (per Spec 001 §Registry model). App-db "
                     "schemas are NOT a registrar kind (rf2-cq1ak) — use "
                     "`rf/app-schemas` for schema enumeration. The `machine` "
                     "kind lists every event handler flagged `:rf/machine? "
                     "true` via (rf/machines); the other kinds lift the id "
                     "vector off the registrar's per-kind map. Returns "
                     "`{:ok? true :kind k :ids [...] :count n}`. The id "
                     "vector is sorted (string / keyword / symbol ordering) "
                     "so the list shape is stable across calls. "
                     "Examples: "
                     "1. List events: {:kind \"event\"} -> {:ok? true :kind :event :ids [:cart/add :cart/checkout :user/login ...] :count 47}. "
                     "2. List subs: {:kind \"sub\"} -> {:ok? true :kind :sub :ids [:current-user :cart/items ...] :count 23}. "
                     "3. List machines: {:kind \"machine\"} -> {:ok? true :kind :machine :ids [:auth/session :checkout/flow] :count 2}.")
   :typicalTokens 800
   :annotations idempotent-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {:kind {:type "string"
                                     :description "Registrar kind. One of event, sub, fx, cofx, view, frame, route, flow, head, error-projector, machine."
                                     :enum ["event" "sub" "fx" "cofx" "view" "frame" "route" "flow" "head" "error-projector" "machine"]}
                              :build {:type "string"}}
                 :required ["kind"]
                 :additionalProperties false}})

;; ---------------------------------------------------------------------------
;; get-re-frame2-pair-instructions
;; ---------------------------------------------------------------------------

(def get-re-frame2-pair-instructions
  {:name "get-re-frame2-pair-instructions"
   :description (str "Return the re-frame2-pair-mcp agent-onboarding text (rf2-fnpqg): tool catalogue, EDN posture, "
                     "tagged-mutation conventions, streaming subscribe semantics, the wire-boundary "
                     "pipeline. Inline prose, no nREPL round-trip — call this at session start to orient "
                     "before the first real op. Mirrors story-mcp's `get-story-instructions`. Returns "
                     "`{:ok? true :tool \"get-re-frame2-pair-instructions\" :text <string>}` — a single text slot "
                     "the agent host renders verbatim. "
                     "Examples: "
                     "1. Session bootstrap: {} -> {:ok? true :tool \"get-re-frame2-pair-instructions\" :text \"re-frame2-pair quick reference...\"}. "
                     "2. Cached on second call (universal cache opt-in): {:cache true} -> {:rf.mcp/cache-hit {:hash ... :via :result-hash :hint \"...\"}} (after the first uncached call). "
                     "3. With budget override: {:max-tokens 0} -> {:ok? true :text \"...\"} (cap disabled; the text always fits comfortably).")
   :typicalTokens 1500
   :annotations inline-read-only-annotations
   :outputSchema envelope-or-marker
   :inputSchema {:type "object"
                 :properties {}
                 :additionalProperties false}})

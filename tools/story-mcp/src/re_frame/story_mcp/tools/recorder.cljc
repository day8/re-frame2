(ns re-frame.story-mcp.tools.recorder
  "record-as-variant — the recorder's MCP surface (rf2-luhdu).

  Wraps `re-frame.story`'s recorder primitives (start-recording! →
  sleep for :duration-ms → stop-recording! → gen-play-snippet) per
  tools/story/spec/005-SOTA-Features.md §Test Codegen \"MCP wiring\".

  This tool's job is the cross-process bridge: an agent calls it and
  gets back the `(reg-variant ...)` snippet for whatever the canvas
  dispatched during the recording window. The recorder itself does the
  filter work (op-type :event/dispatched, frame scope, internal-ns
  suppression) — see `re-frame.story.recorder/recordable-event?`.

  Optional `:write-back` re-registers the source variant with the
  captured recording translated to a live play slot. The emitted EDN
  uses the PUBLIC `:script` authoring spelling (spec/017 §Public
  vocabulary; rf2-7mj4z — the rename is finished on every emission
  surface). This is gated by the same `allow-writes?` flag as `register-variant`
  (`tools.write/assert-writes-allowed`). This is
  the self-healing-loop hook the spec mentions: agent drives canvas →
  tool returns snippet AND patches the variant in place.

  ## Wire-key shape (rf2-pmwgn)

  The wire-arg key is `:write-back` (no `?`) to satisfy Anthropic's
  `^[a-zA-Z0-9_.-]{1,64}$` constraint on tool input-schema property
  keys — the same rationale as `:include-sensitive`. The structured-
  content response key `:written-back?` keeps the `?` because response-
  payload keys are NOT bound by that regex (per
  `schemas.cljc` §wire-key shape). The predicate function
  `write-back?` in scope retains the `?` per the Clojure predicate
  idiom — the `?` belongs on predicates and on response data, not on
  the input-schema property whose wire form disallows it."
  (:require [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.tools.args :as targs]
            [re-frame.story-mcp.tools.egress :as egress]
            [re-frame.story-mcp.tools.result :as result]
            [re-frame.story-mcp.tools.schemas :as s]
            [re-frame.story-mcp.tools.write :as write]))

(def ^:const max-duration-ms
  "Ceiling on `:duration-ms` for `record-as-variant` (rf2-4yuhi). The
  MCP server's request loop is single-threaded (`server/run-loop!`), so
  a `record-as-variant` call sleeps the whole loop for the full window.
  At 30s an abusive caller effectively DoS's the server; we reject any
  value above this ceiling with a tool-execution error rather than
  letting the loop stall. 30s is a generous dev-session window — the
  recorder is meant to bridge an agent driving a canvas, not to be a
  long-running scheduler."
  30000)

(defn- sleep-ms
  "Block the caller for `ms` milliseconds. CLJS host has no blocking
  primitive, so this is a no-op there — CLJS callers wanting a recording
  window dispatch their interactions between `start-recording!` and the
  tool's stop step from their own scheduler. The MCP server's canonical
  deploy is JVM, where `Thread/sleep` is honest."
  [ms]
  #?(:clj  (when (pos? ms) (Thread/sleep ^long ms))
     :cljs nil))

(defn- now-ms
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- write-back!
  "Re-register the target variant with the captured recording as a live
  play body under the public `:script` authoring slot. Preserves the
  source variant's existing body keys (so `:component`, `:args`,
  `:decorators` survive) and overwrites the play surface with the
  translated, replayable script.

  Per rf2-7mj4z the write-back assocs the PUBLIC `:script` authoring slot
  (spec/017 §Public vocabulary), not the transitional `:play-script`
  spelling — the `:setup`/`:script` rename is finished on every emission
  surface. (Both spellings store identically: `reg-variant*` lowers the
  public `:script` to the shipping `:play-script` slot via
  `schemas/lower-public-vocabulary`, so `variant->edn` of the stored body
  reads `:play-script` either way — the rename is an author-facing
  intent, the stored shipping slot is unchanged. The legacy `:play` slot
  was REMOVED in rf2-0wrud; a `:play` key would pass the open variant
  `:map` validation but no runner executes it.) We translate the captured
  `events` via `story/recording->play-script` (the live runtime
  counterpart to `gen-play-snippet`'s text output) — which returns a
  `{:script … :auto-run?}` play body — and write that under `:script`.

  Stamps `:origin :story-mcp` per `spec/Cross-Cutting-Designs.md §5` —
  the write-back produces a new variant body and the origin tag
  identifies the MCP write surface as its producer.

  Returns the structured success result on the happy path, or an
  `error-result` whose `:structuredContent` merges the base recorder
  payload, the failure flag, and the registrar's `ex-data`."
  [base body events target-vid]
  (try
    (let [play-body (story/recording->play-script events)
          id        (story/reg-variant*
                      target-vid
                      (assoc body :script play-body :origin config/origin))
          payload   (assoc base :written-back? true :new-variant-id id)]
      (result/edn-result payload))
    (catch #?(:clj Throwable :cljs :default) e
      (result/error-result (str "Write-back failed: " (ex-message e))
                      (merge base
                             {:written-back?  false
                              :new-variant-id target-vid}
                             (select-keys (ex-data e)
                                          [:rf.error :explain]))))))

(defn tool-record-as-variant
  "Dev (or Write when `:write-back` is true): bridge the recorder's
  start → capture → snippet pipeline across the MCP boundary.

  Args:
    :variant-id    required — keyword id of the existing variant to
                              record against (the recording's target
                              frame).
    :duration-ms   optional — block the tool call for this many ms
                              between `start-recording!` and
                              `stop-recording!`. Default 0 (the caller
                              is expected to drive dispatches in
                              parallel and stop the recording out-of-
                              band). JVM only — CLJS sleeps are a no-op.
                              Hard ceiling `max-duration-ms` (30000 ms)
                              — the MCP server's request loop is single-
                              threaded; durations above the ceiling are
                              rejected with a structured error
                              (rf2-4yuhi).
    :new-variant-id optional — when `:write-back` is true, register the
                              captured recording (translated to a live
                              `:script` body, the public phase-4 play
                              surface — rf2-7mj4z) as a NEW variant with
                              this id. Defaults to the source
                              `:variant-id` (overwrites in place).
    :doc           optional — docstring to embed in the snippet.
    :extends       optional — variant id to embed as the snippet's
                              `:extends` slot (defaults to the source
                              `:variant-id` — recording extends from the
                              canvas it ran against).
    :alias         optional — short ns alias in the rendered form
                              (default `\"story\"`).
    :write-back    optional — when true, also re-register the variant
                              via `reg-variant*` with the captured
                              recording translated to a live `:script`
                              body (the public phase-4 play surface —
                              rf2-7mj4z). Requires `allow-writes?`
                              (same gate as `register-variant`).
                              Wire-key shape per rf2-pmwgn: no `?` —
                              Anthropic's input-schema property-name
                              regex rejects it.
    :include-sensitive optional — opt out of wire-egress redaction of the
                              captured event payloads (default false;
                              rf2-12f2q). Gated by --allow-sensitive-reads.

  Wire-egress posture (rf2-12f2q): the captured event vectors cross the
  AI/off-box boundary in both the `:captured` slot and the `:play-snippet`
  text. They are value-redacted against the source variant frame's
  declared-`:sensitive?` values before egress (the SAME value-based
  redaction the live-state tools apply to their derived trees), and the
  snippet is rendered FROM the scrubbed events. The WRITE-BACK path
  re-registers the RAW events on-box (an operator-gated registration via
  `--allow-writes`, not a wire egress) so replay keeps full fidelity.

  Output:
    `{:variant-id <source>
      :play-snippet <string>
      :recorded-event-count <int>
      :duration-ms <actual ms blocked>
      :captured [<event-vec>]
      :written-back? <bool>
      :new-variant-id <new>?      ; only when write-back happened
     }`

  Errors:
    - Source `:variant-id` is not registered.
    - `:write-back` true but `allow-writes?` is false.
    - `:write-back` true and the underlying `reg-variant*` fails (shape
      validation, unknown extends, etc.).

  Filter layers are inherited from the recorder verbatim (op-type
  `:event/dispatched`, frame scope match, internal-namespace skip). The
  tool does not expose a free-form filter knob — the recorder owns that
  contract."
  [arguments]
  (targs/with-variant arguments
    (fn [vk body]
      (let [write-back? (args/parse-boolean (:write-back arguments) false)
            duration-ms (args/parse-non-negative-int (:duration-ms arguments) 0)]
        (or (when write-back? (write/assert-writes-allowed "record-as-variant"))
            (when (> duration-ms max-duration-ms)
              ;; rf2-4yuhi — the MCP server's request loop is single-
              ;; threaded; a `record-as-variant` call sleeps the loop
              ;; for the full window. Reject abusive durations rather
              ;; than stalling unrelated tool calls.
              (result/error-result
                (str ":duration-ms " duration-ms " exceeds ceiling "
                     max-duration-ms "ms. The MCP server's request "
                     "loop is single-threaded; a `record-as-variant` "
                     "call blocks unrelated tool calls for its full "
                     ":duration-ms window. Drive dispatches from your "
                     "own scheduler and shorten the window.")
                {:rf.error      :rf.story-mcp/duration-ms-too-large
                 :tool          "record-as-variant"
                 :duration-ms   duration-ms
                 :max-allowed   max-duration-ms}))
            ;; rf2-lqjbk: keyword resolution for the three caller-
            ;; supplied id slots.
            ;;
            ;; - `:extends` is a read-side reference — it MUST point to
            ;;   a registered variant whose `:component` / `:args` the
            ;;   snippet inherits. `safe-keyword` against the
            ;;   registered-variant set rejects unknowns without
            ;;   interning. Defaults to the source `vk` when omitted.
            ;;
            ;; - `:new-variant-id` is the write-back target. When
            ;;   `:write-back` is true we DO need a fresh keyword
            ;;   (the registrar's gate is `--allow-writes`; the
            ;;   operator chose to grow the registry). When
            ;;   `:write-back` is false the slot exists only for the
            ;;   rendered snippet's first-line `:variant-id` literal —
            ;;   we resolve the caller's string via `safe-keyword`
            ;;   against the live registered-variant set, so it renders
            ;;   only when it ALREADY names a registered variant. An
            ;;   unregistered suggested id falls back to the source `vk`
            ;;   (no intern) rather than minting a fresh keyword for what
            ;;   may be a one-shot agent suggestion.
            (let [extends     (if-let [e-arg (:extends arguments)]
                                (or (args/safe-keyword e-arg (story/ids :variant))
                                    ;; Reject unknown :extends so the snippet
                                    ;; doesn't render a dangling reference.
                                    nil)
                                vk)]
              (if (nil? extends)
                (result/error-result
                  (str ":extends references an unregistered variant: "
                       (pr-str (:extends arguments)))
                  {:rf.error :rf.story-mcp/extends-not-registered
                   :tool     "record-as-variant"
                   :extends  (:extends arguments)})
                (let [target-vid  (cond
                                    ;; write-back path: operator-gated
                                    ;; intern via fresh-keyword.
                                    (and write-back? (:new-variant-id arguments))
                                    (args/fresh-keyword (:new-variant-id arguments))

                                    ;; non-write-back: snippet-only,
                                    ;; safe-keyword against the live
                                    ;; variant set; otherwise default to
                                    ;; source vk rather than intern.
                                    (:new-variant-id arguments)
                                    (or (args/safe-keyword (:new-variant-id arguments)
                                                           (story/ids :variant))
                                        vk)

                                    :else vk)
                      doc         (:doc arguments)
                      alias-arg   (:alias arguments)
                      incl?       (targs/include-sensitive? arguments)
                      started     (now-ms)
                      _           (story/start-recording! vk)
                      _           (sleep-ms duration-ms)
                      final-state (story/stop-recording!)
                      events      (vec (:events final-state))
                      ;; rf2-12f2q — the captured event vectors cross the
                      ;; AI/off-box boundary in BOTH the `:captured` slot
                      ;; and the `:play-snippet` text. A recorded event can
                      ;; carry a declared-sensitive value in its payload (an
                      ;; auth token, a PII field dispatched into the canvas),
                      ;; so we value-redact the events against the source
                      ;; frame's declared-sensitive values before egress —
                      ;; the SAME value-based redaction the live tools apply
                      ;; to their derived trees. The snippet is then rendered
                      ;; FROM the scrubbed events, so the secret is absent
                      ;; from both wire slots in one place (no fragile text
                      ;; substitution). `:include-sensitive true` opts out
                      ;; (gated by --allow-sensitive-reads). The WRITE-BACK
                      ;; path below re-registers the RAW events on-box (an
                      ;; on-box registration the operator gated via
                      ;; --allow-writes, not a wire egress) so replay keeps
                      ;; full fidelity.
                      wire-events (egress/scrub-frame-value events vk incl?)
                      snippet     (story/gen-play-snippet
                                    wire-events
                                    (cond-> {:variant-id target-vid :extends extends}
                                      (string? doc)       (assoc :doc doc)
                                      (string? alias-arg) (assoc :alias alias-arg)))
                      base        {:variant-id           vk
                                   :play-snippet         snippet
                                   :recorded-event-count (count events)
                                   :duration-ms          (- (now-ms) started)
                                   :captured             wire-events
                                   :written-back?        false}]
                  (if-not write-back?
                    (result/edn-result base)
                    (write-back! base body events target-vid))))))))))

(def descriptors
  "Registry descriptors for the recorder's MCP surface — the single
  `record-as-variant` tool, presented as a vec so
  `tools.registry/tool-registry` can `into cat` recorder alongside
  every other category ns symmetrically. The tool is tail-of-write
  per IMPL-SPEC §7.3."
  [{:name           "record-as-variant"
    :category       :write
    :description    (str "Bridge the recorder's start → capture → snippet pipeline across the MCP boundary. Starts a recording against the source variant's frame, blocks for `:duration-ms`, stops, returns the `(reg-variant ...)` snippet `gen-play-snippet` emits. The captured event payloads (in both the `:captured` slot and the `:play-snippet` text) are value-redacted against the source frame's declared-sensitive values before egress (rf2-12f2q); pass `:include-sensitive true` to opt out (gated by --allow-sensitive-reads). Optional `:write-back` re-registers the variant with the captured recording translated to a live `:script` slot (the public phase-4 play surface) — GATED behind `:rf.story-mcp/allow-writes?` (same gate as `register-variant`); write-back re-registers the RAW events on-box for replay fidelity. Wire-key shape per rf2-pmwgn: input-schema property keys MUST omit the trailing `?` (Anthropic regex); the response key `:written-back?` is not bound by the same rule. "
                         "Examples: "
                         "1. Snippet-only record (no write-back): {:variant-id \":story.cart/full\" :duration-ms 2000} -> {:variant-id :story.cart/full :play-snippet \"(story/reg-variant :story.cart/full {:extends :story.cart/full :script {:auto-run? true :script [[:dispatch-sync [:cart/add ...]]]}})\" :recorded-event-count 4 :duration-ms 2012 :captured [[:cart/add ...]] :written-back? false}. "
                         "2. With write-back (gate must be open): {:variant-id \":story.cart/full\" :duration-ms 1000 :write-back true :new-variant-id \":story.cart/recorded\"} -> {... :written-back? true :new-variant-id :story.cart/recorded}. "
                         "3. Duration too long: {:variant-id \":story.cart/full\" :duration-ms 60000} -> {:isError true :content [{:text \":duration-ms 60000 exceeds ceiling 30000ms...\"}] :structuredContent {:rf.error :rf.story-mcp/duration-ms-too-large :duration-ms 60000 :max-allowed 30000}}.")
    :typicalTokens  1500
    ;; rf2-90eft — `record-as-variant` ships a `:captured` vector of
    ;; event tuples; when an agent records a repetitive interaction
    ;; (e.g. ten `:cart/add` events with the same SKU payload) the
    ;; argument maps repeat across records, and structural dedup
    ;; collapses them. Mirrors pair-mcp's selective `:dedup` knob on
    ;; epoch-vector surfaces (descriptors_data.cljs).
    :dedup-eligible? true
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-dedup
                                  (s/with-include-sensitive
                                  {:variant-id     s/kw-or-string
                                   :duration-ms    {:type "integer" :minimum 0 :maximum max-duration-ms
                                                    :description (str "Milliseconds to block between start and stop. Default 0. JVM-only (CLJS hosts no-op). "
                                                                      "Hard ceiling " max-duration-ms "ms — the MCP server's request loop is single-threaded so "
                                                                      "this call blocks unrelated tools for the full window; abusive durations are rejected (rf2-4yuhi).")}
                                   :new-variant-id (assoc s/kw-or-string
                                                     :description "When `:write-back` is true, register the captured recording (translated to a live `:script` body) under this id. Defaults to the source `:variant-id` (overwrites in place).")
                                   :doc            {:type "string"
                                                    :description "Optional docstring embedded in the rendered snippet."}
                                   :extends        (assoc s/kw-or-string
                                                     :description "Variant id embedded as `:extends` in the snippet. Defaults to the source `:variant-id`.")
                                   :alias          {:type "string"
                                                    :description "Short ns alias for the rendered form (default \"story\")."}
                                   :write-back     {:type "boolean"
                                                    :description (str "When true, also re-register the variant with the captured recording as a live `:script` body. Requires `allow-writes?`. "
                                                                      "Wire-key shape: no `?` per Anthropic's `^[a-zA-Z0-9_.-]{1,64}$` input-schema property-name regex (rf2-pmwgn).")}})))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/write-gated-output-schema
    :annotations  s/destructive-write-annotations
    :handler     tool-record-as-variant}])

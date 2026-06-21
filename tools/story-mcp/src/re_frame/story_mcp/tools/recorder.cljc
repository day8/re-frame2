(ns re-frame.story-mcp.tools.recorder
  "record-as-variant — the recorder's MCP surface.

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
  vocabulary). This is gated by the same `allow-writes?` flag as `register-variant`
  (`tools.write/assert-writes-allowed`). This is
  the self-healing-loop hook the spec mentions: agent drives canvas →
  tool returns snippet AND patches the variant in place.

  ## Wire-key shape

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
  "Ceiling on `:duration-ms` for `record-as-variant`. The
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

  The write-back assocs the PUBLIC `:script` authoring slot
  (spec/017 §Public vocabulary), not the `:play-script` spelling. (Both
  spellings store identically: `reg-variant*` lowers the
  public `:script` to the shipping `:play-script` slot via
  `schemas/lower-public-vocabulary`, so `variant->edn` of the stored body
  reads `:play-script` either way — `:script` is an author-facing
  intent, the stored shipping slot is `:play-script`. A `:play` key would
  pass the open variant `:map` validation but no runner executes it.) We translate the captured
  `events` via `story/recording->script-body` (the live runtime
  counterpart to `gen-play-snippet`'s text output) — which returns a
  `{:script … :auto-run?}` play body — and write that under `:script`.

  Stamps `:origin :story-mcp` per `spec/Cross-Cutting-Designs.md §5` —
  the write-back produces a new variant body and the origin tag
  identifies the MCP write surface as its producer.

  EP-0023: a recording's address is the source variant frame; replay
  dispatches frame-scoped and lands in the frame's own running environment
  by construction — the written-back body carries no separate realm key.

  EP-0017: the captured `:rf.cofx` maps (the parallel `cofx`
  vector, index-aligned with `events` — framework `:rf/time-ms` plus any
  provided recordable facts) are threaded into `recording->script-body`'s
  `:cofx` opt so each written-back dispatch step carries its recorded
  recordable-coeffect envelope (`[:dispatch evec {:rf.cofx …}]`). The
  RAW (unscrubbed) cofx is used here — the write-back is an operator-gated
  on-box registration (`--allow-writes`), not a wire egress, so replay keeps
  full fidelity. A recording with no captured coeffects threads an all-nil
  cofx vector, so the body carries a bare 2-element dispatch step.

  Returns the structured success result on the happy path, or an
  `error-result` whose `:structuredContent` merges the base recorder
  payload, the failure flag, and the registrar's `ex-data`."
  [base body events cofx target-vid]
  (try
    (let [play-body (story/recording->script-body events {:cofx cofx})
          ;; REPLACE any existing play surface before adding the
          ;; recorded `:script`. The source body may already carry a lowered
          ;; `:play-script` (a variant authored with public `:script` is
          ;; stored lowered) or `:plays`; the variant schema rejects a body
          ;; that carries more than one of `:script` / `:play-script` /
          ;; `:plays`, so `(assoc body :script …)` on such a source would
          ;; fail validation instead of overwriting the play body. Dropping
          ;; the prior surfaces first makes the recorded `:script` the sole
          ;; play surface (lowered back to `:play-script` by the registrar).
          registered (-> body
                         (dissoc :script :play-script :plays)
                         (assoc :script play-body :origin config/origin))
          id        (story/reg-variant* target-vid registered)
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
                              rejected with a structured error.
    :new-variant-id optional — when `:write-back` is true, register the
                              captured recording (translated to a live
                              `:script` body, the public phase-4 play
                              surface) as a NEW variant with
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
                              body (the public phase-4 play surface).
                              Requires `allow-writes?`
                              (same gate as `register-variant`).
                              Wire-key shape: no `?` —
                              Anthropic's input-schema property-name
                              regex rejects it.
    :include-sensitive optional — opt out of wire-egress redaction of the
                              captured event payloads (default false).
                              Gated by --allow-sensitive-reads.

  Wire-egress posture (EP-0025 fail-open): the captured event vectors cross
  the AI/off-box boundary in both the `:captured` slot and the `:play-snippet`
  text. They are PATH-projected against the source variant frame's
  classification before egress (the SAME PATH-based projection the live-state
  tools apply to their derived trees). EP-0025 removed value-match, so a
  declared-sensitive value in an event PAYLOAD (a re-keyed position) ships
  RAW — classify the app-db PATH to redact it. The WRITE-BACK path
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
              ;; The MCP server's request loop is single-threaded; a
              ;; `record-as-variant` call sleeps the loop for the full
              ;; window. Reject abusive durations rather than stalling
              ;; unrelated tool calls.
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
            ;; Keyword resolution for the three caller-supplied id slots.
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
                ;; In the write-back arm the caller mints a
                ;; FRESH variant id, so validate its `:story.<path>/<name>`
                ;; grammar on the STRING shape via `fresh-keyword-checked`
                ;; BEFORE interning. Validating the string before interning
                ;; means an invalid id neither grows the JVM keyword table
                ;; nor wastes a driven recording window. A nil here
                ;; (invalid grammar) short-circuits to an error before any
                ;; recording starts. The non-write-back arm keeps its
                ;; safe-keyword/default-to-vk resolution (no intern).
                (let [wb-target   (when (and write-back? (:new-variant-id arguments))
                                    (args/fresh-keyword-checked
                                      (:new-variant-id arguments)
                                      story/valid-variant-id?))]
                 (if (and write-back? (:new-variant-id arguments) (nil? wb-target))
                  (result/error-result
                    (str ":new-variant-id " (pr-str (:new-variant-id arguments))
                         " does not match the canonical variant-id grammar "
                         ":story.<path>/<name>.")
                    {:rf.error       :rf.error/variant-id-shape
                     :tool           "record-as-variant"
                     :new-variant-id (:new-variant-id arguments)})
                  (let [target-vid  (cond
                                    ;; write-back path: operator-gated
                                    ;; intern via the grammar-checked,
                                    ;; pre-validated id resolved above.
                                    (and write-back? (:new-variant-id arguments))
                                    wb-target

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
                      ;; EP-0017: the parallel captured-cofx slot,
                      ;; index-aligned with `:events` — each member is the flat
                      ;; `:rf.cofx` map (framework `:rf/time-ms` + any provided
                      ;; recordable facts) the dispatch carried, or nil. Threaded
                      ;; into the snippet + write-back so replay re-presents the
                      ;; recorded recordable coeffects rather than restamping or
                      ;; failing `:rf.error/missing-required-cofx`.
                      cofx        (vec (:cofx final-state))
                      ;; The captured event vectors cross the
                      ;; AI/off-box boundary in BOTH the `:captured` slot
                      ;; and the `:play-snippet` text. A recorded event can
                      ;; carry a declared-sensitive value in its payload (an
                      ;; auth token, a PII field dispatched into the canvas).
                      ;; We PATH-project the events against the source frame's
                      ;; declared-sensitive paths before egress — the SAME
                      ;; PATH-based projection the live tools apply to their
                      ;; derived trees. EP-0025 FAIL-OPEN: an event PAYLOAD is
                      ;; a re-keyed position the app-db path cannot reach, so a
                      ;; secret in it ships RAW — classify the app-db PATH to
                      ;; redact it before it is dispatched into an event. The
                      ;; snippet is rendered FROM the projected events.
                      ;; `:include-sensitive true` opts out (gated by
                      ;; --allow-sensitive-reads). The WRITE-BACK path below
                      ;; re-registers the RAW events on-box (an on-box
                      ;; registration the operator gated via --allow-writes,
                      ;; not a wire egress) so replay keeps full fidelity.
                      wire-events (egress/scrub-frame-value events vk incl?)
                      ;; EP-0017: the captured `:rf.cofx` maps are
                      ;; value-bearing and cross the wire in the rendered snippet
                      ;; (and, for parity with the events, conceptually in
                      ;; `:captured`). PATH-project each captured-cofx leaf the
                      ;; SAME way as the events. EP-0025 fail-open: a recordable
                      ;; fact in a re-keyed cofx position ships RAW — classify the
                      ;; app-db PATH to redact it. `:rf/time-ms` is an int and
                      ;; always safe to surface (EP-0017 §3). The WRITE-BACK path
                      ;; threads the RAW cofx on-box for replay fidelity.
                      wire-cofx   (mapv #(egress/scrub-frame-value % vk incl?) cofx)
                      snippet     (story/gen-play-snippet
                                    wire-events
                                    (cond-> {:variant-id target-vid :extends extends
                                             :cofx wire-cofx}
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
                    (write-back! base body events cofx target-vid))))))))))))

(def descriptors
  "Registry descriptors for the recorder's MCP surface — the single
  `record-as-variant` tool, presented as a vec so
  `tools.registry/tool-registry` can `into cat` recorder alongside
  every other category ns symmetrically. The tool is tail-of-write
  per IMPL-SPEC §7.3."
  [{:name           "record-as-variant"
    :category       :write
    :description    (str "Bridge the recorder's start → capture → snippet pipeline across the MCP boundary. Starts a recording against the source variant's frame, blocks for `:duration-ms`, stops, returns the `(reg-variant ...)` snippet `gen-play-snippet` emits. Each captured dispatch preserves its flat `:rf.cofx` envelope (the framework `:rf/time-ms` plus any provided recordable facts, EP-0017) so replay re-presents the recorded coeffects instead of restamping — a step with a recorded envelope renders as `[:dispatch evec {:rf.cofx …}]` (bare 2-element step when nothing was captured). The captured event payloads AND `:rf.cofx` values (in both the `:captured` slot and the `:play-snippet` text) are PATH-projected against the source frame's classification before egress (rf2-12f2q). EP-0025 FAIL-OPEN: a declared-sensitive value in an event/cofx PAYLOAD is a re-keyed position the app-db path cannot reach, so it ships RAW — classify the app-db PATH to redact it before it is dispatched. `:rf/time-ms` is always safe and surfaces verbatim; pass `:include-sensitive true` to opt out (gated by --allow-sensitive-reads). Optional `:write-back` re-registers the variant with the captured recording translated to a live `:script` slot (the public phase-4 play surface) — GATED behind `:rf.story-mcp/allow-writes?` (same gate as `register-variant`); write-back re-registers the RAW events + cofx on-box for replay fidelity. Wire-key shape per rf2-pmwgn: input-schema property keys MUST omit the trailing `?` (Anthropic regex); the response key `:written-back?` is not bound by the same rule. "
                         "Examples: "
                         "1. Snippet-only record (no write-back): {:variant-id \":story.cart/full\" :duration-ms 2000} -> {:variant-id :story.cart/full :play-snippet \"(story/reg-variant :story.cart/full {:extends :story.cart/full :script {:auto-run? true :script [[:dispatch-sync [:cart/add ...]]]}})\" :recorded-event-count 4 :duration-ms 2012 :captured [[:cart/add ...]] :written-back? false}. "
                         "2. With write-back (gate must be open): {:variant-id \":story.cart/full\" :duration-ms 1000 :write-back true :new-variant-id \":story.cart/recorded\"} -> {... :written-back? true :new-variant-id :story.cart/recorded}. "
                         "3. Duration too long: {:variant-id \":story.cart/full\" :duration-ms 60000} -> {:isError true :content [{:text \":duration-ms 60000 exceeds ceiling 30000ms...\"}] :structuredContent {:rf.error :rf.story-mcp/duration-ms-too-large :duration-ms 60000 :max-allowed 30000}}.")
    :typicalTokens  1500
    ;; `record-as-variant` ships a `:captured` vector of
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

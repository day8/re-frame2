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

  Optional `:write-back?` re-registers the source variant with the
  captured `:play` slot — gated by the same `allow-writes?` flag as
  `register-variant` (`tools.write/assert-writes-allowed`). This is
  the self-healing-loop hook the spec mentions: agent drives canvas →
  tool returns snippet AND patches the variant in place."
  (:require [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.tools.helpers :as h]
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
  "Re-register the target variant with the captured `:play` body.
  Preserves the source variant's existing body keys (so `:component`,
  `:args`, `:decorators` survive) and overwrites `:play` with the
  captured `events`. Stamps `:origin :story-mcp` per
  `spec/Cross-Cutting-Designs.md §5` — the write-back produces a new
  variant body and the origin tag identifies the MCP write surface as
  its producer.

  Returns the structured success result on the happy path, or an
  `error-result` whose `:structuredContent` merges the base recorder
  payload, the failure flag, and the registrar's `ex-data`."
  [base body events target-vid]
  (try
    (let [id      (story/reg-variant*
                    target-vid
                    (assoc body :play events :origin config/origin))
          payload (assoc base :written-back? true :new-variant-id id)]
      (h/text-result (h/pr-edn payload) payload))
    (catch #?(:clj Throwable :cljs :default) e
      (h/error-result (str "Write-back failed: " (ex-message e))
                      (merge base
                             {:written-back?  false
                              :new-variant-id target-vid}
                             (select-keys (ex-data e)
                                          [:rf.error :explain]))))))

(defn tool-record-as-variant
  "Dev (or Write when `:write-back?` is true): bridge the recorder's
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
    :new-variant-id optional — when `:write-back?` is true, register the
                              captured `:play` body as a NEW variant
                              with this id. Defaults to the source
                              `:variant-id` (overwrites in place).
    :doc           optional — docstring to embed in the snippet.
    :extends       optional — variant id to embed as the snippet's
                              `:extends` slot (defaults to the source
                              `:variant-id` — recording extends from the
                              canvas it ran against).
    :alias         optional — short ns alias in the rendered form
                              (default `\"story\"`).
    :write-back?   optional — when true, also re-register the variant
                              via `reg-variant*` with `:play <captured>`.
                              Requires `allow-writes?` (same gate as
                              `register-variant`).

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
    - `:write-back?` true but `allow-writes?` is false.
    - `:write-back?` true and the underlying `reg-variant*` fails (shape
      validation, unknown extends, etc.).

  Filter layers are inherited from the recorder verbatim (op-type
  `:event/dispatched`, frame scope match, internal-namespace skip). The
  tool does not expose a free-form filter knob — the recorder owns that
  contract."
  [arguments]
  (h/with-variant arguments
    (fn [vk body]
      (let [write-back? (args/parse-boolean (:write-back? arguments) false)
            duration-ms (args/parse-non-negative-int (:duration-ms arguments) 0)]
        (or (when write-back? (write/assert-writes-allowed "record-as-variant"))
            (when (> duration-ms max-duration-ms)
              ;; rf2-4yuhi — the MCP server's request loop is single-
              ;; threaded; a `record-as-variant` call sleeps the loop
              ;; for the full window. Reject abusive durations rather
              ;; than stalling unrelated tool calls.
              (h/error-result
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
            ;;   `:write-back?` is true we DO need a fresh keyword
            ;;   (the registrar's gate is `--allow-writes`; the
            ;;   operator chose to grow the registry). When
            ;;   `:write-back?` is false the slot exists only for the
            ;;   rendered snippet's first-line `:variant-id` literal —
            ;;   we render the caller's string as `:<string>` via
            ;;   `read-string` ONLY when the existing variant-id grammar
            ;;   admits it. If not, we fall back to the source `vk`.
            ;;   This avoids interning a JVM keyword for what may be a
            ;;   one-shot agent suggestion.
            (let [extends     (if-let [e-arg (:extends arguments)]
                                (or (args/safe-keyword e-arg (story/ids :variant))
                                    ;; Reject unknown :extends so the snippet
                                    ;; doesn't render a dangling reference.
                                    nil)
                                vk)]
              (if (nil? extends)
                (h/error-result
                  (str ":extends references an unregistered variant: "
                       (pr-str (:extends arguments)))
                  {:rf.error :rf.story-mcp/extends-not-registered
                   :tool     "record-as-variant"
                   :extends  (:extends arguments)})
                (let [target-vid  (cond
                                    ;; write-back path: operator-gated
                                    ;; intern via parse-keyword.
                                    (and write-back? (:new-variant-id arguments))
                                    (args/parse-keyword (:new-variant-id arguments))

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
                      started     (now-ms)
                      _           (story/start-recording! vk)
                      _           (sleep-ms duration-ms)
                      final-state (story/stop-recording!)
                      events      (vec (:events final-state))
                      snippet     (story/gen-play-snippet
                                    events
                                    (cond-> {:variant-id target-vid :extends extends}
                                      (string? doc)       (assoc :doc doc)
                                      (string? alias-arg) (assoc :alias alias-arg)))
                      base        {:variant-id           vk
                                   :play-snippet         snippet
                                   :recorded-event-count (count events)
                                   :duration-ms          (- (now-ms) started)
                                   :captured             events
                                   :written-back?        false}]
                  (if-not write-back?
                    (h/text-result (h/pr-edn base) base)
                    (write-back! base body events target-vid))))))))))

(def descriptors
  "Registry descriptors for the recorder's MCP surface — the single
  `record-as-variant` tool, presented as a vec so
  `tools.registry/tool-registry` can `into cat` recorder alongside
  every other category ns symmetrically. The tool is tail-of-write
  per IMPL-SPEC §7.3."
  [{:name           "record-as-variant"
    :category       :write
    :description    "Bridge the recorder's start → capture → snippet pipeline across the MCP boundary. Starts a recording against the source variant's frame, blocks for `:duration-ms`, stops, returns the `(reg-variant ...)` snippet `gen-play-snippet` emits. Optional `:write-back?` re-registers the variant with the captured `:play` slot — GATED behind `:rf.story-mcp/allow-writes?` (same gate as `register-variant`)."
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                {:variant-id     s/kw-or-string
                                 :duration-ms    {:type "integer" :minimum 0 :maximum max-duration-ms
                                                  :description (str "Milliseconds to block between start and stop. Default 0. JVM-only (CLJS hosts no-op). "
                                                                    "Hard ceiling " max-duration-ms "ms — the MCP server's request loop is single-threaded so "
                                                                    "this call blocks unrelated tools for the full window; abusive durations are rejected (rf2-4yuhi).")}
                                 :new-variant-id (assoc s/kw-or-string
                                                   :description "When `:write-back?` is true, register the captured `:play` body under this id. Defaults to the source `:variant-id` (overwrites in place).")
                                 :doc            {:type "string"
                                                  :description "Optional docstring embedded in the rendered snippet."}
                                 :extends        (assoc s/kw-or-string
                                                   :description "Variant id embedded as `:extends` in the snippet. Defaults to the source `:variant-id`.")
                                 :alias          {:type "string"
                                                  :description "Short ns alias for the rendered form (default \"story\")."}
                                 :write-back?    {:type "boolean"
                                                  :description "When true, also re-register the variant with the captured `:play`. Requires `allow-writes?`."}})
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-record-as-variant}])

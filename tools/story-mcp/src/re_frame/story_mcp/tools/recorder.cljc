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
            gate-err    (when write-back? (write/assert-writes-allowed))]
        (if gate-err gate-err
          (let [duration-ms (args/parse-non-negative-int (:duration-ms arguments) 0)
                new-vid     (some-> (:new-variant-id arguments) args/parse-keyword)
                target-vid  (or new-vid vk)
                doc         (:doc arguments)
                extends     (or (some-> (:extends arguments) args/parse-keyword) vk)
                alias-arg   (:alias arguments)
                started     (now-ms)
                _           (story/start-recording! vk)
                _           (sleep-ms duration-ms)
                final-state (story/stop-recording!)
                actual-ms   (- (now-ms) started)
                events      (vec (:events final-state))
                snippet-opts (cond-> {:variant-id target-vid
                                      :extends    extends}
                               (string? doc)       (assoc :doc doc)
                               (string? alias-arg) (assoc :alias alias-arg))
                snippet     (story/gen-play-snippet events snippet-opts)
                base-payload {:variant-id           vk
                              :play-snippet         snippet
                              :recorded-event-count (count events)
                              :duration-ms          actual-ms
                              :captured             events
                              :written-back?        false}]
            (if-not write-back?
              (h/text-result (h/pr-edn base-payload) base-payload)
              ;; Write-back: re-register the target variant with the
              ;; captured :play body. We preserve the source variant's
              ;; existing body keys (so :component, :args, :decorators
              ;; survive) and overwrite :play with the captured events.
              ;; Stamp `:origin :story-mcp` per
              ;; spec/Cross-Cutting-Designs.md §5 — the write-back
              ;; produces a new variant body, and the origin tag
              ;; identifies the MCP write surface as its producer.
              (try
                (let [new-body (-> body
                                   (assoc :play events)
                                   (assoc :origin config/origin))
                      id       (story/reg-variant* target-vid new-body)
                      payload  (assoc base-payload
                                      :written-back?   true
                                      :new-variant-id  id)]
                  (h/text-result (h/pr-edn payload) payload))
                (catch #?(:clj Throwable :cljs :default) e
                  (h/error-result (str "Write-back failed: " (ex-message e))
                                  (merge base-payload
                                         {:written-back? false
                                          :new-variant-id target-vid}
                                         (select-keys (ex-data e)
                                                      [:rf.error :explain]))))))))))))

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
                                 :duration-ms    {:type "integer" :minimum 0
                                                  :description "Milliseconds to block between start and stop. Default 0. JVM-only (CLJS hosts no-op)."}
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

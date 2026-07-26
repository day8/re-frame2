(ns re-frame.story-mcp.tools.result
  "Result-envelope builders for MCP `tools/call` responses.

  `text-result` and `error-result` shape the MCP `tools/call` response
  envelope. The success / error split matches MCP §Error Handling — an
  error in the tool's domain (variant not found, gate denied, …)
  surfaces as `:isError true` content rather than a JSON-RPC protocol
  error so the agent can show it to the LLM without aborting the
  conversation.

  `pr-edn` is the canonical EDN-stable stringifier for embedding Story
  data inside an MCP text content item.

  `wire-safe-ex-data` is the one projection every handler that relays a
  caught exception's `ex-data` onto `:structuredContent` runs it through.

  No story-mcp ns deps — `cap` / `cursor` / `args` / `egress`
  / every category ns reaches here. `malli.error` is the sole library
  require, reached transitively through `re-frame.story` (whose registrar
  hard-requires `malli.core`), exactly as `re-frame.error` is."
  (:require [malli.error :as me]))

(defn pr-edn
  "Serialise a value to a stable, EDN-round-trippable string. Used to
  embed Story data inside an MCP text content item. Uses `pr-str` —
  keywords stay keywords, sets stay sets, no JSON lossiness."
  [v]
  (binding [*print-readably* true]
    (pr-str v)))

(defn wire-safe-ex-data
  "Project a caught exception's `ex-data` into something the JSON encoder
  can actually write, for the handlers that relay it onto
  `:structuredContent`.

  ONE slot needs projecting. `re-frame.story.registrar/validate-shape!`
  puts the raw Malli `explain` map in `ex-data`, and its `:schema`
  entries are LIVE reified `malli.core/Schema` objects, not data. Cheshire
  cannot encode them, so relaying `:explain` verbatim made
  `protocol/write-frame!` throw — past the tool handler, into
  `server/handle-frame!`, which answered a protocol-level `-32603`
  \"Server fault: Cannot JSON encode object of class:
  malli.core$_and_schema$…\". The tool's own `isError: true` contract was
  bypassed and the actionable message (which names the offending key and
  the nearest declared slot) never left the JVM — on the single commonest
  authoring mistake an agent makes through the write surface, a typo'd
  variant slot (rf2-2z9u3).

  So `:explain` is replaced by `:explain-humanized`, the
  `malli.error/humanize` projection: plain maps, keywords and strings,
  keyed by the failing slot (`{:compnent [\"disallowed key\"]}`), and
  carrying the schema's own `:error/message` prose for the mutual-
  exclusion `:fn` clauses. `:explain-humanized` is not a new word — it is
  the slot `spec/010-Schemas.md` §humanize hook already defines for
  exactly this value, and consumers there already read it in preference
  to raw `:explain`. Renaming rather than adding also keeps the write
  surface's error slot from colliding with `explain-variant`'s unrelated
  plan-`:explain` projection.

  The OTHER slot needs renaming, not projecting. A thrown error's
  machine-readable discriminator is `:rf.error/id` — the canonical
  thrown-error shape of `spec/009-Instrumentation.md`, which is what
  `re-frame.story.registrar` actually throws. But story-mcp's own wire
  vocabulary for that same fact is the BARE `:rf.error` key, the one
  `Principles.md` §Error envelopes names as the error payload's
  discriminator and every tool-EMITTED error already sets. Relaying the
  thrown spelling verbatim would put a SECOND word for one fact on the
  wire, so the thrown key is renamed to the wire key here — the same
  in-vocabulary move `:explain` → `:explain-humanized` makes just above,
  and the reason both live in one function: this is where ex-data
  vocabulary becomes wire vocabulary (rf2-2nbck).

  Every other `ex-data` slot rides through untouched — `:reason`,
  `:where`, `:recovery`, `:kind`, `:id` are all plain data."
  [d]
  (cond-> d
    (:explain d)      (-> (dissoc :explain)
                          (assoc :explain-humanized (me/humanize (:explain d))))
    (:rf.error/id d)  (-> (dissoc :rf.error/id)
                          (assoc :rf.error (:rf.error/id d)))))

(defn text-result
  "Build a success result with a single text content item. `structured`
  (optional) lands on the `structuredContent` slot per the spec/2025-06-18
  tools §Structured content guidance — agent clients that prefer JSON
  data over text can read it directly without re-parsing."
  ([text]
   {:content [{:type "text" :text text}]})
  ([text structured]
   (cond-> {:content [{:type "text" :text text}]}
     (some? structured) (assoc :structuredContent structured))))

(defn edn-result
  "Build a success result whose `payload` rides BOTH wire slots: the
  `pr-edn`-stringified EDN in the `:content` text slot and the raw map
  in `:structuredContent`. The canonical success envelope for every
  data-returning story-mcp handler — `(edn-result payload)` is the dual-
  coded `(text-result (pr-edn payload) payload)` pair every handler
  shares, named once so each handler reads as 'return this payload'
  rather than re-spelling the
  stringify-into-text-plus-same-map-as-structured dance.

  The two slots are dual-coded on purpose (per `wire-pipeline`): the
  text slot serves agent hosts that read EDN; the structured slot
  serves hosts that prefer JSON data — and the cap pipeline sizes both."
  [payload]
  (text-result (pr-edn payload) payload))

(defn error-result
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

(def capability-unavailable-error-id
  "Stable machine-readable error id for a browser-only Story-MCP read
  whose backing provider is UNREACHABLE from this host (the JVM stdio
  server has no bridge to the CLJS browser registry / panel state). It
  distinguishes 'this host cannot answer' from a reached provider that
  answered EMPTY — an agent MUST NOT read the latter's `[]`/`#{}` as this,
  nor this as an empty inventory (rf2-3fc89f.21)."
  :rf.error/story-mcp-capability-unavailable)

(defn capability-unavailable-result
  "The ONE capability-unavailable error result the browser-only reads
  route an ABSENT provider through — `list-substrates` /
  `read-a11y-violations` when their provider is unreachable, and the
  `:substrate` validation when an explicit render substrate is requested
  with no substrate registry reachable.

  Reserved for provider ABSENCE. A reached-but-empty provider returns
  ordinary success with `[]`/`#{}`, never this — that is the whole point
  of the boundary: capability absence is not answered emptiness.

  The result is `isError: true` with a `:structuredContent` carrying the
  stable `:rf.error/story-mcp-capability-unavailable` id, the affected
  `:capability` + `:tool` names, and an actionable `:recovery` hint so an
  agent can localise the miss rather than trust a false-empty read.

    :tool       — the MCP tool name(s) that tripped the boundary.
    :capability — the browser-only capability that is unreachable
                  (`\"substrate-registry\"` / `\"a11y-panel-state\"`).
    :detail     — optional extra sentence appended to the message."
  [{:keys [tool capability detail]}]
  (error-result
    (str "Capability unavailable: `" tool "` needs the " capability
         " provider, which this host cannot reach. "
         (when detail (str detail " "))
         "The shipped Story-MCP entry point is a JVM stdio server with no "
         "bridge to the CLJS browser registry / panel state, so it cannot "
         "answer this browser-only read. This is NOT an empty result — the "
         "host never looked. Run the read from a browser-local Story host "
         "(or wait for the live browser bridge).")
    {:rf.error   capability-unavailable-error-id
     :capability capability
     :tool       tool
     :recovery   :read-from-a-browser-local-story-host}))

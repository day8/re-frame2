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

  No external story-mcp deps — `cap` / `cursor` / `args` / `egress`
  / every category ns reaches here.")

(defn pr-edn
  "Serialise a value to a stable, EDN-round-trippable string. Used to
  embed Story data inside an MCP text content item. Uses `pr-str` —
  keywords stay keywords, sets stay sets, no JSON lossiness."
  [v]
  (binding [*print-readably* true]
    (pr-str v)))

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

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
  It is TOTAL: whatever a handler threw, the projection's output can be
  JSON-encoded.

  `wire-safe-success` is its counterpart for ordinary SUCCESS data, and
  `edn-result` — the envelope every data-returning handler builds — applies
  it. Success data is not exception data and the two projections are not
  interchangeable: see `wire-safe-success`.

  No story-mcp ns deps — `cap` / `cursor` / `args` / `egress`
  / every category ns reaches here. `malli.error` is the sole third-party
  require (`clojure.walk` is stdlib), reached transitively through
  `re-frame.story` (whose registrar hard-requires `malli.core`), exactly as
  `re-frame.error` is."
  (:require [clojure.walk :as walk]
            [malli.error :as me]))

(defn pr-edn
  "Serialise a value to a stable, EDN-round-trippable string. Used to
  embed Story data inside an MCP text content item. Uses `pr-str` —
  keywords stay keywords, sets stay sets, no JSON lossiness.

  Round-trippable because `edn-result` projects the payload through
  `wire-safe-success` FIRST. Given a raw callable — which a supported
  `:pred` assertion puts in an author's body — `pr-str` emits an
  `#object[…]` form that is not readable EDN at all; the projection has
  already replaced it with the readable `{:rf.story-mcp/unencodable …}`
  marker. The string round-trips; the callable, honestly, does not."
  [v]
  (binding [*print-readably* true]
    (pr-str v)))

(def ^:private unencodable-key
  "Wire key of the bounded stand-in `wire-safe-value` puts where a value
  the encoder cannot write used to be. A member of story-mcp's own
  `:rf.story-mcp/*` vocabulary, alongside
  `:rf.story-mcp/unknown-arguments` — not an `:rf.error/*` id, because it
  labels a VALUE that was withheld, not a failure that occurred."
  :rf.story-mcp/unencodable)

(defn- type-name
  "The class/type NAME of `v`, and nothing else. Never `str`/`pr-str` of
  the value itself: a bare `Object`'s printed form is
  `java.lang.Object@2c6aed22`, and that identity-hash suffix is a build-
  local address the wire has no business carrying."
  [v]
  #?(:clj  (.getName (class v))
     :cljs (pr-str (type v))))

(defn- edn-scalar?
  "Is `v` one of the EDN scalars — nil, a boolean, a number, a string, a
  keyword, a symbol, a character, a UUID or an instant (the last two being
  EDN's built-in tagged literals)?

  The closed set the two projections below share, so they cannot drift on
  what counts as data. Everything outside it that is not a collection is a
  live object the JSON encoder cannot write."
  [v]
  (or (nil? v) (boolean? v) (number? v) (string? v)
      (keyword? v) (symbol? v) (char? v) (uuid? v) (inst? v)))

(defn- wire-safe-value
  "Project any value into the EDN value space, which is the shape the JSON
  encoder can write.

  A value survives when it IS data: an `edn-scalar?`, or a collection of
  values that survive. Everything else is a live object: a
  reified Malli schema, a `Throwable`, an atom, a regex, a function, a bare
  `Object`. Those become `{:rf.story-mcp/unencodable \"<class name>\"}`,
  which says both that a value was there and what kind it was.

  Map KEYS go through the same projection, and for the same reason the
  values do: Cheshire will happily `str` a non-scalar key, so an opaque
  key does not throw — it leaks `\"java.lang.Object@2c6aed22\"` as a JSON
  member name instead, which is the worse failure of the two because
  nothing goes red.

  This is a rule about SHAPE, deliberately not a roster of slots. The
  first fix for this defect named `:explain`, the one slot whose value was
  known to be un-encodable; the next opaque value simply arrived under a
  different key (rf2-ia904). A named-slot list is a roster that rots, so
  the default handles the shape and the named slots below are left to do
  only what naming is actually for — improving a projection, not rescuing
  one."
  [v]
  (cond
    (edn-scalar? v) v

    (map? v)        (reduce-kv (fn [m k x]
                                 (assoc m (wire-safe-value k) (wire-safe-value x)))
                               {} v)
    (set? v)        (into #{} (map wire-safe-value) v)
    (sequential? v) (mapv wire-safe-value v)
    :else           {unencodable-key (type-name v)}))

(defn- rebuild-record
  "`record` with each entry replaced by `(f entry)`, keeping the record's
  type. A copy of the private `rebuild-record` in `re-frame.mcp-base.dedup`,
  which solved the same problem for the dedup walk (rf2-gwye.31).

  A record has no `empty`, so its entries are written back into the
  original, and an extension key that `f` respells must therefore REPLACE
  its original or both spellings survive. A fixed field's key is a keyword,
  which the projection never respells, so the `dissoc` only ever meets
  extension keys and the record keeps its type. Every entry is rebuilt
  before any is written, so a new spelling that matches another entry's old
  one cannot overwrite that entry before it is read."
  [f record]
  (let [rebuilt (mapv (fn [entry] [(key entry) (f entry)]) record)]
    (reduce (fn [r [_ entry]] (conj r entry))
            (reduce (fn [r [k [k' _]]] (if (= k k') r (dissoc r k)))
                    record
                    rebuilt)
            rebuilt)))

(defn wire-safe-success
  "Project ORDINARY SUCCESS data so the JSON encoder can write it, leaving
  everything that already IS data exactly as it was.

  ## Why success data needs its own projection at all (rf2-gwye.58)

  `[:assert-db path :pred fn-or-sym]` is a SUPPORTED Story authoring form
  (`tools/story/spec/001-Authoring.md` §Script steps), so a live function
  legitimately sits in a registered variant body — and from there in the
  body `get-variant` returns, the plan `explain-variant` returns, and the
  assertion evidence a RUN returns. None of that is exception data, so the
  `wire-safe-ex-data` path never saw it, and a function reaching Cheshire
  throws PAST the tool handler into `server/handle-frame!`, which answers
  `-32603 Server fault: Cannot JSON encode object of class: class
  clojure.core$pos_QMARK_`. Four tools failed that way on a variant Story
  itself runs `:pass` — the verdict, the evidence and the whole surrounding
  body discarded over one slot.

  ## The rule: mark the value, keep everything around it

  A callable cannot be reconstructed from JSON or from printed EDN, and
  pretending otherwise is the lie this replaces: `pr-str` of a function
  emits `#object[clojure.core$pos_QMARK_ 0x4d1b0d2a …]`, which is neither
  readable EDN nor free of a build-local address. So the value is replaced —
  in place — by the same bounded `{:rf.story-mcp/unencodable \"<class
  name>\"}` marker `wire-safe-value` already uses: it says a value was
  there, and what kind it was, without an identity hash. Everything else in
  the payload survives untouched.

  ## Not `wire-safe-ex-data`, and not a reshaping walk

  Two differences, both deliberate:

  - `wire-safe-ex-data` RENAMES `:explain` and `:rf.error/id`. Those are
    exception vocabulary; on author data they are ordinary keys somebody
    wrote, and rewriting them would corrupt the very body the author asked
    to read back.
  - this walk is TYPE-PRESERVING. `wire-safe-value` rebuilds every
    collection through `{}` / `#{}` / `mapv`, which is right for an
    `ex-data` blob but would quietly turn a list into a vector, a sorted map
    into a hash map, and a record into a plain map — in a payload whose text
    slot is the byte-stable EDN an agent DIFFS. The walk rebuilds each
    collection as its own type (and visits map keys), so a payload carrying
    no callable comes back equal to what went in.

  ## Records: a respelled key REPLACES its original (rf2-8m1i)

  The walk is `clojure.walk/walk` everywhere except a record, which
  `rebuild-record` handles. `clojure.walk` rebuilds a record by `conj`-ing
  the walked entries back into the ORIGINAL record, so an extension key the
  projection respells — `(assoc (->Sample pos?) pos? :extension)` — gained
  its marker while the raw `pos?` key stayed too, and Cheshire wrote that
  key as its identity string. Every other slot of that record was projected
  correctly, which is why the leak was easy to miss."
  [v]
  (letfn [(project [x]
            (if (or (edn-scalar? x) (coll? x))
              x
              {unencodable-key (type-name x)}))
          (walk-success [form]
            (project (if (record? form)
                       (rebuild-record walk-success form)
                       (walk/walk walk-success identity form))))]
    (walk-success v)))

(defn wire-safe-ex-data
  "Project a caught exception's `ex-data` into something the JSON encoder
  can actually write, for the handlers that relay it onto
  `:structuredContent`.

  TOTALITY IS THE SHAPE RULE, not a list of slots. `wire-safe-value`
  above rewrites every value that is not EDN data into a bounded marker
  naming its class, so an `ex-data` carrying anything at all — under any
  key, at any depth, as a key — relays. That is the whole guarantee, and
  it is what makes `invoke-tool`'s belt-and-braces catch mean what it
  says: a handler exception becomes an `isError: true` tool result, never
  a protocol-level `-32603`.

  The history is worth keeping, because it is why the guarantee is stated
  as a shape. `re-frame.story.registrar/validate-shape!` puts the raw
  Malli `explain` map in `ex-data`, and its `:schema` entries are LIVE
  reified `malli.core/Schema` objects. Relaying `:explain` verbatim made
  `protocol/write-frame!` throw — past the tool handler, into
  `server/handle-frame!`, which answered \"Server fault: Cannot JSON
  encode object of class: malli.core$_and_schema$…\". The tool's own
  `isError: true` contract was bypassed and the actionable message (which
  names the offending key and the nearest declared slot) never left the
  JVM — on the single commonest authoring mistake an agent makes through
  the write surface, a typo'd variant slot (rf2-2z9u3). The fix named
  `:explain`. The generic arm then failed identically for `{:opaque
  (Object.)}`, because one named slot is not a rule (rf2-ia904).

  TWO SLOTS ARE STILL NAMED, and neither is load-bearing for encodability
  any more — the walk would render both safe on its own. They are named
  because a good projection beats a marker:

  `:explain` is replaced by `:explain-humanized`, the
  `malli.error/humanize` projection: plain maps, keywords and strings,
  keyed by the failing slot (`{:compnent [\"disallowed key\"]}`), and
  carrying the schema's own `:error/message` prose for the mutual-
  exclusion `:fn` clauses. Left to the walk it would survive as a tree of
  markers — encodable, and useless. `:explain-humanized` is not a new
  word: it is the slot `spec/010-Schemas.md` §humanize hook already
  defines for exactly this value, and consumers there already read it in
  preference to raw `:explain`. Renaming rather than adding also keeps
  the write surface's error slot from colliding with `explain-variant`'s
  unrelated plan-`:explain` projection.

  `:rf.error/id` is renamed to `:rf.error` — a vocabulary move, never an
  encodability one. A thrown error's machine-readable discriminator is
  `:rf.error/id`, the canonical thrown-error shape of
  `spec/009-Instrumentation.md`, which is what `re-frame.story.registrar`
  actually throws. But story-mcp's own wire vocabulary for that same fact
  is the BARE `:rf.error` key, the one `Principles.md` §Error envelopes
  names as the error payload's discriminator and every tool-EMITTED error
  already sets. Relaying the thrown spelling verbatim would put a SECOND
  word for one fact on the wire (rf2-2nbck). This is where ex-data
  vocabulary becomes wire vocabulary.

  Every other slot rides through as itself — `:reason`, `:where`,
  `:recovery`, `:kind`, `:id` are all plain data and the walk returns
  them unchanged.

  The projection cannot itself escape the caller's catch. `me/humanize`
  runs on a value the registrar built, the walk recurses to whatever
  depth `ex-data` nests, and neither is this function's to trust, so both
  sit under a guard whose fallback is the same bounded marker."
  [d]
  (try
    (wire-safe-value
      (cond-> d
        (:explain d)      (-> (dissoc :explain)
                              (assoc :explain-humanized (me/humanize (:explain d))))
        (:rf.error/id d)  (-> (dissoc :rf.error/id)
                              (assoc :rf.error (:rf.error/id d)))))
    (catch #?(:clj Throwable :cljs :default) e
      {unencodable-key (type-name e)
       :reason         "ex-data projection failed"})))

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
  serves hosts that prefer JSON data — and the cap pipeline sizes both.

  ## The one success projection (rf2-gwye.58)

  `wire-safe-success` runs HERE, once, before either slot is built — so the
  two slots cannot disagree about a value, and the projection lands ahead of
  the wire-boundary transforms (dedup, then the token cap) which run after
  the handler returns. This is the chokepoint every data-returning handler
  already shares, which is why the projection needs no per-handler opt-in
  and no roster of which tools might carry a callable."
  [payload]
  (let [payload (wire-safe-success payload)]
    (text-result (pr-edn payload) payload)))

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

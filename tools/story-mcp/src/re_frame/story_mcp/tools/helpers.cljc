(ns re-frame.story-mcp.tools.helpers
  "Shared helpers for the MCP tool handlers — result builders, arg
  coercers, wire-egress scrubbers. Every category ns
  (`dev`, `docs`, `testing`, `write`) and the `registry` ns reaches
  here.

  ## Result builders

  `text-result` and `error-result` shape the MCP `tools/call` response
  envelope. The success / error split matches MCP §Error Handling — an
  error in the tool's domain (variant not found, gate denied, …)
  surfaces as `:isError true` content rather than a JSON-RPC protocol
  error so the agent can show it to the LLM without aborting the
  conversation.

  ## Args helpers

  The keyword / boolean / int parsers themselves live in
  `re-frame.mcp-base.args` — one canonical implementation per the
  cross-MCP factoring (rf2-vw4sq). This ns adds the story-MCP-specific
  shapes: `required-arg`, `with-variant` (the four-line variant-id
  prelude shared by six handlers — see rf2-f0zxa), and
  `with-variant-id` (the same shape minus the body probe, for tools
  that tolerate unregistered variants).

  ## Wire-egress scrubbers (rf2-73wuj)

  `elide-app-db` and `scrub-assertions` apply the cross-MCP
  privacy-posture rules to every live `:app-db` slice and assertion
  accumulator before egress. Off-box defaults (declared-sensitive paths
  return `:rf/redacted`; assertion records stamped `:sensitive? true`
  are dropped). The shared `:include-sensitive?` arg is the documented
  opt-in escape hatch."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.mcp-base.args :as args]
            [re-frame.mcp-base.sensitive :as sensitive]
            [re-frame.story :as story]
            [re-frame.story-mcp.config :as config]))

;; ---------------------------------------------------------------------------
;; Result builders
;; ---------------------------------------------------------------------------

(defn pr-edn
  "Serialise a value to a stable, EDN-round-trippable string. Used to
  embed Story data inside an MCP text content item. Uses `pr-str` —
  keywords stay keywords, sets stay sets, no JSON lossiness."
  [v]
  (binding [*print-readably* true]
    (pr-str v)))

;; ---------------------------------------------------------------------------
;; Cross-platform var resolution
;; ---------------------------------------------------------------------------

(defn resolve-cljs-var
  "Resolve a fully-qualified symbol (`ns/sym`) to the underlying var
  on the JVM, returning `nil` on miss. Wraps `clojure.core/resolve` in
  a try/catch so a CLJS-only `def` (whose ns hasn't been required on
  JVM) yields nil rather than blowing up.

  Used by handlers that need a CLJS-side surface (the in-browser a11y
  panel atom, the CLJS substrate registry) — the JVM-standalone deploy
  reads an empty surface, and that's the documented correct answer."
  [sym]
  (try (resolve sym) (catch Throwable _ nil)))

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

;; ---------------------------------------------------------------------------
;; Args helpers
;; ---------------------------------------------------------------------------

(defn include-sensitive?
  "True iff the caller opted in to forwarding `:sensitive? true` records
  / app-db slots AND the operator has opened the server-side gate.
  Default off. Reads the `:include-sensitive?` arg via the cross-MCP
  `args/parse-boolean` parser (so string-form booleans `\"true\"` /
  `\"yes\"` / `\"1\"` are accepted alongside the JSON `true`).

  ## Boot-time gate (rf2-g9fje)

  The operator-only gate `config/sensitive-reads-allowed?` (set by
  `--allow-sensitive-reads`, mirroring pair2-mcp's `--allow-eval`) is
  the outer check: when it is `false`, this fn always returns `false`,
  the per-call `:include-sensitive?` arg is silently ignored, and
  declared-sensitive `:app-db` slots / assertion records remain
  redacted regardless of what the caller asked for. The MCP caller
  surface (`tools/list`) likewise omits `:include-sensitive?` from
  the descriptor schemas when the gate is closed (see `schemas/
  with-include-sensitive`)."
  [arguments]
  (and (config/sensitive-reads-allowed?)
       (args/parse-boolean (get arguments :include-sensitive?) false)))

(defn required-arg
  "Read a required argument. Returns `[value nil]` on success, or
  `[nil error-result]` on miss."
  [arguments k]
  (let [v (get arguments k)]
    (if (or (nil? v) (and (string? v) (str/blank? v)))
      [nil (error-result (str "Missing required argument: " (name k)))]
      [v nil])))

;; ---------------------------------------------------------------------------
;; Bounded-allowlist keyword resolution (rf2-lqjbk)
;; ---------------------------------------------------------------------------
;;
;; A naive `(keyword raw-agent-string)` INTERNS on the JVM — a caller
;; streaming unique random strings as `:variant-id`s would slowly grow
;; the JVM's never-shrinking keyword table. The mitigation is to resolve
;; every agent-supplied keyword id against a bounded set BEFORE coercing
;; it to a keyword, using `args/safe-keyword` (which routes through
;; `find-keyword` on the JVM — no intern outside the allowlist).
;;
;; For READ-side handlers (every tool in dev / docs / testing) the
;; bounded set IS the live registry's id set; an id outside the registry
;; can't possibly resolve to a registered handler anyway, so the strict
;; gate is equivalent to "reject what the lookup would have missed
;; anyway". The fns below take the registry-key fn as data so each
;; helper site doesn't repeat the bind.
;;
;; The WRITE-side handlers (`register-variant`) are the documented
;; exception: by design they extend the registry with a fresh keyword.
;; Those callers intern via `args/fresh-keyword` directly, bounded by
;; the operator-gated `--allow-writes` flag (the registry itself is the
;; bounded set; the operator chose to open it).

(defn- variant-id-set
  "Snapshot of registered variant ids — the bounded allowlist used by
  `with-variant` / `with-variant-id` / `read-run-opts`. Captured per
  call so a registration that lands between two read tools is reflected
  on the second read (the registry is logically a mutable atom; we
  re-snapshot rather than cache)."
  []
  (story/ids :variant))

(defn read-run-opts
  "Build the `re-frame.story/run-variant` opts map from the standard MCP
  arg slots (`:substrate`, `:active-modes`, `:cell-overrides`). Each
  slot lands only when present, so the resulting map is the minimal
  shape `run-variant` / `snapshot-identity` / `variant-share-url`
  expect.

  Shared by `tool-preview-variant`, `tool-run-variant`, and
  `tool-snapshot-identity` (the latter omits `:cell-overrides`, but
  the absent-slot-not-present rule keeps the helper general).

  rf2-lqjbk: `:substrate` and each entry in `:active-modes` are
  coerced through `args/safe-keyword` against the live registry's
  bounded set — an unrecognised id surfaces as `nil` (which
  `run-variant`'s opts contract already tolerates as 'no override')
  rather than as a freshly-interned keyword. The substrates set is
  CLJS-only (the JVM-standalone deploy reads `nil`); the modes set
  is the registered-mode id set."
  [arguments]
  (let [substrate-set #?(:clj  (if-let [v (resolve-cljs-var 'story/registered-substrates)]
                                 (try (set (v)) (catch Throwable _ #{}))
                                 #{})
                         :cljs (try (set (story/registered-substrates))
                                    (catch :default _ #{})))
        mode-set      (story/list-modes)]
    (cond-> {}
      (some? (:substrate arguments))
      (assoc :substrate (args/safe-keyword (:substrate arguments) substrate-set))

      (some? (:active-modes arguments))
      (assoc :active-modes
             (into []
                   (keep #(args/safe-keyword % mode-set))
                   (:active-modes arguments)))

      (some? (:cell-overrides arguments)) (assoc :cell-overrides (:cell-overrides arguments)))))

(defn with-variant
  "Resolve `:variant-id` from `arguments` (required), resolve it against
  the registered-variants set (no JVM intern outside the allowlist —
  rf2-lqjbk), and probe `story/variant->edn` for the body. When both
  succeed, returns `(f vk body)`. Otherwise short-circuits with an
  error result:

    - Missing/blank `:variant-id` ⇒ `Missing required argument: …`
      (the shape `required-arg` emits).
    - Unregistered variant ⇒ `Variant not found: <vid>` (the raw
      caller-supplied string is echoed; we don't have a keyword form
      because the safe-keyword gate refused to intern one).

  Crystallises the four-line prelude shared by six tool handlers
  (`preview-variant`, `get-variant`, `variant->edn`, `run-variant`,
  `snapshot-identity`, `record-as-variant`). Tools that tolerate
  unregistered variants (`run-a11y`, `read-failures`,
  `unregister-variant`) reach for `with-variant-id` instead."
  [arguments f]
  (let [[vid err] (required-arg arguments :variant-id)]
    (if err
      err
      (if-let [vk (args/safe-keyword vid (variant-id-set))]
        (let [body (story/variant->edn vk)]
          (if (nil? body)
            (error-result (str "Variant not found: " (pr-str vk)))
            (f vk body)))
        (error-result (str "Variant not found: " (pr-str vid)))))))

(defn with-variant-id
  "Resolve `:variant-id` from `arguments` (required) against the
  registered-variants set (rf2-lqjbk — no JVM intern outside the
  allowlist). Returns `(f vk)` when the id resolves; otherwise an
  error result.

  Used by tools whose reads tolerate an unregistered variant
  (`run-a11y`, `read-failures`, `unregister-variant`) — but per the
  rf2-lqjbk bound, an UNREGISTERED variant id can't intern through
  this surface either. The behavioural change: instead of returning
  an empty result for a never-seen id, the tool returns the same
  `Variant not found` error result `with-variant` emits. That is the
  more honest answer — if the id was never registered, there's
  nothing to read."
  [arguments f]
  (let [[vid err] (required-arg arguments :variant-id)]
    (if err
      err
      (if-let [vk (args/safe-keyword vid (variant-id-set))]
        (f vk)
        (error-result (str "Variant not found: " (pr-str vid)))))))

;; ---------------------------------------------------------------------------
;; Wire-egress scrubbers (rf2-73wuj). Per spec/Tool-Pair.md §Direct-read
;; privacy posture (lines 544-566): every pair-shaped tool surfacing live
;; frame state MUST route the value through `re-frame.core/elide-wire-value`
;; before the value crosses the wire egress. In story-mcp the two surfaces
;; that ship live-state reads are `preview-variant` / `run-variant`
;; (which return the variant frame's `:app-db` slice) and `read-failures`
;; (which returns the variant frame's `:rf.story/assertions` accumulator).
;; The walker reads the live `[:rf/elision :declarations]` and
;; `[:rf/elision :runtime-flagged]` registries from the named frame's
;; app-db; the `:frame variant-id` opts slot is load-bearing.
;; ---------------------------------------------------------------------------

(defn elide-app-db
  "Run `app-db` through `re-frame.core/elide-wire-value` against
  `variant-id`'s frame registry. Returns the elided value, or the input
  unchanged when `include?` is true.

  Two short-circuits avoid pointless work:

    - Nil-safe — a nil `app-db` returns immediately (the walker treats
      nil as a non-elidable scalar, but we pre-check to avoid the
      registry lookup on the empty-frame happy path).

    - `include? true` returns the input unchanged. With both inclusion
      knobs flipped on the walker yields `v` at every node (per
      `elide-wire-value`'s composition rule: `sensitive?` and `large?`
      both return `v` when their inclusion flag is true; no marker
      emit, no `write-runtime-flagged!`, no warning). The walk is a
      pure no-op — full traversal, zero edits — so we skip it. The
      escape hatch should be free."
  [app-db variant-id include?]
  (cond
    (nil? app-db) app-db
    include?      app-db
    :else         (rf/elide-wire-value app-db {:frame variant-id})))

(defn scrub-assertions
  "Default-drop any assertion records carrying the top-level
  `:sensitive? true` stamp. Reuses `strip-sensitive` (the shared trace-
  event filter from `mcp-base.sensitive`) — assertion records and trace
  events both honour the same convention, so a single primitive covers
  both surfaces.

  Two short-circuits avoid pointless work on the opt-in / empty paths:

    - `include? true` returns `(vec (or records []))` directly — the
      walker would yield the input unchanged anyway (no drops with the
      escape hatch open), so we skip the traversal.
    - `nil`/empty records short-return `[]`.

  Returns the kept-vec (the `dropped-count` second slot is suppressed
  here; the caller is the wire egress, not an audit surface)."
  [records include?]
  (cond
    include?       (vec (or records []))
    (nil? records) []
    :else          (first (sensitive/strip-sensitive records false))))

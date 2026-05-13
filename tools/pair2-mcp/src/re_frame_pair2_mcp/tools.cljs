(ns re-frame-pair2-mcp.tools
  "MCP tools — one per pair2 op. Each tool builds an nREPL eval request,
  sends it over the persistent connection, and returns the result as an
  MCP `tools/call` result.

  ## Tool catalogue

  | MCP tool name | What it does                                              |
  |---------------|-----------------------------------------------------------|
  | discover-app  | Verify nREPL + confirm the preloaded runtime + health     |
  | eval-cljs     | Eval a CLJS form, return the value                        |
  | dispatch      | Fire a re-frame2 event with :origin :pair                 |
  | trace-window  | Epochs in the last N ms                                   |
  | watch-epochs  | Pull-mode live epoch streaming                            |
  | tail-build    | Wait for a hot-reload to land                             |
  | snapshot      | Coarse-grained per-frame state read (mega-op)             |
  | get-path      | Direct read-by-path against a frame's app-db (rf2-tygdv)  |
  | subscribe     | Streaming trace/epoch channel — push-mode replacement for |
  |               | watch-epochs (rf2-hq49)                                   |
  | unsubscribe   | Close a streaming subscription                            |

  ## Diff-encoded epoch slice (rf2-1wdzp)

  By default, every `:rf/epoch-record` shipped over the wire (in the
  `:epochs` slice of `snapshot`, in `trace-window`, and in
  `watch-epochs` matches) has its `:db-after` replaced with a structural
  diff against its own `:db-before` — `pr-str` doesn't preserve
  structural sharing across records, so the wire payload would otherwise
  carry two near-identical copies of the whole app-db per epoch. The
  default depth (50 epochs) ⇒ up to 100× app-db per `:epochs` slice. The
  diff transform compresses that to ~1% of the size in the typical case
  (single-key change against an otherwise-unchanged db).

  Opt-back-in to the full pair via `epochs-mode \"full\"` (snapshot,
  trace-window, watch-epochs) — agents that need both halves for
  time-travel restore call out explicitly. The in-memory schema
  (`spec/Spec-Schemas.md` §`:rf/epoch-record`) is unchanged; only the
  wire projection here in `tools.cljs` shifts.

  ## Preload probe (no per-session inject)

  The `re-frame-pair2.runtime` namespace ships into the consumer app via
  shadow-cljs's `:devtools :preloads` mechanism. Each tool that needs
  the runtime first calls `ensure-runtime!`, which checks
  `js/globalThis.__re_frame_pair2_runtime` — a load-time mirror the
  preload installs. Missing marker means the preload isn't configured;
  the tool refuses with `:reason :runtime-not-preloaded` and a setup
  hint pointing at `skills/re-frame-pair2/SKILL.md`.

  No cljs-eval inject path: the preload is the canonical setup. Earlier
  drops shipped a per-session inject fallback; that path was cut for
  rf2-7dvg.

  ## Result shape

  Each MCP tool returns `{:content [{:type \"text\" :text <edn-string>}]}`
  on success, or `{:isError true :content [...]}` on failure."
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.string :as str]
            [de-dupe.core :as dedup]
            [re-frame-pair2-mcp.nrepl :as nrepl]))

;; ---------------------------------------------------------------------------
;; Config — build id.
;; ---------------------------------------------------------------------------

(defn- default-build-id []
  (or (some-> (j/get-in js/process [:env :SHADOW_CLJS_BUILD_ID])
              keyword)
      :app))

;; ---------------------------------------------------------------------------
;; MCP result helpers.
;; ---------------------------------------------------------------------------

(defn- ok-text [v]
  #js {:content #js [#js {:type "text" :text (pr-str v)}]})

(defn- err-text [v]
  #js {:isError true
       :content #js [#js {:type "text" :text (pr-str v)}]})

(defn- arg
  "Extract an MCP tool argument by name. Returns nil if absent."
  [args k]
  (let [v (j/get args (name k))]
    (when-not (or (nil? v) (undefined? v)) v)))

(defn- arg-build [args]
  (or (some-> (arg args :build) keyword)
      (default-build-id)))

;; ---------------------------------------------------------------------------
;; Wire-boundary token-budget cap (rf2-rvyzy).
;;
;; Per `spec/Principles.md` §"Tight token budget per response", every
;; MCP `tools/call` response is bounded at ~5,000 tokens by default.
;; The cap is enforced here, not just documented: when the serialised
;; response would exceed the cap, the wrapper replaces the payload
;; with a structured `{:rf.mcp/overflow {...}}` marker and emits
;; that instead. Silent truncation is unacceptable — it corrupts the
;; agent's conversation without telling the agent.
;;
;; Design notes:
;;
;; - **Token rule**: `token-estimate s = (quot (count s) 4)`. Cheap
;;   character→token approximation aligned with Anthropic's
;;   rule-of-thumb for English / EDN. Not exact; the goal is a
;;   bounded wire payload, not a precise meter.
;; - **Per serialised response**: the cap is applied AFTER pr-str on
;;   the assembled `{:content [...]}` shape's text slots. Multi-part
;;   responses share one cumulative budget rather than per-key.
;; - **Per-call override**: every tool accepts a `max-tokens` arg —
;;   integer cap, `0` disables (escape hatch for callers that have
;;   already paginated). Default `5000`.
;; - **Pluggable strategy**: `apply-cap!` dispatches on a strategy
;;   keyword. Today only `:truncate-with-marker` is implemented —
;;   replace the payload with the overflow marker. Future strategies
;;   (path-slicing rf2-tygdv, lazy summary rf2-u2029, diff encoding
;;   rf2-rl7y, etc.) compose here without rebuilding the wrapper.
;; - **Centralised**: applied as the final step in `invoke`. Per-tool
;;   functions are untouched; they emit the same shapes they always
;;   did. The wire-cap is a property of the egress boundary, not of
;;   each tool's internals.
;; ---------------------------------------------------------------------------

(def ^:private default-max-tokens 5000)

(defn- token-estimate
  "Cheap character→token approximation: `(quot (count s) 4)`. Aligned
  with the published Anthropic rule-of-thumb for English / EDN. The
  goal is a bounded wire payload, not a precise per-token meter."
  [s]
  (quot (count s) 4))

(defn- max-tokens-arg
  "Resolve the per-call cap from MCP args. Returns the integer cap in
  tokens, or `nil` when the cap is disabled (caller passed `0`).
  Defaults to `default-max-tokens` when absent or not a number."
  [args]
  (let [raw (when args (j/get args "max-tokens"))]
    (cond
      (or (nil? raw) (undefined? raw)) default-max-tokens
      (and (number? raw) (zero? raw))  nil
      (number? raw)                    (long raw)
      :else                            default-max-tokens)))

(def ^:private overflow-hints
  "Tool-specific next-step hints for the overflow marker. Generic
  fallback when a tool isn't listed."
  {"snapshot"      "Narrow scope: pass `path [:k1 :k2]` to slice the :app-db slice, `frames` to a single frame, or `include` to a single slice (one of app-db, sub-cache, machines, epochs, traces). Default mode is :summary — drill down via `get-path` once you know the key."
   "get-path"      "Narrow the path further — pass a deeper segment so the addressed subtree is smaller. Or call `snapshot` with no `path` first for a tree-summary, then re-aim."
   "trace-window"  "Reduce `ms` to a smaller window, narrow with `frame`, or fetch incrementally via `watch-epochs` + `since-id`."
   "watch-epochs"  "Narrow `pred` (e.g. `:event-id-prefix`, `:effects`), pass `frame`, or stream via `subscribe` with `max-events`."
   "subscribe"     "Tighten `filter`, lower `max-buffered`, set `max-events` so each tick stays small."
   "eval-cljs"     "Slice the value at the call-site (`get-in`, `take`, project to fewer keys) before returning."
   "discover-app"  "Unusual — the health summary should be small. Inspect `(re-frame-pair2.runtime/health)` directly via `eval-cljs` with a projection."
   "dispatch"      "Trace mode is returning a full epoch — re-run with `trace false` and read the epoch via `watch-epochs`/`snapshot` with a narrower path."})

(def ^:private overflow-hint-fallback
  "Response over budget. Re-call with narrower args, or raise `max-tokens` (0 disables the cap).")

(defn- overflow-payload
  "Build the structured overflow marker that replaces an over-budget
  response. Shape is stable per spec/Principles.md §Tight token
  budget: callers pattern-match on the top-level `:rf.mcp/overflow`
  key. `:limit :reached` is a fixed sentinel; `:token-count` is the
  estimate that tripped the cap; `:hint` is tool-specific."
  [{:keys [tool token-count cap]}]
  {:rf.mcp/overflow {:limit       :reached
                     :token-count token-count
                     :cap-tokens  cap
                     :tool        tool
                     :hint        (get overflow-hints tool overflow-hint-fallback)}})

(defn- sum-text-tokens
  "Sum `token-estimate` across every `:text` slot in the MCP
  `{:content [{:type \"text\" :text ...} ...]}` result. The
  serialised response's wire size is dominated by these slots; the
  JSON envelope is bounded and ignored."
  [result-js]
  (let [content (j/get result-js :content)
        n      (if (array? content) (.-length content) 0)]
    (loop [i 0 sum 0]
      (if (< i n)
        (let [item (aget content i)
              text (when item (j/get item :text))
              t    (if (string? text) (token-estimate text) 0)]
          (recur (inc i) (+ sum t)))
        sum))))

(defn- overflow-result
  "Build a fresh MCP result carrying the overflow marker, preserving
  the `:isError` flag of the original result (an over-budget error
  stays an error; an over-budget success becomes a non-error overflow
  signal — the marker is itself a structured response)."
  [tool token-count cap]
  #js {:content #js [#js {:type "text"
                          :text (pr-str (overflow-payload
                                          {:tool        tool
                                           :token-count token-count
                                           :cap         cap}))}]})

(defn- apply-cap
  "Wire-boundary cap enforcement. Returns either `result-js` unchanged
  (when under the cap or cap disabled) or a fresh result carrying the
  overflow marker.

  Pluggable on `strategy`:
  - `:truncate-with-marker` (default, today the only option): drop
    the payload, emit `{:rf.mcp/overflow ...}` instead.

  Future strategies — path-slicing (rf2-tygdv), lazy summary
  (rf2-u2029), diff encoding (rf2-rl7y) — slot in here without
  touching per-tool functions or the `invoke` glue."
  [result-js {:keys [tool cap strategy]
              :or   {strategy :truncate-with-marker}}]
  (cond
    (nil? cap)        result-js
    (nil? result-js)  result-js
    :else
    (let [tokens (sum-text-tokens result-js)]
      (if (<= tokens cap)
        result-js
        (case strategy
          :truncate-with-marker
          (overflow-result tool tokens cap)
          ;; Unknown strategy: degrade safely.
          (overflow-result tool tokens cap))))))

;; ---------------------------------------------------------------------------
;; Diff-encoded epoch slice (rf2-1wdzp).
;;
;; Each `:rf/epoch-record` carries `:db-before` and `:db-after` —
;; near-identical full app-db snapshots. `pr-str` doesn't preserve
;; structural sharing, so on the wire the pair is roughly 2× app-db per
;; epoch; a 50-epoch default `:epochs` slice ⇒ up to 100× app-db.
;;
;; The transform replaces `:db-after` with a path-keyed structural diff
;; against `:db-before`:
;;
;;   {:db-before <full>
;;    :db-after  {:rf.mcp/diff-from :db-before
;;                :patches [[<path> :assoc <new-value>]
;;                          [<path> :dissoc]]}}
;;
;; A patch is a 2- or 3-element vector — `[path :assoc v]` for new /
;; changed leaves, `[path :dissoc]` for keys that disappeared. The
;; decoder applies each patch in order via `assoc-in` / `update-in` to
;; reconstruct `:db-after`.
;;
;; The diff is intra-record (each epoch's `:db-after` is encoded
;; against the SAME record's `:db-before`); records remain
;; self-contained and decodable without reference to siblings. The
;; slice can be reordered, paginated, or filtered without breaking
;; decode.
;;
;; Why not `clojure.data/diff`? Its parallel-vector sparse form for
;; vector diffs (with `nil` placeholders meaning \"common at this
;; position\") loses information once you only carry one half plus the
;; original — you can't tell `nil` (the leaf value `nil`) apart from
;; `nil` (the no-change sentinel). Path-keyed patches are unambiguous
;; for any value the runtime can produce.
;;
;; Opt-back-in to the full pair via `:full` mode (an `epochs-mode` MCP
;; arg on snapshot / trace-window / watch-epochs). Default is `:diff`.
;;
;; Cross-MCP vocabulary: the `:rf.mcp/diff-from` key follows the same
;; `:rf.mcp/*` namespace convention as `:rf.mcp/overflow` (rf2-rvyzy),
;; `:rf.mcp/summary` (rf2-tygdv), and causa-mcp's `:rf.mcp/dedup-table`
;; (rf2-lwgg8 mechanism 5). Agents recognise the family once.
;; ---------------------------------------------------------------------------

(declare collect-patches)

(defn- collect-map-patches
  "Generate patches that transform map `a` into map `b` at `path`.
  Recurses into sub-maps; vectors and scalars are treated as leaves
  (replaced wholesale via `:assoc` rather than re-diffed element-wise
  — element-wise vector diff doesn't shrink the wire for the typical
  app-db where vector values are short)."
  [a b path]
  (let [ks (into #{} (concat (keys a) (keys b)))]
    (reduce
      (fn [acc k]
        (let [av (get a k ::absent)
              bv (get b k ::absent)
              p  (conj path k)]
          (cond
            ;; Key removed.
            (= bv ::absent)
            (conj acc [p :dissoc])
            ;; Key added.
            (= av ::absent)
            (conj acc [p :assoc bv])
            ;; Unchanged — skip.
            (= av bv)
            acc
            ;; Both maps: recurse.
            (and (map? av) (map? bv))
            (into acc (collect-patches av bv p))
            ;; Otherwise: leaf replacement.
            :else
            (conj acc [p :assoc bv]))))
      []
      ks)))

(defn- collect-patches
  "Patch-list factory. Two maps recurse via `collect-map-patches`; any
  other shape change is a single root-level `:assoc` replacement."
  [a b path]
  (cond
    (= a b) []
    (and (map? a) (map? b)) (collect-map-patches a b path)
    :else [[path :assoc b]]))

(defn- apply-patches
  "Apply a vector of patches to `base`, returning the reconstructed
  value. Patches are `[path :assoc v]` or `[path :dissoc]`. Root-path
  patches (path `[]`) replace `base` outright (for `:assoc`) or are a
  no-op (for `:dissoc`, by convention)."
  [base patches]
  (reduce
    (fn [acc patch]
      (let [[path op v] patch]
        (cond
          (empty? path)
          (if (= op :assoc) v acc)
          (= op :assoc)
          (assoc-in acc path v)
          (= op :dissoc)
          (let [parent-path (vec (butlast path))
                k           (last path)]
            (if (empty? parent-path)
              (dissoc acc k)
              (update-in acc parent-path dissoc k)))
          :else acc)))
    base
    patches))

(defn- diff-encode-db-after
  "Replace an epoch's `:db-after` with a path-keyed structural diff
  against its own `:db-before`. Returns the epoch with `:db-after`
  shaped as `{:rf.mcp/diff-from :db-before :patches [...]}`.

  When `:db-before` is missing (older epoch from a runtime that
  pruned it, or a synthetic record), the function leaves the epoch
  unchanged — there's nothing to diff against and silently shipping a
  half-shape would corrupt the agent's view."
  [epoch]
  (if-not (and (map? epoch)
               (contains? epoch :db-before)
               (contains? epoch :db-after))
    epoch
    (let [patches (collect-patches (:db-before epoch) (:db-after epoch) [])]
      (assoc epoch :db-after
             {:rf.mcp/diff-from :db-before
              :patches          patches}))))

(defn- decode-db-after
  "Reverse `diff-encode-db-after`. Given an epoch whose `:db-after` is
  a `{:rf.mcp/diff-from :db-before :patches [...]}` marker,
  reconstruct the full `:db-after` from the epoch's `:db-before` and
  the patch list. Idempotent on already-full epochs (the marker check
  returns the input unchanged when `:db-after` isn't a diff).
  Provided for agent-host round-trip parity and for the unit tests."
  [epoch]
  (let [da (when (map? epoch) (:db-after epoch))]
    (if-not (and (map? da)
                 (= :db-before (:rf.mcp/diff-from da)))
      epoch
      (let [patches   (:patches da)
            db-before (:db-before epoch)
            rebuilt   (apply-patches db-before (or patches []))]
        (assoc epoch :db-after rebuilt)))))

(defn- diff-encode-epochs
  "Apply `diff-encode-db-after` to every epoch in `epochs` unless
  `mode` is `:full` (in which case the vector passes through
  unchanged). Each record is encoded against ITS OWN `:db-before` —
  no cross-record dependency; the slice can be reordered, paginated,
  or filtered without breaking decode.

  `mode` is one of:
    :diff — default. Each `:db-after` becomes a structural diff.
    :full — pass through (legacy behaviour, opt-in)."
  [epochs mode]
  (if (= mode :full)
    epochs
    (mapv diff-encode-db-after epochs)))

(defn- parse-epochs-mode
  "Normalise the `epochs-mode` MCP arg into the keyword the encoder
  expects. Accepts strings (`\"diff\"` / `\"full\"`), keywords
  (`:diff` / `:full`), or nil (default `:diff`). Unrecognised values
  fall back to `:diff` — least surprise on a budget-sensitive default."
  [raw]
  (cond
    (nil? raw)         :diff
    (= raw :full)      :full
    (= raw "full")     :full
    (= raw :diff)      :diff
    (= raw "diff")     :diff
    :else              :diff))

;; ---------------------------------------------------------------------------
;; Structural dedup at the wire boundary (rf2-obpa9).
;;
;; Persistent data structures share subtrees in memory; `pr-str` flattens
;; the sharing and writes every shared node out in full. For the epoch
;; slice — where the diff-encoder (rf2-1wdzp) has already collapsed
;; each `:db-after` against its own `:db-before` — the `:db-before`
;; reference still rides per-record verbatim; a 10-epoch window over a
;; 1MB app-db is ~10MB on the wire after diff-encoding (the diff itself
;; is tiny, but every record still carries a full `:db-before`).
;;
;; `day8/de-dupe` walks a persistent data structure, hash-identifies
;; repeated subtrees, and rewrites the structure as a flat cache map
;; whose entries are keyed by `de-dupe.cache/cache-N` namespaced
;; symbols. The library guarantees round-trip exactness via the
;; companion `expand` function; the agent host can decode locally.
;;
;; ## When dedup runs
;;
;; - **Inside the epoch encoder**: the `:epochs` slice on `snapshot`,
;;   `trace-window`, and `watch-epochs` is wrapped after diff-encoding
;;   and before the wire-cap check (rf2-rvyzy). Order matters: the
;;   diff-encoder shrinks each record's `:db-after`; the deduper then
;;   pools repeated subtrees across records (most importantly the
;;   `:db-before` reference, which is the dominant cost after
;;   diff-encoding).
;; - **Inside subscribe streaming**: each emitted progress frame's
;;   `:events` vector is deduped per-tick. The cache is per-tick
;;   (not per-subscription) — see §Table reset policy below.
;;
;; ## Why `de-dupe-eq` (equality), not `de-dupe` (identity)
;;
;; Data arrives at the MCP server via bencode over nREPL; CLJS values
;; reconstructed from EDN on the way through don't share identity with
;; values the runtime emitted earlier. Equality is what makes the
;; cross-record share-pooling actually fire on the wire boundary, even
;; though identity would be the cheaper rule if we had it.
;;
;; ## Wire shape
;;
;; A deduped payload is wrapped in a top-level marker:
;;
;;   {:rf.mcp/dedup-table <cache-map>}
;;
;; where `<cache-map>` is the de-dupe library's flat
;; `{:de-dupe.cache/cache-0 <root> :de-dupe.cache/cache-N <subtree> ...}`
;; output. Agents reconstruct by calling `de-dupe.core/expand` on the
;; cache-map value, which returns the original structure with sharing
;; restored. The marker key is the cross-MCP-convention vocabulary
;; from causa-mcp's [`Principles.md`](../../causa-mcp/spec/Principles.md)
;; §"5. Structural dedup" and aligns with the `:rf.mcp/*` family
;; (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/diff-from`).
;;
;; ## Table reset policy
;;
;; The cache is built **per dedup call** (per `:epochs` slice, per
;; subscribe tick). Cross-call carry-over would require a stateful
;; per-subscription cache and a wire shape that references entries
;; from previous frames — a non-trivial protocol change for a marginal
;; gain (the dominant within-call savings are already captured). If a
;; future findings doc shows cross-tick share-pooling matters, that's
;; a separate bead.
;;
;; ## Opt-out
;;
;; `dedup` MCP arg (boolean). Default `true`. `false` skips dedup
;; entirely — the encoder emits the un-deduped value. Useful for ad-hoc
;; reads where the agent host hasn't been taught to call `expand`, or
;; for round-trip debugging.
;;
;; ## Idempotence on no-dedup-opportunity
;;
;; A payload with no repeated subtrees deduplicates to a one-entry
;; cache (`{:de-dupe.cache/cache-0 <original>}`) — the wire shape is
;; very slightly larger than the input. The encoder skips wrapping in
;; that case via an `empty-payload?` short-circuit: nil / `[]` / `{}`
;; inputs return the original value, so the marker only appears when
;; there's actual work to undo.
;; ---------------------------------------------------------------------------

(defn- parse-dedup-arg
  "Normalise the `dedup` MCP arg into a boolean. Accepts booleans,
  strings (`\"true\"`/`\"false\"`), keywords (`:true`/`:false`), or
  nil (default `true`). Unrecognised values default to `true` —
  the budget-sensitive default fires dedup."
  [raw]
  (cond
    (nil? raw)             true
    (true? raw)            true
    (false? raw)           false
    (= raw "false")        false
    (= raw :false)         false
    (= raw "true")         true
    (= raw :true)          true
    :else                  true))

(defn- empty-payload?
  "True for values where dedup yields no win — nil, empty collections,
  scalars. Skipping the wrap avoids the trivial cache-of-one shape
  bloating the wire for empty / single-record responses."
  [v]
  (or (nil? v)
      (and (coll? v) (empty? v))
      (not (coll? v))))

(defn- dedup-value
  "Apply structural dedup to `v` and wrap the result in the cross-MCP
  marker. Returns `v` unchanged when `enabled?` is false or when
  `v` is empty / scalar (no dedup opportunity). Uses `de-dupe-eq`
  (equality-based) — see the section header for the identity-vs-equality
  rationale."
  [v enabled?]
  (if (or (not enabled?) (empty-payload? v))
    v
    (let [cache (dedup/de-dupe-eq v)]
      {:rf.mcp/dedup-table cache})))

(defn- dedup-expand
  "Reverse `dedup-value`. Given a value possibly wrapped in the
  `:rf.mcp/dedup-table` marker, reconstruct the original structure
  via `de-dupe.core/expand`. Idempotent on already-expanded values
  (the marker check returns the input unchanged when the wrapper
  isn't present). Provided for round-trip parity and for the unit
  tests; the agent host can call the same shape locally."
  [v]
  (if (and (map? v) (contains? v :rf.mcp/dedup-table))
    (dedup/expand (:rf.mcp/dedup-table v))
    v))

;; ---------------------------------------------------------------------------
;; Size-elision wire markers (rf2-urjnc).
;;
;; The fifth wire-protocol mechanism. After diff-encoding (rf2-1wdzp)
;; collapses each `:db-after`, and dedup (rf2-obpa9) pools repeated
;; subtrees, a single large slot — say a 100KB uploaded PDF base64 on
;; `[:user :uploaded-pdf]` — still rides the wire verbatim. The
;; framework's size-elision walker (`rf/elide-wire-value`, rf2-v9tw2)
;; substitutes such slots with a `{:rf.size/large-elided {...}}` marker
;; carrying a fetch handle (`[:rf.elision/at <path>]`). Agents drill
;; back into the slot via `get-path` using the handle's path.
;;
;; ## Where in the pipeline
;;
;; Elision runs FIRST — server-side inside the eval form, where the
;; frame's `[:rf/elision]` registry is reachable. The MCP server gets
;; back data that already carries `:rf.size/large-elided` markers in
;; place of declared / over-threshold slots. The downstream pipeline
;; (path-slicing → diff-encode → dedup → wire-cap) operates on the
;; post-elision payload — cap measures post-elision bytes, so a single
;; declared-large slot can't blow the cap on its own.
;;
;; ## Where it fires
;;
;; - `snapshot` tool: each frame's `:app-db` slice is run through the
;;   walker before slice-app-db-in-snapshot sees it. The walker handles
;;   both declared paths (registry-driven) and runtime over-threshold
;;   leaves (auto-detect). Path-slicing then drills into the
;;   already-elided value — drilling into a non-elided sibling returns
;;   the small slot directly; drilling into the elided subtree returns
;;   the marker.
;; - `get-path` tool: the value at the requested path is run through
;;   the walker before pr-str. A small slot returns directly; a large
;;   slot returns the marker with a handle the agent can use for a
;;   subsequent narrower fetch.
;;
;; ## `:elision` MCP arg
;;
;; Each surfacing tool accepts an `:elision` arg (boolean, default
;; `true`). Pass `false` to bypass elision entirely — useful for
;; agents with explicit override permission that need the full
;; payload (e.g. a debug session inspecting the elided slot itself).
;; The default-on posture matches the privacy / dedup defaults: shrink
;; first, opt out explicitly.
;;
;; Per the spec's `:elision` configure key (Conventions / API.md), the
;; underlying knobs are `:rf.size/include-large?`,
;; `:rf.size/include-sensitive?`, `:rf.size/include-digests?`, and
;; `:rf.size/threshold-bytes`. Today we surface the simple boolean
;; (`:elision true|false`); finer-grained control is a follow-on bead
;; (the bead's acceptance pins the simple-boolean shape).
;;
;; ## Cross-MCP vocabulary
;;
;; The marker key `:rf.size/large-elided` and the handle vocabulary
;; `[:rf.elision/at <path>]` are reserved per
;; [Conventions §Reserved namespaces / app-db keys / fx-ids] and
;; [Spec 009 §Size elision in traces]. They are the cross-MCP wire
;; vocabulary — story-mcp, causa-mcp, and pair2-mcp emit the same
;; shape so an agent learns the slot once. The `:rf.size/*` family
;; sits alongside `:rf.mcp/*` (the per-tool wire-mechanism family).
;;
;; ## Handle round-trip
;;
;; An agent that receives a marker on `snapshot {:path [:a :b]}`
;; — say `{:rf.size/large-elided {... :handle [:rf.elision/at [:a :b]]}}`
;; — calls `get-path {:path [:a :b]}` next. With `elision true`
;; (default), the walker runs again and returns the same marker
;; (handle-emitter idempotence). With `elision false`, the call
;; returns the un-elided value. Drilling INTO the elided subtree
;; (e.g. `get-path {:path [:a :b :metadata]}` when only `[:a :b]` is
;; declared-large) returns the small metadata directly — the walker
;; recurses past containers and only elides at the declared path
;; or at a leaf over threshold.
;; ---------------------------------------------------------------------------

(defn- parse-elision-arg
  "Normalise the `elision` MCP arg into a boolean. Accepts booleans,
  strings (`\"true\"`/`\"false\"`), keywords (`:true`/`:false`), or
  nil (default `true`). Unrecognised values default to `true` —
  least-surprise on the budget-sensitive default fires elision."
  [raw]
  (cond
    (nil? raw)             true
    (true? raw)            true
    (false? raw)           false
    (= raw "false")        false
    (= raw :false)         false
    (= raw "true")         true
    (= raw :true)          true
    :else                  true))

(defn- elision-opts-edn
  "Render the elision opts map as an EDN string for inlining into a
  CLJS eval form sent over nREPL. Today the only knob is the
  on/off boolean (`:rf.size/include-large?`): when elision is enabled
  we pass `{:rf.size/include-large? false}` so the walker emits
  markers; when disabled we set `:rf.size/include-large? true` so
  values pass through unmodified. `:frame` and `:path` are
  caller-supplied at the call-site inside the form."
  [enabled?]
  (pr-str {:rf.size/include-large? (not enabled?)}))

;; ---------------------------------------------------------------------------
;; Preload probe.
;; ---------------------------------------------------------------------------

(def ^:private preload-missing-hint
  (str "re-frame-pair2.runtime is not loaded into this build. Add the "
       "preload entry to your shadow-cljs.edn:\n"
       "  :builds {:app {:devtools {:preloads [re-frame-pair2.runtime]}}}\n"
       "and make sure the directory containing re_frame_pair2/runtime.cljs "
       "is on :source-paths. See skills/re-frame-pair2/SKILL.md (§Setup)."))

(defn- runtime-preloaded?
  "Probe `js/globalThis.__re_frame_pair2_runtime` — the load-time
  marker set by the preloaded `re-frame-pair2.runtime` namespace.
  Resolves to true iff the marker is present. One bencode round-trip,
  no CLJS compile."
  [conn build-id]
  (-> (nrepl/cljs-eval-value
        conn build-id
        "(some? (and (exists? js/globalThis) (.-__re_frame_pair2_runtime js/globalThis)))")
      (.then (fn [v] (true? v)))
      (.catch (fn [_] false))))

(defn- runtime-health!
  "Call `(re-frame-pair2.runtime/health)`. Caller must have already
  confirmed the preload landed via `runtime-preloaded?`."
  [conn build-id]
  (nrepl/cljs-eval-value conn build-id "(re-frame-pair2.runtime/health)"))

(defn- ensure-runtime!
  "Confirm the pair2 runtime is preloaded. Resolves to nil on success,
  rejects with a structured error otherwise. Tools that need the
  runtime call this first."
  [conn build-id]
  (-> (runtime-preloaded? conn build-id)
      (.then (fn [ok?]
               (if ok?
                 nil
                 (js/Promise.reject
                   (ex-info "pair2 runtime not preloaded"
                            {:reason :runtime-not-preloaded
                             :hint   preload-missing-hint})))))))

;; ---------------------------------------------------------------------------
;; Error helpers — surface structured `ex-info` from `ensure-runtime!`.
;; ---------------------------------------------------------------------------

(defn- err->result
  "Translate a Promise rejection into an `ok-text` result. Structured
  ex-info reasons (e.g. `:runtime-not-preloaded`) surface verbatim;
  other errors fall through to a generic eval-error shape."
  [fallback-reason err]
  (if-let [data (ex-data err)]
    (ok-text (merge {:ok? false} data))
    (ok-text {:ok? false :reason fallback-reason :message (.-message err)})))

;; ---------------------------------------------------------------------------
;; :sensitive? default-suppress (per spec/009 §Privacy / sensitive data).
;;
;; Spec 009 mandates that framework-published forwarders — Sentry /
;; Honeybadger, pair2 server, Causa-MCP — MUST default-drop trace events
;; whose registration was declared `:sensitive? true`. The runtime
;; stamps `:sensitive? true` at the top level of every emitted trace
;; event inside such a registration's handler scope; an event with no
;; such stamp (or `:sensitive? false`) is fine to forward.
;;
;; Opt-in escape hatch: an MCP arg of `:include-sensitive? true` (on
;; any read/stream tool that surfaces trace-like data) removes the
;; filter for that call. The default is off — apps that want sensitive
;; cascades visible to the pair tool configure the policy explicitly.
;; ---------------------------------------------------------------------------

(defn- include-sensitive?
  "True iff the caller has opted in to forwarding `:sensitive? true`
  events for this call. Default off."
  [args]
  (boolean (arg args :include-sensitive?)))

(defn- sensitive-event?
  "Does this event carry the top-level `:sensitive? true` stamp? The
  filter is conservative — only the literal `true` value drops; any
  other value (including the runtime's possible string-coercion via an
  ill-behaved transport) passes through. The `:rf/trace-event` schema
  (per spec/009) types `:sensitive?` as a boolean."
  [ev]
  (and (map? ev)
       (true? (:sensitive? ev))))

(defn- strip-sensitive
  "Remove `:sensitive? true` events from `events` unless the caller opted
  in. Returns `[kept dropped-count]`. Cheap on the common path
  (no sensitive events ⇒ identical-vector return + zero drop count)."
  [events include?]
  (cond
    include?            [events 0]
    (empty? events)     [events 0]
    :else
    (let [kept (filterv (complement sensitive-event?) events)
          n    (- (count events) (count kept))]
      [kept n])))

;; ---------------------------------------------------------------------------
;; Tool: discover-app — verify the stack and probe the preloaded runtime.
;; ---------------------------------------------------------------------------

(defn- discover-app [conn args]
  (let [build-id (arg-build args)]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (runtime-health! conn build-id)))
        (.then
          (fn [health]
            (cond
              (not (:ok? health))
              (ok-text health)

              (not (:debug-enabled? health))
              (ok-text {:ok? false :reason :debug-disabled
                        :hint (str "re-frame.interop/debug-enabled? is false. "
                                   "This is a production build (or goog.DEBUG was "
                                   "forced off). Trace and epoch surfaces are elided.")})

              (empty? (:frames health))
              (ok-text {:ok? false :reason :no-frames-registered
                        :hint "Call (rf/init!) to register :rf/default, or wait for app boot."})

              (:ambiguous-frame? health)
              (ok-text (assoc health :ok? true
                                     :warning :ambiguous-frame
                                     :note (str "Multiple frames registered: "
                                                (vec (:frames health))
                                                ". Mutating ops require --frame :foo "
                                                "or run `frames/select` first.")))

              (not (:coord-annotation-enabled? health))
              (ok-text (assoc health :ok? true
                                     :warning :no-source-coord-annotation
                                     :note (str "Neither data-rf2-source-coord nor "
                                                "data-rc-src is on any element. "
                                                "DOM->source ops will degrade. Enable "
                                                "(rf/configure :source-coords {:annotate-dom? true}) "
                                                "or use re-com with :src (at).")))

              :else
              (ok-text (assoc health :ok? true :build-id build-id)))))
        (.catch (fn [err] (err->result :discover-failed err))))))

;; ---------------------------------------------------------------------------
;; Tool: eval-cljs — evaluate one CLJS form.
;; ---------------------------------------------------------------------------

(defn- eval-cljs-tool [conn args]
  (let [form     (arg args :form)
        build-id (arg-build args)]
    (cond
      (or (nil? form) (str/blank? form))
      (js/Promise.resolve
        (err-text {:ok? false :reason :missing-form
                   :hint "usage: eval-cljs {form '<cljs-form>' [build :app]}"}))

      :else
      (-> (ensure-runtime! conn build-id)
          (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
          (.then (fn [v] (ok-text {:ok? true :value v})))
          (.catch (fn [err] (err->result :eval-error err)))))))

;; ---------------------------------------------------------------------------
;; Tool: dispatch — fire an event.
;; ---------------------------------------------------------------------------

(defn- dispatch-tool [conn args]
  (let [event-str   (arg args :event)
        build-id    (arg-build args)
        sync?       (boolean (arg args :sync))
        trace?      (boolean (arg args :trace))
        frame       (some-> (arg args :frame) keyword)
        fx-overrides (when-let [o (arg args :fx-overrides)] (js->clj o :keywordize-keys true))]
    (cond
      (or (nil? event-str) (str/blank? event-str))
      (js/Promise.resolve
        (err-text {:ok? false :reason :missing-event
                   :hint "usage: dispatch {event '[:ev/id ...]' [sync true] [trace true] [frame :foo] [fx-overrides {...}]}"}))

      :else
      (let [opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides))
            form (cond
                   trace?
                   (str "(re-frame-pair2.runtime/dispatch-and-collect " event-str " " (pr-str opts-form) ")")
                   sync?
                   (str "(re-frame-pair2.runtime/pair-dispatch-sync! " event-str " " (pr-str opts-form) ")")
                   :else
                   (str "(re-frame-pair2.runtime/pair-dispatch! " event-str " " (pr-str opts-form) ")"))
            mode (cond trace? :trace sync? :sync :else :queued)]
        (-> (ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v] (ok-text (merge {:mode mode} (when (map? v) v)))))
            (.catch (fn [err] (err->result :dispatch-failed err))))))))

;; ---------------------------------------------------------------------------
;; Tool: trace-window — epochs in the last N ms.
;; ---------------------------------------------------------------------------

(defn- trace-window-tool [conn args]
  (let [ms        (or (arg args :ms) 1000)
        build-id  (arg-build args)
        frame     (some-> (arg args :frame) keyword)
        incl?     (include-sensitive? args)
        mode      (parse-epochs-mode (arg args :epochs-mode))
        dedup?    (parse-dedup-arg (arg args :dedup))
        form      (if frame
                    (str "(re-frame-pair2.runtime/epochs-in-last-ms " ms " " (pr-str frame) ")")
                    (str "(re-frame-pair2.runtime/epochs-in-last-ms " ms ")"))]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [epochs]
                 (let [[kept dropped] (strip-sensitive (vec epochs) incl?)
                       encoded         (diff-encode-epochs kept mode)
                       deduped         (dedup-value encoded dedup?)]
                   (ok-text (cond-> {:ok? true
                                     :window-ms ms
                                     :count (count encoded)
                                     :epochs-mode mode
                                     :dedup dedup?
                                     :epochs deduped}
                              (pos? dropped) (assoc :dropped-sensitive dropped))))))
        (.catch (fn [err] (err->result :trace-failed err))))))

;; ---------------------------------------------------------------------------
;; Tool: watch-epochs — pull-mode polling with predicate filter.
;;
;; The bash version streams via repeated `emit`s on stdout. MCP tools
;; aren't streaming — we return one bundle of matches per call. Callers
;; that want a tight loop call us repeatedly with the same `since-id`.
;; ---------------------------------------------------------------------------

(defn- watch-epochs-tool [conn args]
  (let [build-id  (arg-build args)
        frame     (some-> (arg args :frame) keyword)
        since-id  (arg args :since-id)
        incl?     (include-sensitive? args)
        mode      (parse-epochs-mode (arg args :epochs-mode))
        dedup?    (parse-dedup-arg (arg args :dedup))
        pred-map  (when-let [p (arg args :pred)] (js->clj p :keywordize-keys true))
        frame-arg (if frame (str " " (pr-str frame)) "")
        form      (str "(let [r (re-frame-pair2.runtime/epochs-since "
                       (pr-str since-id) frame-arg ")"
                       "      matches (filterv #(re-frame-pair2.runtime/epoch-matches? "
                       (pr-str (or pred-map {})) " %) (:epochs r))]"
                       "  {:matches matches"
                       "   :id-aged-out? (:id-aged-out? r)"
                       "   :head-id (:head-id r)})")]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v]
                 (let [matches        (when (map? v) (:matches v))
                       [kept dropped] (strip-sensitive (vec matches) incl?)
                       encoded        (diff-encode-epochs kept mode)
                       deduped        (dedup-value encoded dedup?)
                       base           (cond-> {:ok? true}
                                        (map? v) (merge v))]
                   (ok-text (cond-> (assoc base
                                           :matches deduped
                                           :epochs-mode mode
                                           :dedup dedup?)
                              (pos? dropped) (assoc :dropped-sensitive dropped))))))
        (.catch (fn [err] (err->result :watch-failed err))))))

;; ---------------------------------------------------------------------------
;; Tool: tail-build — wait for hot-reload to land.
;; ---------------------------------------------------------------------------

(defn- tail-build-tool [conn args]
  (let [build-id (arg-build args)
        wait-ms  (or (arg args :wait-ms) 5000)
        probe    (arg args :probe)
        poll-ms  100]
    (cond
      (nil? probe)
      ;; Soft delay — matches the bash version's behaviour when no probe
      ;; is supplied. We just resolve after a short sleep.
      (js/Promise.
        (fn [resolve _]
          (js/setTimeout
            (fn []
              (resolve (ok-text {:ok? true :t (js/Date.now) :soft? true
                                 :note "No probe supplied; waited a 300ms fixed delay."})))
            300)))

      :else
      (let [start (js/Date.now)]
        (-> (nrepl/cljs-eval-value conn build-id probe)
            (.then
              (fn [before]
                (js/Promise.
                  (fn [resolve _]
                    (letfn [(poll []
                              (js/setTimeout
                                (fn []
                                  (let [elapsed (- (js/Date.now) start)]
                                    (if (>= elapsed wait-ms)
                                      (resolve
                                        (ok-text {:ok? false :reason :timed-out :timed-out? true
                                                  :note "Probe did not change within wait-ms. Likely a compile error."}))
                                      (-> (nrepl/cljs-eval-value conn build-id probe)
                                          (.then
                                            (fn [now]
                                              (if (not= now before)
                                                (resolve (ok-text {:ok? true :t (js/Date.now) :soft? false}))
                                                (poll))))
                                          (.catch (fn [_] (poll)))))))
                                poll-ms))]
                      (poll))))))
            (.catch
              (fn [err]
                (ok-text {:ok? false :reason :probe-failed
                          :message (.-message err)}))))))))

;; ---------------------------------------------------------------------------
;; Tool: snapshot — coarse-grained per-frame state read in one round-trip.
;;
;; Many investigate-X workflows chain 5-10 reads; each is a bencode
;; round-trip plus Claude-think latency. This op composes the existing
;; per-slice readers server-side and returns a per-frame map.
;; ---------------------------------------------------------------------------

(def ^:private valid-slices
  #{:app-db :sub-cache :machines :epochs :traces})

(defn- ->frame-keyword
  "Coerce a frame-id string into a keyword. Accepts both bare names
   (`\"rf/default\"`) and EDN-shaped strings (`\":rf/default\"`) — strips
   a leading colon when present so callers can pass either form."
  [x]
  (cond
    (keyword? x) x
    (string? x)
    (let [s (if (str/starts-with? x ":") (subs x 1) x)]
      (keyword s))
    :else (keyword x)))

;; ---------------------------------------------------------------------------
;; Path-arg parsing (rf2-tygdv).
;;
;; Two tools take a `:path` argument: `snapshot` (slice the :app-db slice)
;; and `get-path` (direct read-by-path). Same parser, same semantics so
;; agents learn the shape once.
;;
;; Accepted shapes from the MCP host:
;;   - JS array of strings  ⇒ each entry parsed as EDN; non-EDN entries
;;                            stay as strings.
;;   - CLJS vector          ⇒ pass through.
;;   - EDN-encoded string   ⇒ read-string (e.g. `"[:cart :items 3 :sku]"`).
;;   - nil / missing        ⇒ nil (no path slicing).
;;
;; This mirrors the causa-mcp wire-protocol Principles §"2. Path slicing"
;; convention: a path is an EDN-encoded vector of keys addressing a
;; subtree. The vocabulary is shared across pair2-mcp / causa-mcp /
;; story-mcp so agents recognise the surface once.
;; ---------------------------------------------------------------------------

(defn- coerce-path-segment
  "Coerce one segment of a JS-array path argument.

  Heuristic: an EDN-shaped string is parsed (`\":cart\"` ⇒ `:cart`,
  `\"0\"` ⇒ `0`, `\"-1\"` ⇒ `-1`), but a bare identifier
  (`\"items\"`, `\"bare-key\"`) stays a string — the default reader
  would otherwise coerce it to a symbol, which is a different
  `get-in` key. Trigger characters are the EDN literal openers `:`
  (keyword), a leading digit, or `-`/`+` (signed number); anything
  else falls through as a plain string."
  [s]
  (if-not (string? s)
    s
    (let [trimmed   (str/trim s)
          fc        (when (pos? (count trimmed)) (.charAt trimmed 0))
          edn-shape (and fc
                         (or (= ":" fc)
                             (= "-" fc)
                             (= "+" fc)
                             (boolean (re-matches #"\d" fc))))]
      (if edn-shape
        (try (cljs.reader/read-string trimmed)
             (catch :default _ s))
        s))))

(defn- parse-path-arg
  "Normalise the `path` MCP arg into a CLJS vector suitable for
   `get-in`. Returns `nil` when the path is absent. Returns `[]` for an
   explicit empty path (root). Unparsable strings fall through as
   strings — `get-in` will then treat them as map keys."
  [raw]
  (cond
    (nil? raw) nil
    (vector? raw) raw
    (sequential? raw) (vec raw)
    (array? raw) (mapv coerce-path-segment (js->clj raw))
    (string? raw)
    (let [trimmed (str/trim raw)]
      (cond
        (str/blank? trimmed) nil
        :else
        (try
          (let [parsed (cljs.reader/read-string trimmed)]
            (cond
              (vector? parsed)     parsed
              (sequential? parsed) (vec parsed)
              :else                [parsed]))
          (catch :default _
            ;; Unparseable; treat the whole string as a single segment.
            [trimmed]))))
    :else nil))

(defn- parse-frames-arg
  "Normalise the `frames` MCP arg into the form the runtime expects.
   Accepts `:all`, the string \"all\", a JS array of strings, or a CLJS
   vector. Returns `:all` or a vector of keyword frame-ids. Returns
   `:all` for nil / empty / unrecognised input — least-surprise."
  [raw]
  (cond
    (nil? raw) :all
    (or (= raw :all) (= raw "all")) :all
    (array? raw)
    (->> (js->clj raw) (mapv ->frame-keyword))
    (sequential? raw)
    (mapv ->frame-keyword raw)
    :else :all))

(defn- parse-include-arg
  "Normalise the `include` MCP arg into the slice vector the runtime
   expects. Filters to known slices; returns the full set when arg
   is nil / empty / all-unknown."
  [raw]
  (let [full [:app-db :sub-cache :machines :epochs :traces]
        coerce (fn [xs]
                 (->> xs
                      (map keyword)
                      (filter valid-slices)
                      vec))]
    (cond
      (nil? raw) full
      (array? raw)
      (let [v (coerce (js->clj raw))]
        (if (seq v) v full))
      (sequential? raw)
      (let [v (coerce raw)]
        (if (seq v) v full))
      :else full)))

(defn- scrub-snapshot-sensitive
  "Walk a snapshot's per-frame map and drop `:sensitive? true` items from
  the `:traces` slice (and, defensively, `:epochs` — epoch records may
  inherit the stamp in future runtime revisions per spec/009). Returns
  `[scrubbed dropped-count]`. The non-trace slices (:app-db, :sub-cache,
  :machines) pass through unchanged — redaction of those payloads is
  the `with-redacted` interceptor's job, not the forwarder's."
  [snapshot include?]
  (if (or include? (not (map? snapshot)))
    [snapshot 0]
    (let [dropped (atom 0)
          scrub-slice
          (fn [items]
            (let [[kept n] (strip-sensitive (vec items) false)]
              (swap! dropped + n)
              kept))
          scrub-frame
          (fn [frame-map]
            (cond-> frame-map
              (contains? frame-map :traces) (update :traces scrub-slice)
              (contains? frame-map :epochs) (update :epochs scrub-slice)))
          scrubbed (reduce-kv (fn [m k v]
                                (assoc m k (if (map? v) (scrub-frame v) v)))
                              {} snapshot)]
      [scrubbed @dropped])))

;; ---------------------------------------------------------------------------
;; Tree-summary for snapshot slices (rf2-tygdv, generalised rf2-u2029).
;;
;; Per causa-mcp's wire-protocol Principles §"4. Lazy summary", the
;; default response mode for a rich nested value is a *summary*, not the
;; full payload. The summary declares the shape without committing the
;; token budget:
;;
;;   {:rf.mcp/summary {:type  :map | :vector | :set | :scalar
;;                     :keys  [<top-level keys>]   ; maps only
;;                     :count <int>                ; non-scalars only
;;                     :bytes ~<int>}}             ; pr-str char count
;;
;; rf2-tygdv landed this for the `:app-db` slice only. rf2-u2029
;; generalises it to every rich slice in the snapshot response:
;; `:app-db`, `:sub-cache`, `:machines`, `:epochs`, `:traces`. The
;; default snapshot call (no `:mode`, no `:path`) returns a summary
;; marker for each slice instead of the full payload — the discovery
;; workflow ("I don't know which slice carries the answer") fits the
;; cap by construction. Agents drill in via:
;;
;;   - `:path` arg (`:app-db` slice only) for path-slicing, OR
;;   - `:mode "full"` (every rich slice expands), OR
;;   - `:modes {"app-db" "full" "sub-cache" "summary" ...}` for per-slice
;;     override.
;;
;; Path-slicing on `:app-db` supersedes the slice-level mode for that
;; slice (the slicer already returns a bounded subtree). Other slices
;; respect mode independently.
;; ---------------------------------------------------------------------------

(def ^:private summary-keys-cap
  "Top-N keys included verbatim in a tree-summary marker. Above this,
  the summary truncates and attaches `:keys-truncated? true` so the
  marker itself stays bounded — a 5,000-entry map's key list alone
  would exceed the wire cap otherwise. 64 is large enough that a
  human-scale app-db root surfaces every key, and small enough that
  the marker is always tens of tokens."
  64)

(defn- tree-summary
  "Compute a server-friendly tree summary of `v`. Returns the marker
  shape causa-mcp's §Lazy-summary mechanism pins. Cheap — one pass
  over the top-level structure, no deep walk. The marker itself is
  bounded: long key lists are truncated to `summary-keys-cap` entries
  and flagged via `:keys-truncated? true` so the marker can never
  blow the wire cap."
  [v]
  (cond
    (map? v)
    (let [ks      (keys v)
          n       (count ks)
          shown   (if (> n summary-keys-cap)
                    (vec (take summary-keys-cap ks))
                    (vec ks))]
      {:rf.mcp/summary (cond-> {:type   :map
                                :keys   shown
                                :count  n
                                :bytes  (count (pr-str v))}
                         (> n summary-keys-cap)
                         (assoc :keys-truncated? true))})
    (vector? v)
    {:rf.mcp/summary {:type  :vector
                      :count (count v)
                      :bytes (count (pr-str v))}}
    (set? v)
    {:rf.mcp/summary {:type  :set
                      :count (count v)
                      :bytes (count (pr-str v))}}
    (sequential? v)
    {:rf.mcp/summary {:type  :seq
                      :count (count v)
                      :bytes (count (pr-str v))}}
    :else
    {:rf.mcp/summary {:type  :scalar
                      :value v
                      :bytes (count (pr-str v))}}))

(defn- deepest-valid-prefix
  "Walk `path` against `db` and return the deepest prefix that
  resolves. Used in `:path-not-found` errors so the agent can re-aim
  without a binary search. Handles map keys + sequential indices;
  anything else (a scalar at depth, a function value, etc.) terminates
  the walk."
  [db path]
  (loop [acc [] cur db remaining path]
    (if (empty? remaining)
      acc
      (let [k (first remaining)]
        (cond
          (and (map? cur) (contains? cur k))
          (recur (conj acc k) (get cur k) (rest remaining))

          (and (sequential? cur) (integer? k) (<= 0 k (dec (count cur))))
          (recur (conj acc k) (nth (vec cur) k) (rest remaining))

          :else acc)))))

(defn- slice-app-db-in-snapshot
  "Post-process the raw snapshot map: for each frame's `:app-db` slice,
  apply path-slicing (when `path` is present), summarisation (when
  `path` is nil and the resolved app-db mode is `:summary`), or pass
  the slice through (when the resolved mode is `:full`).

  `app-db-mode` is `:summary` (default) or `:full`. The `:path` arg
  takes precedence — when a non-nil path is supplied the slice is
  always path-sliced regardless of mode. An empty path `[]` is
  semantically equivalent to `:full` mode (agent explicitly asking
  for the whole slice).

  Returns `[processed-snapshot per-frame-path-status]` where
  `per-frame-path-status` is `{<frame-id> {:exists? bool
                                            :deepest-valid-prefix [...]}}`
  populated only when `path` is supplied and at least one frame's
  path didn't resolve. Empty map when path is nil."
  [snapshot path app-db-mode]
  (if-not (map? snapshot)
    [snapshot {}]
    (let [status* (atom {})
          missing (js-obj)
          full?   (= :full app-db-mode)
          process-frame
          (fn [frame-id frame-map]
            (if-not (and (map? frame-map) (contains? frame-map :app-db))
              frame-map
              (let [db (:app-db frame-map)]
                (cond
                  ;; No path + summary mode (rf2-tygdv default): summarise.
                  (and (nil? path) (not full?))
                  (update frame-map :app-db tree-summary)
                  ;; No path + full mode: full slice (rf2-u2029 opt-in).
                  (nil? path)
                  frame-map
                  ;; Root path (`[]`): return full db (agent opted in
                  ;; explicitly). Equivalent to legacy default behaviour.
                  (empty? path)
                  frame-map
                  ;; Path supplied: get-in with missing sentinel.
                  :else
                  (let [v (get-in db path missing)]
                    (if (identical? v missing)
                      (do (swap! status* assoc frame-id
                                 {:exists? false
                                  :deepest-valid-prefix (deepest-valid-prefix db path)})
                          (assoc frame-map :app-db nil))
                      (assoc frame-map :app-db v)))))))
          processed (reduce-kv (fn [m fid fmap]
                                 (assoc m fid (process-frame fid fmap)))
                               {} snapshot)]
      [processed @status*])))

;; ---------------------------------------------------------------------------
;; Lazy-summary default for every rich slice (rf2-u2029).
;;
;; Generalises rf2-tygdv's `:app-db` summary default to `:sub-cache`,
;; `:machines`, `:epochs`, `:traces`. Per spec/Principles.md §Per-tool
;; budget discipline, "the default MUST be `:sample` for any op whose
;; `:full` payload can exceed the cap" — every rich slice in snapshot
;; can do that.
;;
;; The resolved mode for each slice is governed by:
;;
;;   1. `:modes` per-slice override (highest priority).
;;   2. Global `:mode` arg (`:summary` (default) or `:full`).
;;   3. For `:app-db` specifically: a non-nil `:path` arg forces
;;      `:path-sliced` regardless of mode (path-slicer already gives
;;      a bounded subtree). An empty path `[]` means "explicit full".
;;
;; This function walks each frame's slice map and, for rich slices
;; resolved to `:summary`, replaces the value with the `tree-summary`
;; marker. `:app-db` is handled by `slice-app-db-in-snapshot`
;; upstream; this function skips `:app-db` to avoid double-work.
;; ---------------------------------------------------------------------------

(def ^:private summarisable-slices
  "Slices for which a summary marker is a meaningful budget win.
  `:app-db` is omitted — `slice-app-db-in-snapshot` already handles it
  and respects the `:path` arg. The rest are vectors or maps that can
  grow unbounded with runtime state."
  #{:sub-cache :machines :epochs :traces})

(defn- resolve-slice-mode
  "Resolve the effective mode for a slice. `slice-modes` is the
  per-slice override map; `global-mode` is the snapshot-wide `:mode`
  arg. Falls back to `:summary` when nothing pins the slice. Always
  returns `:summary` or `:full`."
  [slice slice-modes global-mode]
  (let [m (or (get slice-modes slice) global-mode :summary)]
    (case m
      :full :full
      :summary)))

(defn- summarise-other-slices-in-snapshot
  "Apply `tree-summary` to every frame's non-app-db slice whose
  resolved mode is `:summary`. `:full` slices pass through unchanged.
  Returns the rewritten snapshot map. `:app-db` is skipped — that
  slice's summary / path-slicing already happened upstream in
  `slice-app-db-in-snapshot`.

  Returns `{:snapshot processed :resolved-modes {<slice> :summary|:full}}`
  so the snapshot response can echo back which slices were summarised
  — agents pattern-match on the resolved-modes map without re-deriving
  the slice list."
  [snapshot slice-modes global-mode]
  (if-not (map? snapshot)
    {:snapshot snapshot :resolved-modes {}}
    (let [resolved (into {} (map (fn [s]
                                   [s (resolve-slice-mode s slice-modes global-mode)]))
                         summarisable-slices)
          process-frame
          (fn [frame-map]
            (if-not (map? frame-map)
              frame-map
              (reduce-kv
                (fn [m k v]
                  (assoc m k
                         (if (and (contains? summarisable-slices k)
                                  (= :summary (get resolved k))
                                  (some? v))
                           (tree-summary v)
                           v)))
                {} frame-map)))
          processed (reduce-kv (fn [m fid fmap]
                                 (assoc m fid (process-frame fmap)))
                               {} snapshot)]
      {:snapshot processed :resolved-modes resolved})))

(defn- parse-mode-arg
  "Normalise the global `mode` MCP arg. Accepts strings (`\"summary\"`,
  `\"full\"`) or keywords. Defaults to `:summary` — the lazy-summary
  default per rf2-u2029. Unrecognised values default to `:summary`
  (budget-sensitive default)."
  [raw]
  (cond
    (nil? raw)                 :summary
    (= raw :full)              :full
    (= raw :summary)           :summary
    (= raw "full")             :full
    (= raw "summary")          :summary
    :else                      :summary))

(defn- parse-modes-arg
  "Normalise the per-slice `modes` MCP arg into a `{<slice-keyword>
  <mode-keyword>}` map. Accepts a JS object, a CLJS map, or nil.
  Unknown slices are dropped. Unknown mode values are dropped (the
  slice falls back to the global mode default). Slice keys may be
  bare strings (`\"app-db\"`), EDN-shaped strings (`\":app-db\"`),
  or keywords."
  [raw]
  (let [as-clj (cond
                 (nil? raw)            nil
                 (map? raw)            raw
                 ;; JS object from the MCP wire.
                 (and (some? raw)
                      (not (array? raw))
                      (not (string? raw))
                      (not (boolean? raw))
                      (not (number? raw)))
                 (try (js->clj raw) (catch :default _ nil))
                 :else nil)
        coerce-k (fn [k]
                   (cond
                     (keyword? k) k
                     (string? k)
                     (let [s (if (str/starts-with? k ":") (subs k 1) k)]
                       (keyword s))
                     :else nil))
        coerce-v (fn [v]
                   (cond
                     (= v :summary)  :summary
                     (= v :full)     :full
                     (= v "summary") :summary
                     (= v "full")    :full
                     :else           nil))]
    (if-not (map? as-clj)
      {}
      (reduce-kv
        (fn [m k v]
          (let [k' (coerce-k k)
                v' (coerce-v v)]
            (if (and k' (contains? valid-slices k') v')
              (assoc m k' v')
              m)))
        {} as-clj))))

(defn- diff-encode-epochs-in-snapshot
  "Walk the per-frame snapshot map and diff-encode every frame's
  `:epochs` slice (rf2-1wdzp). `mode :full` short-circuits — the
  snapshot passes through unchanged. Other slices are untouched; only
  the `:epochs` slot of each frame map is rewritten."
  [snapshot mode]
  (cond
    (or (= mode :full) (not (map? snapshot)))
    snapshot
    :else
    (reduce-kv
      (fn [m fid fmap]
        (assoc m fid
               (if (and (map? fmap) (contains? fmap :epochs))
                 (update fmap :epochs diff-encode-epochs mode)
                 fmap)))
      {} snapshot)))

(defn- dedup-epochs-in-snapshot
  "Walk the per-frame snapshot map and apply structural dedup
  (rf2-obpa9) to every frame's `:epochs` slice. Dedup is per-frame —
  cross-frame share-pooling would require a single table spanning
  every frame's slice, which is a follow-on optimisation; per-frame
  is the safe default and matches the table-reset policy
  (per-call, not per-stream)."
  [snapshot enabled?]
  (cond
    (or (not enabled?) (not (map? snapshot)))
    snapshot
    :else
    (reduce-kv
      (fn [m fid fmap]
        (assoc m fid
               (if (and (map? fmap) (contains? fmap :epochs))
                 (update fmap :epochs dedup-value enabled?)
                 fmap)))
      {} snapshot)))

(defn- snapshot-tool [conn args]
  (let [build-id  (arg-build args)
        frames    (parse-frames-arg (arg args :frames))
        include   (parse-include-arg (arg args :include))
        incl?     (include-sensitive? args)
        path      (parse-path-arg (arg args :path))
        mode      (parse-epochs-mode (arg args :epochs-mode))
        ;; Global lazy-summary mode (rf2-u2029): `:summary` (default)
        ;; replaces every rich slice with a tree-summary marker;
        ;; `:full` ships the full payload. Per-slice override via
        ;; `:modes` map takes precedence over the global mode.
        slice-mode  (parse-mode-arg (arg args :mode))
        slice-modes (parse-modes-arg (arg args :modes))
        dedup?    (parse-dedup-arg (arg args :dedup))
        elision?  (parse-elision-arg (arg args :elision))
        opts      {:frames frames :include include}
        ;; Eval form composition (rf2-urjnc). The snapshot composer
        ;; returns a per-frame map; we wrap each frame's `:app-db`
        ;; slice with `re-frame.core/elide-wire-value` so large /
        ;; sensitive slots get the `:rf.size/large-elided` marker
        ;; server-side, before the EDN crosses the wire. The walker
        ;; reads the `[:rf/elision]` registry from the live app-db
        ;; — it has to run app-side, where the registry is reachable.
        ;; When elision is disabled the eval form skips the walk
        ;; entirely (a value pass-through is cheaper than walking
        ;; with `:rf.size/include-large? true`).
        elision-opts-form (elision-opts-edn elision?)
        form     (if elision?
                   (str "(let [snap (re-frame-pair2.runtime/snapshot-state "
                        (pr-str opts) ")]"
                        "  (reduce-kv"
                        "    (fn [m fid fmap]"
                        "      (assoc m fid"
                        "             (if (and (map? fmap) (contains? fmap :app-db))"
                        "               (update fmap :app-db"
                        "                       (fn [db] (re-frame.core/elide-wire-value db"
                        "                                  (merge {:frame fid} "
                        elision-opts-form
                        "))))"
                        "               fmap)))"
                        "    {} snap))")
                   (str "(re-frame-pair2.runtime/snapshot-state "
                        (pr-str opts) ")"))]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v]
                 (let [app-db-mode (resolve-slice-mode :app-db slice-modes slice-mode)
                       [scrubbed dropped]    (scrub-snapshot-sensitive v incl?)
                       [sliced path-status]  (slice-app-db-in-snapshot scrubbed path app-db-mode)
                       diff-encoded          (diff-encode-epochs-in-snapshot sliced mode)
                       deduped               (dedup-epochs-in-snapshot diff-encoded dedup?)
                       ;; Lazy-summary default for non-app-db rich
                       ;; slices (rf2-u2029). Runs LAST in the pipeline
                       ;; so summary `:bytes` hints reflect the
                       ;; post-shrink wire cost of each slice.
                       {summarised :snapshot
                        other-modes :resolved-modes} (summarise-other-slices-in-snapshot
                                                       deduped slice-modes slice-mode)
                       resolved-modes (assoc other-modes :app-db
                                             (cond
                                               path :path-sliced
                                               :else app-db-mode))
                       response-mode  (cond
                                        path                  :path-sliced
                                        (= :full app-db-mode) :full
                                        :else                 :summary)]
                   (ok-text (cond-> {:ok?            true
                                     :frames         (if (= :all frames) :all (vec frames))
                                     :include        include
                                     :mode           response-mode
                                     :slice-modes    resolved-modes
                                     :epochs-mode    mode
                                     :dedup          dedup?
                                     :elision        elision?
                                     :snapshot       summarised}
                              path                  (assoc :path path)
                              (seq path-status)     (assoc :path-not-found path-status)
                              (pos? dropped)        (assoc :dropped-sensitive dropped))))))
        (.catch (fn [err] (err->result :snapshot-failed err))))))

;; ---------------------------------------------------------------------------
;; Tool: get-path — direct read-by-path against a frame's app-db
;;       (rf2-tygdv).
;;
;; Minimal, focused primitive. The `snapshot` tool is the right surface
;; when the agent doesn't know yet which slice carries the answer;
;; `get-path` is the right surface when the agent already knows the
;; path. Each call is one bencode round-trip; the runtime computes
;; `(get-in app-db path)` server-side so only the addressed subtree
;; crosses the wire.
;;
;; Path vocabulary mirrors `get-in`: a vector of keys / indices.
;; ---------------------------------------------------------------------------

(defn- get-path-tool [conn args]
  (let [build-id  (arg-build args)
        frame     (some-> (arg args :frame) ->frame-keyword)
        path      (parse-path-arg (arg args :path))
        elision?  (parse-elision-arg (arg args :elision))]
    (cond
      (nil? path)
      (js/Promise.resolve
        (err-text {:ok? false :reason :missing-path
                   :hint "usage: get-path {path '[:cart :items 0 :sku]' [frame :rf/default]}"}))

      :else
      ;; Server-side eval form: call `snapshot` (full db for the frame)
      ;; then `get-in` with a missing sentinel, so we can distinguish
      ;; `path-not-found` from a path that legitimately points at nil.
      ;; The deepest-valid-prefix loop is inlined so a stale runtime
      ;; (no helper) still answers correctly.
      ;;
      ;; Elision wiring (rf2-urjnc): once `get-in` resolves the value,
      ;; we run it through `re-frame.core/elide-wire-value` so a
      ;; large / sensitive slot returns the marker (with a handle the
      ;; agent can drill into) rather than the raw bytes. The walker
      ;; reads the live `[:rf/elision]` registry from the frame's
      ;; app-db, so it must run app-side. Passing `:path path` makes
      ;; the marker's `:handle` slot carry `[:rf.elision/at <path>]`
      ;; — the agent can re-call `get-path` with a deeper segment to
      ;; drill into a non-elided child, or pass `elision false` to
      ;; bypass the walk entirely.
      (let [path-edn      (pr-str path)
            snapshot-call (if frame
                            (str "(re-frame-pair2.runtime/snapshot " (pr-str frame) ")")
                            "(re-frame-pair2.runtime/snapshot)")
            frame-edn     (if frame (pr-str frame) "(re-frame-pair2.runtime/current-frame)")
            elision-opts  (elision-opts-edn elision?)
            elide-call    (if elision?
                            (str "(re-frame.core/elide-wire-value v"
                                 "  (merge {:path path :frame " frame-edn "}"
                                 "         " elision-opts "))")
                            "v")
            form (str "(let [db " snapshot-call
                      "      path " path-edn
                      "      missing #js {}"
                      "      v (get-in db path missing)]"
                      "  (if (identical? v missing)"
                      "    {:ok? false :reason :path-not-found"
                      "     :path path"
                      "     :deepest-valid-prefix"
                      "     (loop [acc [] cur db rem path]"
                      "       (cond"
                      "         (empty? rem) acc"
                      "         (and (map? cur) (contains? cur (first rem)))"
                      "         (recur (conj acc (first rem)) (get cur (first rem)) (rest rem))"
                      "         (and (sequential? cur) (integer? (first rem))"
                      "              (<= 0 (first rem) (dec (count cur))))"
                      "         (recur (conj acc (first rem)) (nth (vec cur) (first rem)) (rest rem))"
                      "         :else acc))}"
                      "    {:ok? true :exists? true :path path :value " elide-call "}))")]
        (-> (ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v]
                     (ok-text (cond-> v
                                frame     (assoc :frame frame)
                                (:ok? v)  (assoc :elision elision?)))))
            (.catch (fn [err] (err->result :get-path-failed err))))))))

;; ---------------------------------------------------------------------------
;; Tool: subscribe — streaming trace + epoch channel (rf2-hq49).
;;
;; The MCP `tools/call` request runs until either:
;;   (a) the client aborts (cancellation arrives via the MCP `extra.signal`
;;       AbortSignal), or
;;   (b) an `unsubscribe` op clears the sub-id from the runtime.
;;
;; While running, each batch of newly-queued runtime events is shipped to
;; the client as a `notifications/progress` notification correlated to the
;; original tools/call via `extra._meta.progressToken`. The final
;; `tools/call` result is a summary `{:ok? true :sub-id :delivered N
;; :overflow N :reason <terminated-reason>}`.
;;
;; The runtime queue is bounded (default 500); overflow events get
;; counted in a per-sub `:overflow` slot and surfaced verbatim. The
;; server's poll cadence (`:poll-ms`, default 100) is well below the
;; agent-loop perceptual threshold and costs one bencode round-trip
;; per tick.
;;
;; Filter vocabulary (server-side normalisation happens on the runtime).
;;
;; Topics:
;;   :trace  — every entry of the raw trace stream matching `:filter`.
;;             `:filter` keys: :operation :op-type :frame :severity
;;                            :event-id :handler-id :source :origin
;;                            :dispatch-id :since-ms :between
;;             (mirrors `(rf/trace-buffer)` per rf2-97ah0).
;;   :epoch  — every assembled `:rf/epoch-record` matching `:filter`.
;;             `:filter` keys: :event-id :event-id-prefix :effects
;;                            :touches-path :sub-ran :render :origin
;;                            :frame  (mirrors `epoch-matches?`).
;;   :fx     — sugar for :topic :trace :filter {:op-type :fx ...}.
;;   :error  — sugar for :topic :trace :filter {:op-type :error ...}.
;; ---------------------------------------------------------------------------

(def ^:private default-poll-ms 100)

(defn- progress-payload
  "Build the JSON params payload for one `notifications/progress` tick.
  `events` is the EDN-printed string of the batch (kept as a string so
  the agent host sees the same shape as `tools/call` results)."
  [progress-token tick events overflow]
  #js {:progressToken progress-token
       :progress      tick
       ;; `message` is the human-readable slot. We stash an EDN form
       ;; here so an MCP client that surfaces progress messages to
       ;; the agent shows the events directly. A capable client can
       ;; additionally inspect the `data` slot for the structured
       ;; counts.
       :message       events
       :data          #js {:overflow overflow}})

(defn- parse-filter-arg
  "MCP-side filter arg can be either a JS object or an EDN string. We
  accept both for ergonomic parity with the bash-shim chain (`pred`
  has been a JSON object there). Returns an EDN-printable map or nil
  when missing."
  [raw]
  (cond
    (nil? raw)        nil
    (string? raw)     (try (cljs.reader/read-string raw)
                           (catch :default _
                             {:invalid-filter-edn raw}))
    (map? raw)        raw
    :else             (js->clj raw :keywordize-keys true)))

(defn- subscribe-tool [conn args extra]
  (let [build-id    (arg-build args)
        topic       (some-> (arg args :topic) keyword)
        filter-map  (parse-filter-arg (arg args :filter))
        max-buf     (or (arg args :max-buffered) 500)
        poll-ms     (or (arg args :poll-ms) default-poll-ms)
        max-ms      (or (arg args :max-ms) 0)    ;; 0 = no upper bound
        max-events  (or (arg args :max-events) 0) ;; 0 = no upper bound
        incl?       (include-sensitive? args)
        dedup?      (parse-dedup-arg (arg args :dedup))
        progress-tk (some-> extra
                            (j/get :_meta)
                            (j/get :progressToken))
        send-note   (some-> extra (j/get :sendNotification))
        signal      (some-> extra (j/get :signal))]
    (cond
      (or (nil? topic)
          (not (#{:trace :epoch :fx :error} topic)))
      (js/Promise.resolve
        (err-text {:ok? false :reason :unknown-topic
                   :given (arg args :topic)
                   :hint "Recognised topics: trace, epoch, fx, error."}))

      :else
      (let [subscribe-form
            (str "(re-frame-pair2.runtime/subscribe! "
                 (pr-str (cond-> {:topic topic
                                  :max-buffered max-buf}
                           filter-map (assoc :filter filter-map)))
                 ")")]
        (-> (ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id subscribe-form)))
            (.then
              (fn [subscribe-resp]
                (if-not (:ok? subscribe-resp)
                  ;; Runtime refused (unknown topic, etc.) — surface verbatim.
                  (ok-text subscribe-resp)
                  (let [sub-id (:sub-id subscribe-resp)]
                    (js/Promise.
                      (fn [resolve _reject]
                        (let [tick               (atom 0)
                              delivered          (atom 0)
                              overflow*          (atom 0)
                              dropped-sensitive* (atom 0)
                              terminate
                              (fn [reason]
                                ;; Drop the runtime subscription and
                                ;; resolve. Idempotent — unsubscribe!
                                ;; returns :existed? false the second
                                ;; time.
                                (-> (nrepl/cljs-eval-value
                                      conn build-id
                                      (str "(re-frame-pair2.runtime/unsubscribe! "
                                           (pr-str sub-id) ")"))
                                    (.catch (fn [_] nil))
                                    (.then
                                      (fn [_]
                                        (resolve
                                          (ok-text
                                            (cond-> {:ok?        true
                                                     :sub-id     sub-id
                                                     :topic      topic
                                                     :delivered  @delivered
                                                     :overflow   @overflow*
                                                     :ticks      @tick
                                                     :reason     reason}
                                              (pos? @dropped-sensitive*)
                                              (assoc :dropped-sensitive @dropped-sensitive*))))))))
                              poll
                              (fn poll []
                                (cond
                                  ;; Client cancelled the tools/call.
                                  (and signal (.-aborted signal))
                                  (terminate :aborted)

                                  ;; Caller-supplied upper bounds.
                                  (and (pos? max-events)
                                       (>= @delivered max-events))
                                  (terminate :max-events-reached)

                                  :else
                                  (-> (nrepl/cljs-eval-value
                                        conn build-id
                                        (str "(re-frame-pair2.runtime/drain-subscription! "
                                             (pr-str sub-id) ")"))
                                      (.then
                                        (fn [drain-resp]
                                          (cond
                                            (:gone? drain-resp)
                                            (terminate :sub-gone)

                                            :else
                                            (let [raw-evts       (:events drain-resp)
                                                  ov             (:overflow drain-resp 0)
                                                  [evts dropped] (strip-sensitive
                                                                   (vec raw-evts) incl?)
                                                  n              (count evts)]
                                              (swap! overflow* + ov)
                                              (when (pos? dropped)
                                                (swap! dropped-sensitive* + dropped))
                                              (when (or (pos? n) (pos? ov))
                                                (swap! tick inc)
                                                (swap! delivered + n)
                                                (when (and send-note progress-tk)
                                                  (try
                                                    (send-note
                                                      #js {:method "notifications/progress"
                                                           :params (progress-payload
                                                                     progress-tk
                                                                     @tick
                                                                     (pr-str
                                                                       (cond-> {:sub-id sub-id
                                                                                :events (dedup-value evts dedup?)
                                                                                :dedup dedup?
                                                                                :overflow ov}
                                                                         (pos? dropped)
                                                                         (assoc :dropped-sensitive dropped)))
                                                                     ov)})
                                                    (catch :default _ nil))))
                                              (js/setTimeout poll poll-ms)))))
                                      (.catch
                                        (fn [_err]
                                          ;; nREPL hiccup — back off
                                          ;; and try again rather than
                                          ;; collapsing the stream.
                                          (js/setTimeout poll (* 2 poll-ms)))))))]
                          ;; Optional max-ms hard cap.
                          (when (pos? max-ms)
                            (js/setTimeout #(terminate :max-ms-reached) max-ms))
                          (poll))))))))
            (.catch (fn [err] (err->result :subscribe-failed err))))))))

(defn- unsubscribe-tool [conn args]
  (let [build-id (arg-build args)
        sub-id   (arg args :sub-id)]
    (cond
      (or (nil? sub-id) (str/blank? sub-id))
      (js/Promise.resolve
        (err-text {:ok? false :reason :missing-sub-id
                   :hint "usage: unsubscribe {sub-id '<uuid>'}"}))

      :else
      (let [form (str "(re-frame-pair2.runtime/unsubscribe! "
                      (pr-str sub-id) ")")]
        (-> (ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v] (ok-text (merge {:ok? true :sub-id sub-id}
                                           (when (map? v) v)))))
            (.catch (fn [err] (err->result :unsubscribe-failed err))))))))

;; ---------------------------------------------------------------------------
;; Tool descriptors — exposed via tools/list.
;;
;; Every descriptor gets a universal `max-tokens` property bolted on
;; by `with-budget-knob` before `tools/list` returns it. The cap is a
;; wire-boundary concern (see `apply-cap` above), not a per-tool one;
;; surfacing the knob universally means clients can discover and
;; override it without per-tool documentation drift.
;; ---------------------------------------------------------------------------

(def ^:private max-tokens-property
  {:type        "integer"
   :description (str "Wire-boundary token-budget cap (default "
                     default-max-tokens
                     "). Per spec/Principles.md §Tight token budget, "
                     "responses serialising over this estimate are "
                     "replaced with an `{:rf.mcp/overflow ...}` "
                     "marker. Pass 0 to disable the cap.")})

(def ^:private dedup-property
  "Per-tool descriptor slot for the `:dedup` opt-out (rf2-obpa9).
  Applied to surfaces that ship epoch slices (`snapshot`,
  `trace-window`, `watch-epochs`) and to the `subscribe` streaming
  channel — the surfaces where repeated subtrees dominate the wire
  cost. Default `true`."
  {:type        "boolean"
   :description (str "Apply structural dedup (day8/de-dupe) to the "
                     "epoch slice / event vector before the wire-cap "
                     "check. Default true. When deduped, the slot is "
                     "wrapped as `{:rf.mcp/dedup-table <cache-map>}` "
                     "and the agent host reconstructs via "
                     "`(de-dupe.core/expand cache-map)`. Pass false "
                     "to skip dedup — useful for ad-hoc reads when "
                     "the agent host hasn't been taught to call "
                     "`expand`.")})

(def ^:private elision-property
  "Per-tool descriptor slot for the `:elision` opt-out (rf2-urjnc).
  Applied to surfaces that surface `:app-db` slots (`snapshot` and
  `get-path`) — the surfaces where a declared-`:large?` slot or an
  over-threshold leaf can blow the wire cap on its own. Default
  `true`."
  {:type        "boolean"
   :description (str "Apply the size-elision walker "
                     "(`re-frame.core/elide-wire-value`, rf2-v9tw2) "
                     "to the `:app-db` slot server-side, before the "
                     "EDN crosses the wire. Default true. Declared "
                     "(`rf/declare-large-path!`) or schema-driven "
                     "(`:large? true`) paths get substituted with a "
                     "`{:rf.size/large-elided {:path [...] :bytes N "
                     ":type ... :handle [:rf.elision/at <path>]}}` "
                     "marker; the agent re-fetches via `get-path` "
                     "using the handle's path. Auto-detect fires on "
                     "leaf strings over the configured "
                     "`:rf.size/threshold-bytes`. Pass false to "
                     "bypass elision and receive the raw value — "
                     "useful when the agent has explicit override "
                     "permission for the slot (e.g. debugging the "
                     "elided value itself).")})

(defn- with-budget-knob
  "Splice `max-tokens` into a tool descriptor's inputSchema.properties.
  No-op if the descriptor already declares it (forward-compat)."
  [desc]
  (let [props (get-in desc [:inputSchema :properties])]
    (if (contains? props :max-tokens)
      desc
      (assoc-in desc [:inputSchema :properties :max-tokens]
                max-tokens-property))))

(def tool-descriptors
  [{:name "discover-app"
    :description "Verify the shadow-cljs nREPL is reachable, confirm the pair2 runtime preload landed, and report a health summary. Run this first every session. Returns :reason :runtime-not-preloaded when the preload entry is missing."
    :inputSchema {:type "object"
                  :properties {:build {:type "string"
                                       :description "shadow-cljs build id (default: app)"}}
                  :additionalProperties false}}
   {:name "eval-cljs"
    :description "Evaluate a ClojureScript form in the connected browser runtime via shadow-cljs's cljs-eval. Returns the EDN value."
    :inputSchema {:type "object"
                  :properties {:form  {:type "string" :description "The CLJS form to evaluate."}
                               :build {:type "string" :description "shadow-cljs build id (default: app)"}}
                  :required ["form"]
                  :additionalProperties false}}
   {:name "dispatch"
    :description "Fire a re-frame2 event tagged with :origin :pair. Default mode is queued dispatch. Set `sync` for dispatch-sync, `trace` for synchronous dispatch returning the assembled :rf/epoch-record."
    :inputSchema {:type "object"
                  :properties {:event {:type "string" :description "The event vector, e.g. [:cart/checkout]"}
                               :sync  {:type "boolean"}
                               :trace {:type "boolean"}
                               :frame {:type "string" :description "Operating frame (e.g. :stories)"}
                               :fx-overrides {:type "object"
                                              :description "Per-call fx redirects, e.g. {:http :stub-http}"}
                               :build {:type "string"}}
                  :required ["event"]
                  :additionalProperties false}}
   {:name "trace-window"
    :description (str "Return the :rf/epoch-records added in the last N ms for the operating frame. "
                      "Per spec/009 §Privacy this forwarder default-drops items carrying `:sensitive? true` "
                      "at the top level; opt back in with `include-sensitive? true`. Dropped count surfaces "
                      "as `:dropped-sensitive` on the result when non-zero. "
                      "Each epoch's :db-after is diff-encoded against its own :db-before by default (rf2-1wdzp) "
                      "— pass `epochs-mode \"full\"` for the legacy full-pair shape (needed for time-travel restore). "
                      "The epoch vector is structurally deduped by default (rf2-obpa9) — repeated subtrees "
                      "(notably the per-record `:db-before` reference) collapse to a `{:rf.mcp/dedup-table ...}` "
                      "wrapper; the agent host calls `(de-dupe.core/expand cache)` to reconstruct. Pass `dedup false` to skip.")
    :inputSchema {:type "object"
                  :properties {:ms    {:type "integer" :description "Window size in milliseconds (default 1000)"}
                               :frame {:type "string"}
                               :epochs-mode {:type "string"
                                             :description "How :db-after rides the wire: \"diff\" (default, intra-record structural diff against :db-before) or \"full\" (legacy full snapshot, opt-in for time-travel)."
                                             :enum ["diff" "full"]}
                               :dedup dedup-property
                               :include-sensitive? {:type "boolean"
                                                    :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                               :build {:type "string"}}
                  :additionalProperties false}}
   {:name "watch-epochs"
    :description (str "Pull-mode poll: returns the epochs matching `pred` that landed after `since-id`. "
                      "Call repeatedly to live-watch. Predicate keys: :event-id, :event-id-prefix, :effects, "
                      ":touches-path, :sub-ran, :render, :origin, :frame. Per spec/009 §Privacy this forwarder "
                      "default-drops items carrying `:sensitive? true`; opt back in with `include-sensitive? true`. "
                      "Each epoch's :db-after is diff-encoded against its own :db-before by default (rf2-1wdzp) "
                      "— pass `epochs-mode \"full\"` for the legacy full-pair shape. "
                      "The matches vector is structurally deduped by default (rf2-obpa9); pass `dedup false` to skip.")
    :inputSchema {:type "object"
                  :properties {:since-id {:type "string" :description "The last epoch id you've seen (omit to start fresh)"}
                               :pred     {:type "object" :description "Filter map"}
                               :frame    {:type "string"}
                               :epochs-mode {:type "string"
                                             :description "How :db-after rides the wire: \"diff\" (default) or \"full\" (legacy, opt-in for time-travel restore)."
                                             :enum ["diff" "full"]}
                               :dedup    dedup-property
                               :include-sensitive? {:type "boolean"
                                                    :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                               :build    {:type "string"}}
                  :additionalProperties false}}
   {:name "tail-build"
    :description "Wait for a hot-reload to land by polling a probe form until its value changes. Returns once changed, or times out."
    :inputSchema {:type "object"
                  :properties {:probe   {:type "string" :description "CLJS form whose value should change after the reload"}
                               :wait-ms {:type "integer" :description "Max wait in ms (default 5000)"}
                               :build   {:type "string"}}
                  :additionalProperties false}}
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
                      "`epochs-mode \"full\"` for legacy full-pair shape (opt-in for time-travel restore). "
                      "Diff-encode runs before the lazy-summary so `bytes` hints reflect post-shrink cost. "
                      "Per spec/009 §Privacy the `:traces` and `:epochs` slices default-drop items carrying "
                      "`:sensitive? true`; opt back in with `include-sensitive? true`. App-db / sub-cache / "
                      "machines slices pass through unchanged — payload redaction is the `with-redacted` "
                      "interceptor's job, not the forwarder's. "
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
                      "the raw value.")
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
                                             :description "How :db-after rides the wire in the :epochs slice: \"diff\" (default, intra-record structural diff against :db-before) or \"full\" (legacy full snapshot, opt-in for time-travel)."
                                             :enum ["diff" "full"]}
                               :dedup    dedup-property
                               :elision  elision-property
                               :include-sensitive? {:type "boolean"
                                                    :description "Opt back in to forwarding `:sensitive? true` items in the :traces / :epochs slices. Default false."}
                               :build   {:type "string" :description "shadow-cljs build id (default: app)"}}
                  :additionalProperties false}}
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
                      "to bypass the walk and receive the raw value.")
    :inputSchema {:type "object"
                  :properties {:path  {:description (str "Path into app-db. EDN-encoded vector of keys "
                                                         "(e.g. \"[:cart :items 0 :sku]\") or a JSON array "
                                                         "of segment strings (each parsed as EDN — bare "
                                                         "strings stay as map-key strings).")
                                       :oneOf [{:type "string"}
                                               {:type "array" :items {:type "string"}}]}
                               :frame   {:type "string"
                                         :description "Frame-id (e.g. \":rf/default\"). Defaults to the operating frame."}
                               :elision elision-property
                               :build   {:type "string"}}
                  :required ["path"]
                  :additionalProperties false}}
   {:name "subscribe"
    :description (str "Open a streaming subscription on the trace or epoch bus. Push-mode replacement for watch-epochs. "
                      "Long-running tools/call — emits each batch of matching events as a notifications/progress notification "
                      "(correlated via the call's progressToken), and resolves with a summary when the client cancels or an "
                      "unsubscribe op fires. Topics: 'trace' (raw trace stream), 'epoch' (assembled :rf/epoch-records), "
                      "'fx' (trace stream filtered to :op-type :fx), 'error' (trace stream filtered to :op-type :error). "
                      "Filter vocab depends on topic — :trace/:fx/:error accept the (rf/trace-buffer) filter map "
                      "(:operation :op-type :frame :severity :event-id :handler-id :source :origin :dispatch-id :since-ms :between); "
                      ":epoch accepts the epoch-matches? predicate map (:event-id :event-id-prefix :effects :touches-path "
                      ":sub-ran :render :origin :frame). Pass `filter` either as a JSON object or as an EDN-encoded string. "
                      "Per spec/009 §Privacy this forwarder default-drops events carrying `:sensitive? true` at the top "
                      "level; opt back in with `include-sensitive? true`. Dropped count surfaces as `:dropped-sensitive` "
                      "on each progress payload (when non-zero) and the final summary. "
                      "Each progress payload's `:events` vector is structurally deduped by default (rf2-obpa9) — "
                      "shared subtrees across the tick collapse to a `{:rf.mcp/dedup-table ...}` wrapper; "
                      "agent host reconstructs via `(de-dupe.core/expand cache-map)`. Dedup is per-tick, not "
                      "per-stream — each notifications/progress frame carries its own cache, no cross-tick "
                      "references. Pass `dedup false` to skip.")
    :inputSchema {:type "object"
                  :properties {:topic   {:type "string"
                                         :description "Topic name. Required."
                                         :enum ["trace" "epoch" "fx" "error"]}
                               :filter  {:description "Filter map (JSON object) or EDN string. Vocab depends on topic."
                                         :oneOf [{:type "object"}
                                                 {:type "string"}]}
                               :max-buffered {:type "integer"
                                              :description "Runtime-side queue cap. Default 500. Overflow is counted, not blocked."}
                               :poll-ms {:type "integer"
                                         :description "Server poll cadence in ms. Default 100."}
                               :max-ms  {:type "integer"
                                         :description "Hard upper-bound on how long the subscription stays open, ms. 0 = unbounded (close on cancel only). Default 0."}
                               :max-events {:type "integer"
                                            :description "Terminate after this many events have been delivered. 0 = unbounded. Default 0."}
                               :dedup    dedup-property
                               :include-sensitive? {:type "boolean"
                                                    :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                               :build   {:type "string"}}
                  :required ["topic"]
                  :additionalProperties false}}
   {:name "unsubscribe"
    :description "Close the subscription with the given sub-id. Idempotent — closing an unknown sub-id returns :existed? false."
    :inputSchema {:type "object"
                  :properties {:sub-id {:type "string"
                                        :description "The uuid returned by `subscribe`."}
                               :build  {:type "string"}}
                  :required ["sub-id"]
                  :additionalProperties false}}])

(defn tool-descriptors-js []
  (clj->js (mapv with-budget-knob tool-descriptors)))

(defn- dispatch-tool*
  "Route a `tools/call` to the per-tool implementation. Unknown tools
  resolve to an isError result rather than throwing — keeps the server
  loop simple."
  [conn name args extra]
  (case name
    "discover-app"     (discover-app   conn args)
    "eval-cljs"        (eval-cljs-tool conn args)
    "dispatch"         (dispatch-tool  conn args)
    "trace-window"     (trace-window-tool conn args)
    "watch-epochs"     (watch-epochs-tool conn args)
    "tail-build"       (tail-build-tool conn args)
    "snapshot"         (snapshot-tool  conn args)
    "get-path"         (get-path-tool  conn args)
    "subscribe"        (subscribe-tool conn args extra)
    "unsubscribe"      (unsubscribe-tool conn args)
    (js/Promise.resolve
      (err-text {:ok? false :reason :unknown-tool :tool name}))))

(defn invoke
  "Dispatch a `tools/call` invocation to the right tool implementation.
  Returns a Promise resolving to the MCP result object.

  Every result passes through `apply-cap` at the wire boundary —
  responses whose serialised size exceeds the per-call cap (default
  5,000 tokens, configurable via the `max-tokens` MCP arg) are
  replaced with an `{:rf.mcp/overflow ...}` marker. The cap is
  enforced here, not just documented; see the `apply-cap` docstring
  for the pluggable-strategy design.

  `extra` carries the MCP `extra` payload (signal + sendNotification +
  _meta.progressToken) for streaming tools. Non-streaming tools
  ignore it."
  [conn name args extra]
  (let [cap-opts {:tool     name
                  :cap      (max-tokens-arg args)
                  :strategy :truncate-with-marker}]
    (-> (dispatch-tool* conn name args extra)
        (.then (fn [result] (apply-cap result cap-opts))))))

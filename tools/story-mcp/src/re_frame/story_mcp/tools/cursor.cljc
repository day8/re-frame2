(ns re-frame.story-mcp.tools.cursor
  "Cursor pagination for the Docs `list-*` tools.

  Implements the spec/Principles.md §'Tight token budget' pagination
  MUST: every read tool whose return size is a function of registry
  size MUST accept a `:limit` argument and return a `:cursor` for
  continuation. The default `:limit` MUST keep the response under the
  cap (5,000 tokens).

  ## Shared codec, story-specific shape

  The base64 codec, the tagged-literal-rejecting EDN reader, the 1 KB
  size cap, the `::malformed` recovery posture, the `:limit` clamp, and
  the `cursor-stale-result` envelope all live in
  `re-frame.mcp-base.cursor` — one cross-MCP implementation shared with
  re-frame2-pair-mcp. This ns owns only what is genuinely
  story-specific:

    - the cursor PAYLOAD shape (`{:v :offset :total :sig}`) + its
      `valid?` predicate,
    - the whole-set `fingerprint` drift detector,
    - the `default-limit` / `max-limit` numbers, and
    - the `page` windowing logic.

  ## Why story-mcp's cursor SHAPE differs from pair-mcp's

  Pair-MCP's cursors carry an `:after-id` epoch-id because epochs live
  in a bounded ring buffer — staleness matters when the ring rotates
  past the cursor's id. Story-MCP's registries (`stories`, `tags`,
  `modes`, `decorators`, `assertions`) are append-mostly stable
  structures — no ring rotation, no buffer eviction. A stable sort over
  the id-set + integer offset is sufficient; the `:sig` fingerprint
  detects a registry that materially changed between cursor mint and
  dereference (e.g. a `register-variant` landed between two pages of
  `list-stories`). When the live signature doesn't match the cursor's,
  we return `:rf.mcp/cursor-stale` — same vocab as pair-mcp's
  ring-rotation case. The agent restarts.

  ## When pagination kicks in

  The default `:limit` is sized per tool's `:typicalTokens` budget so
  small registries return everything in one call (no cursor in the
  response). Pagination only activates when the entry count exceeds
  the limit. The response shape:

      ;; small set, no pagination needed:
      {:stories [...]}

      ;; large set, paginated:
      {:stories [...]                       ; <= :limit entries
       :total 137                           ; whole-set count
       :limit 25
       :has-more? true
       :next-cursor \"<base64>\"}

      ;; final page:
      {:stories [...]
       :total 137
       :limit 25
       :has-more? false
       :next-cursor nil}"
  (:require [re-frame.mcp-base.cursor :as base-cursor]
            [re-frame.story-mcp.tools.result :as result]))

(def ^:const default-limit
  "Default page size for the Docs `list-*` tools. Sized to keep the
  response under the 5K-token cap for typical registry shapes
  (`{:id ... :doc ... :tags [...]}`-style entries — ~150-300 chars per
  entry pretty-printed). At 25 entries we leave headroom for the
  envelope + per-entry padding; agents that have explicit budget
  headroom raise via the `:limit` arg."
  25)

(def ^:const max-limit
  "Hard ceiling on `:limit`. The wire-boundary cap will
  catch oversize responses regardless, but we clamp the arg at the
  tool surface so the agent gets a deterministic per-page count rather
  than a `:rf.mcp/overflow` fallback. 200 is well past what any
  registry should need on a single page."
  200)

(defn- fingerprint
  "Cheap whole-set fingerprint — a sorted-hash of the id-set. We use
  this to detect a registry that materially changed between cursor
  mint and dereference. The sort is required for determinism (sets
  don't order); the hash is the JVM `hash` over the sorted seq.

  Pure data computation (no salt, no secrets) — the fingerprint is a
  drift detector, not a security token."
  [ids]
  (str (hash (vec (sort-by str ids)))))

(defn- nat-int?*
  "A non-negative integer. Used as the cursor `:offset` / `:total`
  bound. (`clojure.core/nat-int?` exists on the JVM but not in older
  CLJS cores; this leaf is `.cljc` so we spell the check out to stay
  portable — `integer?` + non-negative.)"
  [x]
  (and (integer? x) (not (neg? x))))

(defn- payload-valid?
  "The story cursor payload shape: a versioned map carrying the
  natural-integer offset/total + the whole-set fingerprint. Passed to
  the shared `base-cursor/decode-cursor` so the codec is shared while
  the shape check stays story-specific.

  Wire-boundary range gate. `:offset` and `:total` MUST be
  NATURAL integers (a negative offset would feed `subvec` a negative
  start and throw a generic handler exception instead of the documented
  cursor-stale envelope), and `:offset` MUST NOT exceed `:total` (an
  over-total offset would slice an empty window and silently skip the
  tail of the registry). A cursor failing this shape decodes to the
  `::base-cursor/malformed` sentinel, which `page` already maps onto the
  drop-and-restart cursor-stale recovery — so a tampered/edited cursor
  recovers through the contract rather than crashing or losing rows.

  Note `:total` is NOT cross-checked against the LIVE total here — this
  predicate has no registry context. `page` already validates the
  payload's `:sig` against the live whole-set fingerprint, and the
  fingerprint changes whenever the id-set (and therefore the count)
  changes; a stale `:total` is caught there as a sig mismatch."
  [v]
  (and (map? v)
       (= 1 (:v v))
       (nat-int?* (:offset v))
       (nat-int?* (:total v))
       (<= (:offset v) (:total v))
       (string? (:sig v))))

(defn parse-limit-arg
  "Normalise the `:limit` MCP arg into an integer in `[1, max-limit]`,
  default `default-limit`. Thin wrapper over the shared
  `base-cursor/parse-limit-arg` baking story-mcp's default + ceiling."
  [raw]
  (base-cursor/parse-limit-arg raw default-limit max-limit))

(defn encode-cursor
  "Encode a cursor payload as a base64 string. Returns nil when there
  are no more entries (offset has reached total) — the absence-of-cursor
  IS the end-of-pagination signal. Delegates the codec to the shared
  `base-cursor/encode-cursor`; owns only the story-specific
  `{:v :offset :total :sig}` shape + the end-of-page guard."
  [{:keys [offset total sig]}]
  (when (and (integer? offset) (< offset total))
    (base-cursor/encode-cursor {:v 1 :offset offset :total total :sig sig})))

(defn decode-cursor
  "Decode a base64 cursor back to its `{:v :offset :total :sig}` payload.
  Returns nil for an absent/blank cursor, `::base-cursor/malformed` for
  one that doesn't decode to a valid story payload. The codec + size cap
  + tagged-literal rejection are shared (`base-cursor/decode-cursor`);
  the `payload-valid?` shape check is story-specific."
  [s]
  (base-cursor/decode-cursor s payload-valid?))

(defn cursor-stale-result
  "Structured cursor-stale error result via the shared envelope builder.
  Uses the cross-MCP vocab `:rf.mcp/cursor-stale` — same vocab pair-mcp
  uses for ring-rotation staleness, so an agent that learned the
  recovery path on pair-mcp reuses it here.

  In story-mcp's case staleness means the underlying id-set changed
  between cursor-mint and cursor-deref (e.g. a `register-variant`
  landed between two pages of `list-stories`). The agent restarts;
  there is no recovery via wider window — the registry is the source
  of truth."
  [tool]
  (base-cursor/cursor-stale-result
    result/error-result
    tool
    {:message (str "Cursor stale: the registry changed between pages. Drop the "
                   "cursor and restart `" tool "`.")
     :hint    "Drop :cursor and re-request from offset 0."}))

(defn page
  "Apply pagination to a sorted seq of entries.

  Inputs:
    - `entries`     — the full sorted vector of entries to paginate.
                      (Sort externally; this fn assumes a stable
                      caller-determined ordering.)
    - `ids`         — the underlying id set used to compute the
                      fingerprint. Pass the same source the entries
                      were derived from so cursor-deref can validate.
    - `arguments`   — the MCP tool arg map (`:limit`, `:cursor`).
    - `tool-name`   — used for the stale-cursor error message.

  Returns either:
    - `[:ok page-vec page-metadata]` — happy path. `page-vec` is the
      sliced entries; `page-metadata` is a map of `:total :limit
      :has-more? :next-cursor` slots the caller merges into their
      payload.
    - `[:err error-result]` — cursor was malformed or stale; the
      caller returns this result directly.

  The caller assembles the final payload by merging the page-metadata
  into the tool's normal response shape (the metadata slots only land
  when pagination actually kicked in — small registries that fit on
  one page return `[:ok entries {}]`).

  Most callers don't touch this directly — they reach for the
  `paged-result` wrapper below, which folds the `[:ok …]` / `[:err …]`
  branch + the `result/text-result` build into one call. `page` is
  public for the rare caller that wants the raw slice."
  [entries ids arguments tool-name]
  (let [limit         (parse-limit-arg (:limit arguments))
        cursor        (decode-cursor (:cursor arguments))
        total         (count entries)
        live-sig      (fingerprint ids)]
    (cond
      ;; Malformed or stale cursor — same recovery (drop + restart).
      (base-cursor/malformed? cursor)
      [:err (cursor-stale-result tool-name)]

      ;; Cursor present but the underlying set changed between mint
      ;; and deref. Agent restarts.
      (and cursor (not= (:sig cursor) live-sig))
      [:err (cursor-stale-result tool-name)]

      :else
      (let [offset    (or (:offset cursor) 0)
            end       (min total (+ offset limit))
            page-vec  (subvec (vec entries) (min offset total) end)
            has-more? (< end total)
            next-c    (when has-more?
                        (encode-cursor {:offset end :total total :sig live-sig}))
            ;; The pagination-metadata slots only land when pagination
            ;; actually kicked in — a small registry that fits on one
            ;; page returns the bare entries without `:total` etc., so
            ;; the small-registry common case is unchanged on the wire.
            meta-map  (if (or cursor has-more?)
                        {:total       total
                         :limit       limit
                         :has-more?   has-more?
                         :next-cursor next-c}
                        {})]
        [:ok page-vec meta-map]))))

(defn paged-result
  "Page `entries` and build the tool's `tools/call` result in one step —
  the shape every Docs `list-*` handler shares. Pages via
  `page`; on a malformed/stale cursor returns the `cursor-stale-result`
  envelope directly; otherwise calls `(page->payload page-vec)` to build
  the tool-specific base payload, merges in the pagination metadata
  (`:total` / `:limit` / `:has-more?` / `:next-cursor` — present only
  when pagination kicked in), and wraps it in the dual-slot
  `text-result` envelope.

  `page->payload` receives the page slice and returns the base payload
  map (e.g. `(fn [p] {:stories p})` for `list-stories`, or the
  bifurcated `{:canonical … :registered p}` for `list-assertions`).
  This folds the four-line `(let [[res page meta] (page …)] (if (= res
  :err) …))` boilerplate into a single call, so each handler reads as
  'compute entries, then page them under this shape'."
  [entries ids arguments tool-name page->payload]
  (let [[res page-vec meta] (page entries ids arguments tool-name)]
    (if (= res :err)
      page-vec
      (result/edn-result (merge (page->payload page-vec) meta)))))

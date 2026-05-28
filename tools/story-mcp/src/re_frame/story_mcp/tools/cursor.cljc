(ns re-frame.story-mcp.tools.cursor
  "Cursor pagination for the Docs `list-*` tools (rf2-76sf6).

  Implements the spec/Principles.md §'Tight token budget' pagination
  MUST: every read tool whose return size is a function of registry
  size MUST accept a `:limit` argument and return a `:cursor` for
  continuation. The default `:limit` MUST keep the response under the
  cap (5,000 tokens).

  ## Shared codec, story-specific shape (rf2-ee38b.17)

  The base64 codec, the tagged-literal-rejecting EDN reader, the 1 KB
  size cap, the `::malformed` recovery posture, the `:limit` clamp, and
  the `cursor-stale-result` envelope all live in
  `re-frame.mcp-base.cursor` — one cross-MCP implementation shared with
  re-frame2-pair-mcp (the clarity review's `mcp-base` deferral premise
  went stale once story-mcp shipped its own copy). This ns now owns
  only what is genuinely story-specific:

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
  "Hard ceiling on `:limit` (rf2-76sf6). The wire-boundary cap will
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

(defn- payload-valid?
  "The story cursor payload shape: a versioned map carrying the integer
  offset/total + the whole-set fingerprint. Passed to the shared
  `base-cursor/decode-cursor` so the codec is shared while the shape
  check stays story-specific."
  [v]
  (and (map? v)
       (= 1 (:v v))
       (integer? (:offset v))
       (integer? (:total v))
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
  one page return `[:ok entries {}]`)."
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

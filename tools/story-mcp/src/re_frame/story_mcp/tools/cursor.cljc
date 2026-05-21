(ns re-frame.story-mcp.tools.cursor
  "Cursor pagination for the Docs `list-*` tools (rf2-76sf6).

  Implements the spec/Principles.md §'Tight token budget' pagination
  MUST: every read tool whose return size is a function of registry
  size MUST accept a `:limit` argument and return a `:cursor` for
  continuation. The default `:limit` MUST keep the response under the
  cap (5,000 tokens).

  ## Why story-mcp's cursor differs from pair-mcp's

  Pair-MCP's cursors (`tools/re-frame2-pair-mcp/tools/cursor.cljs`) carry
  an `:after-id` epoch-id because epochs live in a bounded ring buffer
  — staleness matters: when enough new epochs land between calls that
  the ring rotates past the cursor's id, the server returns
  `:rf.mcp/cursor-stale` so the agent can recover. The cursor is opaque
  base64-encoded EDN to keep the encoding implementation-detail.

  Story-MCP's registries (`stories`, `tags`, `modes`, `decorators`,
  `assertions`) are append-mostly stable structures — no ring rotation,
  no buffer eviction. A stable sort over the id-set + integer offset
  is sufficient. We still wrap the offset in an opaque base64 cursor
  so the wire shape matches pair-mcp's contract — an agent that
  learned `:cursor`/`:next-cursor` on pair-mcp uses the same slot here.

  ## Cursor shape

  Opaque base64 of a pr-str'd EDN map:

      {:v 1
       :offset N             ; 0-based index into the stable-sorted seq
       :total  N             ; total count at the time the cursor was minted
       :sig    \"<digest>\"} ; cheap whole-set fingerprint

  The `:sig` slot lets us detect a registry that materially changed
  between calls (e.g. a new `register-variant` landed between the
  first list-stories page and the second). When the live signature
  doesn't match the cursor's, we return `:rf.mcp/cursor-stale` —
  same vocab as pair-mcp's ring-rotation case. The agent restarts.

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
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [re-frame.mcp-base.args :as args]
            [re-frame.mcp-base.vocab :as vocab]
            [re-frame.story-mcp.tools.helpers :as h])
  #?(:clj (:import (java.util Base64))))

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

(defn- b64-encode
  "Base64-encode a UTF-8 string. JVM-only (story-mcp's canonical
  deploy)."
  [^String s]
  #?(:clj  (-> (Base64/getEncoder)
               (.encodeToString (.getBytes s "UTF-8")))
     :cljs (-> (js/Buffer.from s "utf8")
               (.toString "base64"))))

(defn- b64-decode
  "Decode a base64 string back to UTF-8."
  [^String s]
  #?(:clj  (String. (.decode (Base64/getDecoder) s) "UTF-8")
     :cljs (-> (js/Buffer.from s "base64")
               (.toString "utf8"))))

(defn- fingerprint
  "Cheap whole-set fingerprint — a sorted-hash of the id-set. We use
  this to detect a registry that materially changed between cursor
  mint and dereference. The sort is required for determinism (sets
  don't order); the hash is the JVM `hash` over the sorted seq.

  Pure data computation (no salt, no secrets) — the fingerprint is a
  drift detector, not a security token."
  [ids]
  (str (hash (vec (sort-by str ids)))))

(defn parse-limit-arg
  "Normalise the `:limit` MCP arg into an integer in
  `[1, max-limit]`. Default `default-limit`. Caller-supplied values
  above `max-limit` clamp DOWN (the same posture as `run-variant`'s
  `:timeout-ms` ceiling — a legitimate large request still works,
  just capped)."
  [raw]
  (min max-limit (args/parse-positive-int raw default-limit)))

(defn encode-cursor
  "Encode a cursor payload as a base64 string. Returns nil when there
  are no more entries — the absence-of-cursor IS the end-of-pagination
  signal."
  [{:keys [offset total sig]}]
  (when (and (integer? offset) (< offset total))
    (b64-encode (pr-str {:v 1 :offset offset :total total :sig sig}))))

(defn decode-cursor
  "Decode a base64 cursor back to its EDN payload. Returns:
    - `nil` if the cursor arg is absent or blank.
    - `::malformed` if the cursor exists but doesn't decode to a
      well-formed payload map. Callers treat `::malformed` the same as
      `::stale` — the agent must drop the cursor and restart.

  Hardened against the same EDN-reader posture as `register-variant`'s
  body slot (`tools/write.cljc`): the `:default` reader handler throws
  on any custom tagged literal; the input is decoded only once.
  Cursors are short opaque tokens — anything longer than 1 KB is
  rejected before parsing."
  [s]
  (cond
    (or (nil? s) (and (string? s) (str/blank? s))) nil
    (not (string? s)) ::malformed
    (> (count s) 1024) ::malformed
    :else
    (try
      (let [edn (b64-decode s)
            v   (edn/read-string {:default (fn [_t _v]
                                             ;; Caught by the surrounding try
                                             ;; → ::malformed; the canonical
                                             ;; :rf.error/id rides on ex-data
                                             ;; for any consumer that inspects.
                                             (throw (ex-info ":rf.error/story-mcp-bad-edn-tag"
                                                             {:rf.error/id :rf.error/story-mcp-bad-edn-tag
                                                              :where    'story-mcp/decode-cursor
                                                              :recovery :no-recovery
                                                              :reason   "EDN cursor carried a tagged literal — none are permitted"})))} edn)]
        (if (and (map? v)
                 (= 1 (:v v))
                 (integer? (:offset v))
                 (integer? (:total v))
                 (string? (:sig v)))
          v
          ::malformed))
      (catch #?(:clj Throwable :cljs :default) _ ::malformed))))

(defn cursor-stale-result
  "Structured cursor-stale error result. Uses the cross-MCP vocab
  `:rf.mcp/cursor-stale` (`re-frame.mcp-base.vocab/cursor-stale-reason`)
  — same vocab pair-mcp uses for ring-rotation staleness, so an agent
  that learned the recovery path on pair-mcp reuses it here.

  In story-mcp's case staleness means the underlying id-set changed
  between cursor-mint and cursor-deref (e.g. a `register-variant`
  landed between two pages of `list-stories`). The agent restarts;
  there is no recovery via wider window — the registry is the source
  of truth."
  [tool]
  (h/error-result
    (str "Cursor stale: the registry changed between pages. Drop the "
         "cursor and restart `" tool "`.")
    {:ok?    false
     :reason vocab/cursor-stale-reason
     :tool   tool
     :hint   "Drop :cursor and re-request from offset 0."}))

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
      (= cursor ::malformed)
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

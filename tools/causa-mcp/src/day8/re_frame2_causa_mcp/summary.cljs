(ns day8.re-frame2-causa-mcp.summary
  "Wire-pipeline mechanism W-4 at the Causa-MCP boundary (rf2-8xzoe.8).
  Lazy summary mode — per
  `tools/causa-mcp/spec/004-Wire-Pipeline.md` §4 (Lazy summary).

  ## What this provides

  The **default response mode** for any tool returning a rich
  nested value is a **summary**, not the full payload. A summary
  declares the shape without committing the budget:

      {:rf.mcp/summary {:type   :map | :vector | :set | :seq | :scalar
                        :keys   [:cart :user :ui ...]   ; :map only
                        :counts {:cart 47 :user 3 ...}   ; :map only
                        :count  N                         ; non-map
                        :bytes  ~int}}

  Tools MUST expose a `:mode` argument with at least:
    - `:summary` (default) — the marker above.
    - `:sample` — a bounded prefix of the value (configurable
                  sample size; default 8 entries).
    - `:full` — the complete payload, subject to W-1 token cap
                and (where applicable) W-3 pagination.

  Agents drill down via W-2 `:path` or W-3 `:cursor` once the
  shape is known; `:full` is opt-in for cases where the agent
  genuinely needs everything.

  ## Causa-specific `:counts` shape

  The causa-spec divergence from pair2-mcp's `tools.summary` is
  the `:counts` map: pair2-mcp emits a single scalar `:count`
  (total entry count), while causa-mcp's `:map` summary emits a
  per-key `:counts` map (the cardinality of each top-level
  value) so the agent can prioritise drill-down without a second
  call.

  For non-`:map` shapes (`:vector` / `:set` / `:seq`) the per-key
  decomposition is meaningless; those carry a single scalar
  `:count` like pair2-mcp.

  ## Cheap `:bytes` estimator (rf2-qta8j shape)

  The `:bytes` field is a cheap APPROXIMATION (per pair2-mcp
  rf2-qta8j; cross-MCP convention). Earlier candidates that paid
  `(count (pr-str v))` on every branch deep-serialised the
  payload just to discard the string — contradicts the marker's
  cost model (a 54MB app-db slice burns a 54MB string allocation
  per summary). The estimator is `entry-count × per-entry
  constant` — order-of-magnitude correct, constant-time.

  Agents needing a precise size measure their drill-down result
  directly. The marker's `:bytes` slot is for planning (`is this
  slice worth drilling?`), not for budgeting.

  ## Mode-arg parsing

  `parse-mode-arg` is the cross-server `:mode` MCP arg resolver.
  Recognised values: `:summary` (default), `:sample`, `:full`.
  Unrecognised values fall back to the cap-sensitive default
  (`:summary`) per the bounded-allowlist-keyword discipline
  (rf2-ih7g4) — an out-of-vocab agent input never interns a
  fresh keyword.

  ## Composition with W-1 / W-2 / W-3 / W-6 / B-1

  - **With W-1 (token-cap)**: `:summary` is the primary cap
    defence — the marker is always small (tens of tokens for a
    typical app-db root). `:full` mode is opt-in and may trip
    W-1's `apply-cap` with `:hint :switch-mode` (recall with
    `:sample` or `:summary`).
  - **With W-2 (path slicing)**: the dispatcher consults the
    parsed `:path` first; if non-nil, slice; if nil, take the
    summary branch (the MUST 9 cascade — `path-slice/parse-path-
    arg` returns nil signalling summary-mode).
  - **With W-3 (cursor pagination)**: `:full` mode pages via
    W-3 when the addressed value is sequence-shaped. `:sample`
    and `:summary` modes are non-paginated (bounded by
    construction).
  - **With W-6 (size-elision)**: `:summary` operates on the
    already-walked tree — large leaves are already markers, the
    summary just describes the top-level shape.
  - **With B-1 (privacy)**: orthogonal — privacy lives on the
    trace-stream surface, summary lives on the direct-read
    surface.

  ## MUSTs honoured

  - MUST 12 — every tree-typed tool exposes a `:mode` argument
    with at least `:summary` (default), `:sample`, `:full`
    (spec/004 §4 L303-306). `parse-mode-arg` is the single
    normative parser; the per-tool dispatcher calls it once on
    the raw args object."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.args :as base-args]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; ---------------------------------------------------------------------------
;; Constants — mode vocabulary, summary cap, sample-size default.
;; ---------------------------------------------------------------------------

(def mode-vocabulary
  "The closed set of `:mode` values per spec/004 §4 (L303-306). An
  agent pattern-matches on this set; an out-of-vocab value would
  break the agent's exhaustive case. Reified as a set so the
  parser can guard against fresh-keyword interning (rf2-ih7g4).

  Closed set:
    - `:summary` — the default; shape marker only.
    - `:sample`  — bounded prefix (default `default-sample-size`).
    - `:full`    — paginated complete payload.

  Tools MUST expose all three modes per MUST 12."
  #{:summary :sample :full})

(def ^:const default-mode
  "Default `:mode` per spec/004 §4 L304 — `:summary`. The cap-
  sensitive default; an agent that doesn't supply `:mode` gets
  the smallest-payload shape."
  :summary)

(def ^:const summary-keys-cap
  "Top-N keys included verbatim in a tree-summary marker. Above
  this, the summary truncates the `:keys` vector and stamps
  `:keys-truncated? true` so the marker itself stays bounded —
  a 5,000-entry map's key list alone would exceed the W-1 wire
  cap otherwise.

  64 is large enough that a human-scale app-db root surfaces
  every key, and small enough that the marker is always tens of
  tokens. Cross-MCP-aligned with pair2-mcp's
  `tools.summary/summary-keys-cap`."
  64)

(def ^:const default-sample-size
  "Default `:sample-size` MCP arg when `:mode :sample` is
  requested. 8 entries is the rule-of-thumb for a useful prefix
  that still fits the W-1 cap on typical record sizes — a 5K-token
  cap divided by ~600-token-per-record envelope is ~8 records.

  The sample is a bounded PREFIX (the first N entries by natural
  collection order), not a stratified sample. Agents that need
  representative coverage across a large value drill into
  specific paths via W-2 `:path`."
  8)

(def ^:const min-sample-size 1)

(def ^:const max-sample-size
  "Upper clamp on `:sample-size`. 256 entries is generous (covers
  most realistic exploratory windows) without blowing the W-1
  cap. Agents asking for more than 256 should switch to
  `:mode :full` + W-3 cursor pagination, not a giant sample."
  256)

;; ---------------------------------------------------------------------------
;; Cheap `:bytes` estimator (rf2-qta8j cross-MCP shape).
;; ---------------------------------------------------------------------------

(def ^:const ^:private map-bytes-per-entry  16)
(def ^:const ^:private coll-bytes-per-entry 8)

(defn- approx-map-bytes  [n] (* map-bytes-per-entry  n))
(defn- approx-coll-bytes [n] (* coll-bytes-per-entry n))

(defn- entry-count
  "Counted-aware count helper — works on seqs lazily (one pass)
  and counted colls in O(1)."
  [v]
  (if (counted? v) (count v) (count v)))

(defn- value-count
  "Cardinality estimate for a per-key `:counts` entry value.
  Scalars contribute 1; collections contribute their count. Used
  to populate the `:counts` map's values without recursing."
  [v]
  (cond
    (nil? v)         0
    (map? v)         (count v)
    (counted? v)     (count v)
    (string? v)      (count v)
    (sequential? v)  (count v)
    :else            1))

;; ---------------------------------------------------------------------------
;; tree-summary — the {:rf.mcp/summary ...} marker shape.
;; ---------------------------------------------------------------------------

(defn tree-summary
  "Compute a server-friendly tree summary of `v`. Returns the
  marker shape causa-spec §4 pins.

  Cheap — one pass over the top-level structure, no deep walk.
  The marker itself is bounded: long key lists are truncated to
  `summary-keys-cap` entries and flagged via `:keys-truncated?
  true` so the marker can never blow the W-1 wire cap.

  Causa-specific divergence from pair2-mcp's marker shape: maps
  carry `:counts` (a per-key cardinality map) in addition to
  `:keys`, so the agent can prioritise drill-down without a
  second call. Non-map collections carry a single scalar
  `:count` like pair2-mcp.

  The `:bytes` field is an APPROXIMATION — `count × per-entry
  constant`. Per rf2-qta8j (cross-MCP): deep `(count (pr-str v))`
  would burn a deep walk per summary, contradicting the marker's
  no-deep-walk cost model.

  Scalars are returned unchanged — they already fit the wire cap
  by definition; wrapping them in a summary marker would add
  tokens without saving any."
  [v]
  (cond
    (map? v)
    (let [ks      (vec (keys v))
          n       (count ks)
          shown   (if (> n summary-keys-cap)
                    (subvec ks 0 summary-keys-cap)
                    ks)
          ;; :counts is per-key cardinality — the causa divergence.
          ;; Only emit entries for the shown keys (the truncated
          ;; trailing keys aren't in :keys; they shouldn't be in
          ;; :counts either, by parity).
          counts  (reduce
                    (fn [m k] (assoc m k (value-count (get v k))))
                    {}
                    shown)]
      {:rf.mcp/summary (cond-> {:type   :map
                                :keys   shown
                                :counts counts
                                :bytes  (approx-map-bytes n)}
                         (> n summary-keys-cap)
                         (assoc :keys-truncated? true))})
    (vector? v)
    (let [n (count v)]
      {:rf.mcp/summary {:type  :vector
                        :count n
                        :bytes (approx-coll-bytes n)}})
    (set? v)
    (let [n (count v)]
      {:rf.mcp/summary {:type  :set
                        :count n
                        :bytes (approx-coll-bytes n)}})
    (sequential? v)
    (let [n (entry-count v)]
      {:rf.mcp/summary {:type  :seq
                        :count n
                        :bytes (approx-coll-bytes n)}})
    :else
    ;; Scalars pass through unchanged — `:type :scalar` isn't
    ;; emitted as a marker because the scalar already fits the
    ;; cap. Spec/004 §4 L297 lists `:scalar` in the type vocab
    ;; for documentation completeness; the marker never wraps a
    ;; scalar in practice.
    v))

;; ---------------------------------------------------------------------------
;; sample-value — bounded prefix for :mode :sample.
;; ---------------------------------------------------------------------------

(defn sample-value
  "Return a bounded prefix of `v` (up to `n` entries) for
  `:mode :sample`. Returns a value of the same collection shape
  (map → map; vector → vector; set → set; seq → seq); scalars
  pass through unchanged (already cap-safe).

  The sample is a PREFIX in natural collection order — `:keys`
  for maps (sorted for stability), index order for vectors /
  seqs, iteration order for sets. Agents needing representative
  coverage across a large value drill into specific paths via
  W-2 `:path`."
  [v n]
  (let [n* (max min-sample-size (min max-sample-size (long n)))]
    (cond
      (map? v)
      (->> (sort-by (comp pr-str key) v)
           (take n*)
           (into {}))

      (vector? v)
      (vec (take n* v))

      (set? v)
      (set (take n* v))

      (sequential? v)
      (take n* v)

      :else v)))

;; ---------------------------------------------------------------------------
;; Mode-arg + sample-size-arg parsers.
;; ---------------------------------------------------------------------------

(defn parse-mode-arg
  "Normalise the cross-server `:mode` MCP arg into one of
  `mode-vocabulary`. Defaults to `default-mode` (`:summary`) when
  the arg is absent or unrecognised.

  Routes through `re-frame.mcp-base.args/parse-mode` (rf2-vw4sq)
  for the bounded-allowlist gate (rf2-ih7g4) — an unrecognised
  agent-supplied string never interns a fresh keyword."
  [raw]
  (base-args/parse-mode raw default-mode mode-vocabulary))

(defn mode-arg
  "Resolve the cross-server `:mode` MCP arg from a raw arguments
  object. Returns a keyword from `mode-vocabulary`.

  Accepts both the JS-side args object (the npm MCP SDK shape) and
  a CLJS map. The slot name is the cross-server `mode` (string key
  for JS, `:mode` for CLJS) per spec/004 §4.

  Unrecognised / absent inputs collapse to `default-mode`."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              (or (get args :mode)
                  (get args "mode"))

              :else
              (let [v (j/get args "mode")]
                (when-not (or (nil? v) (undefined? v)) v)))]
    (parse-mode-arg raw)))

(defn parse-sample-size-arg
  "Normalise a raw `:sample-size` value into an integer in
  `[min-sample-size, max-sample-size]`. Defaults to
  `default-sample-size` when absent / unrecognised. Delegates
  recognised-value parsing to
  `re-frame.mcp-base.args/parse-positive-int` (rf2-vw4sq); the
  upper clamp is applied here."
  [raw]
  (-> (base-args/parse-positive-int raw default-sample-size)
      (max min-sample-size)
      (min max-sample-size)))

(defn sample-size-arg
  "Resolve the cross-server `:sample-size` MCP arg from a raw
  arguments object. Returns an integer in `[min-sample-size,
  max-sample-size]`. Unrecognised / absent inputs collapse to
  `default-sample-size`."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              (or (get args :sample-size)
                  (get args "sample-size"))

              :else
              (let [v (j/get args "sample-size")]
                (when-not (or (nil? v) (undefined? v)) v)))]
    (parse-sample-size-arg raw)))

;; ---------------------------------------------------------------------------
;; apply-to-result — per-tool boundary wrapper.
;;
;; Tools that take a `:mode` arg call this once at the end of their
;; body with the already-walked (W-6) tree + the resolved mode +
;; the resolved sample-size. The wrapper writes either the
;; tree-summary marker (:summary), a bounded prefix (:sample), or
;; the verbatim tree (:full) into the envelope under `value-key`,
;; and stamps the chosen `:mode` slot for the agent's accounting.
;;
;; `:full` mode passes the value through unchanged — downstream
;; mechanisms (W-3 pagination, W-1 cap) handle their own concerns.
;; ---------------------------------------------------------------------------

(defn apply-to-result
  "Apply the spec/004 §4 mode-driven summary/sample/full
  transform to `value` and write the result back into `envelope`
  under `value-key`. Returns the updated envelope with the
  chosen `:mode` slot stamped.

  Arguments:
    - `envelope`    — the per-call result map (will be updated).
    - `value-key`   — the slot in `envelope` the post-transform
                      value goes into (e.g. `:db` for `get-app-db`,
                      `:state` for `get-machine-state`).
    - `value`       — the already-walked tree-typed payload (the
                      eval form ran `re-frame.core/elide-wire-value`
                      server-side; the marker substitution is
                      already in place).
    - opts:
      - `:mode`         — the resolved mode keyword (one of
                          `mode-vocabulary`). Defaults to
                          `default-mode` when omitted.
      - `:sample-size`  — the resolved sample size (integer).
                          Used only when `:mode :sample`. Defaults
                          to `default-sample-size`.

  Returns the envelope with `value-key` set to the transformed
  value and `:mode` stamped to the chosen mode."
  [envelope value-key value {:keys [mode sample-size]
                             :or   {mode        default-mode
                                    sample-size default-sample-size}}]
  (let [transformed (case mode
                      :summary (tree-summary value)
                      :sample  (sample-value value sample-size)
                      :full    value
                      ;; Unknown mode — collapse to default. The
                      ;; parser is bounded-allowlist-guarded, so
                      ;; this branch shouldn't fire; defensive
                      ;; against direct-call drift.
                      (tree-summary value))]
    (-> envelope
        (assoc value-key transformed)
        (assoc :mode mode))))

(ns day8.re-frame2-causa-mcp.dedup
  "Wire-pipeline mechanism W-5 at the Causa-MCP boundary (rf2-8xzoe.9).
  Structural dedup (de-dupe substitution table) — per
  `tools/causa-mcp/spec/004-Wire-Pipeline.md` §5 (Structural dedup).

  ## What this provides

  Sequence-returning tools whose items can repeat structural
  prefixes (trace bursts where many events share the same
  `:event-id` / `:handler-id` / `:source-coord` backbone, or
  epoch slices where every record carries the same `:db-before`)
  MAY apply **structural dedup** before counting tokens: shared
  subtrees are emitted once and referenced by an integer id; the
  wire payload carries

      {:rf.mcp/dedup-table {1 {...} 2 {...} ...}
       :items              [{:rf.mcp/ref 1 ...} ...]}

  The dedup algorithm is `day8/de-dupe` — originally proven on
  re-frame-10x's epoch payloads; re-applied here to re-frame2's
  structurally-similar trace and epoch shapes.

  ## Compression factor (cross-MCP measured — rf2-li2cw)

  Spec/004 §5 cites three regimes (the agent budget hint matches
  the call-site shape):

  | Regime                | Reduction | Ratio   | When                              |
  |-----------------------|-----------|---------|-----------------------------------|
  | Raw trace bursts      | 28-31%    | ~1.4×   | `get-trace-buffer`, `subscribe`   |
  | High-share replays    | ~90%      | ~10×    | Recurring cascade replays         |
  | Epoch slices          | 80-90%    | 5-10×   | `get-epoch-history`, `:epochs`    |

  Catalogue entries (when F-tranche `003-Tool-Catalogue.md` lands)
  MUST cite the regime-appropriate factor when declaring the
  `:typical-tokens` hint. The numbers are pinned by pair2-mcp's
  `dedup_benchmark_test.cljs` (rf2-li2cw) and `dedup_test.cljs`
  `reduction-ratio-shared-subtrees` (89.5% on the 10-epoch /
  256-key shared-`:db-before` corpus).

  ## Per-tick dedup table (no cross-tick refs)

  Causa-mcp's dedup is **per-tick** — each `tools/call` response
  carries its own self-contained `:rf.mcp/dedup-table`. No
  cross-tick refs; the agent never holds state from prior calls
  to decode the current one. This matches pair2-mcp's posture
  (rf2-obpa9) and keeps the wire boundary stateless.

  ## Why `de-dupe-eq` (equality), not `de-dupe` (identity)

  Data arrives at the MCP server via bencode over nREPL; CLJS
  values reconstructed from EDN don't share identity with values
  the runtime emitted earlier. Equality is what makes the cross-
  record share-pooling actually fire on the wire boundary.

  ## Idempotence on no-dedup-opportunity

  A payload with no repeated subtrees deduplicates to a one-entry
  cache (the wire shape would be very slightly larger than the
  input). The encoder skips wrapping in that case via an
  `empty-payload?` short-circuit. Scalars and empty collections
  similarly skip — the cache-of-one is a wire-size loss.

  ## `:dedup?` opt-out

  Per spec/004 §5 L329: `:dedup? false` MCP arg disables the
  transform. On by default for trace-shaped and epoch-shaped
  sequences. The cross-server arg name parallels
  `:include-sensitive?` / `:include-large?` — agents learn the
  flag-shape once.

  ## Composition with W-1 / W-3 / W-6 / B-1

  - **With W-1 (token-cap)**: dedup runs BEFORE the cap check
    (spec/004 §1 + §5 ordering). The post-dedup payload's token
    count is what trips the cap; on high-share regimes the ~10×
    factor frequently keeps the response under cap that would
    have overflowed raw.
  - **With W-3 (cursor pagination)**: dedup is per-page; each
    page carries its own self-contained dedup table. The agent
    decodes one page at a time; no cross-page table state.
  - **With W-6 (size-elision)**: the walker ran server-side
    inside the eval form; markers are in place in `items` before
    dedup. The deduper pools markers identically to other
    repeated subtrees; the post-dedup payload still carries the
    `:elided-large` counter on the envelope.
  - **With B-1 (privacy)**: the strip runs BEFORE dedup — the
    kept items vector is what the deduper sees; the
    `:dropped-sensitive` counter rides on the envelope alongside
    the dedup-table marker.

  ## MUSTs honoured

  - MUST 14 — catalogue entries cite regime-appropriate
    compression factor (~1.4× raw trace bursts, ~10× high-share
    replays, 5-10× epoch slices). The factor is surfaced in the
    per-tool `:typical-tokens` hint when the F-tranche catalogue
    lands; this namespace provides the algorithm and the wire
    shape that the catalogue documents."
  (:require [applied-science.js-interop :as j]
            [de-dupe.core :as de-dupe]
            [re-frame.mcp-base.args :as base-args]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; ---------------------------------------------------------------------------
;; Defaults — dedup is opt-out, default true for trace/epoch shapes.
;; ---------------------------------------------------------------------------

(def ^:const include-dedup-default
  "Default posture for `:dedup?` is `true` — spec/004 §5 L329
  pins dedup on by default for trace-shaped and epoch-shaped
  sequences. The constant is reified so the test corpus +
  downstream tool dispatchers reference the same identity rather
  than re-typing the literal. Parallel to
  `privacy/include-sensitive-default` and
  `elision/include-large-default` (though the polarity inverts —
  `:dedup?` defaults TRUE; the two privacy flags default FALSE)."
  true)

;; ---------------------------------------------------------------------------
;; empty-payload? — the no-op guard.
;; ---------------------------------------------------------------------------

(defn empty-payload?
  "True for values where dedup yields no win — nil, empty
  collections, scalars. Skipping the wrap avoids the trivial
  cache-of-one shape bloating the wire for empty / single-record
  responses."
  [v]
  (or (nil? v)
      (and (coll? v) (empty? v))
      (not (coll? v))))

;; ---------------------------------------------------------------------------
;; dedup-value — the wire-boundary wrap.
;; ---------------------------------------------------------------------------

(defn dedup-value
  "Apply structural dedup to `v` and wrap the result in the cross-
  MCP marker (`base-vocab/dedup-table-key` —
  `:rf.mcp/dedup-table`). Returns `v` unchanged when `enabled?`
  is false or when `v` is empty / scalar (no dedup opportunity).

  Uses `de-dupe-eq` (equality-based) — see the namespace docstring
  for the identity-vs-equality rationale.

  The wire shape — `{:rf.mcp/dedup-table <cache-map>}` — is
  cross-MCP-identical with pair2-mcp's `tools.dedup/dedup-value`;
  the marker key comes from `re-frame.mcp-base.vocab`. The agent
  host reconstructs by calling `de-dupe.core/expand` on the cache
  map value."
  [v enabled?]
  (if (or (not enabled?) (empty-payload? v))
    v
    (let [cache (de-dupe/de-dupe-eq v)]
      {base-vocab/dedup-table-key cache})))

;; ---------------------------------------------------------------------------
;; `:dedup?` MCP-arg parser.
;; ---------------------------------------------------------------------------

(defn parse-dedup-arg
  "Resolve the cross-server `:dedup?` MCP arg from a raw arguments
  object. Returns a boolean.

  Accepts:
    - JS args object (the MCP SDK shape) — looked up via
      `(j/get args \"dedup?\")`.
    - CLJS map — looked up via `(get args :dedup?)` or the
      stringified key.
    - nil / js/undefined.

  Recognised-value parsing (boolean passthrough, string
  `\"true\"`/`\"false\"`/`\"yes\"`/`\"no\"`/`\"1\"`/`\"0\"`,
  keyword `:true`/`:false`) delegates to
  `re-frame.mcp-base.args/parse-boolean` — the cross-MCP
  accept-shape contract (rf2-vw4sq).

  Unrecognised / absent inputs collapse to
  `include-dedup-default` (`true` — spec/004 §5 default-on)."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              ;; Use `contains?` so an explicit `false` is honoured
              ;; (an `or` chain would treat `false` as missing and
              ;; fall through to the default).
              (cond
                (contains? args :dedup?)   (get args :dedup?)
                (contains? args "dedup?")  (get args "dedup?")
                :else                      nil)

              :else
              ;; JS object from the MCP wire.
              (j/get args "dedup?"))]
    (base-args/parse-boolean raw include-dedup-default)))

;; ---------------------------------------------------------------------------
;; apply-to-result — per-tool boundary wrapper.
;;
;; Tools call this once at the end of their body with the
;; already-walked (W-6) + already-stripped (B-1) items vector +
;; the resolved `:dedup?` arg. The wrapper:
;;
;;   1. Skips on opt-out / empty payload (no allocation, value
;;      passes through).
;;   2. Otherwise runs `de-dupe-eq` and writes the
;;      `:rf.mcp/dedup-table`-wrapped value under `items-key`.
;;
;; ## Where in the cascade
;;
;; Per spec/004 §5: dedup runs BEFORE the W-1 cap check. The
;; dispatcher's call sequence is:
;;
;;   raw items
;;     → B-1 strip-sensitive
;;     → W-6 count-elided-markers (the walker ran server-side)
;;     → W-5 dedup-value           ← THIS WRAPPER
;;     → W-3 cursor encode (when paginated)
;;     → W-1 apply-cap             ← egress
;;
;; Each wrapper is additive; the envelope counters / cursors /
;; mode slots accumulate as the cascade runs.
;; ---------------------------------------------------------------------------

(defn apply-to-result
  "Apply the spec/004 §5 structural-dedup transform to `items`
  and write the result back into `envelope` under `items-key`.
  Returns the updated envelope.

  Arguments:
    - `envelope`   — the per-call result map (will be updated).
    - `items-key`  — the slot in `envelope` the (possibly
                     deduped) items go into (e.g. `:trace-events`
                     for `get-trace-buffer`, `:epochs` for
                     `get-epoch-history`, `:events` for
                     `subscribe` drain batches).
    - `items`      — the items value (vector, seq, or
                     `:rf.mcp/dedup-table`-wrapped map from a
                     prior pass — though the latter is unusual
                     for a single-pass dispatcher).
    - `enabled?`   — boolean resolved from `parse-dedup-arg`;
                     when `false`, the wrapper is a no-op
                     (caller opted out explicitly).

  Returns the envelope with `items-key` set to either the raw
  items (opt-out / empty) or the `{:rf.mcp/dedup-table ...}`-
  wrapped value (non-empty + opt-in)."
  [envelope items-key items enabled?]
  (assoc envelope items-key (dedup-value items enabled?)))

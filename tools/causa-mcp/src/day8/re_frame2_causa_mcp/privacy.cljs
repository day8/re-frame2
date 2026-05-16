(ns day8.re-frame2-causa-mcp.privacy
  "Spec/009 §Privacy default-suppress filter at the Causa-MCP boundary
  (B-1 of rf2-8xzoe; tranche bead rf2-8xzoe.11).

  ## What this gates

  Spec/009 mandates that framework-published forwarders — Sentry /
  Honeybadger, pair2 server, story-mcp, causa-mcp — MUST default-drop
  trace events whose registration declared `:sensitive? true`. The
  runtime stamps the flag at the top level of every emitted trace
  event inside such a registration's handler scope; the forwarder's
  job is to gate egress on it before any data crosses the MCP stdio
  trust boundary into the agent surface.

  ## Where the gate fires for causa-mcp

  The trace-stream surface is the four tools whose payloads carry raw
  `:rf/trace-event` items or epoch-records:

    - `get-trace-buffer`     — vector of trace events
    - `subscribe`            — drain-batch over the trace bus
    - `get-epoch-history`    — vector of epoch records

  Every one of these MUST funnel its returned items through
  `strip-sensitive` before the MCP wire-encode step. `apply-to-result`
  is the per-call wrapper that does the strip and stamps the
  `:dropped-sensitive` counter onto the envelope when non-zero. Direct
  reads of live `app-db` / sub-cache / machine state go through the
  `rf/elide-wire-value` walker instead (the §Direct-read MUST in
  `spec/004-Wire-Pipeline.md`); that's a separate B-tranche surface
  with its own normative MUST (row #19 of the MUST inventory).

  ## Cross-server arg vocabulary

  The opt-in escape hatch is `:include-sensitive? true` — fixed cross-
  server (pair2-mcp + story-mcp + causa-mcp). An agent learns the slot
  name once and recognises it everywhere. `parse-include-sensitive`
  centralises the boolean parse so a string `\"true\"` / `\"yes\"` /
  `\"1\"` from the MCP wire collapses to the same boolean the helper
  here expects.

  ## Why this ns delegates to `re-frame.mcp-base.sensitive`

  The base ns (rf2-vw4sq) is the shared spec/009 default-suppress
  primitive across the MCP triplet — same `sensitive-event?`
  predicate, same fail-closed posture on non-boolean truthy stamps
  (rf2-ih7g4), same `[kept dropped-count]` strip-fn contract. Causa-
  mcp uses the trace-event-only filter (not the pair2-mcp union with
  `sensitive-epoch?` — that's pair2-mcp-specific belt-and-braces over
  its in-process epoch shape; the causa runtime returns epoch records
  whose top-level `:sensitive?` rollup is computed at assembly time
  per rf2-isdwf, and the per-event filter cascades through them via
  the `:trace-events` slot from the same primitive).

  ## MUSTs honoured

  - MUST 1 — default-suppress at the MCP boundary (spec/004 L32).
  - MUST 2 — every trace-stream-surface tool MUST apply the filter
    before any data crosses (spec/004 L39). `apply-to-result` is the
    wrapper those tools call when they land.
  - MUST 19, `:include-sensitive?` half — the opt-in slot name is
    fixed cross-MCP (spec/004 §Privacy + spec/Tool-Pair.md L569)."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.args :as base-args]
            [re-frame.mcp-base.sensitive :as base-sensitive]))

;; ---------------------------------------------------------------------------
;; Predicate + strip — direct re-export of the base primitives.
;;
;; Causa-mcp uses the trace-event-only filter; the base helper handles
;; epoch records correctly because the runtime stamps the rollup at
;; assembly time (rf2-isdwf) and the per-event `:sensitive?` check
;; fires on the same top-level slot whether the map is a trace event
;; or an epoch record.
;; ---------------------------------------------------------------------------

(def sensitive-event?
  "Does this map carry the spec/009 `:sensitive? true` stamp (or a
  fail-closed malformed-truthy variant)? Delegates to
  `re-frame.mcp-base.sensitive/sensitive-event?` — same predicate the
  MCP triplet shares for byte-identical contract semantics."
  base-sensitive/sensitive-event?)

(def strip-sensitive
  "Remove `:sensitive? true` items from `items` unless the caller opted
  in. Returns `[kept dropped-count]`. Cheap on the common path
  (no sensitive items ⇒ identical-vector return + zero drop count).

  Two-arity `[items include?]` form delegates to
  `re-frame.mcp-base.sensitive/strip-sensitive` — same fail-closed
  fast-path the MCP triplet shares."
  base-sensitive/strip-sensitive)

;; ---------------------------------------------------------------------------
;; Argument parsing — `:include-sensitive?` is the cross-MCP opt-in.
;; ---------------------------------------------------------------------------

(def ^:const include-sensitive-default
  "Default posture for `:include-sensitive?` is `false` — spec/009
  §Privacy MUST default-suppress. The constant is reified so the
  test corpus + downstream tool dispatchers reference the same
  identity rather than re-typing the literal."
  false)

(defn parse-include-sensitive
  "Resolve the cross-server `:include-sensitive?` MCP arg from a raw
  arguments object. Accepts:

    - a JS args object (the MCP SDK shape) — looked up via
      `(j/get args \"include-sensitive?\")`.
    - a CLJS map — looked up via `(get args :include-sensitive?)` or
      the stringified key (whichever the upstream dispatcher hands us).
    - `nil` / `js/undefined`.

  Recognised-value parsing (boolean passthrough, string `\"true\"` /
  `\"false\"` / `\"yes\"` / `\"no\"` / `\"1\"` / `\"0\"`, keyword
  `:true` / `:false`) delegates to
  `re-frame.mcp-base.args/parse-boolean` — the cross-MCP accept-shape
  contract (rf2-vw4sq).

  Returns a boolean. Unrecognised / absent inputs collapse to the
  spec/009 default-suppress posture (`false`)."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              (or (get args :include-sensitive?)
                  (get args "include-sensitive?"))

              :else
              ;; JS object from the MCP wire.
              (j/get args "include-sensitive?"))]
    (base-args/parse-boolean raw include-sensitive-default)))

;; ---------------------------------------------------------------------------
;; Result-envelope shape — `:dropped-sensitive` counter (cross-MCP).
;;
;; The pair2-mcp wire emits `:dropped-sensitive <n>` only when the
;; counter is positive. Story-mcp + causa-mcp follow the same shape so
;; an agent reads one slot name and one polarity everywhere. The
;; helper below stamps the slot only when non-zero — zero-drop calls
;; carry no counter, keeping the common-path envelope minimal.
;; ---------------------------------------------------------------------------

(defn stamp-dropped-sensitive
  "Splice the `:dropped-sensitive` counter onto `envelope` iff
  `dropped` is positive. Returns the envelope unchanged when nothing
  was dropped — the zero-drop common path carries no counter so the
  agent surface stays minimal (a missing slot reads as zero, same
  convention as pair2-mcp's `wire/stamp-indicator-fields`).

  `envelope` is the per-call result map a tool dispatcher is about to
  serialise to the MCP wire. `dropped` is the second return value of
  `strip-sensitive` (or the accumulated total across a multi-slice
  call)."
  [envelope dropped]
  (cond-> envelope
    (and (number? dropped) (pos? dropped))
    (assoc :dropped-sensitive dropped)))

;; ---------------------------------------------------------------------------
;; Per-tool boundary wrapper.
;;
;; Trace-stream-shaped tools call this once at the end of their body,
;; with the raw items vector + the resolved `:include-sensitive?`
;; arg + the in-progress envelope. The helper strips, accumulates the
;; dropped count, stamps the counter when non-zero, and writes the
;; kept items back into the envelope at `items-key`.
;;
;; Cross-tool one-liner shape so the gate is uniform across
;; `get-trace-buffer`, `subscribe`, `get-epoch-history` — same call
;; site, same envelope-mutation rule, same indicator slot.
;; ---------------------------------------------------------------------------

(defn apply-to-result
  "Apply the spec/009 default-suppress gate to `items` and write the
  result back into `envelope` under `items-key`. Returns the updated
  envelope with the `:dropped-sensitive` counter spliced in when non-
  zero. The single call-site every trace-stream-shaped tool uses
  before returning — MUST 2 of the causa-mcp inventory.

  Arguments:
    - `envelope`   — the per-call result map (will be updated).
    - `items-key`  — the slot in `envelope` the kept items go into
                     (e.g. `:events` for `subscribe`, `:trace-events`
                     for `get-trace-buffer`, `:epochs` for
                     `get-epoch-history`).
    - `items`      — the vector of trace-event-shaped or epoch-record-
                     shaped maps to filter.
    - `include?`   — boolean resolved from `parse-include-sensitive`;
                     when `true`, the gate is a no-op (caller opted
                     in explicitly).

  Returns the envelope with `items-key` set to the kept items (or the
  original items unchanged when `include?` is true / nothing dropped)
  and `:dropped-sensitive` stamped when at least one item was dropped."
  [envelope items-key items include?]
  (let [[kept dropped] (strip-sensitive items include?)]
    (-> envelope
        (assoc items-key kept)
        (stamp-dropped-sensitive dropped))))

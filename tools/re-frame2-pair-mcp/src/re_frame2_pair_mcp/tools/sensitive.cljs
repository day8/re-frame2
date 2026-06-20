(ns re-frame2-pair-mcp.tools.sensitive
  ":sensitive? default-suppress (per spec/009 §Privacy / sensitive data).

  Spec 009 mandates that framework-published forwarders — Sentry /
  Honeybadger, re-frame2-pair server, Xray-MCP — MUST default-drop trace events
  whose registration was declared `:sensitive? true`. The runtime
  stamps `:sensitive? true` at the top level of every emitted trace
  event inside such a registration's handler scope; an event with no
  such stamp (or `:sensitive? false`) is fine to forward.

  Opt-in escape hatch: an MCP arg of `:include-sensitive true` (on
  any read/stream tool that surfaces trace-like data) removes the
  filter for that call. The default is off — apps that want sensitive
  cascades visible to the pair tool configure the policy explicitly.
  The wire-key drops the trailing `?` because
  Anthropic's tool-input-schema regex `^[a-zA-Z0-9_.-]{1,64}$` rejects
  the predicate `?`. The arg is parsed by the shared
  `re-frame2-pair-mcp.tools.args/parse-bool-arg` table."
  (:require [re-frame.mcp-base.sensitive :as base-sensitive]))

(defn sensitive-event?
  "Delegates to `re-frame.mcp-base.sensitive/sensitive-event?`.
  The predicate is the conservative spec/009 stamp
  check: only the literal `true` value drops."
  [ev]
  (base-sensitive/sensitive-event? ev))

(defn sensitive-epoch?
  "Does this projected epoch record carry — or transitively contain — a
  sensitive-rollup signal? Defense-in-depth on top of the per-event
  filter.

  An epoch is considered sensitive if EITHER:

    1. The record-level rollup slot `:rf.epoch/sensitive?` is `true`
       (the spec/009 §Privacy / Security.md §Epoch privacy rollup, the
       qualified key the runtime epoch assembler writes once at
       record-assembly time — see
       `re-frame.epoch.assembly` and the `projected-record` egress
       projection, which preserves the key verbatim). The rollup is
       classified through the SHARED fail-closed
       `re-frame.mcp-base.sensitive/sensitive-stamp?` so a malformed
       truthy rollup (string `\"true\"`, keyword `:yes`, …) drops +
       logs + bumps the malformed counter, byte-identical to the
       trace-event stamp's drift posture — NEVER a divergent
       `(true? ...)` check that fails OPEN. OR
    2. ANY constituent `:trace-events` entry carries `:sensitive? true`
       — a belt-and-braces walk that catches a sensitive cascade even
       if the record-level rollup is absent (a runtime missing the
       late-bind hook, a hand-built record in a test fixture).

  The qualified `:rf.epoch/sensitive?` rollup is the load-bearing key:
  a schema-derived sensitive epoch — sensitivity sourced from a
  declared sensitive app-db path rather than a per-event stamp —
  carries `:rf.epoch/sensitive? true` with NO sensitive constituent
  trace event. Classifying on the qualified rollup keeps such an epoch
  from shipping its metadata (`:event-id`, timing, redacted-modified
  count, outcome) across the MCP boundary, honouring the default-DROP
  contract.

  The check is short-circuit-cheap on the common path: most epochs have
  a `false`/absent rollup and no sensitive `:trace-events`, so the
  rollup classifies as non-sensitive and `some` over an empty (or
  all-false) vector returns nil immediately."
  [epoch]
  (and (map? epoch)
       (or (base-sensitive/sensitive-stamp? (:rf.epoch/sensitive? epoch))
           (boolean (some sensitive-event? (:trace-events epoch))))))

(defn strip-sensitive
  "Remove `:sensitive? true` items from `items` unless the caller opted
  in. Returns `[kept dropped-count]`. Cheap on the common path
  (no sensitive items ⇒ identical-vector return + zero drop count).

  Applies the union predicate `sensitive-event? OR sensitive-epoch?`
  so both trace-event vectors AND epoch-record vectors are gated by
  the same call site (defense-in-depth). A trace event
  has neither an `:rf.epoch/sensitive?` rollup nor a `:trace-events`
  slot, so `sensitive-epoch?` collapses to false on it and the
  trace-event arm (`sensitive-event?`'s `:sensitive?` check) governs;
  an epoch record drops on EITHER its `:rf.epoch/sensitive?` rollup
  (the qualified key the runtime writes) OR a sensitive
  constituent trace event, even when the other signal is absent.

  Cross-MCP factoring: the predicate `sensitive-event?`
  delegates to `re-frame.mcp-base.sensitive`. The epoch-level union
  check is re-frame2-pair-mcp-specific (story-mcp doesn't emit epoch records);
  the trace-event-only `strip-sensitive` in the base is the right fit
  for story-mcp consumers."
  [items include?]
  (cond
    include?            [items 0]
    (empty? items)      [items 0]
    :else
    (let [drop? (fn [x] (or (sensitive-event? x) (sensitive-epoch? x)))
          kept  (filterv (complement drop?) items)
          n     (- (count items) (count kept))]
      [kept n])))

(defn scrub-snapshot-sensitive
  "Walk a snapshot's per-frame map and drop `:sensitive? true` items
  from the `:traces` and `:epochs` slices. Returns
  `[scrubbed dropped-count]`. Non-trace slices (:app-db, :sub-cache,
  :machines) pass through unchanged — redaction of those payloads is
  the `redact-interceptor` interceptor's job, not the forwarder's.

  Epoch records carry a sensitivity stamp: the epoch assembler computes
  the `:rf.epoch/sensitive?` rollup at record-assembly time and the
  `sensitive-epoch?` defence-in-depth check matches against that
  qualified key. Both vectors are scrubbed by the same union strip-fn.

  Thin delegate to
  `re-frame.mcp-base.sensitive/scrub-snapshot` with the re-frame2-pair-mcp
  union strip-fn (`strip-sensitive`, which gates both
  `sensitive-event?` and `sensitive-epoch?`). Story-mcp uses the base
  helper's two-arity form, which defaults to the trace-event-only
  filter."
  [snapshot include?]
  (base-sensitive/scrub-snapshot snapshot include? strip-sensitive))

(ns re-frame2-pair-mcp.tools.sensitive
  ":sensitive? default-suppress (per spec/009 §Privacy / sensitive data).

  Spec 009 mandates that framework-published forwarders — Sentry /
  Honeybadger, re-frame2-pair server, Causa-MCP — MUST default-drop trace events
  whose registration was declared `:sensitive? true`. The runtime
  stamps `:sensitive? true` at the top level of every emitted trace
  event inside such a registration's handler scope; an event with no
  such stamp (or `:sensitive? false`) is fine to forward.

  Opt-in escape hatch: an MCP arg of `:include-sensitive true` (on
  any read/stream tool that surfaces trace-like data) removes the
  filter for that call. The default is off — apps that want sensitive
  cascades visible to the pair tool configure the policy explicitly.
  The wire-key drops the trailing `?` per rf2-y710n + rf2-ihq4d —
  Anthropic's tool-input-schema regex `^[a-zA-Z0-9_.-]{1,64}$` rejects
  the predicate `?`. The arg is parsed by the shared
  `re-frame2-pair-mcp.tools.args/parse-bool-arg` table (rf2-c4fmh)."
  (:require [re-frame.mcp-base.sensitive :as base-sensitive]))

(defn sensitive-event?
  "Delegates to `re-frame.mcp-base.sensitive/sensitive-event?`
  (rf2-vw4sq). The predicate is the conservative spec/009 stamp
  check: only the literal `true` value drops."
  [ev]
  (base-sensitive/sensitive-event? ev))

(defn sensitive-epoch?
  "Does this epoch record carry — or transitively contain — a
  `:sensitive? true` stamp? Defense-in-depth on top of the per-event
  filter (rf2-re2s3).

  An epoch is considered sensitive if EITHER:

    1. The top-level `:sensitive?` slot on the record is literal `true`
       (the spec/009 §Privacy rollup, computed by the epoch assembler
       once at record-assembly time per rf2-isdwf), OR
    2. ANY constituent `:trace-events` entry carries `:sensitive? true`
       — a belt-and-braces walk that catches a sensitive cascade even
       if the upstream rollup wasn't computed (older runtime, missing
       late-bind hook, hand-built record in a test fixture).

  The check is short-circuit-cheap on the common path: most epochs have
  no `:sensitive?` slot and no sensitive `:trace-events`, so `some` over
  an empty (or all-false) vector returns nil immediately."
  [epoch]
  (and (map? epoch)
       (or (true? (:sensitive? epoch))
           (boolean (some sensitive-event? (:trace-events epoch))))))

(defn strip-sensitive
  "Remove `:sensitive? true` items from `items` unless the caller opted
  in. Returns `[kept dropped-count]`. Cheap on the common path
  (no sensitive items ⇒ identical-vector return + zero drop count).

  Applies the union predicate `sensitive-event? OR sensitive-epoch?`
  so both trace-event vectors AND epoch-record vectors are gated by
  the same call site (defense-in-depth per rf2-re2s3). A trace event
  has no `:trace-events` slot so `sensitive-epoch?` collapses to the
  same `:sensitive?` top-level check; an epoch record with a
  sensitive constituent trace event drops even if its top-level
  rollup is absent.

  Cross-MCP factoring (rf2-vw4sq): the predicate `sensitive-event?`
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
  the `with-redacted` interceptor's job, not the forwarder's.

  Epoch-record stamping is current contract per rf2-isdwf — the
  epoch assembler computes the rollup at record-assembly time and
  the `sensitive-epoch?` defence-in-depth check matches against it.
  Both vectors are scrubbed by the same union strip-fn.

  Thin delegate (rf2-zpmmr) to
  `re-frame.mcp-base.sensitive/scrub-snapshot` with the re-frame2-pair-mcp
  union strip-fn (`strip-sensitive`, which gates both
  `sensitive-event?` and `sensitive-epoch?`). Story-mcp uses the base
  helper's two-arity form, which defaults to the trace-event-only
  filter."
  [snapshot include?]
  (base-sensitive/scrub-snapshot snapshot include? strip-sensitive))

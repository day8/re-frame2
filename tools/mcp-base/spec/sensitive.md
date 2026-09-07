# `sensitive` — Spec 009 §Privacy default-suppress filter

> **Type:** Reference (`tools/mcp-base/spec/`)
> The cross-MCP privacy filter. Off-box forwarders must default-drop trace events whose registration declared `:sensitive? true`. The runtime stamps that classification on emitted trace events; forwarders gate egress before data crosses the trust boundary.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`sensitive` owns:

- The cross-MCP fail-closed `sensitive-event?` predicate over a trace-event map.
- The `strip-sensitive` walker (returns `[kept dropped-count]`).
- The `scrub-snapshot` walker that strips `:traces` / `:epochs` slices from each per-frame snapshot map.
- The fail-closed malformed-stamp counter (`malformed-count` / `reset-malformed-count!`), bumped exactly once per dropped malformed event. The walkers classify each event once, so the count is a per-event metric.
- The fixed cross-server arg-vocabulary name (`:include-sensitive`) every MCP tool surfacing trace-like data MUST accept.

`sensitive` does NOT own:

- The framework-side `:sensitive?` registration-meta stamp — that's normative in [`../../../spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md).
- The wire-elision walker itself (`rf/elide-wire-value` lives in `day8/re-frame2` core).
- The runtime stamping logic (each registration's handler scope; see `re-frame.privacy/sensitive?`).

## Surface

### `sensitive-stamp? stamp` — raw rollup predicate

Classifies a supplied stamp with the same fail-closed rule as
`sensitive-event?`. Consumers of a different rollup slot (for example
`:rf.epoch/sensitive?`) use this helper instead of duplicating the predicate.
Malformed stamps increment `malformed-count` and emit the value-free warning;
`(malformed-count)` reads that count and `(reset-malformed-count!)` resets it.

### `sensitive-event?` — predicate

Predicate over a trace-event map. **Fail-closed**: literal `true` drops, and any other truthy non-boolean stamp drops with a value-free warning and malformed-counter increment. Only `false`, nil, or an absent stamp passes.

**The contract-drift warning is value-free.** Logs are an egress boundary, so the warning carries only a type tag and fixed `value=:rf/redacted` sentinel, never the raw malformed stamp.

Definition (effectively):

```clojure
(defn sensitive-event? [ev]
  (and (map? ev)
       (let [stamp (:sensitive? ev)]
         (cond
           (true? stamp)  true              ; documented spec/009 drop
           (false? stamp) false             ; non-sensitive
           (nil? stamp)   false             ; absent ⇒ non-sensitive
           :else          (do (bump-malformed!)   ; truthy non-boolean:
                              (log-malformed! stamp)
                              true)))))            ; fail-closed drop + warn
```

A literal-`true`-only predicate would fail open if serialization changed the stamp's type. The same fail-closed classifier applies to top-level trace batches and snapshot trace slices.

### `strip-sensitive events include?` → `[kept dropped-count]`

Walks a collection of trace events; returns a `[kept dropped-count]` 2-vector where `kept` is the filtered collection and `dropped-count` is the integer for the `:dropped-sensitive` envelope slot (per [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)).

The count feeds the indicator-field slot the agent reads to know the payload was filtered without re-inferring from absence.

`include?` is the caller's already-authorized opt-in, not a permission check:
truthy returns `[events 0]` unchanged; false/nil applies the filter. A batch
with no drops preserves the original collection identity.

### `scrub-snapshot` — per-frame snapshot scrubber

Walks a snapshot map keyed by frame and, for each per-frame map, applies the
strip-fn to sequential `:traces` and `:epochs` batches. Returns
`[scrubbed-snapshot total-dropped]`. A single map slice is also classified
through the supplied strip-fn: if sensitive it becomes `[]` and counts as one
drop; otherwise it is unchanged. Other non-sequential slices and non-snapshot
inputs pass through unchanged. An authorized `include?` bypasses the scrubber.

`scrub-snapshot` is a **trace/epoch sensitivity filter only, not the complete snapshot privacy boundary.** It strips the `:traces` and `:epochs` slices but leaves `:app-db`, `:sub-cache`, and `:machines` untouched. Its output is not a fully projected snapshot.

Per [EP-0015](../../../docs/EP/EP-0015-frame-owned-egress-policy.md), non-trace slices are projected at egress under a named `:rf.egress/*` profile. The caller must apply that projection before a snapshot crosses the MCP boundary; `scrub-snapshot` alone is insufficient.

Two arities:

- `[snapshot include?]` — uses the base `strip-sensitive` predicate (the spec/009 §Privacy stamp check).
- `[snapshot include? strip-fn]` — accepts a custom strip-fn matching the `[items include?] => [kept dropped]` contract (re-frame2-pair-mcp passes a union predicate that also catches the epoch-level `:sensitive?` rollup).

## Cross-server arg-vocabulary convention

The opt-in arg every MCP tool surfacing trace-like data MUST accept. The semantics are fixed — accept the arg, default it to `false`, feed it to `strip-sensitive` (and any analogous walker that recurses through snapshot slices) — and the **wire-key spelling is now uniform** across every server:

- Both servers ship the unqualified `:include-sensitive` wire key (no trailing `?`).
- The walker option key inside the framework (`vocab/include-sensitive-opt`) is the namespaced `:rf.size/include-sensitive?` — internal, not a wire-key, so the predicate `?` is retained.

The cross-server wire-key is a fixed literal-spelling pin: every server accepts `:include-sensitive` (the per-server tool catalogue documents the same literal).

The default-OFF posture aligns with the framework's privacy-by-default stance (per [`../../../spec/Security.md` §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) and [`../../../spec/Conventions.md` §Privacy config-knob naming](../../../spec/Conventions.md)).

## Zero-dep rationale

re-frame2-pair-mcp is a CLJS Node bundle without `re-frame.trace` on its classpath; story-mcp is JVM-side. This zero-framework-dependency predicate gives both hosts the same fail-closed classification.

Consumers reach the predicate two ways: re-frame2-pair-mcp keeps a thin local alias ns (`tools/sensitive.cljs`) that delegates here, for code-review locality; story-mcp requires `re-frame.mcp-base.sensitive` directly (no local alias ns). Either way the fail-closed predicate itself lives here.

## Conformance posture

Tree-payload emitters compute both `:dropped-sensitive` and `:elided-large` indicators; each zero count is omitted independently (see [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)).

Consumer and conformance fixtures cover the default-off posture, the dropped count, and absence of sensitive events from emitted bodies.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md) — the framework's `:sensitive?` substrate this filter consumes.
- [`../../../spec/Security.md` §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) — the pattern-level privacy MUSTs the filter enforces.
- [`../../../spec/Conventions.md` §Privacy config-knob naming](../../../spec/Conventions.md) — the `include-sensitive?` (off-box wire-egress verb) vs `show-sensitive?` (on-box UI verb) split. Note the verb keeps the `?`; the MCP tool-input *wire-key* drops it (`:include-sensitive`) per the Anthropic schema regex.
- [`vocab.md`](vocab.md) — the marker keyword + envelope-slot catalogue this filter populates.
- [`elision.md`](elision.md) — the size-elision counterpart; both indicator slots ride the response envelope together.

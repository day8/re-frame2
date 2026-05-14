# `sensitive` — Spec 009 §Privacy default-suppress filter

> **Type:** Reference (`tools/mcp-base/spec/`)
> The cross-MCP privacy filter. Framework-published forwarders — Sentry / Honeybadger, pair2 server, Story-MCP, Causa-MCP — MUST default-drop trace events whose registration declared `:sensitive? true`. The runtime stamps the flag at the top level of every emitted trace event inside such a registration's handler scope; the forwarder's job is to gate egress on it before any data crosses the trust boundary.

This doc is one of seven per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md).

## Scope

`sensitive` owns:

- The cross-MCP `sensitive-event?` predicate over a trace-event map.
- The `strip-sensitive` walker (returns `[kept dropped-count]`).
- The `scrub-snapshot` walker that recurses through a snapshot payload and removes `:sensitive?`-stamped subtrees.
- The fixed cross-server arg-vocabulary name (`:include-sensitive?`) every MCP tool surfacing trace-like data MUST accept.

`sensitive` does NOT own:

- The framework-side `:sensitive?` registration-meta stamp — that's normative in [`../../../spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md).
- The wire-elision walker itself (`rf/elide-wire-value` lives in `day8/re-frame2` core).
- The runtime stamping logic (each registration's handler scope; see `re-frame.privacy/sensitive?`).

## Surface

### `sensitive-event?` — predicate

Predicate over a trace-event map. **Conservative** — only the literal `true` value drops. Mirrors the Spec-Schemas contract: `:sensitive?` is typed boolean.

Definition (effectively):

```clojure
(defn sensitive-event? [ev]
  (and (map? ev) (true? (:sensitive? ev))))
```

The same shape applies whether the trace event is a top-level emission or a nested fragment inside a snapshot — the predicate is conservative everywhere.

### `strip-sensitive` — coll → `[kept dropped-count]`

Walks a collection of trace events; returns a `[kept dropped-count]` 2-vector where `kept` is the filtered collection and `dropped-count` is the integer for the `:dropped-sensitive` envelope slot (per [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)).

The count feeds the indicator-field slot the agent reads to know the payload was filtered without re-inferring from absence.

### `scrub-snapshot` — snapshot tree walker

Recurses through a snapshot payload (snapshot mode `:full` / `:summary`) and removes any `:sensitive?`-stamped sub-tree.

Snapshot scrubbing is **stricter** than trace-event filtering — the snapshot payload may contain nested registration handles whose sub-trees carry sensitive declarations; the walker descends into those sub-trees and removes them rather than relying on the top-level flag.

## Cross-server arg-vocabulary convention

The opt-in arg name **`:include-sensitive?`** is the fixed, cross-server vocabulary an agent learns once. Every MCP tool that surfaces trace-like data MUST:

1. Accept this arg.
2. Default it to `false`.
3. Feed it to `strip-sensitive` (and any analogous walker that recurses through snapshot slices).

The default-OFF posture aligns with the framework's privacy-by-default stance (per [`../../../spec/Security.md` §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) and [`../../../spec/Conventions.md` §Privacy config-knob naming](../../../spec/Conventions.md)).

## Zero-dep rationale

Pair2-mcp is a CLJS Node bundle (no `re-frame.trace` on its classpath); story-mcp / causa-mcp are JVM-side and DO have the framework primitive available. The predicate here (`(and (map? ev) (true? (:sensitive? ev)))`) is conservative and identical to `re-frame.privacy/sensitive?`.

Consumers that want to bind to the framework primitive (story-mcp does, for code-review locality) alias the surface in their own ns and delegate through here.

## Conformance posture

The `:dropped-sensitive` envelope slot rides alongside `:elided-large` per the indicator-field parity rule (per [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)). Both slots are MUST-level — the conformance gate at `tools/mcp-conformance/wire-vocab/` asserts parity.

The privacy default — `:include-sensitive?` defaults `false` — is enforced via the per-tool argument schema in each consumer; the conformance harness drives every tool with a payload that includes a `:sensitive? true` event and asserts the response envelope's `:dropped-sensitive` counter is non-zero, with the sensitive event absent from the response body.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md) — the framework's `:sensitive?` substrate this filter consumes.
- [`../../../spec/Security.md` §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) — the pattern-level privacy MUSTs the filter enforces.
- [`../../../spec/Conventions.md` §Privacy config-knob naming](../../../spec/Conventions.md) — the `:include-sensitive?` (wire) vs `:show-sensitive?` (UI) verb split.
- [`vocab.md`](vocab.md) — the marker keyword + envelope-slot catalogue this filter populates.
- [`elision.md`](elision.md) — the size-elision counterpart; both indicator slots ride the response envelope together.

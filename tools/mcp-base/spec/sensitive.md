# `sensitive` — Spec 009 §Privacy default-suppress filter

> **Type:** Reference (`tools/mcp-base/spec/`)
> The cross-MCP privacy filter. Framework-published forwarders — Sentry / Honeybadger, re-frame2-pair server, Story-MCP, Xray-MCP — MUST default-drop trace events whose registration declared `:sensitive? true`. The runtime stamps the flag at the top level of every emitted trace event inside such a registration's handler scope; the forwarder's job is to gate egress on it before any data crosses the trust boundary.

This doc is one of eleven per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`sensitive` owns:

- The cross-MCP fail-closed `sensitive-event?` predicate over a trace-event map.
- The `strip-sensitive` walker (returns `[kept dropped-count]`).
- The `scrub-snapshot` walker that strips `:traces` / `:epochs` slices from each per-frame snapshot map.
- The fail-closed malformed-stamp counter (`malformed-count` / `reset-malformed-count!`) — operator-surface observability for the rf2-ih7g4 fail-closed posture; bumped every time a non-boolean truthy `:sensitive?` stamp arrives and is dropped + logged.
- The fixed cross-server arg-vocabulary name (`:include-sensitive`) every MCP tool surfacing trace-like data MUST accept.

`sensitive` does NOT own:

- The framework-side `:sensitive?` registration-meta stamp — that's normative in [`../../../spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md).
- The wire-elision walker itself (`rf/elide-wire-value` lives in `day8/re-frame2` core).
- The runtime stamping logic (each registration's handler scope; see `re-frame.privacy/sensitive?`).

## Surface

### `sensitive-event?` — predicate

Predicate over a trace-event map. **Fail-closed** (rf2-ih7g4): the literal `true` value drops (the documented spec/009 path), AND any other *truthy non-boolean* stamp also drops — with a stderr / `console.warn` contract-drift warning and a bump of the malformed counter. Only an explicit `false` / `nil` / absent stamp passes. The `:rf/trace-event` schema types `:sensitive?` as a boolean (Spec 009 + Spec-Schemas); a non-boolean stamp is a serialisation-bug *contract violation* that we surface (warn + drop) rather than silently treat as non-sensitive.

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

The earlier `true?`-only predicate was fail-**open** on contract drift: an upstream serialisation bug coercing the boolean into a string (`"true"`) would silently leak every sensitive event past the filter. The fail-closed posture closes that leak. The same predicate applies whether the trace event is a top-level emission or a nested fragment inside a snapshot.

### `strip-sensitive` — coll → `[kept dropped-count]`

Walks a collection of trace events; returns a `[kept dropped-count]` 2-vector where `kept` is the filtered collection and `dropped-count` is the integer for the `:dropped-sensitive` envelope slot (per [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)).

The count feeds the indicator-field slot the agent reads to know the payload was filtered without re-inferring from absence.

### `scrub-snapshot` — per-frame snapshot scrubber

Walks a snapshot map keyed by frame and, for each per-frame map, applies the strip-fn to the `:traces` and `:epochs` slices only. Returns `[scrubbed-snapshot total-dropped]`. Non-map slices and non-snapshot inputs pass through unchanged.

The walk is **shallow by design** (per rf2-zq0n1 / rf2-3cted): it strips sensitive trace events from `:traces` / `:epochs` but **leaves `:app-db`, `:sub-cache`, and `:machines` untouched**. App-db payload redaction is `redact-interceptor`'s job at *write-time*, not the forwarder's job at *read-time* — `scrub-snapshot` does NOT descend into arbitrary sub-trees and does NOT redact app-db values. (A reviewer should not assume app-db redaction happens here; it does not.)

Two arities:

- `[snapshot include?]` — uses the base `strip-sensitive` predicate (the spec/009 §Privacy stamp check).
- `[snapshot include? strip-fn]` — accepts a custom strip-fn matching the `[items include?] => [kept dropped]` contract (re-frame2-pair-mcp passes a union predicate that also catches the epoch-level `:sensitive?` rollup).

## Cross-server arg-vocabulary convention

The opt-in arg every MCP tool surfacing trace-like data MUST accept. The semantics are fixed — accept the arg, default it to `false`, feed it to `strip-sensitive` (and any analogous walker that recurses through snapshot slices) — and the **wire-key spelling is now uniform** across every server:

- **story-mcp** (rf2-y710n) and **re-frame2-pair-mcp** (rf2-ihq4d) both ship the unqualified `:include-sensitive` (no trailing `?` — the Anthropic tool-input-schema regex `^[a-zA-Z0-9_.-]{1,64}$` rejects `?`).
- The walker option key inside the framework (`vocab/include-sensitive-opt`) is the namespaced `:rf.size/include-sensitive?` — internal, not a wire-key, so the predicate `?` is retained.

The cross-server wire-key is a fixed literal-spelling pin: every server accepts `:include-sensitive` (the per-server tool catalogue documents the same literal).

The default-OFF posture aligns with the framework's privacy-by-default stance (per [`../../../spec/Security.md` §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) and [`../../../spec/Conventions.md` §Privacy config-knob naming](../../../spec/Conventions.md)).

## Zero-dep rationale

re-frame2-pair-mcp is a CLJS Node bundle (no `re-frame.trace` on its classpath); story-mcp is JVM-side and DOES have the framework primitive available. The predicate here matches the spirit of `re-frame.privacy/sensitive?` but adds the fail-closed posture (rf2-ih7g4): the runtime always stamps the literal boolean, but if a transport bug delivers any other truthy value, the filter drops the event AND logs so the contract drift surfaces in operator output rather than leaking the event.

Consumers that want to bind to the framework primitive (story-mcp does, for code-review locality) alias the surface in their own ns and delegate through here.

## Conformance posture

The `:dropped-sensitive` envelope slot rides alongside `:elided-large` per the indicator-field parity rule (per [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)). Both slots are MUST-level — the conformance gate at `tools/mcp-conformance/wire-vocab/` asserts parity.

The privacy default — `:include-sensitive` defaults `false` — is enforced via the per-tool argument schema in each consumer; the conformance harness drives every tool with a payload that includes a `:sensitive? true` event and asserts the response envelope's `:dropped-sensitive` counter is non-zero, with the sensitive event absent from the response body.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md) — the framework's `:sensitive?` substrate this filter consumes.
- [`../../../spec/Security.md` §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) — the pattern-level privacy MUSTs the filter enforces.
- [`../../../spec/Conventions.md` §Privacy config-knob naming](../../../spec/Conventions.md) — the `include-sensitive?` (off-box wire-egress verb) vs `show-sensitive?` (on-box UI verb) split. Note the verb keeps the `?`; the MCP tool-input *wire-key* drops it (`:include-sensitive`) per the Anthropic schema regex.
- [`vocab.md`](vocab.md) — the marker keyword + envelope-slot catalogue this filter populates.
- [`elision.md`](elision.md) — the size-elision counterpart; both indicator slots ride the response envelope together.

# `sensitive` — Spec 009 §Privacy default-suppress filter

> **Type:** Reference (`tools/mcp-base/spec/`)
> The cross-MCP privacy filter. Framework-published forwarders — Sentry / Honeybadger, re-frame2-pair server, Story-MCP (xray-mcp was dropped in rf2-bu21t; xray now ships as a Clojars-only library, not an MCP server) — MUST default-drop trace events whose registration declared `:sensitive? true`. The runtime stamps the flag at the top level of every emitted trace event inside such a registration's handler scope; the forwarder's job is to gate egress on it before any data crosses the trust boundary.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`sensitive` owns:

- The cross-MCP fail-closed `sensitive-event?` predicate over a trace-event map.
- The `strip-sensitive` walker (returns `[kept dropped-count]`).
- The `scrub-snapshot` walker that strips `:traces` / `:epochs` slices from each per-frame snapshot map.
- The fail-closed malformed-stamp counter (`malformed-count` / `reset-malformed-count!`) — operator-surface observability for the rf2-ih7g4 fail-closed posture; bumped exactly **once per dropped malformed event** (a non-boolean truthy `:sensitive?` stamp). `strip-sensitive` / `scrub-snapshot` classify each event exactly once (single-pass — rf2-el9sw), so the count is a faithful per-event metric rather than a scan-strategy-dependent over-count.
- The fixed cross-server arg-vocabulary name (`:include-sensitive`) every MCP tool surfacing trace-like data MUST accept.

`sensitive` does NOT own:

- The framework-side `:sensitive?` registration-meta stamp — that's normative in [`../../../spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md).
- The wire-elision walker itself (`rf/elide-wire-value` lives in `day8/re-frame2` core).
- The runtime stamping logic (each registration's handler scope; see `re-frame.privacy/sensitive?`).

## Surface

### `sensitive-event?` — predicate

Predicate over a trace-event map. **Fail-closed** (rf2-ih7g4): the literal `true` value drops (the documented spec/009 path), AND any other *truthy non-boolean* stamp also drops — with a stderr / `console.warn` contract-drift warning and a bump of the malformed counter. Only an explicit `false` / `nil` / absent stamp passes. The `:rf/trace-event` schema types `:sensitive?` as a boolean (Spec 009 + Spec-Schemas); a non-boolean stamp is a serialisation-bug *contract violation* that we surface (warn + drop) rather than silently treat as non-sensitive.

**The contract-drift warning is value-free (rf2-el9sw).** Logs are an egress boundary on this privacy surface; a malformed stamp is wire-adjacent, untrusted data, and a serialisation bug could put a secret-bearing string / map / vector in `:sensitive?`. The warning therefore carries ONLY a value-free type tag (`type=String`, `type=Keyword`, …) and a fixed `value=:rf/redacted` sentinel — it MUST NOT `pr-str` the raw stamp. The `malformed-count` counter is the quantitative observability hook; the type tag is the qualitative one; neither carries the payload.

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

`scrub-snapshot` is a **trace/epoch sensitivity filter only — NOT the complete snapshot privacy boundary.** The walk is **shallow by design** (per rf2-zq0n1 / rf2-3cted): it strips sensitive trace events from `:traces` / `:epochs` but **leaves `:app-db`, `:sub-cache`, and `:machines` untouched** — it does NOT descend into arbitrary sub-trees and does NOT redact app-db values. Its OUTPUT is therefore **not** already-projected full-snapshot output.

Per [EP-0015](../../../docs/EP/EP-0015-frame-owned-egress-policy.md), the public privacy model is registration-owned `:sensitive` / `:large` classification plus centralized `project-egress` (under a named `:rf.egress/*` profile) at the egress boundary; the write-time `redact-interceptor` that earlier owned app-db payload redaction has been **removed**. So the **caller's egress pipeline** must run `project-egress` over the non-trace slices with the frame's known policy **before** the snapshot crosses an MCP/tool boundary. A consumer must NOT treat `scrub-snapshot` output as sufficient, and must NOT look for a write-time interceptor to have handled app-db redaction — it does not happen here, and there is no longer a write-time interceptor that does it.

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

Consumers reach the predicate two ways: re-frame2-pair-mcp keeps a thin local alias ns (`tools/sensitive.cljs`) that delegates here, for code-review locality; story-mcp requires `re-frame.mcp-base.sensitive` directly (no local alias ns). Either way the fail-closed predicate itself lives here.

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

# `elision` — wire-boundary `:rf.size/large-elided` walker

> **Type:** Reference (`tools/mcp-base/spec/`)
> Per [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md), the framework's `rf/elide-wire-value` walker substitutes over-threshold leaves with a `{:rf.size/large-elided {…}}` marker before the payload leaves the runtime. Every MCP tool that returns a tree-typed payload surfaces a scalar count of those substitutions on its response envelope (the `:elided-large` slot — see [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)). This namespace owns the **counter**, not the walker.

This doc is one of seven per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md).

## Scope

`elision` owns:

- The `count-elided-markers` walker — returns the integer for the `:elided-large` envelope slot.
- The shallow-at-the-marker-boundary contract (marker bodies are summaries; they shouldn't double-count).

`elision` does NOT own:

- The walker that *produces* the `:rf.size/large-elided` markers — that's `rf/elide-wire-value`, framework-side, in `day8/re-frame2` core. This ns is consumer-side, summarising what the framework already produced.
- The `:rf.size/large-elided` marker shape itself — that's framework-owned per [`../../../spec/009-Instrumentation.md` §Size elision](../../../spec/009-Instrumentation.md).
- The threshold knob (`:rf.size/threshold-bytes`) — that's a framework-side opt the consumer relays via the walker's option map; see [`vocab.md` §Marker catalogue (`:rf.size/*`)](vocab.md#marker-catalogue-rfsize).

## Surface

### `count-elided-markers` — value → integer

Walks a value and returns the integer for the `:elided-large` envelope slot.

**Shallow at the marker boundary** — once a marker map (`{:rf.size/large-elided {…}}`) is found, its body is NOT recursed into. Marker bodies are summaries; they shouldn't double-count.

Definition (effectively):

```clojure
(defn count-elided-markers [v]
  (cond
    (and (map? v) (contains? v :rf.size/large-elided)) 1
    (coll? v)                                          (reduce + 0 (map count-elided-markers v))
    :else                                              0))
```

The counter is non-negative integer; 0 means nothing was elided. The slot itself rides the response envelope (per [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)).

## Cousin to `sensitive`

`sensitive/strip-sensitive` returns `[kept dropped-count]` for the `:dropped-sensitive` slot; `count-elided-markers` returns the integer for `:elided-large`. Both indicators ride the response envelope together per the cross-MCP indicator-field parity rule.

**Parity is MUST-level.** Indicator-field parity is the round-2 audit fix the conformance gate at `tools/mcp-conformance/wire-vocab/` enforces — one without the other is a wire-protocol break.

## Cross-platform

Pure-data tree walk; loads identically into JVM (story-mcp / causa-mcp) and CLJS (pair2-mcp). No transport, no runtime, no framework dep. The walker uses only:

- `map?` / `coll?` host predicates.
- `contains?` membership.
- `reduce` / `map` recursion.

All available in both CLJ and CLJS without `.cljc` reader-conditional branches.

## Conformance posture

The conformance harness at `tools/mcp-conformance/` drives every tool with a payload that contains a `{:rf.size/large-elided {…}}` substitution and asserts:

1. The response envelope's `:elided-large` slot is a non-zero integer.
2. The slot is present **alongside** the `:dropped-sensitive` slot — indicator-field parity.
3. The counter value equals the visible marker count in the response body (no double-counting; no under-counting).

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md) — the framework primitive this ns counts the output of.
- [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots) — the `:elided-large` slot definition.
- [`sensitive.md`](sensitive.md) — the cousin walker; both indicators ride the response envelope.
- [`../../../spec/Conventions.md` §Cross-MCP indicator-field vocabulary](../../../spec/Conventions.md) — the MUST-level parity between `:dropped-sensitive` and `:elided-large`.

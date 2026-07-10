# `elision` — wire-boundary `:rf.size/large-elided` walker

> **Type:** Reference (`tools/mcp-base/spec/`)
> Per [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md), the framework's `rf/elide-wire-value` walker substitutes over-threshold leaves with a `{:rf.size/large-elided {…}}` marker before the payload leaves the runtime. Every MCP tool that returns a tree-typed payload surfaces a scalar count of those substitutions on its response envelope (the `:elided-large` slot — see [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)). This namespace owns the **counter**, not the walker.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

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
    (and (map? v) (contains? v :rf.size/large-elided))   1
    (map? v)                                             (reduce-kv
                                                           (fn [n _ c] (+ n (count-elided-markers c)))
                                                           0 v)
    (or (vector? v) (set? v) (seq? v))                   (reduce
                                                           (fn [n c] (+ n (count-elided-markers c)))
                                                           0 v)
    :else                                                0))
```

The counter is non-negative integer; 0 means nothing was elided. The slot itself rides the response envelope (per [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots)).

The walker descends through maps (including record values that satisfy `map?`), vectors, sets, and seqs. Other values are leaves.

## Cousin to `sensitive`

`sensitive/strip-sensitive` returns `[kept dropped-count]` for `:dropped-sensitive`; `count-elided-markers` returns the count for `:elided-large`. Tree-payload emitters compute both, and omit each zero count independently.

## Cross-platform

Pure-data tree walk; loads identically into JVM (story-mcp) and CLJS (re-frame2-pair-mcp). No transport, no runtime, no framework dep. The walker uses only:

- `map?` / `vector?` / `set?` / `seq?` host predicates.
- `contains?` membership.
- `reduce` / `map` recursion.

All available in both CLJ and CLJS without `.cljc` reader-conditional branches.

## Conformance posture

Base and consumer conformance fixtures assert:

1. The response envelope's `:elided-large` slot is a non-zero integer.
2. A zero count omits the slot independently of `:dropped-sensitive`.
3. The count equals the visible marker count in the response body.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/009-Instrumentation.md` §Size elision in traces](../../../spec/009-Instrumentation.md) — the framework primitive this ns counts the output of.
- [`vocab.md` §Envelope counter slots](vocab.md#envelope-counter-slots) — the `:elided-large` slot definition.
- [`sensitive.md`](sensitive.md) — the cousin walker; both indicators ride the response envelope.
- [`../../../spec/Conventions.md` §Cross-MCP indicator-field vocabulary](../../../spec/Conventions.md) — the shared indicator rules.

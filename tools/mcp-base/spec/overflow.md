# `overflow` — overflow-marker shape builder (rf2-rvyzy)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the SHAPE of the overflow marker (the `{:rf.mcp/overflow {:limit :reached :token-count … :cap-tokens … :tool … :hint …}}` map). The cap-enforcement glue (counting tokens, replacing the payload) lives in [`cap.md`](cap.md).

This doc is one of seven per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`cap.md`](cap.md).

## Scope

`overflow` owns:

- `default-max-tokens` const (**5000**).
- The `token-estimate` cheap character→token approximation.
- `overflow-hint-fallback` (generic fallback hint).
- `overflow-marker` — the builder fn that returns the canonical marker map shape.

`overflow` does NOT own:

- The cap-enforcement algorithm — that's [`cap.md`](cap.md).
- The per-tool hint table — that lives consumer-side (pair2-mcp ships `overflow-hints`; story-mcp ships its own; the convention is the *shape*, not the text).
- The `:rf.mcp/overflow` marker key — that's pinned in [`vocab.md` §Marker catalogue (`:rf.mcp/*`)](vocab.md#marker-catalogue-rfmcp).

## Surface

### `default-max-tokens` — const, 5000

The convention's documented cap. Sized for a typical 5K-token MCP response envelope after diff-encode + dedup. Consumers may override per-call via the `:max-tokens` MCP arg; `0` disables.

### `token-estimate s` — string → integer

```clojure
(defn token-estimate [s] (quot (count s) 4))
```

Cheap character→token approximation aligned with Anthropic's rule-of-thumb for English / EDN. Not exact; the goal is a **bounded** wire payload, not a precise meter.

The estimate is intentionally simple — running a tokeniser per response would re-introduce the perf cost the cap is supposed to prevent. The 4-chars-per-token approximation overshoots and undershoots in roughly equal measure across realistic EDN payloads.

### `overflow-hint-fallback` — generic fallback

Generic hint used when a tool isn't listed in the per-tool hint table:

> "Response exceeded token cap; consider narrowing your query or paginating via the tool's slice args."

The per-tool hint table lives consumer-side and is keyed by tool id. The builder fn delegates to the consumer's hint resolver via a small adapter.

### `overflow-marker` — builder fn

Builds the canonical marker map shape:

```clojure
(defn overflow-marker
  [{:keys [tool token-count cap-tokens hint]}]
  {:rf.mcp/overflow
   {:limit       :reached
    :token-count token-count
    :cap-tokens  cap-tokens
    :tool        tool
    :hint        (or hint overflow-hint-fallback)}})
```

The shape is the wire-protocol contract; the slot names are pinned by the conformance gate. Consumer-side overrides only the `:hint` text via the consumer's per-tool table.

## Hint table

Per-tool overflow hints live with the consumer (pair2-mcp ships its `overflow-hints` table, story-mcp ships its own). The builder delegates to the consumer's hint resolver via a small adapter; the shape (a `tool→hint` map with `overflow-hint-fallback` as the fallback) is the convention. The cross-MCP conformance gate (`wire-vocab/`) pins the marker SHAPE; the hint text is consumer-authored.

Example consumer registration:

```clojure
(def overflow-hints
  {"get-app-db" "Use :path opts to narrow the slice"
   "trace-window" "Lower the :window-ms or add a tighter filter"
   "watch-epochs" "Decrease :depth or filter to a frame"})
```

## Why the cap exists

The MCP transport carries text payloads to the agent host (Claude, GPT, etc.). Agents have **context budgets** measured in tokens; an MCP tool that responds with 50K tokens of payload would consume the agent's working context, leaving no room for the agent's reasoning. The cap is the agent-ergonomics counterpart to the framework's `:rf.size/threshold-bytes` (which caps per-leaf byte size); both close the "response too big for the consumer to use" failure mode.

The cap is a **soft** constraint at the algorithm level — the consumer can override via `:max-tokens`. It is a **hard** constraint at the wire level — every server's cap pipeline runs the same algorithm, and the cross-MCP conformance gate asserts the marker shape is identical across consumers.

## Conformance posture

The cross-MCP conformance gate at `tools/mcp-conformance/wire-vocab/` pins the canonical Malli schema for the `:rf.mcp/overflow` marker:

```clojure
[:map
 [:rf.mcp/overflow
  [:map
   [:limit       [:= :reached]]
   [:token-count :int]
   [:cap-tokens  :int]
   [:tool        :string]
   [:hint        :string]]]]
```

Every server's cap-trigger fixture asserts the response matches this schema. The conformance harness at `tools/mcp-conformance/test/live-pair2-overflow.js` drives a real `:max-tokens 100` over-budget call on each server and asserts the marker shape parity.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`cap.md`](cap.md) — the cap-enforcement algorithm that drives this marker into a result.
- [`vocab.md` §Marker catalogue (`:rf.mcp/*`)](vocab.md#marker-catalogue-rfmcp) — the `:rf.mcp/overflow` key.
- rf2-rvyzy — the bead that landed this marker shape.

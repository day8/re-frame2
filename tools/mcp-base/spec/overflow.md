# `overflow` — overflow-marker shape builder (rf2-rvyzy)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the SHAPE of the overflow marker (the `{:rf.mcp/overflow {:limit :reached :token-count … :cap-tokens … :tool … :hint …}}` map). The cap-enforcement glue (counting tokens, replacing the payload) lives in [`cap.md`](cap.md).

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`overflow` owns:

- `default-max-tokens` const (**5000**).
- The `token-estimate` cheap character→token approximation.
- `overflow-hint-fallback` (generic fallback hint).
- `overflow-payload` — the builder fn that returns the canonical marker map shape.

`overflow` does NOT own:

- The cap-enforcement algorithm — that's [`cap.md`](cap.md).
- The per-tool hint table — that lives consumer-side (re-frame2-pair-mcp ships `overflow-hints`; story-mcp ships its own; the convention is the *shape*, not the text).
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

> "Response over budget. Re-call with narrower args, or raise `max-tokens` (0 disables the cap)."

The per-tool hint table lives consumer-side and is keyed by tool id. The builder fn delegates to the consumer's hint resolver via a small adapter.

### `overflow-payload` — builder fn

Builds the canonical marker map shape. Note the input/output key
asymmetry: the destructured INPUT key is `:cap` (the cap in tokens),
which the builder emits into the OUTPUT under the wire slot
`:cap-tokens`:

```clojure
(defn overflow-payload
  [{:keys [tool token-count cap hint]}]
  {:rf.mcp/overflow
   {:limit       :reached
    :token-count token-count
    :cap-tokens  cap
    :tool        tool
    :hint        (or hint overflow-hint-fallback)}})
```

The output shape is the wire-protocol contract; the slot names are pinned by the conformance gate. Consumer-side overrides only the `:hint` text via the consumer's per-tool table. `cap.cljc`'s `apply-cap` calls `overflow-payload` with `:cap` (the resolved per-call cap) and `:token-count` (the count that tripped the gate).

## Hint table

Per-tool overflow hints live with the consumer (re-frame2-pair-mcp ships its `overflow-hints` table, story-mcp ships its own). The builder delegates to the consumer's hint resolver via a small adapter; the shape (a `tool→hint` map with `overflow-hint-fallback` as the fallback) is the convention. The cross-MCP conformance gate (`wire-vocab/`) pins the marker SHAPE; the hint text is consumer-authored.

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

The cross-MCP conformance gate at `tools/mcp-conformance/wire-vocab/` pins the canonical Malli schema for the `:rf.mcp/overflow` marker (`wire_vocab/schemas.clj` `Overflow` / `ReFrame2PairOverflowBody`):

```clojure
[:map {:closed true}
 [:rf.mcp/overflow
  [:map {:closed false}
   [:limit       [:enum :reached]]
   [:token-count :int]
   [:cap-tokens  :int]
   [:tool        [:or :string :keyword]]
   [:hint        [:or :string :keyword]]]]]
```

Both servers emit this marker via the shared `cap/apply-cap` → `overflow-payload`, so the body is byte-identical; the gate carries a per-server cap-trigger fixture (`:re-frame2-pair-mcp` and `:story-mcp`) and asserts each validates against this one schema.

Live cap-trigger coverage today:

- **re-frame2-pair-mcp** — over the real MCP wire via `tools/mcp-conformance/test/live-re-frame2-pair-overflow.cjs` (a `:max-tokens 100` over-budget `tools/call` against a live nREPL-backed server; hermetic on CI, SKIPs without `$SHADOW_CLJS_NREPL_PORT`).
- **story-mcp** — at the wire-pipeline boundary via `tools/story-mcp/test/re_frame/story_mcp/tools_test.clj` (`wire-pipeline/invoke-tool` with `:max-tokens 1` asserts the `{:rf.mcp/overflow …}` marker; `:max-tokens 0` asserts the bypass). story-mcp is JVM-side with no nREPL/browser runtime, so its over-budget trip is exercised in-process rather than through a parallel `.cjs` SDK script.

The marker SHAPE builder (`overflow-payload`) is additionally driven live JVM-side in `wire_vocab_test.clj` (`overflow-marker-shape-emitted-live-by-canonical-builder`) — the one builder both servers share.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`cap.md`](cap.md) — the cap-enforcement algorithm that drives this marker into a result.
- [`vocab.md` §Marker catalogue (`:rf.mcp/*`)](vocab.md#marker-catalogue-rfmcp) — the `:rf.mcp/overflow` key.
- rf2-rvyzy — the bead that landed this marker shape.

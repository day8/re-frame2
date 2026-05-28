# `cursor` — shared cursor-pagination machinery (rf2-ee38b.19)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the cross-MCP cursor codec, the EDN-with-no-tagged-literals reader, the size cap, the `::malformed` recovery contract, the `:limit` clamp, and the `cursor-stale-result` envelope. The cursor PAYLOAD shape is consumer-side (story carries `{:offset :total :sig}`; pair carries `{:after-id :ms :until-ms :frame}`).

This doc is one of ten per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`envelope.md`](envelope.md).

## Scope

Per `spec/Principles.md` §Pagination and `spec/Tool-Pair.md` §Cursor pagination, every read tool whose return size is a function of registry / ring size MUST accept a `:limit` arg and return an opaque `:cursor` for continuation. Cursors are OPAQUE on the wire — the agent passes them back verbatim and has no business decoding the format.

`cursor` owns:

- `max-cursor-bytes` const (**1024**) — hard ceiling on a cursor token's character length.
- `b64-encode` / `b64-decode` — the base64 codec, resolving to `java.util.Base64` (JVM) / `js/Buffer` (CLJS) via reader-conditional.
- `parse-limit-arg` — the `:limit` clamp (`default` and `max` per-consumer).
- `encode-cursor` / `decode-cursor` — opaque codec around the consumer's payload map.
- `malformed?` — predicate for the `::malformed` sentinel.
- `cursor-stale-result` — the cross-MCP cursor-stale envelope builder, delegating to the consumer's `error-result`.

`cursor` does NOT own:

- The cursor PAYLOAD shape — that's consumer-side. `decode-cursor` takes a `valid?` predicate so each server validates its own payload map.
- The wire envelope shape — `cursor-stale-result` takes the consumer's `error-result` fn so each server shapes the envelope its own way.
- The cursor *resource controls* (concurrent-stream cap, token-bucket rate-limit, abuse window) — those live consumer-side in pair-mcp's `resource_controls.cljs`.

## Surface

### `max-cursor-bytes` — const, 1024

Hard ceiling on the cursor token's character length. Cursors are short opaque tokens (a base64'd EDN map of a handful of scalar slots); anything longer is rejected before parsing as a cheap DoS / malformed guard. 1 KB is far past any legitimate cursor.

### `b64-encode s` — string → base64 string

Base64-encode a UTF-8 string. `java.util.Base64` on the JVM, `js/Buffer` on CLJS.

### `b64-decode s` — base64 string → string

Inverse of `b64-encode`.

### `parse-limit-arg raw default max` — int

Normalise the `:limit` MCP arg into an integer in `[1, max]`, defaulting to `default`. Caller-supplied values above `max` clamp DOWN (a legitimate large request still works, just capped). Delegates to `args/parse-positive-int` for the shared string/number coercion, then applies the ceiling.

Each server bakes its own `default` (story 25, pair 50) and `max` (story's 200; pair's wire-cap is the implicit ceiling) — the convention is the CLAMP behaviour, not the numbers.

### `encode-cursor payload` — payload map → base64 string

Unconditional encoder — always produces a token for a payload map. The caller decides WHEN to emit a cursor (when there are more entries / records); the absence-of-cursor IS the end-of-pagination signal, so the caller passes `nil` rather than calling this when pagination is over. Returns `nil` for a nil / non-map payload as a safety net.

### `decode-cursor s valid?` — base64 string × predicate → payload map | nil | `::malformed`

Decode an opaque base64 cursor back to its EDN payload map.

Returns:

- `nil` — the cursor arg is absent (nil / undefined) or blank. The caller treats this as "start from the beginning" (offset 0 / head).
- `::malformed` — the cursor exists but is not a well-formed, `valid?`-passing payload map: it failed to base64/EDN-decode, carried a tagged literal, exceeded `max-cursor-bytes`, or its payload map failed the consumer's `valid?` predicate. Callers treat `::malformed` exactly like a STALE cursor — drop it and restart.

`valid?` is the consumer's payload-shape predicate (e.g. `#(and (map? %) (integer? (:offset %)) (string? (:sig %)))` for story; `#(and (map? %) (string? (:after-id %)))` for pair).

Hardened: non-string input → `::malformed`; oversize input → `::malformed` BEFORE parsing; the input is decoded exactly once.

### `malformed? decoded` — predicate

True when `decoded` is the `::malformed` sentinel returned by `decode-cursor`. Convenience for consumers that prefer a predicate over the qualified-keyword literal at the call site.

### `cursor-stale-result error-result tool opts` — envelope map

Build a structured cursor-stale error result via the consumer's `error-result` fn.

The `:reason` slot is the cross-MCP `vocab/cursor-stale-reason` (`:rf.mcp/cursor-stale`) so an agent that learned the recovery path on one server reuses it on the other — whether staleness means ring-rotation (pair) or registry-change-between-pages (story).

`error-result` is the consumer's error-envelope builder. It is called as `(error-result message data-map)` where `message` is a human-readable string and `data-map` carries the structured slots (`:ok? false`, `:reason`, `:tool`, `:hint`, plus any caller `extra`). Each server's `error-result` shapes the wire envelope its own way (story-mcp's `h/error-result`, pair-mcp's `wire/err-text`); this builder owns only the cross-MCP slot vocabulary.

`opts`:

- `:message` — override the default human-readable message.
- `:hint` — override the default recovery hint.
- `:extra` — a map of additional structured slots to merge in (e.g. pair's `:requested-id` / `:head-id`).

## Tagged-literal rejection

The EDN reader inside `decode-cursor` runs with a `:default` data-reader that throws `:rf.error/mcp-cursor-bad-edn-tag` on ANY tagged literal. A hostile / corrupt cursor cannot smuggle a `#js`, `#inst`, or custom tagged literal past the reader. The throw is caught by the surrounding try and surfaces as `::malformed`.

## Why this ns

Both servers previously hand-rolled the SAME machinery:

- `tools/story-mcp/.../cursor.cljc` — offset/total/sig over append-mostly registries.
- `tools/re-frame2-pair-mcp/.../cursor.cljs` — after-id/ms over the bounded epoch ring.

The cursor PAYLOAD differs by domain, but the base64 codec, the EDN reader with tagged-literal rejection + size guard, the `::malformed` recovery contract, the `:limit` clamp, and the `cursor-stale-result` envelope are identical. Earlier the README argued the codec was consumer-side because `js/Buffer` vs `java.util.Base64` differ — story-mcp's `cursor.cljc` refuted that — the codec lifts cleanly as a `.cljc` reader-conditional.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`vocab.md` §JSON-RPC error codes + cross-MCP error reasons](vocab.md) — the `cursor-stale-reason` key.
- [`args.md`](args.md) — `parse-positive-int`, used by `parse-limit-arg`.
- `spec/Tool-Pair.md` §Cursor pagination — the framework-level rule the codec implements.
- rf2-ee38b.19 — the bead that landed this ns.

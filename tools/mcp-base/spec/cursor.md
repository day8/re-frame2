# `cursor` — shared cursor-pagination machinery

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the cross-MCP cursor codec, the EDN-with-no-tagged-literals reader, the size cap, the `::malformed` recovery contract, the `:limit` clamp, and the `cursor-stale-result` envelope. The cursor payload shape is consumer-side (story carries `{:v :offset :total :sig}`; pair carries `{:after-id :ms :until-ms :frame}`).

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

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

Each server bakes its own `default` (story 25, pair 50) and `max` (story 200, pair 1000) — the convention is the clamp behavior, not the numbers.

### `encode-cursor payload` — payload map → base64 string

Unconditional encoder — always produces a token for a payload map. The caller decides WHEN to emit a cursor (when there are more entries / records); the absence-of-cursor IS the end-of-pagination signal, so the caller passes `nil` rather than calling this when pagination is over. Returns `nil` for a nil / non-map payload as a safety net.

### `decode-cursor s valid?` — base64 string × predicate → payload map | nil | `::malformed`

Decode an opaque base64 cursor back to its EDN payload map.

Returns:

- `nil` — the cursor arg is absent (nil / undefined) or blank. The caller treats this as "start from the beginning" (offset 0 / head).
- `::malformed` — the cursor exists but is not a well-formed, `valid?`-passing payload map: it failed to base64/EDN-decode, carried a tagged literal, decoded to more than one EDN form, exceeded `max-cursor-bytes`, or failed the consumer's `valid?` predicate. Callers treat `::malformed` like a stale cursor — drop it and restart.

`valid?` is the consumer's payload-shape predicate. Story validates `:v`, natural `:offset`/`:total`, and a string `:sig`. Pair requires a present, non-nil `:after-id` of any EDN-printable type because epoch ids are opaque.

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

The EDN reader inside `decode-cursor` rejects **every** tagged literal — custom AND built-in — and surfaces the rejection as `::malformed`:

- A `:default` data-reader throws `:rf.error/mcp-cursor-bad-edn-tag` on every **unregistered** tag (`#js`, `#foo/bar`, …).
- `:readers` overrides throw on the **built-in** `#inst` / `#uuid` tags. These have registered readers (`clojure.edn` / `cljs.reader` resolve them from `*data-readers*` / the tag-table), so they **bypass `:default`** and would otherwise decode to a host `java.util.Date` / `UUID` (JVM) or `js/Date` / `cljs.core/UUID` (CLJS). Because `:readers` is consulted **before** `:default` on both platforms, the override wins and the built-in tag never materialises a host object.

A hostile or corrupt cursor therefore cannot smuggle a tagged literal, or the host object it would decode to, past the reader. The surrounding `decode-cursor` catch maps the rejection to `::malformed` before the consumer's shape predicate runs.

## One-form requirement

A cursor is **one** opaque EDN payload map. `read-edn-no-tags` requires the decoded text to be exactly one form and rejects any trailing form as `::malformed`; a plain `read-string` would read only the first form without proving exhaustion.

### Why not the naive one-element-vector wrap — the injected-`]` bypass

The obvious closure — wrap the decoded text in `[ … ]` and accept a ONE-element vector — is **bypassable by `]`-injection** and is NOT what the implementation does. Attacker text `{…valid map…}] <junk>` wraps to `[{…}] <junk>]`: the *injected* `]` closes the wrapper early, so `read-string` reads the clean one-element vector `[{…}]`, returns the inner map, and SILENTLY DISCARDS everything after the injected `]`. A one-element-vector check would accept that corrupted cursor.

### The EOF-sentinel exhaustion check (what the implementation does)

`read-edn-no-tags` instead asserts reader **exhaustion** via an appended sentinel — the string-host analog of an `:eof` read sentinel. It wraps as `[ <decoded-text> <eof-sentinel> ]` and requires the read to yield **exactly** `[<one-form> <eof-sentinel>]` — a **two**-element vector whose SECOND element is the sentinel. The sentinel can only land as the trailing element if the reader consumed the WHOLE wrapped string:

- a single clean form (trailing whitespace / comments absorbed) yields `[<map> <sentinel>]` → accepted, returning the sole payload form so the caller's `map?` / `valid?` checks are unchanged;
- an **injected `]`** truncates the read before the sentinel, so the vector does NOT end with it → `::malformed`;
- any **genuine trailing form** pushes the element count past 2 → `::malformed`.

The sentinel is a qualified, unguessable keyword (`:re-frame.mcp-base.cursor/cursor-eof-sentinel`); even attacker text that reproduces the literal cannot pass — the cursor must still be a single payload map FOLLOWED by the sentinel (two forms, no more), so a trailing sentinel keyword pushes the count to 3 and rejects.

This is a pure-`read-string` technique that behaves identically on the JVM (`clojure.edn`) and CLJS (`cljs.reader`) without reaching into either host's pushback-reader internals; tagged literals anywhere inside the wrapped text still throw via the `:readers` / `:default` overrides above. The injected-`]` bypass and the reproduced-sentinel case are both pinned in `cursor_test`'s `decode-cursor-rejects-trailing-forms`.

## Why this namespace

The payload differs by domain, but the base64 codec, tagged-literal rejection, size guard, malformed recovery, limit clamp, and stale-result vocabulary are shared. Reader conditionals isolate the `java.util.Base64` and `js/Buffer` host adapters.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`vocab.md` §JSON-RPC error codes + cross-MCP error reasons](vocab.md) — the `cursor-stale-reason` key.
- [`args.md`](args.md) — `parse-positive-int`, used by `parse-limit-arg`.
- `spec/Tool-Pair.md` §Cursor pagination — the framework-level rule the codec implements.

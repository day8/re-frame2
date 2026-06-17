# `envelope` — cross-MCP response-envelope helpers (rf2-ee38b.19)

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the SHAPE of the indicator-field splice (`with-indicators`) enforcing the MUST-level "omit when zero" rule, plus the wire-bounded `:rf.mcp/*` marker detection used by the cache + cap boundary steps. The indicator KEYS themselves live in [`vocab.md`](vocab.md); this ns owns the splice + detection logic.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`envelope` owns:

- `with-indicators` — splice the `:dropped-sensitive` / `:elided-large` slots onto an envelope, enforcing the "omit when zero" MUST.
- `marker-prefixes` — the rendered-text prefixes of the wire-bounded `:rf.mcp/cache-hit` / `:rf.mcp/overflow` envelopes (both flat and namespaced-map print forms).
- `marker-text?` — wire-bounded marker detection on a rendered text string.

`envelope` does NOT own:

- The indicator KEYS — those live in [`vocab.md`](vocab.md) (`dropped-sensitive-key`, `elided-large-key`).
- The COUNTS — the producers live in [`sensitive.md`](sensitive.md) (`strip-sensitive`) and [`elision.md`](elision.md) (`count-elided-markers`).
- The transport. `with-indicators` operates on a Clojure envelope map. `marker-text?` operates on the RENDERED text string — the consumer reads its own platform's content slot (`:text` from a Clojure map / `j/get :text` from a JS object) and passes the string here, so the shape-specific accessor stays consumer-side while the prefix-detection logic is shared.

## Surface

### `with-indicators envelope counts` — envelope map

Splice the cross-MCP indicator-field slots onto a tool's envelope map, enforcing the MUST-level "omit when zero" rule from Conventions §Cross-MCP indicator-field vocabulary and Spec 009 §Indicator field on tool responses.

Every tool that walks a tree-typed payload (`snapshot`, `get-path`, `trace-window`, `watch-epochs`, `subscribe`, …) routes its envelope-tail through here so the rule lives in one place — drift across emit sites can no longer silently violate the MUST.

`counts`:

- `:dropped` — count of `:sensitive? true` leaves dropped at the wire boundary (from `sensitive/strip-sensitive`). Emitted under `vocab/dropped-sensitive-key` when positive.
- `:elided` — count of leaves replaced with the `:rf.size/large-elided` marker (from `elision/count-elided-markers`). Emitted under `vocab/elided-large-key` when positive.

A zero / nil / absent count omits its slot entirely (the "omit when zero" MUST). Returns `envelope` unchanged when both counts are zero — identity-preserving on the common path.

### `marker-prefixes` — vector of prefix strings

Rendered-text prefixes of the wire-bounded `:rf.mcp/*` replacement envelopes (`:rf.mcp/cache-hit`, `:rf.mcp/overflow`). Includes BOTH the flat and the namespaced-map print forms (see §Print-form parity below) so the detector works regardless of host or `*print-namespace-maps*`. The leading token must MATCH a marker key exactly — not merely begin with one (see `marker-text?` and §Exact-key match below).

### `marker-text? text` — predicate

Is `text` (the rendered EDN text of a response's first content slot) a wire-bounded `:rf.mcp/*` marker envelope?

Returns true for `:rf.mcp/cache-hit` / `:rf.mcp/overflow` markers — the two envelopes the cache + cap steps emit themselves. The consumer reads its own platform's content text (`:text` from a Clojure map, `j/get :text` from a JS object) and passes the string here; this fn owns only the leading-token match logic, shared across hosts.

Matches the EXACT marker key, not merely a prefix of it (see §Exact-key match below): a lookalike leading key such as `:rf.mcp/overflowed` or `:rf.mcp/cache-hit-extra` is NOT a marker.

Nil-safe: a nil / non-string `text` is not a marker.

## Why wire-bounded markers matter (rf2-gktyn, rf2-3z0zi)

The `:rf.mcp/cache-hit` and `:rf.mcp/overflow` envelopes are replacement results the cache + cap boundary steps emit themselves. By construction they are sub-cap size — re-walking either is wasted work, and a cache check on a hit-marker would hash the marker, not the original payload. `marker-text?` is the cheap detector every boundary step uses to short-circuit when it sees an earlier step's marker.

## Print-form parity

Leading-token match on the rendered text is the cheap detector — the marker map's namespaced key is the first key of the outer map, so a tight match on the trimmed text's leading token is fast. The detector matches BOTH print forms the single-key namespaced marker map can take:

- the flat form `{:rf.mcp/overflow …` (the form CLJS `pr-str` emits and the form JVM emits with `*print-namespace-maps*` false), and
- the namespaced-map shorthand `#:rf.mcp{:overflow …` (the form JVM `pr-str` emits by default for a single-namespace map).

Matching both keeps the detector host- and print-setting-agnostic.

## Exact-key match (rf2-3xd9i9)

The detector matches the marker key EXACTLY — not merely as a prefix. After the leading marker-key text matches, the very next character must be an EDN token TERMINATOR (whitespace, `,`, or a map/vector/list/string delimiter) — proving the marker key ended exactly there and was not merely a prefix of a longer key. EDN keyword/symbol constituents (alphanumerics plus `* + ! - _ ' ? < > = . / : # & %`) immediately after the prefix mean the leading key is a LOOKALIKE, not the marker.

This closes a correctness hole a bare `starts-with?` left open: a lookalike leading key whose name begins with a marker key — e.g. `:rf.mcp/overflowed` or `:rf.mcp/cache-hit-extra` — was wrongly classified as an already-bounded marker, so an over-budget payload whose first key merely started with `:rf.mcp/overflow` would bypass cap enforcement. The two real markers always carry a non-empty map value, so the terminator (the space `pr-str` writes before the value) is always present — the exact-match check preserves their short-circuit while rejecting the lookalikes.

## Indicator-field parity

The cross-MCP conformance gate at `tools/mcp-conformance/wire-vocab/` pins the canonical Malli schema for the indicator-field slots and asserts the "omit when zero" MUST across both servers' fixtures. The slot keys themselves come from [`vocab.md`](vocab.md):

- `vocab/dropped-sensitive-key` — `:dropped-sensitive`
- `vocab/elided-large-key` — `:elided-large`

Every server that emits an envelope with one of these slots routes through `with-indicators` so the MUST is enforced in one place.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`vocab.md`](vocab.md) — the indicator + marker key catalogue.
- [`sensitive.md`](sensitive.md) — the producer of `:dropped` counts.
- [`elision.md`](elision.md) — the producer of `:elided` counts.
- `spec/Conventions.md` §Cross-MCP indicator-field vocabulary — the MUST-level parity rule.
- `spec/009-Instrumentation.md` §Indicator field on tool responses — the framework-level surface.
- rf2-ee38b.19 — the bead that landed this ns.
- rf2-gktyn / rf2-3z0zi — the beads that landed the wire-bounded marker detection.

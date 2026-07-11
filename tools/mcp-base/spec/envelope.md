# `envelope` — cross-MCP response-envelope helpers

> **Type:** Reference (`tools/mcp-base/spec/`)
> Owns the SHAPE of the indicator-field splice (`with-indicators`) enforcing the MUST-level "omit when zero" rule, plus the wire-bounded `:rf.mcp/*` marker detection used by the cache + cap boundary steps. The indicator KEYS themselves live in [`vocab.md`](vocab.md); this ns owns the splice + detection logic.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`envelope` owns:

- `with-indicators` — splice the `:dropped-sensitive` / `:elided-large` slots onto an envelope, enforcing the "omit when zero" MUST.
- `marker-prefixes` — the rendered-text prefixes of the wire-bounded `:rf.mcp/cache-hit` / `:rf.mcp/overflow` envelopes (both flat and namespaced-map print forms).
- `marker-text?` — wire-bounded marker detection on a rendered text string; requires a complete, closed, single-key marker wrapper (leading-token pre-filter + structural confirmation).

`envelope` does NOT own:

- The indicator KEYS — those live in [`vocab.md`](vocab.md) (`dropped-sensitive-key`, `elided-large-key`).
- The COUNTS — the producers live in [`sensitive.md`](sensitive.md) (`strip-sensitive`) and [`elision.md`](elision.md) (`count-elided-markers`).
- The transport. `with-indicators` operates on a Clojure envelope map. `marker-text?` operates on the RENDERED text string — the consumer reads its own platform's content slot (`:text` from a Clojure map / `j/get :text` from a JS object) and passes the string here, so the shape-specific accessor stays consumer-side while the prefix-detection logic is shared.

## Surface

### `with-indicators envelope counts` — envelope map

Splice the cross-MCP indicator-field slots onto a tool's envelope map, enforcing the MUST-level "omit when zero" rule from Conventions §Cross-MCP indicator-field vocabulary and Spec 009 §Indicator field on tool responses.

Tree-payload emitters route their envelope through this helper so the indicator-key and omit-when-zero rules stay consistent.

`counts`:

- `:dropped` — count of records classified as sensitive and dropped at the wire boundary, including fail-closed malformed stamps. Emitted under `vocab/dropped-sensitive-key` when positive.
- `:elided` — count of leaves replaced with the `:rf.size/large-elided` marker (from `elision/count-elided-markers`). Emitted under `vocab/elided-large-key` when positive.

A zero / nil / absent count omits its slot entirely (the "omit when zero" MUST). Returns `envelope` unchanged when both counts are zero — identity-preserving on the common path.

### `marker-prefixes` — vector of prefix strings

Rendered-text prefixes of the wire-bounded `:rf.mcp/*` replacement envelopes (`:rf.mcp/cache-hit`, `:rf.mcp/overflow`). Includes BOTH the flat and the namespaced-map print forms (see §Print-form parity below) so the detector works regardless of host or `*print-namespace-maps*`. The leading token must MATCH a marker key exactly — not merely begin with one (see `marker-text?` and §Exact-key match below). This is the cheap **pre-filter** for `marker-text?`; a match still has to pass the §Closed-wrapper confirmation before it counts as a marker.

### `marker-text? text` — predicate

Is `text` (the rendered EDN text of a response's first content slot) a wire-bounded `:rf.mcp/*` marker envelope — a **complete, closed, single-key marker map**?

Returns true for `:rf.mcp/cache-hit` / `:rf.mcp/overflow` markers — the two envelopes the cache + cap steps emit themselves. The consumer reads its own platform's content text (`:text` from a Clojure map, `j/get :text` from a JS object) and passes the string here; this fn owns the shared recognition logic across hosts.

Three gates, **all required**:

1. A cheap **leading-token pre-filter** — the EXACT marker key must be the first top-level key, not merely a prefix of it (see §Exact-key match below): a lookalike leading key such as `:rf.mcp/overflowed` or `:rf.mcp/cache-hit-extra` is NOT a marker.
2. A **size bound** — the rendered text must estimate within the documented default cap `overflow/default-max-tokens` (see §Size bound below). Closure alone does not bound the marker's SIZE, so a single-key wrapper with an over-budget body is NOT a marker.
3. A **structural confirmation** — the whole `text` must parse to a closed single-key map whose sole key is a marker key and whose body is a map (see §Closed-wrapper confirmation below).

Together the three gates make the fast-path invariant TRUE: anything `marker-text?` accepts is a closed single-key `:rf.mcp/*` map guaranteed under the default cap.

Nil-safe: a nil / non-string `text` is not a marker.

## Why wire-bounded markers matter

The `:rf.mcp/cache-hit` and `:rf.mcp/overflow` envelopes are replacement results emitted by cache and cap steps. By construction they are sub-cap size; pair-mcp uses `marker-text?` to avoid re-walking them. Consumers that do not chain such boundary steps need not call the detector.

## Print-form parity

Leading-token match on the rendered text is the cheap detector — the marker map's namespaced key is the first key of the outer map, so a tight match on the trimmed text's leading token is fast. The detector matches BOTH print forms the single-key namespaced marker map can take:

- the flat form `{:rf.mcp/overflow …` (the form CLJS `pr-str` emits and the form JVM emits with `*print-namespace-maps*` false), and
- the namespaced-map shorthand `#:rf.mcp{:overflow …` (the form JVM `pr-str` emits by default for a single-namespace map).

Matching both keeps the detector host- and print-setting-agnostic.

## Exact-key match

The detector matches the marker key EXACTLY — not merely as a prefix. After the leading marker-key text matches, the very next character must be an EDN token TERMINATOR (whitespace, `,`, or a map/vector/list/string delimiter) — proving the marker key ended exactly there and was not merely a prefix of a longer key. EDN keyword/symbol constituents (alphanumerics plus `* + ! - _ ' ? < > = . / : # & %`) immediately after the prefix mean the leading key is a LOOKALIKE, not the marker.

A bare prefix match would also accept lookalike keys such as `:rf.mcp/overflowed`, allowing an ordinary payload to bypass cap enforcement. Requiring a token terminator preserves the real markers' short-circuit while rejecting lookalikes.

## Closed-wrapper confirmation

The leading-token match proves only the **first** key. That is not the invariant the fast-path skip relies on: "a marker is sub-cap **by construction**" holds only for a **complete, closed, single-key** marker map. A mixed wrapper whose first key is a real marker key but which carries an unexpected top-level sibling —

```clojure
{:rf.mcp/overflow {:limit :reached} :unexpected "<8K body>"}
```

— leads with a marker key yet is an arbitrary over-budget payload. Classifying it as an already-bounded marker would let a reserved-key-shaped or malformed handler result **bypass cap enforcement** (and skip cache bookkeeping) instead of being measured/replaced.

So after the leading-token pre-filter matches, `marker-text?` **parses the whole rendered text** and requires:

- exactly **one** top-level key,
- that key is `vocab/cache-hit-key` or `vocab/overflow-key`, and
- a **map** body (additive body fields are permitted where the conformance schema allows them — only the OUTER wrapper must be closed).

The read reuses `cursor.md`'s host-agnostic *one form, EOF-exhausted, tagged-literals-rejected* technique (wrap as `[<text> <eof-sentinel>]`, require the read to yield exactly `[<one-form> <eof-sentinel>]`). This rejects — identically on `clojure.edn` (JVM) and `cljs.reader` (CLJS) — an extra top-level sibling, a trailing EDN form, an injected `]` truncation, any tagged literal (built-in `#inst`/`#uuid` or custom `#js`/`#foo`), and a non-map root/body. Anything that fails the confirmation is ordinary/malformed payload and continues through cap enforcement. The cheap path for genuinely generated markers is preserved: their pre-filter matches and the parse confirms a closed single-key map, with no overflow-of-overflow recursion.

## Size bound

Closure proves the wrapper is closed but NOT small. A **complete, closed, single-key** wrapper whose body is arbitrarily large —

```clojure
{:rf.mcp/overflow {:limit :reached :blob "<100 KB of x's>"}}
```

— passes the leading-token and closed-wrapper gates yet renders to ~25 000 tokens, far over the ~5 000-token default cap. Treating it as sub-cap-by-construction would let the fast-path skip egress an over-budget payload un-capped — the BODY dimension of the same reserved-`:rf.mcp/*`-namespace threat the closed-wrapper gate closes for the extra-sibling dimension.

So `marker-text?` also requires the rendered text to estimate within the documented default cap: `(<= (overflow/token-estimate text) overflow/default-max-tokens)`. This expresses "sub-cap by construction" **directly** against the convention cap. The two real markers are tiny fixed-shape maps (a few hundred chars ≈ ~100 tokens), so they pass with vast headroom; only an over-budget reserved-key-shaped payload is caught. Consequently anything `marker-text?` accepts is guaranteed under the default cap, so skipping the cap step on it can never leak an over-default-cap body. The O(1) length check also short-circuits an adversarial 100 KB input before the O(n) closed-wrapper read.

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

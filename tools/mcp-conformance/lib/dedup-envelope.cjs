// Wire-side decoder for the `:rf.mcp/dedup-table` envelope.
//
// ## Why this lives here
//
// Two MCP servers (pair-mcp + story-mcp) wrap selected tools'
// `:structuredContent` payloads in a top-level marker
// `{:rf.mcp/dedup-table <cache-map>}` — see `tools/mcp-base/spec/
// vocab.md` for the canonical key and `re-frame.mcp-base.dedup/dedup-value`
// (consumed directly by both servers) for the production transform. Real MCP
// clients (Claude Code, Continue, …) running on a JVM / CLJS host
// decode by calling `re-frame.mcp-base.dedup/expand` on the cache map; the
// reconstructed payload is what user code sees.
//
// The conformance harness drives the servers from Node, so it does
// not have `re-frame.mcp-base.dedup/expand` reachable. A server may wrap a
// semantic slot (e.g. `:lifecycle` at the top level of
// `:structuredContent`) inside `cache-0` of the dedup table, so the
// literal JSON shape on the wire differs from what a real client sees
// after expansion. Conformance MUST validate the semantic contract a
// real client sees, not the pre-expansion wire shape — this decoder
// supplies the Node-side expansion the harness needs to do that.
//
// `decodeDedupEnvelope` is the Node-side mirror of
// `re-frame.mcp-base.dedup/expand`: given a structuredContent value that may be
// wrapped in `:rf.mcp/dedup-table`, reconstruct the agent-visible
// payload. Idempotent on already-expanded values.
//
// ## Wire shape (post-JSON)
//
// Clojure side:
//   {:rf.mcp/dedup-table {cache-0 <root-value-with-symbol-refs>
//                         cache-1 <subtree-value>
//                         ...}}
//
// where cache keys are namespaced symbols `de-dupe.cache/cache-N` and
// internal cross-references serialise as bare symbols of the same
// shape. JSON encoding flattens those:
//
//   {"rf.mcp/dedup-table": {"de-dupe.cache/cache-0": <root>,
//                           "de-dupe.cache/cache-1": <subtree>,
//                           ...}}
//
// Cross-references inside values surface as plain strings
// `"de-dupe.cache/cache-N"` (Cheshire's default symbol → string
// coercion). The decoder walks the root value and substitutes any
// matching string with its expanded cache entry, memoising to keep
// shared subtrees shared in the reconstructed tree.
//
// ## Reference grammar (rf2-kjv05) — MIRRORED, not invented here
//
// Occupying the namespace is not the same as owning it: an ordinary
// payload value can spell `de-dupe.cache/…`. On the Clojure side that is
// a symbol, a KEYWORD or a string a re-frame app holds in app-db; by the
// time it reaches here JSON has erased all three onto ONE spelling —
// exactly the spelling a real reference arrives under. That erasure is
// the reason this decoder cannot be the place the collision is settled,
// and the reason the encoder escapes all three types rather than the
// symbols alone (rf2-kjv05 was reopened on precisely the keyword gap:
// the Clojure round-trip stayed exact while the JSON projection was
// corrupt, in value and map-KEY position both). A PREFIX-only decoder
// reads every such value as a reference and turns payload data into
// another subtree, into `undefined`, or into a thrown missing-entry
// error. So in VALUE position a token `de-dupe.cache/<name>` is
//
//   - a REFERENCE to slot N, when `<name>` is exactly `cache-<digits>`;
//   - an ESCAPED LITERAL of `de-dupe.cache/<rest>`, when `<name>` is
//     `!<rest>` — strip one `!`;
//   - ordinary payload data otherwise (passed through verbatim).
//
// This mirrors `re-frame.mcp-base.dedup`, which is where the grammar is
// stated normatively (`tools/mcp-base/spec/dedup.md` §Reference
// grammar); the encoder escapes every payload token that would
// otherwise read as one of the first two forms, so a conformant
// server's output is unambiguous by construction.
//
// The grammar here is deliberately TYPE-BLIND, which is what lets the
// two halves stay in step: a JSON string is all this side ever sees, so
// widening the encoder's escape set (symbols, then keywords and strings)
// changed nothing here — the same three rules already decoded the wider
// output. What this decoder does NOT do is recover which Clojure type a
// token started as; nothing on the JSON wire can, and the codec's claim
// is that the JSON PROJECTION survives it unchanged, not that the
// Clojure type does.
//
// A `cache-<digits>` token is a reference whether or not the table holds
// that slot, so a truncated or hand-mangled table still fails LOUD here
// rather than being silently re-read as payload data.
//
// ## API
//
// `decodeDedupEnvelope(structuredContent)` — return the expanded
// payload if `structuredContent` carries the marker, otherwise return
// the input unchanged. Throws if the marker is present but the cache
// has no `cache-0` entry (a malformed envelope).

'use strict';

const DEDUP_TABLE_KEY = 'rf.mcp/dedup-table';
const CACHE_NS_PREFIX = 'de-dupe.cache/';
const ROOT_CACHE_ID = 'de-dupe.cache/cache-0';
const ESCAPE_MARKER = '!';

// The allocator's own spelling, anchored at both ends. Anchoring is the
// whole fix: `startsWith(CACHE_NS_PREFIX)` matched every payload value
// in the namespace, not just the ids `make-cache-element` emits.
const CACHE_REF_RE = /^de-dupe\.cache\/cache-\d+$/;

function isCacheRefString(v) {
  return typeof v === 'string' && CACHE_REF_RE.test(v);
}

// Strip ONE escape marker from an escaped literal; every other value is
// returned unchanged. The inverse of the encoder's escape, and reversible
// under repetition — a payload token spelled `de-dupe.cache/!cache-1`
// arrives as `de-dupe.cache/!!cache-1` and sheds exactly one marker.
function unescapeToken(v) {
  if (typeof v !== 'string' || !v.startsWith(CACHE_NS_PREFIX)) return v;
  const name = v.slice(CACHE_NS_PREFIX.length);
  return name.startsWith(ESCAPE_MARKER)
    ? CACHE_NS_PREFIX + name.slice(ESCAPE_MARKER.length)
    : v;
}

// Distinct in-progress sentinel. Installed in the memo
// while a cache entry is mid-expansion so a cyclic ref that re-enters
// `expandEntry` resolves to THIS sentinel rather than to `undefined` —
// letting the cycle be detected and rejected LOUDLY instead of silently
// corrupting the reconstruction with an `undefined` hole. A unique object
// identity (not a value) so it can never collide with a real expanded
// payload.
const EXPANDING = Symbol('dedup-envelope/expanding');

function expandCache(cache) {
  // Memoise expanded cache entries so shared subtrees stay
  // structurally shared in the reconstruction (and to terminate on
  // self-referential cache shapes, should they ever arise).
  const memo = new Map();

  function expandValue(v) {
    if (isCacheRefString(v)) return expandEntry(v);
    if (Array.isArray(v)) return v.map(expandValue);
    if (v && typeof v === 'object') {
      // Preserve plain-object shape; we never see records / non-plain
      // objects on the JSON wire.
      const out = {};
      for (const k of Object.keys(v)) {
        // Route the KEY through expandValue too, not just the value. The
        // real `re-frame.mcp-base.dedup/expand` dispatches on `map-entry?` before
        // `coll?` and expands BOTH halves of every map-entry — `cachable?`
        // permits a de-duped map KEY, and this codebase has vector/
        // path-keyed structures where that fires. Expanding only `v[k]`
        // and using `k` verbatim leaves a de-duped key as the raw
        // "de-dupe.cache/cache-N" placeholder string in the decoded
        // object, so a downstream lookup against the real (expanded) key
        // fails a conformant server. Plain (non-ref) keys pass through
        // `expandValue` unchanged, so this is safe to apply uniformly.
        const expandedKey = expandValue(k);
        // JS object keys must be strings; a Clojure map key can expand to
        // an arbitrary structure (e.g. a path vector). Use it directly
        // when it already is a string/number, otherwise fall back to a
        // deterministic canonical string so the entry is still reachable
        // (rather than silently colliding/overwriting under implicit
        // `String(...)` coercion).
        const jsKey =
          typeof expandedKey === 'string' || typeof expandedKey === 'number'
            ? expandedKey
            : JSON.stringify(expandedKey);
        out[jsKey] = expandValue(v[k]);
      }
      return out;
    }
    // Scalars (numbers, booleans, null, non-ref strings) pass through —
    // save for an escaped literal, which sheds one marker here.
    return unescapeToken(v);
  }

  function expandEntry(cacheId) {
    if (memo.has(cacheId)) {
      const memoized = memo.get(cacheId);
      // A ref that resolves to the in-progress sentinel is a CYCLE: this
      // entry is still mid-expansion higher up the stack. Real de-dupe
      // caches are acyclic — refs always point at strictly-smaller
      // subtrees — so a cycle is a malformed / adversarial table. Fail
      // LOUD rather than returning `undefined` (silent corruption); a
      // conformance decoder must reject malformed input.
      if (memoized === EXPANDING) {
        throw new Error(
          'dedup-envelope: cyclic dedup cache — reference ' + cacheId +
            ' forms a cycle (entry refers back to itself while expanding). ' +
            'Real de-dupe caches are acyclic; refusing to decode a malformed ' +
            'cache table rather than returning a corrupt undefined hole.',
        );
      }
      return memoized;
    }
    if (!Object.prototype.hasOwnProperty.call(cache, cacheId)) {
      throw new Error(
        'dedup-envelope: cache reference ' + cacheId +
          ' has no matching entry in the dedup table',
      );
    }
    // Install the in-progress sentinel before recursing so a cyclic ref
    // re-entering this entry is DETECTED (above) and thrown — not blown up
    // as a stack overflow, and not silently short-circuited to `undefined`.
    // Real de-dupe caches are acyclic; the cheap defensive guard costs
    // nothing and now fails loud on adversarial input.
    memo.set(cacheId, EXPANDING);
    const expanded = expandValue(cache[cacheId]);
    memo.set(cacheId, expanded);
    return expanded;
  }

  // Eagerly expand EVERY cache entry, not just those reachable from
  // cache-0. The real re-frame.mcp-base.dedup/decompress-cache expands every key
  // before picking cache-0 off the result, so a malformed ORPHAN entry
  // (unreferenced from the root — e.g. a dangling ref to a nonexistent
  // cache id, or itself cyclic) throws for a real client but, if only
  // `expandEntry(ROOT_CACHE_ID)` below were called, would never be
  // visited here and would decode cleanly (grading GREEN a wire payload
  // a real client rejects outright). `expandEntry` memoises, so this
  // costs nothing extra for the reachable subset.
  for (const cacheId of Object.keys(cache)) {
    expandEntry(cacheId);
  }
  return expandEntry(ROOT_CACHE_ID);
}

function decodeDedupEnvelope(structuredContent) {
  if (
    !structuredContent ||
    typeof structuredContent !== 'object' ||
    Array.isArray(structuredContent) ||
    !Object.prototype.hasOwnProperty.call(structuredContent, DEDUP_TABLE_KEY)
  ) {
    return structuredContent;
  }
  const cache = structuredContent[DEDUP_TABLE_KEY];
  if (!cache || typeof cache !== 'object' || Array.isArray(cache)) {
    throw new Error(
      'dedup-envelope: ' + DEDUP_TABLE_KEY +
        ' slot MUST be a JSON object (cache-map); got: ' +
        JSON.stringify(cache),
    );
  }
  return expandCache(cache);
}

module.exports = {
  DEDUP_TABLE_KEY,
  CACHE_NS_PREFIX,
  ROOT_CACHE_ID,
  decodeDedupEnvelope,
};

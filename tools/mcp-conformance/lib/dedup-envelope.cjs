// Wire-side decoder for the `:rf.mcp/dedup-table` envelope (rf2-90eft).
//
// ## Why this lives here
//
// Two MCP servers (pair-mcp + story-mcp) wrap selected tools'
// `:structuredContent` payloads in a top-level marker
// `{:rf.mcp/dedup-table <cache-map>}` — see `tools/mcp-base/spec/
// vocab.md` for the canonical key and `tools/story-mcp/src/re_frame/
// story_mcp/tools/dedup.cljc` for the production transform. Real MCP
// clients (Claude Code, Continue, …) running on a JVM / CLJS host
// decode by calling `de-dupe.core/expand` on the cache map; the
// reconstructed payload is what user code sees.
//
// The conformance harness drives the servers from Node, so it does
// not have `de-dupe.core/expand` reachable. Per the rf2-90eft
// post-mortem (CI run 26403312986), the story-mcp end-to-end test
// asserted `:lifecycle` at the top level of `:structuredContent` —
// a SEMANTIC contract — but the literal JSON shape after dedup wraps
// the slot inside `cache-0` of the dedup table. We were checking the
// wire shape, but conformance MUST validate the semantic contract a
// real client sees.
//
// `decodeDedupEnvelope` is the Node-side mirror of
// `de-dupe.core/expand`: given a structuredContent value that may be
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

function isCacheRefString(v) {
  return typeof v === 'string' && v.startsWith(CACHE_NS_PREFIX);
}

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
        out[k] = expandValue(v[k]);
      }
      return out;
    }
    // Scalars (numbers, booleans, null, non-ref strings) pass through.
    return v;
  }

  function expandEntry(cacheId) {
    if (memo.has(cacheId)) return memo.get(cacheId);
    if (!Object.prototype.hasOwnProperty.call(cache, cacheId)) {
      throw new Error(
        'dedup-envelope: cache reference ' + cacheId +
          ' has no matching entry in the dedup table',
      );
    }
    // Install a placeholder before recursing so a (hypothetical) cycle
    // can short-circuit without blowing the stack. Real de-dupe caches
    // are acyclic — refs always point at strictly-smaller subtrees —
    // but the cheap defensive memo costs nothing.
    memo.set(cacheId, undefined);
    const expanded = expandValue(cache[cacheId]);
    memo.set(cacheId, expanded);
    return expanded;
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

// Exact-token text containment — a stricter alternative to
// `String#includes` for checking a structured-error-reason keyword (or
// any hyphenated token) appears in free-form response text.
//
// ## Why this exists
//
// `String#includes` treats a token as present whenever it is a literal
// SUBSTRING — it can't distinguish `:invalid-cofx` from
// `:invalid-cofx-time-ms` (the former IS a substring of the latter), so
// a conformance assertion built on `.includes(reason)` collapses two
// distinct structured-error reasons onto the shortest one: a server
// returning the WRONG reason (`:invalid-cofx-time-ms` for a case that
// should have produced `:invalid-cofx`, or vice versa) still satisfies
// the shorter check and grades GREEN.
//
// `includesToken` anchors the match: it requires no keyword-continuation
// character (a word character or hyphen — the characters EDN keywords
// use internally) immediately before or after the match, so
// `:invalid-cofx` only matches a text where it appears as a complete
// keyword, not as a prefix of a longer one.

'use strict';

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function includesToken(text, token) {
  return new RegExp('(?<![\\w-])' + escapeRegExp(token) + '(?![\\w-])').test(text);
}

module.exports = { escapeRegExp, includesToken };

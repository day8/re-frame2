# `args` — argument coercion helpers

> **Type:** Reference (`tools/mcp-base/spec/`)
> Parsers that take an ALREADY-RESOLVED raw value (extracted by the consumer from its platform-specific args object: a JS object for re-frame2-pair-mcp, a Clojure map for story-mcp / causa-mcp) and normalise it into the Clojure-side type the tool body expects. Cross-MCP arg-vocabulary convention: an agent that learns `:dedup` defaults true on re-frame2-pair-mcp must see the same default everywhere.

This doc is one of seven per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`diff-encode.md`](diff-encode.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md).

## Scope

`args` owns:

- The cross-MCP parser catalogue (`parse-boolean`, `parse-positive-int`, `parse-non-negative-int`, `fresh-keyword`, `safe-keyword`, `parse-mode`).
- The default-handling posture for each parser (call-sites supply the default; the parser is policy-free).
- The keyword-interning safety rule (per rf2-ih7g4): unbounded inputs MUST route through `safe-keyword`; only operator-gated write paths may use `fresh-keyword`.

`args` does NOT own:

- Platform-specific argument extraction (each consumer extracts the raw value from its platform's args object before calling these parsers).
- The argument schemas themselves (each tool's argument schema is owned by the tool's `spec/` folder).
- The arg-name vocabulary (`:include-sensitive?`, `:dedup`, etc.) — that's a cross-server convention pinned by each consumer's tool catalogue and the conformance gate.

## Cross-server convention

Argument names and default postures are a cross-MCP convention. An agent that learns `:dedup` defaults true on re-frame2-pair-mcp must see the same default everywhere. These parsers encode the defaults so they can't drift across consumers.

The rejection posture (default-suppress vs default-allow) is named at the call-site by passing the appropriate `default` — the parser itself is policy-free.

## Surface

| Parser | Accepts | Output | Notes |
|---|---|---|---|
| `parse-boolean` | bools, strings (`"true"`/`"false"`/`"1"`/`"0"`/`"yes"`/`"no"`/`"y"`/`"n"`/`"on"`/`"off"`, case-insensitive), keywords (`:true`/`:false`), nil | boolean | Unrecognised → `default`. Call-sites wrap to bake the default. |
| `parse-positive-int` | ints, parsable strings | positive int or `default` | Strictly positive; zero falls to `default`. |
| `parse-non-negative-int` | ints, parsable strings | non-negative int or `default` | Zero allowed. |
| `fresh-keyword` | keywords, strings (leading `:` optional), nil | keyword or `nil` | The `:` prefix is stripped on string input. **Positive-named intern (rf2-xxtrz)**: the name signals the call-site is ALLOCATING a new id, not resolving an existing one. INTERNS by design — reserved for operator-gated write paths whose registrar grammar bounds the per-id allocation cost (e.g. story-mcp's `register-variant`). For finite option sets, use `safe-keyword`. |
| `safe-keyword` | keywords, strings, nil | keyword from `allowed` set, or `nil` | Bounded-allowlist gate — NEVER interns a fresh keyword on rejection. The right primitive for finite option sets and any input that doesn't go straight to a registry lookup with a bounded known set. Per rf2-ih7g4. |
| `parse-mode` | enum-shaped strings / keywords | one of an allowed set, otherwise `default` | Bakes an `allowed-modes` set; rejected values fall to `default`. Routes through `safe-keyword` so unrecognised input never interns (rf2-ih7g4). |

## Keyword-interning safety (rf2-ih7g4)

The same threat model that drives `:rf.http/max-decoded-keys` ([`../../../spec/014-HTTPRequests.md` §Keyword-interning cap](../../../spec/014-HTTPRequests.md)) applies to MCP argument parsing. An MCP server is a long-running process; every `(keyword raw-agent-string)` call against unbounded user input grows the host's interned-symbol table for the life of the process. A compromised agent submitting N-unique-string arguments-per-call would permanently burn N slots in the keyword table.

The cross-MCP rule:

1. **`safe-keyword` is the default primitive.** Every cross-MCP arg whose set of valid values is *bounded* — modes, enum-like opts, registered tool ids — uses `safe-keyword` with the allowlist passed in.
2. **`fresh-keyword` is reserved for operator-gated write paths.** When the arg's value is by design a NEW identifier (story-mcp's `register-variant` / `record-as-variant`), `fresh-keyword` is the positive-named primitive. The intern is bounded by an operator gate (`--allow-writes`) AND a registrar grammar (`:story.<path>/<name>` per `assert-id!`). The name carries the "I am allocating, not resolving" posture; no warning-laden docstring needed at the call site.
3. **`parse-mode` routes through `safe-keyword`.** The convention's enum-shaped parser is internally safe; consumers should use it rather than rolling their own.

The keyword-interning cap (`:rf.http/max-decoded-keys`) defends against the body-decode threat; this convention defends against the argument-parse threat. Both close the same DoS / keyword-table-poisoning vector.

## Default-handling posture

The parser itself is policy-free. The call-site supplies the default that determines the rejection posture:

```clojure
;; default-suppress: rejected values become nil
(parse-boolean v nil)

;; default-allow: rejected values become true
(parse-boolean v true)

;; bake the default once per call-site
(defn arg-dedup [v] (parse-boolean v true))   ; re-frame2-pair-mcp's :dedup default
```

This split keeps the parser pure data — no thread-local policy, no global config.

## Cross-platform

All six parsers are pure `.cljc`. They use:

- `boolean?` / `int?` / `keyword?` / `string?` host predicates.
- Reader-conditional-free `Integer/parseInt` (JVM) / `js/parseInt` (CLJS) via a small `.cljc` adapter.
- Standard collection ops; no host-specific machinery.

`safe-keyword`'s rejection branch carries the bounded-allowlist check — a fresh keyword is created with `keyword` only after the allowlist passes.

## Conformance posture

Per-parser fixture tests live in `tools/mcp-base/test/`. Cross-consumer convention checks live in `tools/mcp-conformance/`:

- Every cross-MCP tool that accepts a finite-option arg uses `safe-keyword`. The conformance harness diffs argument schemas across consumers and asserts the same allowlist appears everywhere.
- Default-handling parity: a sample arg that's unset on each consumer must produce identical observable behaviour across the triplet. The harness drives each tool with `{}` (no args) and asserts the response envelope matches the convention's documented defaults.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/014-HTTPRequests.md` §Keyword-interning cap](../../../spec/014-HTTPRequests.md) — the framework counterpart to this ns's keyword-safety rule.
- [`../../../spec/Security.md` §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) — the broader privacy posture this ns's `:include-sensitive?` convention is part of.
- [`vocab.md`](vocab.md) — the marker-key catalogue agents pattern-match on once the parsers have normalised the args.

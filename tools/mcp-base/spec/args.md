# `args` — argument coercion helpers

> **Type:** Reference (`tools/mcp-base/spec/`)
> Parsers that take an already-resolved raw value from a consumer's platform-specific argument object and normalize it for the tool body. Shared coercion semantics keep the JVM and CLJS consumers aligned; call sites supply policy defaults.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## Scope

`args` owns:

- The cross-MCP parser catalogue (`parse-boolean`, `parse-positive-int`, `fresh-keyword`, `safe-keyword`, `parse-mode`).
- The default-handling posture for each parser (call-sites supply the default; the parser is policy-free).
- The keyword-interning safety rule: bounded lookups route through `safe-keyword`; only deliberate, operator-gated allocation paths use `fresh-keyword`.

`args` does NOT own:

- Platform-specific argument extraction (each consumer extracts the raw value from its platform's args object before calling these parsers).
- The argument schemas themselves (each tool's argument schema is owned by the tool's `spec/` folder).
- The arg-name vocabulary (`:include-sensitive`, `:dedup`, etc.) — that's a cross-server convention pinned by each consumer's tool catalogue and the conformance gate.

## Cross-server convention

Argument names and default postures are a cross-MCP convention. These parsers share coercion semantics; each consumer supplies the documented default at its call site.

The rejection posture (default-suppress vs default-allow) is named at the call-site by passing the appropriate `default` — the parser itself is policy-free.

## Surface

| Parser | Accepts | Output | Notes |
|---|---|---|---|
| `parse-boolean` | bools, strings (`"true"`/`"false"`/`"1"`/`"0"`/`"yes"`/`"no"`/`"y"`/`"n"`/`"on"`/`"off"`, case-insensitive), keywords (`:true`/`:false`), nil | boolean | Unrecognised → `default`. Call-sites wrap to bake the default. |
| `parse-positive-int` | finite in-range numbers, integer strings | positive int or `default` | Numbers floor toward zero, then clamp to 1. Invalid or out-of-domain inputs return `default`. |
| `fresh-keyword` | keywords, strings (leading `:` optional), nil | keyword or `nil` | INTERNS by design — reserved for operator-gated paths that deliberately allocate a new id. It is not a registry lookup helper. |
| `fresh-keyword-checked` | keywords, strings (leading `:` optional), nil; a `[ns name] → bool` shape predicate; optional `max-len` | keyword or `nil` | Validates a string's decomposed shape and length before interning. Keyword inputs are already interned, so they are shape-checked but not length-checked. |
| `safe-keyword` | keywords, strings, nil | keyword from `allowed` set, or `nil` | Bounded-allowlist gate — never interns a fresh JVM keyword on rejection. Use for finite options and live registry lookups. |
| `parse-mode` | enum-shaped strings / keywords | one of an allowed set, otherwise `default` | Routes through `safe-keyword`; rejected values fall to `default`. |

## Keyword-interning safety

The same threat model that drives `:rf.http/max-decoded-keys` ([`../../../spec/014-HTTPRequests.md` §Keyword-interning cap](../../../spec/014-HTTPRequests.md)) applies to MCP argument parsing. An MCP server is a long-running process; every `(keyword raw-agent-string)` call against unbounded user input grows the host's interned-symbol table for the life of the process. A compromised agent submitting N-unique-string arguments-per-call would permanently burn N slots in the keyword table.

The cross-MCP rule:

1. **`safe-keyword` is the default primitive.** Every cross-MCP arg whose set of valid values is *bounded* — modes, enum-like opts, registered tool ids — uses `safe-keyword` with the allowlist passed in.
2. **`fresh-keyword` / `fresh-keyword-checked` are reserved for operator-gated allocation paths.** When an argument is by design a new identifier, prefer `fresh-keyword-checked` so its grammar and string length are validated before interning. A grammar limits per-id shape, not the number of valid ids; the operator gate remains the allocation policy.
3. **`parse-mode` routes through `safe-keyword`.** The convention's enum-shaped parser is internally safe; consumers should use it rather than rolling their own.

The keyword-interning cap (`:rf.http/max-decoded-keys`) defends against the body-decode threat; this convention defends against the argument-parse threat. Both close the same DoS / keyword-table-poisoning vector.

## Cross-runtime numeric domain

`parse-positive-int` (and, via `cursor/parse-limit-arg` and `cap/max-tokens`, every numeric MCP arg) coerces through one shared finite/range guard (`coerce-finite-long`) BEFORE `(long raw)`. The guard exists because `(long raw)` is unsafe at the arg boundary:

- On the JVM `(long ##Inf)` and `(long 1.0E20)` THROW `IllegalArgumentException` — a crash at the wire boundary instead of a recoverable default; `(long ##NaN)` truncates to a real `0` (a real, non-sentinel value).
- The hosts have different numeric ranges and parsing behavior, so both arms enforce the JS safe-integer window before returning a value.

The admissible domain is the JS safe-integer window `[-(2^53−1), 2^53−1]` — the strictest of the two hosts, and far wider than any real MCP arg (pagination limits, token caps). An out-of-domain value (non-finite, NaN, or past the window) is treated uniformly: the int parsers fall to `default`; `cap/max-tokens` rejects with `{:rf.mcp/invalid-arg}`. Never a crash, never a real `0`-cap, never a host-divergent result.

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

- `boolean?` / `number?` / `keyword?` / `string?` host predicates.
- A reader-conditional finite/range guard: `Double/isNaN`/`isInfinite` + the safe-integer window (JVM), `js/isFinite`/`isNaN` + `Number.isSafeInteger` (CLJS). The numeric string arm parses via `bigint` (JVM) / `js/parseInt` (CLJS) and clamps to the same window so the two hosts agree.
- Standard collection ops; no host-specific machinery.

On the JVM, `safe-keyword` uses `find-keyword`, so a rejected string is never interned. CLJS constructs a keyword for membership testing but has no JVM-style permanent keyword table; both hosts return the same allowed value or `nil`.

## Conformance posture

Per-parser fixture tests live in `tools/mcp-base/test/`. Consumer suites cover the call-site defaults and their advertised argument schemas.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/014-HTTPRequests.md` §Keyword-interning cap](../../../spec/014-HTTPRequests.md) — the framework counterpart to this ns's keyword-safety rule.
- [`../../../spec/Security.md` §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) — the broader privacy posture this ns's `:include-sensitive` convention is part of.
- [`vocab.md`](vocab.md) — the marker-key catalogue agents pattern-match on once the parsers have normalised the args.

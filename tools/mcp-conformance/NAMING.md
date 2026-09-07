# Cross-MCP tool naming

`re-frame2-pair-mcp` and `story-mcp` share a bounded tool-name
vocabulary. A tool's leading verb should tell a client whether it reads,
enumerates, executes, mutates, or waits without requiring
server-specific guesswork.

Each server's canonical `tool-names.json` fixture owns its complete
catalogue. The JVM `verb_vocab_test.clj` gate reads those fixtures and
checks every name against the vocabulary below. This document does not
repeat either full catalogue.

## Verb vocabulary

| Shape | Meaning | Examples |
|---|---|---|
| `get-<thing>` | Read one addressed record or value. | `get-path`, `get-story`, `get-operating-frame` |
| `list-<things>` | Enumerate a collection. | `list-handlers`, `list-stories`, `list-subscriptions` |
| `read-<thing>` | Re-read already computed or rendered diagnostic state; do not imply a fresh run. | `read-sub`, `read-dom`, `read-failures` |
| `discover-<surface>` | Bootstrap health and connection discovery. | `discover-app` |
| `restore-<thing>` | Restore a prior state. | `restore-epoch` |
| `replay-<thing>` | Re-drive a recorded thing through the live system, faithfully or fail-loud; the recorded run is the input, never a caller payload. | `replay-epoch` |
| `replace-<thing>` | Replace a named state partition outside the normal cascade. | `replace-app-db` |
| `reset-<thing>` | Clear a named state or session partition. | `reset-operating-frame` |
| `set-<thing>` | Pin one named session setting. This prefix is reserved for the operating-frame contract. | `set-operating-frame` |
| `register-<thing>` / `unregister-<thing>` | Add or remove a registry entry. | `register-variant`, `unregister-variant` |
| `run-<thing>` | Execute a definition and report pass/fail results. | `run-variant` |
| `preview-<thing>` | Execute and report rendered or resolved state without a pass/fail claim. | `preview-variant` |
| `explain-<thing>` | Read derivation or resolution reasoning without executing. | `explain-variant` |
| `describe-<thing>` | Read composed behaviour and its provenance for an addressed runtime context. | `describe-image` |
| `record-as-<thing>` | Capture activity and return it as an artefact. **Reserved — no shipped tool currently bears it.** Its one bearer, story-mcp's `record-as-variant`, was retired under rf2-5saz7; the prefix stays catalogued because it is what keeps a live observer from being misnamed `record-as-…` (a capture-as-persisted-artefact claim). | — |
| `tail-<thing>` | Wait for an external state change or timeout. | `tail-build` |
| `watch-<thing>` | Wait until a predicate over live signals holds or times out. | `watch-until` |

The following names are intentionally bare:

- `dispatch` and `dispatch-dry-run`: the framework event primitives.
- `eval-cljs`: the browser-runtime evaluation primitive.
- `snapshot`, `trace-window`, `watch-epochs`, `orient`, and `record`:
  coarse projections or recorders that span several registry kinds.

Three catalogue names are explicit exceptions:

- `handler-meta`: a single metadata record.
- `variant->edn`: a Clojure-idiomatic canonical-form projection.
- `snapshot-identity`: a single identity digest.

The exception allowlist is executable in `verb_vocab_test.clj`; adding a
name there requires the matching rationale here.

## Rejected synonyms

Do not introduce alternate verbs for an existing semantic:

- use `get-`, not `fetch-`, `query-`, `find-`, or `lookup-`;
- use `list-`, not `enumerate-`, `all-`, or a `<things>-list` suffix;
- use a named mutation such as `dispatch`, `register-`, `restore-`, or
  `replace-`, not generic `update-`;
- reserve `set-` for `set-operating-frame`, rather than generic state
  mutation;
- use `eval-cljs` or `dispatch`, not `call-`, `invoke-`, or `run-fn`;
- use `tail-` for one external
  change, `watch-` for a predicate, and `record` for a bounded live
  change log.

## Catalogue and count ownership

The complete tool lists and counts live in the server catalogues:

- [`re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`](../re-frame2-pair-mcp/spec/003-Tool-Catalogue.md)
- [`story-mcp/spec/002-Tool-Registry.md`](../story-mcp/spec/002-Tool-Registry.md)

Other documents should link to those catalogues instead of copying a
count or inventory. The executable conformance harness loads these
canonical fixtures directly:

- `tools/re-frame2-pair-mcp/test/fixtures/tool-names.json`
- `tools/story-mcp/test/fixtures/tool-names.json`

Adding or removing a tool updates the owning server catalogue and
fixture. The Node call-coverage ratchet then requires the advertised
tool to be called or explicitly excluded, while the JVM verb linter
requires its name to conform.

## Operator flags

Equivalent authority gates use the same launch-flag spelling across
servers:

| Flag | Default | Contract |
|---|---|---|
| `--no-eval` | eval enabled | Pair-only opt-out for `eval-cljs`; disabled calls return `:rf.error/eval-cljs-disabled` before nREPL. |
| `--allow-sensitive-reads` | closed | Allows per-call sensitive/raw opt-ins that are otherwise forced to the safe projection. |
| `--allow-writes` | closed | Enables the named out-of-band state or registry writes. It is not a blanket read-only mode: Pair's `dispatch` and `replay-epoch` remain available, and `--no-eval` independently disables only `eval-cljs`. |

Rules:

- describe the operator-visible authority, not an implementation detail;
- reject removed spellings instead of maintaining aliases;
- keep the gate at `tools/call`, so descriptors remain discoverable;
- return a structured refusal without contacting the runtime when the
  launch gate is closed.

`test/end-to-end-flag-gates.cjs` owns the SDK-level story write-gate
checks. The live pair suite owns the pair configurations that require a
runtime, including the `--no-eval` result envelope. Server unit tests own
argument parsing and removed-flag diagnostics.

## JSON-RPC versus tool errors

Protocol failures use JSON-RPC numeric codes. The canonical constants
live in `tools/mcp-base/src/re_frame/mcp_base/vocab.cljc`; the SDK
conformance harness pins `MethodNotFound` and accepts the SDK-observed
`InvalidParams`/`InternalError` union for malformed `tools/call` input.

Tool execution failures remain MCP tool results: `isError: true` plus a
structured reason. Cross-server reason and marker vocabulary belongs to
[`wire-vocab/README.md`](wire-vocab/README.md), not the tool-name table.

## Extending the vocabulary

A genuinely new verb shape requires:

1. a rationale in the owning server's `spec/DESIGN-RATIONALE.md`;
2. a row in this table;
3. a corresponding update to `verb-prefixes`, `bare-verbs`, or the
   exception allowlist in `verb_vocab_test.clj`; and
4. wire-vocabulary coverage if the tool emits a shared marker.

Prefer an existing shape whenever its semantics fit.

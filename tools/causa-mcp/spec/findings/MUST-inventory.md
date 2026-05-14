# MUST-inventory — Causa-MCP pre-impl

**Bead**: rf2-g3u9f (parent audit rf2-c33xi).
**Status**: pre-impl scaffold. Exploratory; not normative.
**Substrate**: per [`tools/causa-mcp/spec/README.md`](../README.md)
line 25 (`findings/` — exploratory working substrate; audit
lineage, not normative).

## Purpose

Causa-MCP's spec scaffold names ~20 MUST-level invariants across
[`Principles.md`](../Principles.md) and
[`004-Wire-Pipeline.md`](../004-Wire-Pipeline.md). When
`tools/causa-mcp/src/` lands and the impl pass authors the test
corpus, every MUST needs a corresponding test. Today there is no
inventory binding MUSTs to test-stubs — risk: the impl-pass
corpus grows organically and misses one. Precedent: pair2-mcp's
`:elided-large` parity bead (rf2-zjqh8) where the indicator MUST
drifted between sites because no per-tool test pinned it.

This doc is the forcing function. Every MUST below MUST surface
as a named test (or named test cluster) once
`tools/causa-mcp/test/` exists. New MUSTs added to the spec MUST
land alongside an inventory row.

The inventory is intentionally **pre-impl**: test names are
sketched, not authoritative. When impl ships, the column
"Planned test" gets sharpened against the actual deftest names
and any inventory rows that didn't translate cleanly get
audited.

## Cross-server MUSTs (separate substrate)

A subset of the MUSTs below are not Causa-MCP-private — they are
cross-server reservations shared with pair2-mcp and story-mcp
(token-cap shape, `:rf.mcp/overflow` envelope, `:sensitive?`
default-drop, `:rf.size/large-elided` marker shape, the
`max-tokens` arg name). Those land in
[`tools/mcp-conformance/`](../../../mcp-conformance/) tests —
tracked separately by rf2-zvv65. They appear in this inventory
for completeness; the **test owner** column flags them.

## Inventory

| # | MUST | Source | Test owner | Planned test (pre-impl sketch) |
|---|---|---|---|---|
| 1 | Framework-published listener integrations (Causa-MCP server) MUST default-suppress `:sensitive? true` events | [004-Wire-Pipeline.md L32](../004-Wire-Pipeline.md) | Cross-server (rf2-zvv65) | `mcp-conformance/sensitive-default-drop-test` — Causa-MCP row |
| 2 | Every tool surfacing trace-stream-shaped payloads (`get-trace-buffer`, `subscribe`, `get-epoch-history`) MUST apply the default-suppress filter at the MCP boundary before any data crosses into the agent surface | [004-Wire-Pipeline.md L39](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.privacy/get-trace-buffer-drops-sensitive`; `subscribe-drops-sensitive`; `get-epoch-history-drops-sensitive` |
| 3 | A tool that cannot answer inside the 5,000-token budget MUST trim, summarise, slice, paginate, or dedupe rather than over-spend | [004-Wire-Pipeline.md L74](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.budget/no-tool-overspends-cap` (parameterised across every catalogue entry) |
| 4 | Every catalogue entry (`003-Tool-Catalogue.md`) MUST declare which of the six mechanisms apply, with `:typical-tokens` and `:cap-reached` slots | [004-Wire-Pipeline.md L100, L359-363](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.catalogue/every-entry-declares-mechanism-set` (static-shape lint, runs against the registry-load step) |
| 5 | Every tool that returns to the agent MUST measure the rendered payload (post-EDN-encoding, post-JSON-wrap) against the cap before returning | [004-Wire-Pipeline.md L115](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.budget/every-tool-measures-pre-return` |
| 6 | A tool that would exceed the cap MUST NOT silently truncate | [004-Wire-Pipeline.md L121](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.budget/overflow-never-silent` (paired with #7) |
| 7 | A tool exceeding the cap MUST return the structured overflow marker at the top of the payload | [004-Wire-Pipeline.md L122](../004-Wire-Pipeline.md) | Causa-MCP + cross-server (rf2-zvv65) | `causa-mcp.tools.budget/overflow-marker-shape`; `mcp-conformance/overflow-marker-cross-server` |
| 8 | Tools returning rich nested values (`get-app-db`, `get-machine-state`, `get-epoch-history`) MUST accept an optional `:path` argument (EDN-encoded vector) | [004-Wire-Pipeline.md L141](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.path/{get-app-db,get-machine-state,get-epoch-history}-accepts-path-arg` |
| 9 | The default behaviour without a `:path` argument MUST be a tree-summary, not the full payload | [004-Wire-Pipeline.md L145](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.path/default-mode-is-summary` (parameterised) |
| 10 | Sequence-returning tools MUST accept `:cursor` (opaque string) and `:limit` (integer) | [004-Wire-Pipeline.md L158](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.pagination/sequence-tools-accept-cursor-and-limit` (parameterised: `get-trace-buffer`, `get-epoch-history`, `list-subscriptions`, others) |
| 11 | Sequence-returning responses MUST carry `:next-cursor` (opaque or nil) and `:remaining` (count/estimate) | [004-Wire-Pipeline.md L163](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.pagination/response-shape` |
| 12 | Tools returning rich nested values MUST expose a `:mode` argument with at least `:summary` (default), `:sample`, and `:full` | [004-Wire-Pipeline.md L184](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.mode/exposes-summary-sample-full` (parameterised) |
| 13 | `get-app-db-diff` in particular MUST default to changed-paths-with-cardinalities, not the nested diff | [004-Wire-Pipeline.md L189](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.tools.diff/default-shape-is-paths-with-cardinalities` |
| 14 | Catalogue entries in `003-Tool-Catalogue.md` MUST cite the regime-appropriate compression factor when declaring `:typical-tokens` (~1.4× trace bursts; ~10× recurring cascades; 5-10× epoch slices) | [004-Wire-Pipeline.md L226](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.catalogue/dedup-factor-cited` (registry-load lint) |
| 15 | Causa-MCP catalogue MUST declare the `:include-large?` slot, the `:elided-large` indicator field, and the default elision-policy on every tool emitting tree-typed payloads | [004-Wire-Pipeline.md L342](../004-Wire-Pipeline.md) | Causa-MCP + cross-server (rf2-zvv65) | `causa-mcp.tools.elision/every-tree-tool-declares-elision-slots`; cross-server marker-shape test in mcp-conformance |
| 16 | Every tool entry MUST declare which mechanisms apply, `:typical-token` hint, `:cap-reached` behaviour, default `:mode` / `:limit` / `:dedup?` values (catalogue-entry contract) | [004-Wire-Pipeline.md L359-363](../004-Wire-Pipeline.md) | Causa-MCP | `causa-mcp.catalogue/entry-declares-all-required-slots` (the load-bearing scaffolding test) |
| 17 | The size-elision walker (`re-frame.core/elide-wire-value`) is the single normative emission site — per-tool reimplementation is prohibited | [004-Wire-Pipeline.md L301-302](../004-Wire-Pipeline.md) | Causa-MCP + cross-server | `causa-mcp.tools.elision/walker-is-single-emission-site` (lint over the impl source — searches for marker keyword construction outside the walker call) |
| 18 | The `:sensitive?` / `:large?` composition cascade `(and sensitive? large?) → ::drop` MUST hold — sensitive wins; no marker emitted when both predicates match | [004-Wire-Pipeline.md L277-291](../004-Wire-Pipeline.md), [DESIGN-RATIONALE Lock #10](../DESIGN-RATIONALE.md) | Cross-server (rf2-zvv65) + Causa-MCP | `mcp-conformance/elision-composition-sensitive-wins`; causa-mcp integration test exercising the cascade end-to-end |
| 19 | Direct-read tools (`get-app-db`, `get-app-db-diff`, `get-machine-state`, sub-cache reads, direct epoch slices) MUST route returned values through `rf/elide-wire-value` with **both** `:include-sensitive?` AND `:include-large?` defaulting `false` before egress. Pairs with the cross-MCP normative MUST at `spec/Tool-Pair.md` §Direct-read privacy posture (L569). Row #15 covers `:include-large?` only; this row pins the `:include-sensitive?` half on the direct-read surface. | [004-Wire-Pipeline.md §Privacy L53-68](../004-Wire-Pipeline.md), [spec/Tool-Pair.md L569](../../../../spec/Tool-Pair.md) | Causa-MCP | `causa-mcp.tools.privacy/direct-read-tools-elide-sensitive-by-default` (parameterised across `get-app-db`, `get-app-db-diff`, `get-machine-state`, sub-cache, direct epoch slices); `direct-read-tools-elide-large-by-default` (pairs with row #15) |

## Implicit MUSTs (not RFC-2119-cased but normatively binding)

Some load-bearing invariants are spelled out without the literal
"MUST" word. They behave like MUSTs for the impl pass and are
catalogued for inventory completeness.

| # | Invariant | Source | Test owner | Planned test |
|---|---|---|---|---|
| I1 | Every Causa-MCP-driven side-effect on the trace bus carries `:tags :origin :causa-mcp` (default-on, per-call opt-out). Synchronous-extent only on `eval-cljs` — see row I6 | [Principles.md §"Origin tagging is the convention"](../Principles.md), [Lock #4](../DESIGN-RATIONALE.md) | Causa-MCP | `causa-mcp.tools.origin/every-mutation-tagged-by-default`; `eval-cljs-inherits-origin-via-dynamic-var` |
| I2 | MCP-server-side code (`day8.re-frame2-causa-mcp.*`) never reaches a consumer app's preload classpath; injected-runtime code lives under `day8.re-frame2-causa.runtime` | [Principles.md §"MCP-server-ns and injected-runtime-ns are distinct"](../Principles.md), [Lock #11](../DESIGN-RATIONALE.md) | Causa-MCP | `causa-mcp.bundle-isolation/server-ns-not-in-preload-classpath` (lint; mirrors `tools/causa/`'s analogous gate) |
| I3 | Single persistent nREPL socket held for the lifetime of the session; subsequent ops reuse without reconnecting | [Principles.md §"Single persistent nREPL socket"](../Principles.md), [Lock #3](../DESIGN-RATIONALE.md) | Causa-MCP | `causa-mcp.nrepl/socket-is-persistent`; `subsequent-ops-reuse` |
| I4 | If the nREPL port can't be resolved at startup, the server still boots and answers `tools/list`; every `tools/call` returns `{:ok? false :reason :nrepl-port-not-found}` | [Principles.md §"Degraded boot, not failed boot"](../Principles.md) | Causa-MCP | `causa-mcp.degraded-boot/boots-without-port`; `tools-call-returns-structured-error` |
| I5 | If the runtime hasn't been preloaded, the first mutating/inspecting tool call returns `{:ok? false :reason :runtime-not-preloaded}` with a setup hint | [Principles.md §"Degraded boot, not failed boot"](../Principles.md) | Causa-MCP | `causa-mcp.degraded-boot/runtime-not-preloaded-shape` |
| I6 | `eval-cljs` MUST set `current-origin` via `binding` whose extent is the **synchronous body** of the eval call only. The picked posture (Principles.md §"Boundary semantics of `eval-cljs` origin tagging") is option (b): async dispatches launched from `eval-cljs` are accepted as untagged and documented as a known audit-trail-coverage gap, not a privacy or authority leak. Refines I1 — the default-on property holds synchronously; async-escape is the spec'd incompleteness. | [Principles.md §"Boundary semantics of `eval-cljs` origin tagging"](../Principles.md), [Lock #4](../DESIGN-RATIONALE.md) | Causa-MCP | `causa-mcp.tools.origin/eval-cljs-binding-wraps-synchronous-body`; `eval-cljs-sync-dispatch-inherits-causa-mcp-tag`; `eval-cljs-async-dispatch-tag-known-incomplete` (documentation-shape test, asserts the spec'd posture not a contract on the runtime) |

## Out-of-spec MUSTs cited (not Causa-MCP-private)

For completeness — references to upstream MUSTs Causa-MCP relies
on but doesn't own. No test owned by `tools/causa-mcp/`; listed
so the inventory is self-auditing.

- The `:rf/elision-marker` schema shape: owned by
  [`spec/Spec-Schemas.md`](../../../../spec/Spec-Schemas.md) +
  [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md).
  Tests live with the schema spec.
- `:rf.size/*` and `:rf.elision/*` reservations: owned by
  [`spec/Conventions.md`](../../../../spec/Conventions.md). Test
  is the namespace-reservation lint.
- Tool-Pair epoch contracts (`get-epoch-history`,
  `restore-epoch`, the six-row restore-failure surface,
  `subscribe :epoch`): owned by
  [`spec/Tool-Pair.md`](../../../../spec/Tool-Pair.md). Tests
  live with the Tool-Pair conformance corpus.

## Inventory hygiene

When a new MUST lands in this folder's spec docs:

1. Add a row above with the source cite, test owner, and planned
   test name.
2. If the MUST is cross-server (touches the shape of the wire on
   pair2-mcp/story-mcp/causa-mcp), tag the row "Cross-server
   (rf2-zvv65)" and file/cross-reference into
   [`tools/mcp-conformance/`](../../../mcp-conformance/).
3. When `tools/causa-mcp/test/` exists, replace the "Planned
   test" sketch with the actual `deftest` name.
4. When a MUST is removed/relaxed, strike through the row but
   keep it in place — the inventory is also an audit trail.

This file is **not** normative; the normative spec docs are.
This file is a forcing function so the impl-pass test corpus
covers every MUST without organic drift.

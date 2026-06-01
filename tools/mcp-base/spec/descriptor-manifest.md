# `descriptor-manifest` — MCP tool-descriptor manifest generator + drift-check

> **Type:** Reference (`tools/mcp-base/spec/`)
> The shared, platform-agnostic serialiser + drift-check that lets BOTH MCP servers (`re-frame2-pair-mcp`, `story-mcp`) GENERATE / VALIDATE a committed `tool-descriptors.edn` manifest from their tool registry — so adding / removing / renaming an MCP tool goes RED in CI until the manifest is regenerated. Models the project's API-governance keystone (`rf2-3nbl5.2`, `spec/api-manifest.edn`) on the MCP descriptor surface (`rf2-sofwv`).

This doc is one of the per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`args.md`](args.md), [`cap.md`](cap.md).

## The problem it solves

Each MCP server has ONE truth — its tool registry (the ordered vector that bundles every tool's name, descriptor, handler, …). The `tools/list` wire surface is a projection of that registry; so were the hand-maintained `test/fixtures/tool-names.json` lists the Node stdio-roundtrip and the cross-server conformance harness compared against. Projections drift: a tool added / removed / renamed in the registry silently diverged from the committed projection until a same-language unit test (if one existed) caught it.

A generated, CI-guarded manifest prevents the drift the way the keystone's `spec/api-manifest.edn` prevents public-API drift: the committed `tool-descriptors.edn` is regenerated in memory and compared; any difference fails the gate.

## Scope

`descriptor-manifest` owns:

- The **catalogue-row projection** (`descriptor->row`): the stable shape one registry descriptor maps to.
- The **deterministic, byte-stable, LF-pinned EDN serialiser** (`render-edn`) — one tool row per line for surgical diffs.
- The **drift-check** (`check`): regenerate-in-memory vs committed, with an added/removed name diff for the failure message.
- `build-rows` / `build-manifest` — the small assembly helpers between the two.

`descriptor-manifest` does NOT own:

- **Reading the registry.** Each server's generator reads its OWN registry on its OWN platform (story-mcp on the JVM, pair-mcp under Node — its registry is CLJS-only) and passes the descriptor seq in. The base never names a registry.
- **File I/O.** Each server's generator does its own `spit`/`slurp` (JVM) or `fs.writeFileSync`/`readFileSync` (Node). The base is pure data-in / string-out.
- **CI wiring.** Each check is wired into the workflow that already has the right toolchain (story-mcp's JVM job; pair-mcp's Node + shadow-cljs job).

## The manifest row shape

```clojure
{:name        "<wire-name>"
 :description "<one-line semantics>"
 :input-keys  ["<sorted inputSchema property keys, as strings>"]
 :output?     <bool — does the tool declare an outputSchema?>
 :annotations ["<sorted annotation-hint keys, as strings>"]}
```

### Why this shape, not the whole descriptor

The full descriptor maps carry deeply-nested JSON-Schema fragments whose exact serialisation (key order inside nested property maps, optional prose) is volatile and not the contract this gate guards. The gate's job is the **catalogue identity**: which tools exist, what they are called, the top-level input-property surface, whether each declares structured output + annotation hints. That is precisely the surface that goes stale when a tool is added / removed / renamed or its input surface changes. The full descriptor's WIRE validity is already pinned by the MCP-SDK conformance harness (`tools/mcp-conformance/`); this manifest pins the catalogue identity.

## Surface

| Fn | Signature | Returns |
|---|---|---|
| `descriptor->row` | `[descriptor-map]` | one stable manifest row |
| `build-rows` | `[descriptors]` | sorted-by-`:name` vector of rows |
| `build-manifest` | `[server descriptors]` | `{:meta {:server :tool-count} :tools [rows]}` |
| `render-edn` | `[manifest]` | deterministic, LF-pinned EDN string |
| `check` | `[generated-manifest generated-edn committed-string-or-nil]` | `{:ok? :added :removed (:missing-file?)}` |

## Determinism + byte-stability

- **One serialiser, two platforms.** story-mcp generates on the JVM, pair-mcp under Node; both route through `render-edn` so the committed EDN is byte-identical regardless of which platform produced it.
- **LF-pinned.** `render-edn` normalises all line endings to bare `\n`; the committed files are `.gitattributes`-pinned to `eol=lf`. Same discipline as the keystone — a Windows author and a Linux CI must agree byte-for-byte or the drift-check trips spuriously.
- **Rows sorted by `:name`.** The manifest order is insensitive to registry authoring order (catalogue IDENTITY, not ordering, is what this gate guards; the wire-surface ordering is pinned separately by each server's order-sensitive tests).
- **One row per line.** Adding a tool is a one-line diff.
- **CRLF-tolerant comparison.** `check` LF-normalises both sides before comparing, so a CRLF working-tree checkout on Windows does not trip a spurious drift.

## Per-server generators (consumer-side)

| Server | Generator ns | Platform | Source of truth | Entry-point |
|---|---|---|---|---|
| story-mcp | `re-frame.story-mcp.descriptor-manifest-gen` | JVM | `registry/tool-registry` | `clojure -M:gen [--check]` |
| re-frame2-pair-mcp | `re-frame2-pair-mcp.descriptor-manifest-gen` | CLJS / Node | `registry/tool-descriptors` | `npm run gen:descriptors` / `npm run check:descriptors` |

Each generator reads the RAW registry descriptors (BEFORE any config-dependent / `tools/list`-time transform — story-mcp's `:include-sensitive` strip, pair-mcp's `max-tokens` / `cache` knob splices) so the manifest is deterministic and config-independent. The committed manifest lives at each artefact's root: `tools/<server>/tool-descriptors.edn`.

## Conformance posture

- **Serialiser determinism + drift semantics** are pinned on the JVM by `tools/mcp-base/test/re_frame/mcp_base/descriptor_manifest_test.clj` (the algorithm is platform-agnostic `.cljc`).
- **The drift-check itself** runs in CI: story-mcp's check rides its JVM job (`clojure -M:gen --check`); pair-mcp's rides its Node + shadow-cljs job (`npm run check:descriptors`). Adding / removing / renaming a tool, or changing a tool's input-key / output? / annotations surface, turns the respective job red until the manifest is regenerated.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/api-manifest.edn`](../../../spec/api-manifest.edn) + `implementation/scripts/api-manifest/` — the keystone (`rf2-3nbl5.2`) this generator models on the MCP descriptor surface.
- [`handler-arity.md`](handler-arity.md) — the cross-server registry-shape divergence; the two registries this manifest projects differ in handler arity but share the descriptor slots this gate reads.

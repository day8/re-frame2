# `descriptor-manifest` — MCP tool-descriptor manifest generator + drift-check

> **Type:** Reference (`tools/mcp-base/spec/`)
> The shared, platform-agnostic serialiser and drift-check that lets both MCP servers generate and validate `tool-descriptors.edn` from their registries.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`egress.md`](egress.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md).

## The problem it solves

Each MCP server's registry is the source for its `tools/list` surface. The committed manifest is a generated governance projection of that registry and its deterministic descriptor composition.

A generated, CI-guarded manifest prevents the drift the way the keystone's `spec/api-manifest.edn` prevents public-API drift: the committed `tool-descriptors.edn` is regenerated in memory and compared; any difference fails the gate.

## Scope

`descriptor-manifest` owns:

- The **catalogue-row projection** (`descriptor->row`): the stable shape one registry descriptor maps to.
- The **deterministic, byte-stable, LF-pinned EDN serialiser** (`render-edn`) — one tool row per line for surgical diffs.
- The **drift-check** (`check`): regenerate-in-memory vs committed, with added, removed, and row-level changed details.
- The **drift-report formatter** (`drift-report-lines`, `row-slot-deltas`, `governed-slots`): a pure, shared diagnostic body; consumers supply only their command-specific wording and I/O.
- `build-rows` / `build-manifest` — the small assembly helpers between the two.

`descriptor-manifest` does NOT own:

- **Reading the registry.** Each server's generator reads its OWN registry on its OWN platform (story-mcp on the JVM, pair-mcp under Node — its registry is CLJS-only) and passes the descriptor seq in. The base never names a registry.
- **File I/O.** Each server's generator does its own `spit`/`slurp` (JVM) or `fs.writeFileSync`/`readFileSync` (Node). The base is pure data-in / string-out — `drift-report-lines` returns strings; the generator prints them on its own stdout/stderr and owns the process exit code.
- **Per-server wording.** The two strings that legitimately differ per server — the `Regenerate with: <command>` hint and the missing-file header (which names each server's own regenerate command) — are passed INTO `drift-report-lines` by the generator; the shared body (added / removed / changed / malformed) is byte-identical across servers. The OK-path "in sync" wording likewise stays generator-side.
- **CI wiring.** Each check is wired into the workflow that already has the right toolchain (story-mcp's JVM job; pair-mcp's Node + shadow-cljs job).

## The manifest row shape

```clojure
{:name             "<wire-name>"
 :description      "<one-line semantics>"
 :input-keys       ["<sorted inputSchema property keys, as strings>"]
 :gated-input-keys ["<sorted subset of :input-keys the default profile gates off>"]
 :required         ["<sorted inputSchema :required keys, as strings>"]
 :output?          <bool — does the tool declare an outputSchema?>
 :annotations      ["<sorted annotation-hint keys, as strings>"]
 :typicalTokens    <int response-payload token hint, or nil>}
```

`:required` and `:typicalTokens` guard live API-semantics facets:

- **`:required`** — the SORTED subset of `:input-keys` the descriptor's `:inputSchema :required` marks mandatory (empty when none). Surfaces an argument silently flipping required↔optional — a contract change a `:input-keys`-only gate (which sees only that the key *exists*) would miss. **This subset relation is ENFORCED, not assumed.** `:input-keys` (from `:inputSchema :properties`) and `:required` (from `:inputSchema :required`) are read from independent descriptor sources, so the raw shape does not force the relation. `descriptor->row` rejects a descriptor whose required keys are not a subset of its property keys — it throws an `ex-info` (`:tool` / `:required` / `:input-keys` / `:missing`) rather than emitting a self-inconsistent row (`:input-keys ["known"]` with `:required ["missing"]`) that would bless a mandatory argument the advertised input surface never declares and break the default/gate-open surface derivation.
- **`:typicalTokens`** — the integer response-payload token-budget hint AI clients read to pick size-conscious args (`max-tokens` / `cache` / `cursor`). `nil` when a tool declares no hint (forward-compatible; the slot still renders so every row shares one shape). Surfaces a token-budget hint drifting.

`:gated-input-keys` models the **two-profile** `tools/list` surface explicitly:

- **`:gated-input-keys`** — the sorted subset of `:input-keys` hidden by the server's default `tools/list` profile. `:input-keys` remains the full gate-open surface, so both profiles are derivable from one row. The gated-key set is static configuration, not a read of a live gate atom, which keeps generation deterministic.

### Why this shape, not the whole descriptor

The full descriptor maps carry deeply-nested JSON-Schema fragments whose exact serialisation (key order inside nested property maps, optional prose) is volatile and not the contract this gate guards. The gate's job is the **catalogue identity**: which tools exist, what they are called, the top-level input-property surface, which of those inputs the default profile gates off, which are required vs optional, whether each declares structured output + annotation hints, and the response-size budget hint. That is precisely the surface that goes stale when a tool is added / removed / renamed, its input surface changes, a gate is added or lifted, an argument flips required↔optional, or a token-budget hint drifts. The full descriptor's WIRE validity is already pinned by the MCP-SDK conformance harness (`tools/mcp-conformance/`); this manifest pins the catalogue identity.

## Surface

| Fn | Signature | Returns |
|---|---|---|
| `descriptor->row` | `[descriptor-map]` / `[descriptor-map gated-keys]` | one stable manifest row |
| `build-rows` | `[descriptors]` / `[descriptors gated-keys]` | sorted-by-`:name` vector of rows |
| `build-manifest` | `[server descriptors]` / `[server descriptors gated-keys]` | `{:meta {:server :tool-count} :tools [rows]}` |
| `render-edn` | `[manifest]` | deterministic, LF-pinned EDN string |
| `check` | `[generated-manifest generated-edn committed-string-or-nil]` | `{:ok? :added :removed :changed (:missing-file?)}` |
| `governed-slots` | *(def)* | the canonical per-tool slot vector (row slots minus `:name`) |
| `row-slot-deltas` | `[old-row new-row]` | seq of `[slot old new]` for the `governed-slots` that differ |
| `drift-report-lines` | `[check-result {:keys [regenerate-line missing-file-line]}]` | vector of printable report lines (pure — no I/O, no exit) |

The optional `gated-keys` arg is the set of input-key strings the server's default `tools/list` profile gates off (story-mcp passes `#{"include-sensitive"}`; pair-mcp uses the empty default). Each row records the intersection with its actual input keys.

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

Each generator reads registry descriptors without consulting the live operator gate. The manifest records the full gate-open input surface plus its default-hidden subset, and includes deterministic universal input splices that are part of `tools/list`:

- **re-frame2-pair-mcp** splices the universal `max-tokens` / `cache` knobs onto every descriptor at `tools/list` time (`tools/descriptors.cljs` — `with-budget-knob` / `with-cache-knob`). The generator applies those SAME composers, in the same order, BEFORE projecting, so the manifest's `:input-keys` reflect the actual `tools/list` input surface. The splices are deterministic — they consult only the descriptor + the static `registry/cacheable?` predicate, not any operator flag — so the manifest stays byte-stable and config-independent. (Whether `cache` is spliced encodes a tool's read/action cacheability classification; a drift there now trips the gate.) pair-mcp gates NO input key off behind an operator flag (its `eval-cljs` gate is default-ON and gates a whole TOOL, not an input property), so it passes no gated-key set and every pair-mcp row carries `:gated-input-keys []`.
- **story-mcp** bakes `max-tokens` into raw descriptors. It passes the static `registry/gated-input-keys` set so rows distinguish the gate-open surface from the default profile that hides `include-sensitive`.

The committed manifest lives at each artefact's root: `tools/<server>/tool-descriptors.edn`.

## Conformance posture

- **Serialiser determinism + drift semantics + the drift-report body** are pinned on the JVM by `tools/mcp-base/test/re_frame/mcp_base/descriptor_manifest_test.clj` (the algorithm is platform-agnostic `.cljc`). The report tests cover the added / removed / per-slot changed / structurally-broken / missing-file lines `drift-report-lines` emits, so a wording or slot-ordering regression trips the base suite rather than diverging silently between the two servers.
- **The drift-check itself** runs in CI: story-mcp's check rides its JVM job (`clojure -M:gen --check`); pair-mcp's rides its Node + shadow-cljs job (`npm run check:descriptors`). Adding / removing / renaming a tool, or changing a tool's input-key / gated-input-key / required / output? / annotations / typicalTokens surface, turns the respective job red until the manifest is regenerated.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`../../../spec/api-manifest.edn`](../../../spec/api-manifest.edn) + `implementation/scripts/api-manifest/` — the analogous public API manifest machinery.
- [`handler-arity.md`](handler-arity.md) — the cross-server registry-shape divergence; the two registries this manifest projects differ in handler arity but share the descriptor slots this gate reads.

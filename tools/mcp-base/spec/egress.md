# `egress` — cross-MCP `:rf.egress/*` profile vocabulary + resolver

> **Type:** Reference (`tools/mcp-base/spec/`)
> The cross-MCP, framework-runtime-free mirror of the closed six-member `:rf.egress/*` profile enum and its `:rf.size/*` floor (EP-0015 §10). A pure-data table + `profile-size-opts` resolver an MCP server uses to express its egress boundary as a NAMED profile — *which boundary is this?* — without pulling the framework runtime graph into its bundle.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## The problem it solves

EP-0015 graduates the named-egress model: an off-box surface chooses *which boundary is this?* — a named `:rf.egress/*` profile — not *which combination of `:rf.size/*` booleans did I remember?*. The framework owns the authoritative table in `re-frame.projection/profile-size-opts` (`implementation/core`), but that namespace transitively requires the framework runtime graph (`re-frame.elision` → `re-frame.frame` → substrate adapter, …). The MCP servers must NOT pull that graph into their wire-egress decision:

- **re-frame2-pair-mcp** ships as a self-contained Node `server.js` bundle; requiring the runtime graph would bloat it and trip bundle-isolation.
- The profile→opts resolution is a *server-side* decision (it renders the resolved `:rf.size/*` map into the eval form before it crosses the wire), so it cannot defer to the live app's `project-egress`.

This namespace is the pure-data, framework-runtime-free mirror of the six-member closed profile enum and its `:rf.size/*` floor. It is pure data over the keys [`vocab`](vocab.md) already owns (`:rf.size/include-sensitive?` / `:rf.size/include-large?` / `:rf.size/include-digests?`). No transport, no runtime, no framework dep — it loads identically into the JVM (story-mcp) and CLJS (re-frame2-pair-mcp).

## Scope

`egress` owns:

- `profiles` — the closed six-member `:rf.egress/*` set.
- `profile->size-opts` — the profile → `:rf.size/*` opt-set table.
- `profile-size-opts` — the resolver (`[profile] → opt-set | nil`).

`egress` does NOT own:

- **The authoritative table.** `re-frame.projection/profile-size-opts` (`implementation/core`) is the single source of truth; this is a MIRROR pinned byte-identical to it by the conformance gate (below).
- **Applying the floor.** Walking a value against the resolved `:rf.size/*` opt-set is the framework's `project-egress` job; this namespace only RESOLVES the named profile to the opt-set the server splices into its eval form.

## The six profiles (EP-0015 §10, CLOSED enum)

| Profile | `:rf.size/*` floor |
|---|---|
| `:rf.egress/off-box-observability` | sensitive redact, large elide, no digests |
| `:rf.egress/off-box-tool`          | sensitive redact, large elide, digests ON (structural indicators) |
| `:rf.egress/local-redacted`        | sensitive redact, large elide, no digests |
| `:rf.egress/local-raw`             | sensitive AND large pass through |
| `:rf.egress/ssr-hydration`         | sensitive redact, large elide, no digests |
| `:rf.egress/public-error`          | sensitive redact, large elide, no digests |

- `:rf.egress/off-box-tool` is the only off-box profile that turns `:rf.size/include-digests?` ON — the §10 "include structural indicators / counters so the tool can reason about shape without seeing content" clause. It is the MCP servers' DEFAULT off-box boundary.
- `:rf.egress/local-raw` is the only profile that opts sensitive AND large back in (the trusted-local, operator-opt-in boundary, e.g. `--allow-sensitive-reads`).

The opt-set keys are `vocab/include-sensitive-opt` / `vocab/include-large-opt` / `vocab/include-digests-opt` (the `:rf.size/include-*?` keywords [`vocab`](vocab.md) defines), so a rename of those keys is a one-edit change here.

## Surface

| Fn / def | Signature | Returns |
|---|---|---|
| `profiles` | (def) | the closed `#{:rf.egress/…}` six-member set |
| `profile->size-opts` | (def) | `{profile → {:rf.size/include-*? bool}}` table |
| `profile-size-opts` | `[profile]` | the `:rf.size/*` floor map, or `nil` for an unknown / absent profile |

`profile-size-opts` returns `nil` (not a permissive default) for an unknown or `nil` profile — the closed enum means a caller never silently gets a permissive walk from a typo'd profile name.

## Determinism + drift protection

- **Pure-data, cross-platform.** No transport, no runtime, no framework dep; loads identically into JVM (story-mcp) and CLJS (re-frame2-pair-mcp).
- **Pinned to the framework table.** The cross-MCP conformance gate `tools/mcp-conformance/wire-vocab/test/re_frame/mcp_conformance/egress_profile_test.clj` loads BOTH the framework table (`re-frame.projection/profile-size-opts` over `re-frame.projection/profiles`) and this mirror, and asserts (1) the profile NAME sets are identical and (2) every profile resolves to the SAME `:rf.size/*` floor in both. A profile added / renamed in the framework, or an opt-set change (e.g. flipping `off-box-tool`'s digests), that does not also land here fails the gate. So the off-box-tool default the MCP servers ship is provably the framework's off-box-tool floor.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`vocab.md`](vocab.md) — the `:rf.size/*` opt keys this table's values are keyed by.
- [`tools/mcp-conformance/wire-vocab/`](../../mcp-conformance/wire-vocab/) — the JVM-side gate that pins this mirror byte-identical to the framework table.
- [`/spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md) — EP-0015, the named-egress model this namespace mirrors for the MCP wire.

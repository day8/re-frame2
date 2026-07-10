# `egress` — cross-MCP `:rf.egress/*` profile vocabulary + resolver

> **Type:** Reference (`tools/mcp-base/spec/`)
> The cross-MCP, framework-runtime-free mirror of the closed six-member `:rf.egress/*` profile enum and its `:rf.size/*` floor (EP-0015 §10). A pure-data table + `profile-size-opts` resolver an MCP server uses to express its egress boundary as a NAMED profile — *which boundary is this?* — without pulling the framework runtime graph into its bundle.

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## The problem it solves

EP-0015 graduates the named-egress model: an off-box surface chooses *which boundary is this?* — a named `:rf.egress/*` profile — not *which combination of `:rf.size/*` booleans did I remember?*. The framework owns the authoritative table in `re-frame.projection/profile-size-opts` (`implementation/core`), but that namespace transitively requires the framework runtime graph. The shared base must remain independent of that graph:

- **re-frame2-pair-mcp** renders the resolved opts into an nREPL eval form without adding the framework graph to its Node server bundle.
- **story-mcp** applies the same opts in-process. The pure mirror keeps these two host paths aligned.

This namespace is the pure-data, framework-runtime-free mirror of the six-member closed profile enum and its `:rf.size/*` floor. It is pure data over the keys [`vocab`](vocab.md) already owns (`:rf.size/include-sensitive?` / `:rf.size/include-large?` / `:rf.size/include-digests?`). No transport, no runtime, no framework dep — it loads identically into the JVM (story-mcp) and CLJS (re-frame2-pair-mcp).

## Scope

`egress` owns:

- `profiles` — the closed six-member `:rf.egress/*` set.
- `profile->size-opts` — the profile → `:rf.size/*` opt-set table.
- `profile-size-opts` — the resolver (`[profile] → opt-set | nil`).
- `mcp-tool-profile` — the shared MCP-tool posture mapping (`[sensitive-reads-allowed?] → :rf.egress/off-box-tool | :rf.egress/local-raw`). The pure two-value `if` both MCP servers' direct-read surfaces used to duplicate, centralized here so they cannot drift.

`egress` does NOT own:

- **The authoritative table.** `re-frame.projection/profile-size-opts` (`implementation/core`) is the single source of truth; this is a MIRROR pinned value-for-value to it by the conformance gate (below).
- **Applying the floor.** This namespace only resolves a profile. Consumers pass the result to the appropriate framework boundary (`project-egress` or `elide-wire-value`), either in-process or through pair-mcp's eval form.

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
| `mcp-tool-profile` | `[sensitive-reads-allowed?]` | `:rf.egress/off-box-tool` (false) or `:rf.egress/local-raw` (true) |

`profile-size-opts` returns `nil` (not a permissive default) for an unknown or absent profile. Callers choose profiles from the closed `profiles` set; this resolver does not validate or throw for them.

`mcp-tool-profile` is the pure posture→profile mapping the two MCP tool servers (story-mcp, re-frame2-pair-mcp) share on their direct-read surfaces: `false ⇒ :rf.egress/off-box-tool` (the default MCP/AI tool wire), `true ⇒ :rf.egress/local-raw` (the trusted-local operator's deliberate raw read). It performs NO permission check — the `sensitive-reads-allowed?` boolean is produced by each server's own operator-launch gate + per-call `:include-sensitive` opt-in, and each consumer calls this fn only AFTER that gate. Centralizing it here (rf2-54y369) removes the duplicated `if` each server previously carried (`story-mcp tools.egress/posture->profile`, `pair-mcp tools.elision/posture->profile`).

## Determinism + drift protection

- **Pure-data, cross-platform.** No transport, no runtime, no framework dep; loads identically into JVM (story-mcp) and CLJS (re-frame2-pair-mcp).
- **Pinned to the framework table.** The cross-MCP conformance gate `tools/mcp-conformance/wire-vocab/test/re_frame/mcp_conformance/egress_profile_test.clj` loads BOTH the framework table (`re-frame.projection/profile-size-opts` over `re-frame.projection/profiles`) and this mirror, and asserts (1) the profile NAME sets are identical and (2) every profile resolves to the SAME `:rf.size/*` floor in both. A profile added / renamed in the framework, or an opt-set change (e.g. flipping `off-box-tool`'s digests), that does not also land here fails the gate. So the off-box-tool default the MCP servers ship is provably the framework's off-box-tool floor.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`vocab.md`](vocab.md) — the `:rf.size/*` opt keys this table's values are keyed by.
- [`tools/mcp-conformance/wire-vocab/`](../../mcp-conformance/wire-vocab/) — the JVM-side gate that pins this mirror equal to the framework table.
- [`/spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md) — EP-0015, the named-egress model this namespace mirrors for the MCP wire.

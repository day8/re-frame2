# `egress` — cross-MCP `:rf.egress/*` profile vocabulary

> **Type:** Reference (`tools/mcp-base/spec/`)
> The cross-MCP, framework-runtime-free mirror of the closed six-member `:rf.egress/*` profile enum (EP-0015 §10): the profile NAME SET plus the one posture→profile mapping the MCP servers share, so a server can express its egress boundary as a NAMED profile — *which boundary is this?* — without pulling the framework runtime graph into its bundle. The server names the profile; the framework's `project-egress` resolves it to its `:rf.egress/*` floor app-side (rf2-kuky.88).

This doc is one of thirteen per-namespace contracts indexed from [`README.md`](README.md). See also: [`vocab.md`](vocab.md), [`sensitive.md`](sensitive.md), [`elision.md`](elision.md), [`args.md`](args.md), [`diff-encode.md`](diff-encode.md), [`section-grouping.md`](section-grouping.md), [`dedup.md`](dedup.md), [`overflow.md`](overflow.md), [`cap.md`](cap.md), [`cursor.md`](cursor.md), [`envelope.md`](envelope.md), [`descriptor-manifest.md`](descriptor-manifest.md).

## The problem it solves

EP-0015 graduates the named-egress model: an off-box surface chooses *which boundary is this?* — a named `:rf.egress/*` profile — not *which combination of `:rf.egress/*` booleans did I remember?*. The framework owns the authoritative resolution — `re-frame.projection/profile-size-opts` behind the `project-egress` door (`implementation/core`) — but that namespace transitively requires the framework runtime graph. The shared base must remain independent of that graph:

- **re-frame2-pair-mcp** renders a PROFILE NAME into an nREPL eval form; the app-side `project-egress` resolves it, so the Node server bundle never gains the framework graph.
- **story-mcp** names the same profile in-process. Sharing the name set and the posture→profile mapping keeps the two host paths aligned.

This namespace carries the profile NAME SET and the posture→profile mapping — and NOT a resolution table. rf2-kuky.88 deleted the pure-data mirror of the framework's `:rf.egress/*` floor (`profile->size-opts` / `profile-size-opts`): a server that names a profile has no reason to resolve it, and the mirror was a second place the §10 default-behaviour table could drift. No transport, no runtime, no framework dep — it loads identically into the JVM (story-mcp) and CLJS (re-frame2-pair-mcp).

## Scope

`egress` owns:

- `profiles` — the closed six-member `:rf.egress/*` set.
- `mcp-tool-profile` — the shared MCP-tool posture mapping (`[sensitive-reads-allowed?] → :rf.egress/off-box-tool | :rf.egress/local-raw`). The pure two-value `if` both MCP servers' direct-read surfaces used to duplicate, centralized here so they cannot drift.

`egress` does NOT own:

- **The `:rf.egress/*` floor table.** `re-frame.projection/profile-size-opts` (`implementation/core`) is the single source of truth, applied by `re-frame.core/project-egress` at the app-side door; this namespace carries no copy of it (rf2-kuky.88).
- **Resolving or applying the floor.** This namespace only names a profile. Consumers hand the name to `project-egress`, either in-process (story-mcp) or through pair-mcp's eval form.

## The six profiles (EP-0015 §10, CLOSED enum)

| Profile |
|---|
| `:rf.egress/off-box-observability` |
| `:rf.egress/off-box-tool`          |
| `:rf.egress/local-redacted`        |
| `:rf.egress/local-raw`             |
| `:rf.egress/ssr-hydration`         |
| `:rf.egress/public-error`          |

The `:rf.egress/*` floor each name resolves to lives in the framework (`re-frame.projection`'s §10 default-behaviour table, per [`/spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md)) and is not repeated here. The one distinction a server needs when choosing a name:

- `:rf.egress/off-box-tool` is the MCP servers' DEFAULT off-box boundary — the §10 "include structural indicators so the tool can reason about shape without seeing content" clause. It does not carry digests by default; a digest is the explicit `:rf.egress/include-digests? true` override.
- `:rf.egress/local-raw` is the trusted-local, operator-opt-in boundary (e.g. `--allow-sensitive-reads`) that opts sensitive AND large back in.

## Surface

| Fn / def | Signature | Returns |
|---|---|---|
| `profiles` | (def) | the closed `#{:rf.egress/…}` six-member set |
| `mcp-tool-profile` | `[sensitive-reads-allowed?]` | `:rf.egress/off-box-tool` (false) or `:rf.egress/local-raw` (true) |

`mcp-tool-profile` is the pure posture→profile mapping the two MCP tool servers (story-mcp, re-frame2-pair-mcp) share on their direct-read surfaces: `false ⇒ :rf.egress/off-box-tool` (the default MCP/AI tool wire), `true ⇒ :rf.egress/local-raw` (the trusted-local operator's deliberate raw read). It performs NO permission check — the `sensitive-reads-allowed?` boolean is produced by each server's own operator-launch gate + per-call `:include-sensitive` opt-in, and each consumer calls this fn only AFTER that gate. Centralizing it here (rf2-54y369) removes the duplicated `if` each server previously carried (`story-mcp tools.egress/posture->profile`, `pair-mcp tools.elision/posture->profile`).

## Determinism + drift protection

- **Pure-data, cross-platform.** No transport, no runtime, no framework dep; loads identically into JVM (story-mcp) and CLJS (re-frame2-pair-mcp).
- **Pinned to the framework name set.** The cross-MCP conformance gate `tools/mcp-conformance/wire-vocab/test/re_frame/mcp_conformance/egress_profile_test.clj` asserts (1) `profiles` equals `re-frame.projection/profiles` and (2) `mcp-tool-profile` returns, on both postures, a name the framework's `project-egress` door resolves. A profile added / renamed in the framework that does not also land here fails the gate. The per-profile floor pins went with the mirror table (rf2-kuky.88): the floors are the framework's own to pin, and `implementation/core` pins them.

## See also

- [`README.md`](README.md) — the per-namespace index this doc is part of.
- [`vocab.md`](vocab.md) — the `:rf.egress/*` opt keys (`include-*-opt`) a server relays inward to `project-egress`.
- [`tools/mcp-conformance/wire-vocab/`](../../mcp-conformance/wire-vocab/) — the JVM-side gate that pins this name set equal to the framework's `profiles`.
- [`/spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md) — EP-0015, the named-egress model this namespace mirrors for the MCP wire.

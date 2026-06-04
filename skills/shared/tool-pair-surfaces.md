# Tool-Pair surfaces — shared enumeration

The canonical list of the **consumer-facing Tool-Pair surfaces** re-frame2 exposes to tools (pair, pair-retro, Xray, …). This is the single home for the surface enumeration that several skills otherwise restate from memory and let drift out of sync. The authoritative contract is [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md); this leaf is the skills-corpus pointer at it, not a second source of truth.

When a finding routes **upstream to `re-frame2`** (the friction is in the framework's Tool-Pair contract, not the consuming tool or the author's code), name the specific surface here rather than gesturing at "the contract."

## The surfaces

- **The trace stream** — `re-frame.trace.tooling/register-listener!` registers a listener over the live trace-event bus; `trace-buffer` is the retain-N replay buffer. The default multi-tool posture is parallel listeners under distinct ids (per [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) §Listener ordering).
- **The registrar query API** — introspect what is registered: `registrations`, `handler-meta`, `frame-ids`, `frame-meta`, `machines`, `machine-meta`, `app-schemas`, `sub-cache`. (See [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md) and [`spec/002-Frames.md` §The public registrar query API](../../spec/002-Frames.md#the-public-registrar-query-api).) `sub-cache` is also a **direct value read** — when egressed off-box it carries the fail-closed wire contract under §Direct reads below, not just introspection-metadata semantics.
- **Epoch history + restore** — the per-frame epoch ring buffer `(rf/epoch-history frame-id)`, the assembled-record listener `register-epoch-listener!`, and first-class time-travel `(rf/restore-epoch frame-id epoch-id)`. State injection bypassing the dispatch loop is `(rf/reset-frame-db! frame-id new-db)`.
- **Schema reflection** — `app-schemas` returns the registered schemas for reflective validation / shape inspection.
- **Source-coord annotation** — `data-rf2-source-coord` bridges live DOM elements back to source `{:ns :line :file}` (with re-com's `data-rc-src` as a fallback).
- **Direct reads (`app-db` / `sub-cache` / run a sub)** — `(rf/app-db-value frame-id)` reads a frame's current `app-db` value, `(rf/sub-cache frame-id)` reads the cached-sub map, `(rf/compute-sub query-v db-value)` runs a sub against a db value. These are the **raw runtime** reads. The **Tool-Pair wire surfaces** that egress them off-box to an AI/MCP client are `get-app-db`, `get-path`, `snapshot` (wraps both `:app-db` and `:sub-cache`), and `sub-cache` — and they carry a MUST-level egress contract (next bullet), because a direct read bypasses trace redaction.

> **Direct-read egress is fail-closed (MUST).** Direct reads bypass the trace surface where `:sensitive?` stamping / `redact-interceptor` operate, so any pair-shaped tool that egresses `get-app-db` / `get-path` / `snapshot` / `sub-cache` off-box **MUST** route the returned value through `rf/elide-wire-value` (the single normative emission site) before it crosses the wire. The AI/MCP boundary — not `app-db` itself — is the trust boundary. Off-box defaults are **suppress-by-default**: `:rf.size/include-sensitive?` and `:rf.size/include-large?` both default `false`, so a sensitive slot returns `:rf/redacted` and an oversize value returns `:rf.size/large-elided`; when both match, **sensitive drop wins** (the size marker would leak `:path` / `:bytes` / `:digest`). Re-enabling is opt-in only: the cross-MCP boot gate `--allow-sensitive-reads` (default **OFF**) plus the per-call `include-sensitive` arg. Dropped values surface as the `:dropped-sensitive` / `:elided-large` indicators. The walker runs **app-side** (it reads the frame's `[:rf/runtime :elision …]` registries), never in the MCP host. Full contract: [`spec/Tool-Pair.md` §Direct-read privacy posture for `sub-cache` and `get-path`](../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) (and the security framing in [`spec/Security.md` §Direct-read privacy posture](../../spec/Security.md#direct-read-privacy-posture-for-sub-cache-and-get-path)).

The abbreviated five-name version some skills carry — `register-listener!`, `register-epoch-listener!`, `epoch-history`, `restore-epoch`, `app-schemas` (plus source-coord annotation) — is the upstream-routing subset of the above. The fuller list lives in [`re-frame2-pair/README.md`](../re-frame2-pair/README.md).

## Supersedes re-frame-10x

These surfaces are **first-class in re-frame2 itself** — v2 tooling does not depend on, recommend, or fall back to `re-frame-10x`. Where v1 tooling read 10x's epoch buffer it now reads `(rf/epoch-history frame-id)`; where it stepped through 10x navigation it now calls `(rf/restore-epoch frame-id epoch-id)`; where it detected a 10x trace callback it now registers its own listener (multi-tool coexistence is the expected default). Consuming skills may keep their own audience-specific framing of this claim (dependency note / anti-pattern / structural successor / migration mechanics) and cite this paragraph for the underlying fact.

## Consumers

- [`re-frame2-pair`](../re-frame2-pair/README.md) — the live-runtime pair tool; carries the fullest surface list.
- [`re-frame2-pair-retro`](../re-frame2-pair-retro/SKILL.md) and [`re-frame2-xray`](../re-frame2-xray/README.md) — name a surface from this leaf when routing an upstream finding.
- [`retro-protocol.md`](retro-protocol.md) §Layer-routing rules — "upstream `re-frame2`" findings route to a surface enumerated here.

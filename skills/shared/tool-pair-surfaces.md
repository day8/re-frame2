# Tool-Pair surfaces — shared enumeration

The canonical list of the **consumer-facing Tool-Pair surfaces** re-frame2 exposes to tools (pair, pair-retro, Causa, …). This is the single home for the surface enumeration that several skills otherwise restate from memory and let drift out of sync. The authoritative contract is [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md); this leaf is the skills-corpus pointer at it, not a second source of truth.

When a finding routes **upstream to `re-frame2`** (the friction is in the framework's Tool-Pair contract, not the consuming tool or the author's code), name the specific surface here rather than gesturing at "the contract."

## The surfaces

- **The trace stream** — `re-frame.trace.tooling/register-listener!` registers a listener over the live trace-event bus; `trace-buffer` is the retain-N replay buffer. The default multi-tool posture is parallel listeners under distinct ids (per [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) §Listener ordering).
- **The registrar query API** — introspect what is registered: `registrations`, `handler-meta`, `frame-ids`, `frame-meta`, `machines`, `machine-meta`, `app-schemas`, `sub-cache`. (See [`spec/Tool-Pair.md`](../../spec/Tool-Pair.md) and [`spec/002-Frames.md` §The public registrar query API](../../spec/002-Frames.md#the-public-registrar-query-api).)
- **Epoch history + restore** — the per-frame epoch ring buffer `(rf/epoch-history frame-id)`, the assembled-record listener `register-epoch-listener!`, and first-class time-travel `(rf/restore-epoch frame-id epoch-id)`. State injection bypassing the dispatch loop is `(rf/reset-frame-db! frame-id new-db)`.
- **Schema reflection** — `app-schemas` returns the registered schemas for reflective validation / shape inspection.
- **Source-coord annotation** — `data-rf2-source-coord` bridges live DOM elements back to source `{:ns :line :file}` (with re-com's `data-rc-src` as a fallback).
- **Read `app-db` / run a sub** — `(rf/get-frame-db frame-id)` reads a frame's current `app-db` value; `(rf/compute-sub query-v db-value)` runs a sub against a db value.

The abbreviated five-name version some skills carry — `register-listener!`, `register-epoch-listener!`, `epoch-history`, `restore-epoch`, `app-schemas` (plus source-coord annotation) — is the upstream-routing subset of the above. The fuller list lives in [`re-frame2-pair/README.md`](../re-frame2-pair/README.md).

## Supersedes re-frame-10x

These surfaces are **first-class in re-frame2 itself** — v2 tooling does not depend on, recommend, or fall back to `re-frame-10x`. Where v1 tooling read 10x's epoch buffer it now reads `(rf/epoch-history frame-id)`; where it stepped through 10x navigation it now calls `(rf/restore-epoch frame-id epoch-id)`; where it detected a 10x trace callback it now registers its own listener (multi-tool coexistence is the expected default). Consuming skills may keep their own audience-specific framing of this claim (dependency note / anti-pattern / structural successor / migration mechanics) and cite this paragraph for the underlying fact.

## Consumers

- [`re-frame2-pair`](../re-frame2-pair/README.md) — the live-runtime pair tool; carries the fullest surface list.
- [`re-frame2-pair-retro`](../re-frame2-pair-retro/SKILL.md) and [`re-frame2-causa`](../re-frame2-causa/README.md) — name a surface from this leaf when routing an upstream finding.
- [`retro-protocol.md`](retro-protocol.md) §Layer-routing rules — "upstream `re-frame2`" findings route to a surface enumerated here.

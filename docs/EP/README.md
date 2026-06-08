# Enhancement Proposals (EPs)

Design proposals for re-frame2.

EPs are *proposals*, not normative specification. The normative artefact remains [`spec/`](https://github.com/day8/re-frame2/tree/main/spec). An EP graduates into `spec/` + implementation beads once accepted. Each EP carries a stable PEP-style number (`EP-0001`, …), a `Status:` line, and standard sections (Abstract, Motivation, Relationships, Specification, …); dependencies live in each EP's `Relationships` section.

## Index

| EP       | Title | Status | Summary |
|----------|-------|--------|---------|
| [EP-0001](EP-0001-frame-partitions.md) | Frame App/Runtime Partitions | proposal | A frame owns two durable partitions — user-owned **app-db** (`:db`) and framework-owned **runtime-db** (`:rf.db/runtime`) — committed coherently by one cascade, removing the footgun where a fresh `:db` return clobbers runtime state. |
| [EP-0002](EP-0002-frame-target-resolution.md) | Explicit Frame Target Resolution | proposal | Remove the ambient `:rf/default` fallback; frame-scoped operations resolve their target from explicit frame context, and missing context fails instead of touching the wrong frame. |
| [EP-0003](EP-0003-resource-queries.md) | Resource Queries | proposal | An optional `day8/re-frame2-resources` artefact for declarative server-state — the re-frame2 answer to TanStack Query / RTK Query / SWR — with resource identity, caching, invalidation, lifecycle/GC, and route + SSR preload. |
| [EP-0004](EP-0004-subscription-inputs.md) | Parametric Subscription Inputs | final | Restore v1's two-function `reg-sub` shape via input functions that return a vector of query vectors, keeping `:<-` as static-input sugar and dependencies pure, inspectable, JVM-runnable, and Xray-visible. |
| [EP-0005](EP-0005-machine-data-schema.md) | Machine `:data` Schema | final | Rename `reg-machine`'s `:schema` key to `:data-schema` for the machine's `:data` context, close the schema→marks redaction bridge, switch machines-viz to declared-over-inferred Context shape, and document XState v5 parity. |

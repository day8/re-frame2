# Enhancement Proposals (EPs)

Design proposals for re-frame2. Each EP states a **problem**, the proposed **re-frame2 solution**, the **options considered**, concrete **examples**, and an **implementation / bead plan**.

EPs are *proposals*, not normative specification. The normative artefact remains [`spec/`](https://github.com/day8/re-frame2/tree/main/spec). The lifecycle is:

> exploratory findings (local `ai/findings/`) → **EP** (synthesised proposal, here) → accepted EP graduates into `spec/` + implementation beads.

## Conventions

- One EP per file, **flat** in this directory, kebab-case named, each self-contained.
- Each EP carries a stable PEP-style **number** (`EP-0001`, `EP-0002`, …) assigned in `Created`-date order (oldest = `EP-0001`). The number lives in the doc's header metadata (`Number:`), its H1 title, and the index below. Numbers are stable and never reused; the descriptive filename is kept (the number does not rename the file).
- Header metadata block: `Number`, `Status` (`proposal` / `accepted` / `superseded`), `Type`, `Date`, `Created`, `Author`, `Target Artifact`, and `Requires` (dependency EPs/specs). Inter-EP dependencies live **in each EP** — its `Requires:` header and a `Relationships` section — not here.
- Standard sections, modelled on [`subscription-inputs.md`](subscription-inputs.md): Abstract, Motivation, Goals/Non-Goals, Relationships, Specification, Rationale / Runtime Semantics, Backwards Compatibility, Migration, Rejected Ideas, Open Issues, Recommendation.
- Add new EPs to the table below (next free number) and to the `EP` section of [`mkdocs.yml`](https://github.com/day8/re-frame2/blob/main/mkdocs.yml).

## Index

| EP | Title | Status | Summary |
|----|-------|--------|---------|
| [EP-0001](app-db-runtime-partition.md) | Frame App/Runtime Partitions | proposal | A frame owns two durable partitions — user-owned **app-db** (`:db`) and framework-owned **runtime-db** (`:rf.db/runtime`) — committed coherently by one cascade, removing the footgun where a fresh `:db` return clobbers runtime state. |
| [EP-0002](frame-target-resolution.md) | Explicit Frame Target Resolution | proposal | Remove the ambient `:rf/default` fallback; frame-scoped operations resolve their target from explicit frame context, and missing context fails instead of touching the wrong frame. |
| [EP-0003](resource-queries.md) | Resource Queries | proposal | An optional `day8/re-frame2-resources` artefact for declarative server-state — the re-frame2 answer to TanStack Query / RTK Query / SWR — with resource identity, caching, invalidation, lifecycle/GC, and route + SSR preload. |
| [EP-0004](subscription-inputs.md) | Parametric Subscription Inputs | proposal | Restore v1's two-function `reg-sub` shape via input functions that return a vector of query vectors, keeping `:<-` as static-input sugar and dependencies pure, inspectable, JVM-runnable, and Xray-visible. |
| [EP-0005](machine-data-schema.md) | Machine `:data` Schema | proposal | Rename `defmachine`'s `:schema` key to `:data-schema` for the machine's `:data` context, close the schema→marks redaction bridge, switch machines-viz to declared-over-inferred Context shape, and document XState v5 parity. |

# Enhancement Proposals (EPs)

Design proposals for re-frame2. Each EP states a **problem**, the proposed **re-frame2 solution**, the **options considered**, concrete **examples**, and an **implementation / bead plan**.

EPs are *proposals*, not normative specification. The normative artefact remains [`spec/`](https://github.com/day8/re-frame2/tree/main/spec). The lifecycle is:

> exploratory findings (local `ai/findings/`) → **EP** (synthesised proposal, here) → accepted EP graduates into `spec/` + implementation beads.

## Conventions

- One EP per file, **flat** in this directory, kebab-case named, each self-contained.
- Front matter: a one-line `Status:` (`proposal` / `accepted` / `superseded`), a `Date:`, and a `Related:` list of guide/spec links.
- Add new EPs to the table below and to the `EP` section of [`mkdocs.yml`](https://github.com/day8/re-frame2/blob/main/mkdocs.yml).

## Index

| EP | Status | Summary |
|----|--------|---------|
| [App/Runtime Partition](app-db-runtime-partition.md) | proposal | A frame owns two durable partitions — user-owned **app-db** (`:db`) and framework-owned **runtime-db** (`:rf.db/runtime`) — committed coherently by one cascade. Removes the footgun where an ordinary `:db` return silently clobbers machine / routing / elision / SSR runtime state, while preserving one coherent app+runtime snapshot for time-travel and SSR. |
| [Explicit Frame Target Resolution](frame-target-resolution.md) | proposal | Remove ambient `:rf/default` fallback. Frame-scoped operations must resolve their target from explicit frame context (frame id/handle, provider, cascade, lexical binding, or tool/session target), and missing context fails instead of mutating or reading the wrong frame. |
| [Parametric Subscription Inputs](subscription-inputs.md) | proposal | Restore the useful part of re-frame v1's two-function `reg-sub` shape by adding data-returning input functions. Keep `:<-` as static-input sugar while making query-vector-parametric subscription dependencies pure, inspectable, JVM-runnable, and Xray-visible. |
| [Resource Queries](resource-queries.md) | proposal | An optional `day8/re-frame2-resources` artefact for declarative server-state — the re-frame2 answer to TanStack Query / RTK Query / SWR / `shipclojure/re-frame-query`. Resource identity, caching, staleness, dedupe, tag invalidation, active-owner lifecycle/GC, route + SSR preload, built on managed-HTTP (Spec 014) and the runtime partition. |

## Relationships

The **App/Runtime Partition** EP is foundational: **Resource Queries** stores its cache in the framework-owned runtime partition (`:rf.runtime/resources`) that EP introduces, so the partition should land (or its key vocabulary be fixed) before resources rely on it.

The **Explicit Frame Target Resolution** EP is a cross-cutting safety proposal. It should be resolved before large frame-aware features such as resource queries, Xray control surfaces, SSR hydration helpers, and work-ledger tooling harden their public APIs.

The **Parametric Subscription Inputs** EP is a Reactive Substrate proposal. It should be resolved before resource-query, route-model, or Hasura helpers lean on parameterized subscription view models, because it determines whether those helpers use static `:<-`, data-returning input functions, or broader app-db reads.

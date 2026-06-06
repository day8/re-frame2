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
| [Resource Queries](resource-queries.md) | proposal | An optional `day8/re-frame2-resources` artefact for declarative server-state — the re-frame2 answer to TanStack Query / RTK Query / SWR / `shipclojure/re-frame-query`. Resource identity, caching, staleness, dedupe, tag invalidation, active-owner lifecycle/GC, route + SSR preload, built on managed-HTTP (Spec 014) and the runtime partition. |

## Relationships

The **App/Runtime Partition** EP is foundational: **Resource Queries** stores its cache in the framework-owned runtime partition (`:rf.runtime/resources`) that EP introduces, so the partition should land (or its key vocabulary be fixed) before resources rely on it.

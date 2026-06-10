# resources — Spec 016 worked example

A small server-state app — an articles list and an article detail — that
demonstrates re-frame2 **resources** (declarative, cached server-state
reads) composed as proper re-frame2: app-db + events + subs, views
passive, fetches caused. The worked companion to [Spec
016](../../../spec/016-Resources.md) (the EP-0003 read-resource MVP) and
the tutorial in [docs/guide/27-resources.md](../../../docs/guide/27-resources.md).

## What this demonstrates

The four **landed** causal patterns, in one cohesive app:

- **Route-driven page load** — `reg-route` with `:resources` metadata
  (`:resources.app/articles`, `:resources.app/article-detail`). Route entry
  marks each resource active under a `[:route route-id nav-token]` owner and
  ensures it with cause `[:route-entry …]`; route leave releases by token.
- **Event-driven ensure** — `:resources.app/preview-opened` dispatches
  `:rf.resource/ensure` under an app-minted `[:lease …]` owner, with a
  matching `:resources.app/preview-closed` → `:rf.resource/release-owner`
  release path (app leases are app-authoritative).
- **Manual refresh** — the "Refresh" button dispatches
  `:rf.resource/refetch` with a `:cause` and **no** `:owner` (a refresh
  wants fresh data but keeps nothing alive — owner vs cause).
- **Machine-owned resource** — `:resources.app/reader` ensures the article
  under a `[:machine machine-id instance-id]` owner (released on actor
  destroy); the machine stays the semantic workflow.

Plus the read side: views read everything through the **passive**
`[:rf.resource/state …]` sub (and its narrower projections), distinguishing
`:loading?` (first-load skeleton) from `:error` + `:has-data?` from
`:fetching?` (refresh-in-flight) from `:refresh-error` (background-refresh
failure with prior data kept).

**Scope is the fail-closed leak boundary.** Each resource declares the
explicit, auditable `:scope :rf.scope/global` claim — these reads are public
and the same for every user. A user/tenant/locale-scoped read would carry a
scope resolver instead; a missing scope policy is a loud
`:rf.error/resource-missing-scope-policy` at registration.

## Status

Resources is a **post-v1 optional artefact** (`day8/re-frame2-resources`)
and the read-resource runtime has **landed** (EP-0003): `reg-resource`, the
passive `[:rf.resource/*]` subs, route `:resources` metadata, and the causal
`:rf.resource/ensure` / `:rf.resource/refetch` / `:rf.resource/invalidate-tags`
/ `:rf.resource/release-owner` event bodies are all real and operational. The
four patterns above run live: route entry, an event lease, a manual refresh,
and a machine-owned ensure each cause a real fetch, dedupe in flight, and
flow through the passive status subs.

## Scope of this example — read patterns only

This example demonstrates the **read** side end to end. **Mutations** —
causal WRITEs (`reg-mutation` / `[:rf.mutation/execute …]`) that
invalidate / patch / populate resource entries on success — have also
**landed**, but are **not** demonstrated here to keep the example focused on
the read lifecycle. The mutation surface is covered in
[docs/guide/27-resources.md §Mutations](../../../docs/guide/27-resources.md#mutations--the-causal-write)
and the migration walkthrough at
[migration/from-re-frame-v1/re-frame-query-to-resources.md](../../../migration/from-re-frame-v1/re-frame-query-to-resources.md),
including the **scoped-invalidation** discipline (a write must invalidate
under the same scope its resources were ensured under).

## Deferred — not built here

- **GraphQL** — a deferred later phase, out of the read-resource contract.
  `:rf.http/managed` is the single built-in transport; the lifecycle is
  transport-neutral so a GraphQL transport can plug in later. No GraphQL
  example is built.

## Files

```
resources/
  core.cljs    — resources, routes, events, the reader machine, subs, views, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/resources
```

The watch build emits `main.js` into `out/examples/resources/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared assets it
references under [`../../_shared/`](../../_shared/)) alongside it, then serve
`out/examples/resources/` over HTTP. (`npm run test:examples` does not build
this example — it compiles and serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md); resource contract testing lives in
`implementation/resources/test/` and the conformance fixtures.

## Cross-references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — the normative spec.
- [`docs/guide/27-resources.md`](../../../docs/guide/27-resources.md) — the tutorial.
- [`examples/reagent/resources_ssr/`](../resources_ssr/) — the SSR preload + hydration counterpart.
- [`examples/reagent/managed_http_counter/`](../managed_http_counter/) — the raw `:rf.http/managed` transport resources lower onto.
- [`examples/reagent/routing/`](../routing/) — the routing surface `:resources` metadata extends.

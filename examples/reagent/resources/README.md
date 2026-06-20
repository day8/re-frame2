# resources — Spec 016 worked example

A small server-state app — an articles list and an article detail — that
demonstrates re-frame2 **resources** (declarative, cached server-state
reads) composed as proper re-frame2: app-db + events + subs, views
passive, fetches caused. The worked companion to [Spec
016](../../../spec/016-Resources.md) (the EP-0003 read-resource MVP) and
the guide in [docs/guide/concepts/server-state.md](../../../docs/guide/concepts/server-state.md).

## What this demonstrates

The four **landed** causal patterns, in one cohesive app — all four are
**wired into the articles page UI** and run live:

- **Route-driven page load** — `reg-route` with `:resources` metadata
  (`:resources.app/articles`, `:resources.app/article-detail`). Route entry
  marks each resource active under a `[:route route-id nav-token]` owner and
  ensures it with cause `[:route-entry …]`; route leave releases by token.
- **Event-driven ensure** — the per-row **Preview** button dispatches
  `:resources.app/preview-opened` → `:rf.resource/ensure` under an
  app-minted `[:lease …]` owner; **Close preview** dispatches
  `:resources.app/preview-closed` → `:rf.resource/release-owner` (the
  matching release path app leases require — app leases are
  app-authoritative).
- **Manual refresh** — the "Refresh" button dispatches
  `:rf.resource/refetch` with a `:cause` and **no** `:owner` (a refresh
  wants fresh data but keeps nothing alive — owner vs cause).
- **Machine-owned resource** — the per-row **Open in reader** button starts
  the `:resources.app/reader` machine, whose `:reading` entry ensures the
  article under a `[:machine machine-id instance-id]` owner; **Stop reader**
  emits `[:rf.machine/destroy …]`, which releases that owner. The machine
  stays the semantic workflow.

**No backend ships with the example.** It overrides `:rf.http/managed` with a
per-URL canned stub that delegates to the framework-shipped
`:rf.http/managed-canned-success` (Spec 014 §Testing) — the same reply shape a
live server would produce, so every ensure exercises a real fetch, in-flight
dedupe, and the passive status flow (a 120 ms delay lets the loading skeleton
render before the reply lands). A repeat ensure of an entry still inside its
`:stale-after-ms` window **fresh-skips** (no refetch — `:rf.resource/cache-hit`);
the manual Refresh forces a refetch regardless.

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
four patterns above run live: route entry, an event lease (the Preview
button), a manual refresh, and a machine-owned ensure (the Open-in-reader
button) each cause a real fetch (against the canned stub), dedupe in flight,
and flow through the passive status subs.

## Scope of this example — read patterns only

This example demonstrates the **read** side end to end. **Mutations** —
causal WRITEs (`reg-mutation` / `[:rf.mutation/execute …]`) that
invalidate / patch / populate resource entries on success — have also
**landed**, but are **not** demonstrated here to keep the example focused on
the read lifecycle. The mutation surface is covered in
[docs/guide/concepts/server-state.md §Writes invalidate by tag](../../../docs/guide/concepts/server-state.md#writes-invalidate-by-tag--causally)
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
  core.cljs    — resources, the per-URL canned :rf.http/managed stub, routes,
                 events, the reader machine, subs, views, mount.
  index.html   — minimal host page.
```

The example synthesises its server replies **in-app** via the canned
`:rf.http/managed` stub (it delegates to `:rf.http/managed-canned-success`),
so — unlike `managed_http_counter`, which fetches a real static
`api/inc.json` — there is **no `api/` asset to stage**. The detail route's
per-slug URL (`/api/articles/:slug`) is routed by the stub, not by a static
file tree.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/resources
```

The watch build emits `main.js` into `out/examples/resources/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared assets it
references under [`../../_shared/`](../../_shared/)) alongside it, then serve
`out/examples/resources/` over HTTP. (`npm run test:adapter-smokes` does not build
this example — it compiles and serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).)

## Coverage

The example tree is test-free, but this example's wiring is pinned by
a direct headless CLJS fixture, **`re-frame.resources-example-cljs-test`**
(`implementation/adapters/reagent/test/re_frame/`, run by `npm run test:cljs`).
It requires this example's production `resources.core` and drives the four causal
patterns directly: route-driven page load (the `:resources` route metadata
ensures under a `[:route …]` owner; the view reads passively and settles
`:loaded`), event-driven lease ensure/release (`:resources.app/preview-opened` /
`-closed` under a `[:lease …]` owner), manual refresh as a cause (no owner;
re-fetches a loaded list into `:fetching` keeping prior data), and the reader
machine's start/stop event glue. The machine-owned-resource ENSURE step itself
(the reader's `:reading` `:entry`) is left to the resources artefact runtime
suites — its `[:machine …]` owner is fail-closed bound to a live actor, so
pinning the entry deterministically in a shared headless bundle is brittle; the
generic ensure-under-owner + release-on-destroy mechanics it composes are pinned
in `implementation/resources/test/`. See the [coverage table](../README.md#coverage-level-per-reagent-example).

## Cross-references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — the normative spec.
- [`docs/guide/concepts/server-state.md`](../../../docs/guide/concepts/server-state.md) — the guide.
- [`examples/reagent/resources_ssr/`](../resources_ssr/) — the SSR preload + hydration counterpart.
- [`examples/reagent/managed_http_counter/`](../managed_http_counter/) — the raw `:rf.http/managed` transport resources lower onto.
- [`examples/reagent/routing/`](../routing/) — the routing surface `:resources` metadata extends.
